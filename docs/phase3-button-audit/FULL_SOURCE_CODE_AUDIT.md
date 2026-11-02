# HMXPDF — Full Source-Code Audit (Phase 3 testing branch)

Branch `release/hmxpdf-2.1.1-build` @ `60b8218` ("Reset head"). Read-only audit: **no source code was modified, no commits/pushes made** (the only new files in the work tree are these four report files, uncommitted). CI baseline for this exact commit: run `41328856513` — build/release/release-guard **all success**.

## 1. Architecture (verified by reading, not filenames)

| Module | Role |
|---|---|
| `:app` (ns `com.hmx.pdf`, minSdk 23, flavors play/foss) | The product: PDF tool suite + viewers, DOCX editor, XLSX/PPTX renderers, image editor, Office Engine client. Entry `MainActivity` → `ui/DocsApp.kt` → `ui/navigation/DocsNavGraph.kt`. |
| `:backdrop` | Vendored Kyant liquid-glass Compose library, used by all app chrome. |
| `:pdf-core` (ns `com.kyant.pdfcore`) | Lossless PDF engine on PdfBox-Android (compress/convert/form/merge/split/security/…). 5 test files. |
| `:ocr-core` | Tesseract4Android + ML-Kit OCR service abstraction. No test sources (CI `:ocr-core:test` is a pass-through). |
| `:office_engine` | Dynamic-feature (bundle-only) holding pinned LibreOfficeKit binaries; no Kotlin. |

Word-editor engine (`app/src/main/java/com/hmx/pdf/docx/`) is pure Kotlin: `DocxReader` → `DocState` of immutable `DocBlock`s (edited paragraphs drop a byte-preserving `origin` so only touched content is re-serialized); edits mutate state through **kernels** (`DocxEdits`, `DocxProps`, `DocxPageLayout`, `DocxComments`, `DocxRevisions`, `DocxObjects`); `DocxEditorViewModel` owns undo/redo (whole-doc snapshots + per-key 1.5 s coalescing); **`DocxWriter.write(pkg, state, source=mirror, dest, extras)` is the sole DOCX write path** (streams the pristine cache copy, replaces only touched zip entries). Save is journaled (`SaveRecovery`): backup → hash-verified read-back → restore-on-failure.

Phase history (from committed reports): Phase 1 find/replace + comments (+ P0-3 pre-existing cell editing), Phase 1.1 edit-mode centralization + add-comment fallback, Phase 2 Track Changes (`w:ins`/`w:del` modeling + accept/reject), Phase 3 pages/sections/breaks/header-footer.

## 2. Major findings outside Page Setup

