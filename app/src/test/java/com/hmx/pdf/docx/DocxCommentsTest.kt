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

/** DOCX comments: read, add, delete, save/reopen. */
class DocxCommentsTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private val CT_COMMENTS = "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"

    private fun commentXml(id: Int, author: String, text: String) =
        "<w:comment w:id=\"$id\" w:author=\"$author\" w:date=\"2026-01-0${id}T00:00:00Z\"><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:comment>"

    /** body para anchored as comment [id]: markers around "hello" plus a reference run. */
    private val anchoredBody = "<w:commentRangeStart w:id=\"1\"/>" +
        "<w:r><w:t>hello</w:t></w:r>" +
        "<w:commentRangeEnd w:id=\"1\"/>" +
        "<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference w:id=\"1\"/></w:r>"

    private fun docx(body: String, withComments: Boolean = true): ByteArray {
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/>" +
                (if (withComments) "<Override PartName=\"/word/comments.xml\" ContentType=\"$CT_COMMENTS\"/>" else "") +
                "</Types>",
            "_rels/.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/></Relationships>",
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>$body" +
                "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>"
        )
        if (withComments) {
            parts["word/comments.xml"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:comments $ns>${commentXml(1, "Alice", "First note")}</w:comments>"
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun read(bytes: ByteArray) = DocxReader.read(bytes.inputStream())!!

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

    @Test
    fun readsCommentsAndAnchors() {
        val (pkg, state) = read(docx(anchoredBody))
        val items = pkg.comments.items
        assertEquals(1, items.size)
        assertEquals("1", items[0].id)
        assertEquals("Alice", items[0].author)
        assertEquals("First note", items[0].text)
        val p = paras(state)[0]
        assertEquals("hello$OBJ_CHAR", p.text)
        assertTrue(p.extras.contains("commentRangeStart"))
        assertTrue(p.fmts.last().obj!!.contains("commentReference w:id=\"1\""))
    }

    @Test
    fun addSavesReopens() {
        val bytes = docx("<w:p><w:r><w:t>plain</w:t></w:r></w:p>", withComments = false)
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val id = pkg.comments.add("HMXPDF", "H", "2026-01-01T00:00:00Z", "a note")
        val out = save(bytes, pkg, DocState(listOf(DocxComments.anchorPara(p, id))))
        val xmlRead = entry(out, "word/comments.xml")!!
        assertTrue(xmlRead, xmlRead.contains(">a note<"))
        assertTrue(entry(out, "word/_rels/document.xml.rels")!!.contains("relationships/comments"))
        assertTrue(entry(out, "[Content_Types].xml")!!.contains("/word/comments.xml"))
        val (pkg2, state2) = read(out)
        val items = pkg2.comments.items
        assertEquals(1, items.size)
        assertEquals("a note", items[0].text)
        val p2 = paras(state2)[0]
        assertTrue(p2.extras.contains("commentRangeStart w:id=\"$id\""))
        assertEquals("plain$OBJ_CHAR", p2.text)
    }

    @Test
    fun deleteCleansElementAnchorsAndReference() {
        val bytes = docx(anchoredBody)
        val (pkg, state) = read(bytes)
        pkg.comments.remove("1")
        val next = DocState(listOf(DocxComments.stripPara(paras(state)[0], "1")))
        val out = save(bytes, pkg, next)
        assertFalse(entry(out, "word/comments.xml")!!.contains("First note"))
        val (pkg2, state2) = read(out)
        assertTrue(pkg2.comments.items.isEmpty())
        val p = paras(state2)[0]
        assertEquals("hello", p.text)
        assertFalse(p.extras.contains("commentRange"))
        assertFalse(entry(out, "word/document.xml")!!.contains("commentReference"))
    }

    @Test
    fun undoneDeletionKeepsItsElement() {
        val bytes = docx(anchoredBody)
        val (pkg, state) = read(bytes)
        // The element is removed in the model only; the anchor (and its undo) is untouched.
        pkg.comments.remove("1")
        val out = save(bytes, pkg, state)
        val xmlRead = entry(out, "word/comments.xml")!!
        assertTrue(xmlRead, xmlRead.contains("First note"))
        assertEquals("First note", read(out).first.comments.items.single().text)
    }

    @Test
    fun twoAnchorsInOneParagraphLookIndependent() {
        val bytes = docx(anchoredBody)
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val id2 = pkg.comments.add("HMXPDF", "H", "2026-01-02T00:00:00Z", "second")
        val both = DocxComments.anchorPara(p, id2)
        // The original anchor is intact, and stripping the new one restores the paragraph.
        assertTrue(both.extras.contains("id=\"1\""))
        assertTrue(both.extras.contains("id=\"$id2\""))
        val back = DocxComments.stripPara(both, id2)
        assertEquals("hello$OBJ_CHAR", back.text)
        assertTrue(back.fmts.last().obj!!.contains("commentReference w:id=\"1\""))
        assertFalse(back.fmts.last().obj!!.contains("id=\"$id2\""))
    }

    @Test
    fun unknownIdsAreNoOps() {
        val (pkg, _) = read(docx(anchoredBody))
        pkg.comments.remove("999")
        assertFalse(pkg.comments.modified)
        assertEquals("First note", pkg.comments.items.single().text)
    }

    @Test
    fun writeOnlyWhenTouched() {
        val bytes = docx(anchoredBody)
        val (pkg, state) = read(bytes)
        assertFalse(pkg.comments.modified)
        assertEquals(pkg.xml, DocxWriter.documentXml(pkg, state))
        // And the streamed part is byte-identical to what went in.
        val out = save(bytes, pkg, state)
        assertEquals(entry(bytes, "word/comments.xml"), entry(out, "word/comments.xml"))
    }

    @Test
    fun unrelatedTextEditKeepsCommentsPartBytes() {
        val bytes = docx(anchoredBody)
        val (pkg, state) = read(bytes)
        val alone = paras(state).first { p -> p.text == "hello$OBJ_CHAR" }
        val typed = DocxEdits.typed(alone, "hello there$OBJ_CHAR", CharFmt.Plain)
        val out = save(bytes, pkg, DocState(listOf(typed)))
        assertEquals(entry(bytes, "word/comments.xml"), entry(out, "word/comments.xml"))
        assertEquals("First note", read(out).first.comments.items.single().text)
    }
}
