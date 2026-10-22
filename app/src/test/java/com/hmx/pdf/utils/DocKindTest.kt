package com.hmx.pdf.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocKindTest {

    @Test
    fun opaqueContentUriFallsBackToTheSenderMimeType() {
        assertEquals(
            DocKind.Excel,
            docKindOf("content://provider/document/1234", "application/vnd.ms-excel")
        )
        assertEquals(
            DocKind.Image,
            docKindOf("content://provider/document/1234", "image/jpeg")
        )
        assertEquals(
            DocKind.Pdf,
            docKindOf("content://provider/document/1234", "application/pdf")
        )
    }

    @Test
    fun aUsefulExtensionStillWinsOverAGenericMimeType() {
        assertEquals(
            DocKind.Excel,
            docKindOf("budget.xlsx", "application/octet-stream")
        )
        assertEquals(
            DocKind.Word,
            docKindOf("report.docx", "application/octet-stream")
        )
    }

    @Test
    fun onlyDocxUsesTheWordEditorRoute() {
        assertTrue(isDocxDocument("report.docx", "application/octet-stream"))
        assertTrue(
            isDocxDocument(
                "content://provider/document/1234",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
        assertFalse(isDocxDocument("report.doc", "application/msword"))
        assertFalse(isDocxDocument("budget.xlsx", "application/vnd.ms-excel"))
    }
}
