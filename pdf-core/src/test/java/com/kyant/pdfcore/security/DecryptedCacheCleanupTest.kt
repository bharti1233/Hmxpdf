package com.kyant.pdfcore.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecryptedCacheCleanupTest {

    @Test
    fun `only stale unlocked files are deleted`() {
        val dir = tempDir("clearpdf-decrypted")
        val now = System.currentTimeMillis()
        val old = File(dir, "unlocked_old.pdf").apply {
            writeText("old")
            setLastModified(now - 2 * 60 * 60 * 1000L)
        }
        val active = File(dir, "unlocked_active.pdf").apply {
            writeText("active")
            setLastModified(now)
        }
        val unrelated = File(dir, "notes.txt").apply {
            writeText("keep")
            setLastModified(now - 2 * 60 * 60 * 1000L)
        }

        val removed = PdfSecurityService.cleanupStaleDecryptedFiles(
            dir,
            cutoffMillis = now - 60 * 60 * 1000L
        )

        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(active.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `old file owned by a live session is preserved`() {
        val dir = tempDir("clearpdf-decrypted-live")
        val now = System.currentTimeMillis()
        val live = File(dir, "unlocked_live.pdf").apply {
            writeText("live")
            setLastModified(now - 48 * 60 * 60 * 1000L)
        }
        val stale = File(dir, "unlocked_stale.pdf").apply {
            writeText("stale")
            setLastModified(now - 48 * 60 * 60 * 1000L)
        }

        val removed = PdfSecurityService.cleanupStaleDecryptedFiles(
            dir,
            cutoffMillis = now - 60 * 60 * 1000L,
            activePaths = setOf(live.canonicalPath)
        )

        assertEquals(1, removed)
        assertTrue(live.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `recent session sidecar preserves an old file`() {
        val dir = tempDir("clearpdf-decrypted-sidecar")
        val now = System.currentTimeMillis()
        val pdf = File(dir, "unlocked_sidecar.pdf").apply {
            writeText("session")
            setLastModified(now - 48 * 60 * 60 * 1000L)
        }
        File(dir, "${pdf.name}.session").apply {
            writeText("sessionId=test")
            setLastModified(now)
        }

        val removed = PdfSecurityService.cleanupStaleDecryptedFiles(
            dir,
            cutoffMillis = now - 60 * 60 * 1000L,
            activePaths = emptySet()
        )

        assertEquals(0, removed)
        assertTrue(pdf.exists())
    }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }
}
