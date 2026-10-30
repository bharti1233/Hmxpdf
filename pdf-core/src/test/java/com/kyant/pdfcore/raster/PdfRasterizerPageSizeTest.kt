package com.kyant.pdfcore.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages that exceed the per-page pixel budget must shrink, never over-allocate and OOM. */
class PdfRasterizerPageSizeTest {

    private fun assertSize(pageW: Int, pageH: Int, dpi: Int, maxPixels: Long): IntArray =
        PdfRasterizer.boundedPageSize(pageW, pageH, dpi, maxPixels)

    @Test
    fun normal_pages_keep_the_requested_dpi() {
        // A4 portrait at 150 dpi ≈ 1239 × 1754 (well under any budget).
        val s = assertSize(595, 842, 150, PdfRasterizer.MAX_PAGE_PIXELS)
        assertEquals(1239, s[0])
        assertEquals(1754, s[1])
    }

    @Test
    fun oversized_pages_are_scaled_down_within_budget() {
        // 34" × 44" engineering sheet at 150 dpi ≈ 5100 × 6600 = ~33.7 MP > 16 MP budget.
        val s = assertSize(2448, 3168, 150, PdfRasterizer.MAX_PAGE_PIXELS)
        val pixels = s[0].toLong() * s[1].toLong()
        assertTrue("pixels=$pixels must fit the budget", pixels <= PdfRasterizer.MAX_PAGE_PIXELS)
        // Scale-down must be proportional (same aspect ratio), never zero.
        val ratioIn = 2448.0 / 3168.0
        val ratioOut = s[0].toDouble() / s[1].toDouble()
        assertTrue(Math.abs(ratioIn - ratioOut) < 0.05)
        assertTrue(s[0] >= 1 && s[1] >= 1)
    }

    @Test
    fun well_under_budget_is_untouched_even_with_tiny_limit() {
        // maxPixels fits exactly → no change.
        val s = assertSize(100, 100, 72, 10_000L)
        assertEquals(100, s[0])
        assertEquals(100, s[1])
    }

    @Test
    fun degenerate_pages_stay_positive() {
        val s = assertSize(0, 0, 72, 100L)
        assertTrue(s[0] >= 1 && s[1] >= 1)
    }
}
