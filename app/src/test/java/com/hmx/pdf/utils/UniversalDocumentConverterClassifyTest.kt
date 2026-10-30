package com.hmx.pdf.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The converter must recognise only its supported source types and reject everything else.
 * Unknown binaries used to fall through to the text converter and become garbage PDFs.
 */
class UniversalDocumentConverterClassifyTest {

    @Test
    fun recognizes_every_supported_kind() {
        assertEquals(UniversalDocumentConverter.SourceKind.IMAGE,
            UniversalDocumentConverter.classifySource("photo.jpg", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.IMAGE,
            UniversalDocumentConverter.classifySource("blob.bin", "image/png"))
        assertEquals(UniversalDocumentConverter.SourceKind.DOCX,
            UniversalDocumentConverter.classifySource("report.docx", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.XLSX,
            UniversalDocumentConverter.classifySource("book.xlsx", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.PPTX,
            UniversalDocumentConverter.classifySource("deck.pptx", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.ODT,
            UniversalDocumentConverter.classifySource("paper.odt", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.DOC,
            UniversalDocumentConverter.classifySource("legacy.doc", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.XLS,
            UniversalDocumentConverter.classifySource("legacy.xls", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.PPT,
            UniversalDocumentConverter.classifySource("legacy.ppt", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.TEXT,
            UniversalDocumentConverter.classifySource("notes.md", ""))
        assertEquals(UniversalDocumentConverter.SourceKind.TEXT,
            UniversalDocumentConverter.classifySource("data.blob", "text/plain"))
    }

    @Test
    fun rejects_unknown_binaries_instead_of_guessing() {
        assertNull(UniversalDocumentConverter.classifySource("archive.zip", ""))
        assertNull(UniversalDocumentConverter.classifySource("book.epub", ""))
        assertNull(UniversalDocumentConverter.classifySource("firmware.bin", "application/octet-stream"))
        assertNull(UniversalDocumentConverter.classifySource("", ""))
    }

    @Test
    fun ignored_prefixes_do_not_shadow_explicit_names() {
        // image/png MIME + .zip name resolves to IMAGE (mime wins), text/plain + .zip resolves
        // to TEXT; only unmatched pairs are rejected.
        assertEquals(UniversalDocumentConverter.SourceKind.TEXT,
            UniversalDocumentConverter.classifySource("odd.zip", "text/plain"))
    }

    @Test
    fun unsupported_message_is_type_or_fallback() {
        val e = UniversalDocumentConverter.UnsupportedDocumentException("archive.zip")
        assertEquals("archive.zip", e.message)
    }
}
