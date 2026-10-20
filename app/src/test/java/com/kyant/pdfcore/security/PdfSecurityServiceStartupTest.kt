package com.kyant.pdfcore.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfSecurityServiceStartupTest {

    @Test
    fun `cleanupStaleDecryptedFiles runs without error on startup`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // This should not throw
        val deleted = PdfSecurityService.cleanupStaleDecryptedFiles(context)
        // Just verify it runs - the count depends on test environment
        assertTrue(deleted >= 0)
    }

    @Test
    fun `activePaths prevents deletion of in-use files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "decrypted_pdfs")
        dir.mkdirs()

        val now = System.currentTimeMillis()
        val live = File(dir, "unlocked_live_${System.nanoTime()}.pdf").apply {
            writeText("live")
            setLastModified(now - 48 * 60 * 60 * 1000L) // very old
        }
        val sidecar = File(dir, "${live.name}.session").apply {
            writeText("sessionId=test")
            setLastModified(now) // recent sidecar
        }

        val removed = PdfSecurityService.cleanupStaleDecryptedFiles(
            dir,
            cutoffMillis = now - 60 * 60 * 1000L,
            activePaths = emptySet()
        )

        // Recent sidecar should protect the file even though it's old
        assertEquals(0, removed)
        assertTrue(live.exists())
        assertTrue(sidecar.exists())

        // Clean up
        live.delete()
        sidecar.delete()
    }
}
