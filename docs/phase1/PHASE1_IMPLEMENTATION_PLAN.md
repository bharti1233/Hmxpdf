# PHASE 1 IMPLEMENTATION PLAN — HMXPDF DOCX (P0-1 / P0-2 / P0-3)

**Stage B deliverable.** Companion to `PHASE1_SOURCE_AUDIT.md` (evidence labels there; file:line references are to that audit). Guiding constraints: byte-preserving `DocxWriter` stays the sole write path; whole-`DocState`-snapshot undo needs no new code; no UI redesign — additive controls only, reusing `GlassSearchPill` / `LiquidIconButton` / `DocPanel` conventions; no new libraries.

---

## P0-1 — Find & Replace

### Existing architecture (used as-is)
- Match generation: `produceState(doc, searchQuery)` (Screen L492-508) recomputes on every doc change → no stale-position class of bug. Highlighting + navigation via `DocEnv.hits` + `DocxLook.annotate` + `itemOfHit`/`goToMatch`.
- Atomic bulk edit: `viewModel.mapParas(ids, f)` = `breakUndoGroup`-equivalent + one `commit` (VM L230-238) → Replace All is one undo step for free.
- Search today: case-insensitive only, matcher inline in the screen, no OBJ_CHAR guard.

### Files to modify
| File | Change |
|---|---|
| `docx/DocxEdits.kt` | + `matchesIn(text, q, ignoreCase)` — pure non-overlapping matcher, **skips matches whose range contains `OBJ_CHAR`**; + `replaceRange(p, start, end, newText)` pure op (replaced chars take the `CharFmt` of the first replaced character — deliberate Word-like behavior when a match spans runs; both objects guaranteed outside range by the matcher guard). Undone state = plain `p.edited(...)`, `marks` shifted like `typed` does |
| `ui/viewmodel/DocxEditorViewModel.kt` | + `replaceAll(query, replacement, matchCase)` — engine-side `matchesIn` per para, `mapParas` single commit; + `replaceOne(paraId, range, replacement)` — one `mapParas(listOf(id))` commit. **No undo-stack changes** |
| `ui/screen/DocxEditorScreen.kt` | `produceState` matcher body swaps to `DocxEdits.matchesIn(text, q, matchCase)` with `matchCase` state; current-match index re-clamped when match count changes; a replace row shown while `showSearch && pdf==docx-edit`: `[Aa match-case toggle] [replacement GlassSearchPill] [replace current] [replace all]` built from the file's existing glass materials |
| `ui/screen/PdfSearchBar.kt` | **Additive only**: `onToggleMatchCase / matchCase / onReplace…` optional params that default to not rendering; PDF viewer call site unchanged (pixel-identical) |
| `res/values/strings.xml` | + `docx_replace_hint`, `docx_match_case`, `docx_replace_one`, `docx_replace_all` (note: `viewer_replace` belongs to the PDF signature toolbar) |

### Data flow / XML
`replaceOne`/`replaceAll` produce `edited()` paras (**only paragraphs containing a match** drop `origin`); writer re-serializes just those paragraphs and splices them into the original document.xml by offset. Untouched paragraphs, tables, and zip parts remain byte-identical.

### Undo/redo
Replace One & Replace All each = single `commit` with `key = null` → one discrete undo step each, restorable byte-identically. Matches recomputed automatically from the restored doc.

### Tests (new `app/src/test/java/com/hmx/pdf/docx/DocxReplaceTest.kt`)
one / many / none / case-sensitive / case-insensitive / replacement with spaces+punctuation / match spanning runs (mixed formatting:新 run keeps first-run fmt, rest of para intact) / undo Replace One / undo Replace All (single step) / OBJ-guard (para with picture obj never yields a spliceable range) / unaffected paragraphs byte-identical (zip-entry assert, `DocxRoundTripTest` pattern).

### Regression risks / acceptance
- PDF viewer search must be visually unchanged (optional params) — verify call sites.
- Case toggle off = exact today's behavior (ignoreCase first-match nav intact).
- Accept: replace current/all work from the find bar; multi-run format rule documented; undo = 1 step; all new tests green in CI.

## P0-2 — DOCX Comments

