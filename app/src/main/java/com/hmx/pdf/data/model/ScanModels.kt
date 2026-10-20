package com.hmx.pdf.data.model

import android.net.Uri

/**
 * Represents a single scanned page
 */
data class ScanPage(
    val id: String,
    val uri: Uri,
    val timestamp: Long = System.currentTimeMillis(),
    val filter: ScanFilter = ScanFilter.AUTO
)

/**
 * Scan filter types for image enhancement
 */
enum class ScanFilter {
    AUTO,           // Automatic enhancement
    BLACK_WHITE,    // High contrast B&W
    GRAYSCALE,      // Grayscale
    COLOR,          // Color with white balance
    ORIGINAL        // No processing
}

/**
 * Represents a complete scanned document with multiple pages
 */
data class ScannedDocument(
    val id: String,
    val name: String,
    val pages: List<ScanPage>,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val pdfUri: Uri? = null
)

/** The page/filter pairing handed to the PDF writer; kept pure so reordering cannot mix filters. */
internal fun <T> List<T>.toSavePlan(filterOf: (T) -> ScanFilter): List<Pair<T, ScanFilter>> =
    map { it to filterOf(it) }

internal fun List<ScanPage>.toSavePlan(): List<Pair<Uri, ScanFilter>> = toSavePlan { it.filter }
