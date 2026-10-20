package com.hmx.pdf.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EditedSourceTest {

    @Test
    fun editedExportUsesTheEditedCopy() {
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
    fun failedEditedExportThrowsInsteadOfReturningTheOriginal() {
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
    fun uneditedExportUsesTheOriginal() {
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
    fun choosingOriginalKeepsTheOriginalEvenWithEdits() {
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
