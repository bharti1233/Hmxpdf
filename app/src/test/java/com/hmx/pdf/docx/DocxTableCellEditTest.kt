package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Table cell editing rides the same paragraph pipeline as body text (audit §4.4 — confirmed by
 * `DocxRoundTripTest.editingATableCellRebuildsOnlyThatTable`); these cover the remaining shapes:
 * several edited cells in one save, typing into an empty cell, and untouched tables staying byte
 * identical next to edited ones.
 */
class DocxTableCellEditTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private fun cell(text: String) = "<w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>" +
        "<w:p><w:r><w:t>$text</w:t></w:r></w:p></w:tc>"

    private fun table(rows: List<String>) = "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>" +
        "<w:tblGrid><w:gridCol w:w=\"4000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
        rows.joinToString("") { "<w:tr>$it</w:tr>" } + "</w:tbl>"

    private fun docx(body: String): ByteArray {
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>$body" +
                "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>"
        )
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
    fun severalCellsEditInOneSave() {
        val body = table(listOf(cell("java 1") + cell("java 2"), cell("name 1") + cell("name 2")))
        val bytes = docx(body)
        val (pkg, state) = read(bytes)
        var blocks = state.blocks
        for (p in paras(state)) {
            val t = p.text
            val np = DocxEdits.typed(p, t + "!", DocxEdits.fmtAt(p, t.length))
            if (np !== p) blocks = replacePara(blocks, np)!!
        }
        val out = save(bytes, pkg, DocState(blocks))
        val ps = paras(read(out).second)
        assertEquals(listOf("java 1!", "java 2!", "name 1!", "name 2!"), ps.take(4).map { it.text })
    }

    @Test
    fun typingIntoAnEmptyCellWorks() {
        val body = table(listOf(cell("") + cell("kept")))
        val bytes = docx(body)
        val (pkg, state) = read(bytes)
        val empty = paras(state)[0]
        assertEquals("", empty.text)
        val filled = DocxEdits.typed(empty, "filled", CharFmt.Plain)
        val out = save(bytes, pkg, DocState(replacePara(state.blocks, filled)!!))
        val ps = paras(read(out).second)
        assertEquals(listOf("filled", "kept"), ps.take(2).map { it.text })
    }

    @Test
    fun editedCellsLeaveOtherTablesByteIdentical() {
        val untouched = table(listOf(cell("uA") + cell("uB")))
        val editedTable = table(listOf(cell("A") + cell("B")))
        val bytes = docx(untouched + editedTable)
        val (pkg, state) = read(bytes)
        val first = paras(state)[2] // first para of the second table
        assertEquals("A", first.text)
        val typed = DocxEdits.typed(first, "A!", DocxEdits.fmtAt(first, 1))
        val out = save(bytes, pkg, DocState(replacePara(state.blocks, typed)!!))
        val xml = entry(out, "word/document.xml")!!
        assertTrue(xml, xml.contains(cell("uA")))
        assertTrue(xml.contains(cell("uB")))
        assertTrue(xml.contains(">A!<"))
        assertEquals(listOf("uA", "uB", "A!", "B"), paras(read(out).second).map { it.text })
    }

    @Test
    fun multiParagraphCellKeepsItsSiblingParagraph() {
        val body = table(listOf("<w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>" +
            "<w:p><w:r><w:t>first</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>second</w:t></w:r></w:p></w:tc>" + cell("other")))
        val bytes = docx(body)
        val (pkg, state) = read(bytes)
        val first = paras(state)[0]
        val typed = DocxEdits.typed(first, "first edited", DocxEdits.fmtAt(first, 5))
        val out = save(bytes, pkg, DocState(replacePara(state.blocks, typed)!!))
        assertEquals(listOf("first edited", "second", "other"), paras(read(out).second).map { it.text })
    }
}
