package com.hmx.pdf.docx

import java.util.concurrent.atomic.AtomicLong

/** Author/date of one tracked change, kept per paragraph by revision id (`w:id`). */
data class RevMeta(val author: String, val date: String)

/**
 * Pure resolution operations on `DocPara`s' tracked-change flags (`CharFmt.rev`). Accepting an
 * insertion keeps its text as ordinary content; accepting a deletion removes it; rejecting does
 * the inverse. Each operation rewrites exactly the characters of the revision id it names — no
 * other revision, comment anchor or formatting is touched. The paragraph loses its original XML,
 * so the next save re-serializes it through the revision-aware writer.
 */
object DocxRevisions {

    private val seq = AtomicLong(0)

    /** Seeds the id counter above every revision id a document already carries (called at parse). */
    fun seed(maxSeen: Int) { seq.set(maxSeen.coerceAtLeast(0).toLong()) }

    /** A fresh revision id, unique against the whole document even after undo re-parses. */
    fun nextId(): Int = seq.incrementAndGet().toInt()

    private enum class Op { KEEP, /** The character stays, its flag goes away. */ STRIP, /** The character goes away. */ DROP }

    private fun edit(p: DocPara, f: (Rev) -> Op): DocPara {
        val drop = HashSet<Int>()
        var revs = p.revs
        var touched = false
        for (i in p.text.indices) {
            val flag = p.fmts[i].rev ?: continue
            when (f(flag)) {
                Op.DROP -> { drop.add(i); revs = revs - flag.id; touched = true }
                Op.STRIP -> { revs = revs - flag.id; touched = true }
                Op.KEEP -> {}
            }
        }
        if (!touched) return p
        val text = StringBuilder(p.text.length)
        val fmts = ArrayList<CharFmt>(p.text.length)
        for (i in p.text.indices) if (i !in drop) {
            val old = p.fmts[i]
            fmts.add(if (old.rev != null && f(old.rev) == Op.STRIP) old.copy(rev = null) else old)
            text.append(p.text[i])
        }
        return p.edited(
            text = text.toString(), fmts = fmts, revs = revs,
            marks = p.marks.map { m -> (m - drop.count { it < m }).coerceIn(0, text.length) }.distinct()
        )
    }

    /** Accepts the one revision [id]: its insertion stays, its deletion is removed. */
    fun accept(p: DocPara, id: Int): DocPara =
        edit(p) { rev -> if (rev.id != id) Op.KEEP else if (rev.ins) Op.STRIP else Op.DROP }

    /** Rejects the one revision [id]: its insertion is removed, its deletion's text is restored. */
    fun reject(p: DocPara, id: Int): DocPara =
        edit(p) { rev -> if (rev.id != id) Op.KEEP else if (rev.ins) Op.DROP else Op.STRIP }

    /** Accepts every pending change in [p] in one pass. */
    fun acceptAll(p: DocPara): DocPara = edit(p) { if (it.ins) Op.STRIP else Op.DROP }

    /** Rejects every pending change in [p] in one pass. */
    fun rejectAll(p: DocPara): DocPara = edit(p) { if (it.ins) Op.DROP else Op.STRIP }

    /** The pending revisions of [p], in document order, one entry per id. */
    fun pending(p: DocPara): List<Rev> {
        val seen = LinkedHashMap<Int, Rev>()
        for (f in p.fmts) f.rev?.let { seen.putIfAbsent(it.id, it) }
        return seen.values.toList()
    }

    /** The ranges of [p]'s characters covered by revision [id]. */
    fun spans(p: DocPara, id: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < p.text.length) {
            if (p.fmts[i].rev?.id == id) {
                var j = i
                while (j < p.text.length && p.fmts[j].rev?.id == id) j++
                out.add(i until j)
                i = j
            } else i++
        }
        return out
    }

    /** A short excerpt of the revised text (objects collapse to a space), for lists. */
    fun excerpt(p: DocPara, id: Int): String =
        spans(p, id).joinToString("") { r -> p.text.substring(r.first, r.last + 1).replace(OBJ_CHAR, ' ') }
            .trim().let { s -> if (s.length > 40) s.take(40) + "…" else s }.ifEmpty { "…" }
}
