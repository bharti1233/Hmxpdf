package com.hmx.pdf.office

import androidx.work.Data
import androidx.work.WorkInfo

/**
 * Best-effort usability level of the device's default network, probed at mapping time by
 * [DownloadOfficeEngineInstaller]. WorkManager does not expose which constraint deferred a
 * pending row, so pending states are labelled from this probe plus a storage preflight.
 */
enum class OfficeEngineConnectivity { UNKNOWN, NONE, UNVALIDATED, VALIDATED }

/**
 * Honest state labels for WorkManager rows (pure function: unit-tested on the JVM).
 *
 * Never labels a pending (`ENQUEUED`/`BLOCKED`) row "Waiting for network" unless the app's
 * own connectivity probe says the network is unusable — the former behaviour was the
 * "Waiting for network" bug documented in docs/HMXPDF_OFFICE_ENGINE_DOWNLOAD_AUDIT.md.
 *
 * Pending rows: no network or an unvalidated one → Waiting for network; not enough
 * free space (from the visible preflight) → Insufficient storage; otherwise → Queued: the
 * OS scheduler (battery rules, Doze, backoff or an imminent start) owns when the row runs,
 * and WorkManager does not expose which constraint is responsible.
 */
object OfficeEngineStateMapper {

    /**
     * Disk space the install needs beyond the remaining download: the uncompressed engine
     * tree (staging) is written while the compressed archive is still on disk. The true
     * extracted size is not pinned in [OfficeEngineManifest]; LibreOffice's tree is on the
     * order of 150 MB, so this is a conservative cap for tree + slack.
     * ponytail: measure the extracted size of a completed install and shrink this constant
     * when a real number is known.
     */
    const val EXTRACTION_WORKING_BYTES = 256L * 1024 * 1024

    /**
     * Free bytes needed to finish the install: whatever is left to download of the archive
     * plus the extraction working set, plus files that must coexist (a previously installed
     * engine stays until [OfficeEngineStore.commit] swaps atomically). Pure function; tests
     * cover the arithmetic, the free-space comparison happens at the call site against
     * `StatFs.freeBytes`.
     */
    fun requiredFreeBytes(archiveBytes: Long, alreadyHaveBytes: Long, existingEngineBytes: Long): Long =
        (archiveBytes - alreadyHaveBytes).coerceAtLeast(0L) + EXTRACTION_WORKING_BYTES + existingEngineBytes

    fun map(
        state: WorkInfo.State?,
        progress: Data?,
        outputError: String?,
        connectivity: OfficeEngineConnectivity,
        insufficientStorageBytes: Long?,
        startedHere: Boolean,
        installedState: OfficeEngineState,
    ): OfficeEngineState = when (state) {
        WorkInfo.State.RUNNING -> when (progress?.getString(OfficeEngineDownloadWorker.KEY_PHASE)) {
            OfficeEngineDownloadWorker.PHASE_INSTALL -> OfficeEngineState.Installing
            OfficeEngineDownloadWorker.PHASE_VERIFY -> OfficeEngineState.Verifying
            else -> {
                val total = progress?.getLong(OfficeEngineDownloadWorker.KEY_TOTAL, 0L) ?: 0L
                val done = progress?.getLong(OfficeEngineDownloadWorker.KEY_DONE, 0L) ?: 0L
                OfficeEngineState.Downloading(
                    downloaded = done,
                    total = if (total > 0) total else OfficeEngineManifest.APPROX_DOWNLOAD_BYTES,
                )
            }
        }

        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> when {
            connectivity == OfficeEngineConnectivity.NONE || connectivity == OfficeEngineConnectivity.UNVALIDATED ->
                // No usable network (a captive portal cannot reach the pinned HTTPS release;
                // if WorkManager runs the row anyway it fails fast with a clear error).
                OfficeEngineState.WaitingForNetwork
            connectivity == OfficeEngineConnectivity.UNKNOWN -> OfficeEngineState.CheckingConnection
            insufficientStorageBytes != null ->
                // Enough network, but the visibly-computed space budget is not met.
                OfficeEngineState.InsufficientStorage(insufficientStorageBytes)
            else ->
                OfficeEngineState.Queued
        }

        WorkInfo.State.FAILED -> if (startedHere) {
            OfficeEngineState.Failed(outputError ?: "Download failed")
        } else {
            // A failure from before this process lifetime must not clobber the restorable
            // state (e.g. a previously hidden FAILED row after a process restart).
            installedState
        }

        // SUCCEEDED, CANCELLED, or no row yet: report the actual install state on disk.
        else -> installedState
    }
}
