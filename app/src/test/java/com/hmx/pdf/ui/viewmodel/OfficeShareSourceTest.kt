package com.hmx.pdf.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficeShareSourceTest {

    @Test
    fun `clean document shares the original`() {
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
    fun `dirty document shares the edited copy`() {
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
    fun `failed edited export never falls back to the original`() {
        assertNull(
            officeShareSource<String>(
                hasEdits = true,
                original = "original.docx",
                edited = { null }
            )
        )
    }
}
