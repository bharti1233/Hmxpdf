package com.kyant.pdfcore.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * @Ignore Tests requiring Android Context - require Robolectric or instrumented test runner
 */
@Ignore("Requires Android Context - enable with Robolectric or move to androidTest")
class DecryptedSessionTest {

    @Test
    fun decryptedSessionCloseRemovesFromActivePaths() {
    }
}
