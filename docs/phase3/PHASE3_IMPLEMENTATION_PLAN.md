# HMXPDF — Phase 3 Implementation Plan: Page Layout, Sections, Breaks, Headers & Footers

Date: 2026-10-31 · Source audit: `PHASE3_SOURCE_AUDIT.md` (findings marked [C] there apply). No UI redesign, no new dependencies, `DocxWriter` stays the sole write path.

## Architecture decisions

- **Sections stay where OOXML puts them.** A section's properties are the `w:sectPr` string inside that section's END paragraph's `pPr`, or the trailing body-level `w:sectPr` (currently a `DocRaw` block). Phase 3 edits those strings surgically — never whole-document rewrites, never discarding unknown children. This preserves every byte of the "unedited paragraph" contract.
- **One new engine file** — `docx/DocxPageLayout.kt` (kernels only, pure functions over `DocState`/strings, same style as `DocxRevisions.kt`):
  - `sections(state)` → ordered `List<Section>`; `Section(paraId: Long?, sectPr: String)` — paragraph bearing the sectPr (null = the trailing body-level one, identified by its DocRaw block).
  - `pageSetupOf(sectPr)` → `PageSetup` (extends parsing with `orient`, `pgMar w:header/w:footer/w:gutter`).
  - `withPageSetup(sectPr, PageSetup)` — surgically replaces `w:pgSz` (adds `w:orient="landscape"` when swapped) and `w:pgMar` children via a new `SECTPR_ORDER` in `DocxProps` (`headerReference/footerReference` first, `type` before `pgSz`, `titlePg` before `textDirection`, etc.).
  - `withTitlePg(sectPr, on)`, `withType(sectPr, "continuous"|"evenPage"|"oddPage"|null)`, `withReference(sectPr, kind, type, relId|remove)`.
  - `beginSection(sectPr, type)` — section-break payload for a NEW paragraph's `pPr` (clone of the section's current sectPr + chosen `w:type`).
  - `nextPageBreak()` — the OBJ_CHAR payload `<w:r><w:br w:type="page"/></w:r>` (object-run compatibility with what the reader already parses).
  - `HF` model: `DocxHeaderFooter(path: String, index: Int)` + parse/edit via the existing reader (`DocxReader.Ctx.container`) so HF paragraphs are ordinary `DocPara`s.
