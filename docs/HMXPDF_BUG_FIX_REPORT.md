# HMX PDF — Bug Fix Report (2026-10-29)

All fixes are minimal, root-cause-level changes to files verified against upstream `main` @ `912e997`. Nothing was rebuilt into an APK locally; verification gates are listed in the push-readiness report.

## FIX-1 — Unknown file types no longer convert to garbage text PDFs (NEW-101 / P2)

**Files:** `app/src/main/java/com/hmx/pdf/utils/UniversalDocumentConverter.kt`, `app/src/main/java/com/hmx/pdf/ui/viewmodel/PdfViewerViewModel.kt`

- Extracted the decision into a pure, exhaustive `classifySource(name, mime)` returning a nullable `SourceKind`; `convertToPdf` dispatches on it.
- An unmatched type now throws a new typed `UnsupportedDocumentException` instead of streaming raw binary bytes into the text renderer.
- The viewer open path catches it and shows the existing, fully-localized message `file_preview_unsupported` ("This file type cannot be previewed yet.") instead of the generic open failure.
- DOCX/XLSX export-to-PDF paths already wrap conversion in `runCatching`; they now surface a clean null instead of a nonsense PDF.

**Test:** `app/src/test/java/com/hmx/pdf/utils/UniversalDocumentConverterClassifyTest.kt` — 4 tests covering every supported kind, explicit rejection of `.zip`/`.epub`/octet-stream, and message content.

## FIX-2 — Image→PDF uses bounded decode + resource-safe lifecycle (NEW-102 / P2)

**File:** `app/src/main/java/com/hmx/pdf/utils/UniversalDocumentConverter.kt`

- Now uses the same `ImageIo.decode(context, uri, maxDim = 3508, maxPixels = 8_000_000L)` budget the scan flow uses (also applies EXIF orientation).
- Bitmap recycle and `PdfDocument` close moved into `finally`, so a page/draw/write failure cannot leak native resources or leave an undrawn bitmap referenced.

## FIX-3 — `writePdf` closes the document on failure (NEW-103 / P3)

**File:** `app/src/main/java/com/hmx/pdf/utils/UniversalDocumentConverter.kt`

`pdfDoc.use { }` guarantees close when `writeTo` (or the folder/file preparation) throws.

## FIX-4 — Failed gallery export no longer leaves an empty MediaStore row (NEW-104 / P3)

**File:** `pdf-core/src/main/java/com/kyant/pdfcore/raster/PdfRasterizer.kt`

On copy failure the inserted row is deleted and not counted as saved.

## FIX-5 — Rasterizer pixel budget + OOM resilience (NEW-105 / P2)

**File:** `pdf-core/src/main/java/com/kyant/pdfcore/raster/PdfRasterizer.kt`

- New pure `boundedPageSize(pageW, pageH, dpi, maxPixels = MAX_PAGE_PIXELS)` clamps any page to a 16 MP (~64 MiB ARGB) allocation by lowering the effective dpi proportionally instead of OOM-crashing; used by both `rasterize` and `rasterizePageBitmap` (OCR path).
- Per-page bitmaps are recycled via `finally`; a failed run deletes its partial `run_*` folder and propagates the error to the caller's existing failure handling.

**Test:** `pdf-core/src/test/java/com/kyant/pdfcore/raster/PdfRasterizerPageSizeTest.kt` — 4 tests (normal A4 sizes unchanged, proportional scale-down within budget, untouched when under budget, degenerate pages stay positive).

## FIX-6 — Valid `dataExtractionRules` schema (NEW-106 / P2)

**Files:** app manifest, `app/src/main/res/xml/data_extraction_rules.xml` (new)

API 31+ requires a `<data-extraction-rules>` root; the manifest had pointed that attribute at the legacy `<full-backup-content>` file. A proper new rules file mirrors the same privacy exclusions (decrypted-PDF cache, share temp, recovery journals, converted copies) for both `cloud-backup` and `device-transfer`; `backup_rules.xml` remains the `fullBackupContent` source. Manifest/resource validity is validated by CI lint.

## FIX-7 — Apache POI 3.17 → 5.4.1 (NEW-107 / P2)

**Files:** `app/build.gradle.kts`, `THIRD_PARTY_NOTICES.md`

EOL POI parsing untrusted legacy `.doc/.xls/.ppt` replaced with 5.4.1 (documents updated to match). The used API surface (`WordExtractor`, `HSSFWorkbook`, `HSLFSlideShow`, `TextShape<*,*>`) is unchanged; no ProGuard keep rules exist for POI and none are added speculatively. Compile is validated on CI (see readiness report); runtime legacy-document conversion should be spot-checked on device before release.

## FIX-8 — Unified jvmToolchain (NEW-108 / P3)

**Files:** `pdf-core/build.gradle.kts`, `ocr-core/build.gradle.kts`

Both modules now use the same `jvmToolchain(17)` as the app and CI, removing a per-run JDK-21 provisioning.

## Regression tests added

1. `UniversalDocumentConverterClassifyTest` — unsupported-type rejection.
2. `PdfRasterizerPageSizeTest` — pixel-budget math.

Both are JVM-only, dependency-free (`junit:junit:4.13.2` already present), exercise no device code, and do not use user data.
