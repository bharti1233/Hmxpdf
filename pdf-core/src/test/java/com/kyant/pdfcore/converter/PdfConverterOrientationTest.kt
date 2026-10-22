package com.kyant.pdfcore.converter

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfConverterOrientationTest {

    @Test fun exifRotationSwapsPageDimensionsOnce() {
        assertEquals(
            3000 to 4000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_ROTATE_90, 0)
        )
        assertEquals(
            4000 to 3000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_ROTATE_270, 0)
        )
    }

    @Test fun exifAndManualRotationAreEachAppliedOnce() {
        // 90 + 90 = 180, so the landscape page becomes landscape again. Applying either twice
        // would produce 270 and a portrait page.
        assertEquals(
            4000 to 3000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_ROTATE_90, 1)
        )
    }

    @Test fun manualRotationAloneSwapsPageDimensions() {
        assertEquals(
            3000 to 4000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_NORMAL, 1)
        )
    }

    @Test fun flipsPreservePageDimensions() {
        assertEquals(
            4000 to 3000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 0)
        )
        assertEquals(
            4000 to 3000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_FLIP_VERTICAL, 0)
        )
    }

    @Test fun transposeOrientationsSwapPageDimensions() {
        assertEquals(
            3000 to 4000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_TRANSPOSE, 0)
        )
        assertEquals(
            3000 to 4000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_TRANSVERSE, 0)
        )
    }

    @Test fun manualRotationIsNormalized() {
        assertEquals(
            3000 to 4000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_NORMAL, -1)
        )
        assertEquals(
            3000 to 4000,
            pageSizeAfterTransform(4000, 3000, ExifInterface.ORIENTATION_NORMAL, 5)
        )
    }
}
