package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Test

/** A malformed numeric character reference must not throw from Composition. */
class DocxXmlTest {

    @Test
    fun invalidNumericEntitiesStayLiteral() {
        assertEquals("&#x110000;", XmlScan.unescape("&#x110000;"))
        assertEquals("&#-1;", XmlScan.unescape("&#-1;"))
    }

    @Test
    fun validNumericEntitiesDecode() {
        assertEquals("A", XmlScan.unescape("&#65;"))
        assertEquals("\uD83D\uDE00", XmlScan.unescape("&#x1F600;"))
    }
}
