package com.kyant.pdfcore.security

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kyant.pdfcore.internal.PdfBox
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Password operations for PDFs. The service keeps the original URI untouched and performs
 * all parsing/writing on the caller's worker thread.
 */
object PdfSecurityService {

    class PasswordRequiredException : IllegalStateException("PDF password required")

    /**
     * An owned plaintext copy. The viewer must close it when the document is closed; cleanup uses
     * both the live registry and the sidecar so it never deletes a file belonging to a live view.
     */
    class DecryptedSession internal constructor(
        val file: File,
        internal val sidecar: File,
        internal val key: String,
        internal val sessionId: String
    ) : Closeable {
        private var released = false

        override fun close() {
            val shouldRelease = synchronized(this) {
                if (released) false else { released = true; true }
            }
            if (shouldRelease) PdfSecurityService.releaseDecryptedSession(this)
        }
    }

    // Canonical paths of files owned by live viewers in this process. Process death clears this,
    // which is exactly when the sidecar and age checks take over.
    private val activePaths = ConcurrentHashMap<String, Boolean>()

    fun isPasswordProtected(context: Context, uri: Uri): Boolean {
        PdfBox.ensureInitialized(context)
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).use { document -> document.isEncrypted }
            } ?: false
        } catch (error: Exception) {
            if (isPasswordError(error)) true else throw error
        }
    }

    /**
     * Removes plaintext copies abandoned by a crash or process kill. Callers must not use this to
     * delete a file that is currently open; [activePaths] and the per-file sidecar protect live
     * sessions, and the age bound catches anything that survived a process death.
     */
    fun cleanupStaleDecryptedFiles(
        context: Context,
        maxAgeMs: Long = 24L * 60L * 60L * 1000L
    ): Int = cleanupStaleDecryptedFiles(
        File(context.cacheDir, "decrypted_pdfs"),
        System.currentTimeMillis() - maxAgeMs.coerceAtLeast(0L),
        activeDecryptedPaths()
    )

    fun cleanupStaleDecryptedFiles(
        directory: File,
        cutoffMillis: Long,
        activePaths: Set<String> = activeDecryptedPaths()
    ): Int {
        if (!directory.isDirectory) return 0
        val files = directory.listFiles() ?: return 0
        var deleted = 0
        files.forEach { file ->
            if (!file.isFile || !file.name.startsWith("unlocked_") || file.name.endsWith(".session")) {
                return@forEach
            }
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (key in activePaths) return@forEach
            val sidecar = sidecarFor(file)
            // A recent sidecar means a live (or just-finished) session. Leave it alone even if the
            // PDF's timestamp is old: a long reading session may run past the age cutoff.
            if (sidecar.isFile && sidecar.lastModified() >= cutoffMillis) return@forEach
            if (file.lastModified() >= cutoffMillis) return@forEach
            val removed = runCatching { file.delete() }.getOrDefault(false)
            if (removed) {
                deleted++
                runCatching { sidecar.delete() }
            } else {
                Log.w(TAG, "Could not remove stale decrypted PDF ${file.name}")
            }
        }
        // Remove orphaned sidecars whose PDF is already gone. Only names this service creates.
        files.forEach { file ->
            if (!file.isFile || !file.name.startsWith("unlocked_") || !file.name.endsWith(".session")) {
                return@forEach
            }
            val pdf = File(file.parentFile, file.name.removeSuffix(".session"))
            if (!pdf.exists() && file.lastModified() < cutoffMillis) {
                runCatching { file.delete() }
            }
        }
        return deleted
    }

    /** Creates an unlocked temporary PDF for the viewer without modifying the source. */
    fun decryptToCache(context: Context, uri: Uri, password: String): DecryptedSession {
        PdfBox.ensureInitialized(context)
        val outputDirectory = File(context.cacheDir, "decrypted_pdfs").apply { mkdirs() }
        // A previous process may have died before deleting its plaintext copy.
        cleanupStaleDecryptedFiles(
            outputDirectory,
            System.currentTimeMillis() - 24L * 60L * 60L * 1000L,
            activeDecryptedPaths()
        )
        val sessionId = UUID.randomUUID().toString()
        val output = File(
            outputDirectory,
            "unlocked_${System.currentTimeMillis()}_${sessionId.take(8)}.pdf"
        )
        val sidecar = sidecarFor(output)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input, password, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                    document.setAllSecurityToBeRemoved(true)
                    output.outputStream().use { document.save(it) }
                }
            } ?: throw IllegalStateException("Unable to read PDF")
            writeSidecar(sidecar, sessionId)
            val key = runCatching { output.canonicalPath }.getOrDefault(output.absolutePath)
            activePaths[key] = true
            return DecryptedSession(output, sidecar, key, sessionId)
        } catch (error: Throwable) {
            // Also clean up after OutOfMemoryError and other failures: an unowned plaintext copy
            // must not be left in cache just because the failure was not an Exception.
            runCatching { output.delete() }
            runCatching { sidecar.delete() }
            if (isPasswordError(error)) throw PasswordRequiredException()
            throw error
        }
    }

    internal fun releaseDecryptedSession(session: DecryptedSession) {
        activePaths.remove(session.key)
        val deleted = runCatching { session.file.delete() }.getOrDefault(false)
        runCatching { session.sidecar.delete() }
        if (!deleted && session.file.exists()) {
            Log.w(TAG, "Could not remove decrypted PDF ${session.file.name}")
        }
    }

    private fun activeDecryptedPaths(): Set<String> = activePaths.keys.toSet()

    private fun sidecarFor(pdf: File): File = File(pdf.parentFile, pdf.name + ".session")

    private fun writeSidecar(sidecar: File, sessionId: String) {
        val properties = Properties().apply {
            setProperty("sessionId", sessionId)
            setProperty("createdAt", System.currentTimeMillis().toString())
        }
        val temp = File(sidecar.parentFile, sidecar.name + ".tmp")
        FileOutputStream(temp).use { properties.store(it, null) }
        if (!temp.renameTo(sidecar)) {
            temp.copyTo(sidecar, overwrite = true)
            temp.delete()
        }
    }

    /** Writes an unlocked copy to a user-selected destination URI. */
    fun decryptToUri(context: Context, sourceUri: Uri, destinationUri: Uri, password: String) {
        PdfBox.ensureInitialized(context)
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input, password, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                    document.setAllSecurityToBeRemoved(true)
                    context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                        document.save(output)
                    } ?: throw IllegalStateException("Unable to write PDF")
                }
            } ?: throw IllegalStateException("Unable to read PDF")
        } catch (error: Exception) {
            if (isPasswordError(error)) throw PasswordRequiredException()
            throw error
        }
    }

    /** Writes a password-protected copy to a user-selected destination URI. */
    fun encryptToUri(context: Context, sourceUri: Uri, destinationUri: Uri, password: String) {
        require(password.isNotBlank()) { "PDF password required" }
        PdfBox.ensureInitialized(context)
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { document ->
                    val permissions = AccessPermission().apply {
                        setCanPrint(true)
                        setCanModify(false)
                        setCanExtractContent(true)
                    }
                    // AES-256, and an owner password of its own: with owner == user the open password also
                    // grants owner rights, so the "no modify" permission would mean nothing.
                    val policy = StandardProtectionPolicy(randomOwnerPassword(), password, permissions).apply {
                        encryptionKeyLength = 256
                    }
                    document.protect(policy)
                    context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                        document.save(output)
                    } ?: throw IllegalStateException("Unable to write PDF")
                }
            } ?: throw IllegalStateException("Unable to read PDF")
        } catch (error: Exception) {
            if (isPasswordError(error)) throw PasswordRequiredException()
            throw error
        }
    }

    private fun randomOwnerPassword(): String {
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun isPasswordError(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }
            .any { it::class.java.simpleName.contains("InvalidPassword", ignoreCase = true) }
    }

    private const val TAG = "PdfSecurityService"
}
