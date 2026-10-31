# HMXPDF — Phase 3 Source Audit: Page Layout, Sections, Breaks, Headers & Footers

Date: 2026-10-31 · Branch: `release/hmxpdf-2.1.1-build` · Head: `efa139d22da8c62bc8533017f083a72a75764004`

Legend: **[C]** confirmed by reading source at this head · **[T]** test-confirmed on CI (run 41218797265, success, this exact head) · **[U]** unverified / requires runtime or device check.

---

## 1. Current Git state [C]

- Remote `origin` = `https://github.com/bharti1233/Hmxpdf.git`; branch `release/hmxpdf-2.1.1-build` HEAD `efa139d`, in sync with origin. `origin/main` = `de40322` (published Phase 2 release; **not** touched by Phase 3).
- Working tree clean except the two intentionally-untracked user items (`Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/`), preserved and never staged.
- No Phase 3 code exists at this head.

## 2. Phase 2 prerequisite verification [C/T]

Verified at the current head, not from the historical report:

- **w:ins / w:del / w:delText**: reader flags wrapper runs (`DocxReader.para()` ins/del branch); writer emits `w:delText` for deleted runs (`DocxWriter` run serialization, `elem = if (del) "w:delText" else "w:t"`).
- **Revision ids / author / date**: `RevMeta(author, date)` captured from wrapper attributes; `DocxRevisions.seed(ctx.maxRev)` + monotonic `nextId()` (AtomicLong).
- **Revision formatting**: `CharFmt.rev` rides the per-char format; the writer groups runs by `(rPr, rev)` so deleted runs keep their original `rPr`.
- **Accept/Reject**: `DocxRevisions.accept/reject/acceptAll/rejectAll` (per-paragraph kernels) + VM `resolveRevision/resolveAll`.
- **Save/reopen**: round-trip tests assert saved revisions and `w:id`/author/date survive (`DocxRevisionsTest`, 24 tests) — green on run 41218797265 at this head.
- **Undo behavior**: typing spurts share a revision id with the undo window (`capture(key)` mirrors `commit(key)`); resolution and F&R are one undo step each (existing tests).
- **Comments and anchors intact**: the comment path is untouched by Phase 2 (separate files); Phase 2 range diff lists no comment files.
- CI evidence: run **41218797265** = `success` at `efa139d` (jobs: build incl. unit tests + lint + assemblePlayDebug/Release; release upload; release-guard).

**Verdict: prerequisite satisfied. Phase 3 may proceed.**

## 3. Relevant source files (with roles) [C]

| File | Role relevant to Phase 3 |
|---|---|
| `docx/DocxModel.kt` | `DocPara/DocTable/DocCell/DocRaw`, `PageSetup(width,height,left,right,top,bottom)` (twips), `DocState(blocks)`; `mapContainerOf/replacePara/findPara/forEachPara` rebuild helpers |
| `docx/DocxReader.kt` | zip read (all `.xml`/`.rels` parts into `parts` map, pictures budgeted), rels parse, `container()` walk, `Ctx.para()` run logic, **body-level `w:sectPr→pgSz/pgMar → PageSetup (pkg.page)`**, `objXml()` object-run serialization, `DocxRevisions.seed` |
| `docx/DocxWriter.kt` | byte-preserving patch writer: `documentXml` = body-open slice + blocks() + body-close slice; `parts()` returns (replace, add) zip maps; `addRel`, `addOverride`, `relTarget`, `insertNumbering`; comments part creation pattern; `rewrite` streams every other entry unchanged |
| `docx/DocxEdits.kt` | kernels: `typed` (tracked-aware), `replaceRange/replaceAllIn`, **`split` (sectPr moves to second half)**, **`merge` (sectPr carried in)**, format ops |
| `docx/DocxProps.kt` | `PPR_ORDER` (ends `sectPr`), `setP/child/setChild` surgical pPr edits |
| `docx/DocxRevisions.kt` | Phase 2 kernels (keep intact) |
| `docx/DocxObjects.kt` | `ObjInfo`/`info(obj)`/`isPageBreak` — object runs (`w:br type="page"` becomes an OBJ_CHAR with verbatim obj XML) |
| `docx/DocxComments.kt` | in-package comment part model: `parse(path, xml)`, `add/remove/document(refs)`, `modified` flag — the creation pattern to mirror for header/footer parts |
| `ui/viewmodel/DocxEditorViewModel.kt` | `pkg/doc` snapshot state, `commit(next, key)` undo stack (whole-DocState snapshots), typed/replace/split/merge ops, comments ops, revisions ops, `buildPatched` (sole save path: `DocxWriter.write`), `exportToPdf` (Office engine → internal PDF → legacy converter) |
| `ui/screen/DocxEditorScreen.kt` | page preview: paper from `pkg.page` (width/margins in pt), `flatten()` pagination (Word `lastRenderedPageBreak` marks, page breaks via `DocxObjects.isPageBreak`, **section breaks: pPr `sectPr` non-continuous → `breakHere()`**), dock buttons, `DocPanel` dialogs (`NONE, TEXT_COLOR, HIGHLIGHT, COMMENTS, REVISIONS`), `GlassDialog/GlassDialogAction/LiquidToggle/GlassToolButton` |
| `ui/screen/DocxPdfWriter.kt` | internal PDF export: uses `pkg.page` for page size + margins; lays out flattened blocks; **does not render headers/footers** |
| `.github/workflows/android.yml` | CI authority (test/lint/APK/release-guard) |

