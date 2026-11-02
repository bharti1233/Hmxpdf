# HMXPDF — Phase 3 Bug Fix Report

Branch `release/hmxpdf-2.1.1-build`. Baseline commit `60b821837f1527acc449b0bfc3b8b8580c79cf68` (CI run `41328856513` green). **Ending commit `96773ab` (`96773abc72d9e65a5a6651d864dfaf89f59036fd`)** — committed and pushed on the user's explicit authorization. Commit chain: `a0e718e` (the whole implementation wave + audit documents) → `78dfcba` (CI fix: `onClick(enabled)` overload missing in this Compose version → gate inside the semantics action; null-safe `recordSaved` snapshot) → `65f0392` (CI fix: `const val` → `val` inside the test class) → `96773ab` (test fix: fixture sanity assert read the document rels only — `rId1` is a root-`_rels/.rels` entry, not a document-rels id). All three CI-fix commits touch test scaffolding or compile-gating only; no product logic changed after `a0e718e`.

Companions: `BUTTON_ISSUE_REGISTER.md` (per-issue status table), `DOCX_PAGE_SETUP_AUDIT.md` (§7 per-root-cause status), `PHASE3_REGRESSION_TEST_REPORT.md` (test inventory + execution environment).

## 1. Scope and constraints honored

- Only Page Setup (P0/P1/P2) plus confirmed register items A1/A5/A6-as-scoped were touched; no interface redesign, no return of the experimental Page-Setup UI, no new features.
- `DocxWriter` remains the sole DOCX write path; every change rides the existing byte-preserving patch writer.
- No git history actions of any kind were performed (`main` untouched; no commits, no resets, no cleans). Untracked user files (`Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/`) were preserved and never staged.
- No local APK/AAB build was attempted; CI remains the build path (unit-test attempt documented in the regression report).

## 2. Files modified

| File | Change |
|------|--------|
| `app/src/main/java/com/hmx/pdf/docx/DocxPageLayout.kt` | R1: `insertSectionBreak` — boundary carries an unmodified source-sectPr copy (minimal `<w:sectPr/>` when none exists), the chosen `w:type` is stamped on the source terminator after insertion; null type = default Next-Page stripping. |
| `app/src/main/java/com/hmx/pdf/docx/DocxWriter.kt` | PS-03: `addRelWithId` — idempotent for same type+target, throws `IllegalStateException` on a real id conflict (fail-loud, never a corrupt rels part). |
| `app/src/main/java/com/hmx/pdf/docx/DocxEdits.kt` | A1: `replaceRange` clamps stale ranges to the current text/fmts (no-op instead of `StringIndexOutOfBoundsException`). |
| `app/src/main/java/com/hmx/pdf/ui/viewmodel/DocxEditorViewModel.kt` | PS-02: `dirty` extends to settings/HF edits vs saved baselines (seeded at load, refreshed via `recordSaved`), `currentFile.hasEdits` covers them, even/odd toggle cycle returns to clean (no empty settings shell for part-less packages). PS-03: `freeRelId` counts session ids. PS-10: `hfRemove` purges orphaned session state once no section references the rid. A5: `save()` reports `Failed` via `onDone` instead of a silent return. Section targeting: `firstBodyId` → `isBodyLevel` + `firstBodyLevelId`. Stale-capture fix: `commitSect` rebases on the section's *current* terminator (match by `paraId`/`rawId`), refuses a vanished synthetic section, treats content-preserving rebases as no-ops; `setSectionSetup`/`setFirstPageDifferent`/`hfCreate`/`hfRemove` all derive content from the fresh sectPr with the undo-key passthrough. Test seams: `writerExtras()`, `recordSaved()`. |
| `app/src/main/java/com/hmx/pdf/ui/screen/DocxEditorScreen.kt` | Gear `enabled = pkg != null` (PS-05); preview chrome reads `viewModel.livePage()` (PS-04); LAYOUT panel banner for View/read-only with the right string (§Interface behavior); presets normalize to the section's current orientation + normalized highlight (PS-08); `,`→`.` normalization + invalid-value danger tint + `enabled = live` fields (PS-09); section-break chips target the focused body-level paragraph with a first-top-level fallback and a placement hint (R5); first-page and odd/even toggles `enabled = live`. `flatten()`: section-boundary page breaks now come from a precomputed holder→following-`w:type` map (`sectionType` parsed, not a substring check; top-level bound — nested sectPr paragraphs inside DocRaw previews don't page-break, matching Word's own tolerance). |
| `app/src/main/java/com/hmx/pdf/ui/components/LiquidToggle.kt` | `enabled` param: gates the drag/tap path, `onClick(enabled)` semantics, 0.55 dimming — the panel toggles can no longer look active while rejecting (A6 for this surface). |
| `app/src/main/res/values*/strings.xml` (7 locales) | `docx_pagesetup_view`, `docx_pagesetup_readonly`, `docx_section_target` — en, es, it, pt, pt-rBR, ru, ar, all escaped per XML rules. |
| `app/src/test/java/com/hmx/pdf/docx/DocxPageLayoutTest.kt` | Wrong-behavior test rewritten + 2 new tests, incl. the writer-collision suite (20 @Test). |
| `app/src/test/java/com/hmx/pdf/docx/DocxReplaceTest.kt` | + stale-range regression (9 @Test). |
| `app/src/test/java/com/hmx/pdf/ui/viewmodel/DocxPageSetupViewModelTest.kt` | New (7 @Test): the first Page-Setup VM-state suite in the repo. |
| `docs/phase3-button-audit/*.md` | Register/audit status sections appended; the two new reports accompany this one. |

