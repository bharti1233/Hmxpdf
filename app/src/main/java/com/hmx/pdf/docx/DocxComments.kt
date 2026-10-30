package com.hmx.pdf.docx

/**
 * `word/comments.xml`. The part is only rewritten when the editor changes it: additions append,
 * deletions splice out, and a deleted-but-then-undone comment (its reference is still in the
 * document) is written back, so a save never produces a reference without its element.
 *
 * Anchors: Word's shape for a "new comment" with no selection — a zero-length
 * `commentRangeStart/End` pair at the paragraph start and a `commentReference` run. The markers
 * ride [DocPara.extras], which the writer already re-emits verbatim for edited paragraphs.
 */
class CommentInfo(
    val id: String,
    val author: String,
    val initials: String,
    val date: String,
    val text: String
)

class DocxComments(val path: String?, val xml: String?) {

    private data class Added(val info: CommentInfo, val raw: String)

    private val added = ArrayList<Added>()
    /** id → the raw element removed from [xml] (kept so an undone deletion can be written back). */
    private val removed = HashMap<String, String>()

    /** The part's root element (`w:comments`) — `scan()` returns a pseudo-root above it. */
    private fun partRoot(x: String): XNode? =
        XmlScan.scan(x).children.firstOrNull { it.local == "comments" }

    companion object {
        private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        fun parse(path: String?, xml: String?): DocxComments {
            if (path == null) return DocxComments(null, null)
            return DocxComments(path, xml)
        }

        /** The reference run: a single object character, exactly like footnote marks and pictures. */
        fun reference(id: String): CharFmt = CharFmt(
            "", null, "<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference w:id=\"$id\"/></w:r>"
        )

        private fun markers(id: String) =
            "<w:commentRangeStart w:id=\"$id\"/><w:commentRangeEnd w:id=\"$id\"/>"

        /** Puts a comment's markers and reference run on [p] (markers at the start, run at the end). */
        fun anchorPara(p: DocPara, id: String): DocPara =
            p.edited(extras = markers(id) + p.extras, text = p.text + OBJ_CHAR, fmts = p.fmts + reference(id))

        /** Removes every marker and reference run of [id] from [p]. */
        fun stripPara(p: DocPara, id: String): DocPara {
            val ref = """w:commentReference w:id="$id"""
            val drop = (0 until p.text.length).filter { p.fmts[it].obj?.contains(ref) == true }.toSet()
            val extras = p.extras
                .replace(Regex("""<w:commentRangeStart[^>]*w:id="$id"[^>]*/>"""), "")
                .replace(Regex("""<w:commentRangeEnd[^>]*w:id="$id"[^>]*/>"""), "")
            if (drop.isEmpty() && extras == p.extras) return p
            val text = StringBuilder(p.text.length)
            val fmts = ArrayList<CharFmt>(p.text.length)
            for (i in 0 until p.text.length) if (i !in drop) { text.append(p.text[i]); fmts.add(p.fmts[i]) }
            val marks = p.marks.map { m -> m - drop.count { it < m } }.distinct()
            return p.edited(text = text.toString(), fmts = fmts, extras = extras, marks = marks)
        }
    }

    /** Every comment recorded in the part: base entries plus additions (deletions live in [removed]). */
    val items: List<CommentInfo>
        get() {
            val base = ArrayList<CommentInfo>()
            if (xml != null) {
                val part = partRoot(xml) ?: return emptyList()
                for (c in part.childrenNamed("comment")) {
                    val id = c.attr(xml, "id") ?: continue
                    val text = StringBuilder()
                    collectText(c, xml, text)
                    base.add(CommentInfo(id, c.attr(xml, "author") ?: "", c.attr(xml, "initials") ?: "", c.attr(xml, "date") ?: "", text.toString()))
                }
            }
            base.addAll(added.map { it.info })
            return base
        }

    val modified: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

    private fun collectText(n: XNode, xml: String, sb: StringBuilder) {
        if (n.local == "t") sb.append(XmlScan.unescape(n.inner(xml)))
        for (c in n.children) collectText(c, xml, sb)
    }

    private fun nextId(): String {
        var max = 999
        for (i in items) i.id.toIntOrNull()?.let { if (it > max) max = it }
        for (a in added) a.info.id.toIntOrNull()?.let { if (it > max) max = it }
        return (max + 1).toString()
    }

    /** Records a new comment; its element is written to the part, its anchor to the paragraph. */
    fun add(author: String, initials: String, date: String, text: String): String {
        val id = nextId()
        val info = CommentInfo(id, author, initials, date, text)
        val payload = StringBuilder()
        DocxWriter.para(DocPara(0, null, "<w:p>", "", "", text, List(text.length) { CharFmt.Plain }, editable = true), payload)
        val raw = "<w:comment w:id=\"$id\" w:author=\"${XmlScan.escape(author)}\" w:initials=\"${XmlScan.escape(initials)}\"" +
            " w:date=\"${XmlScan.escape(date)}\">$payload</w:comment>"
        added.add(Added(info, raw))
        return id
    }

    /** Removes a comment; [stripPara] must clear its anchors from every paragraph. */
    fun remove(id: String) {
        val a = added.firstOrNull { it.info.id == id }
        if (a != null) { added.remove(a); removed[id] = a.raw; return }
        val base = xml ?: return
        val node = partRoot(base)?.childrenNamed("comment")?.firstOrNull { it.attr(base, "id") == id } ?: return
        removed[id] = node.text(base)
    }

    /**
     * The part's current content: base minus removed (unless one is still referenced — an undone
     * deletion keeps its element), plus additions. Null means "leave the part alone".
     */
    fun document(references: Set<String> = emptySet()): String? {
        if (!modified) return null
        var out = xml
            ?: "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:comments xmlns:w=\"$W_NS\"></w:comments>"
        if (xml != null && removed.isNotEmpty()) {
            val splice = (partRoot(out)?.childrenNamed("comment") ?: emptyList())
                .filter { removed.containsKey(it.attr(out, "id")) && !references.contains(it.attr(out, "id")) }
                .sortedByDescending { it.start }
            for (n in splice) out = out.substring(0, n.start) + out.substring(n.end)
        }
        for ((id, raw) in removed) {
            if (references.contains(id) && !out.contains(raw)) {
                val at = out.lastIndexOf("</")
                if (at > 0) out = out.substring(0, at) + raw + out.substring(at)
            }
        }
        if (added.isNotEmpty()) {
            val at = out.lastIndexOf("</")
            if (at > 0) out = out.substring(0, at) + added.joinToString("") { it.raw } + out.substring(at)
        }
        return out
    }
}
