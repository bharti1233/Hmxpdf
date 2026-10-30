package com.kyant.pdfcore.raster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Renders PDF pages to raster image files (JPEG / PNG / WebP) fully on-device using
 * the platform [PdfRenderer]. Output is written to the app cache and exposed through
 * a [FileProvider] content Uri so it can be shared or opened.
 */
object PdfRasterizer {

    enum class ImageFormat(val extension: String, val mime: String) {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp")
    }

    data class RasterPage(val pageIndex: Int, val uri: Uri, val file: File)

    /**
     * ARGB_8888 pixel budget for one rendered page (~64 MiB). Pages that alone would exceed it
     * (large-format sheets at high dpi) are rendered at a lower effective dpi instead of
     * allocating an unchecked bitmap and dying with OutOfMemoryError.
     */
    internal const val MAX_PAGE_PIXELS = 16_000_000L

    /** Target pixel size for [pageW]x[pageH] points at [dpi], clamped to at most [maxPixels]. */
    internal fun boundedPageSize(pageW: Int, pageH: Int, dpi: Int, maxPixels: Long = MAX_PAGE_PIXELS): IntArray {
        val scale = dpi / 72f
        val w = (pageW * scale).toInt().coerceAtLeast(1)
        val h = (pageH * scale).toInt().coerceAtLeast(1)
        val pixels = w.toLong() * h.toLong()
        if (pixels <= maxPixels) return intArrayOf(w, h)
        val shrink = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        return intArrayOf(
            (w * shrink).toInt().coerceAtLeast(1),
            (h * shrink).toInt().coerceAtLeast(1)
        )
    }

    /**
     * Render every page of [source] to an image.
     *
     * @param dpi target render density; 150 is a good screen/print compromise.
     * @param quality 0..100 (ignored for lossless PNG).
     * @param onProgress invoked as pages complete: (done, total).
     */
    fun rasterize(
        context: Context,
        source: Uri,
        format: ImageFormat,
        dpi: Int = 150,
        quality: Int = 90,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<RasterPage> {
        val outDir = File(context.cacheDir, "pdf_images").apply { mkdirs() }
        // A fresh sub-folder per run keeps exports from previous runs from piling up in shares.
        val runDir = File(outDir, "run_${System.currentTimeMillis()}").apply { mkdirs() }
        val results = mutableListOf<RasterPage>()

        val pfd = context.contentResolver.openFileDescriptor(source, "r")
            ?: throw IllegalStateException("Cannot open PDF")

        try {
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val total = renderer.pageCount
                    for (i in 0 until total) {
                        renderer.openPage(i).use { page ->
                            val (w, h) = boundedPageSize(page.width, page.height, dpi).let { it[0] to it[1] }
                            var bitmap: Bitmap? = null
                            try {
                                val pageBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bitmap = pageBitmap
                                // Paint a white backing so transparent PDF areas don't render black in JPEG.
                                pageBitmap.eraseColor(Color.WHITE)
                                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                val file = File(runDir, "page_${i + 1}.${format.extension}")
                                FileOutputStream(file).use { out ->
                                    val compressFormat = when (format) {
                                        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                                        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                                        ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
                                    }
                                    pageBitmap.compress(compressFormat, quality.coerceIn(0, 100), out)
                                }
                                pageBitmap.recycle()
                                bitmap = null

                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    file
                                )
                                results.add(RasterPage(i, uri, file))
                            } finally {
                                // Recycle even when rendering/compression/FileProvider threw.
                                bitmap?.recycle()
                            }
                        }
                        onProgress?.invoke(i + 1, total)
                    }
                }
            }
        } catch (t: Throwable) {
            // Don't leave broken/partial run folders behind after a render failure.
            runCatching { runDir.deleteRecursively() }
            throw t
        }
        return results
    }

    /**
     * Renders a single page straight to an in-memory [Bitmap] (no file/FileProvider round-trip).
     * Used to feed on-device OCR, which only needs the pixels, not a shareable image.
     *
     * @param dpi target render density; higher improves OCR accuracy on small text at some cost
     *   to speed — 200 is a reasonable OCR-quality default (vs. 150 for the export path above).
     */
    fun rasterizePageBitmap(context: Context, source: Uri, pageIndex: Int, dpi: Int = 200): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(source, "r") ?: return null
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return@use null
                renderer.openPage(pageIndex).use { page ->
                    val (w, h) = boundedPageSize(page.width, page.height, dpi).let { it[0] to it[1] }
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    /** Persist all rendered pages to the shared Pictures collection via MediaStore. */
    fun exportToGallery(context: Context, pages: List<RasterPage>, format: ImageFormat, albumName: String = "ClearPDF"): Int {
        var saved = 0
        val resolver = context.contentResolver
        pages.forEach { page ->
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, page.file.name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, format.mime)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "${android.os.Environment.DIRECTORY_PICTURES}/$albumName"
                    )
                }
            }
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val item = resolver.insert(collection, values) ?: return@forEach
            var copied = false
            runCatching {
                resolver.openOutputStream(item)?.use { out ->
                    page.file.inputStream().use { it.copyTo(out) }
                }
                copied = true
                saved++
            }
            if (!copied) {
                // A failed copy must not leave an empty gallery entry behind.
                runCatching { resolver.delete(item, null, null) }
            }
        }
        return saved
    }
}