| # | Finding | Root cause (file:line) | Sev / Confidence |
|---|---|---|---|
| A1 | **Find→Replace can crash the app with a stale match.** Replace-Current forwards a match collected up to 160 ms earlier straight into the kernel. | `DocxEdits.replaceRange` (`docx/DocxEdits.kt:107-118`) revalidates nothing: `p.text.substring(end)` / `p.fmts[start]` throw `StringIndexOutOfBoundsException` when the text changed (match shrank/past end; empty object-guard at `start > top` bypasses the trap). Debounce source `DocxEditorScreen.kt:1242`, `:513-528`. | **P2 / Strong Evidence** (window needs a fast change; the missing bound is objective) |
| A2 | **Revisions inside content-control previews list in the Revisions panel but Accept/Reject silently no-ops.** Preview (`sdt`) paragraphs are read-only (`DocRaw`-style) yet can carry `Rev` chars; `pendingRevisions()` walks them (`includePreview`) while `mapParas`→`findPara` skips them. | `docx/DocxModel.kt:128-152` vs `ui/viewmodel/DocxEditorViewModel.kt:253-263,368-377`. | P3 / Confirmed (code path) |
| A3 | **Undo right after adding a comment leaves an orphan `w:comment` element in the saved file.** `addComment` registers in `cm.added` first; `undo()` restores the body (anchor gone) but nothing reconciles additions; the writer appends every `added` entry unconditionally. | `ui/viewmodel/DocxEditorViewModel.kt:407-419`; `docx/DocxComments.kt:140-143` (verified: deletion side keeps stale entries only when still referenced, addition side has no equivalent filter). Word tolerates; the file gains a phantom comment. | P3 / Confirmed |
| A4 | **No autosave of edits at all.** The only quiet persistence is reading-progress; `save()` runs only on button/dialog. Process death loses every edit with no prompt (Save Recovery covers only interrupted *writes*). | Whole `DocxEditorViewModel`; acknowledged trade-off already hinted in Phase 1.1 report ("edits never saved were lost with the process"). | P2 (product decision) / Confirmed by inspection |
| A5 | `save()` guard returns **without invoking `onDone`** (`if (meta.saving \|\| !editable) return`). Today's callers gate on `dirty`/`saving`, so it is latent — but a save-in-flight + dialog-Save overlaps exit without callback. | `ui/viewmodel/DocxEditorViewModel.kt:673`. | P3 / Confirmed (latent) |
| A6 | **LiquidToggle flips its thumb before the VM decides**; a guarded reject (e.g. `setTracking`, orientation with no change, edit-mode re-entry) never re-emits `selected`, so the thumb can stay on the rejected side until recomposition. | `ui/screen/LiquidToggle.kt:82-117` + guards (`DocxEditorViewModel.kt:129-133`). Reachability of the reject state needs a device check. | P3 / Strong Evidence |
| A7 | `SelectionContainer` wraps the entire viewport including editable `BasicTextField`s — long-press/selection-handle conflicts with fields are possible on some Android versions. | `DocxEditorScreen.kt:1011-1045`, fields at `:2003-2013`. | P3 / Needs device verification |
| A8 | **No structural table operations** (insert/delete row, column) exist anywhere in the editor — only cell text editing. Absence of feature, not a dead wire (exhaustive grep). | grep across `app/src/main` for `insertRow|addRow|deleteRow|insertCol…` — zero callers. | P2 (product gap if promised) |
| A9 | "Open as PDF" gives no progress feedback and stays tappable during conversion (double-tap → double conversion/navigation); Print shows a toast but the PDF path does not. | `DocxEditorScreen.kt:798-805` vs `:807`; `DocxEditorViewModel.exportToPdf:800-824`. | P3 / Strong Evidence |
| A10 | Comments headline/toolbar buttons expose no count badge. (Panel content works; badge never existed.) | `DocxEditorScreen.kt:889-891,1101`. | P3 (absence) |
| A11 | Phase-2 documented limits stand: `w:moveFrom`/`w:moveTo` not modeled as revisions; paragraph-mark (`w:pPr/w:rPr/w:ins`) revisions not resolvable as rows. | `docs/phase2/PHASE2_TEST_REPORT.md:65-69`. | P3 / documented |
| A12 | Vendored debt: backdrop `LayerBackdrop` outer-transform bug (TODO), cropper pan TODO; **lint baseline holds 410 recorded issues** (incl. 46 `NewApi`, 22 `LocalContextGetResourceValueCall`) — new lint errors fail CI, old debt rests. | `backdrop/…/LayerBackdrop.kt:67`; `cropper/state/DynamicCropState.kt:319`; `app/lint-baseline.xml`. | P3 |
| A13 | Test-inventory skew: 38 JVM test files in `:app` (261 tests, 44 pre-existing skips per Phase-3 report), 5 in `:pdf-core`, **none in `:ocr-core`, no instrumented (`androidTest`) set at all** — beyond-page-setup UI interactions rely on device tiers. | source sets + CI `android.yml`. | P3 |

## 3. Cross-module risks for the upcoming fix wave

- **The Writer is the only write path — kernel fixes must stay inside `docx/`** (mirrors a `git diff`-safe branch contract).
- Settings/HF changes live *beside* `DocState` (`wantSettings`, `hfChanged`) and are invisible to `dirty` — see Page-Setup report §R2; any autosave design must include them.
- Chrome page geometry is snapshot-in-a-plain-field (`DocxPackage.page`, mutated only in `exportToPdf`, VM:802) — any feature counting on live reflow must go through an observable sync (the rejected experiment's approach was this same fix; a minimal version without its UI is what the repair wave should consider — see Page-Setup report).
- Recents/reading-progress (`RecentFilesManager`) is the only persistence on process death; Save Recovery journal only wraps explicit saves.

## 4. Verified-fine areas (do not re-audit)

Finish-save journaling (`writeWithRecovery`, VM:709-776) · BackHandler priority chain and dirty-exit dialog (Screen:820-886, 1760-1771) · Find-bar hit rendering (`DocxLook.annotate` through `DocTransformation`, Screen:1885-1892) · Undo/redo + `restoreEpoch` field re-read (VM:196-234, Screen:596-604) · Track-change accept/reject semantics (`DocxRevisions.kt:54-65`, `w:ins/w:del` writer at `DocxWriter.kt:269-284`) · Header/footer **part** plumbing incl. rels/content-types (`DocxWriter.kt:105-133`) · Office-Engine install state machine & error surfacing (`OfficeEngineSettingsSection.kt:149-271`) · Scan output journaling (`ScanDocumentScreen.kt:611-666`) · ImageEditor compare-button intentional no-op (`ImageEditorScreen.kt:241-254`).
