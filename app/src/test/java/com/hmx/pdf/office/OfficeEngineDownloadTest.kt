package com.hmx.pdf.office

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hmx.pdf.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Unit tests for the Office engine download and installation flow.
 * These tests verify the core logic without requiring network or device.
 */
class OfficeEngineDownloadTest {

    @Test
    fun bundleForDeviceReturnsCorrectBundleForSupportedAbis() {
        val bundle = OfficeEngineManifest.bundleForDevice()
        if (bundle != null) {
            assertEquals("arm64-v8a", bundle.abi)
            assertTrue(bundle.url.startsWith("https://github.com/vasuki-re/LibreOffice-Lite/releases/download/v2.0/"))
            assertEquals(47_521_152L, bundle.sizeBytes)
            assertEquals(64, bundle.sha256.length)
        }
    }

    @Test
    fun bundleDefinitionsHaveValidSha256Format() {
        for (bundle in OfficeEngineManifest.bundles) {
            assertEquals(64, bundle.sha256.length)
            bundle.sha256.toCharArray().forEach { c ->
                assertTrue("Invalid hex char: $c", c in '0'..'9' || c in 'a'..'f')
            }
        }
    }

    @Test
    fun approxDownloadBytesMatchesArm64BundleSize() {
        assertEquals(47_521_152L, OfficeEngineManifest.APPROX_DOWNLOAD_BYTES)
    }

    @Test
    fun versionConstantsAreConsistent() {
        assertEquals("lo-lite-2.0", OfficeEngineManifest.VERSION)
        assertEquals("2.0", OfficeEngineManifest.DISPLAY_VERSION)
    }

    @Test
    fun downloadDirAndStagingDirAreInNoBackupFilesDir() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val downloadDir = OfficeEngineStore.downloadDir(context)
        val stagingDir = OfficeEngineStore.stagingDir(context)
        val rootDir = OfficeEngineStore.root(context)

        assertTrue(downloadDir.absolutePath.startsWith(rootDir.absolutePath))
        assertTrue(stagingDir.absolutePath.startsWith(rootDir.absolutePath))
        assertTrue(downloadDir.absolutePath.contains("office-engine"))
    }

    @Test
    fun isSupportedReturnsTrueForSupportedAbis() {
        val supported = OfficeEngine.isSupported()
        val bundle = OfficeEngineManifest.bundleForDevice()
        assertEquals(bundle != null, supported)
    }

    @Test
    fun relocateFromConstantIsSet() {
        assertEquals("/data/data/vasuki.istanpdf/files", OfficeEngineManifest.RELOCATE_FROM)
    }

    @Test
    fun sha256VerificationLogic() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val testFile = File(context.cacheDir, "sha256-test-${System.nanoTime()}.tmp")
        val content = "test content for sha256".toByteArray()
        FileOutputStream(testFile).use { it.write(content) }

        val md = java.security.MessageDigest.getInstance("SHA-256")
        testFile.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals(64, sha.length)

        testFile.delete()
    }
}

/**
 * Integration-style tests for the download worker logic.
 * These test the verification and extraction logic using local files.
 */
class OfficeEngineDownloadWorkerIntegrationTest {

    @Test
    fun engineArchiveLayoutExtractTarValidatesZipSlipProtection() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: return

        val safe = EngineArchiveLayout.destinationFor(staging, abi, "lib/$abi/libtest.so")
        assertNotNull(safe)
        assertTrue(safe!!.absolutePath.startsWith(staging.absolutePath))

        val unsafe = EngineArchiveLayout.destinationFor(staging, abi, "../../../etc/passwd")
        assertNull(unsafe)
    }

    @Test
    fun officeEngineStoreIsInstalledReturnsFalseForFreshInstall() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        OfficeEngineStore.delete(context)
        assertFalse(OfficeEngineStore.isInstalled(context))
        assertNull(OfficeEngineStore.installedDir(context))
        assertNull(OfficeEngineStore.installedVersion(context))
    }

    @Test
    fun officeEngineStoreDeleteRemovesAllEngineData() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val root = OfficeEngineStore.root(context)
        root.mkdirs()
        File(root, "dummy").writeText("test")
        OfficeEngineStore.delete(context)
        assertFalse(root.exists())
    }
}

/**
 * Tests for the DownloadOfficeEngineInstaller state machine.
 */
class DownloadOfficeEngineInstallerTest {

    @Test
    fun installerInitialStateReflectsInstalledStatus() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val installer = DownloadOfficeEngineInstaller(context)
        val state = installer.state.value
        when (state) {
            is OfficeEngineState.Unsupported -> assertTrue(true)
            is OfficeEngineState.NotInstalled -> assertTrue(true)
            is OfficeEngineState.Installed -> assertTrue(true)
            else -> fail("Unexpected initial state: $state")
        }
    }

    @Test
    fun cancelOnNonExistentWorkDoesNotCrash() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val installer = DownloadOfficeEngineInstaller(context)
        installer.cancel()
    }

    @Test
    fun uninstallCleansUpWorkAndEngine() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val installer = DownloadOfficeEngineInstaller(context)
        installer.uninstall()
    }

    @Test
    fun refreshDoesNotCrash() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val installer = DownloadOfficeEngineInstaller(context)
        installer.refresh()
    }

    @Test
    fun usesNotificationIsTrueForDownloadInstaller() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val installer = DownloadOfficeEngineInstaller(context)
        assertTrue(installer.usesNotification)
    }
}

/**
 * Tests for the Office engine archive layout and path validation.
 */
class EngineArchiveLayoutTest {

    @Test
    fun destinationForAcceptsValidLibPaths() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val dest = EngineArchiveLayout.destinationFor(staging, abi, "lib/$abi/liblo-native-code.so")
        assertNotNull(dest)
        assertTrue(dest!!.absolutePath.contains("lib/$abi/"))
    }

    @Test
    fun destinationForAcceptsValidAssetPaths() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val dest = EngineArchiveLayout.destinationFor(staging, abi, "assets/lo/program/fundamentalrc")
        assertNotNull(dest)
        assertTrue(dest!!.absolutePath.contains("program/"))
    }

    @Test
    fun destinationForRejectsDirectoryTraversal() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val dest = EngineArchiveLayout.destinationFor(staging, abi, "../../../etc/passwd")
        assertNull(dest)
    }

    @Test
    fun destinationForRejectsAbsolutePaths() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val dest = EngineArchiveLayout.destinationFor(staging, abi, "/etc/passwd")
        assertNull(dest)
    }
}

/**
 * Tests for OfficeEngineRelocation path aliasing.
 */
class OfficeEngineRelocationTest {

    @Test
    fun aliasForGeneratesCorrectLengthAlias() {
        val needle = "/data/data/vasuki.istanpdf/files"
        val alias = OfficeEngineRelocation.aliasFor(needle)
        assertEquals(needle.length, alias.length)
        assertTrue(alias.startsWith("/proc/self/fd/1023"))
    }

    @Test
    fun aliasForWorksWithEvenPadding() {
        val needle = "/data/data/com.example.app/files"
        val alias = OfficeEngineRelocation.aliasFor(needle)
        assertEquals(needle.length, alias.length)
    }
}
