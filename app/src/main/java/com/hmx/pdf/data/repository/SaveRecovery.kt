package com.hmx.pdf.data.repository

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Durable, app-private journal for Office saves.
 *
 * A SAF destination cannot be replaced atomically, so a save prepares and validates a complete
 * replacement, copies both the original and the replacement into [Context.getNoBackupFilesDir],
 * and only then writes to the destination. The journal (never document contents, passwords, or
 * other sensitive data) lets the next launch detect a process death mid-save. Recovery is
 * deliberately a "save a copy" action: the destination itself is never overwritten unless the
 * operation can prove it is still the same file and the current bytes are not a valid package.
 */
internal object SaveRecovery {

    private const val ROOT = "save_recovery"
    private const val JOURNAL = "journal.properties"
    private const val VERSION = "1"

    enum class State { PREPARED, WRITING, FAILED }

    data class Entry(
        val directory: File,
        val destination: Uri?,
        val displayName: String,
        val kind: String,
        val backup: File?,
        val prepared: File?,
        val originalHash: String,
        val preparedHash: String,
        val state: State,
        val createdAt: Long
    ) {
        /** The newest valid complete copy we can offer without touching the destination. */
        val recoveryFile: File?
            get() = prepared?.takeIf { SaveRecovery.validateOfficePackage(it, kind) }
                ?: backup?.takeIf { SaveRecovery.validateOfficePackage(it, kind) }
    }

    enum class RestoreResult { SUCCEEDED, UNCHANGED, RESTORED, UNSAFE, FAILED }

