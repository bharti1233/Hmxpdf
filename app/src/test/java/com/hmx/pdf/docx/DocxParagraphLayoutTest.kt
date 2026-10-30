package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paragraph geometry must never send a negative value to Compose padding: a hanging indent is a
 * negative first-line offset, not a negative start padding.
 */
class DocxParagraphLayoutTest {

    private fun layout(pPr: String) = Props.p(pPr).layoutTwips()

    private fun assertPaddingSafe(l: ParaTwips) {
        assertTrue("start must be non-negative", l.start >= 0)
        assertTrue("before must be non-negative", l.before >= 0)
        assertTrue("after must be non-negative", l.after >= 0)
        assertTrue("hanging column must be non-negative", l.hanging >= 0)
    }

    @Test
    fun ordinaryParagraphHasNoIndentOrSpacing() {
        val l = layout("")
        assertEquals(0, l.start)
        assertEquals(0, l.firstLine)
        assertEquals(0, l.hanging)
        assertEquals(0, l.before)
        assertEquals(0, l.after)
    }

    @Test
    fun positiveIndentsArePreserved() {
        val l = layout("<w:pPr><w:ind w:left=\"720\" w:right=\"360\" w:firstLine=\"360\"/></w:pPr>")
        assertEquals(720, l.start)
        assertEquals(360, l.firstLine)
        assertEquals(0, l.hanging)
        assertPaddingSafe(l)
    }

    @Test
    fun hangingIndentWithoutNumberingBecomesNegativeFirstLine() {
        val l = layout("<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>")
        assertEquals(720, l.start)
        assertEquals(-360, l.firstLine)
        assertEquals(0, l.hanging)
        assertPaddingSafe(l)
    }

    @Test
    fun numberedParagraphKeepsHangingColumnOutOfPadding() {
        val l = layout(
            "<w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>" +
                "<w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>"
        )
        assertEquals(360, l.start)
        assertEquals(0, l.firstLine)
        assertEquals(360, l.hanging)
        assertPaddingSafe(l)
    }

    @Test
    fun numberedParagraphWithoutIndentUsesDefaultHanging() {
        val l = layout("<w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr>")
        assertEquals(0, l.start)
        assertEquals(0, l.firstLine)
        assertEquals(360, l.hanging)
        assertPaddingSafe(l)
    }

    @Test
    fun negativeAndMalformedValuesCannotReachPadding() {
        val l = layout(
            "<w:pPr><w:spacing w:before=\"-240\" w:after=\"-120\"/>" +
                "<w:ind w:left=\"-720\" w:hanging=\"1440\"/></w:pPr>"
        )
        assertEquals(0, l.start)
        assertEquals(-1440, l.firstLine)
        assertEquals(0, l.before)
        assertEquals(0, l.after)
        assertPaddingSafe(l)
    }

    @Test
    fun negativeHangingOnANumberedParagraphIsClamped() {
        val l = layout(
            "<w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr>" +
                "<w:ind w:left=\"240\" w:hanging=\"-360\"/></w:pPr>"
        )
        assertEquals(240, l.start)
        assertEquals(0, l.firstLine)
        assertEquals(0, l.hanging)
        assertPaddingSafe(l)
    }
}
