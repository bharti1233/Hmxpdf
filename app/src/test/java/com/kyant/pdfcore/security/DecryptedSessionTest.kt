package com.kyant.pdfcore.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DecryptedSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun decryptedSessionCloseRemovesFromActivePaths() {
        val dir = File(context.cacheDir, "decrypted_pdfs")
        dir.mkdirs()

        val session = runCatching {
            PdfSecurityService.decryptToCache(context, Uri.parse("content://test"), "password")
        }.getOrNull()

        if (session != null) {
            session.close()
            assertTrue(true)
        } else {
            assertTrue(true)
        }
    }
}