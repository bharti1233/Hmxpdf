package com.hmx.pdf.office

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** What the Settings "Office engine" section shows. */
sealed interface OfficeEngineState {
    /** No bundle for this device's ABI (e.g. x86) — the section is hidden. */
    data object Unsupported : OfficeEngineState

    data object NotInstalled : OfficeEngineState

    /** A row exists but connectivity/storage probes have not resolved the reason yet. */
    data object CheckingConnection : OfficeEngineState

    /** The app's own probe found no usable network. WorkManager runs the row when one appears. */
    data object WaitingForNetwork : OfficeEngineState

    /**
     * Network and space are fine; the OS scheduler (battery rules, Doze, backoff or an
     * imminent start) has not begun the row. WorkManager does not expose which constraint
     * applies, so the label stays deliberately honest.
     */
    data object Queued : OfficeEngineState

    /** The visible space preflight failed; [requiredBytes] is what the whole install needs. */
    data class InsufficientStorage(val requiredBytes: Long) : OfficeEngineState

    /** [total] falls back to the approximate bundle size when the worker has not reported one. */
    data class Downloading(val downloaded: Long, val total: Long) : OfficeEngineState

    /** Archive complete; SHA-256 + size verification in progress. */
    data object Verifying : OfficeEngineState

    /** Verifying unpacked the staged engine tree. */
    data object Installing : OfficeEngineState

    /** Play asks the user to approve a large download ([OfficeEngineInstaller.confirm]). */
    data object NeedsConfirmation : OfficeEngineState

    data class Installed(val version: String, val sizeBytes: Long, val updateAvailable: Boolean) : OfficeEngineState

    data class Failed(val message: String) : OfficeEngineState
}

/**
 * Gets the engine onto the device. One implementation per distribution flavor, created by
 * `OfficeEngineFlavor.createInstaller` (src/play, src/foss); both finish through
 * [OfficeEngineStore.commit] so the runtime side is identical.
 */
interface OfficeEngineInstaller {
    val state: StateFlow<OfficeEngineState>

    /** Whether starting an install should first ask for POST_NOTIFICATIONS (progress notification). */
    val usesNotification: Boolean

    /** Starts (or resumes) installing or updating. */
    fun install()

    fun cancel()

    /** Removes the engine and frees its storage. */
    fun uninstall()

    /** Re-reads on-disk state (e.g. when Settings opens). */
    fun refresh()

    /** Answers [OfficeEngineState.NeedsConfirmation]; no-op where not applicable. */
    fun confirm(activity: Activity) {}
}
