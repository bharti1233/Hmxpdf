# HMXPDF — Phase 3 Final Review: Page Layout, Sections, Breaks, Headers & Footers

Branch: `release/hmxpdf-2.1.1-build` · Range reviewed: `efa139d..567414c` (9 commits) · Whole-diff review pass + CI green at run [41232855647](https://github.com/bharti1233/Hmxpdf/actions/runs/41232855647).

## Commits

```
fb920c3 feat(docx): page layout and section kernels (orientation, sizes, margins, breaks, references)
7bcc864 feat(docx): reader parses sections/hf parts/settings; writer emits hf parts and settings
d5f6afa feat(docx): page layout, break and header/footer edit operations in the view model
e4fae91 feat(docx): page setup and header/footer panels with localized strings in all locales
a6915ef test(docx): page layout round-trip, breaks, header/footer and settings coverage
9b8ae84 docs: add phase 3 source audit and implementation plan
c6b8270 fix(docx): compile fixes for CI — kernel nullability, package-level settings and page mirrors instead of copy()
d5755af fix(docx): supply CharFmt's required rPr in the page-break round-trip test
567414c fix(docx): page-break object must carry its full run; correct test semantics
```

## What shipped

- **Engine** `docx/DocxPageLayout.kt`: section enumeration (paragraph `pPr` + trailing `DocRaw` + synthetic), `pageSetupOf`/`withPageSetup` (orientation + header/footer/gutter), `titlePg`, section-break `w:type`, header/footer reference add/remove per variant, section-break paragraph insertion (body-level guarded), HF part state helpers (`hfState`, `hfXml`, `freePart`). All edits are surgical `w:sectPr` splices via `DocxProps.setSect` + new `SECTPR_ORDER`; unknown children byte-preserved; `setChildWhere` added so same-name variants never clobber each other (P3-5 hazard).
- **Reader/Writer**: `PageSetup` extends with defaulted `landscape/header/footer/gutter`; `DocxPackage` gains defaulted `hfParts/settingsPath/settings`; writer gains `Extras(hfSave, hfRelIds, settings)` — existing parts replaced, new parts emit part + caller-chosen `addRelWithId` + content-type override; settings replace-or-create.
- **VM**: per-section page-setup ops with per-field coalesced undo; page/section breaks (structural, untracked, Edit-Mode gated); `titlePg` toggle; odd/even settings splice (`wantSettings` package-level mirror — `DocxPackage` is a plain class, no `copy()`); HF paragraph editing with stable-id cache (`hfCache`) so text fields don't reset on recomposition; `resetPageLayoutExtras()` at both load sites + `primeForTest`; `pkg.page` synced from the undo stack before preview/PDF export.
- **UI**: one new dock button (existing `Icons.Rounded.Settings`, `GlassToolButton` convention) → `DocPanel.LAYOUT` glass dialog: section picker, orientation `LiquidToggle`, A4/Letter/Legal presets, 9 validated inch fields, page/section-break pills (section breaks only on body-level targets), first-page + odd/even toggles, 6 header/footer variant rows (edit → `DocPanel.HEADERS` inline field dialog, remove). No UI redesign: dock, glass dialogs, toggles, chips and text-field treatment all reuse existing components.
- **Strings**: 38 new keys × 7 locales (en/ar/es/it/pt/pt-rBR/ru), XML-validated.
- **Tests**: `DocxPageLayoutTest.kt`, 18 tests — every op verified through save→reopen round trips, including revision coexistence and multi-variant reference safety.

## Review pass findings (whole-diff, additive-by-construction)

- Engine changes are additive + defaulted: `PageSetup`/`DocxPackage` new fields default to legacy values; no existing caller can break; existing suites (phase 1/1.1/2) pass unchanged on CI (44 skipped are environment-bound).
- `DocPackage.page` moved `val`→`var` (source-of-truth stays the block/undo stack; the mirror is synced only at preview/PDF touches — stale-mirror risk retained by design, documented in the test report).
- HF edits are package-level (not undo steps) — comments precedent; intentional.
- During landing, CI surfaced and we fixed: kernel `String?` smart-cast, `copy()` assumptions (→ `wantSettings` + mirror assignments), a dropped return value in `buildPatched`, a required `CharFmt.rPr` in tests, and the **page-break payload bug** (bare `w:br` → full `w:r` run so the shared serializer emits valid OOXML that survives reopen). Four test-expectation corrections documented in `PHASE3_TEST_REPORT.md`.
- Safety contracts held: unknown `sectPr` children byte-preserved (tested), body-level sectPr remains `DocRaw` until edited, references of untouched variants survive (tested), part bytes/rels untouched when only the reference is removed (tested), Gate Changes intact through layout edits (tested), `DocxWriter` remains the sole write path.
- Not touched: `main`, tracked user files (`Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/`), no new dependencies, no local build attempts.

## Verification status

- CI run `41232855647` (SHA `567414c`): `build` (261 executed tests, 0 failed), `release` (signed APK), `release-guard` — all `success`. Artifacts: debug APK `13054523148`, release APK `13054212782`, bundle `13054167911`.
- Limitations and their reasons: see `PHASE3_TEST_REPORT.md` §Limitations (continuous-break visual, internal-PDF header/footer rendering, `fldChar` read-only, unused `lastRenderedPageBreak`, package-level HF ops).

Phase 3 is complete on the build branch and CI-verified; awaiting user authorization for `main`.
