# HMXPDF — Phase 2 Source Audit: DOCX Track Changes, Review, Accept & Reject

Date: 2026-10-31 · Branch: `release/hmxpdf-2.1.1-build` · Author: engineering audit (source-verified)

Legend: **[C]** confirmed by reading source at this commit · **[T]** test-confirmed (test exists and CI ran) · **[U]** unverified (documented as such).

---

## 1. Current Git state [C]

- Remote `origin` = `https://github.com/bharti1233/Hmxpdf.git` (verified, not the original HMXPANEL repo).
- Branch `release/hmxpdf-2.1.1-build`, HEAD `d1652dd7e5da6475467c344667bbf945534be805`, in sync with `origin/release/hmxpdf-2.1.1-build`.
- Working tree clean except two intentionally-untracked user items (preserved, never staged): `Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/`.
- No Phase 2 code exists yet at this HEAD.

## 2. Phase 1.1 prerequisite status [C/T]

- View/Edit guards intact: `DocxEditorViewModel.kt` `if (!editMode) return` in all seven mutators (lines 193, 203, 216, 225, 232, 252, 275); screen gates `editable && editMode` at 1197, 1350, 1365, 1372; search-dismiss on mode exit at 804.
- Add-comment fix intact: `addComment(paraId: Long?, text)` at VM:311, wired from the comments dialog at screen:1348 with the no-caret fallback (`firstEditable`).
- Test evidence: 16 `@Test` methods in `app/src/test/java/com/hmx/pdf/ui/viewmodel/DocxEditorViewModeTest.kt`.
- CI evidence: GitHub Actions run **41114231939** (branch workflow) — status `completed`, conclusion `success` at head `d1652dd7`.
- **Verdict: prerequisite satisfied; Phase 2 may proceed.**

## 3. Existing architecture [C]

### 3.1 Document model (`DocxModel.kt`)

- `CharFmt(rPr, link, obj)` — one per character. `rPr` = the run's raw `<w:rPr>` XML kept verbatim; `link` = a raw `<w:hyperlink …>` open tag; `obj` = a whole verbatim object run (picture, footnote mark, page break…) rendered as a single `OBJ_CHAR` (`U+FFFC`) in the text. `@Immutable` data class.
- `DocPara(id, origin, openTag, pPr, extras, text, fmts, editable, marks)` — `text` is the paragraph's character stream (`\t`, `\n`, `\u2011`, `\u00AD`, `OBJ_CHAR` are model characters); `fmts.size == text.length`. `origin` = the paragraph's original XML; **while it is non-null the writer copies the paragraph byte-for-byte**. `edited()` returns a copy with `origin = null` plus per-field defaults (text/fmts/pPr/id/openTag/extras/marks) — construction sites are named-parameter-safe; adding fields after `marks` keeps the few positional call sites valid.
- `extras` — marker XML (`bookmarkStart/End`, `permStart/End`, `commentRangeStart/End`) re-emitted verbatim before the runs of edited paragraphs.
- Immutable tree + path-rebuilding helpers: `mapContainerOf` (rebuilds only the container path of one paragraph), `replacePara`, `findPara`, `forEachPara` (walks paragraphs inside tables and previews too).

### 3.2 Edit kernels (`DocxEdits.kt`)

