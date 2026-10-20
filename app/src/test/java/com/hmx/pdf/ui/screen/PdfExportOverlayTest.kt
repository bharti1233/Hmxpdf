package com.hmx.pdf.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExportOverlayTest {

    @Test
    fun `missing page layout fails instead of silently dropping the edit`() {
        val result = buildExportOverlays(
            annotationsByPage = mapOf(
                2 to listOf(
                    PdfMarkup.RectMarkup(
                        start = Offset(10f, 10f),
                        end = Offset(20f, 20f),
                        color = Color.Red
                    )
                )
            ),
            ocrBlocksByPage = emptyMap(),
            pageCanvasSizes = emptyMap(),
            pageBitmapSizes = emptyMap()
        )

        assertFalse(result.isComplete)
        assertEquals(0, result.overlays.size)
        assertTrue(result.failures.single().contains("Page 3"))
    }

    @Test
    fun `missing OCR block fails instead of dropping a highlight`() {
        val result = buildExportOverlays(
            annotationsByPage = mapOf(
                0 to listOf(
                    PdfMarkup.TextBlockHighlightMarkup(
                        blockId = "gone",
                        color = Color.Yellow
                    )
                )
            ),
            ocrBlocksByPage = emptyMap(),
            pageCanvasSizes = mapOf(0 to Size(100f, 100f)),
            pageBitmapSizes = mapOf(0 to Size(100f, 100f))
        )

        assertFalse(result.isComplete)
        assertEquals(0, result.overlays.size)
        assertTrue(result.failures.single().contains("OCR"))
    }

    @Test
    fun `empty annotations are a genuine empty export`() {
        val result = buildExportOverlays(
            annotationsByPage = emptyMap(),
            ocrBlocksByPage = emptyMap(),
            pageCanvasSizes = emptyMap(),
            pageBitmapSizes = emptyMap()
        )

        assertTrue(result.isComplete)
        assertTrue(result.overlays.isEmpty())
    }

    @Test
    fun `blank text box is a no-op rather than a failure`() {
        val result = buildExportOverlays(
            annotationsByPage = mapOf(
                0 to listOf(
                    PdfMarkup.TextBoxMarkup(
                        id = 1L,
                        position = Offset(10f, 10f),
                        text = "   ",
                        color = Color.Black
                    )
                )
            ),
            ocrBlocksByPage = emptyMap(),
            pageCanvasSizes = mapOf(0 to Size(100f, 100f)),
            pageBitmapSizes = mapOf(0 to Size(100f, 100f))
        )

        assertTrue(result.isComplete)
        assertTrue(result.overlays.isEmpty())
    }

    @Test
    fun `unchanged in-place text edit is a no-op rather than a failure`() {
        val result = buildExportOverlays(
            annotationsByPage = mapOf(
                0 to listOf(
                    PdfMarkup.TextEditMarkup(
                        id = 1L,
                        blockId = "line",
                        rect = Rect(0f, 0f, 50f, 10f),
                        baseline = 8f,
                        fontSize = 10f,
                        original = "same",
                        text = "same",
                        color = Color.Black,
                        background = Color.White,
                        bold = false,
                        italic = false,
                        serif = false,
                        mono = false
                    )
                )
            ),
            ocrBlocksByPage = emptyMap(),
            pageCanvasSizes = mapOf(0 to Size(100f, 100f)),
            pageBitmapSizes = mapOf(0 to Size(100f, 100f))
        )

        assertTrue(result.isComplete)
        assertTrue(result.overlays.isEmpty())
    }
}
