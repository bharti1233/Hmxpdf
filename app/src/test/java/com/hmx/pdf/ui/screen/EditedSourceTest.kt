package com.hmx.pdf.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EditedSourceTest {

    @Test
    fun `edited export uses the edited copy`() {
        assertEquals(
            "edited.pdf",
            editedSourceOrThrow(
                hasEdits = true,
                wantsOriginal = false,
                original = "original.pdf",
                edited = { "edited.pdf" }
            )
        )
    }

    @Test
    fun `failed edited export throws instead of returning the original`() {
        assertThrows(IllegalStateException::class.java) {
            editedSourceOrThrow(
                hasEdits = true,
                wantsOriginal = false,
                original = "original.pdf",
                edited = { null }
            )
        }
    }

    @Test
    fun `unedited export uses the original`() {
        assertEquals(
            "original.pdf",
            editedSourceOrThrow(
                hasEdits = false,
                wantsOriginal = false,
                original = "original.pdf",
                edited = { error("must not be called") }
            )
        )
    }

    @Test
    fun `choosing original keeps the original even with edits`() {
        assertEquals(
            "original.pdf",
            editedSourceOrThrow(
                hasEdits = true,
                wantsOriginal = true,
                original = "original.pdf",
                edited = { error("must not be called") }
            )
        )
    }
}
