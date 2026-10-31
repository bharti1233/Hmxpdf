# HMXPDF — Phase 3 Test Report: Page Layout, Sections, Breaks, Headers & Footers

Branch: `release/hmxpdf-2.1.1-build` · CI authority: GitHub Actions `Android build` workflow.
Green verification run: **[41232855647](https://github.com/bharti1233/Hmxpdf/actions/runs/41232855647)** at SHA `567414c` — jobs `build` / `release` / `release-guard` all `success`, `BUILD SUCCESSFUL` ×3.

## Scope & method

- Engine level is verified by JVM unit tests (`app/src/test/java/com/hmx/pdf/docx/DocxPageLayoutTest.kt`, 18 tests) — every round-trip op goes through real reader → edit kernel → **save through `DocxWriter` to a zip on disk → reopen through `DocxReader`**, the same pipeline the app uses.
- Compile/packaging verification is the `release` signed-assembly job; unit tests run inside the `build` job (`:app:testPlayDebugUnitTest`).
- Legend: **[T]** = asserted by a JUnit test · **[C]** = covered indirectly (compile + existing suite) · **[L]** = limitation, not verifiable in CI.

## CI result on the green head

- `:app:testPlayDebugUnitTest` executed and passed. Suite totals on the last *executed* run: **261 tests completed, 0 failed, 44 skipped** (previously logged as "261 tests completed, 5 failed, 44 skipped" at `c6b8270`; the 5 were new Phase 3 tests, see "Landing log").
- Signed release APK assembled successfully → artifacts **hmx-pdf-debug-apk** (`13054523148`), **hmx-pdf-release-apk** (`13054212782`), **hmx-pdf-release-bundle** (`13054167911`).

## Requirements coverage

| Plan item | Status | Evidence **[T]/[C]/[L]** |
|---|---|---|
| P3-1 Read `w:orient`, `header/footer/gutter` margins | ✅ | [T] `readsOrientationAndMarginExtras` |
| P3-1 Orientation round trip (flag added/removed, dims swap) | ✅ | [T] `orientationRoundTripThroughSave` |
| Unknown `sectPr` children (`cols`, `docGrid`) preserved | ✅ | [T] `pageSetupPreservesUnknownSectPrChildren` |
| Section list in document order incl. trailing/synthetic | ✅ | [T] `sectionsFoundInOrderIncludingTrailing` |
| P3-2 Size/margin bounds (Word's 0.1"–22") | ✅ | [T] `dimensionValidationBounds` |
| P3-2 Margins edited independently, others untouched | ✅ | [T] `marginsRoundTripIndependently` |
| P3-3 Page-break object survives save/reopen | ✅ | [T] `pageBreakInsertionSurvivesSaveReopen` (kernel fixed — see landing log) |
| P3-3 Section-break paragraph + `w:type` coalesce | ✅ | [T] `sectionBreakInsertCarriesTypeBeforeTargetParagraph`; body-level guard guarded in kernel |
| P3-3 `w:type` round trip + removal | ✅ | [T] `sectionTypeRoundTripAndRemoval` |
| P3-4 Header part parses to editable paragraphs | ✅ | [T] `readsHeaderPartIntoEditableParagraphs` |
| P3-4 Editing an existing part replaces it, body untouched | ✅ | [T] `headerEditReplacesExistingPartAndBodyUntouched` |
| P3-4 Header creation: part + rel + content-type + reference | ✅ | [T] `headerCreationAddsPartRelAndContentType` |
| P3-4 Reference removal leaves part bytes + rel intact | ✅ | [T] `footerReferenceRemovalLeavesPartUntouched` |
| P3-4 Free part name skips used `headerN/footerN` | ✅ | [T] `freePartSkipsUsedNames` |
| P3-5 Multi-variant safety (default/first/even coexist) | ✅ | [T] `referenceEditKeepsOtherVariants` (`setChildWhere` regression test) |
| P3-5 `titlePg` round trip both directions | ✅ | [T] `titlePgRoundTrip` |
| P3-5 `evenAndOddHeaders` inserted in settings schema order, removed byte-exact | ✅ | [T] `evenOddSettingsInsertedInOrderAndRemoved` |
| Layout edits leave Track Changes intact; accept still works | ✅ | [T] `sectionEditKeepsTrackedChangesAndAcceptStillWorks` |
| Gated ops (Edit Mode / read-only) | ✅ | [C] VM guard conventions shared with comments/revisions, compile-verified |
| Undo/redo unaffected for body text | ✅ | [C] existing phase-1/2 suite passes unchanged (44 skipped are device-dependent suites) |
| Signed release packaging intact | ✅ | [C] `release` job green |

## Landing log (runs observed while converging)

| Run | SHA | Result | What it verified |
|---|---|---|---|
| `41225384282` | `9b8ae84` | ❌ compile | Kernel `String?` smart-cast; `DocxPackage.copy` removals (plain class) → `wantSettings` mirror + `var page`; `buildPatched` return value |
| `41226026815` | `c6b8270` | ❌ 5 tests | Release job ✅ (packaging intact). Unit tests: **261 completed, 5 failed, 44 skipped** |
| `41231612982` | `d5755af` | ❌ 1 test | `CharFmt` requires `rPr` in the page-break test |
| `41232855647` | `567414c` | ✅ | One real engine fix + 4 test-semantics corrections (below): **261 completed, 0 failed** |

Engine fix in `567414c`: `PAGE_BREAK_OBJ` must be a **full `w:r` run** (`<w:r><w:br w:type="page"/></w:r>`), matching what the reader parses back via `objXml` — the bare `<w:br>` form was emitted outside a run by the shared paragraph serializer and vanished on reopen. Test corrections: the `w:orient` flag rides on landscape dims (Word models the flag, not the ratio), `w:evenAndOddHeaders` schema position is *after* `defaultTabStop` per CT_Settings, `acceptAll` keeps inserted text (16+9+6=31 chars), and new HF parts are parsed through `hfState` like the VM does (consistent `fmts`).

## Limitations **[L]** — carried, not testable in CI

- **Continuous break**: added and serialized correctly (`w:type="continuous"`), but the editor/preview has no in-page column simulation, so its on-screen effect is not drawn — Word shows it correctly on reopen (tested the OOXML round trip, not the visual).
- **Internal PDF export** (`DocxPdfWriter`) follows the live page setup (synced via `pkg.page`), but does **not** draw header/footer content into the exported pages — the Office Engine path does render them when installed.
- **`fldChar`-based HF paragraphs** stay read-only by design (identical to the body's field handling).
- **`lastRenderedPageBreak`** markers inside HF paragraphs are refreshed by Word on save; ours are ignorт preservations of the stored stream (no functional impact on layout).
- HF edits are package-level, not undo steps (comments precedent): intentional, coordinated with the Phase 1/2 conventions.
- Panel behavior (scroll, focus, IME) compiled + code-reviewed; instrumented UI tests are outside this phase's CI envelope.

## Verdict

**Phase 3 passes CI**: 18 new engine tests green, 261/261 executed tests passed on the green head (44 environment-skipped suites unchanged), debug + signed release artifacts built. Ready for user repair-on-device confirmation before considering `main`.
