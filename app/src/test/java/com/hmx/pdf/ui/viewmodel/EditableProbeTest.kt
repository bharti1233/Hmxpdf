package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.DocxReader
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
        v.primeForTest(pkg to state)
        assertTrue("p1Mirror=${v.testFileMirror}", v.testFileMirror == true)
        assertTrue("p2PkgSame=${v.pkg === pkg}", v.pkg === pkg)
        assertTrue("p3Writable=${v.pkg?.writable}", v.pkg?.writable == true)
        assertTrue("p4Editable=${v.editable}", v.editable)
        v.setEditMode(true)
        assertTrue("p5Mode=${v.editMode}", v.editMode)
    }
}
