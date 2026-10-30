package com.hmx.pdf.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a durable, private copy of every externally-picked document the app opens, keyed by the
 * URI the user picked — so a document opened via "Open with ClearPDF" from another app is still
 * openable from Recents days later, not just in the minutes right after.
 *
 * Why this is needed at all: a URI handed to the app in a share/view intent from a foreign app
 * (WhatsApp, Gmail, a file manager, ...) carries only a *temporary* read grant. `takePersistableUriPermission`
 * is called on every such URI throughout this app, but it only succeeds when the sender actually
 * attached `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` — many senders don't, and the call then throws
 * and is (correctly) swallowed. Without a persistable grant, Android revokes read access once the
 * sending app's task finishes, which on many devices is within minutes, not "after some time" as it
 * might appear from inside this app. The document then opened once and can never be read again —
 * which is exactly "Failed to open PDF." / "Couldn't read this spreadsheet." from Recents.
 *
 * SAF picks (this app's own document picker) mostly don't need this — `takePersistableUriPermission`
 * on those genuinely persists. This mirror is a no-op safety net for them: [resolve] returns the
 * original unchanged, and the viewer calls [mirrorLater] once the first page is already on screen
 * for the case where that access ever stops being true (a doc-provider revoking access, an SD card
 * removed, ...).
 */
object LocalDocumentMirror {
    private const val PREFS_NAME = "clearpdf_doc_mirror"

    // Durability copies are maintenance, not part of opening the document. Keep them off the
    // caller's thread so a 100 MB PDF does not wait for a second 100 MB write before page one.
    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mirroring = ConcurrentHashMap<String, Boolean>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** App-private and durable — survives a system cache clear, unlike `cacheDir`. */
    private fun mirrorDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(base, "mirrored").apply { mkdirs() }
    }

    /**
     * The best URI to read [original] from right now.
     *
     * - If [original] is still readable, this returns it as-is immediately. The caller can call
     *   [mirrorLater] once the viewer has opened it, keeping the durability copy off the opening
     *   path and out of the provider's first read.
     * - If [original] is no longer readable, this returns a previously-saved mirror instead, if one
     *   exists.
     * - If neither is readable — the very first open of a URI whose grant is already gone, which
     *   this app never had a chance to save — [original] is returned unchanged and the caller's
     *   normal "couldn't open" handling applies. There is nothing this mirror can do about a file
     *   it was never able to read even once.
     */
    fun resolve(context: Context, original: Uri): Uri {
        if (!AppSettingsManager.getKeepLocalCopies(context)) return original
        val originalSize = runCatching {
            context.contentResolver.openFileDescriptor(original, "r")?.use { it.statSize }
        }.getOrNull()

        if (originalSize != null && originalSize > 0L) return original

        return savedMirror(context, original)?.let { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } ?: original
    }

    /**
     * The editor's local, durable file. Unlike [resolve], this waits for one copy because DOCX/XLSX
     * editing needs a writable `file://` source, but it returns that copy directly instead of
     * making a second cache copy before parsing.
     */
    fun localFile(context: Context, original: Uri, extensionHint: String): Uri {
        if (!AppSettingsManager.getKeepLocalCopies(context)) return original
        val appContext = context.applicationContext
        val originalSize = runCatching {
            appContext.contentResolver.openFileDescriptor(original, "r")?.use { it.statSize }
        }.getOrNull()
        // Editors must read the current bytes, not a same-size mirror from a previous open. -1
        // means the provider did not report a size; it can still be copied.
        runCatching { mirror(appContext, original, extensionHint, originalSize ?: -1L, force = true) }
            .getOrNull()
            ?.let { return Uri.fromFile(it) }
        return savedMirror(appContext, original)?.let { Uri.fromFile(it) } ?: original
    }

    private fun savedMirror(context: Context, original: Uri): File? {
        val path = prefs(context).getString(key(original), null) ?: return null
        return File(path).takeIf { it.exists() && it.length() > 0L }
    }

    private val knownExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp",
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "csv"
    )

    private fun cleanExtension(raw: String): String {
        val ext = raw.trimStart('.').lowercase().filter { it.isLetterOrDigit() }.take(5)
        return ext.takeIf { it in knownExtensions } ?: "pdf"
    }

    /** Provider query kept out of [resolve]'s caller: only the background mirror runs this. */
    private fun providerExtension(context: Context, original: Uri): String {
        val name = runCatching {
            context.contentResolver.query(original, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: original.lastPathSegment.orEmpty()
        return name.substringAfterLast('.', "pdf").lowercase().ifBlank { "pdf" }
    }

    /** Refresh the durable copy after the viewer has already started opening [original]. */
    fun mirrorLater(context: Context, original: Uri, extensionHint: String? = null) {
        if (!AppSettingsManager.getKeepLocalCopies(context)) return
        val appContext = context.applicationContext
        val id = key(original)
        if (mirroring.putIfAbsent(id, true) != null) return
        mirrorScope.launch {
            try {
                val size = runCatching {
                    appContext.contentResolver.openFileDescriptor(original, "r")?.use { it.statSize }
                }.getOrNull()
                if (size == null || size > 0L) {
                    runCatching { mirror(appContext, original, extensionHint, size ?: -1L) }
                }
            } finally {
                mirroring.remove(id)
            }
        }
    }

    /** Copies [original] into durable storage, skipping the copy if an up-to-date one already exists.
     *  Returns the mirror file, or null when the source could not be copied. */
    private fun mirror(
        context: Context,
        original: Uri,
        extensionHint: String?,
        currentSize: Long,
        force: Boolean = false
    ): File? {
        val cleanExt = cleanExtension(extensionHint ?: providerExtension(context, original))
        val file = File(mirrorDir(context), "${key(original)}.$cleanExt")

        // A document that hasn't changed size since it was last mirrored is treated as unchanged.
        // This mirror exists purely as an access-durability net, not a sync mechanism, so a cheap
        // heuristic that avoids re-copying on every single open is the right trade-off. Editors
        // pass force=true because they need the current bytes, not a same-size older copy.
        if (!force && file.exists() && file.length() == currentSize) {
            prefs(context).edit().putString(key(original), file.absolutePath).apply()
            return file
        }

        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        val input = context.contentResolver.openInputStream(original) ?: return null
        try {
            input.use { stream ->
                FileOutputStream(tmp).use { stream.copyTo(it) }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }

        if (tmp.length() <= 0L) {
            tmp.delete()
            return null
        }
        file.delete()
        return if (tmp.renameTo(file)) {
            prefs(context).edit().putString(key(original), file.absolutePath).apply()
            file
        } else {
            tmp.delete()
            null
        }
    }

    /** Forgets and deletes the mirror for one URI — called when its Recents entry is removed. */
    fun forget(context: Context, original: Uri) {
        val savedPath = prefs(context).getString(key(original), null) ?: return
        runCatching { File(savedPath).delete() }
        prefs(context).edit().remove(key(original)).apply()
    }

    /** Removes all durable document recovery copies created by ClearPDF. */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        listOf(
            File(base, "mirrored"),
            File(base, "imported_pdfs"),
            File(context.cacheDir, "imported_pdfs")
        ).distinct().forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }
}
