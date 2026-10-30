package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.DocxReader
import com.hmx.pdf.docx.forEachPara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The View/Edit mode contract at the ViewModel (phase 1.1, audit §Tests): every mutation is
 * guarded once by [DocxEditorViewModel.editMode] at the action layer, so a View-Mode caller
 * changes nothing even if a control slips past the screen, and every guard re-engages the
 * moment Edit closes. Documents open in View Mode; "Add" without a caret session anchors to
 * the first editable paragraph rather than silently no-oping.
 *
 * Persistence itself (add → save → reopen) is the writer's contract, already covered green by
 * `DocxCommentsTest` — these tests stop at the VM's in-memory state.
 */
class DocxEditorViewModeTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private val CT_COMMENTS = "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"

    /** Body paragraph plus a one-cell table, so cell editing rides the same tests as body text. */
    private fun body(anchored: Boolean): String {
        val para = if (anchored) {
            "<w:p>" +
                "<w:commentRangeStart w:id=\"1\"/>" +
                "<w:r><w:t>alpha</w:t></w:r>" +
                "<w:commentRangeEnd w:id=\"1\"/>" +
                "<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference w:id=\"1\"/></w:r>" +
                "</w:p>"
        } else "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>"
        val table = "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>" +
            "<w:tblGrid><w:gridCol w:w=\"4000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
            "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>" +
            "<w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        return para + table
    }

    private fun docx(anchoredComment: Boolean): ByteArray {
        val (override, rel, comments) = if (anchoredComment) {
            Triple(
                "<Override PartName=\"/word/comments.xml\" ContentType=\"$CT_COMMENTS\"/>",
                "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/>",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<w:comments $ns><w:comment w:id=\"1\" w:author=\"Alice\" w:date=\"2026-01-01T00:00:00Z\">" +
                    "<w:p><w:r><w:t>First note</w:t></w:r></w:p></w:comment></w:comments>"
            )
        } else Triple("", "", "")
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/>$override</Types>",
            "_rels/.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rel</Relationships>",
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>${body(anchoredComment)}" +
                "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>"
        )
        if (anchoredComment) parts["word/comments.xml"] = comments
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun vm(anchoredComment: Boolean = false): DocxEditorViewModel {
        val (pkg, state) = DocxReader.read(docx(anchoredComment).inputStream())!!
        val v = DocxEditorViewModel()
        v.primeForTest(pkg to state)
        return v
    }

    private fun paras(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks, includePreview = false) { out.add(it) }
        return out
    }

    private fun bodyId(v: DocxEditorViewModel) = paras(v.doc!!).first { it.text.startsWith("alpha") }.id
    private fun cellId(v: DocxEditorViewModel) = paras(v.doc!!).first { it.text == "cell" }.id

    // ── mode state ─────────────────────────────────────────────────────────────

    @Test
    fun opensInViewMode() {
        val v = vm()
        assertFalse(v.editMode)
        assertFalse(v.dirty)
        assertFalse(v.canUndo)
    }

    @Test
    fun setEditModeTogglesBothWays() {
        val v = vm()
        v.setEditMode(true)
        assertTrue(v.editMode)
        v.setEditMode(false)
        assertFalse(v.editMode)
    }

    @Test
    fun setEditModeRefusesNonEditableDocuments() {
        val (pkg, state) = DocxReader.read(docx(false).inputStream())!!
        val v = DocxEditorViewModel()
        v.primeForTest(pkg to state)
        v.testFileMirror = false // "file": mirror missing — not a writable document
        v.setEditMode(true)
        assertFalse(v.editMode)
    }

    // ── mutations blocked in View Mode, allowed in Edit ────────────────────────

    @Test
    fun typingBlockedInViewModeAllowedInEdit() {
        val v = vm()
        v.typed(bodyId(v), "alphabet", CharFmt.Plain)
        assertFalse(v.dirty)
        assertEquals("alpha", paras(v.doc!!)[0].text)
        v.setEditMode(true)
        v.typed(bodyId(v), "alphabet", CharFmt.Plain)
        assertTrue(v.dirty)
        assertEquals("alphabet", paras(v.doc!!)[0].text)
    }

    @Test
    fun tableCellTypingBlockedInViewModeAllowedInEdit() {
        val v = vm()
        val cid = cellId(v)
        v.typed(cid, "celled", CharFmt.Plain)
        assertEquals("cell", paras(v.doc!!).first { it.id == cid }.text)
        v.setEditMode(true)
        v.typed(cid, "celled", CharFmt.Plain)
        assertEquals("celled", paras(v.doc!!).first { it.id == cid }.text)
    }

    @Test
    fun enterKeyBlockedInViewMode() {
        val v = vm()
        assertNull(v.split(bodyId(v), "beta", 2, CharFmt.Plain))
        assertEquals(2, paras(v.doc!!).count())
        v.setEditMode(true)
        val newId = v.split(bodyId(v), "beta", 2, CharFmt.Plain)
        val after = paras(v.doc!!)
        assertEquals(3, after.count())
        assertEquals("be", after[0].text)
        assertEquals("ta", after.first { it.id == newId }.text)
    }

    @Test
    fun findAndReplaceBlockedInViewModeAllowedInEdit() {
        val v = vm()
        val id = bodyId(v)
        v.replaceAll("alpha", "beta", false)
        v.replaceOne(id, 0..1, "X")
        assertEquals("alpha", paras(v.doc!!)[0].text)
        assertFalse(v.dirty)
        v.setEditMode(true)
        v.replaceAll("alpha", "beta", false)
        assertEquals("beta", paras(v.doc!!)[0].text)
        v.undo()
        assertEquals("alpha", paras(v.doc!!)[0].text)
        v.replaceAll("alpha", "beta", false)
        v.setEditMode(false)
        v.replaceOne(id, 0..1, "X")
        assertEquals("beta", paras(v.doc!!)[0].text)
    }

    @Test
    fun formattingBlockedInViewModeAllowedInEdit() {
        val v = vm()
        val id = bodyId(v)
        val unchanged = paras(v.doc!!)[0]
        v.replace(unchanged.edited(pPr = unchanged.pPr + "TEST"))
        assertFalse(v.dirty)
        v.setEditMode(true)
        val p = paras(v.doc!!)[0]
        v.replace(p.edited(pPr = p.pPr + "TEST"))
        assertTrue(v.dirty)
        assertEquals("TEST", paras(v.doc!!).first { it.id == id }.pPr.takeLast(4))
    }

    @Test
    fun undoRedoBlockedInViewModeStillCorrectInEdit() {
        val v = vm()
        val id = bodyId(v)
        v.setEditMode(true)
        v.typed(id, "beta", CharFmt.Plain)
        v.setEditMode(false)
        v.undo()
        assertEquals("beta", paras(v.doc!!)[0].text)
        assertTrue(v.canUndo)
        v.setEditMode(true)
        v.undo()
        assertEquals("alpha", paras(v.doc!!)[0].text)
        assertFalse(v.canUndo)
        v.setEditMode(false)
        v.redo()
        assertEquals("alpha", paras(v.doc!!)[0].text)
        v.setEditMode(true)
        v.redo()
        assertEquals("beta", paras(v.doc!!)[0].text)
    }

    // ── comments ───────────────────────────────────────────────────────────────

    @Test
    fun addCommentBlockedInViewMode() {
        val v = vm(anchoredComment = true)
        val before = v.doc
        assertFalse(v.addComment(bodyId(v), "note")) // anchored request from View Mode
        assertFalse(v.addComment(null, "note"))
        assertSame(before, v.doc)
        assertEquals(1, v.pkg!!.comments.items.size)
        assertEquals(1, v.comments().size)
    }

    @Test
    fun deleteCommentBlockedInViewMode() {
        val v = vm(anchoredComment = true)
        v.deleteComment("1")
        assertEquals(1, v.comments().size)
        assertTrue(v.comments()[0].text == "First note")
    }

    @Test
    fun addCommentWithoutCaretAnchorsToFirstEditableParagraph() {
        val v = vm()
        v.setEditMode(true)
        val id = bodyId(v)
        assertTrue(v.addComment(null, "note"))
        val items = v.pkg!!.comments.items
        assertEquals(1, items.size)
        assertEquals("note", items[0].text)
        // The body paragraph is the first editable one and now carries the new anchor.
        assertTrue(v.para(id)!!.extras.contains("commentRangeStart w:id=\"${items[0].id}\""))
        assertEquals(1, v.comments().size)
    }

    @Test
    fun blankCommentsRejected() {
        val v = vm()
        v.setEditMode(true)
        assertFalse(v.addComment(null, ""))
        assertFalse(v.addComment(null, "   "))
        assertEquals(0, v.pkg!!.comments.items.size)
        assertFalse(v.dirty)
    }

    @Test
    fun multipleCommentsGetDistinctIdsAndBothList() {
        val v = vm()
        v.setEditMode(true)
        val id = bodyId(v)
        assertTrue(v.addComment(null, "note a"))
        assertTrue(v.addComment(id, "note b"))
        val listed = v.comments()
        assertEquals(2, listed.size)
        assertTrue(listed.all { it.text.startsWith("note ") })
        assertTrue(listed[0].id != listed[1].id)
    }

    @Test
    fun returningToViewModeRelocksEverything() {
        val v = vm()
        v.setEditMode(true)
        assertTrue(v.addComment(null, "note"))
        v.setEditMode(false)
        val frozen = v.doc
        val ids = paras(v.doc!!).map { it.id }
        v.typed(ids[0], "hacked", CharFmt.Plain)
        assertNull(v.split(ids[0], "x", 0, CharFmt.Plain))
        assertFalse(v.addComment(null, "sneaky"))
        v.deleteComment(v.pkg!!.comments.items[0].id)
        v.replaceOne(ids[0], 0..1, "X")
        v.replaceAll("note", "gone", false)
        v.mapParas(ids) { p -> p.edited(text = p.text + "x") }
        assertSame(frozen, v.doc)
        assertEquals(1, v.comments().size)
    }

    // ── viewing stays functional ───────────────────────────────────────────────

    @Test
    fun viewingOperationsRemainAvailableInViewMode() {
        val v = vm(anchoredComment = true)
        assertEquals(listOf("First note"), v.comments().map { it.text })
        assertTrue(v.para(bodyId(v)) != null)
        assertTrue(v.para(cellId(v)) != null)
        assertFalse(v.dirty)
    }
}
