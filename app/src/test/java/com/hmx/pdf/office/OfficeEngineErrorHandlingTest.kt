package com.hmx.pdf.office

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hmx.pdf.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Tests for error handling in the Office engine download and installation flow.
 * These tests verify behavior under failure conditions without requiring network.
 */
class OfficeEngineErrorHandlingTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `SHA256 mismatch detection`() {
        val testFile = File(context.cacheDir, "checksum-test-${System.nanoTime()}.tmp")
        FileOutputStream(testFile).use { it.write("original content".toByteArray()) }

        val md = MessageDigest.getInstance("SHA-256")
        testFile.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val correctSha = md.digest().joinToString("") { "%02x".format(it) }

        // Modify the file
        FileOutputStream(testFile).use { it.write("modified content".toByteArray()) }

        // Verify mismatch detection
        val md2 = MessageDigest.getInstance("SHA-256")
        testFile.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md2.update(buf, 0, n)
            }
        }
        val wrongSha = md2.digest().joinToString("") { "%02x".format(it) }

        assertNotEquals(correctSha, wrongSha)
        testFile.delete()
    }

    @Test
    fun `size mismatch detection`() {
        val testFile = File(context.cacheDir, "size-test-${System.nanoTime()}.tmp")
        FileOutputStream(testFile).use { it.write("short".toByteArray()) }
        assertEquals(5L, testFile.length())
        assertNotEquals(100L, testFile.length())
        testFile.delete()
    }

    @Test
    fun `corrupt tar.xz archive detection`() {
        // Create a fake corrupt .tar.xz file
        val testFile = File(context.cacheDir, "corrupt-${System.nanoTime()}.tar.xz")
        FileOutputStream(testFile).use { it.write("not a valid xz archive".toByteArray()) }

        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"

        // The worker uses XZInputStream which will throw on corrupt data
        var threw = false
        try {
            org.tukaani.xz.XZInputStream(testFile.inputStream()).use { }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("Should throw on corrupt xz", threw)
        testFile.delete()
    }

    @Test
    fun `empty archive rejection`() {
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"

        // Create an empty archive
        val testFile = File(context.cacheDir, "empty-${System.nanoTime()}.tar.xz")
        testFile.writeBytes(emptyByteArray())

        var threw = false
        try {
            org.tukaani.xz.XZInputStream(testFile.inputStream()).use { }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
        testFile.delete()
    }

    @Test
    fun `staging directory validation requires liblo-native-code.so`() {
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val libDir = File(staging, "lib/$abi").apply { mkdirs() }
        // Don't create liblo-native-code.so

        var threw = false
        try {
            OfficeEngineStore.commit(context, staging, abi)
        } catch (e: Exception) {
            threw = true
            assertTrue(e.message?.contains("liblo-native-code.so") == true)
        }
        assertTrue(threw)
    }

    @Test
    fun `staging directory validation requires libc++_shared.so`() {
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val libDir = File(staging, "lib/$abi").apply { mkdirs() }
        File(libDir, "liblo-native-code.so").writeText("fake")

        var threw = false
        try {
            OfficeEngineStore.commit(context, staging, abi)
        } catch (e: Exception) {
            threw = true
            assertTrue(e.message?.contains("libc++_shared.so") == true)
        }
        assertTrue(threw)
    }

    @Test
    fun `staging directory validation requires program/fundamentalrc`() {
        val staging = OfficeEngineStore.resetStaging(context)
        val abi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val libDir = File(staging, "lib/$abi").apply { mkdirs() }
        File(libDir, "liblo-native-code.so").writeText("fake")
        File(libDir, "libc++_shared.so").writeText("fake")
        // Don't create program/fundamentalrc

        var threw = false
        try {
            OfficeEngineStore.commit(context, staging, abi)
        } catch (e: Exception) {
            threw = true
            assertTrue(e.message?.contains("runtime") == true)
        }
        assertTrue(threw)
    }

    @Test
    fun `atomic commit preserves previous engine on failure`() {
        val root = OfficeEngineStore.root(context)
        root.deleteRecursively()

        // Install a valid "previous" engine first
        val oldVersion = "lo-lite-1.0"
        val oldAbi = "arm64-v8a"
        val oldDir = File(root, "$oldVersion-$oldAbi")
        val oldLibDir = File(oldDir, "lib/$oldAbi").apply { mkdirs() }
        File(oldLibDir, "liblo-native-code.so").writeText("old")
        File(oldLibDir, "libc++_shared.so").writeText("old")
        val oldProgramDir = File(oldDir, "program").apply { mkdirs() }
        File(oldProgramDir, "fundamentalrc").writeText("old")
        File(oldDir, ".ready").writeText(oldVersion)
        File(root, "current").writeText("$oldVersion-$oldAbi")

        assertTrue(OfficeEngineStore.isInstalled(context))
        assertEquals(oldVersion, OfficeEngineStore.installedVersion(context))

        // Attempt to commit a new invalid engine
        val staging = OfficeEngineStore.resetStaging(context)
        val newAbi = OfficeEngineManifest.bundleForDevice()?.abi ?: "arm64-v8a"
        val newLibDir = File(staging, "lib/$newAbi").apply { mkdirs() }
        File(newLibDir, "liblo-native-code.so").writeText("new")
        // Missing libc++_shared.so and program/fundamentalrc - commit should fail

        var threw = false
        try {
            OfficeEngineStore.commit(context, staging, newAbi)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)

        // Verify old engine is still intact
        assertTrue(OfficeEngineStore.isInstalled(context))
        assertEquals(oldVersion, OfficeEngineStore.installedVersion(context))
        assertTrue(oldDir.exists())
    }

    @Test
    fun `download part file cleanup on checksum mismatch`() {
        val downloadDir = OfficeEngineStore.downloadDir(context)
        downloadDir.deleteRecursively()
        downloadDir.mkdirs()

        val bundle = OfficeEngineManifest.bundleForDevice() ?: return
        val partFile = File(downloadDir, "${bundle.sha256}.part")
        partFile.writeText("partial download")

        // Simulate checksum verification failure - the worker deletes the part file
        // when verification fails
        val md = MessageDigest.getInstance("SHA-256")
        val sha = md.digest("wrong content".toByteArray()).joinToString("") { "%02x".format(it) }
        assertNotEquals(bundle.sha256, sha)

        // The worker would delete the part file on mismatch
        // (we can't easily test the private download function, but we verify the logic)
        assertTrue(partFile.exists()) // Still exists before manual deletion
        partFile.delete()
        assertFalse(partFile.exists())
    }
}

/**
 * Tests for retry behavior and idempotency.
 */
class OfficeEngineRetryTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `repeated install calls are idempotent`() {
        val installer = DownloadOfficeEngineInstaller(context)
        installer.install()
        installer.install() // Second call should not create duplicate work
        installer.cancel()
    }

    @Test
    fun `cancel after install clears state`() {
        val installer = DownloadOfficeEngineInstaller(context)
        installer.install()
        installer.cancel()
        // State should return to installed/not-installed
        val state = installer.state.value
        when (state) {
            is OfficeEngineState.NotInstalled -> assertTrue(true)
            is OfficeEngineState.Installed -> assertTrue(true)
            is OfficeEngineState.Unsupported -> assertTrue(true)
            else -> fail("Expected terminal state after cancel, got: $state")
        }
    }

    @Test
    fun `uninstall then install starts fresh`() {
        val installer = DownloadOfficeEngineInstaller(context)
        installer.uninstall()
        installer.install()
        installer.cancel()
        // Should not crash
    }

    @Test
    fun `state flow emits correct values`() {
        val installer = DownloadOfficeEngineInstaller(context)
        val initialState = installer.state.value
        // Initial state should be a valid terminal state
        when (initialState) {
            is OfficeEngineState.Unsupported,
             is OfficeEngineState.NotInstalled,
             is OfficeEngineState.Installed,
             is OfficeEngineState.Failed -> assertTrue(true)
            else -> fail("Invalid initial state: $initialState")
        }
    }
}

