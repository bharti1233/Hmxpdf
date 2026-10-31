package com.hmx.pdf.docx

/**
 * Page-layout kernels (P3-1…P3-5): everything a section's `w:sectPr` holds — page size, margins,
 * orientation, the section-break type, header/footer references, and the different-first-page and
 * odd/even settings — edited surgically. Each op rewrites only the children it touches; unknown
 * children (`cols`, `docGrid`, `pgBorders`, …) survive character for character. Values are twips,
 * exactly what `w:pgSz`/`w:pgMar` store, so there is one unit system end to end.
 */
object DocxPageLayout {

    /** One section boundary: a paragraph whose `pPr` holds the section's `w:sectPr` ([paraId]), or the body-level trailing sectPr held by a [DocRaw][rawId] (`paraId` null). */
    data class Section(val paraId: Long?, val rawId: Long?, val sectPr: String)

    // Word's own bounds (ECMA/Word UI: objects between 0.1" and 22"). All values in twips.
    const val MIN_DIM = 144
    const val MAX_DIM = 31680

    val HEADER_REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/header"
    val FOOTER_REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"
    val SETTINGS_REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings"

    fun validDim(v: Int) = v in MIN_DIM..MAX_DIM

    fun validMargin(v: Int) = v in 0..MAX_DIM

    /** The sections of [state] in document order; the trailing body-level sectPr (a [DocRaw]) comes last. */
    fun sections(state: DocState): List<Section> {
        val out = ArrayList<Section>()
        var rawId: Long? = null
        var rawSect: String? = null
        for (b in state.blocks) when {
            b is DocPara -> {
                val sect = Props.child(b.pPr, "sectPr")
                if (sect != null) out.add(Section(b.id, null, sect))
            }
            b is DocRaw && b.xml.trimStart().startsWith("<w:sectPr") -> {
                rawId = b.id
                rawSect = b.xml
            }
        }
        // Synthetic empty sectPr for documents without one: editing it creates the real element.
        out.add(Section(null, rawId, rawSect ?: ""))
        return out
    }

    private fun root(sectPr: String) = XmlScan.scan(sectPr).children.firstOrNull()

    private fun attr(sectPr: String, child: String, name: String): Int? {
        if (sectPr.isEmpty()) return null
        return root(sectPr)?.child(child)?.attr(sectPr, name)?.toFloatOrNull()?.toInt()
    }

    fun pageSetupOf(sectPr: String): PageSetup {
        fun d(child: String, name: String, def: Int) = attr(sectPr, child, name) ?: def
        return PageSetup(
            width = d("pgSz", "w", 11906), height = d("pgSz", "h", 16838),
            left = d("pgMar", "left", 1440), right = d("pgMar", "right", 1440),
            top = d("pgMar", "top", 1440), bottom = d("pgMar", "bottom", 1440),
            landscape = hasOrient(sectPr),
            header = d("pgMar", "header", 720), footer = d("pgMar", "footer", 720),
            gutter = d("pgMar", "gutter", 0)
        )
    }

    fun hasOrient(sectPr: String): Boolean {
        if (sectPr.isEmpty()) return false
        return root(sectPr)?.child("pgSz")?.attr(sectPr, "orient").equals("landscape", true)
    }

    private fun pgSz(s: PageSetup) = buildString {
        append("<w:pgSz w:w=\"").append(s.width).append("\" w:h=\"").append(s.height).append('"')
        if (s.landscape) append(" w:orient=\"landscape\"")
        append("/>")
    }

    private fun pgMar(s: PageSetup) = "<w:pgMar w:top=\"${s.top}\" w:right=\"${s.right}\" w:bottom=\"${s.bottom}\" " +
        "w:left=\"${s.left}\" w:header=\"${s.header}\" w:footer=\"${s.footer}\" w:gutter=\"${s.gutter}\"/>"