- **Package state** — `DocxPackage` gains defaulted fields: `parts: Map<String,String>` (all text parts, already read), `settingsPath: String?`, `settings: String?`. `PageSetup` gains defaulted `orient:Boolean=false, header:Int=720, footer:Int=720, gutter:Int=0`.
- **VM** (`DocxEditorViewModel.kt`): Edit-Mode-gated ops, none reflow unrelated content:
  - `livePage(): PageSetup` — block-derived (undo-proof preview source; fallback `pkg.page`).
  - `pageLivePkg()` — `pkg.copy(page = livePage())` used by `exportToPdf` only.
  - `setSectionSetup(section, PageSetup)` — rewrites that section's sectPr (DocRaw replace for body-level via `DocRaw.withXml` copy; paragraph route via `mapParas`-style pPr edit) — one undo step.
  - `insertSectionBreak(beforeId, type)` — inserts a new empty editable paragraph carrying the cloned sectPr (via `mapContainerOf`, one undo step).
  - `insertPageBreak(id, caret)` — OBJ_CHAR insertion into the field's paragraph (one undo step, revision-null; tracked capture untouched).
  - `setFirstPageDifferent(section, on)` — `w:titlePg` toggle (one undo step).
  - Header/footer ops on the part's own `DocState` (`hfStates: SnapshotStateMap<path, DocState>`): `hfParas(path)`, `hfTyped(path, paraId, newText)`, plus reference management: `hfAdd(section, kind, variant)` → creates part+rel+override if missing, writes reference into the section's sectPr; `hfRemove(section, kind, variant)` → drops the reference (leaving other sections' references intact and part untouched).
  - `setEvenOdd(on)` — settings.xml splice (`w:evenAndOddHeaders` before anchor if present, else append), creating settings part + rel + content-type when absent, mirroring the comments pattern.
- **Writer** (`DocxWriter.kt`): `parts()` gains a header/footer block: for `pkg.hfModified` paths → `replace[path]` (existing part edited) or `add[path]` + `addRel` + `addOverride` (`application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml` / `+footer+xml`, exceptions handled) emitted as `<?xml …?><w:hdr xmlns:w …>…</w:hdr>`; settings splice replace when changed. All new ids come from the VM (`freeRelId` numbered scan) so the reference string is written in the document XML in the same commit.
- **UI** (`DocxEditorScreen.kt`, existing conventions only):
  - One dock button `Icons.Rounded.Settings`… actually `Icons.Rounded.Map`… use `Icons.Rounded.CropRotate`-family existing Material icon `Icons.Rounded.CropLinear`? Minimum: same `GlassToolButton` row pattern; label `Page setup`; opens `DocPanel.LAYOUT`.
  - `DocPanel.LAYOUT` = existing `GlassDialog` — ориентация row (LiquidToggle), paper-size chooser rows, 4 margin fields (reuse text-field style already used by search bar/coments), header/footer distances, gutter (Extra), section list rows when >1 section ("Pages: section N"), per-section Edit, plus "Insert page break"/"Insert section break" apply on the active paragraph or first editable, and header/footer variant rows (Header/Footer × Default/First/Even) each opening an inline text editor list (BasicTextField like the comments dialog).
  - `DocPanel.HEADERS` reused inside LAYOUT through row taps; no separate new navigation surface.
- **Strings**: EN + ar/es/it/pt/pt-rBR/ru — page setup labels, orientation, sizes, margins, breaks, headers/footers, variants, validation error, section nomenclature.

## Safety review (mandatory)

- Sections not silently discarded: only surgical replaces of pgSz/pgMar/titlePg/type/ref children; unknown sectPr children stay byte-identical; body-level sectPr still a DocRaw (verbatim until edited).
- Page settings preserved: parse keeps all pgMar attrs; editing writes only the touched attrs.
- Comments intact: comment part/rels untouched; body edits don't touch comment markers.
- Revisions intact: break insertion captures no revision (page/section structure isn't text), and tracked text flows are untouched.
- Header/footer references remain intact: existing references are preserved verbatim unless a variant is being removed; parts stream unchanged when their state invalidates nothing.
- Unedited paragraphs byte-preserved: no global rewrites; documentXml splice regions unchanged.
- New relationships/content-types valid: addRel/addOverride patterns proven by comments/numbering/styles paths.
- PDF export: gains live page setup (sections after the first remain internal-renderer-limited — documented, not faked).
- UI: no redesign — one button + two dialogs in existing components.
**Verdict: PROCEED — no destructive migrations, no architecture replacement.**

## Test plan (new suite `app/src/test/java/com/hmx/pdf/docx/DocxPageLayoutTest.kt` + VM additions in a second class if needed)

1. Orientation: switch to landscape (w/h swapped + `w:orient="landscape"`), switch back, save/reopen stability; multi-section docs change only the edited section.
2. Sizes: A4/Letter/Legal presets; custom valid; invalid (negative, > 22in, non-numeric) rejected by validation; margins independent + all; header/footer/gutter distances written; unrelated sectPr children (cols, docGrid) intact after edits (byte-substring checks).
3. Breaks: insert page break → model OBJ_CHAR → survives save/reopen → parse-back via `isPageBreak`; section break next/continuous typed; two sections with different orientation/margins; sectPr placement (in new paragraph's pPr, body sectPr untouched).
4. HF: read existing header text; edit it; create new header+footer parts (rels+content-types correct, no orphan parse of missing rels); save/reopen; removing reference removes only that reference; other variant parts untouched byte-level.
5. Variants: titlePg flag + first-page reference; even/odd settings flag; combined save/reopen.
6. Regression: tracked insertions/deletions/comments/tables/F&R/untouched verbatim round-trips all re-asserted in the new suite too (cheap byte-slice checks), so page-layout edits demonstrably don't disturb them.
7. Validation-focused negative tests (width too small or > max, margins negative).

## Files

| File | Change |
|---|---|
| `docx/DocxPageLayout.kt` | new kernels (sections, pgSz/pgMar surgery, refs, HF helpers) |
| `docx/DocxModel.kt` | `PageSetup` defaults + `DocRaw.withXml` |
| `docx/DocxProps.kt` | `SECTPR_ORDER` + `setChild`-based sectPr helpers |
| `docx/DocxReader.kt` | extended PageSetup parse, header/footer refs + parts, settings path |
| `docx/DocxWriter.kt` | header/footer + settings part emission w/ ids passed in |
| `ui/viewmodel/DocxEditorViewModel.kt` | ops + hf state machine + live page sync |
| `ui/screen/DocxEditorScreen.kt` | dock button + LAYOUT dialog; no other UI change |
| strings ×7 | new keys |
| tests | `DocxPageLayoutTest` (+ VM class) |
| docs | audit/plan/test-report/final-review |

## Risks & limits (documented in reports)

- Word's `lastRenderedPageBreak` marks stay authoritative for old page splits; inserted breaks/sections split the preview immediately (real, not simulated).
- Internal PDF renderer: single-section setup + no header/footer drawing; Office Engine path renders them when installed.
- Continuous breaks show no in-page column simulation.
- evenAndOddHeaders settings-order: inserted before known schema anchors, else appended; unit-tested for round-trip, Word-fidelity flagged for device check.
- HF content edits are per-paragraph text replaces through the same kernels (no undo on 包 package state, comments precedent); body undo untouched.
