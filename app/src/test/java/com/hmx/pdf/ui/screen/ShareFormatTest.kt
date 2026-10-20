package com.hmx.pdf.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareFormatTest {

    @Test
    fun `clean converted document keeps the original default`() {
        assertEquals(
            ShareFormat.ORIGINAL,
            defaultShareFormat(pdfOnly = false, hasUnsavedEdits = false)
        )
    }

    @Test
    fun `pending edits never default to the original document`() {
        assertEquals(
            ShareFormat.PDF,
            defaultShareFormat(pdfOnly = false, hasUnsavedEdits = true)
        )
    }

    @Test
    fun `plain PDFs always default to PDF`() {
        assertEquals(
            ShareFormat.PDF,
            defaultShareFormat(pdfOnly = true, hasUnsavedEdits = false)
        )
    }
}
