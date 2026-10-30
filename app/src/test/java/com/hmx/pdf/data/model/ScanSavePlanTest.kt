package com.hmx.pdf.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSavePlanTest {

    @Test
    fun mixedFiltersStayPairedWithTheirPageInOrder() {
        val pages = listOf("page-1", "page-2", "page-3")
        val filters = mapOf(
            "page-1" to ScanFilter.AUTO,
            "page-2" to ScanFilter.BLACK_WHITE,
            "page-3" to ScanFilter.ORIGINAL
        )

        val plan = pages.toSavePlan { filters.getValue(it) }

        assertEquals(
            listOf(
                "page-1" to ScanFilter.AUTO,
                "page-2" to ScanFilter.BLACK_WHITE,
                "page-3" to ScanFilter.ORIGINAL
            ),
            plan
        )
    }
}
