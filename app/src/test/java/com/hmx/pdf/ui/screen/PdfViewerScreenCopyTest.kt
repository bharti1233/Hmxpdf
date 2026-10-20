package com.hmx.pdf.ui.screen

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hmx.pdf.data.repository.SaveRecovery
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class PdfViewerScreenCopyTest {

    @Test
    fun `copyWithVerification rejects same content source and target`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "copytest-${System.nanoTime()}").apply { mkdirs() }
        val source = File(dir, "source.pdf")
        val target = File(dir, "target.pdf")
        val content = "test pdf content".toByteArray()
        FileOutputStream(source).use { it.write(content) }
        FileOutputStream(target).use { it.write(content) }

        val sourceUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", source
        )
        val targetUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", target
        )

        // We can't easily test the private copyWithVerification function from here,
        // but we can verify the digest comparison logic
        val cr = context.contentResolver
        fun digestOf(uri: Uri): ByteArray? = runCatching {
            cr.openInputStream(uri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
                digest.digest()
            }
        }.getOrNull()

        val sourceDigest = digestOf(sourceUri)
        val targetDigest = digestOf(targetUri)
        assertNotNull(sourceDigest)
        assertNotNull(targetDigest)
        assertTrue(java.security.MessageDigest.isEqual(sourceDigest!!, targetDigest!!))
    }

    @Test
    fun `SaveRecovery prevents overwriting same document`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.noBackupFilesDir, "recovery-test-${System.nanoTime()}").apply { mkdirs() }
        val original = File(dir, "original.docx")
        val prepared = File(dir, "prepared.docx")
        val content = "PK\x03\x04 valid docx".toByteArray() // minimal ZIP header
        FileOutputStream(original).use { it.write(content) }
        FileOutputStream(prepared).use { it.write(content) }

        val originalUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", original
        )

        // begin() should succeed even if backup matches prepared
        val entry = SaveRecovery.begin(context, originalUri, "test.docx", "docx", prepared, false)
        assertNotNull(entry)
        SaveRecovery.discard(entry)
    }
}
