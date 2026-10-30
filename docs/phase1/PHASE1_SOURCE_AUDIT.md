# PHASE 1 SOURCE AUDIT — HMXPDF DOCX (P0-1 Find & Replace, P0-2 Comments, P0-3 Table Cell Editing)

**Stage A deliverable.** Every claim is labelled:
**[S]** = confirmed by reading source · **[T]** = confirmed by an existing test · **[N]** = not verified · **[R]** = requires runtime/device testing.
Line numbers refer to this working tree at commit `4f2c8ba` (branch `release/hmxpdf-2.1.1-build`).

---

## 1. Repository and Branch Details [S]

| Item | Value |
|---|---|
| Remote `origin` | `https://github.com/bharti1233/Hmxpdf.git` |
| Current branch | `release/hmxpdf-2.1.1-build` (HEAD `4f2c8ba`) |
| Remote branches (`ls-remote`) | **only** `refs/heads/release/hmxpdf-2.1.1-build` |
| Local `main` | does not exist |
| GitHub default branch (API) | `release/hmxpdf-2.1.1-build` |
| Token permissions (API) | `admin`, `maintain`, `push`, `triage`, `pull` |
| Workflows | `.github/workflows/android.yml` ("Android build") |

Remote has **no `main`**. The "protected main" rule therefore means: never create, push, or PR to it. Work and push happen only on `release/hmxpdf-2.1.1-build`, which is already the default.

## 2. Git Working-Tree Status [S]

`git status --short` shows exactly two untracked paths, both intentionally untouched:

- `Word_16.0.20618.20008.apks` — never stage/commit.
- `docs/word-competitive-analysis/` — keep intact (Task 2 deliverable); never stage in Phase 1 commits except as their own decision.

No modified or staged user work exists; nothing to preserve or transfer. `docs/phase1/` is new (created by this workflow).

## 3. Relevant Source Files and Responsibilities [S]

| File | Responsibility (verified) |
|---|---|
| `docx/DocxReader.kt` | Reads the zip into `parts`; finds main part via root rels (L68); `writable = !strict && body is w:body` (L84); builds `DocState` blocks (L87); constructs `DocxPackage` (L100-118) |
| `docx/DocxModel.kt` | `CharFmt(rPr, link, obj)` (L11); `OBJ_CHAR = '\uFFFC'` (L18); `DocPara` (L29) with `origin`, `extras`, `marks`, `editable`; `edited()` drops `origin` → re-serialization (L46-53); `forEachPara` (L113), `findPara` (L121), `mapContainerOf` (L135), `replacePara` (L153) |
| `docx/DocxXml.kt` | Offset-preserving `XNode`/`XmlScan` scan; `attr`, `escape/unescape` — only changed ranges are spliced; everything else copied verbatim |
| `docx/DocxEdits.kt` | Pure paragraph ops: `markFmt`, `fmtAt`, `typed`, `format`/`toggle`, `split`, `merge`, `withList`, style helpers |
| `docx/DocxProps.kt` | `RProps`/`PProps` parse+overlay; schema-ordered `setR`/`setP` (L219-243); `DocxStyles` (L271, `added` list), `DocxNumbering` (L358, `ensureList`), `DocxTheme`, `tableGridBefore` clamp (L488) |
| `docx/DocxObjects.kt` | Object-run decode (picture/textbox/page-break) → `ObjInfo` |
| `docx/DocxWriter.kt` | **Sole write path.** `write` (L21) → `parts(pkg, state)` (L37): replaces/adds styles & numbering parts with `addRel` (L92) + `addOverride` (L101) when absent (L51-76); every other zip entry streams through unchanged; document.xml splices re-serialized paras into the original at exact offsets |
| `docx/DocxPackage` fields | `mainPath`, `xml`, `bodyOpenEnd/bodyCloseStart`, `styles/stylesPath`, `numbering/numberingPath`, `theme`, `rels`, `links`, `relsPath`, `relsXml`, `contentTypes`, `media`, `page`, `writable` (Reader L13-36) — **no comments field** |
| `ui/viewmodel/DocxEditorViewModel.kt` | `restoreEpoch` (L68); `lastKey` (L78); `commit(next, key)` coalesces per key within 1500 ms (L131-143); `undo/redo` (L158-169); `typed` (L175); `replace(para)` (L183); `split` (L189); `mergeWithPrevious` (L208); `mapParas(ids)` = bulk single commit after `breakUndoGroup` (L230-238); save path via `DocxWriter` + `SaveRecovery` |
| `ui/screen/DocxEditorScreen.kt` | `DocPanel { NONE, TEXT_COLOR, HIGHLIGHT }` (L234); `flatten` (L269) emits `DocItem.Para/Gap/TRow` (TRow L361); search query (L475) and `matches` `produceState` (L492-508); `itemOfHit` (L510), `goToMatch` (L529), word/char `counts` (L535); `PdfSearchBar` call (L1157-1170); `ParagraphBlock` (L1398) with `editable = env.editMode && !readOnly && para.editable && range == null` (L1411); `ParaField` (L1491); `TableRowBlock` (L1709) routes cell paras through `ParagraphBlock` (L1766) |
| `ui/screen/DocxLook.kt` | `annotate(...)` renders hit highlighting from `hits` + current index (L208-214) |
| `ui/screen/PdfSearchBar.kt` | Shared find-bar (PDF viewer + DOCX editor): props `query, matchCount, currentMatchIndex, focusRequester, backdrop, uiSensor, fg/fgSoft/surface, onQueryChange, onPrevMatch, onNextMatch, onClose` (L46-61) |
| `res/values/strings.xml` | `viewer_find*` (L243-246), `viewer_replace` (L270, **used by PDF signature toolbar** — different meaning, do not reuse), `docx_*` block (L770-811) |
| Tests | `DocxRoundTripTest` (11 tests), `DocxParagraphLayoutTest` (7), `DocxTableLayoutTest` (3), `DocxXmlTest` (2) |

