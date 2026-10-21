package com.hmx.pdf.utils

/**
 * The original document family, derived from a file name's extension (and, when the URI has no
 * useful suffix, the sender's MIME type). The app converts non-PDF documents to a PDF for viewing
 * (which loses the original type), but the original display name is preserved (e.g. "budget.xlsx")
 * — so the extension is still the reliable way to recover the kind and show the right editor tools
 * / route to the right viewer.
 */
enum class DocKind { Pdf, Word, Excel, Ppt, Image, Other }

/**
 * Classify a document by its name/extension, falling back to its MIME type when the provider's
 * URI has no useful suffix. The MIME fallback matters for external opens: many file managers hand
 * over opaque content URIs such as `content://.../document/1234`, so the sender's MIME type is the
 * only way to route a DOCX/XLSX/image correctly without querying the provider before first frame.
 */
fun docKindOf(fileName: String?, mimeType: String? = null): DocKind {
    val n = (fileName ?: "").lowercase().trim()
    val byName = when {
        n.endsWith(".pdf") -> DocKind.Pdf
        n.endsWith(".docx") || n.endsWith(".doc") || n.endsWith(".odt") || n.endsWith(".rtf") -> DocKind.Word
        n.endsWith(".xlsx") || n.endsWith(".xls") -> DocKind.Excel
        n.endsWith(".pptx") || n.endsWith(".ppt") -> DocKind.Ppt
        n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".heic") -> DocKind.Image
        else -> DocKind.Other
    }
    if (byName != DocKind.Other) return byName
    return when {
        mimeType == null -> DocKind.Other
        mimeType == "application/pdf" -> DocKind.Pdf
        mimeType.contains("word", ignoreCase = true) ||
            mimeType.contains("opendocument.text", ignoreCase = true) ||
            mimeType.startsWith("text/", ignoreCase = true) -> DocKind.Word
        mimeType.contains("excel", ignoreCase = true) ||
            mimeType.contains("spreadsheet", ignoreCase = true) -> DocKind.Excel
        mimeType.contains("presentation", ignoreCase = true) -> DocKind.Ppt
        mimeType.startsWith("image/", ignoreCase = true) -> DocKind.Image
        else -> DocKind.Other
    }
}

/** Whether this is the editable Word format, which uses the DOCX editor instead of the PDF path. */
fun isDocxDocument(fileName: String?, mimeType: String?): Boolean =
    mimeType?.trim()?.equals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ignoreCase = true
    ) == true || (fileName ?: "").lowercase().trim().endsWith(".docx")
