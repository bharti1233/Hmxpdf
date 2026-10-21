package com.hmx.pdf.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmx.pdf.data.repository.LocalDocumentMirror
import com.hmx.pdf.data.repository.RecentFile
import com.hmx.pdf.data.repository.RecentFilesManager
import com.hmx.pdf.data.repository.SaveRecovery
import com.hmx.pdf.docx.DocBlock
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.DocxEdits
import com.hmx.pdf.docx.DocxPackage
import com.hmx.pdf.docx.DocxReader
import com.hmx.pdf.docx.DocxWriter
import com.hmx.pdf.docx.findPara
import com.hmx.pdf.docx.mapContainerOf
import com.hmx.pdf.docx.replacePara
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The Word editor's document: loads a .docx into blocks, keeps undo/redo as whole-document
 * snapshots (cheap: blocks are immutable and shared), and saves by patching the pristine local
 * mirror with [DocxWriter], so only what was edited is rewritten.
 *
 * The document lives in snapshot state rather than a flow: text fields read it in the same frame
 * they write it, which an asynchronously collected flow can't guarantee (the IME would see stale
 * text and the caret would jump).
 */
class DocxEditorViewModel : ViewModel() {

    data class Meta(
        val fileName: String = "",
        /** Local mirror of the file as opened (never modified). */
        val fileUri: Uri? = null,
        /** The uri the user opened — the "Save" target. */
        val sourceUri: Uri? = null,
        val isLoading: Boolean = true,
        val error: String? = null,
        val saving: Boolean = false
    )

    var meta by mutableStateOf(Meta())
        private set
    var pkg by mutableStateOf<DocxPackage?>(null)
        private set
    var doc by mutableStateOf<DocState?>(null)
        private set
    private var saved: DocState? = null
    /** The document as opened: the local mirror already holds exactly this. */
    private var original: DocState? = null
    private val undoStack = ArrayList<DocState>()
    private val redoStack = ArrayList<DocState>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** Bumped when undo/redo replace the document, so an open text field re-reads its paragraph. */
    var restoreEpoch by mutableStateOf(0)
        private set

    val dirty: Boolean get() = doc != null && doc !== saved
    val editable: Boolean get() = pkg?.writable == true && meta.fileUri?.scheme == "file"

    private var started = false
    /** The page last read in this document (from Recents), to reopen at. */
    var resumePage = 0
        private set
    private var lastKey: String? = null
    private var lastAt = 0L

