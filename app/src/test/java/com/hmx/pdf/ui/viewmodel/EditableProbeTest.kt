package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.DocxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One-run diagnostic for the phase 1.1 view-mode CI failure; removed once the cause is known. */
class EditableProbeTest {

    private fun docx(): ByteArray {
        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val classes = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"
        val pend = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val marco = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>" +
            "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>" +
            "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>"
        val parte = linkedMapOf(
            "[Content_Types].xml" to classes,
            "_rels/.rels" to pend,
            "word/document.xml" to marco
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in parte) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    @Test
    fun probe() {
        val (pkg, state) = DocxReader.read(ByteArrayInputStream(docx()))!!
        val v = DocxEditorViewModel()
        println("PROBE0 writable=${pkg.writable} mirror=${v.testFileMirror} editable=${v.editable}")
        v.primeForTest(pkg to state)
        println(
            "PROBE1 writable=${pkg.writable} mirror=${v.testFileMirror} editable=${v.editable} mode=${v.editMode} " +
                "pkgSame=${v.pkg === pkg} mirrorField=" + run {
                    v.javaClass.getDeclaredField("testFileMirror").isAccessible = true; v.javaClass.getDeclaredField("testFileMirror").get(v)
                }
        )
        assertTrue("p1Mirror=${v.testFileMirror}", v.testFileMirror == true)
        assertEquals("p2PkgSame", v.pkg, pkg)
        assertTrue("p3Editable=${v.editable}", v.editable)
        v.setEditMode(true)
        println("PROBE2 mode=${v.editMode} editable=${v.editable} mirror=${v.testFileMirror}")
        assertTrue("p4Mode=${v.editMode}", v.editMode)
    }
}