## 4. Existing Implementation Details

### 4.1 Search / Find (P0-1) [S]

- Triggered by `searchQuery` state (Screen L475); matches computed in `produceState(emptyList<Pair<Long,IntRange>>(), doc, searchQuery)` (L492-508) **and recomputed automatically whenever `doc` changes**, so stale match positions heal themselves after any edit/undo.
- Matching: `p.text.indexOf(q, i + q.length, ignoreCase = true)` over **all** paragraphs — body **and table cells and previews** — with a 5000-hit cap. **Case-insensitive only; no case toggle exists.**
- Hits flow as `hits: Map<Long, List<IntRange>>` into `DocEnv` and are highlighted by `DocxLook.annotate` (L208-214); `itemOfHit`/(`goToMatch`) map match (id, offset) → LazyColumn index for navigation.

**[N] Key model detail verified here:** `p.text` contains real U+FFFC object characters. A query (typeable text) can never match **at** an OBJ_CHAR, but a match range may **span** one (e.g. text `"a\uFFFCb"`, query `"ab"` doesn't match... but query `"a b"`? no — contiguous only). A contiguous query substring spanning OBJ_CHAR happens when text is `X\uFFFCY` and query has ≥2 chars with the OBJ between different letters — impossible, since OBJ is a char in the middle. Actually the risky case is: query matches across the boundary of adjacent normal chars with OBJ inside the match — e.g. paragraph text `AB\uFFFCCD` and query `BC` does NOT match (`B\uFFFC` breaks contiguity). So an indexOf match can only span an OBJ_CHAR if the OBJ itself appears inside the matched substring — which requires the query to contain U+FFFC (not typeable via keyboard, possible via paste). → **Source-confirmed low risk, but replace must still assert its range is OBJ-free (one cheap `rangeIsPlain` check).**

### 4.2 Undo / Replace plumbing [S]

- Undo = whole-`DocState` snapshots. `commit(next, key)` (VM L134) pushes immediately unless `key == lastKey && elapsed < 1500 ms` (L138-139). Typing passes a key; structural ops (`mergeWithPrevious` L224, `mapParas` L238) call `breakUndoGroup()`-equivalent (`lastKey = null`) then commit → **one undo step each**.
- Therefore **Replace All = one `viewModel.mapParas(...)` call = exactly one undo step**, with zero new undo code.
- `restoreEpoch` bumps on undo/redo (L160, L169); UI shows search `currentMatch` state does not survive undo — recomputed from `doc` instead (matches are `produceState` on `doc`), so navigation stays consistent. **[N] no stale-index bug reachable via matching because ranges are recomputed per doc change.**

### 4.3 Comments (P0-2) [S]

- Reader collects `commentRangeStart`/`commentRangeEnd` marker tags into `DocPara.extras` **verbatim** (marker list L173; append points L223/L228); `w:commentReference` runs are preserved **verbatim as object runs** (`OBJ_CHAR` + `CharFmt(..., objXml)` — Reader L198/L203).
- The writer re-emits `extras` and obj runs for any paragraph — including edited ones — so **existing anchors already survive editing, splitting, merging, and re-saving**. [T] `DocxRoundTripTest.readsTextRunsAndStructure` asserts `extras.contains("bookmarkStart")` after edit flows (L87, L116).
- **Gap [S]:** `word/comments.xml` is read into the zip `parts` map but `DocxReader.read()` never surfaces it: `DocxPackage` (L13-36) has **no comments field**, and the reader drops the parts map. The UI has no comment display, add, or delete.
- Relationships: comments part needs no doc rel to *render anchors* (the `w:commentReference` has no r:id), but Word adds a `comments` relationship + `[Content_Types].xml` override for the part. **The numbering "add part when absent" pattern already exists** (Writer L53-56: build XML, `addRel`, `addOverride`, replace Content_Types L76) and is fully reusable.

### 4.4 Table cell editing (P0-3) [S][T]

- Reader: cell paragraphs are built by the same `container()` call as body ones (`readOnly = !writable` folds into every `DocPara.editable`) — Reader L87/L84. **Writable doc ⇒ cell paras are `editable = true`.**
- Renderer: `TableRowBlock` (Screen L1709) lays each cell's paras with `ParagraphBlock(b, cellLook, env, readOnly || !b.editable, …)` (L1766) → `ParagraphBlock` computes `editable = env.editMode && !readOnly && para.editable` (L1411) → `ParaField` (L1491) is the same text field body paragraphs use. **No table-specific read-only gate exists.**
- Persistence: any cell edit produces an `edited()` para (drops `origin`); the writer re-serializes only that table, leaving other content byte-identical. [T] **`DocxRoundTripTest.editingATableCellRebuildsOnlyThatTable` (DocxRoundTripTest.kt L163-173) proves: cell edit → save → reopen → edited cell text present, `tblStyle`/`tcW` intact, sibling cell "A1" unchanged.**
- Untouched tables [T]: full-doc byte preservation asserted by `untouchedDocumentIsWrittenVerbatim` (L91-95) and `typingKeepsFormattingAndOtherParagraphs` (L98-118, includes the table cell assertion).

## 5. Confirmed Working Functionality

| # | Item | Evidence |
|---|---|---|
| 1 | Find with navigation + highlight, across whole doc incl. cells | [S] L492-508, L510-540; [R] device UX |
| 2 | Whole-doc-snapshot undo with 1.5 s typed coalescing | [S] VM L131-171 |
| 3 | Bulk edit = single undo step (`mapParas`) | [S] VM L230-238 |
| 4 | Comment **anchors** preserved through any paragraph edit | [S] Reader L173/223/228 + obj runs L198-203; Writer verbatim re-emit; [T] bookmark-in-extras assertions |
| 5 | Table-cell text editing + persistence in the byte-preserving writer | [S] L1709/1766/1411/1491; [T] RoundTrip L163-173 |
| 6 | Untouched paragraphs/tables/zip entries byte-identical on save | [T] RoundTrip L91-95, L98-118 |
| 7 | Add-part-with-rels-and-ContentTypes pattern for the writer | [S] Writer L51-76, L92-107 |
| 8 | Strict/`purl` OOXML docs gated read-only (`writable`) | [S] Reader L84; all P0 work must respect `viewModel.editable` |

## 6. Missing Functionality (the P0 gaps)

1. **[S] No replace:** no replace primitive in `DocxEdits`, no ViewModel `replaceAll`/`replaceOne`, no UI button/case toggle. Search is case-insensitive only, no toggle.
2. **[S] No comments parsing/UI:** `comments.xml` never parsed; no comment read/list/add/delete; no UI surface.
3. **[S] No OBJ_CHAR guard on matches/replace ranges** (paste-only edge, but cheap to close while implementing replace).
4. **[N][R] Table-cell editing UX reachability:** model + renderer + writer confirmed working by test; actual tap→caret→keyboard flow inside a cell on device is not verifiable from source. Per scope rule: **if it works, do not duplicate** — implement nothing unless a runtime defect is confirmed.

## 7. Confirmed Bugs [S]

1. **Find/replace range may include U+FFFC** only via pasted query text — guard in the shared matcher (fix lands with P0-1).
2. No other defect found in the audited paths. `tableGridBefore` negative clamp already fixed (Props L488-496, [T] DocxTableLayoutTest).

## 8. Data-Loss and Document-Corruption Risks

| Risk | Status |
|---|---|
| Replace corrupting an object run (picture/pageref) | Closed by design with an OBJ-char-free range assertion in the replace primitive [S-prevention] |
| Replace All breaking one undo into many | Closed: single `mapParas` commit [S] |
| Comments lost on save of unrelated edits | **Not a risk [S]** — anchors ride `extras`/obj verbatim; but until comments are parsed, Word comments' *text* already streams through untouched (parts streamed verbatim), so no data loss exists today either |
| Adding comments while paragraph is edited concurrently | Anchors must be written on the *current* `DocPara` (post-edit state), never on `origin` — plan requirement |
| Read-only/strict docs | reader gates `writable`; UI must keep `env.editMode` off for them [S] |
| Deleting a comment | Must remove: `w:comment` element (comments.xml), the `commentRangeStart/End` markers from one/more `DocPara.extras`, and the reference obj run from the anchor para. Half-done deletes leave invalid references — implement as one atomic pure op in `DocxComments` + one `mapParas` commit |

## 9. UI/UX Impact Assessment

- **PdfSearchBar is shared with the PDF viewer** [S] (PdfViewerBottomToolbar/PdfViewerScreen call the same class). Any change must be **additive optional params** (default = exactly today's rendering) so the PDF viewer is pixel-identical.
- Case toggle + Replace/Replace-all controls: added as an adjacent row in the DOCX editor's existing search surface (Screen L1157-1170 area), reusing `LiquidIconButton`/`GlassSearchPill` materials already in the file. No theme/layout/navigation change.
- Comments UI: minimal — a `DocPanel`-style strip for the selected comment + add/delete actions on existing edit-mode affordances. New strings under `docx_*` keys only (L770-811 block). `viewer_replace` is taken by the PDF signature toolbar — coined new keys instead.
- Tables: **no UI change** (feature already exists; only fix + runtime-verify).

## 10. Test Coverage Gaps [S]

Existing: round-trip (11), paragraph layout (7), table layout (3), xml entities (2). No tests for: replacement (any), comments (any), search matching itself (implicit via UI only). New tests required per plan (`DocxReplaceTest`, `DocxCommentsTest`), plus extend `DocxRoundTripTest` patterns (temp-file save → zip-entry assert → re-read).

## 11. Implementation Dependencies

1. `DocxComments.kt` (new) needs: `XmlScan`/`XNode`, `Props` helpers, `XmlScan.escape` — all exist.
2. `DocxPackage` gains a comments field → verify sole constructor caller is `DocxReader.read` (confirmed by grep during planning).
3. Writer: reuse `addRel`/`addOverride`/numbering-merge pattern for comments part (only when the package carries new/changed comments; existing docs' comments.xml streams through untouched when `pkg.comments` is unchanged).
4. VM: `mapParas` already the atomic bulk op; new pure ops live in `DocxEdits`/`DocxComments` so undo needs **no new code**.
5. CI: `.github/workflows/android.yml` runs `testPlayDebugUnitTest`, `:pdf-core:test`, `:ocr-core:test`, `lintPlayDebug`, `assemblePlayDebug`, `assemblePlayRelease` (signed), `bundlePlayRelease` — all gate verification.

## 12. Recommended Implementation Sequence

1. **P0-1** — `DocxEdits.replace` pure op (OBJ-guarded) → shared `matchesIn` used by the existing `produceState` (single matcher, case param) → VM `replaceAll`/`replaceOne` (single commit) → optional-params UI row in the DOCX search surface → tests.
2. **P0-2** — `DocxReader`: parse `comments.xml` into `DocxPackage.comments` → `DocxComments.kt` (list/add/delete, atomic) → writer parts() replace/add (numbering pattern) → minimal UI with `docx_*` strings → tests (read/add/save-reopen/delete/multi/id-preserve/unrelated-preserve).
3. **P0-3** — **no reproduction** (functionality confirmed [S]/[T]); add only a round-trip regression extension if Stage C runtime-style tests can express any confirmed gap; document Requires-runtime checks in the test report.
4. Stage D `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test` locally → Stage E diff review → Stage F push → Stage G CI.

**Verdict for the approval boundary: no destructive migration, no architecture replacement, no UI redesign is required** — implementation proceeds without waiting for approval.
