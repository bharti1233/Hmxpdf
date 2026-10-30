package com.kyant.pdfcore.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.kyant.pdfcore.internal.PdfBox
import com.kyant.pdfcore.model.PdfDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Conversions between PDF and other formats.
 */
interface PdfConverter {

    /**
     * Build a PDF from a list of image [imageUris], one image per page.
     *
     * @param fitToA4 when true each page is A4 and the image is centred/scaled to fit;
     *                when false each page matches its image's pixel dimensions.
     * @param jpegQuality 0..100 quality for the embedded JPEGs.
     * @param rotationQuarterTurns clockwise 90-degree turns per image, parallel to [imageUris].
     */
    fun imagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        outputUri: Uri,
        fitToA4: Boolean = true,
        jpegQuality: Int = 85,
        rotationQuarterTurns: List<Int> = emptyList()
    ): PdfDocument

    /** Extract all text from [source] (empty string for scanned/imaged PDFs without a text layer). */
    fun extractText(context: Context, source: PdfDocument): String
}

class PdfConverterImpl : PdfConverter {

    private data class DecodedImage(
        val bitmap: Bitmap,
        val pageWidth: Int,
        val pageHeight: Int
    )

    override fun imagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        outputUri: Uri,
        fitToA4: Boolean,
        jpegQuality: Int,
        rotationQuarterTurns: List<Int>
    ): PdfDocument {
        PdfBox.ensureInitialized(context)
        if (imageUris.isEmpty()) throw IllegalArgumentException("No images to convert")

        val doc = PDDocument()
        var pages = 0
        try {
            for ((index, uri) in imageUris.withIndex()) {
                val decoded = decodeBitmap(
                    context,
                    uri,
                    rotationQuarterTurns.getOrElse(index) { 0 }
                ) ?: continue
                val bitmap = decoded.bitmap
                try {
                    val pdImage = JPEGFactory.createFromImage(doc, bitmap, jpegQuality / 100f)
                    val page = if (fitToA4) {
                        PDPage(PDRectangle.A4)
                    } else {
                        PDPage(PDRectangle(decoded.pageWidth.toFloat(), decoded.pageHeight.toFloat()))
                    }
                    doc.addPage(page)

                    PDPageContentStream(doc, page).use { content ->
                        val box = page.mediaBox
                        if (fitToA4) {
                            val scale = minOf(box.width / bitmap.width, box.height / bitmap.height)
                            val w = bitmap.width * scale
                            val h = bitmap.height * scale
                            val x = (box.width - w) / 2f
                            val y = (box.height - h) / 2f
                            content.drawImage(pdImage, x, y, w, h)
                        } else {
                            content.drawImage(pdImage, 0f, 0f, box.width, box.height)
                        }
                    }
                    pages++
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            if (pages == 0) throw IllegalStateException("No readable images")
            val out = context.contentResolver.openOutputStream(outputUri)
                ?: throw IllegalStateException("Cannot write PDF output")
            out.use { doc.save(it) }
        } finally {
            doc.close()
        }
        return PdfDocument(uri = outputUri, name = "Converted.pdf", pageCount = pages)
    }

    override fun extractText(context: Context, source: PdfDocument): String {
        PdfBox.ensureInitialized(context)
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalArgumentException("Cannot open source")
        return input.use { stream ->
            PDDocument.load(stream).use { doc ->
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        }
    }

    private fun decodeBitmap(context: Context, uri: Uri, rotationQuarterTurns: Int): DecodedImage? {
        return try {
            val orientation = exifOrientation(context, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // ponytail: one-image-at-a-time pixel budget. Raise only if PDFs need more resolution.
            val maxPixels = (Runtime.getRuntime().maxMemory() / (4L * 5))
                .coerceAtLeast(1_000_000L)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val source = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val turns = normalizeQuarterTurns(rotationQuarterTurns)
            val (pageWidth, pageHeight) = pageSizeAfterTransform(
                bounds.outWidth,
                bounds.outHeight,
                orientation,
                turns
            )

            val matrix = Matrix()
            applyExifOrientation(matrix, orientation)
            if (turns != 0) matrix.postRotate(turns * 90f)
            val transformed = if (matrix.isIdentity) {
                source
            } else {
                try {
                    Bitmap.createBitmap(
                        source, 0, 0, source.width, source.height, matrix, true
                    ).also { if (it !== source) source.recycle() }
                } catch (t: Throwable) {
                    source.recycle()
                    throw t
                }
            }
            DecodedImage(transformed, pageWidth, pageHeight)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun exifOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(matrix: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
    }
}

internal fun normalizeQuarterTurns(turns: Int): Int = ((turns % 4) + 4) % 4

/** EXIF orientation and the user's quarter turns each affect page dimensions exactly once. */
internal fun pageSizeAfterTransform(
    width: Int,
    height: Int,
    exifOrientation: Int,
    rotationQuarterTurns: Int
): Pair<Int, Int> {
    val exifSwaps = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
    val orientedWidth = if (exifSwaps) height else width
    val orientedHeight = if (exifSwaps) width else height
    return if (normalizeQuarterTurns(rotationQuarterTurns) % 2 == 1) {
        orientedHeight to orientedWidth
    } else {
        orientedWidth to orientedHeight
    }
}

/**
 * Power-of-two decode sample for [w] x [h] images so a huge source does not allocate
 * two full-resolution ARGB bitmaps while the rotation copy is created.
 */
internal fun sampleSize(w: Int, h: Int, maxPixels: Long): Int {
    if (w <= 0 || h <= 0) return 1
    var sample = 1
    while (sample < (1 shl 20) &&
        (w.toLong() / sample) * (h.toLong() / sample) > maxPixels
    ) {
        sample *= 2
    }
    return sample
}
