package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Test

/** A negative table grid offset must never become a negative column index. */
class DocxTableLayoutTest {

    @Test
    fun negativeGridBeforeClampsToZero() {
        assertEquals(0, tableGridBefore("<w:trPr><w:gridBefore w:val=\"-1\"/></w:trPr>"))
    }

    @Test
    fun positiveGridBeforeIsPreserved() {
        assertEquals(2, tableGridBefore("<w:trPr><w:gridBefore w:val=\"2\"/></w:trPr>"))
    }

    @Test
    fun missingOrInvalidGridBeforeIsZero() {
        assertEquals(0, tableGridBefore(""))
        assertEquals(0, tableGridBefore("<w:trPr/>"))
        assertEquals(0, tableGridBefore("<w:trPr><w:gridBefore w:val=\"x\"/></w:trPr>"))
    }
}