## 4. Existing page-layout support [C]

- Page dimensions + margins of the **last (body-level) section only** are parsed into `PageSetup` (`pkg.page`): `w:w/w:h` from `w:pgSz`, `w:left/w:right/w:top/w:bottom` from `w:pgMar`. Defaults A4/1" if absent.
- `w:orient` attribute is **not parsed**. `w:pgMar w:header/w:footer/w:gutter` are not parsed.
- The preview builds the paper from `pkg.page` (real proportions), margins drawn — so changing the parsed values updates the preview genuinely (no image rotation).
- The internal PDF exporter uses the same `pkg.page` for page size and margin flow.
- Word's own `lastRenderedPageBreak` marks drive page splitting; after a layout change these marks are stale until Word re-lays out — same limitation any patch editor has (documented).

## 5. Existing section support [C]

- **Body-level `w:sectPr`**: last child of `w:body`; reaches the model as a `DocRaw` block (`container()` else-branch) and is re-emitted verbatim by `DocxWriter.blocks()` — untouched documents round-trip byte-exact (test-established).
- **Paragraph-level `w:sectPr` (section breaks)**: opaque child of that paragraph's `pPr`; NOT parsed into a section object, but preserved verbatim in the pPr string both unedited (origin copy) and edited (`Props.setP` child handling + `PPR_ORDER` keeps it properly ordered, `DocxEdits.split` strips/moves it to the **second** half, `merge` re-attaches it — Phase 1-tested).
- The previewer already pages at paragraph-level section breaks (`flatten()` line 358: `sectPr` in pPr and not `continuous` → page gap).
- **No section model exists**: orientation/size/margins are only known for the trailing section; there is no list of sections, no `w:titlePg`, no header/footer references parsed.

## 6. Existing header/footer support [C]

- **None.** No `word/header*.xml`/`footer*.xml` parsing anywhere; `headerReference`/`footerReference` inside preserved sectPr strings survive untouched (they are raw XML), the referenced parts themselves stream through the writer untouched, and relationships exist in the parsed `rels` map but are unused.
- `[Content_Types].xml` handling exists (comments/numbering/styles patterns) and is directly reusable for new header/footer parts.
- `DocxComments` is the exact template for "create a new package part + relationship + content-type override + document reference, only when modified".

## 7. Confirmed missing functionality

