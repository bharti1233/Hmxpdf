# HMXPDF — Audit Test Results

Branch `release/hmxpdf-2.1.1-build` @ `60b8218` ("Reset head"). Read-only audit: **no application source modified; no commits/pushes** — the only new work-tree files are the four reports in `docs/phase3-button-audit/`.

> Rule honored throughout: **builds/integration used CI only** (no local APK/AAB), **results from skips were never counted as passes**, and **nothing below claims device behavior that was not executed on a device**.

## 1. Environments and tooling used for this audit

| What | How | Result |
|------|-----|--------|
| Static execution-path tracing | Direct reads of UI/ViewModel/kernel/writer/reader + codebase-wide greps for `livePage`, `hfCreate`, `insertSectionBreak`, `dirty`, `freeRelId`, dead-handler patterns | Primary evidence for every register entry |
| Spec ground truth for `w:type` | Microsoft OpenXML `SectionType` reference (fetched) + ECMA-376/ISO 29500 §17.6.22 semantics | Basis of PS-01 (Strong Evidence) |
| Breadth sweeps | Two background audit agents (architecture map; non-Page-Setup interaction sweep), findings re-verified by direct reads before acceptance | Findings A1–A13 et al. in `FULL_SOURCE_CODE_AUDIT.md` |
| Local builds/tests | **None** (forbidden by audit constraints) | — |

## 2. CI baseline for the audited commit

Workflow run **`41328856513`** (triggered on `60b8218`), queried via API:

| Job | Status | Conclusion |
|-----|--------|------------|
| `build` | completed | **success** |
| `release` | completed | **success** |
| `release-guard` | completed | **success** |

CI compiles all modules and runs the JVM test suites (`.github/workflows/android.yml`; `:ocr-core:test` is a no-op pass-through — that module has no test sources). Lint runs with `app/lint-baseline.xml` (410 recorded, pre-existing issues); **new** lint errors would have failed CI — failure here would have surfaced in `build`.

## 3. Test inventory (from sources + committed phase reports; not re-executed locally)

- `:app` JVM tests: 38 files, ~**261 tests** — committed Phase-3 report (`docs/phase3/PHASE3_TEST_REPORT.md`) documents ≈ 44 skips (conditional `assume*`/device-gated/installed-app-dependent).
- `@Ignore` annotations in `app/src/test`: **22** (long-lived disables — each should be revisited in a future cleanup; not evidence of green here).
- Page Setup-relevant suites: `docx/DocxPageLayoutTest.kt` (18 @Test incl. `sectionBreakInsertCarriesTypeBeforeTargetParagraph`, which **locks the current R1-wrong type placement**), `DocxRoundTripTest.kt`, `DocxParagraphLayoutTest.kt`, plus kernels' suites (`DocxXmlTest`, `DocxRevisionsTest`, `DocxCommentsTest`, `DocxReplaceTest`, `DocxTable*Test`).
- Instrumented (`androidTest`): **none exist, anywhere**. `:pdf-core` has 5 test files. `:ocr-core` has none.

## 4. What the audit itself verified against the automated suite

- Confirmed green baseline → the defects in `BUTTON_ISSUE_REGISTER.md` are **not** covered by any existing test (that is the point): no test asserts (a) `dirty` for settings/HF-only sessions [PS-02], (b) rel-id uniqueness across multiple `hfCreate`s [PS-03], (c) preview geometry sync [PS-04], (d) section-break placement/type semantics versus a real Word document [PS-01], (e) `replaceRange` revalidation [A1].
- `DocxPageLayoutTest:210-232` actively **asserts the wrong OOXML** (type on the inserted pre-break sectPr) — a green CI run is consistent with the diagnosed defects, not a contradiction.

## 5. Skipped tests — accounting

Per committed Phase-3 report: ≈44 skipped tests, reasons stated there (device-only, requires specific installed apps/bookmark state, etc.). None of the audited defect areas were among the skipped tests (they have no tests at all). **All skips were treated as "not executed": no result in this audit is derived from a skipped test.**

## 6. Device-only verification requirements (nothing here has been run on a device)

| ID | Requirement | Source finding | Expected-if-bug (falsifiable) |
|----|-------------|----------------|-------------------------------|
| DV-1 | Word opens a DOCX saved after 2 header/footer creates in one session | PS-03 | Repair prompt on open |
| DV-2 | Word renders an app-inserted "Continuous" section break | PS-01 | New page instead of continuous |
| DV-3 | Word renders app-inserted Even/Odd breaks; check for a phantom empty first page/section | PS-01 + placement | Identical to Next Page; empty leading section present |
| DV-4 | Exit editor after toggling odd/even or editing a header/footer part only | PS-02 | No save prompt appears; edits lost without an explicit save |
| DV-5 | Enter margin values on a comma-locale keyboard | PS-09 | Values never commit, no error shown |
| DV-6 | Word tolerates orphaned header/footer parts after remove+recreate | PS-10 | No repair prompt (expected benign) |
| DV-7 | LiquidToggle reject paths (tracking toggle, orientation no-change) leave the thumb correct | A6 | Thumb can sit on the rejected state |
| DV-8 | Long-press/selection-handle behavior of text fields inside `SelectionContainer` | A7 | Field selection conflicts |
| DV-timing | Replace-Current immediately after rapid paragraph edits | A1 | Crash (StringIndexOutOfBounds) |

## 7. Build & lint summary

- Gradle/CI: **green** on this head for all three jobs (§2).
- Lint: `app/lint-baseline.xml` holds **410** recorded issues (largest buckets `NewApi` 46, `LocalContextGetResourceValueCall` 22); CI policy fails only **new** issues — no new lint error on this head.
- Vendored `TODO` debt: `backdrop` (`LayerBackdrop.kt:67` outer-transform bug), `cropper` (`DynamicCropState.kt:319`); counted under A12.

## 8. Repro matrix for the register (device prerequisites)

| Finding | Needs |
|---------|-------|
| PS-01, PS-03, DV-2, DV-3 | Real Word (Android or desktop) + a saved file from the app |
| PS-04, PS-05, PS-08, PS-09 | App alone (on-screen) |
| PS-02, DV-4 | App alone (in-app) |
| A1, DV-timing | App alone, fast input |

**Definition of done for the repair wave** (for later, not part of this audit): every fix above lands with (a) its regression test green, (b) the re-written/superseded tests of `DocxPageLayoutTest:210-232` consistent with the *fixed* semantics, (c) CI (`build`+`release`+`release-guard`) green, and (d) the DV rows it claims marked executed on device with results recorded here.
