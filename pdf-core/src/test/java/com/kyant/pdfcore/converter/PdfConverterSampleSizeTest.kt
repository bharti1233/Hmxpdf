package com.kyant.pdfcore.converter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfConverterSampleSizeTest {

    @Test fun normalPhotoIsNotDownsampled() {
        assertEquals(1, sampleSize(4000, 3000, 12_000_000))
    }

    @Test fun hugePhotoIsDownsampledWithinBudget() {
        val sample = sampleSize(16000, 12000, 12_000_000)
        assertTrue(sample > 1)
        assertTrue((16000L / sample) * (12000L / sample) <= 12_000_000)
    }

    @Test fun invalidDimensionsUseSampleOne() {
        assertEquals(1, sampleSize(0, 100, 1_000_000))
        assertEquals(1, sampleSize(-1, -1, 1_000_000))
    }
}
