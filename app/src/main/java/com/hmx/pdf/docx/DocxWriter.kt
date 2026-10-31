package com.hmx.pdf.docx

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writes an edited document back into its package. Untouched paragraphs and tables are copied from
 * the original XML verbatim; only edited ones are serialized again. Every other zip entry is
 * streamed through unchanged, except styles / numbering when the edits needed new definitions.
 */
object DocxWriter {

    private const val NUMBERING_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
    private const val STYLES_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
    private const val COMMENTS_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments"
    private const val SETTINGS_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings"
    private const val HF_HEADER_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/header"
    private const val HF_FOOTER_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun write(pkg: DocxPackage, state: DocState, source: File, dest: File, extras: Extras = Extras()) {
        val parts = parts(pkg, state, extras)
        source.inputStream().buffered().use { input ->
            dest.outputStream().buffered().use { out -> rewrite(input, out, parts.first, parts.second) }
        }
    }

    /**
     * The pieces the package needs beyond the document body: edited header/footer parts (existing
     * paths are replaced, new ones become add + relationship + content-type override; the [Extras]
     * carries the relationship ids the section references already point at) and a settings.xml
     * replacement for the odd/even flag. Empty by default, so callers that don't touch these pay nothing.
     */
    class Extras(
        val hfSave: Map<String, DocState> = emptyMap(),
        /** `path → relationship id` for added (brand-new) parts above; existing paths don't need one. */
        val hfRelIds: Map<String, String> = emptyMap(),
        val settings: String? = null
    )

    fun documentXml(pkg: DocxPackage, state: DocState): String {
        val sb = StringBuilder(pkg.xml.length + 1024)
        sb.append(pkg.xml, 0, pkg.bodyOpenEnd)
        blocks(state.blocks, sb)
        sb.append(pkg.xml, pkg.bodyCloseStart, pkg.xml.length)
        return sb.toString()
    }

