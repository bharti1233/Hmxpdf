package com.hmx.pdf.office

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shared installer: downloads, verifies and installs the Office engine from the pinned
 * GitHub release. Works for all distribution flavors (play, foss, fdroid) without
 * requiring Google Play Feature Delivery.
 *
 * WorkManager does not expose which constraint deferred a pending row, so pending states
 * are labelled here from the app's own probes (network + free space) — see
 * [OfficeEngineStateMapper]. The OS-level `requiresStorageNotLow` constraint was dropped
 * in favour of a visible preflight: an invisible storage deferral rendered as "Waiting
 * for network…" indefinitely (docs/HMXPDF_OFFICE_ENGINE_DOWNLOAD_AUDIT.md, root cause 1).
 */
class DownloadOfficeEngineInstaller(private val context: Context) : OfficeEngineInstaller {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager = WorkManager.getInstance(context)
    private val _state = MutableStateFlow(OfficeEngine.installedState(context))
    override val state: StateFlow<OfficeEngineState> = _state.asStateFlow()
    override val usesNotification: Boolean = true

    /** Only surface a FAILED work result for installs started in this process's lifetime. */
    @Volatile private var startedHere = false

    /**
     * Failed storage preflight, held as the required bytes so its InsufficientStorage state
     * survives Flow remapping (there is no row yet, so the Flow would otherwise report the
     * installed state). Cleared by the next Download/Retry tap or by uninstall().
     */
    @Volatile private var storageBlock: Long? = null

    init {
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(OfficeEngineDownloadWorker.UNIQUE_NAME).collect { infos ->
                _state.value = map(infos.firstOrNull())
            }
        }
    }

    private fun map(info: WorkInfo?): OfficeEngineState {
        if (!OfficeEngine.isSupported()) return OfficeEngineState.Unsupported
        storageBlock?.let { return OfficeEngineState.InsufficientStorage(it) }
        val pending = info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED
        return OfficeEngineStateMapper.map(
            state = info?.state,
            progress = info?.progress,
            outputError = info?.outputData?.getString(OfficeEngineDownloadWorker.KEY_ERROR),
            connectivity = if (pending) connectivity() else OfficeEngineConnectivity.VALIDATED,
            insufficientStorageBytes = if (pending) missingStorageBytes() else null,
            startedHere = startedHere,
            installedState = OfficeEngine.installedState(context),
        )
    }

    /** Usability of the current default network, probed at mapping time. */
    private fun connectivity(): OfficeEngineConnectivity {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return OfficeEngineConnectivity.UNKNOWN
        val network = cm.activeNetwork ?: return OfficeEngineConnectivity.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return OfficeEngineConnectivity.NONE
        // WorkManager's CONNECTED constraint accepts any INTERNET-capable network, even one
        // the OS has not validated (captive portal) — such a network cannot usefully reach
        // the pinned HTTPS release, so it is reported as waiting too. If the job runs
        // regardless, it fails fast with a clear error instead of creeping.
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return OfficeEngineConnectivity.NONE
        }
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            OfficeEngineConnectivity.VALIDATED
        } else {
            OfficeEngineConnectivity.UNVALIDATED
        }
    }

    /** Bytes the install still needs when free space is short, or null when space is enough. */
    private fun missingStorageBytes(): Long? {
        val bundle = OfficeEngineManifest.bundleForDevice() ?: return null
        val required = OfficeEngineStateMapper.requiredFreeBytes(
            archiveBytes = bundle.sizeBytes,
            alreadyHaveBytes = partBytes(bundle),
            existingEngineBytes = existingEngineBytes(),
        )
        return required.takeIf { it > freeBytes() }
    }

    /** Free internal-storage bytes; unmeasurable space never blocks (the worker fails visibly instead). */
    private fun freeBytes(): Long = try {
        StatFs(OfficeEngineStore.root(context).apply { mkdirs() }.path).availableBytes
    } catch (e: IllegalArgumentException) {
        Long.MAX_VALUE
    }

    /** Resumable `<sha256>.part` bytes of this bundle, if any (the worker resumes them). */
    private fun partBytes(bundle: OfficeEngineManifest.Bundle): Long {
        val part = File(OfficeEngineStore.downloadDir(context), "${bundle.sha256}.part")
        return if (part.isFile) part.length() else 0L
    }

    /**
     * Engine files that must coexist with the new install (a previously installed engine is
     * kept until commit swaps atomically). Excludes resumable parts (counted separately via
     * [partBytes]) and the stale staging tree, which the next attempt wipes before extract.
     */
    private fun existingEngineBytes(): Long {
        val root = OfficeEngineStore.root(context)
        if (!root.exists()) return 0L
        val downloadPath = OfficeEngineStore.downloadDir(context).path
        val stagingPath = OfficeEngineStore.stagingDir(context).path
        return root.walkBottomUp()
            .filter { it.isFile && !it.path.startsWith(downloadPath) && !it.path.startsWith(stagingPath) }
            .sumOf { it.length() }
    }

    override fun install() {
        if (!OfficeEngine.isSupported()) return
        startedHere = true
        storageBlock = null
        _state.value = OfficeEngineState.CheckingConnection
        scope.launch {
            val bundle = OfficeEngineManifest.bundleForDevice() ?: return@launch
            val required = OfficeEngineStateMapper.requiredFreeBytes(
                archiveBytes = bundle.sizeBytes,
                alreadyHaveBytes = partBytes(bundle),
                existingEngineBytes = existingEngineBytes(),
            )
            if (freeBytes() < required) {
                storageBlock = required
                _state.value = OfficeEngineState.InsufficientStorage(required)
                return@launch
            }
            workManager.enqueueUniqueWork(
                OfficeEngineDownloadWorker.UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request(),
            )
        }
    }

    private fun request(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<OfficeEngineDownloadWorker>()
            .setConstraints(
                // Network only: storage is covered by the visible preflight above; an OS
                // storage-low deferral would otherwise surface as a false network wait.
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()

    override fun cancel() {
        workManager.cancelUniqueWork(OfficeEngineDownloadWorker.UNIQUE_NAME)
    }

    override fun uninstall() {
        storageBlock = null
        workManager.cancelUniqueWork(OfficeEngineDownloadWorker.UNIQUE_NAME)
        scope.launch {
            OfficeEngine.removeInstalledEngine(context)
            workManager.pruneWork()
            _state.value = OfficeEngine.installedState(context)
        }
    }

    override fun refresh() {
        storageBlock?.let { _state.value = OfficeEngineState.InsufficientStorage(it) }
            ?: run {
                scope.launch {
                    val info = workManager.getWorkInfosForUniqueWork(OfficeEngineDownloadWorker.UNIQUE_NAME)
                        .get().firstOrNull()
                    _state.value = map(info)
                }
            }
    }
}
