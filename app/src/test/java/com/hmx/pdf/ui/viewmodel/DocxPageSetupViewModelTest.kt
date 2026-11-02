package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.DocxPageLayout
import com.hmx.pdf.docx.DocxReader
import com.hmx.pdf.docx.forEachPara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Page Setup state at the ViewModel (phase 3): the mode/read-only gates, the dirty bookkeeping,
 * section targeting for breaks, session-wide relationship-id allocation for repeated header/
 * footer creates, and the single page-setup source of truth the preview and PDF export read.
 * Package persistence (write + reopen) is the writer's contract, already covered green by
 * `DocxPageLayoutTest`; the save() call itself needs an Android Context, so its guard is checked
 * by inspection there (it fails loud through its onDone callback now, not a silent return).
 */
class DocxPageSetupViewModelTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** Body paragraph plus a one-cell table and a real trailing sectPr, like a fresh Word file. */
    private fun body(): String {
        val para = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>"
        val table = "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>" +
            "<w:tblGrid><w:gridCol w:w=\"4000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
            "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>" +
            "<w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        return para + table
    }

    private fun docx(sectPr: String = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"): ByteArray {
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document $ns><w:body>" + body() + sectPr + "</w:body></w:document>"
        val parts = linkedMapOf(
            "_rels/.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId8\" Type=\"$REL/styles\" Target=\"styles.xml\"/></Relationships>",
            "word/document.xml" to document,
            "[Content_Types].xml" to "<?xml version=\"1.0\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/></Types>"
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun read(bytes: ByteArray) = DocxReader.read(bytes.inputStream())!!

    private fun vm(bytes: ByteArray = docx()): DocxEditorViewModel {
        val v = DocxEditorViewModel()
        v.primeForTest(read(bytes))
        return v
    }

    private fun edit(v: DocxEditorViewModel) {
        v.setEditMode(true)
        assertTrue(v.editMode)
    }

    private fun paras(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks, includePreview = false) { out.add(it) }
        return out
    }

    // ── mode + read-only gates ─────────────────────────────────────────────────

    @Test
    fun viewModeRefusesEveryPageSetupOperation() {
        val v = vm()
        val before = v.sectionsOf().first().sectPr
        v.setSectionSetup(v.sectionsOf().first(), DocxPageLayout.pageSetupOf(before).copy(landscape = true), "pg:orient")
        v.setFirstPageDifferent(v.sectionsOf().first(), true)
        v.setEvenOddHeaders(true)
        assertFalse(v.insertSectionBreak(paras(v.doc!!)[0].id, "continuous"))
        assertNull(v.hfCreate(v.sectionsOf().first(), true, "default"))
        assertFalse(v.dirty)
        assertEquals(before, v.sectionsOf().first().sectPr)
    }

    @Test
    fun readOnlyDocumentIgnoresSetupEditsEvenAfterModeDemand() {
        val v = vm()
        v.testFileMirror = false // "file" mirror missing: not editable
        v.setEditMode(true)
        assertFalse(v.editMode) // refused at the gate
        val section = v.sectionsOf().first()
        v.setSectionSetup(section, DocxPageLayout.pageSetupOf(section.sectPr).copy(top = 720), "pg:top")
        assertFalse(v.dirty)
        assertEquals(1440, DocxPageLayout.pageSetupOf(v.sectionsOf().first().sectPr).top)
    }

    @Test
    fun setSectionSetupAppliesInEditModeAndMarksDirty() {
        val v = vm()
        edit(v)
        val section = v.sectionsOf().first()
        v.setSectionSetup(section, DocxPageLayout.pageSetupOf(section.sectPr).copy(width = 16838, height = 11906, landscape = true), "pg:orient")
        assertTrue(v.dirty)
        assertTrue(v.livePage().landscape)
        // Stale-capture safety: the SAME Section object was captured before the landscape commit.
        // A following op must rebase on the CURRENT terminator, keeping both edits (the old
        // commitSect rebuilt content from the stale string and silently dropped the landscape).
        v.setFirstPageDifferent(section, true)
        val now = v.sectionsOf().first().sectPr
        assertTrue(DocxPageLayout.titlePg(now))
        assertTrue(now.contains("orient=\"landscape\""))
        assertEquals(16838, DocxPageLayout.pageSetupOf(now).width)
        assertTrue(v.dirty)
    }

    // ── dirty bookkeeping across package-side edits ────────────────────────────

    @Test
    fun evenOddToggleCycleReturnsToCleanState() {
        val v = vm()
        edit(v)
        v.setEvenOddHeaders(true)
        assertTrue(v.dirty)
        assertTrue(DocxPageLayout.hasEvenOdd(v.writerExtras().settings))
        v.setEvenOddHeaders(false)
        assertFalse(v.dirty) // on→off round trip = back to the loaded package state
        // …and a package without a settings.xml part: "on" materializes it, "off" returns to null
        // (never an empty <w:settings> shell written just to hold a removed flag).
        val v2 = vm(docx(sectPr = "<w:sectPr/>"))
        edit(v2)
        v2.setEvenOddHeaders(true)
        assertTrue(DocxPageLayout.hasEvenOdd(v2.writerExtras().settings))
        v2.setEvenOddHeaders(false)
        assertFalse(v2.dirty)
        assertNull(v2.writerExtras().settings)
    }

    @Test
    fun recordSavedSetsTheDirtyBaseline() {
        val v = vm()
        edit(v)
        v.setEvenOddHeaders(true)
        v.setSectionSetup(v.sectionsOf().first(), DocxPageLayout.pageSetupOf(v.sectionsOf().first().sectPr).copy(top = 700), "pg:top")
        assertTrue(v.dirty)
        v.recordSaved(v.doc!!)
        assertFalse(v.dirty)
    }

    // ── section break targeting (body level only) ──────────────────────────────

    @Test
    fun sectionBreakRespectsBodyLevelTargeting() {
        val v = vm()
        edit(v)
        val body = paras(v.doc!!).first { it.text == "alpha" }
        val cell = paras(v.doc!!).first { it.text == "cell" }
        // Word hosts section breaks at top level only: a cell-paragraph ask refuses, it does not
        // relocate the break somewhere else silently.
        assertFalse(v.isBodyLevel(cell.id))
        assertFalse(v.insertSectionBreak(cell.id, "continuous"))
        assertFalse(v.dirty)
        assertTrue(v.isBodyLevel(body.id))
        assertTrue(v.insertSectionBreak(body.id, "continuous"))
        val secs = v.sectionsOf()
        assertEquals(2, secs.size)
        assertTrue(v.dirty)
        val boundary = paras(v.doc!!).first { it.text.isEmpty() }
        assertEquals(secs.first().paraId, boundary.id)
    }

    // ── relationship-id allocation across repeated creates ─────────────────────

    @Test
    fun repeatedHfCreatesNeverShareARelationshipId() {
        val (pkg, _) = read(docx())
        // Sanity for the allocator: the fixture's own rels already claim rId1 and rId8.
        assertTrue(pkg.relsXml!!.contains("rId1") && pkg.relsXml!!.contains("rId8"))
        val v = vm()
        edit(v)
        val section = v.sectionsOf().first()
        val headerPath = v.hfCreate(section, true, "default")!!
        val footerPath = v.hfCreate(section, false, "default")!!
        assertNotEquals(headerPath, footerPath)
        val ids = v.writerExtras().hfRelIds.values
        assertEquals(2, ids.toSet().size) // session-wide: a second create never reuses the first id
        assertTrue(ids.none { it == "rId1" || it == "rId8" }) // past pre-existing ids too
        // Both references legally coexist on one terminator (default + default of the other kind).
        val withRefs = v.sectionsOf().first().sectPr
        assertTrue(withRefs.contains("headerReference"))
        assertTrue(withRefs.contains("footerReference"))
        // Removing the session's own create purges its part and rel claim from the save payload,
        // and the surviving sibling reference (on the fresh terminator) is untouched.
        v.hfRemove(v.sectionsOf().first(), true, "default")
        assertFalse(v.writerExtras().hfRelIds.containsKey(headerPath))
        val footerId = v.writerExtras().hfRelIds.getValue(footerPath)
        val sectPr = v.sectionsOf().first().sectPr
        assertFalse(sectPr.contains("headerReference"))
        assertTrue(sectPr.contains("footerReference"))
        assertTrue(sectPr.contains("r:id=\"$footerId\""))
    }
}