    fun load(context: Context, uri: Uri, nameHint: String? = null) {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            resumePage = withContext(Dispatchers.IO) {
                runCatching { RecentFilesManager.getRecents(context).firstOrNull { it.uriString == uri.toString() }?.currentPage }.getOrNull() ?: 0
            }
            val name = nameHint?.takeIf { it.isNotBlank() } ?: withContext(Dispatchers.IO) {
                runCatching { queryName(context, uri) }.getOrNull()
                    ?: RecentFilesManager.getRecents(context).firstOrNull { it.uriString == uri.toString() }?.name
                    ?: uri.lastPathSegment ?: "Document.docx"
            }
            val loaded = withContext(Dispatchers.IO) {
                val readable = runCatching { LocalDocumentMirror.resolve(context, uri, "docx") }.getOrDefault(uri)
                val local = runCatching { mirrorToCache(context, readable, name) }.getOrDefault(readable)
                val parsed = runCatching { context.contentResolver.openInputStream(local)?.use { DocxReader.read(it) } }.getOrNull()
                parsed to local
            }
            val (parsed, local) = loaded
            if (parsed == null) {
                meta = Meta(fileName = name, fileUri = local, sourceUri = uri, isLoading = false, error = "Couldn't read this document.")
                return@launch
            }
            pkg = parsed.first
            doc = parsed.second
            saved = parsed.second
            original = parsed.second
            meta = Meta(fileName = name, fileUri = local, sourceUri = uri, isLoading = false)
            withContext(Dispatchers.IO) {
                runCatching {
                    RecentFilesManager.addRecent(context, RecentFile(name = name, uriString = uri.toString(), timestamp = System.currentTimeMillis()))
                }
            }
        }
    }

    // ── edit log ───────────────────────────────────────────────────────────────

    /**
     * Replaces the document. Typing into one paragraph coalesces into one undo step (per [key],
     * until a pause); everything else is its own step.
     */
    private fun commit(next: DocState, key: String? = null) {
        val cur = doc ?: return
        if (next === cur) return
        val now = System.currentTimeMillis()
        val coalesce = key != null && key == lastKey && now - lastAt < 1500
        if (!coalesce) {
            undoStack.add(cur)
            if (undoStack.size > 200) undoStack.removeAt(0)
        }
        lastKey = key
        lastAt = now
        redoStack.clear()
        doc = next
        canUndo = undoStack.isNotEmpty()
        canRedo = false
    }

    /** Ends typing coalescing, so the next keystroke starts a new undo step. */
    fun breakUndoGroup() { lastKey = null }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        doc?.let { redoStack.add(it) }
        doc = prev
        lastKey = null
        canUndo = undoStack.isNotEmpty(); canRedo = true
        restoreEpoch++
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        doc?.let { undoStack.add(it) }
        doc = next
        lastKey = null
        canUndo = true; canRedo = redoStack.isNotEmpty()
        restoreEpoch++
    }

    fun para(id: Long): DocPara? = doc?.let { findPara(it.blocks, id) }

    /** Typing: the field's text changed. */
    fun typed(id: Long, newText: String, typing: com.hmx.pdf.docx.CharFmt) {
        val d = doc ?: return
        val p = findPara(d.blocks, id) ?: return
        val np = DocxEdits.typed(p, newText, typing)
        if (np === p) return
        replacePara(d.blocks, np)?.let { commit(DocState(it), "type$id") }
    }

    fun replace(para: DocPara, grouped: Boolean = false) {
        val d = doc ?: return
        replacePara(d.blocks, para)?.let { commit(DocState(it), if (grouped) "fmt${para.id}" else null) }
    }

    /** Enter: splits paragraph [id] at [at] and returns the new paragraph's id. */
    fun split(id: Long, text: String, at: Int, typing: com.hmx.pdf.docx.CharFmt): Long? {
        val d = doc ?: return null
        val p0 = findPara(d.blocks, id) ?: return null
        val p = DocxEdits.typed(p0, text, typing)
        val (a, b) = DocxEdits.split(p, at.coerceIn(0, p.text.length), pkg!!.styles)
        val blocks = mapContainerOf(d.blocks, id) { list ->
            val out = ArrayList<DocBlock>(list.size + 1)
            for (x in list) if (x is DocPara && x.id == id) { out.add(a); out.add(b) } else out.add(x)
            out
        } ?: return null
        lastKey = null
        commit(DocState(blocks))
        return b.id
    }

    /**
     * Backspace at the start of paragraph [id]: joins it to the paragraph before it. Returns that
     * paragraph's id and where the caret goes, or null when there is nothing to join with.
     */
    fun mergeWithPrevious(id: Long): Pair<Long, Int>? {
        val d = doc ?: return null
        var result: Pair<Long, Int>? = null
        val blocks = mapContainerOf(d.blocks, id) { list ->
            val i = list.indexOfFirst { it is DocPara && it.id == id }
            val prev = list.getOrNull(i - 1)
            val cur = list[i] as DocPara
            if (prev is DocPara && prev.editable) {
                val merged = DocxEdits.merge(prev, cur)
                result = prev.id to prev.text.length
                list.toMutableList().also { it[i - 1] = merged; it.removeAt(i) }
            } else if (prev != null && cur.isEmpty && i < list.size - 1) {
                // An empty paragraph after a table or picture: just remove it.
                result = -1L to 0
                list.toMutableList().also { it.removeAt(i) }
            } else list
        } ?: return null
        if (result != null) { lastKey = null; commit(DocState(blocks)) }
        return result
    }

    /** Paragraph-level change applied to every paragraph id in [ids]. */
    fun mapParas(ids: Collection<Long>, f: (DocPara) -> DocPara) {
        var blocks = doc?.blocks ?: return
        for (id in ids) {
            val p = findPara(blocks, id) ?: continue
            if (!p.editable) continue
            val np = f(p)
            if (np !== p) blocks = replacePara(blocks, np) ?: blocks
        }
        if (blocks !== doc?.blocks) { lastKey = null; commit(DocState(blocks)) }
    }

    // ── saving ─────────────────────────────────────────────────────────────────

    private fun buildPatched(context: Context): File? {
        val p = pkg ?: return null
        val d = doc ?: return null
        val src = meta.fileUri?.takeIf { it.scheme == "file" }?.path?.let(::File) ?: return null
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val safe = meta.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document.docx" }
            .let { if (it.lowercase().endsWith(".docx")) it else "$it.docx" }
        val out = File(dir, "edited_${System.currentTimeMillis()}_$safe")
        return runCatching { DocxWriter.write(p, d, src, out); out }.getOrElse { out.delete(); null }
    }

    sealed class SaveResult {
        data object Saved : SaveResult()
        data object NeedsSaveAs : SaveResult()
        data class Failed(val message: String) : SaveResult()
    }

    private sealed class WriteResult {
        data object Success : WriteResult()
        data class Failure(val message: String, val canRetryWithSaveAs: Boolean = true) : WriteResult()
    }

    fun save(context: Context, target: Uri?, onDone: (SaveResult) -> Unit) {
        if (meta.saving || !editable) return
        val dest = target ?: meta.sourceUri ?: return onDone(SaveResult.NeedsSaveAs)
        val snapshot = doc
        meta = meta.copy(saving = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val patched = buildPatched(context) ?: return@withContext SaveResult.Failed("Couldn't write the document.")
                try {
                    when (val written = writeWithRecovery(
                        context = context,
                        uri = dest,
                        file = patched,
                        displayName = meta.fileName,
                        requireBackup = target == null
                    )) {
                        WriteResult.Success -> SaveResult.Saved
                        is WriteResult.Failure ->
                            if (target == null && written.canRetryWithSaveAs) SaveResult.NeedsSaveAs
                            else SaveResult.Failed(written.message)
                    }
                } finally {
                    patched.delete()
                }
            }
            meta = meta.copy(saving = false)
            if (result is SaveResult.Saved) saved = snapshot
            onDone(result)
        }
    }

    /**
     * Prepare a durable recovery entry (backup + validated replacement), then write the replacement
     * to the SAF destination and read it back. SAF cannot promise an atomic replace; the journal is
     * what makes an interrupted write recoverable. A destination that changed after the backup is
     * never overwritten.
     */
    private fun writeWithRecovery(
        context: Context,
        uri: Uri,
        file: File,
        displayName: String,
        requireBackup: Boolean
    ): WriteResult {
        if (file.length() <= 0L) {
            return WriteResult.Failure("Couldn't prepare the document.", canRetryWithSaveAs = false)
        }
        val entry = runCatching {
            SaveRecovery.begin(context, uri, displayName, "docx", file, requireBackup)
        }.getOrElse {
            return WriteResult.Failure(
                it.message?.takeIf { message -> message.isNotBlank() }
                    ?: "Couldn't prepare a safe copy; the original was not changed.",
                canRetryWithSaveAs = false
            )
        }
        return try {
            SaveRecovery.markWriting(entry)
            if (SaveRecovery.destinationChangedSinceBackup(context, entry)) {
                // Keep the prepared copy: the destination belongs to someone else now, but the
                // user's edits are still the only recoverable copy of this save.
                SaveRecovery.markFailed(entry)
                return WriteResult.Failure(
                    "The destination changed while the document was being prepared. Nothing was written; " +
                        "a recovery copy was kept.",
                    canRetryWithSaveAs = true
                )
            }
            val prepared = entry.prepared
                ?: throw IllegalStateException("The prepared document is missing")
            val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
                ?: throw IllegalStateException("Couldn't open that location for writing")
            out.use { output ->
                prepared.inputStream().use { input -> input.copyTo(output) }
            }
            if (SaveRecovery.destinationHash(context, entry) != entry.preparedHash) {
                throw IllegalStateException("The destination did not receive the whole document")
            }
            SaveRecovery.markVerified(entry)
            WriteResult.Success
        } catch (error: Throwable) {
            SaveRecovery.markFailed(entry)
            when (SaveRecovery.restoreIfSafe(context, entry)) {
                SaveRecovery.RestoreResult.SUCCEEDED -> {
                    SaveRecovery.markVerified(entry)
                    WriteResult.Success
                }
                SaveRecovery.RestoreResult.UNCHANGED,
                SaveRecovery.RestoreResult.RESTORED -> {
                    SaveRecovery.discard(entry)
                    WriteResult.Failure(
                        "Couldn't save to that location. The original document is unchanged.",
                        canRetryWithSaveAs = true
                    )
                }
                SaveRecovery.RestoreResult.UNSAFE,
                SaveRecovery.RestoreResult.FAILED -> WriteResult.Failure(
                    "Couldn't save, and the original couldn't be restored safely. " +
                        "A recovery copy was kept; HMX PDF will offer it on the next launch.",
                    canRetryWithSaveAs = false
                )
            }
        }
    }

    fun suggestedSaveAsName(): String {
        val n = meta.fileName.ifBlank { "Document.docx" }
        return n.substringBeforeLast('.') + " (edited).docx"
    }

    /** The edited document (or the original when unedited) as a file uri. Null if an edited copy failed. */
    fun currentFile(context: Context, onDone: (Uri?) -> Unit) {
        val uri = meta.fileUri ?: return onDone(null)
        val hasEdits = doc !== original
        if (!hasEdits) return onDone(officeShareSource(false, uri) { null })
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) { buildPatched(context)?.let { Uri.fromFile(it) } }
            onDone(officeShareSource(true, uri) { out })
        }
    }

    /**
     * The document as a PDF, for the PDF button and Print, best source first: the Office engine
     * (LibreOffice, Word's own layout rules) when it is installed; otherwise the editor's own pages
     * drawn straight into a PDF ([DocxPdfWriter]), exactly as they read here, unsaved edits
     * included; and only if that fails, the old web-preview converter.
     */
    fun exportToPdf(context: Context, onDone: (Uri?) -> Unit) {
        val p = pkg
        val d = doc
        val name = meta.fileName.ifBlank { "Document.docx" }
        currentFile(context) { src ->
            viewModelScope.launch {
                val out = withContext(Dispatchers.Default) {
                    if (src != null) {
                        runCatching { com.hmx.pdf.office.OfficeEngine.tryConvert(context, src, name.lowercase()) }
                            .getOrNull()?.let { return@withContext Uri.fromFile(it) }
                    }
                    if (p != null && d != null) {
                        val dir = File(context.cacheDir, "converted_pdfs/${System.currentTimeMillis()}")
                        val pdf = File(dir, name.substringBeforeLast('.').ifBlank { "Document" }.replace('/', '_') + ".pdf")
                        if (com.hmx.pdf.ui.screen.DocxPdfWriter.write(context, p, d.blocks, pdf)) return@withContext Uri.fromFile(pdf)
                    }
                    if (src == null) null
                    else runCatching { com.hmx.pdf.utils.UniversalDocumentConverter.convertToPdf(context, src) }.getOrNull()
                }
                onDone(out)
            }
        }
    }

    private fun mirrorToCache(context: Context, uri: Uri, name: String): Uri {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document.docx" }
        val file = File(dir, "${System.currentTimeMillis()}_$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        } ?: return uri
        if (file.length() == 0L) { file.delete(); return uri }
        return Uri.fromFile(file)
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i != -1 && c.moveToFirst()) c.getString(i) else null
        }
}
