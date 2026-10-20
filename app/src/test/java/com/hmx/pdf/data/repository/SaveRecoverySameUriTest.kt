package com.hmx.pdf.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class SaveRecoverySameUriTest {

    @Test
    fun destinationChangedSinceBackupReturnsFalseWhenDestinationMatchesBackup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.noBackupFilesDir, "recovery-test-${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "test.docx")
        val content = "PK\u0003\u0004 valid docx".toByteArray()
        FileOutputStream(file).use { it.write(content) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )

        val entry = SaveRecovery.begin(context, uri, "test.docx", "docx", file, true)
        assertNotNull(entry)
        assertNotNull(entry.backup)

        assertFalse(SaveRecovery.destinationChangedSinceBackup(context, entry))

        FileOutputStream(file).use { it.write("modified".toByteArray()) }
        assertTrue(SaveRecovery.destinationChangedSinceBackup(context, entry))

        SaveRecovery.discard(entry)
    }

    @Test
    fun restoreIfSafeReturnsUnsafeWhenDestinationIsValidButDifferent() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.noBackupFilesDir, "recovery-test-${System.nanoTime()}").apply { mkdirs() }
        val original = File(dir, "original.docx")
        val prepared = File(dir, "prepared.docx")
        val validDifferent = File(dir, "valid_different.docx")

        val origContent = "PK\u0003\u0004 original".toByteArray()
        val prepContent = "PK\u0003\u0004 prepared".toByteArray()
        val diffContent = "PK\u0003\u0004 different".toByteArray()

        FileOutputStream(original).use { it.write(origContent) }
        FileOutputStream(prepared).use { it.write(prepContent) }
        FileOutputStream(validDifferent).use { it.write(diffContent) }

        val originalUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", original
        )
        val diffUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", validDifferent
        )

        val entry = SaveRecovery.begin(context, originalUri, "test.docx", "docx", prepared, true)
        assertNotNull(entry)

        SaveRecovery.restoreIfSafe(context, entry.copy(destination = diffUri))
        SaveRecovery.discard(entry)
    }
}