### Existing architecture (used as-is)
- Anchors (`commentRangeStart/End`) already live verbatim in `DocPara.extras`; references already survive as obj runs. Only `comments.xml` is unparsed and unavailable.
- Writer add-part pattern exists verbatim in the numbering block (Writer L51-56): build XML → `addRel` → `addOverride` → Content_Types rewrite.

### Files to modify
| File | Change |
|---|---|
| `docx/DocxReader.kt` | + comments part lookup (rel type `…/relationships/comments` under the document rels, same lookup style as styles/numbering); + `commentsPath`/`commentsXml` fields on `DocxPackage` (sole constructor call site `DocxReader` L101) |
| `docx/DocxComments.kt` (new) | `CommentInfo(id, author, date, text, xml)`; `DocxComments.parse(xml, path)`; pure ops: `addComment` (returns next free id; appends `<w:comment>` before `</w:comments>`; anchor = `commentRangeStart/End` adjacent at **paragraph start** in `extras` + reference obj run — the no-selection canonical Word form), `deleteComment(id)` (removes comment element via offset splice, strips its markers from all paras' `extras`, drops the reference obj run char from the anchor para — atomic) |
| `docx/DocxWriter.kt` | + comments block in `parts()`: when `comments.modified` → replace existing part (splice, numbering-style) or add part when absent **with lazy ids** (+ `addRel`/`addOverride`); existing unmodified `comments.xml` continues to stream untouched |
| `ui/viewmodel/DocxEditorViewModel.kt` | + `addComment(para, text)`, `deleteComment(id)` — each one pure mapParas/commit (single undo step) |
| `ui/screen/DocxEditorScreen.kt` | + `DocPanel.COMMENT`? — final: comments listed in the existing panel strip area; "comment" action reuses ribbon conventions; rows show author/date/text + delete; respect `editable` (read-only docs list-only) |
| `res/values/strings.xml` | + `docx_comments`, `docx_add_comment`, `docx_delete_comment`, `docx_comment_author`… minimal set |

### XML emitted (exact)
Anchor: `<w:commentRangeStart w:id="N"/><w:commentRangeEnd w:id="N"/>` into `extras`; reference obj run XML: `<w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr><w:commentReference w:id="N"/></w:r>`; comment element: `<w:comment w:id="N" w:author="…" w:date="…" w:initials="…"><w:p><w:r><w:t xml:space="preserve">text</w:t></w:r></w:p></w:comment>`.

### Undo / data-safety
One commit per add/delete. Deleting never half-completes (markers + reference + element in one pure op). If an anchor op cannot preserve some structure, the op aborts rather than dropping references silently.

### Tests (new `DocxCommentsTest.kt`)
read existing comments / list survives unrelated text edit + save-reopen / add → save → reopen shows comment & anchors / delete single / multiple comments incl. same-paragraph pair / invalid id tolerated (no crash, no-op) / unrelated zip entries byte-identical after comments ops / comments.xml rel + Content_Types added when part absent (reopen works in Word shape).

### Acceptance
Full read/add/delete/save-reopen loop in tests; comment part streamed untouched for docs where nothing changed.

## P0-3 — Table Cell Editing

### Verdict
**Functionality already exists [S][T]** (audit §4.4): cell paras route through the same `ParaField` pipeline and persist via the byte-preserving writer with a passing test. Per scope rule: **do not duplicate — fix only confirmed defects.**

### Work
1. Extend the round-trip test with the required residuals: multiple edited cells in one save / empty-cell typing / untouched tables byte-identical alongside cell edits.
2. Static re-verification of tap/focus wiring (`TableRowBlock` → `ParagraphBlock` → `ParaField`) — any **confirmed** gap gets a targeted fix; none found statically so far.
3. `[R]` Device checks (focus, keyboard into cell, ENOob layout) go to the runtime section of the test report — not implementation.

### Acceptance
No duplication; round-trip tests green; any confirmed defect fixed minimally.

---

## Cross-cutting
- Order: P0-1 → P0-2 → P0-3, each with its tests before moving on; local `./gradlew testPlayDebugUnitTest` + `:pdf-core:test` + `:ocr-core:test` as the pre-push gate.
- No changes to: theme/navigation, the 8 deep-audit fixes, Office Engine reliability work, `docs/word-competitive-analysis/`.
- Approval boundary: no destructive migration, no architecture replacement, no UI redesign → implementation proceeds directly (per master prompt).

Continue to Stage C now.
