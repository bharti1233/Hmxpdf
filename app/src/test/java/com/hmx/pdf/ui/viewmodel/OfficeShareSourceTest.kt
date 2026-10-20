package com.hmx.pdf.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficeShareSourceTest {

    @Test
    fun cleanDocumentSharesTheOriginal() {
        assertEquals(
            "original.docx",
            officeShareSource(
                hasEdits = false,
                original = "original.docx",
                edited = { error("must not be called") }
            )
        )
    }

    @Test
    fun dirtyDocumentSharesTheEditedCopy() {
        assertEquals(
            "edited.docx",
            officeShareSource(
                hasEdits = true,
                original = "original.docx",
                edited = { "edited.docx" }
            )
        )
    }

    @Test
    fun failedEditedExportNeverFallsBackToTheOriginal() {
        assertNull(
            officeShareSource<String>(
                hasEdits = true,
                original = "original.docx",
                edited = { null }
            )
        )
    }
}