- `typed(p, newText, typing)` — prefix/suffix diff between old and new text; middle characters of the new text get the `typing` format (Word's "format of the char before", from `fmtAt`); removed middle characters simply disappear from the model — **there is no concept of retained deleted text**.
- `replaceRange(p, start, end, newText)` — refuses ranges containing `OBJ_CHAR`; replaced text takes the first replaced char's format. `replaceAllIn` folds it (descending ranges). These are the Find & Replace kernels.
- `split` (Enter; `sectPr` moves to the second half), `merge` (Backspace at para start; concatenates text/fmts/extras/marks).
- `format`/`toggle`/`withColor`/`withHighlight` — rewrite `rPr` element-wise through `Props.setR`, which **preserves unknown `rPr` children verbatim** (`DocxProps.setChild` inserts/removes only the named child; unlisted children pass through untouched).
- `markFmt(p)` — the paragraph-mark's own formatting taken from `pPr/w:rPr`, deliberately **stripping `ins`, `del`, `rPrChange`, `moveFrom`, `moveTo`** before use. The engine already knows these element names exist; they are just never modeled.

### 3.3 Reader (`DocxReader.kt`, `Ctx.para()`)

Per paragraph:
- Runs inside `w:r` / `w:hyperlink`: `w:t/tab/br/cr/noBreakHyphen/softHyphen` → text with the run's `CharFmt`; `w:lastRenderedPageBreak` → `marks`; anything else (`drawing`, `footnoteReference`…) → `OBJ_CHAR` + verbatim object XML.
- **`delText`, `instrText`, `delInstrText`, `fldChar` as run children → paragraph marked non-editable, text not added.**
- Paragraph-level children other than `pPr/proofErr/r/hyperlink/markers` → `editable = false; deep(c)`. `deep()` recurses but **skips `del` and `moveFrom`** (their content is never added) and reads `r`/`moveTo`/`ins`/`sdt` children as ordinary runs through the else path. Consequences:
  - A paragraph containing `w:ins` or `w:moveTo` wrappers loads **read-only** with the revision wrappers gone from the model (runs read as plain text). If the user could edit it (they can't — read-only), the wrappers would not be re-emitted.
  - A paragraph containing `w:del`/`moveFrom` loads read-only with **the deleted text entirely absent from display and from the model**.
- `sdt`/`customXml` produce `DocRaw` with read-only previews. Strict-OOXML or oddly-prefixed documents → `writable = false`, whole document read-only.

### 3.4 Writer (`DocxWriter.kt`) — the byte-preserving patch writer, sole write path [C]

- `para()` edited serialization: `openTag + pPr + extras`, then link groups (`w:hyperlink` tags re-opened/closed), then `runs()` groups contiguous chars by identical `rPr` (objects emitted verbatim as single runs), then `run()` emits `<w:r>rPr<w:t xml:space="preserve">…</w:t></w:r>` with `tab/br/noBreakHyphen/softHyphen` mapped back to elements. **No `w:ins`/`w:del` concept exists anywhere in the writer.**
- Package surgery mirrors what Phase 1 needed: comments/numbering/styles parts are rewritten or created; relationships and `[Content_Types].xml` updated; **every other zip entry streams through unchanged** (used for all unverifiable claims of preservation — see tests).

### 3.5 Undo/redo (`DocxEditorViewModel.kt`) [C]

- Whole-`DocState` snapshots on commit; typing coalesces by key (`"type$id"`) within a time window; `lastKey = null` forces a new step. `mapParas(ids) { … }` commits **once** for the entire batch — comments' add/delete and Find & Replace already rely on this for one-undo-step bulk behavior.
- All mutating funnels (`typed`, `replace`, `split`, `mergeWithPrevious`, `undo`, `redo`, `mapParas`) guard on `editMode`.

### 3.6 Render pipeline (`DocxEditorScreen.kt`, `DocxLook.kt`)

- `DocxLook.annotate(p, …)` walks `fmts`, groups consecutive chars by **reference-identical** `CharFmt`, and adds one `SpanStyle` per group (built by `spanFor`: effective `RProps` → underline/strike/size/color…). Search hits overlay as background styles. The result is an `AnnotatedString` shared by two paths: `BasicText` when read-only, and `ParaField`'s `DocTransformation` when editing — the transformation wraps the *same* annotated string (after the leading ZWSP), so **per-range span styling carried in `annotate` renders inside the editable field as well** — the exact seam a revision indicator (ins underline / del strike) needs, without touching text layout, navigation, selection or caret behavior.
- `ParagraphBlock`: `editable = env.editMode && !readOnly && para.editable && range == null`; read-only paragraphs route to `BasicText`.

### 3.7 UI structure

- `DocPanel { NONE, TEXT_COLOR, HIGHLIGHT, COMMENTS }` (screen:240); dock buttons via `GlassToolButton` (1086–1087); comments dialog 1336–1395: `GlassDialog` with title + actions, list rows, per-row delete `Icon`, `editable && editMode` gating everywhere. Comments part lives in `DocxComments` (part lazily created, `add/remove/document(references)` splice, `modified` flag) and the writer only rewrites the comments part when modified.

## 4. Existing revision-XML support — what is parsed, preserved, or discarded [C]

| Element | Where it appears | Reader behavior today | Writer behavior today |
|---|---|---|---|
| `w:ins` (run wrapper) | para level | Paragraph **read-only**; wrapper not modeled; wrapped runs shown as normal text | n/a (para can't be edited) |
| `w:del` (run wrapper) | para level | **Entirely skipped — deleted text invisible and dropped from model** | n/a |
| `w:moveFrom` | para level | Skipped (content dropped), paragraph read-only | n/a |
| `w:moveTo` | para level | Read as ordinary text via else-deep, paragraph read-only | n/a |
| `w:delText` | run child | Read-only marker, no text modeled | n/a |
| `w:rPrChange` / `w:pPrChange` | inside `rPr`/`pPr` | Implicitly preserved (raw strings kept; `pPr` re-emitted verbatim for edited paras; unknown `rPr` children preserved by `setChild`) | pPr re-emitted verbatim; rPr re-emitted verbatim element-wise preserving unknowns |
| Para-mark `pPr/rPr/ins|del` | `pPr/w:rPr` | Preserved in `pPr` raw; `markFmt` strips them only when computing the typing format | Preserved verbatim in `pPr` |
| `w:lastRenderedPageBreak` | run child | `marks` (synchronized with text by edit kernels) | — |

**Net current behavior:** any document containing run-level revisions opens read-only as a whole or per paragraph; display loses deleted text; nothing in the model can represent a revision.

## 5. Confirmed gaps [C]

- **G1 — Revisions not modeled.** No field anywhere carries revision identity/type/author/date. Save-open of an *edited* revision-bearing document loses revision markup (rev paragraphs are read-only, so edits are refused before loss — but Track Changes is thereby impossible).
- **G2 — Deleted text is discarded from the model and display** (`deep()` skips `w:del`/`moveFrom`; `delText` emits nothing). A user cannot see or resolve pending deletions.
- **G3 — No capture path.** `typed`/`replaceRange` physically remove replaced text; there is no revision-aware variant.
- **G4 — No writer support.** Grouping keys are `(link, rPr)`; `run()` only emits `w:t`.
- **G5 — No resolution operations.** Nothing maps "accept/reject revision N" onto paragraphs; no id sequencing exists for new revisions.
- **G6 — No UI.** No panel, indicator, or toggle exists.
- **G7 — Coalescing gap for IDs.** Per-keystroke revisions would serialize one `w:ins` per keystroke; revision ids need to coalesce with the typing-undo window (Word merges continuous typing into one `w:ins`).

## 6. Risks if naïvely implemented [C]

- **Data loss:** flattening `w:ins` wrappers on edit (loader must *keep* them, not strip them as `markFmt` does for paragraph marks).
- **Formatting risk:** replaced text inherits the first replaced char's format (`replaceRange`); with tracking, inserted chars must each keep *their own* capture-time format, and pre-existing `rPr` unknown children must continue to round-trip.
- **Undo/redo risk:** bulk accept/reject must be a single `mapParas`-style commit (existing pattern gives this for free); individual resolution must rebuild only the paragraph's container path.
- **Model/offset risk:** deleted text as *retained but pending* chars changes nothing structurally — flags ride per-char `CharFmt`, and every consumer that walks `fmts` per char (writer, annotate, replaceRange's OBJ guard, typed's diff) scales unchanged; `marks` heuristics already tolerate text-length changes.
- **Comments interaction [C]:** comment markers ride `extras` (untouched by revision flags); comment reference runs are `OBJ_CHAR` object chars which the tracked kernels must exclude from capture-garbling (deleted *reference runs* would corrupt comment anchors — `matchesIn` already refuses object chars in match ranges; `typed`'s diff can still delete an OBJ_CHAR (same as today) — under tracking it would then serialize as still-present deleted content, which is *more* correct, not less).
- **Compat risk [U→design-for]:** every `w:ins`/`w:del` must carry `w:id`/`w:author`/`w:date`; runs under `w:del` must serialize text as `w:delText`; adjacency of same-id wrappers is legal but we emit merged wrappers where contiguous; `moveFrom/moveTo` remain out of scope (see §8).

## 7. Comments interaction [C]

Measured and found compatible:
- Anchors (`commentRangeStart/End` in `extras`, reference run as `OBJ_CHAR`) are per-paragraph and unaffected by per-char revision flags.
- Individual accept/reject of one revision must not strip comment anchors — operations are scoped to a revision id on chars of one paragraph, `extras` never rewritten by revision ops.
- Comments' one-undo-step pattern (`mapParas`) is reused as-is for revision resolution.

## 8. Compatibility audit — how Word interprets what we would emit [C analysis]

- Word's requirement for opener-recognized revisions: `w:ins`/`w:del` in the `w:p` content sequence between paragraph-mark properties and runs (or after `pPr`/`extras`, i.e. exactly where `extras` markers already sit is a *stylistic* matter — we emit wrappers in run order), carrying `w:id` (ST_DecimalNumber int), `w:author`, `w:date` (ISO-8601 `yyyy-MM-dd'T'HH:mm:ss'Z'` like comments' `w:date` produced in the VM).
- Runs inside `w:del` use `w:delText` (XML-escaped, `xml:space="preserve"`); `w:tab/br/noBreakHyphen/softHyphen` remain ordinary elements inside the deleted runs (ECMA-376 keeps them legal under `w:del`).
- Deleted object runs (pictures etc. under `w:del`) are legal; Word displays them only in review panes — our editor shows the object glyph struck through.
- `w:hyperlink` may wrap `w:ins`/`w:del` and vice versa; our writer nests wrappers inside the existing link-group loop.
- Paragraph-mark revisions (`pPr/rPr/w:ins|w:del`) are *preserved verbatim* by all our mutation paths (no data loss), but resolving them means paragraph split/merge — **out of Phase 2 scope, documented**, not silently accepted/rejected: they stay pending in the XML exactly as they were read.
- `moveFrom/moveTo` stay skipped read-only (unchanged from today — loader keeps `origin`, so they still round-trip byte-stable for unedited paragraphs).

## 9. Test coverage at HEAD [C]

- 210 app unit tests plus `pdf-core`/`ocr-core` suites pass in CI run 41114231939 (`testPlayDebugUnitTest :pdf-core:test :ocr-core:test --rerun-tasks` in `.github/workflows/android.yml`).
- Existing DOCX suites relevant to Phase 2 regression: `DocxCommentsTest` (round-trip + anchoring), `DocxTableCellEditTest`, `DocxEditorViewModeTest` (16 view-mode guards), plus writer/reader round-trip tests. Revision coverage: **none exists** — Phase 2 adds it.

## 10. Proposed architecture (per feature decisions)

- **Per-char revision flag.** `CharFmt` gains `val rev: Rev? = null`; `data class Rev(val ins: Boolean, val id: Int)` — inserted chars are real text flagged `Rev(ins=true, id)`; deleted text is retained as real chars flagged `Rev(ins=false, id)` and rendered struck through. No new per-paragraph containers for text; format-list size unchanged semantics; `@Immutable` keeps equal (rev,rPr) groups composable-cache-friendly.
- **Per-paragraph revision metadata.** `DocPara` gains `val revs: Map<Int, DocxRevisions.RevMeta> = emptyMap()` (`id → author/date`), read from parsed wrappers and recorded at capture; writer emits it on each wrapper it opens. Added *after* `marks` so existing positional `DocPara(…)` call sites stay valid.
- **Reader.** `w:ins` para-level wrappers parse their wrapped runs with an ins flag (paragraph stays editable if everything else parsed cleanly — unknown children inside the wrapper fall back to today's read-only behavior); `w:del` runs parse `delText` as retained del-flagged text; `moveFrom/moveTo` unchanged (read-only skip).
- **Capture.** `typed(p, newText, typing, rev: Rev? = null)` and `replaceRange(…, rev: Rev? = null)` gain a tracking-aware variant per parameter (VM passes a fresh revision when tracking is on): inserted middle chars get the new rev; removed middle chars either vanish (if they were pending insertions — uncounting a pending insertion) or are retained as del-flagged (normal text), logically "the user deleted it while tracking".
- **Writer.** `runs()` groups by `(rPr, rev)` within link groups; `ins` groups open `<w:ins w:id w:author w:date>`, `del` groups open `<w:del …>` and `run()` emits `w:delText` under a del flag; object runs inside a rev get wrapped. Untouched paragraphs remain `origin`-verbatim — byte-preservation untouched.
- **Resolution.** New `DocxRevisions.kt`: per-paragraph helpers `rangesOf`, `accept(p, id)`, `reject(p, id)`, `acceptAll(p)`, `rejectAll(p)` (single char pass), `pending(p)`; VM-side id sequencing seeded from the max id seen at parse.
- **UI.** Revision indicators ride the existing `annotate`/`DocTransformation` span path (ins → underline, del → strike, existing colors). One new dock tool button opens a **Revisions panel** (`DocPanel.REVISIONS`, `GlassDialog` like comments'): Track Changes toggle, Accept All / Reject All, per-revision rows with per-item accept/reject. No other UI change; all controls fenced by the same `editable && editMode` gate as the comments dialog.
- **State.** `tracking` session flag in the VM (default **off**, matching the documented editor behavior: editing is opt-in and destructive captures only when asked). Toggling off never resolves pending revisions. No persisted preference is invented (the settings architecture holds none for editor toggles — verified by reading the screen/VM).

## 11. Feasibility verdict

No architecture replacement and no destructive migration are needed: the change rides per-char `CharFmt` (an existing mechanism at every seam), whole-state undo, the comments-panel dialog pattern, and the byte-preserving writer. All identified incompatibilities are handled by explicit design decisions in the plan document; none is a blocker.
