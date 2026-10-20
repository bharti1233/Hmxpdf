package com.kyant.pdfcore.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DecryptedCacheCleanupTest {

    @Test
    fun `only stale unlocked files are deleted`() {
        val dir = Files.createTempDirectory("clearpdf-decrypted").toFile()
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
}
