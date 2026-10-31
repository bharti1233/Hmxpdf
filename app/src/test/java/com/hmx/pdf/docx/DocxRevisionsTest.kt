package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DOCX Track Changes: read, capture (insert/delete), resolve individually and in bulk,
 * save/reopen. The writer output is checked for the OOXML shapes Word needs (`w:ins`, `w:del`,
 * `w:delText`, `w:id/author/date`).
 */
class DocxRevisionsTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    /** "HMXPDF supports and DOCX files." with "and DOCX " (9 chars) as a tracked insertion by Bob. */
    private val insertedBody = "<w:p>" +
        "<w:r><w:t>HMXPDF supports </w:t></w:r>" +
        "<w:ins w:id=\"4\" w:author=\"Bob\" w:date=\"2026-02-01T10:00:00Z\">" +
        "<w:r><w:t>and DOCX </w:t></w:r></w:ins>" +
        "<w:r><w:t>files.</w:t></w:r></w:p>"

    /** "Give me PDF files." with the bold "PDF " (4 chars) as a tracked deletion by Bob. */
    private val deletedBody = "<w:p>" +
        "<w:r><w:t>Give me </w:t></w:r>" +
        "<w:del w:id=\"7\" w:author=\"Bob\" w:date=\"2026-02-01T10:00:00Z\">" +
        "<w:r><w:rPr><w:b/></w:rPr><w:delText>PDF </w:delText></w:r></w:del>" +
        "<w:r><w:t>files.</w:t></w:r></w:p>"

    /** One inserted paragraph plus one deletion paragraph in one body. */
    private val twoParaBody = insertedBody + "<w:p>" +
        "<w:r><w:t>cell</w:t></w:r>" +
        "<w:del w:id=\"9\" w:author=\"Eve\" w:date=\"2026-03-01T10:00:00Z\">" +
        "<w:r><w:delText>2</w:delText></w:r></w:del></w:p>"

    private fun docx(body: String): ByteArray {
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>",
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>$body" +
                "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>"
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun read(bytes: ByteArray): Pair<DocxPackage, DocState> = DocxReader.read(bytes.inputStream())!!

    private fun paras(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks, includePreview = false) { out.add(it) }
        return out
    }

    private fun save(bytes: ByteArray, pkg: DocxPackage, state: DocState): ByteArray {
        val src = File.createTempFile("src", ".docx").apply { writeBytes(bytes); deleteOnExit() }
        val out = File.createTempFile("out", ".docx").apply { deleteOnExit() }
        DocxWriter.write(pkg, state, src, out)
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

    private fun flagged(p: DocPara, id: Int, ins: Boolean): Int = p.fmts.count { it.rev == Rev(ins, id) }

    // ── parsing ───────────────────────────────────────────────────────────────

    @Test
    fun readsInsertionFlaggedAndEditable() {
        val (pkg, state) = read(docx(insertedBody))
        assertTrue(pkg.writable)
        val p = paras(state)[0]
        assertTrue(p.editable)
        assertEquals("HMXPDF supports and DOCX files.", p.text)
        assertEquals(9, flagged(p, 4, ins = true))
        assertEquals(0, flagged(p, 4, ins = false))
        assertEquals("Bob", p.revs[4]?.author)
        assertEquals("2026-02-01T10:00:00Z", p.revs[4]?.date)
        assertEquals(listOf(Rev(true, 4)), DocxRevisions.pending(p))
        assertEquals("and DOCX", DocxRevisions.excerpt(p, 4))
    }

    @Test
    fun readsDeletionRetainedWithFormat() {
        val (_, state) = read(docx(deletedBody))
        val p = paras(state)[0]
        assertTrue(p.editable)
        assertEquals("Give me PDF files.", p.text)
        assertEquals(4, flagged(p, 7, ins = false))
        // The deleted run's own bold rides the flagged characters.
        assertTrue(p.fmts[8].rev != null && p.fmts[8].rPr.contains("<w:b/>"))
        assertEquals("Bob", p.revs[7]?.author)
    }

    @Test
    fun readsRevisionsAcrossParagraphs() {
        val (_, state) = read(docx(twoParaBody))
        val ps = paras(state)
        assertEquals(2, ps.size)
        assertEquals(Rev(true, 4), DocxRevisions.pending(ps[0]).single())
        assertEquals(Rev(false, 9), DocxRevisions.pending(ps[1]).single())
    }

    @Test
    fun missingOptionalMetadataParsesSafely() {
        val (_, state) = read(docx("<w:p><w:ins w:id=\"12\"><w:r><w:t>hi</w:t></w:r></w:ins></w:p>"))
        val p = paras(state)[0]
        assertTrue(p.editable)
        assertEquals("hi", p.text)
        assertEquals("", p.revs[12]?.author)
        assertEquals("", p.revs[12]?.date)
    }

    @Test
    fun unmodelableWrapperFallsBackToReadOnlyWithoutDataLoss() {
        // A hyperlink inside the wrapper can't be re-flowed safely: the paragraph stays read-only
        // and keeps its original XML untouched on save.
        val body = "<w:p><w:ins w:id=\"3\" w:author=\"Bob\" w:date=\"2026-02-01T10:00:00Z\">" +
            "<w:hyperlink r:id=\"rId1\"><w:r><w:t>link</w:t></w:r></w:hyperlink></w:ins></w:p>"
        val bytes = docx(body)
        val (pkg, state) = read(bytes)
        assertFalse(paras(state)[0].editable)
        val out = save(bytes, pkg, state)
        assertTrue(entry(out, "word/document.xml")!!.contains("w:ins w:id=\"3\""))
    }

    // ── untouched round-trip ──────────────────────────────────────────────────

    @Test
    fun untouchedRevisionDocumentStaysByteIdentical() {
        val bytes = docx(twoParaBody)
        val (pkg, state) = read(bytes)
        val out = save(bytes, pkg, state)
        assertEquals(entry(bytes, "word/document.xml"), entry(out, "word/document.xml"))
        val ps = paras(read(out).second)
        assertEquals(Rev(true, 4), DocxRevisions.pending(ps[0]).single())
        assertEquals(Rev(false, 9), DocxRevisions.pending(ps[1]).single())
    }

    @Test
    fun captureCounterSeedsAboveParsedIds() {
        DocxRevisions.seed(9)
        assertEquals(10, DocxRevisions.nextId())
        assertTrue(DocxRevisions.nextId() > 10)
    }

    // ── capture (typed / replace) ─────────────────────────────────────────────

    @Test
    fun typedDeleteRetainsTextAsDeletion() {
        val bytes = docx("<w:p><w:r><w:t>HMXPDF supports PDF files.</w:t></w:r></w:p>")
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        // The user deletes "PDF" (the field's new text has no "PDF"): it stays, flagged.
        val np = DocxEdits.typed(p, "HMXPDF supports  files.", CharFmt.Plain, revId = 11, meta = RevMeta("HMXPDF", "2026-05-01T00:00:00Z"))
        assertEquals("HMXPDF supports PDF files.", np.text)
        assertEquals(3, flagged(np, 11, ins = false))
        assertTrue(np.revs.containsKey(11))
        // Serialize: deletion runs use `w:delText` inside a `w:del` with id/author/date.
        val sb = StringBuilder()
        DocxWriter.para(np, sb)
        val xml = sb.toString()
        assertTrue(xml, xml.contains("<w:del w:id=\"11\" w:author=\"HMXPDF\" w:date=\"2026-05-01T00:00:00Z\">"))
        assertTrue(xml.contains("<w:delText xml:space=\"preserve\">PDF</w:delText>"))
        assertFalse(xml.contains("w:ins"))
        // And the same survives a real save/reopen.
        val out = save(bytes, pkg, DocState(listOf(np)))
        val p2 = paras(read(out).second)[0]
        assertTrue(p2.editable)
        assertEquals(3, flagged(p2, 11, ins = false))
        assertEquals("HMXPDF supports PDF files.", p2.text)
    }

    @Test
    fun typedInsertRecordsInsertion() {
        val bytes = docx("<w:p><w:r><w:t>AB</w:t></w:r></w:p>")
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val np = DocxEdits.typed(p, "ABxy", CharFmt.Plain, revId = 5, meta = RevMeta("HMXPDF", "2026-05-01T00:00:00Z"))
        assertEquals("ABxy", np.text)
        assertTrue(np.revs.containsKey(5))
        assertEquals(2, flagged(np, 5, ins = true))
        val sb = StringBuilder()
        DocxWriter.para(np, sb)
        val xml = sb.toString()
        assertTrue(xml, xml.contains("<w:ins w:id=\"5\" w:author=\"HMXPDF\""))
        assertTrue(xml.contains(">xy<"))
        val out = save(bytes, pkg, DocState(listOf(np)))
        val p2 = paras(read(out).second)[0]
        assertEquals("ABxy", p2.text)
        assertEquals(2, flagged(p2, 5, ins = true))
    }

    @Test
    fun typedOverPendingInsertionShrinksIt() {
        val bytes = docx("<w:p><w:r><w:t>AB</w:t></w:r></w:p>")
        val (_, state) = read(bytes)
        val p = paras(state)[0]
        val ins = DocxEdits.typed(p, "ABxy", CharFmt.Plain, revId = 5, meta = RevMeta("HMXPDF", "2026-05-01T00:00:00Z"))
        // Removing the just-inserted "xy" while still tracked: the insertion shrinks; nothing new
        // is recorded and no orphan deletion appears.
        val back = DocxEdits.typed(ins, "AB", CharFmt.Plain, revId = 6, meta = RevMeta("HMXPDF", "2026-05-01T00:00:00Z"))
        assertEquals("AB", back.text)
        assertTrue(DocxRevisions.pending(back).isEmpty())
    }

    @Test
    fun replaceUnderTrackingKeepsBothSidesOfTheEdit() {
        val bytes = docx("<w:p><w:r><w:t>hello world</w:t></w:r></w:p>")
        val (_, state) = read(bytes)
        val p = paras(state)[0]
        val np = DocxEdits.replaceRange(p, 6, 11, "there", revId = 8, meta = RevMeta("HMXPDF", "2026-05-01T00:00:00Z"))
        assertEquals("hello worldthere", np.text)
        assertEquals(5, flagged(np, 8, ins = false)) // "world" retained, pending deletion
        assertEquals(5, flagged(np, 8, ins = true)) // "there" is the replacement
        val sb = StringBuilder()
        DocxWriter.para(np, sb)
        val xml = sb.toString()
        assertTrue(xml, xml.contains("<w:del w:id=\"8\" ") && xml.contains("<w:ins w:id=\"8\" "))
    }

    @Test
    fun untrackedEditingStaysExactlyAsBefore() {
        val (_, state) = read(docx("<w:p><w:r><w:t>AB</w:t></w:r></w:p>"))
        val p = paras(state)[0]
        val np = DocxEdits.typed(p, "ABx", CharFmt.Plain)
        assertEquals("ABx", np.text)
        assertTrue(np.revs.isEmpty())
        val np2 = DocxEdits.replaceRange(p, 0, 1, "z")
        assertEquals("zB", np2.text)
        assertTrue(np2.revs.isEmpty())
    }

    // ── resolution ────────────────────────────────────────────────────────────

    @Test
    fun acceptInsertionKeepsTextWithoutFlag() {
        val (_, state) = read(docx(insertedBody))
        val np = DocxRevisions.accept(paras(state)[0], 4)
        assertEquals("HMXPDF supports and DOCX files.", np.text)
        assertTrue(np.fmts.none { it.rev != null })
        assertTrue(np.revs.isEmpty())
    }

    @Test
    fun acceptDeletionRemovesItsTextOnly() {
        val (_, state) = read(docx(twoParaBody))
        val ps = paras(state)
        val insert = DocxRevisions.accept(ps[0], 4) // an insertion: text stays
        assertEquals("HMXPDF supports and DOCX files.", insert.text)
        assertFalse(insert.revs.containsKey(4))
        val deletion = DocxRevisions.accept(ps[1], 9) // a deletion: "2" goes for good
        assertEquals("cell", deletion.text)
        assertFalse(deletion.revs.containsKey(9))
    }

    @Test
    fun rejectInsertionRemovesItsText() {
        val (_, state) = read(docx(insertedBody))
        val np = DocxRevisions.reject(paras(state)[0], 4)
        // The insertion covered "and DOCX " (with its own trailing space): the original join returns.
        assertEquals("HMXPDF supports files.", np.text)
        assertTrue(DocxRevisions.pending(np).isEmpty())
    }

    @Test
    fun rejectDeletionRestoresTheText() {
        val (_, state) = read(docx(deletedBody))
        val np = DocxRevisions.reject(paras(state)[0], 7)
        assertEquals("Give me PDF files.", np.text)
        assertTrue(np.fmts.none { it.rev != null })
        assertTrue(np.revs.isEmpty())
        // The run's bold survives the restoration.
        assertTrue(np.fmts[8].rPr.contains("<w:b/>"))
    }

    @Test
    fun resolvingOneRevisionDoesNotTouchTheOther() {
        // Rejections in one paragraph leave another paragraph's revision alone.
        val (_, state) = read(docx(twoParaBody))
        val ps = paras(state)
        DocxRevisions.reject(ps[0], 4)
        assertEquals(Rev(false, 9), DocxRevisions.pending(ps[1]).single())

        // Two revisions inside ONE paragraph: rejecting one leaves the other pending.
        val both = "<w:p><w:r><w:t>a</w:t></w:r>" +
            "<w:ins w:id=\"20\" w:author=\"A\" w:date=\"d\"><w:r><w:t>b</w:t></w:r></w:ins>" +
            "<w:del w:id=\"21\" w:author=\"A\" w:date=\"d\"><w:r><w:delText>c</w:delText></w:r></w:del></w:p>"
        val p1 = paras(read(docx(both)).second)[0]
        assertEquals("abc", p1.text)
        val after = DocxRevisions.reject(p1, 20)
        assertEquals("ac", after.text)
        assertEquals(listOf(Rev(false, 21)), DocxRevisions.pending(after))
        assertEquals(1, after.fmts.count { it.rev != null })
    }

    @Test
    fun acceptAllAppliesEveryPendingChange() {
        val (_, state) = read(docx(twoParaBody))
        val ps = paras(state)
        val a = DocxRevisions.acceptAll(ps[0])
        assertEquals("HMXPDF supports and DOCX files.", a.text)
        assertTrue(DocxRevisions.pending(a).isEmpty())
        val b = DocxRevisions.acceptAll(ps[1])
        assertEquals("cell", b.text)
        assertTrue(DocxRevisions.pending(b).isEmpty())
    }

    @Test
    fun rejectAllRemovesInsertionsAndRestoresDeletions() {
        val (_, state) = read(docx(twoParaBody))
        val ps = paras(state)
        val a = DocxRevisions.rejectAll(ps[0])
        assertEquals("HMXPDF supports files.", a.text)
        assertTrue(DocxRevisions.pending(a).isEmpty())
        val b = DocxRevisions.rejectAll(ps[1])
        assertEquals("cell2", b.text)
        assertTrue(DocxRevisions.pending(b).isEmpty())
        assertTrue(b.fmts.none { it.rev != null })
    }

    @Test
    fun resolutionIsIdempotentWhenNothingMatches() {
        val (_, state) = read(docx(insertedBody))
        val p = paras(state)[0]
        assertEquals(p, DocxRevisions.accept(p, 99))
        assertEquals(p, DocxRevisions.reject(p, 99))
    }

    // ── bulk save / reopen ────────────────────────────────────────────────────

    @Test
    fun saveAfterAcceptAllLeavesNoRevisionMarkupBehind() {
        val bytes = docx(twoParaBody)
        val (pkg, state) = read(bytes)
        val ps = paras(state)
        val resolved = DocState(listOf(DocxRevisions.acceptAll(ps[0]), DocxRevisions.acceptAll(ps[1])))
        val out = save(bytes, pkg, resolved)
        val xml = entry(out, "word/document.xml")!!
        assertFalse(xml, xml.contains("<w:ins ") || xml.contains("<w:del "))
        val (_, state2) = read(out)
        val ps2 = paras(state2)
        assertTrue(ps2.all { DocxRevisions.pending(it).isEmpty() })
        assertEquals("HMXPDF supports and DOCX files.", ps2[0].text)
        assertEquals("cell", ps2[1].text)
    }

    @Test
    fun pristineSaveReopensSamePendingRevisions() {
        val bytes = docx(twoParaBody)
        val (pkg, state) = read(bytes)
        val out = save(bytes, pkg, state) // unedited: writer copies the paragraph XML verbatim
        val (_, state2) = read(out)
        val ps2 = paras(state2)
        assertTrue(DocxRevisions.pending(ps2[0]).isNotEmpty())
        assertTrue(DocxRevisions.pending(ps2[1]).isNotEmpty())
        assertEquals("Bob", ps2[0].revs[4]?.author)
        assertEquals("Eve", ps2[1].revs[9]?.author)
    }

    @Test
    fun commentsAnchorsSurviveRevisionResolution() {
        val body = "<w:p>" +
            "<w:commentRangeStart w:id=\"1\"/>" +
            "<w:r><w:t>a</w:t></w:r>" +
            "<w:del w:id=\"7\" w:author=\"Bob\" w:date=\"d\"><w:r><w:delText>b</w:delText></w:r></w:del>" +
            "<w:commentRangeEnd w:id=\"1\"/>" +
            "<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference w:id=\"1\"/></w:r>" +
            "</w:p>"
        val (_, state) = read(docx(body))
        val p = paras(state)[0]
        assertTrue(p.extras.contains("commentRangeStart"))
        assertTrue(p.extras.contains("commentRangeEnd"))
        // Accepting the deletion keeps the markers and the reference run exactly.
        val np = DocxRevisions.accept(p, 7)
        assertEquals("a$OBJ_CHAR", np.text)
        assertTrue(np.extras.contains("commentRangeStart"))
        assertTrue(np.extras.contains("commentRangeEnd"))
        assertTrue(np.fmts.last().obj!!.contains("commentReference"))
    }

    @Test
    fun formattingOnFlaggedTextSurvivesResolution() {
        val (_, state) = read(docx(insertedBody))
        val p = paras(state)[0]
        // Size the insertion's own characters, then accept: the formatting stays ordinary content.
        val np = DocxEdits.format(p, 16, 25) { DocxEdits.withSize(it, 44) }
        assertEquals(44, Props.r(np.fmts[16].rPr).size)
        assertEquals(9, np.fmts.count { it.rev != null })
        val done = DocxRevisions.accept(np, 4)
        assertEquals("HMXPDF supports and DOCX files.", done.text)
        assertEquals(44, Props.r(done.fmts[16].rPr).size)
        assertTrue(done.fmts.none { it.rev != null })
    }
}
