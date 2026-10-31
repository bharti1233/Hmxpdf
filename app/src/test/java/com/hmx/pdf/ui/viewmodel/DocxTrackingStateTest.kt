package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.DocxReader
import com.hmx.pdf.docx.forEachPara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Track Changes at the ViewModel (P2-1/P2-5/P2-7): the toggle is Edit-Mode-only, disabling never
 * resolves pending revisions, capture writes only when asked, and the bulk actions are one undo
 * step that restores the revisions exactly. Serialization itself is the writer's contract,
 * covered by `DocxRevisionsTest`.
 */
class DocxTrackingStateTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private fun docx(): ByteArray {
        val body = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>beta</w:t></w:r></w:p>"
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
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

    private fun vm(): DocxEditorViewModel {
        val (pkg, state) = DocxReader.read(docx().inputStream())!!
        val v = DocxEditorViewModel()
        v.primeForTest(pkg to state)
        return v
    }

    private fun paras(state: DocState): List<com.hmx.pdf.docx.DocPara> {
        val out = ArrayList<com.hmx.pdf.docx.DocPara>()
        forEachPara(state.blocks, includePreview = false) { out.add(it) }
        return out
    }

    private fun pendingCount(v: DocxEditorViewModel): Int = v.pendingRevisions().size

    @Test
    fun trackingDefaultsOff() {
        val v = vm()
        assertFalse(v.tracking)
    }

    @Test
    fun setTrackingRefusedInViewMode() {
        val v = vm()
        v.setTracking(true)
        assertFalse(v.tracking)
        // Disabling is fine anywhere.
        v.setTracking(false)
        assertFalse(v.tracking)
    }

    @Test
    fun setTrackingWorksInEditModeAndSurvivesLeavingIt() {
        val v = vm()
        v.setEditMode(true)
        v.setTracking(true)
        assertTrue(v.tracking)
        v.setEditMode(false)
        // Leaving Edit Mode doesn't resolve anything, and re-entering keeps the toggle as it was.
        assertEquals(0, pendingCount(v))
        assertTrue(v.tracking)
        v.setEditMode(true)
        v.setTracking(false)
        assertFalse(v.tracking)
    }

    @Test
    fun typingWithTrackingRecordsOneRevisionPerSpurt() {
        val v = vm()
        v.setEditMode(true)
        v.setTracking(true)
        val id = paras(v.doc!!)[0].id
        val caret = v.typed(id, "alphaxy", CharFmt.Plain)
        assertTrue("caret=$caret", caret >= 0)
        assertEquals(1, pendingCount(v))
        // The same undo spurt keeps one revision.
        Thread.sleep(2)
        val caret2 = v.typed(id, "alphaxyz", CharFmt.Plain)
        assertTrue("caret2=$caret2", caret2 >= 0)
        assertEquals(1, pendingCount(v))
        assertEquals(8, caret2)
    }

    @Test
    fun typingWithoutTrackingRecordsNothing() {
        val v = vm()
        v.setEditMode(true)
        val id = paras(v.doc!!)[0].id
        assertEquals(-1, v.typed(id, "alphaxy", CharFmt.Plain))
        assertEquals(0, pendingCount(v))
    }

    @Test
    fun disablingTrackingKeepsPendingRevisions() {
        val v = vm()
        v.setEditMode(true)
        v.setTracking(true)
        val id = paras(v.doc!!)[0].id
        v.typed(id, "alphaxy", CharFmt.Plain)
        val id2 = paras(v.doc!!)[1].id
        // A deletion capture on the other paragraph: "beta" -> "ba" deletes "et".
        v.typed(id2, "ba", CharFmt.Plain)
        assertEquals(2, pendingCount(v))
        v.setTracking(false)
        assertEquals(2, pendingCount(v))
        // Plain typing after disabling captures nothing and leaves the two alone.
        v.typed(id, "alphaxyz", CharFmt.Plain)
        assertEquals(2, pendingCount(v))
    }

    @Test
    fun resolveRevisionTouchesOnlyItsOwn() {
        val v = vm()
        v.setEditMode(true)
        v.setTracking(true)
        v.typed(paras(v.doc!!)[0].id, "alphaxy", CharFmt.Plain) // insertion
        v.typed(paras(v.doc!!)[1].id, "ba", CharFmt.Plain) // deletion
        val infos = v.pendingRevisions()
        assertEquals(2, infos.size)
        val deletion = infos.first { !it.ins }
        v.resolveRevision(deletion, accept = true)
        val after = v.pendingRevisions()
        assertEquals(1, after.size)
        assertTrue(after.single().ins)
        assertTrue(paras(v.doc!!)[1].text == "ba")
    }

    @Test
    fun bulkResolutionIsOneUndoStep() {
        val v = vm()
        v.setEditMode(true)
        v.setTracking(true)
        v.typed(paras(v.doc!!)[0].id, "alphaxy", CharFmt.Plain)
        v.typed(paras(v.doc!!)[1].id, "ba", CharFmt.Plain)
        val accepted = v.pendingRevisions().first { it.ins }
        v.resolveRevision(accepted, accept = true)
        assertEquals(1, pendingCount(v))
        // Bulk resolve is its own single undo step…
        v.resolveAllRevisions(accept = true)
        assertEquals(0, pendingCount(v))
        // …and one undo restores pre-bulk state with its revisions exactly.
        v.undo()
        assertEquals(1, pendingCount(v))
        assertFalse(v.pendingRevisions().single().ins)
        assertTrue(paras(v.doc!!)[0].text == "alphaxy")
        assertTrue(paras(v.doc!!)[1].text == "beta")
    }

    @Test
    fun resolutionRefusedInViewMode() {
        val v = vm()
        v.setEditMode(true)
        v.setTracking(true)
        v.typed(paras(v.doc!!)[0].id, "alphaxy", CharFmt.Plain)
        val info = v.pendingRevisions().single()
        v.setEditMode(false)
        v.resolveRevision(info, accept = true)
        assertEquals(1, pendingCount(v))
        // No revision data at all: the ViewModel capture is a no-op even if a caller races.
        v.resolveAllRevisions(false)
        assertEquals(1, pendingCount(v))
    }
}
