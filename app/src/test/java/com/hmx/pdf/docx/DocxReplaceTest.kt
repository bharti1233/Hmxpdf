package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Find & replace: matching, replacement formatting, byte preservation. */
class DocxReplaceTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

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

    private fun paras(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks, includePreview = false) { out.add(it) }
        return out
    }

    private fun para(text: String, fmt: CharFmt = CharFmt.Plain, vararg brk: Pair<Int, CharFmt>): DocPara {
        val fmts = (0 until text.length).map { brk.lastOrNull { (i, _) -> i <= it }?.second ?: fmt }
        return DocPara(DocIds.next(), null, "<w:p>", "", "", text, fmts, editable = true)
    }

    private val bold = CharFmt("<w:b/>")
    private val italic = CharFmt("<w:i/>")
    private val other = CharFmt("<w:sz w:val=\"28\"/>")

    @Test
    fun findsCaseVariants() {
        assertEquals(listOf(0..4, 6..10), DocxEdits.matchesIn("Hello hello", "hello", true))
        assertEquals(listOf(6..10), DocxEdits.matchesIn("Hello hello", "hello", false))
        assertTrue(DocxEdits.matchesIn("Hello", "world", true).isEmpty())
    }

    @Test
    fun matchNeverIncludesAnObjectChar() {
        val t = "a${OBJ_CHAR}b"
        assertTrue(DocxEdits.matchesIn(t, OBJ_CHAR.toString(), true).isEmpty())
        assertEquals(listOf(0..0), DocxEdits.matchesIn(t, "a", true))
    }

    @Test
    fun replacesOneRunKeepingItsFormat() {
        val p = para("Hello world", bold, 5 to italic)
        val r = DocxEdits.replaceRange(p, 0, 5, "Howdy")
        assertEquals("Howdy world", r.text)
        assertEquals(bold, r.fmts[0])
        assertEquals(italic, r.fmts[5])
        assertEquals(italic, r.fmts[10])
    }

    @Test
    fun replacesAllRangesRightToLeft() {
        val p = para("abab", bold, 2 to other)
        val r = DocxEdits.replaceAllIn(p, DocxEdits.matchesIn(p.text, "ab", false), "x")
        assertEquals("xx", r.text)
        assertEquals(bold, r.fmts[0])
        assertEquals(other, r.fmts[1])
    }

    @Test
    fun noRangesMeansUnchanged() {
        val p = para("abc")
        assertSame(p, DocxEdits.replaceAllIn(p, emptyList(), "x"))
    }

    @Test
    fun replacementsWithSpacesAndPunctuation() {
        val r = DocxEdits.replaceRange(para("the cat, sat"), 4, 7, "dog (big)")
        assertEquals("the dog (big), sat", r.text)
    }

    @Test
    fun pageMarksShiftWithTheEdit() {
        val p = para("abc def").let { it.edited(marks = listOf(4)) }
        val r = DocxEdits.replaceRange(p, 0, 1, "xy")
        assertEquals("xybc def", r.text)
        assertEquals(listOf(5), r.marks)
    }

    @Test
    fun replacingNeverRewritesUntouchedParagraphs() {
        val one = "<w:p><w:r><w:t>kestrel</w:t></w:r></w:p>"
        val two = "<w:p><w:r><w:t>cat sat on cat</w:t></w:r></w:p>"
        val (pkg, state) = DocxReader.read(docx("$one$two").inputStream())!!
        val ids = HashMap<Long, List<IntRange>>()
        forEachPara(state.blocks) { p ->
            val ms = DocxEdits.matchesIn(p.text, "cat", false)
            if (ms.isNotEmpty()) ids[p.id] = ms
        }
        var blocks = state.blocks
        forEachPara(state.blocks) { p ->
            ids[p.id]?.let { ms ->
                val np = DocxEdits.replaceAllIn(p, ms, "bat")
                if (np !== p) blocks = replacePara(blocks, np)!!
            }
        }
        val xml = DocxWriter.documentXml(pkg, DocState(blocks))
        // The untouched paragraph is byte-identical; the edited one is rebuilt with the replacement.
        assertTrue(xml.contains(one))
        assertFalse(xml.contains("<w:t>cat sat on cat</w:t>"))
        assertTrue(xml.contains(">bat sat on bat"))
        assertEquals(listOf("kestrel", "bat sat on bat"), paras(DocState(blocks)).map { it.text })
    }
}
