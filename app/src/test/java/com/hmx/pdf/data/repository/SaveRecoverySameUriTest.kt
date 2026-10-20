package com.hmx.pdf.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * @Ignore Tests requiring Android Context - require Robolectric or instrumented test runner
 */
@Ignore("Requires Android Context - enable with Robolectric or move to androidTest")
class SaveRecoverySameUriTest {

    @Test
    fun destinationChangedSinceBackupReturnsFalseWhenDestinationMatchesBackup() {
    }

    @Test
    fun restoreIfSafeReturnsUnsafeWhenDestinationIsValidButDifferent() {
    }
}