    /** Rewrites [sectPr]'s pgSz/pgMar and touches nothing else. */
    fun withPageSetup(sectPr: String, s: PageSetup): String =
        Props.setSect(Props.setSect(sectPr, "pgSz", pgSz(s)), "pgMar", pgMar(s))

    fun titlePg(sectPr: String): Boolean {
        if (sectPr.isEmpty()) return false
        return root(sectPr)?.child("titlePg") != null
    }

    fun withTitlePg(sectPr: String, on: Boolean): String =
        Props.setSect(sectPr, "titlePg", if (on) "<w:titlePg/>" else null)

    /** `w:type` of the section break; null = next page (the omitted default). */
    fun sectionType(sectPr: String): String? {
        if (sectPr.isEmpty()) return null
        return root(sectPr)?.child("type")?.attr(sectPr, "val")
    }

    fun withType(sectPr: String, type: String?): String =
        Props.setSect(sectPr, "type", type?.let { "<w:type w:val=\"$it\"/>" })

    /**
     * Adds or removes one header/footer reference on [sectPr] (`variant`: "default"/"first"/"even";
     * [relId] null removes). Same-kind references of OTHER variants survive (default/first/even
     * coexist in Word's schema).
     */
    fun withReference(sectPr: String, header: Boolean, variant: String, relId: String?): String {
        val local = if (header) "headerReference" else "footerReference"
        val elem = relId?.let { "<${if (header) "w:headerReference" else "w:footerReference"} w:type=\"$variant\" r:id=\"$it\"/>" }
        return Props.setChildWhere(sectPr, "w:sectPr", Props.SECTPR_ORDER, local, elem) { n, xml ->
            n.local == local && n.attr(xml, "type") == variant
        }
    }

    /** The reference's rel id, or null: that section inherits the previous section's (or none exists). */
    fun reference(sectPr: String, header: Boolean, variant: String): String? {
        if (sectPr.isEmpty()) return null
        val r = root(sectPr) ?: return null
        return r.children.filter { it.local == (if (header) "headerReference" else "footerReference") }
            .firstOrNull { it.attr(sectPr, "type") == variant }?.attr(sectPr, "r:id")
    }

    /** Applies a section's new sectPr, wherever it lives (raw block / paragraph pPr / create it). */
    fun withSect(state: DocState, section: Section, sectPr: String): DocState = when {
        section.rawId != null ->
            DocState(state.blocks.map { if (it is DocRaw && it.id == section.rawId) it.withXml(sectPr) else it })
        section.paraId != null ->
            mapContainerOf(state.blocks, section.paraId) { list ->
                list.map { if (it is DocPara && it.id == section.paraId) it.edited(pPr = Props.setSect(it.pPr, "sectPr", sectPr)) else it }
            }?.let { DocState(it) } ?: state
        else -> DocState(state.blocks + DocRaw(DocIds.next(), sectPr))
    }

    private fun paraIndex(state: DocState, id: Long) = state.blocks.indexOfFirst { it is DocPara && it.id == id }

    /** The section [beforeId] belongs to: the one whose sectPr paragraph is the first after it, else the body-level last. */
    fun sectionAt(state: DocState, beforeId: Long): Section {
        val i = paraIndex(state, beforeId)
        val at = sections(state)
        return at.filter { s -> s.paraId != null }
            .filter { s -> paraIndex(state, s.paraId!!) > i }
            .minByOrNull { s -> paraIndex(state, s.paraId!!) }
            ?: at.last()
    }

    /**
     * Inserts a section-break paragraph (carrying [source]'s sectPr plus [type]) before [beforeId]:
     * the content before the break becomes its own section; Word's trailing sectPr semantics keep
     * the rest under the next section. One empty, editable paragraph — typical for a wide table.
     * [beforeId] must be a body-level paragraph: Word ignores section breaks inside table cells.
     */
    fun insertSectionBreak(state: DocState, beforeId: Long, source: Section, type: String?): DocState? {
        if (state.blocks.none { it is DocPara && it.id == beforeId }) return null
        val sectPr = withType(source.sectPr, type)
        val np = DocPara(
            id = DocIds.next(), origin = null, openTag = "<w:p>",
            pPr = Props.setP("", "sectPr", sectPr), extras = "", text = "", fmts = emptyList(), editable = true
        )
        val blocks = mapContainerOf(state.blocks, beforeId) { list ->
            val i = list.indexOfFirst { it is DocPara && it.id == beforeId }
            list.toMutableList().apply { add(i, np) }
        } ?: return null
        return DocState(blocks)
    }

