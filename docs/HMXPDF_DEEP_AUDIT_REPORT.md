# HMX PDF — Deep Source Audit Report (2026-10-29)

## 1. Baseline

| Item | Value |
|---|---|
| Repository | HMXPANEL/HMXPDF (public GitHub) |
| Local path | `/mnt/sdcard/AIProjects/HMXPDF` |
| Local git metadata | **None** — the directory is not a git repository (`git rev-parse` fails). |
| Tree identity | Local file set (496 blobs) is **byte-identical** to upstream `main` @ `912e9977916c4f365147a294e60c3cd95d96960e` (2026-10-24, current tip) — verified by SHA-1 blob comparison. |
| Stale context | The task brief described a "Phase 3" branch (`feature/hmxpdf-diagnostics-admin-updates`), Supabase functions, an `admin/` frontend, commit `7c2fadd`, and privacy/diagnostic onboarding. **None of that exists in the repository or in upstream history** (`7151723c` and `7c2fadd` do not resolve on GitHub). Per the brief's own rule, the real repository takes precedence: this audit covers the actual code. |
| Prior audit | `docs/AUDIT_REPORT.md` (protected, left untouched) audited a now-nonexistent revision; several of its findings have since been fixed upstream and are re-graded below. |

## 2. Scope and coverage

- All 391 Kotlin/Java/XML/Gradle files inventoried; every prior P0/P1 finding re-verified against the current tip.
- Architecture, manifests, CI workflow, signing guard, backup rules, dependency catalog read in full.
- No APK/AAB build, install, or emulator task was run locally. Only `test*`/`:module:test` tasks (JVM unit tests) were permitted; their task graph was dry-run first.

## 3. Findings re-verified against upstream `main` (@912e997)

Already fixed upstream (prior-audit items resolved by commits after the stale audit):

| ID (prior) | Item | Evidence |
|---|---|---|
| AUDIT-001 | Share falls back to original on failed edit export | `editedSourceOrThrow` + `writeEditedCopy` validation (`PdfViewerScreen.kt:189`, VM:1009) |
| AUDIT-002 | Print ignores unsaved edits | Print routes through `buildSharePayload(PDF)` incl. edits (`PdfViewerScreen.kt:2700`) |
| AUDIT-003 | Silent partial exports | Form values/page bounds `check()`ed; failure deletes partial output (VM:1042-1103) |
| AUDIT-004 | Plaintext decrypted cache persists | Cleanup sessions + `DecryptedCacheCleanupTest` exist (`pdf-core/.../PdfSecurityService.kt`) |
| AUDIT-005 | Non-atomic DOCX/XLSX saves | Durable `SaveRecovery` journal + verified replacement (VM:297-363) |
| AUDIT-006 | Scan filter applied to all pages | Per-page filter dispatch + per-page save plan (`ScanDocumentScreen.kt:505-521, 583`) |
| AUDIT-007 | CI runs no tests/lint | Workflow runs `testPlayDebugUnitTest`, `:pdf-core:test`, `:ocr-core:test`, `lintPlayDebug` (`.github/workflows/android.yml:34-46`) |
| AUDIT-008 | Debug-signed "release" APKs | Missing-signing `GradleException` guard + CI `apksigner`/`jarsigner` verification + `release-guard` job |
| AUDIT-010 | Scan decodes unbounded | Bounded decode (`150, 8.0M` pixel cap in `ImageIo.decode`, scan save path) |
| AUDIT-017 | No backup rules | `backup_rules.xml` exists (but see NEW-106) |
| AUDIT-020 | Export leaks source stream | `use { }` everywhere in export path |
| AUDIT-021 | Main-thread display-name query | Moved to `Dispatchers.IO` (`MainActivity.kt:121`) |
| AUDIT-015 | HTML-to-PDF unreachable | **Intentional by design** — privacy release hides the only networked tool; explicit comment (`ToolsScreen.kt:108-109`) |

Open defects found in current source (register with severity):

| New ID | Prior ID | Sev | Summary | Evidence |
|---|---|---|---|---|
| NEW-101 | AUDIT-022 | P2 | Unknown binaries (`.zip`, `.epub`, …) silently became garbage "text PDFs" | `UniversalDocumentConverter.kt:63` (`else -> convertTextToPdf`) |
| NEW-102 | AUDIT-010 | P2 | Image→PDF conversion decoded full-resolution bitmaps (OOM risk); `PdfDocument` leaked on failure | `convertImageToPdf` unbounded `BitmapFactory.decodeStream` |
| NEW-103 | AUDIT-039 | P3 | `writePdf` didn't close `PdfDocument` if `writeTo` failed | `UniversalDocumentConverter.kt:701` |
| NEW-104 | AUDIT-038 | P3 | PDF→Gallery export left an empty MediaStore row when the copy failed | `PdfRasterizer.exportToGallery` |
| NEW-105 | AUDIT-019 | P2 | Rasterizer allocated unbounded ARGB bitmaps at export dpi (large-format pages → OOM), no partial-file cleanup | `PdfRasterizer.rasterize` / `rasterizePageBitmap` |
| NEW-106 | AUDIT-017 | P2 | `android:dataExtractionRules` pointed at a `<full-backup-content>`-rooted XML — invalid schema for API 31+, so the rules could be ignored | Manifest line 38 + `xml/backup_rules.xml` |
| NEW-107 | AUDIT-037 | P2 | Apache POI 3.17 (EOL, CVE class in scratchpad) parses untrusted legacy documents | `app/build.gradle.kts:233-234` |
| NEW-108 | AUDIT-045 | P3 | `jvmToolchain(21)` in pdf-core/ocr-core vs app/CI on 17 — forces a redundant JDK download in every CI run | pdf-core/build.gradle.kts:26, ocr-core:34, CI sets JDK 17 |

Known product gaps (documented, not bugs — unchanged): no true redaction, no digital signatures, no PPTX editing, no XLSX sheet management, PDF/A output absent, English-only OCR fallback, same `applicationId` across distribution channels. The text-editor "cover" fallback (AUDIT-009) remains documented product behavior, not a silent failure.

## 4. Out-of-scope / unchanged

- `docs/AUDIT_REPORT.md` and the historical audit trail are untouched.
- No signing configuration, versioning literals (`versionCode = 6`, F-Droid), retention policies, or release workflows were altered.
- `unitTests.isReturnDefaultValues = true` is kept: it is documented as deliberate for pure-logic JVM tests.

## 5. Limitations

Static verification was performed on-device without a device/emulator; dynamic QA (TalkBack, multi-ABI office engine, large-deck rendering) remains a device-QA gate. See the push-readiness report for exact verification results.