    /**
     * Copies [prepared] and the current destination into durable storage and writes the journal.
     * Nothing is written to [destination] here. Throws on preparation/backup/journal failure, in
     * which case the caller must leave the original untouched.
     */
    fun begin(
        context: Context,
        destination: Uri?,
        displayName: String,
        kind: String,
        prepared: File,
        requireBackup: Boolean
    ): Entry {
        require(kind == "docx" || kind == "xlsx") { "Unsupported document kind" }
        if (!validateOfficePackage(prepared, kind)) {
            throw IOException("The prepared document is not a valid $kind package")
        }
        val root = File(context.noBackupFilesDir, ROOT).apply {
            if (!exists() && !mkdirs()) throw IOException("Could not create recovery storage")
        }
        val directory = File(root, UUID.randomUUID().toString()).apply {
            if (!mkdirs()) throw IOException("Could not create a recovery journal")
        }
        try {
            val preparedCopy = File(directory, "prepared.$kind")
            copyFile(prepared, preparedCopy)
            if (!validateOfficePackage(preparedCopy, kind)) {
                throw IOException("The recovery copy is not a valid $kind package")
            }
            var backup: File? = null
            if (destination != null) {
                val candidate = File(directory, "backup.$kind")
                if (copyUriToFile(context, destination, candidate) && candidate.isFile) {
                    backup = candidate
                } else {
                    candidate.delete()
                }
            }
            if (requireBackup && backup == null) {
                throw IOException("Could not back up the original document")
            }
            if (destination != null && backup == null) {
                throw IOException("Could not back up the destination document")
            }
            val entry = Entry(
                directory = directory,
                destination = destination,
                displayName = displayName,
                kind = kind,
                backup = backup,
                prepared = preparedCopy,
                originalHash = backup?.let(::hashFile).orEmpty(),
                preparedHash = hashFile(preparedCopy),
                state = State.PREPARED,
                createdAt = System.currentTimeMillis()
            )
            writeJournal(entry, State.PREPARED)
            return entry
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun markWriting(entry: Entry) {
        writeJournal(entry, State.WRITING)
    }

    fun markFailed(entry: Entry) {
        runCatching { writeJournal(entry, State.FAILED) }
    }

    fun markVerified(entry: Entry) {
        // The destination was read back and matched. Keeping the backup any longer only risks
        // leaving plaintext copies around; a failed cleanup is harmless because pending() sees the
        // destination hash on the next launch and removes it then.
        discard(entry)
    }

    fun discard(entry: Entry) {
        runCatching { entry.directory.deleteRecursively() }
    }

    /** The destination's current SHA-256, or null when it cannot be read. */
    fun destinationHash(context: Context, entry: Entry): String? =
        entry.destination?.let { hashUri(context, it) }

    /**
     * True when a destination that had a backup no longer matches it. False when there was no
     * original to compare (a Save As target) or the destination is still the backed-up bytes.
     */
    fun destinationChangedSinceBackup(context: Context, entry: Entry): Boolean {
        if (entry.backup == null) return false
        val current = destinationHash(context, entry) ?: return true
        return current != entry.originalHash
    }

    /**
     * Journals that still need user attention. An entry is cleaned up automatically only when the
     * destination already equals the prepared copy, or when the destination is still the backed-up
     * original and the prepared copy adds nothing. Those cases cannot lose data.
     */
    fun pending(context: Context): List<Entry> {
        val root = File(context.noBackupFilesDir, ROOT)
        val entries = root.listFiles()
            ?.mapNotNull { directory ->
                if (!directory.isDirectory) return@mapNotNull null
                // A directory without a journal may still be a save that is preparing its
                // durable copies right now; leave it for a later launch instead of racing it.
                readJournal(directory)
            }
            ?: return emptyList()
        return entries.filter { entry ->
            val recovery = entry.recoveryFile
            if (recovery == null) {
                discard(entry)
                return@filter false
            }
            val current = entry.destination?.let { hashUri(context, it) }
            if (entry.backup != null && current != null && current == entry.originalHash &&
                (entry.prepared == null || entry.preparedHash == entry.originalHash)) {
                // The write never changed the destination and there is no different prepared
                // copy to recover.
                discard(entry)
                false
            } else if (entry.prepared != null && current != null && current == entry.preparedHash) {
                // The write actually completed, even if the process died before cleanup.
                discard(entry)
                false
            } else {
                true
            }
        }.sortedBy { it.createdAt }
    }

    /**
     * Puts the original back only when the destination is no longer a valid package (so it is
     * almost certainly our partial write). A valid but different package may be an independent
     * change and is left untouched; [UNSAFE] means the caller should keep the recovery copy.
     */
    fun restoreIfSafe(context: Context, entry: Entry): RestoreResult {
        val destination = entry.destination ?: return RestoreResult.UNSAFE
        val current = hashUri(context, destination) ?: return RestoreResult.UNSAFE
        if (entry.prepared != null && current == entry.preparedHash) return RestoreResult.SUCCEEDED
        if (entry.backup == null) return RestoreResult.UNSAFE
        if (current == entry.originalHash) return RestoreResult.UNCHANGED
        if (validateOfficeUri(context, destination, entry.kind)) return RestoreResult.UNSAFE
        if (!copyFileToUri(context, entry.backup, destination)) return RestoreResult.FAILED
        val restored = hashUri(context, destination)
        if (restored != entry.originalHash) return RestoreResult.FAILED
        discard(entry)
        return RestoreResult.RESTORED
    }

    /** Copies the best valid recovery file to a user-picked destination, verifying the bytes. */
    fun copyRecovered(context: Context, entry: Entry, target: Uri): Boolean {
        return try {
            fun valid(file: File?): File? = file?.takeIf {
                it.isFile && it.length() > 0L && validateOfficePackage(it, entry.kind)
            }
            val source = valid(entry.prepared) ?: valid(entry.backup) ?: return false
            val expected = hashFile(source)
            val copied = runCatching {
                val out = context.contentResolver.openOutputStream(target, "wt")
                    ?: context.contentResolver.openOutputStream(target, "w")
                    ?: return@runCatching false
                out.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
                hashUri(context, target) == expected
            }.getOrDefault(false)
            if (!copied) {
                // The user picked a new document; do not leave a partial package behind.
                runCatching { context.contentResolver.delete(target, null, null) }
            } else {
                discard(entry)
            }
            copied
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            runCatching { context.contentResolver.delete(target, null, null) }
            false
        }
    }

    fun suggestedRecoveredName(entry: Entry): String {
        val base = entry.displayName.substringBeforeLast('.').ifBlank { "document" }
        return "$base (recovered).${entry.kind}"
    }

    fun mimeType(entry: Entry): String = when (entry.kind) {
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }

    internal fun validateOfficePackage(file: File, kind: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("[Content_Types].xml") != null &&
                    (zip.getEntry(mainEntry(kind))?.size ?: 0L) != 0L
            }
        }.getOrDefault(false)
    }