1. Orientation (`w:orient` + dimension swap). 2. Page sizes beyond the loaded values (A4/Letter/Legal/custom presets, validation). 3. Header/footer/gutter margin attrs. 4. Editing the page setup of any non-final section (paragraph-level sectPr). 5. Inserting page breaks (parse-only today: `w:br type=page` is an OBJ_CHAR the reader understands and the previewer honors — insertion needs a VM op). 6. Inserting section breaks (`w:sectPr` creation in a new paragraph's pPr). 7. Reading, displaying, editing, and creating header/footer parts. 8. `w:titlePg` (different first page) per section. 9. `w:evenAndOddHeaders` (settings.xml global flag) + even-page variant references.

## 8. OOXML risks

- **`w:sectPr` child order** is schema-enforced (`headerReference*`, `footerReference*`, `footnotePr`, `endnotePr`, `type`, `pgSz`, `pgMar`, `paperSrc`, `pgBorders`, `lnNumType`, `pgNumType`, `cols`, `formProt`, `vAlign`, `noEndnote`, `titlePg`, `textDirection`, `bidi`, `rtlGutter`, `docGrid`, `printerSettings`, `sectPrChange`) — new helpers must insert at the right positions (the codebase solves the same problem for `w:pPr` via `PPR_ORDER` + `setChild`).
- **`word/settings.xml`**: `w:evenAndOddHeaders` is a settings-level element (NOT per-section). Settings.xml may not exist (part+rel+content-type creation needed). Its own child order is schema-enforced; production files carry long ordered lists. Mitigation: insert before known anchor elements or append last, documented; settings edits are surgical (never rewrite the whole part).
- **Inheritance**: a section without references inherits the previous section's; per-section "unset first header" must remove the reference, not leave a pointer to a stale part. Word renders absent-variant by falling back to the default — correct to rely on.
- **Titles pages**: `w:titlePg` in sectPr tells Word to use a `first`-type reference (or none). Setting the flag without a first-page reference is valid and renders an empty first header — exactly the "no header on page 1" use case.
- Strict OOXML (`w:pgSz` purl namespace docs) already renders read-only (`writable = false`) — page-layout ops must no-op on read-only documents, same gate as every other mutator.
- `w:type` missing means "nextPage" by definition — inserting type only when the user picks a non-default.

## 9. Rendering / pagination limitations

- Page and section breaks re-flow the preview immediately (no stale marks involved for inserted ones). Word's original `lastRenderedPageBreak` positions remain the base pagination — a real Word re-layout may differ; that is inherent to patch editing (unchanged by this phase).
- No in-page headers/footers are drawn today; the editor will show them inside the editing panel, not drawn at page tops (no redesign of the page chrome). Documented as renderer limitation.
- Continuous section breaks inside a page (column reuse) are not simulated — the preview pages at non-continuous breaks only; continuous breaks are saved correctly but show no special gap.

## 10. PDF export implications

- Office Engine path (LibreOffice-backed) renders saved headers/footers/orientation natively — the accuracy winner and the first choice in `exportToPdf`.
- Internal `DocxPdfWriter` path follows `pkg.page` (so orientation/margins updates flow), but it does not draw headers/footers and lays out one section's setup for all pages. Multi-section PDFs from the internal path stay limited to the final section's page setup. Honest, documented; not faked.

## 11. Undo/redo impact

- Body/paragraph sectPr changes live in blocks (DocRaw string / pPr string) → whole-DocState snapshots cover them; orientation/size/margin edits on the trailing section update `pkg.page` too, which is package state (like comments.xml). Mitigation: always derive the live page setup for preview from the document blocks (VM helper), and sync `pkg.page` from blocks immediately before export/save so undo cannot leave a stale paper. Header/footer part edits mutate package parts (like comment part writes) and are not undo steps — same accepted precedent as comments `add/remove`.

## 12. Comments and Track Changes interactions

- Section/page edits touch only sectPr strings and new package parts; they do not touch runs, revisions, or comment anchors. Tracked text regions survive saves (writer groups by rPr/rev independent of sectPr).
- A tracked deletion or insertion near a section-break paragraph: the pPr string is preserved through `typed/replaceRange` (they only modify text/fmts fields), so the sectPr child survives tracked edits.
- Comment parts/rels untouched by the new code paths (new addRel calls reserve ids by scanning existing ones, `rIdCpN` collision-free pattern unchanged).

## 13. Test coverage gaps (current)

Nothing covers: orientation persistence, non-final-section edits, margin validation, break insertion, section-break creation, header/footer read/edit/create/rels/content-types, titlePg, evenAndOddHeaders.

## 14. Recommended implementation sequence

1. `DocxPageLayout.kt` kernels: section enumeration + surgical sectPr editing (pgSz/pgMar/titlePg/type/refs) — pure string/regex-free via `Props.setChild` style helpers; `PageSetup` extension (orient/header/footer/gutter fields defaulted; existing constructor sites unaffected).
2. Reader: parse sections list (paragraph sectPrs + body sectPr), `w:pgMar` extra attrs, header/footer refs per section + parts map.
3. Writer: header/footer part write path (replace-existing / create-new with rels+content-types; VM-provided ids), settings.xml `evenAndOddHeaders` splice.
4. VM ops (Edit-Mode-gated): set section page setup, insert page break at caret, insert section break (next/continuous/even/odd), insert/replace/remove header-footer reference + content edit, titlePg toggle, even/odd toggle.
5. UI: one new dock button "Page setup" dialog (paper/orientation/margins/variants) + break buttons in the existing dock pattern + header/footer dialog — all existing GlassDialog conventions; strings ×7.
6. Tests (in-memory docx builder like the existing suites) + docs; CI loop.
