package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DOCX page layout: orientation, size/margins, page & section breaks, header/footer parts and
 * their references, different-first-page and odd/even settings — each through save/reopen, each
 * while tracked changes and layout-external sectPr children survive untouched.
 */
class DocxPageLayoutTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private val CT_HEADER = "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"
    private val CT_FOOTER = "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"
    private val CT_SETTINGS = "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"

    private fun docx(
        body: String,
        rels: String = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>",
        contentTypes: String = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/></Types>",
        extra: Map<String, String> = emptyMap()
    ): ByteArray {
        val parts = linkedMapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to rels,
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>$body</w:body></w:document>"
        )
        for ((n, c) in extra) parts[n] = c
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun read(bytes: ByteArray): Pair<DocxPackage, DocState> = DocxReader.read(bytes.inputStream())!!

    private fun save(bytes: ByteArray, pkg: DocxPackage, state: DocState, extras: DocxWriter.Extras = DocxWriter.Extras()): ByteArray {
        val src = File.createTempFile("src", ".docx").apply { writeBytes(bytes); deleteOnExit() }
        val out = File.createTempFile("out", ".docx").apply { deleteOnExit() }
        DocxWriter.write(pkg, state, src, out, extras)
        return out.readBytes()
    }

    private fun entry(bytes: ByteArray, name: String): String? {
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: return null
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private fun paras(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks, includePreview = false) { out.add(it) }
        return out
    }

    private fun lastSect(state: DocState) = DocxPageLayout.sections(state).last()

    private fun hfXml(root: String, text: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><$root $ns><w:p><w:r><w:t>$text</w:t></w:r></w:p></$root>"

    private fun relsOf(vararg rs: String) =
        "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            rs.joinToString("") + "</Relationships>"

    private fun typesOf(vararg os: String) =
        "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" + os.joinToString("") + "</Types>"

    private fun override(name: String, type: String) = "<Override PartName=\"$name\" ContentType=\"$type\"/>"

    // ── orientation (P3-1) ─────────────────────────────────────────────────────

    @Test
    fun readsOrientationAndMarginExtras() {
        val body = "<w:p><w:r><w:t>x</w:t></w:r></w:p>" +
            "<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>" +
            "<w:pgMar w:top=\"1000\" w:right=\"1100\" w:bottom=\"1200\" w:left=\"1300\" w:header=\"500\" w:footer=\"600\" w:gutter=\"90\"/>" +
            "<w:cols w:space=\"517\"/></w:sectPr>"
        val pkg = read(docx(body)).first
        assertEquals(16838, pkg.page.width)
        assertEquals(11906, pkg.page.height)
        assertTrue(pkg.page.landscape)
        assertEquals(1000, pkg.page.top)
        assertEquals(500, pkg.page.header)
        assertEquals(600, pkg.page.footer)
        assertEquals(90, pkg.page.gutter)
    }

    @Test
    fun orientationRoundTripThroughSave() {
        val body = "<w:p><w:r><w:t>report</w:t></w:r></w:p>" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        val original = docx(body)
        var (pkg, state) = read(original)
        var s = lastSect(state)
        val landscape = DocxPageLayout.pageSetupOf(s.sectPr).copy(width = 16838, height = 11906, landscape = true)
        state = DocxPageLayout.withSect(state, s, DocxPageLayout.withPageSetup(s.sectPr, landscape))
        val bytes = save(original, pkg, state)
        pkg = read(bytes).first
        assertTrue(pkg.page.landscape)
        assertEquals(16838, pkg.page.width)
        // …and back to portrait: the flag goes away again, dimensions restore.
        state = read(bytes).second
        s = lastSect(state)
        val portrait = landscape.copy(width = 11906, height = 16838, landscape = false)
        val bytes2 = save(bytes, read(bytes).first, DocxPageLayout.withSect(state, s, DocxPageLayout.withPageSetup(s.sectPr, portrait)))
        val (pkg2, _) = read(bytes2)
        assertFalse(pkg2.page.landscape)
        assertEquals(11906, pkg2.page.width)
        assertEquals(16838, pkg2.page.height)
    }

    @Test
    fun pageSetupPreservesUnknownSectPrChildren() {
        val body = "<w:p><w:r><w:t>x</w:t></w:r></w:p>" +
            "<w:sectPr><w:cols w:space=\"517\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:docGrid w:linePitch=\"312\"/></w:sectPr>"
        val (_, state) = read(docx(body))
        val s = lastSect(state)
        val edited = DocxPageLayout.withPageSetup(s.sectPr, DocxPageLayout.pageSetupOf(s.sectPr).copy(width = 12240))
        assertTrue(edited.contains("<w:cols w:space=\"517\"/>"))
        assertTrue(edited.contains("<w:docGrid w:linePitch=\"312\"/>"))
        assertTrue(edited.contains("<w:pgSz w:w=\"12240\" w:h=\"16838\""))
        // the rewritten element order still satisfies the sectPr sequence (cols before/after pgSz only)
        assertTrue(edited.indexOf("cols") < edited.indexOf("docGrid"))
    }

    // ── sections list (P3-1/P3-3) ──────────────────────────────────────────────

    @Test
    fun sectionsFoundInOrderIncludingTrailing() {
        val s1 = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        val s2 = "<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\"/></w:sectPr>"
        val body = "<w:p><w:pPr>$s1</w:pPr><w:r><w:t>one</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>two</w:t></w:r></w:p>" +
            "<w:p><w:pPr><w:ind w:left=\"2\"/>$s2</w:pPr><w:r><w:t>three</w:t></w:r></w:p>"
        val secs = DocxPageLayout.sections(read(docx(body)).second)
        assertEquals(3, secs.size)
        assertFalse(DocxPageLayout.pageSetupOf(secs[0].sectPr).landscape)
        assertTrue(DocxPageLayout.pageSetupOf(secs[1].sectPr).landscape)
        assertNull(secs[2].paraId) // trailing body-level (or synthetic) section
    }

    // ── size + margins (P3-2) ──────────────────────────────────────────────────

    @Test
    fun dimensionValidationBounds() {
        assertTrue(DocxPageLayout.validDim(11906))
        assertFalse(DocxPageLayout.validDim(143))
        assertFalse(DocxPageLayout.validDim(31681))
        assertTrue(DocxPageLayout.validMargin(0))
        assertFalse(DocxPageLayout.validMargin(-1))
    }

    @Test
    fun marginsRoundTripIndependently() {
        val body = "<w:p><w:r><w:t>x</w:t></w:r></w:p>" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>"
        val original = docx(body)
        val (pkg, state) = read(original)
        val s = lastSect(state)
        val edited = DocxPageLayout.withPageSetup(
            s.sectPr,
            DocxPageLayout.pageSetupOf(s.sectPr).copy(top = 720, left = 1800, header = 400, gutter = 250)
        )
        val bytes = save(original, pkg, DocxPageLayout.withSect(state, s, edited))
        val p = read(bytes).first.page
        assertEquals(720, p.top)
        assertEquals(1800, p.left)
        assertEquals(400, p.header)
        assertEquals(250, p.gutter)
        assertEquals(1440, p.right) // untouched margin survives
    }

    // ── page break (P3-3) ──────────────────────────────────────────────────────

    @Test
    fun pageBreakInsertionSurvivesSaveReopen() {
        val original = docx("<w:p><w:r><w:t>Chapter one</w:t></w:r></w:p>")
        val (pkg, state) = read(original)
        val p = paras(state).first()
        // The view model's op: an object run with the page-break payload joins the field text.
        val np = DocxEdits.typed(p, p.text + OBJ_CHAR, CharFmt(rPr = "", obj = DocxPageLayout.PAGE_BREAK_OBJ))
        val bytes = save(original, pkg, DocState(replacePara(state.blocks, np)!!))
        val reopened = read(bytes)
        val p2 = paras(reopened.second).first()
        assertTrue(p2.text.contains(OBJ_CHAR))
        assertTrue(DocxObjects.info(p2.fmts.last().obj!!).pageBreak)
        assertTrue(p2.text.startsWith("Chapter one"))
    }

    // ── section break (P3-3) ───────────────────────────────────────────────────

    @Test
    fun sectionBreakInsertCarriesTypeBeforeTargetParagraph() {
        val original = docx(
            "<w:p><w:r><w:t>alpha</w:t></w:r></w:p><w:p><w:r><w:t>beta</w:t></w:r></w:p>"
        )
        val (pkg, state) = read(original)
        val target = paras(state)[1]
        val next = DocxPageLayout.insertSectionBreak(state, target.id, DocxPageLayout.sections(state).last(), "continuous")!!
        val inserted = paras(next).first { it.text.isEmpty() }
        assertTrue(inserted.pPr.contains("w:sectPr"))
        assertTrue(inserted.pPr.contains("<w:type w:val=\"continuous\"/>"))
        assertTrue(inserted.editable)
        val bytes = save(original, pkg, next)
        val (_, state2) = read(bytes)
        val secs = DocxPageLayout.sections(state2)
        assertEquals(2, secs.size)
        assertEquals("continuous", DocxPageLayout.sectionType(secs[0].sectPr))
        assertEquals("beta", paras(state2)[2].text)
    }

    @Test
    fun sectionTypeRoundTripAndRemoval() {
        val original = docx(
            "<w:p><w:r><w:t>x</w:t></w:r></w:p>" +
                "<w:sectPr><w:type w:val=\"evenPage\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        )
        val target = read(original)
        assertEquals("evenPage", DocxPageLayout.sectionType(lastSect(target.second).sectPr))
        val swapped = DocxPageLayout.withType(lastSect(target.second).sectPr, "oddPage")
        val bytes = save(original, target.first, DocxPageLayout.withSect(target.second, lastSect(target.second), swapped))
        val reopen = read(bytes)
        assertEquals("oddPage", DocxPageLayout.sectionType(lastSect(reopen.second).sectPr))
        val bytes2 = save(bytes, reopen.first, DocxPageLayout.withSect(reopen.second, lastSect(reopen.second), DocxPageLayout.withType(lastSect(reopen.second).sectPr, null)))
        assertNull(DocxPageLayout.sectionType(lastSect(read(bytes2).second).sectPr))
    }

    // ── header/footer (P3-4) ───────────────────────────────────────────────────

    private val hfDoc = docx(
        body = "<w:p><w:r><w:t>body text</w:t></w:r></w:p>",
        rels = relsOf("<Relationship Id=\"rIdH\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>"),
        contentTypes = typesOf(override("/word/document.xml", CT_MAIN), override("/word/header1.xml", CT_HEADER)),
        extra = mapOf("word/header1.xml" to hfXml("w:hdr", "ABC College"))
    )

    @Test
    fun readsHeaderPartIntoEditableParagraphs() {
        val pkg = read(hfDoc).first
        val ps = paras(DocxPageLayout.hfState(pkg.hfParts["word/header1.xml"]))
        assertEquals("ABC College", ps.first().text)
        assertTrue(ps.first().editable)
    }

    @Test
    fun headerEditReplacesExistingPartAndBodyUntouched() {
        val (pkg, state) = read(hfDoc)
        val st = DocxPageLayout.hfState(pkg.hfParts["word/header1.xml"])
        val p = paras(st).first()
        val np = DocxEdits.typed(p, "HMXPDF College", DocxEdits.fmtAt(p, p.text.length))
        val edited = DocState(replacePara(st.blocks, np)!!)
        val bytes = save(hfDoc, pkg, state, DocxWriter.Extras(hfSave = mapOf("word/header1.xml" to edited)))
        val out = entry(bytes, "word/header1.xml")!!
        assertTrue(out.contains("HMXPDF College"))
        assertFalse(out.contains("ABC College"))
        assertTrue(entry(bytes, "word/document.xml")!!.contains("body text"))
    }

    @Test
    fun headerCreationAddsPartRelAndContentType() {
        val original = docx(
            "<w:p><w:r><w:t>x</w:t></w:r></w:p><w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        )
        val (pkg, state) = read(original)
        val s = lastSect(state)
        val path = "word/header2.xml"
        val para = DocPara(
            id = 501, origin = null, openTag = "<w:p>", pPr = "", extras = "",
            text = "My header", fmts = emptyList(), editable = true
        )
        val extra = DocxWriter.Extras(
            hfSave = mapOf(path to DocState(listOf(para))),
            hfRelIds = mapOf(path to "rId9")
        )
        val withRef = DocxPageLayout.withReference(s.sectPr, true, "default", "rId9")
        val bytes = save(original, pkg, DocxPageLayout.withSect(state, s, withRef), extra)
        assertTrue(entry(bytes, "word/header2.xml")!!.contains("My header"))
        val relsEntry = entry(bytes, "word/_rels/document.xml.rels")!!
        assertTrue(relsEntry.contains("Id=\"rId9\""))
        assertTrue(relsEntry.contains("relationships/header"))
        assertTrue(entry(bytes, "[Content_Types].xml")!!.contains("word/header2.xml"))
        assertTrue(entry(bytes, "[Content_Types].xml")!!.contains(CT_HEADER))
        val (pkg2, state2) = read(bytes)
        assertEquals("rId9", DocxPageLayout.reference(lastSect(state2).sectPr, true, "default"))
        assertTrue(pkg2.hfParts.containsKey("word/header2.xml"))
    }

    @Test
    fun footerReferenceRemovalLeavesPartUntouched() {
        val original = docx(
            body = "<w:p><w:r><w:t>x</w:t></w:r></w:p>" +
                "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdF\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>",
            rels = relsOf("<Relationship Id=\"rIdF\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>"),
            contentTypes = typesOf(override("/word/document.xml", CT_MAIN), override("/word/footer1.xml", CT_FOOTER)),
            extra = mapOf("word/footer1.xml" to hfXml("w:ftr", "Semester 1"))
        )
        val (pkg, state) = read(original)
        assertEquals("Semester 1", paras(DocxPageLayout.hfState(pkg.hfParts["word/footer1.xml"])).first().text)
        val s = lastSect(state)
        assertEquals("rIdF", DocxPageLayout.reference(s.sectPr, false, "default"))
        val bytes = save(original, pkg, DocxPageLayout.withSect(state, s, DocxPageLayout.withReference(s.sectPr, false, "default", null)))
        assertNull(DocxPageLayout.reference(lastSect(read(bytes).second).sectPr, false, "default"))
        // the part's bytes and its rel survive; only the reference went away
        assertEquals(entry(original, "word/footer1.xml"), entry(bytes, "word/footer1.xml"))
        assertTrue(entry(bytes, "word/_rels/document.xml.rels")!!.contains("rIdF"))
    }

    @Test
    fun referenceEditKeepsOtherVariants() {
        val sectPr = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdA\"/>" +
            "<w:footerReference w:type=\"default\" r:id=\"rIdB\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        val edited = DocxPageLayout.withReference(sectPr, true, "first", "rIdC")
        assertEquals("rIdA", DocxPageLayout.reference(edited, true, "default"))
        assertEquals("rIdC", DocxPageLayout.reference(edited, true, "first"))
        assertEquals("rIdB", DocxPageLayout.reference(edited, false, "default"))
        // only the first-variant reference was touched
        assertTrue(edited.indexOf("rIdA") < edited.indexOf("rIdC"))
        // removing first keeps default again
        assertEquals("rIdA", DocxPageLayout.reference(DocxPageLayout.withReference(edited, true, "first", null), true, "default"))
    }

    @Test
    fun freePartSkipsUsedNames() {
        assertEquals("word/header2.xml", DocxPageLayout.freePart(read(hfDoc).first.hfParts.keys, false))
        assertEquals("word/footer4.xml", DocxPageLayout.freePart(mapOf("word/footer3.xml" to "").keys, true))
    }

    // ── different first page / odd-even (P3-5) ────────────────────────────────

    private val plainDoc = docx(
        "<w:p><w:r><w:t>x</w:t></w:r></w:p><w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
    )

    @Test
    fun titlePgRoundTrip() {
        val (pkg, state) = read(plainDoc)
        val s = lastSect(state)
        assertFalse(DocxPageLayout.titlePg(s.sectPr))
        val bytes = save(plainDoc, pkg, DocxPageLayout.withSect(state, s, DocxPageLayout.withTitlePg(s.sectPr, true)))
        val read1 = read(bytes)
        assertTrue(DocxPageLayout.titlePg(lastSect(read1.second).sectPr))
        val bytes2 = save(bytes, read1.first, DocxPageLayout.withSect(read1.second, lastSect(read1.second), DocxPageLayout.withTitlePg(lastSect(read1.second).sectPr, false)))
        assertFalse(DocxPageLayout.titlePg(lastSect(read(bytes2).second).sectPr))
    }

    @Test
    fun evenOddSettingsInsertedInOrderAndRemoved() {
        assertTrue(DocxPageLayout.hasEvenOdd(DocxPageLayout.withEvenOdd(null, true)))
        val settings = "<?xml version=\"1.0\"?><w:settings $ns>" +
            "<w:zoom w:percent=\"100\"/><w:defaultTabStop w:val=\"720\"/><w:compat/></w:settings>"
        val on = DocxPageLayout.withEvenOdd(settings, true)
        assertTrue(DocxPageLayout.hasEvenOdd(on))
        // schema order: before the first anchor (zoom) — never dropped inside w:compat
        assertTrue(on.indexOf("evenAndOddHeaders") < on.indexOf("defaultTabStop"))
        assertTrue(on.contains("<w:compat/>"))
        val off = DocxPageLayout.withEvenOdd(on, false)
        assertFalse(DocxPageLayout.hasEvenOdd(off))
        assertEquals(settings, off)
    }

    // ── coexistence: section edits leave revisions intact ─────────────────────

    private val trackedBody = "<w:p>" +
        "<w:r><w:t>HMXPDF supports </w:t></w:r>" +
        "<w:ins w:id=\"4\" w:author=\"Bob\" w:date=\"2026-02-01T10:00:00Z\">" +
        "<w:r><w:t>and DOCX </w:t></w:r></w:ins>" +
        "<w:r><w:t>files.</w:t></w:r></w:p>"

    @Test
    fun sectionEditKeepsTrackedChangesAndAcceptStillWorks() {
        val original = docx(
            trackedBody + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        )
        val (pkg, state) = read(original)
        val p = paras(state).first()
        assertEquals(Rev(true, 4), p.fmts[17].rev) // "and DOCX " starts at 17
        val s = lastSect(state)
        val bytes = save(
            original, pkg,
            DocxPageLayout.withSect(state, s, DocxPageLayout.withPageSetup(s.sectPr, DocxPageLayout.pageSetupOf(s.sectPr).copy(width = 12240)))
        )
        val (pkg2, state2) = read(bytes)
        val p2 = paras(state2).first()
        assertEquals(Rev(true, 4), p2.fmts[17].rev)
        assertEquals(12240, pkg2.page.width)
        val accepted = DocxRevisions.acceptAll(p2)
        assertEquals(17, accepted.text.length)
        assertFalse(accepted.fmts.any { it.rev != null })
    }
}