    internal fun validateOfficeUri(context: Context, uri: Uri, kind: String): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var contentTypes = false
                var main = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "[Content_Types].xml") contentTypes = true
                    if (entry.name == mainEntry(kind)) main = true
                    if (contentTypes && main) break
                    zip.closeEntry()
                }
                contentTypes && main
            }
        } ?: false
    }.getOrDefault(false)

    private fun mainEntry(kind: String): String =
        if (kind == "xlsx") "xl/workbook.xml" else "word/document.xml"

    private fun readJournal(directory: File): Entry? {
        val journal = File(directory, JOURNAL)
        if (!journal.isFile) return null
        return runCatching {
            val properties = Properties().apply {
                FileInputStream(journal).use { load(it) }
            }
            if (properties.getProperty("version") != VERSION) return@runCatching null
            val kind = properties.getProperty("kind")
                ?.takeIf { it == "docx" || it == "xlsx" }
                ?: return@runCatching null
            fun child(key: String): File? = properties.getProperty(key)
                ?.takeIf { it.isNotBlank() }
                ?.let { File(directory, it) }
                ?.takeIf { it.isFile }
            Entry(
                directory = directory,
                destination = properties.getProperty("destination")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Uri.parse(it) },
                displayName = properties.getProperty("displayName").orEmpty(),
                kind = kind,
                backup = child("backup"),
                prepared = child("prepared"),
                originalHash = properties.getProperty("originalHash").orEmpty(),
                preparedHash = properties.getProperty("preparedHash").orEmpty(),
                state = runCatching {
                    State.valueOf(properties.getProperty("state") ?: State.FAILED.name)
                }.getOrDefault(State.FAILED),
                createdAt = properties.getProperty("createdAt")?.toLongOrNull()
                    ?: directory.lastModified()
            )
        }.getOrNull()
    }

    private fun writeJournal(entry: Entry, state: State) {
        val properties = Properties().apply {
            setProperty("version", VERSION)
            setProperty("destination", entry.destination?.toString().orEmpty())
            setProperty("displayName", entry.displayName)
            setProperty("kind", entry.kind)
            setProperty("backup", entry.backup?.name.orEmpty())
            setProperty("prepared", entry.prepared?.name.orEmpty())
            setProperty("originalHash", entry.originalHash)
            setProperty("preparedHash", entry.preparedHash)
            setProperty("state", state.name)
            setProperty("createdAt", entry.createdAt.toString())
        }
        val journal = File(entry.directory, JOURNAL)
        val temp = File(entry.directory, "$JOURNAL.tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
        if (!temp.renameTo(journal)) {
            temp.copyTo(journal, overwrite = true)
            temp.delete()
        }
    }

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun copyUriToFile(context: Context, source: Uri, target: File): Boolean {
        var copied = false
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
                copied = true
            }
        } catch (_: Exception) {
            copied = false
        }
        if (!copied) target.delete()
        return copied
    }

    private fun copyFileToUri(context: Context, source: File, target: Uri): Boolean = runCatching {
        val output = context.contentResolver.openOutputStream(target, "wt")
            ?: context.contentResolver.openOutputStream(target, "w")
            ?: return@runCatching false
        output.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
        hashUri(context, target) == hashFile(source)
    }.getOrDefault(false)

    private fun hashFile(file: File): String =
        FileInputStream(file).use { input -> hashStream(input) }

    private fun hashUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> hashStream(input) }
    }.getOrNull()

    private fun hashStream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(hex)
    }
}
