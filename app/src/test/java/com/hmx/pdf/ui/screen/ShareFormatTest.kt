package com.hmx.pdf.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareFormatTest {

    @Test
    fun cleanConvertedDocumentKeepsTheOriginalDefault() {
        assertEquals(
            ShareFormat.ORIGINAL,
            defaultShareFormat(pdfOnly = false, hasUnsavedEdits = false)
        )
    }

    @Test
    fun pendingEditsNeverDefaultToTheOriginalDocument() {
        assertEquals(
            ShareFormat.PDF,
            defaultShareFormat(pdfOnly = false, hasUnsavedEdits = true)
        )
    }

    @Test
    fun plainPdfsAlwaysDefaultToPdf() {
        assertEquals(
            ShareFormat.PDF,
            defaultShareFormat(pdfOnly = true, hasUnsavedEdits = false)
        )
    }
}
