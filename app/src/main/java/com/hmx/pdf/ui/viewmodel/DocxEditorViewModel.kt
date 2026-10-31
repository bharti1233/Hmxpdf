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
import com.hmx.pdf.docx.CommentInfo
import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocIds
import com.hmx.pdf.docx.DocxPageLayout
import com.hmx.pdf.docx.OBJ_CHAR
import com.hmx.pdf.docx.PageSetup
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.DocxComments
import com.hmx.pdf.docx.DocxEdits
import com.hmx.pdf.docx.DocxPackage
import com.hmx.pdf.docx.DocxReader
import com.hmx.pdf.docx.DocxRevisions
import com.hmx.pdf.docx.DocxWriter
import com.hmx.pdf.docx.RevMeta
import com.hmx.pdf.docx.findPara
import com.hmx.pdf.docx.forEachPara
import com.hmx.pdf.docx.mapContainerOf
import com.hmx.pdf.docx.replacePara
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The editor's own identity on comments it creates (no user account exists to attribute). */
private const val COMMENT_AUTHOR = "HMXPDF"
private const val COMMENT_INITIALS = "H"

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
    // The test-mirror half sits alone: unparenthesized, the elvis binds to the getter's left side
    // (`(mirror ?: uri) == "file"`) and any forced Boolean would compare against "file" as false.
    val editable: Boolean get() = pkg?.writable == true && (testFileMirror ?: (meta.fileUri?.scheme == "file"))

    /**
     * The session mode: documents open in View Mode (read-only); Edit is the explicit header
     * toggle. The VM owns it so every mutating method below can guard on one shared flag — the
     * screen hides its editing controls from this same value. (Backing state stays private:
     * a `private set` on a property named `editMode` would generate a JVM setter clashing with
     * [setEditMode].)
     */
    private var mode by mutableStateOf(false)
    val editMode: Boolean get() = mode

    /** Enters edit mode (writable documents only); leaving it is always allowed. */
    fun setEditMode(on: Boolean) {
        if (on && !editable) return
        if (!on) lastKey = null
        mode = on
    }

    /** Test seam: plain JVM unit tests build no android.net.Uri, so they force the mirror half of [editable]. */
    internal var testFileMirror: Boolean? = null

    /** Test seam: installs a parsed package/state without [load]'s picker and mirror pipeline. */
    internal fun primeForTest(parsed: Pair<DocxPackage, DocState>) {
        pkg = parsed.first; doc = parsed.second; saved = parsed.second; original = parsed.second
        testFileMirror = true
        resetPageLayoutExtras()
    }

    /**
     * Track Changes. Off by default every session (nothing is captured until asked, matching the
     * editor's other opt-in behavior). Enabling needs Edit Mode; disabling — which the pending
     * revisions all survive — is always allowed.
     */
    var tracking by mutableStateOf(false)
        private set

    @JvmName("trackChanges") // clashes with the property's generated JVM setter otherwise
    fun setTracking(on: Boolean) {
        if (on && (!editMode || !editable)) return
        if (!on) lastCapture = null
        tracking = on
    }

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
            // One recents read feeds both the display name fallback and the resume page. The old
            // path decoded the same JSON twice on first open. The permission binder calls go to
            // IO with the rest of the open work instead of running before the first frame.
            val (name, page) = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val recent = runCatching {
                    RecentFilesManager.getRecents(context).firstOrNull { it.uriString == uri.toString() }
                }.getOrNull()
                val resolved = nameHint?.takeIf { it.isNotBlank() }
                    ?: runCatching { queryName(context, uri) }.getOrNull()
                    ?: recent?.name
                    ?: uri.lastPathSegment ?: "Document.docx"
                resolved to (recent?.currentPage ?: 0)
            }
            resumePage = page
            val loaded = withContext(Dispatchers.IO) {
                // This route only opens .docx, so do not let a display name like "report.docx (1)"
                // become a bogus mirror extension.
                val readable = runCatching { LocalDocumentMirror.localFile(context, uri, "docx") }.getOrDefault(uri)
                val local = if (readable.scheme == "file") readable
                    else runCatching { mirrorToCache(context, readable, name) }.getOrDefault(readable)
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
            resetPageLayoutExtras()
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
        if (!editMode) return
        val prev = undoStack.removeLastOrNull() ?: return
        doc?.let { redoStack.add(it) }
        doc = prev
        lastKey = null
        canUndo = undoStack.isNotEmpty(); canRedo = true
        restoreEpoch++
    }

    fun redo() {
        if (!editMode) return
        val next = redoStack.removeLastOrNull() ?: return
        doc?.let { undoStack.add(it) }
        doc = next
        lastKey = null
        canUndo = true; canRedo = redoStack.isNotEmpty()
        restoreEpoch++
    }

    fun para(id: Long): DocPara? = doc?.let { findPara(it.blocks, id) }

    // ── tracked-change capture ─────────────────────────────────────────────────

    /** One row of the Revisions panel: a pending insertion or deletion. */
    data class RevisionInfo(
        val paraId: Long,
        /** The revision id (`w:id`). An edit that replaced text carries both its shapes under it. */
        val id: Int,
        val ins: Boolean,
        /** The revised text, abbreviated. */
        val excerpt: String,
        val author: String,
        val date: String
    )

    /** Every pending tracked change, in document order, tables and previews included. */
    fun pendingRevisions(): List<RevisionInfo> {
        val out = ArrayList<RevisionInfo>()
        doc?.blocks?.let { blocks ->
            forEachPara(blocks) { p ->
                for (r in DocxRevisions.pending(p)) out.add(
                    RevisionInfo(p.id, r.id, r.ins, DocxRevisions.excerpt(p, r.id), p.revs[r.id]?.author ?: "", p.revs[r.id]?.date ?: "")
                )
            }
        }
        return out
    }

    /**
     * The revision a capture lands under — null with tracking off (plain editing path). Continuous
     * typing coalesces into one revision, exactly like its undo step (same key, same window);
     * every other action passes a fresh key.
     */
    private fun capture(key: String): Pair<Int, RevMeta>? {
        if (!tracking) return null
        val now = System.currentTimeMillis()
        lastCapture?.let { last -> if (last.first == key && now - lastCaptureAt < 1500) {
            lastCaptureAt = now
            return last.second to last.third
        } }
        val meta = RevMeta(COMMENT_AUTHOR, docDate())
        val c = Triple(key, DocxRevisions.nextId(), meta)
        lastCapture = c
        lastCaptureAt = now
        return c.second to c.third
    }

    private var lastCapture: Triple<String, Int, RevMeta>? = null
    private var lastCaptureAt = 0L
    private var captureOps = 0

    /** The unique key of a one-shot action (each replace is its own revision). */
    private fun freshKey(): String = "op${captureOps++}"

    /** `w:date` in the machine format comments use: fixed US locale, no java.time (min SDK 23). */
    private fun docDate(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()) + "Z"

    /**
     * Typing: the field's text changed. Returns the committed paragraph's caret when tracking
     * captured the edit (retained deleted text re-enters the paragraph, so the field must
     * re-read its buffer), or -1 — today's plain path, no re-read.
     */
    fun typed(id: Long, newText: String, typing: com.hmx.pdf.docx.CharFmt): Int {
        if (!editMode) return -1
        val d = doc ?: return -1
        val p = findPara(d.blocks, id) ?: return -1
        val np = if (tracking) {
            val (revId, meta) = capture("type$id")!!
            DocxEdits.typed(p, newText, typing, revId, meta)
        } else DocxEdits.typed(p, newText, typing)
        if (np === p) return -1
        commit(DocState(replacePara(d.blocks, np) ?: return -1), "type$id")
        if (!tracking) return -1
        val (pre, suf) = DocxEdits.diff(p.text, newText)
        val retained = (pre until p.text.length - suf).count { p.fmts[it].rev == null }
        return pre + retained + (newText.length - suf - pre)
    }

    fun replace(para: DocPara, grouped: Boolean = false) {
        if (!editMode) return
        val d = doc ?: return
        replacePara(d.blocks, para)?.let { commit(DocState(it), if (grouped) "fmt${para.id}" else null) }
    }

    /** Enter: splits paragraph [id] at [at] and returns the new paragraph's id. */
    fun split(id: Long, text: String, at: Int, typing: com.hmx.pdf.docx.CharFmt): Long? {
        if (!editMode) return null
        val d = doc ?: return null
        val p0 = findPara(d.blocks, id) ?: return null
        val np = if (tracking) {
            val (revId, meta) = capture("type$id")!!
            DocxEdits.typed(p0, text, typing, revId, meta)
        } else DocxEdits.typed(p0, text, typing)
        val (a, b) = DocxEdits.split(np, at.coerceIn(0, np.text.length), pkg!!.styles)
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
        if (!editMode) return null
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
        if (!editMode) return
        var blocks = doc?.blocks ?: return
        for (id in ids) {
            val p = findPara(blocks, id) ?: continue
            if (!p.editable) continue
            val np = f(p)
            if (np !== p) blocks = replacePara(blocks, np) ?: blocks
        }
        if (blocks !== doc?.blocks) { lastKey = null; commit(DocState(blocks)) }
    }

    /** Replaces every occurrence of the query — one undo step ("Replace all"). */
    fun replaceAll(query: String, replacement: String, matchCase: Boolean) {
        val d = doc ?: return
        val ids = ArrayList<Long>()
        val hits = HashMap<Long, List<IntRange>>()
        forEachPara(d.blocks) { p ->
            val ms = DocxEdits.matchesIn(p.text, query, !matchCase)
            if (ms.isNotEmpty()) { ids.add(p.id); hits[p.id] = ms }
        }
        if (ids.isEmpty()) return
        val (revId, meta) = if (tracking) capture("replace-all${freshKey()}")!! else null to null
        mapParas(ids) { p -> hits[p.id]?.let { DocxEdits.replaceAllIn(p, it, replacement, revId, meta) } ?: p }
    }

    /** Replaces one match on its paragraph — one undo step. The range must come from a live match. */
    fun replaceOne(id: Long, range: IntRange, replacement: String) {
        val (revId, meta) = if (tracking) capture(freshKey())!! else null to null
        mapParas(listOf(id)) { cur -> DocxEdits.replaceRange(cur, range.first, range.last + 1, replacement, revId, meta) }
    }

    // ── comments ───────────────────────────────────────────────────────────────

    /**
     * Adds a comment anchored to [paraId]'s start — one undo step. [paraId] null (or a paragraph
     * that has since gone) falls back to the first editable paragraph, so "Add" works even with no
     * caret session: the anchor survives focus being consumed by the field that requested it.
     */
    fun addComment(paraId: Long?, text: String): Boolean {
        if (!editMode || !editable || text.isBlank()) return false
        val cm = pkg?.comments ?: return false
        val blocks = doc?.blocks ?: return false
        val target = paraId?.let { findPara(blocks, it) }?.takeIf { p -> p.editable }
            ?: firstEditable(blocks)
            ?: return false
        // w:date is a machine format: fixed US locale, no java.time (min SDK 23).
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()) + "Z"
        val id = cm.add(COMMENT_AUTHOR, COMMENT_INITIALS, date, text)
        mapParas(listOf(target.id)) { DocxComments.anchorPara(it, id) }
        return true
    }

    /** Deletes a comment everywhere: part element, range markers, reference runs — one undo step. */
    fun deleteComment(id: String) {
        if (!editMode || !editable) return
        val cm = pkg?.comments ?: return
        cm.remove(id)
        val all = ArrayList<Long>()
        doc?.blocks?.let { blocks -> forEachPara(blocks) { p -> all.add(p.id) } }
        mapParas(all) { DocxComments.stripPara(it, id) }
    }

    // ── tracked-change resolution ──────────────────────────────────────────────

    /** Resolves the one pending revision of [info] — one undo step, other revisions untouched. */
    fun resolveRevision(info: RevisionInfo, accept: Boolean) {
        if (!editMode || !editable) return
        mapParas(listOf(info.paraId)) { p -> if (accept) DocxRevisions.accept(p, info.id) else DocxRevisions.reject(p, info.id) }
    }

    /** Accepts or rejects every pending revision at once — a single commit, one undo step. */
    fun resolveAllRevisions(accept: Boolean) {
        if (!editMode || !editable) return
        val all = ArrayList<Long>()
        doc?.blocks?.let { blocks -> forEachPara(blocks) { p -> all.add(p.id) } }
        mapParas(all) { p -> if (accept) DocxRevisions.acceptAll(p) else DocxRevisions.rejectAll(p) }
    }

    /** The first editable paragraph (the breaks and page-setup fallback target), or null. */
    fun firstEditableId(): Long? = doc?.blocks?.let { firstEditable(it)?.id }

    /** The first body-level editable paragraph — the only valid place for a section break. */
    fun firstBodyId(): Long? {
        val d = doc ?: return null
        for (b in d.blocks) if (b is DocPara && b.editable) return b.id
        return null
    }

    /** The first editable paragraph (the add-comment anchor fallback), or null. */
    private fun firstEditable(blocks: List<com.hmx.pdf.docx.DocBlock>): DocPara? {
        var found: DocPara? = null
        forEachPara(blocks) { p -> if (found == null && p.editable) found = p }
        return found
    }

    /** The comments anchored in the document right now — additions undone show their absence. */
    fun comments(): List<CommentInfo> {
        val cm = pkg?.comments ?: return emptyList()
        val d = doc ?: return emptyList()
        val anchored = HashSet<String>()
        forEachPara(d.blocks) { p ->
            Regex("""<w:commentRangeStart[^>]*w:id="(\d+)""").findAll(p.extras).forEach { anchored.add(it.groupValues[1]) }
        }
        return cm.items.filter { it.id in anchored }
    }

    // ── page layout (P3) ────────────────────────────────────────────────────────

    /**
     * The trailing section's page setup as the document blocks hold it right now (not the mirror's
     * `pkg.page`), so preview and PDF follow the undo stack, never a stale value.
     */
    fun livePage(): PageSetup {
        val d = doc ?: return pkg?.page ?: PageSetup()
        val last = DocxPageLayout.sections(d).lastOrNull() ?: return pkg?.page ?: PageSetup()
        return DocxPageLayout.pageSetupOf(last.sectPr)
    }

    /** Every section in current document order, keyed for the page-setup panel's picker. */
    fun sectionsOf(): List<DocxPageLayout.Section> {
        val d = doc ?: return emptyList()
        return DocxPageLayout.sections(d)
    }

    private fun commitSect(section: DocxPageLayout.Section, sectPr: String) {
        if (!editMode || !editable) return
        val d = doc ?: return
        val next = DocxPageLayout.withSect(d, section, sectPr)
        if (next.blocks !== d.blocks) { lastKey = null; commit(next) }
    }

    /** One section's page setup, orientation included. [key] coalesces field-by-field undo steps. */
    fun setSectionSetup(section: DocxPageLayout.Section, s: PageSetup, key: String? = null) {
        if (s == DocxPageLayout.pageSetupOf(section.sectPr)) return
        if (!editMode || !editable) return
        val d = doc ?: return
        lastKey = key
        commit(DocxPageLayout.withSect(d, section, DocxPageLayout.withPageSetup(section.sectPr, s)), key)
    }

    fun setFirstPageDifferent(section: DocxPageLayout.Section, on: Boolean) {
        if (on == DocxPageLayout.titlePg(section.sectPr)) return
        commitSect(section, DocxPageLayout.withTitlePg(section.sectPr, on))
    }

    /** Inserts a section break paragraph before [beforeId] ("nextPage"/"continuous"/"evenPage"/"oddPage"). */
    fun insertSectionBreak(beforeId: Long, type: String?): Boolean {
        if (!editMode || !editable) return false
        val d = doc ?: return false
        val source = DocxPageLayout.sectionAt(d, beforeId)
        val next = DocxPageLayout.insertSectionBreak(d, beforeId, source, type) ?: return false
        lastKey = null
        commit(next)
        return true
    }

    /** Inserts a page break into [id] at [caret] (a structural object, not text: untracked). */
    fun insertPageBreak(id: Long, caret: Int): Boolean {
        if (!editMode || !editable) return false
        val d = doc ?: return false
        val p = findPara(d.blocks, id) ?: return false
        var at = caret.coerceIn(0, p.text.length)
        while (at > 0 && p.text[at - 1] == OBJ_CHAR) at--
        val np = DocxEdits.typed(p, p.text.substring(0, at) + OBJ_CHAR + p.text.substring(at), hnBreakFmt())
        if (np === p) return false
        lastKey = null
        commit(DocState(replacePara(d.blocks, np) ?: return false))
        return true
    }

    private fun hnBreakFmt() = CharFmt(rPr = "", link = null, obj = DocxPageLayout.PAGE_BREAK_OBJ)

    /** Toggles the settings-level odd/even headers flag (settings.xml is spliced at save). */
    fun setEvenOddHeaders(on: Boolean) {
        if (!editMode || !editable) return
        if (on == evenOddHeaders()) return
        // Turning the flag off in a package without a settings part is a no-op (nothing to remove).
        if (!on && pkg?.settings == null) return
        val want = DocxPageLayout.withEvenOdd(pkg?.settings, on)
        if (want == pkg?.settings) return
        settingsChanged = true
        wantSettings = want
        lastKey = null
    }

    fun evenOddHeaders(): Boolean = DocxPageLayout.hasEvenOdd(wantSettings ?: pkg?.settings)

    // ── headers & footers (P3): part contents live here, references live in sections ──

    private val hfChanged = HashMap<String, DocState>()
    private val hfRelIds = HashMap<String, String>()
    private var settingsChanged = false
    /** The spliced settings.xml once the even/odd toggle is on (DocxPackage has no copy()). */
    private var wantSettings: String? = null

    /** Saved-parse cache per part path: paragraph ids stay stable across recompositions. */
    private val hfCache = HashMap<String, DocState>()

    private fun hfWalk(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks) { out.add(it) }
        return out
    }

    /** The paragraphs of one header/footer part: the live edit when present, else the cached parse. */
    fun hfParas(path: String): List<DocPara> {
        hfChanged[path]?.let { return hfWalk(it) }
        val saved = hfCache.getOrPut(path) { DocxPageLayout.hfState(pkg?.hfParts?.get(path) ?: "") }
        return hfWalk(saved)
    }

    /** The field buffer of one header/footer paragraph. */
    fun hfText(path: String, paraId: Long): String = hfParas(path).firstOrNull { it.id == paraId }?.text ?: ""

    /** Edits one header/footer paragraph's text (package state; no undo step — comments precedent). */
    fun hfTyped(path: String, paraId: Long, newText: String) {
        if (!editMode || !editable) return
        val cur = hfParas(path)
        val idx = cur.indexOfFirst { it.id == paraId }
        if (idx < 0 || cur[idx].text == newText) return
        val p = cur[idx]
        // Headers keep their paragraph's own last ink while typing (styles/fonts preserved).
        val np = DocxEdits.typed(p, newText, DocxEdits.fmtAt(p, p.text.length))
        val base = hfChanged[path] ?: hfCache.getOrPut(path) { DocxPageLayout.hfState(pkg?.hfParts?.get(path) ?: "") }
        val next = DocState(replacePara(base.blocks, np) ?: return)
        hfChanged[path] = next
    }

    /** Creates a brand-new header/footer part and references it on [section]; returns its path. */
    fun hfCreate(section: DocxPageLayout.Section, header: Boolean, variant: String): String? {
        if (!editMode || !editable) return null
        val p = pkg ?: return null
        if (DocxPageLayout.reference(section.sectPr, header, variant) != null) return null
        val path = DocxPageLayout.freePart(p.hfParts.keys + hfChanged.keys, !header)
        if (path in hfChanged) return null
        val para = DocPara(
            id = DocIds.next(), origin = null, openTag = "<w:p>", pPr = "", extras = "",
            text = "", fmts = emptyList(), editable = true
        )
        hfChanged[path] = DocState(listOf(para))
        val id = freeRelId()
        hfRelIds[path] = id
        commitSect(section, DocxPageLayout.withReference(section.sectPr, header, variant, id))
        return path
    }

    /** Removes this section's reference to a header/footer variant; other variants/sections untouched. */
    fun hfRemove(section: DocxPageLayout.Section, header: Boolean, variant: String) {
        if (!editMode || !editable) return
        if (DocxPageLayout.reference(section.sectPr, header, variant) == null) return
        commitSect(section, DocxPageLayout.withReference(section.sectPr, header, variant, null))
    }

    private fun freeRelId(): String {
        val ids = Regex("Id=\"(rId\\d+)\"").findAll(pkg?.relsXml ?: "").mapNotNull { m ->
            m.groupValues[1].removePrefix("rId").toIntOrNull()
        }
        return "rId${(ids.maxOrNull() ?: 0) + 1}"
    }

    /**
     * The header/footer part path a section reference points at ([rid]). `rels` maps id → resolved
     * zip path, and `hfParts` membership is the type check (only header/footer rels got collected).
     */
    fun hfPathOf(rid: String): String? {
        val p = pkg ?: return null
        return p.rels[rid]?.takeIf { it in p.hfParts }
    }

    /** Package-level layout state belongs to one document: clearing at every (re)load. */
    private fun resetPageLayoutExtras() {
        hfChanged.clear(); hfRelIds.clear(); hfCache.clear(); settingsChanged = false; wantSettings = null
    }

    /** The package half of save: header/footer parts and settings ride along with the body patch. */
    private fun extras() = DocxWriter.Extras(
        hfSave = hfChanged.toMap(),
        hfRelIds = hfRelIds.toMap(),
        settings = if (settingsChanged) wantSettings else null
    )

    private fun buildPatched(context: Context): File? {
        val p = pkg ?: return null
        val d = doc ?: return null
        val src = meta.fileUri?.takeIf { it.scheme == "file" }?.path?.let(::File) ?: return null
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val safe = meta.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document.docx" }
            .let { if (it.lowercase().endsWith(".docx")) it else "$it.docx" }
        val out = File(dir, "edited_${System.currentTimeMillis()}_$safe")
        return runCatching { DocxWriter.write(p, d, src, out, extras()); out }.getOrElse { out.delete(); null }
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
        // The fallback renderer follows the live page setup (blocks), not a possibly-stale mirror.
        pkg?.page = livePage()
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