    /** (entry → new bytes, new entries). */
    private fun parts(pkg: DocxPackage, state: DocState, extras: Extras = Extras()): Pair<Map<String, ByteArray>, Map<String, ByteArray>> {
        val replace = HashMap<String, ByteArray>()
        val add = LinkedHashMap<String, ByteArray>()
        val docXml = documentXml(pkg, state)
        replace[pkg.mainPath] = docXml.toByteArray(Charsets.UTF_8)
        var rels = pkg.relsXml
        var types = pkg.contentTypes
        val dir = pkg.mainPath.substringBeforeLast('/', "")

        val cm = pkg.comments
        if (cm.modified) {
            val path = cm.path ?: if (dir.isEmpty()) "comments.xml" else "$dir/comments.xml"
            val refs = Regex("w:commentReference w:id=\"(\\d+)\"").findAll(docXml).mapTo(HashSet()) { it.groupValues[1] }
            cm.document(refs)?.let {
                if (cm.xml != null) replace[path] = it.toByteArray(Charsets.UTF_8)
                else {
                    add[path] = it.toByteArray(Charsets.UTF_8)
                    rels = addRel(rels, COMMENTS_TYPE, relTarget(dir, path))
                    types = addOverride(types, "/" + path, "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml")
                }
            }
        }

        val num = pkg.numbering
        if (num.modified) {
            val abstracts = num.addedAbstract.joinToString("")
            val nums = num.addedNums.joinToString("")
            val existing = num.xml
            if (existing != null) {
                replace[pkg.numberingPath] = insertNumbering(existing, abstracts, nums).toByteArray(Charsets.UTF_8)
            } else {
                add[pkg.numberingPath] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<w:numbering xmlns:w=\"$W_NS\">$abstracts$nums</w:numbering>").toByteArray(Charsets.UTF_8)
                rels = addRel(rels, NUMBERING_TYPE, relTarget(dir, pkg.numberingPath))
                types = addOverride(types, "/" + pkg.numberingPath, "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml")
            }
        }
        val st = pkg.styles
        if (st.added.isNotEmpty()) {
            val extra = st.added.joinToString("")
            val existing = st.xml
            if (existing != null) {
                val close = existing.lastIndexOf("</")
                replace[pkg.stylesPath] = (existing.substring(0, close) + extra + existing.substring(close)).toByteArray(Charsets.UTF_8)
            } else {
                add[pkg.stylesPath] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<w:styles xmlns:w=\"$W_NS\">$extra</w:styles>").toByteArray(Charsets.UTF_8)
                rels = addRel(rels, STYLES_TYPE, relTarget(dir, pkg.stylesPath))
                types = addOverride(types, "/" + pkg.stylesPath, "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml")
            }
        }
        for ((path, ds) in extras.hfSave) {
            val header = !path.substringAfterLast('/').startsWith("footer")
            val xml = DocxPageLayout.hfXml(ds.blocks, header)
            if (path in pkg.hfParts) {
                replace[path] = xml.toByteArray(Charsets.UTF_8)
            } else {
                add[path] = xml.toByteArray(Charsets.UTF_8)
                val type = if (header) HF_HEADER_TYPE else HF_FOOTER_TYPE
                val target = relTarget(dir, path)
                val kind = "application/vnd.openxmlformats-officedocument.wordprocessingml.${if (header) "header" else "footer"}+xml"
                extras.hfRelIds[path]?.let {
                    // The relationship id is caller-chosen: the section reference points at it already.
                    rels = addRelWithId(rels, type, target, it)
                } ?: run { rels = addRel(rels, type, target) }
                types = addOverride(types, "/" + path, kind)
            }
        }
        extras.settings?.let { settingsXml ->
            if (pkg.settingsPath != null && pkg.settings != null) replace[pkg.settingsPath!!] = settingsXml.toByteArray(Charsets.UTF_8)
            else {
                val p = pkg.settingsPath ?: "word/settings.xml"
                add[p] = settingsXml.toByteArray(Charsets.UTF_8)
                rels = addRel(rels, SETTINGS_TYPE, relTarget(dir, p))
                types = addOverride(types, "/" + p, "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml")
            }
        }
        if (rels != pkg.relsXml && rels != null) {
            if (pkg.relsXml != null) replace[pkg.relsPath] = rels.toByteArray(Charsets.UTF_8) else add[pkg.relsPath] = rels.toByteArray(Charsets.UTF_8)
        }
        if (types != pkg.contentTypes && types != null) replace["[Content_Types].xml"] = types.toByteArray(Charsets.UTF_8)
        return replace to add
    }

    private fun relTarget(dir: String, path: String): String =
        if (dir.isNotEmpty() && path.startsWith("$dir/")) path.removePrefix("$dir/") else "/$path"

    /** `abstractNum`s go after the existing ones, `num`s after theirs — the schema's sequence. */
    internal fun insertNumbering(xml: String, abstracts: String, nums: String): String {
        val root = XmlScan.scan(xml).children.firstOrNull { it.local == "numbering" } ?: return xml
        val absAt = (root.children.lastOrNull { it.local == "abstractNum" } ?: root.children.lastOrNull { it.local == "numPicBullet" })?.end
            ?: root.startTagEnd
        val numAt = root.children.firstOrNull { it.local == "numIdMacAtCleanup" }?.start ?: xml.lastIndexOf("</")
        return xml.substring(0, absAt) + abstracts + xml.substring(absAt, numAt) + nums + xml.substring(numAt)
    }

    private fun addRel(rels: String?, type: String, target: String): String {
        val base = rels ?: newRels()
        var n = 1
        while (base.contains("Id=\"rIdCp$n\"")) n++
        return addRelWithId(base, type, target, "rIdCp$n")
    }

    private fun newRels() = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>"

    /** Same, with a caller-chosen id (the document's section reference is written with it in the same commit). */
    private fun addRelWithId(rels: String?, type: String, target: String, id: String): String {
        val base = rels ?: newRels()
        val close = base.lastIndexOf("</")
        return base.substring(0, close) + "<Relationship Id=\"$id\" Type=\"$type\" Target=\"$target\"/>" + base.substring(close)
    }

    private fun addOverride(types: String?, partName: String, contentType: String): String? {
        types ?: return null
        if (types.contains("PartName=\"$partName\"")) return types
        val close = types.lastIndexOf("</")
        return types.substring(0, close) + "<Override PartName=\"$partName\" ContentType=\"$contentType\"/>" + types.substring(close)
    }

    private fun rewrite(input: InputStream, output: OutputStream, replace: Map<String, ByteArray>, add: Map<String, ByteArray>) {
        ZipInputStream(input).use { zin ->
            val zout = ZipOutputStream(output)
            val buf = ByteArray(64 * 1024)
            val seen = HashSet<String>()
            while (true) {
                val e = zin.nextEntry ?: break
                val n = e.name.removePrefix("/")
                if (!seen.add(n)) continue
                val out = ZipEntry(e.name).apply { if (e.time != -1L) time = e.time }
                zout.putNextEntry(out)
                val bytes = replace[n]
                if (bytes != null) zout.write(bytes)
                else while (true) {
                    val k = zin.read(buf)
                    if (k <= 0) break
                    zout.write(buf, 0, k)
                }
                zout.closeEntry()
            }
            for ((n, bytes) in add) {
                if (n in seen) continue
                zout.putNextEntry(ZipEntry(n))
                zout.write(bytes)
                zout.closeEntry()
            }
            zout.finish()
            zout.flush()
        }
    }

    // ── serialization ──────────────────────────────────────────────────────────

    fun blocks(list: List<DocBlock>, sb: StringBuilder) {
        for (b in list) when (b) {
            is DocPara -> para(b, sb)
            is DocTable -> table(b, sb)
            is DocRaw -> sb.append(b.xml)
        }
    }

    private fun table(t: DocTable, sb: StringBuilder) {
        if (t.origin != null) { sb.append(t.origin); return }
        sb.append(t.head)
        for (item in t.items) when (item) {
            is DocRow -> {
                sb.append(item.head)
                for (ri in item.items) when (ri) {
                    is DocCell -> {
                        sb.append(ri.head)
                        blocks(ri.blocks, sb)
                        sb.append(ri.tail)
                    }
                    is DocRaw -> sb.append(ri.xml)
                }
                sb.append(item.tail)
            }
            is DocRaw -> sb.append(item.xml)
        }
        sb.append(t.tail)
    }

    private fun tagName(open: String): String =
        open.substring(1).takeWhile { !it.isWhitespace() && it != '>' && it != '/' }

    fun para(p: DocPara, sb: StringBuilder) {
        if (p.origin != null) { sb.append(p.origin); return }
        sb.append(p.openTag).append(p.pPr).append(p.extras)
        val n = p.text.length
        var i = 0
        while (i < n) {
            val link = p.fmts[i].link
            var j = i
            while (j < n && p.fmts[j].link == link) j++
            if (link != null) {
                sb.append(link)
                runs(p, i, j, sb)
                sb.append("</").append(tagName(link)).append('>')
            } else runs(p, i, j, sb)
            i = j
        }
        sb.append("</").append(tagName(p.openTag)).append('>')
    }

    private fun runs(p: DocPara, from: Int, to: Int, sb: StringBuilder) {
        var k = from
        while (k < to) {
            val f = p.fmts[k]
            if (f.obj != null) {
                wrap(p, f.rev, sb) { sb.append(f.obj) }
                k++
                continue
            }
            var m = k
            while (m < to && p.fmts[m].obj == null && p.fmts[m].rPr == f.rPr && p.fmts[m].rev == f.rev) m++
            val del = f.rev != null && !f.rev.ins
            wrap(p, f.rev, sb) { run(f.rPr, p.text, k, m, sb, del) }
            k = m
        }
    }

    /** Opens/closes a tracked-change wrapper around the runs that share [rev] (none: verbatim path). */
    private inline fun wrap(p: DocPara, rev: Rev?, sb: StringBuilder, body: () -> Unit) {
        if (rev == null) { body(); return }
        val meta = p.revs[rev.id] ?: RevMeta("", "")
        val tag = if (rev.ins) "w:ins" else "w:del"
        sb.append('<').append(tag).append(" w:id=\"").append(rev.id).append("\" w:author=\"")
            .append(XmlScan.escape(meta.author)).append("\" w:date=\"").append(XmlScan.escape(meta.date)).append("\">")
        body()
        sb.append("</").append(tag).append('>')
    }

    private fun run(rPr: String, text: String, from: Int, to: Int, sb: StringBuilder, del: Boolean = false) {
        sb.append("<w:r>").append(rPr)
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            val elem = if (del) "w:delText" else "w:t"
            sb.append('<').append(elem).append(" xml:space=\"preserve\">").append(XmlScan.escape(buf.toString()))
                .append("</").append(elem).append('>')
            buf.setLength(0)
        }
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '\t' -> { flush(); sb.append("<w:tab/>") }
                c == '\n' || c == '\r' -> { flush(); sb.append("<w:br/>") }
                c == '\u2011' -> { flush(); sb.append("<w:noBreakHyphen/>") }
                c == '\u00AD' -> { flush(); sb.append("<w:softHyphen/>") }
                c == OBJ_CHAR -> {}
                c.isHighSurrogate() -> {
                    if (i + 1 < to && text[i + 1].isLowSurrogate()) { buf.append(c).append(text[i + 1]); i++ }
                }
                c.isLowSurrogate() -> {}
                c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> {}
                else -> buf.append(c)
            }
            i++
        }
        flush()
        sb.append("</w:r>")
    }
}
