package com.hmx.pdf.office

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the honest Office-engine state mapping
 * (docs/HMXPDF_OFFICE_ENGINE_DOWNLOAD_AUDIT.md §8 regression plan). No Android context:
 * the mapper works on WorkInfo.State + androidx.work.Data only.
 */
class OfficeEngineStateMapperTest {

    private val approx = OfficeEngineManifest.APPROX_DOWNLOAD_BYTES
    private val installed = OfficeEngineState.Installed(version = "2.0", sizeBytes = 1L, updateAvailable = false)

    private fun downloadProgress(done: Long, total: Long): Data =
        workDataOf(
            OfficeEngineDownloadWorker.KEY_PHASE to OfficeEngineDownloadWorker.PHASE_DOWNLOAD,
            OfficeEngineDownloadWorker.KEY_DONE to done,
            OfficeEngineDownloadWorker.KEY_TOTAL to total,
        )

    private fun map(
        state: WorkInfo.State?,
        progress: Data? = null,
        outputError: String? = null,
        connectivity: OfficeEngineConnectivity = OfficeEngineConnectivity.VALIDATED,
        insufficientStorageBytes: Long? = null,
        startedHere: Boolean = true,
        installedState: OfficeEngineState = installed,
    ): OfficeEngineState = OfficeEngineStateMapper.map(
        state = state,
        progress = progress,
        outputError = outputError,
        connectivity = connectivity,
        insufficientStorageBytes = insufficientStorageBytes,
        startedHere = startedHere,
        installedState = installedState,
    )

    // --- Pending rows: the network claim is only made when the probe says so. ---

    @Test
    fun enqueuedWithoutNetworkWaitsForNetwork() {
        assertEquals(
            OfficeEngineState.WaitingForNetwork,
            map(WorkInfo.State.ENQUEUED, connectivity = OfficeEngineConnectivity.NONE)
        )
    }

    @Test
    fun enqueuedOnValidatedNetworkWithoutSpaceIssuesIsQueued() {
        assertEquals(OfficeEngineState.Queued, map(WorkInfo.State.ENQUEUED))
    }

    @Test
    fun enqueuedValidatedNetworkButNotValidatedCapabilityWaits() {
        assertEquals(
            OfficeEngineState.WaitingForNetwork,
            map(WorkInfo.State.ENQUEUED, connectivity = OfficeEngineConnectivity.UNVALIDATED)
        )
    }

    @Test
    fun enqueuedWithInsufficientStorageDoesNotClaimNetworkProblems() {
        assertEquals(
            OfficeEngineState.InsufficientStorage(123L),
            map(WorkInfo.State.ENQUEUED, insufficientStorageBytes = 123L)
        )
    }

    @Test
    fun enqueuedDuringRetryBackoffIsQueuedNotWaitingForNetwork() {
        // Backoff appears as ENQUEUED with no progress payload.
        assertEquals(OfficeEngineState.Queued, map(WorkInfo.State.ENQUEUED, progress = null))
    }

    @Test
    fun blockedBehavesLikeEnqueued() {
        assertEquals(
            OfficeEngineState.WaitingForNetwork,
            map(WorkInfo.State.BLOCKED, connectivity = OfficeEngineConnectivity.NONE)
        )
        assertEquals(OfficeEngineState.Queued, map(WorkInfo.State.BLOCKED))
    }

    @Test
    fun undeterminedConnectivityShowsCheckingInsteadOfClaimingNetwork() {
        assertEquals(
            OfficeEngineState.CheckingConnection,
            map(WorkInfo.State.ENQUEUED, connectivity = OfficeEngineConnectivity.UNKNOWN)
        )
    }

    // --- Running rows ---

    @Test
    fun runningDownloadReportsProgress() {
        assertEquals(
            OfficeEngineState.Downloading(1024L, 47_521_152L),
            map(WorkInfo.State.RUNNING, progress = downloadProgress(1024L, 47_521_152L))
        )
    }

    @Test
    fun runningDownloadWithoutProgressIsIndeterminate() {
        assertEquals(OfficeEngineState.Downloading(0L, approx), map(WorkInfo.State.RUNNING, progress = null))
    }

    @Test
    fun runningVerificationPhaseIsVerifying() {
        assertEquals(
            OfficeEngineState.Verifying,
            map(WorkInfo.State.RUNNING, workDataOf(OfficeEngineDownloadWorker.KEY_PHASE to OfficeEngineDownloadWorker.PHASE_VERIFY))
        )
    }

    @Test
    fun runningInstallPhaseIsInstalling() {
        assertEquals(
            OfficeEngineState.Installing,
            map(WorkInfo.State.RUNNING, workDataOf(OfficeEngineDownloadWorker.KEY_PHASE to OfficeEngineDownloadWorker.PHASE_INSTALL))
        )
    }

    // --- Terminal rows / restarts ---

    @Test
    fun failedInThisSessionShowsActionableError() {
        assertEquals(
            OfficeEngineState.Failed("Download verification failed (checksum mismatch)"),
            map(WorkInfo.State.FAILED, outputError = "Download verification failed (checksum mismatch)")
        )
    }

    @Test
    fun failedInThisSessionWithNoErrorGetsGenericMessage() {
        assertEquals(OfficeEngineState.Failed("Download failed"), map(WorkInfo.State.FAILED, outputError = null))
    }

    @Test
    fun failedFromBeforeRestartFallsBackToInstalledState() {
        // Process restart: startedHere=false — the same case the old map() masked silently.
        assertEquals(
            OfficeEngineState.NotInstalled,
            map(
                WorkInfo.State.FAILED,
                startedHere = false,
                installedState = OfficeEngineState.NotInstalled,
            )
        )
    }

    @Test
    fun cancelledRowShowsInstalledState() {
        assertEquals(
            installed,
            map(WorkInfo.State.CANCELLED, startedHere = false)
        )
    }

    @Test
    fun succeededRowShowsInstalledState() {
        assertEquals(installed, map(WorkInfo.State.SUCCEEDED))
    }

    @Test
    fun absentRowShowsInstalledState() {
        // Already-installed engine (or never-started install): pass the on-disk state through.
        assertEquals(installed, map(state = null))
        assertEquals(
            OfficeEngineState.Installed(version = "1.0", sizeBytes = 99L, updateAvailable = true),
            map(
                state = null,
                installedState = OfficeEngineState.Installed(version = "1.0", sizeBytes = 99L, updateAvailable = true),
            )
        )
    }

    // --- Storage budget arithmetic (independent of any Android StatFs probe) ---

    @Test
    fun requiredFreeBytesSumsRemainingArchiveWorkingSetAndExistingEngine() {
        val remaining = OfficeEngineStateMapper.requiredFreeBytes(
            archiveBytes = 47_521_152L,
            alreadyHaveBytes = 7_521_152L,
            existingEngineBytes = 100L * 1024 * 1024,
        )
        val expected = 47_521_152L - 7_521_152L +
            OfficeEngineStateMapper.EXTRACTION_WORKING_BYTES +
            100L * 1024 * 1024
        assertEquals(expected, remaining)
    }

    @Test
    fun requiredFreeBytesClampsNegativeRemainingDownload() {
        // A complete(or overflowing) part must not create negative "remaining" space.
        val required = OfficeEngineStateMapper.requiredFreeBytes(
            archiveBytes = 1_000L,
            alreadyHaveBytes = 5_000L,
            existingEngineBytes = 0L,
        )
        assertEquals(OfficeEngineStateMapper.EXTRACTION_WORKING_BYTES, required)
    }
}