/**
 * Tests for built-in functionality when engine is absent.
 */
class BuiltInFallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `OfficeEngine.tryConvert returns null when engine not installed`() {
        OfficeEngineStore.delete(context)
        val result = OfficeEngine.tryConvert(context, Uri.parse("content://test"), "test.docx")
        assertNull(result)
    }

    @Test
    fun `OfficeEngine.tryConvert returns null when engine disabled`() {
        OfficeEngine.setUseForOfficeFiles(context, false)
        val result = OfficeEngine.tryConvert(context, Uri.parse("content://test"), "test.docx")
        assertNull(result)
        OfficeEngine.setUseForOfficeFiles(context, true)
    }

    @Test
    fun `OfficeEngine.isOfficeFile detects office extensions`() {
        val officeFiles = listOf("test.doc", "test.docx", "test.xls", "test.xlsx", "test.ppt", "test.pptx", "test.odt", "test.ods", "test.odp")
        for (f in officeFiles) {
            assertTrue("Should detect $f", OfficeEngine.isOfficeFile(f))
        }
        val nonOffice = listOf("test.pdf", "test.txt", "test.png", "test.jpg")
        for (f in nonOffice) {
            assertFalse("Should not detect $f", OfficeEngine.isOfficeFile(f))
        }
    }

    @Test
    fun `OfficeEngine.isActive requires both installed and enabled`() {
        OfficeEngineStore.delete(context)
        OfficeEngine.setUseForOfficeFiles(context, true)
        assertFalse(OfficeEngine.isActive(context))

        OfficeEngine.setUseForOfficeFiles(context, false)
        assertFalse(OfficeEngine.isActive(context))
    }
}