    /** The break object the reader already models: the full `w:br type="page"` run as an OBJ_CHAR fmt. */
    const val PAGE_BREAK_OBJ = "<w:r><w:br w:type=\"page\"/></w:r>"

    /** Next free "header|footerN.xml" part name for creating a part. */
    fun freePart(parts: Set<String>, footer: Boolean): String {
        val base = if (footer) "footer" else "header"
        val max = parts.mapNotNull { p ->
            Regex("(?:^|/)${base}(\\d+)\\.xml$").find(p)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return "word/${base}${max + 1}.xml"
    }

    /** `w:hdr` / `w:ftr` part from edited paragraph blocks — the writer's own run serializer keeps rPr/objects/links. */
    fun hfXml(list: List<DocBlock>, header: Boolean): String {
        val root = if (header) "w:hdr" else "w:ftr"
        val sb = StringBuilder()
        DocxWriter.blocks(list, sb)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<$root xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" + sb + "</$root>"
    }

    /** The part parses back into editable paragraphs (container walk: rPr, runs, objects preserved). */
    fun hfState(xml: String?): DocState {
        val root = xml?.let { XmlScan.scan(it).children.firstOrNull() } ?: return DocState(emptyList())
        if (root.end <= root.startTagEnd) return DocState(emptyList())
        val blocks = DocxReader.Ctx(xml).container(root.children, readOnly = false)
        return DocState(blocks)
    }

    // ── settings.xml: the odd/even flag lives globally, not per section ──

    fun hasEvenOdd(settings: String?): Boolean {
        if (settings == null) return false
        return root(settings)?.child("evenAndOddHeaders") != null
    }

    /** settings.xml child order puts evenAndOddHeaders midway; insert before the first anchor found, else last. */
    private val SETTINGS_ANCHORS = listOf(
        "bookFoldRevPrinting", "bookFoldPrinting", "printTwoOnOne", "strictFirstAndLastChars",
        "noLineBreaksAfter", "noLineBreaksBefore", "savePreviewPicture", "doNotValidateAgainstSchema",
        "saveInvalidXml", "ignoreMixedContent", "alwaysShowPlaceholderText", "doNotDemarcateInvalidXml",
        "saveXmlDataOnly", "useXSLTWhenSaving", "saveThroughXslt", "showXMLTags", "alwaysMergeEmptyNamespace",
        "updateFields", "hdrShapeDefaults", "footnotePr", "endnotePr", "compat", "docVars"
    )

    fun withEvenOdd(settings: String?, on: Boolean): String {
        if (!on) {
            // `var out = settings` keeps the declared String? type, so spell the non-null value out.
            val s = settings ?: return ""
            val r = root(s) ?: return s
            var out = s
            for (c in r.childrenNamed("evenAndOddHeaders").asReversed()) out = out.substring(0, c.start) + out.substring(c.end)
            return out
        }
        if (hasEvenOdd(settings)) return settings ?: ""
        if (settings == null || root(settings)?.local != "settings") {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:evenAndOddHeaders/></w:settings>"
        }
        val r = root(settings)!!
        val anchor = r.children.firstOrNull { it.local in SETTINGS_ANCHORS }
        val close = r.end - ("</" + r.name + ">").length
        val elem = "<w:evenAndOddHeaders/>"
        return if (anchor != null) settings.substring(0, anchor.start) + elem + settings.substring(anchor.start)
        else settings.substring(0, close) + elem + settings.substring(close)
    }
}