Totals: 15 files, +326/−88 at report time. No debug prints, no hardcoded test values in main code, no deleted user files, no branch operations.

## 3. Per-issue outcomes (summary)

Fixed & test-verified: PS-02, PS-03, PS-10, A1. Fixed, kernel-verified & CI-green: PS-01 (+R5 placement), rebase fix. Fixed, needs device verification (visual/behavior): PS-04, PS-05, PS-08, PS-09 (A-DV items), A6-surface. Partially fixed: PS-09 (visible invalid feedback + comma normalize; commit-debounce deferred — whole-number commit also drives live preview updates; a blur-based redesign needs device focus handling). Not fixed by design: PS-07 per-section render geometry (data layer correct; boundary breaking now spec-correct). Not fixed — out of wave scope: PS-06/A8, A2/A3/A4/A7/A9–A13 (A5, A6-page-setup, A1 excepted as covered above). Per-issue detail with root causes: register appendix.

## 4. Known limitations (documented, deliberate)

1. **Per-section geometry render (PS-07/R6)** — preview and internal-PDF still draw every page with the trailing section's paper/margins. The data layer enumerates per-section setup correctly and inserted breaks paginate; per-page rendering was not implemented this wave.
2. **Intermediate numeric commits** — typing "0.75" still commits "0" first (then corrects); ready for a blur-based commit design only on device verification.
3. **In-app distinction of Even/Odd sections** — legal Word behavior distinguishes them; the in-app preview draws any section boundary identically (unchanged, by design of the preview).
4. **HF variant nuances** — first-page/even rows work but are inert until the matching document-level setting is on (Word's own model); unchanged.
5. **`save()`/share guard tests** are inspection-verified, not executed (needs Android Context; see regression report §4).

## 5. Device checks to run after the authorization push

1. Create two section breaks (Continuous upstream, Even Page downstream) in a small DOCX → reopen in Word: sections behave per their type, no phantom leading section, no repair prompt (DV-2/3).
2. Toggle odd/even then press back with no body edit: exit dialog appears (DV-4); save; reopen: flag persisted.
3. Edit one header text, create a header variant, create a footer variant, then remove one: Word reopens clean — unique rel ids, sibling variant intact (DV-1 + purge).
4. Rotate orientation, verify preset chips show the landscape dims and the highlight persists after another orientation swap (PS-08).
5. Type a margin with a comma-locale keyboard ("1,5"): value commits; type "99": field turns red and never commits (DV-5, PS-09).
6. Preview geometry follows an orientation change immediately (PS-04, visual).

## 6. Final numbers

- Starting commit `60b8218`; ending commit **`96773abc72d9e65a5a6651d864dfaf89f59036fd`** (pushed `60b8218..a0e718e` then three CI-fix commits).
- CI: baseline run `41328856513` green @ `60b8218`. **This wave's final run is green.**
- Local test execution: impossible — no Android SDK on this device (documented attempt, §1 of the regression report). No APK/AAB was built locally.
- `main` status: untouched (no checkout, no merge, no push, no reset).

---

*Post-push appendix (final CI results):*

```
Ending commit: 96773abc72d9e65a5a6651d864dfaf89f59036fd
  chain: a0e718e (fix wave) -> 78dfcba -> 65f0392 -> 96773ab (ci fixes, test scaffolding only)
CI run (final, green): https://github.com/bharti1233/Hmxpdf/actions/runs/41417314759
  jobs: build (JVM unit tests + lint) SUCCESS · release (signed APK) SUCCESS · release-guard SUCCESS
  :app:testPlayDebugUnitTest — from the identical test set one commit earlier (run 41415983417
  breakdown line): 271 completed, 44 skipped — the 44 skips are the repo's pre-existing
  @Ignore("Requires Android Context") tests; no skip was added by this wave. Final run: 0 failures.
Intermediate runs (transparency):
  41414243999 failed — 2 Kotlin compile errors fixed in 78dfcba (onClick overload, recordSaved)
  41414929475 failed — in-test const val fixed in 65f0392
  41415983417 failed — 1 real test failure: the test's own sanity assert (knew that rId1
      belongs to the root rels, not document rels) fixed in 96773ab; 270/271 passed in that run
```

Per-issue statuses in the companion docs (`BUTTON_ISSUE_REGISTER.md`, `DOCX_PAGE_SETUP_AUDIT.md` §7) reflect the ending commit. Remaining work is the device-verification checklist (§5) — nothing else is pending on CI.
