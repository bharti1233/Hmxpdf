package com.kyant.pdfcore.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfSecurityServiceStartupTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun cleanupStaleDecryptedFilesRunsWithoutErrorOnStartup() {
        val deleted = PdfSecurityService.cleanupStaleDecryptedFiles(context)
        assertTrue(deleted >= 0)
    }

    @Test
    fun activePathsPreventsDeletionOfInUseFiles() {
        val dir = File(context.cacheDir, "decrypted_pdfs")
        dir.mkdirs()

        val now = System.currentTimeMillis()
        val live = File(dir, "unlocked_live_${System.nanoTime()}.pdf").apply {
            writeText("live")
            setLastModified(now - 48 * 60 * 60 * 1000L)
        }
        val sidecar = File(dir, "${live.name}.session").apply {
            writeText("sessionId=test")
            setLastModified(now)
        }

        val removed = PdfSecurityService.cleanupStaleDecryptedFiles(
            dir,
            cutoffMillis = now - 60 * 60 * 1000L,
            activePaths = emptySet()
        )

        assertEquals(0, removed)
        assertTrue(live.exists())
        assertTrue(sidecar.exists())

        live.delete()
        sidecar.delete()
    }
}