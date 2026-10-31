# HMXPDF — Phase 2 Final Review: DOCX Track Changes, Review, Accept & Reject

Date: 2026-10-31 · Branch: `release/hmxpdf-2.1.1-build` · Head: `c99d3bc3bbbe9c5c436a744f3cc172ad556df897` · Final CI run: **41213743269**

Scope executed as planned and audited in `PHASE2_SOURCE_AUDIT.md` / `PHASE2_IMPLEMENTATION_PLAN.md`; verification numbers in `PHASE2_TEST_REPORT.md`.

---

## §1. Checklist

- **No UI/UX redesign**: only existing conventions used — one new dock button (`Icons.Rounded.ChangeHistory`), the Revisions panel is a `GlassDialog` like the comments panel, revision rows ride the existing dialog list conventions, and the tracking toggle reuses the existing LiquidToggle row pattern. No new navigation or visual language.
- **No data loss**:
  - Untracked-edit path byte-exact: "untouched document is written verbatim" green; every non-document zip entry still streams through unchanged (round-trip suite).
  - Tracked deletions retain their text in the model as `Rev(false, id)`; deletion text survives the save round-trip (`w:delText` re-emitted, per-run formatting preserved — bold deletion test).
  - Paragraph-mark revisions (`w:pPr/w:rPr/w:ins|w:del`) preserved verbatim in `pPr` (not resolvable — documented).
  - Unmodelable wrapper content (e.g. `w:ins` containing `w:hyperlink`) falls back read-only with the original XML untouched — no loss, no crash.
- **No regression in prior features (same green run)**:
  - Comments (Phase 1): add/delete/anchoring tests pass.
  - Table and preview tests pass.
  - Find & Replace tests pass; with tracking off `replaceRange`/`replaceAllIn` remain the byte-consistent F&R kernels.
  - Undo/redo: typing coalescing shares the revision id with the undo window (tested); one-undo-step bulk behavior unchanged.
  - Mode guards: `setTracking(true)` refused outside Edit Mode / non-editable docs (tested); all seven Phase 1 `if (!editMode) return` guards untouched.
  - Phase 1 and the 8 earlier fixes: none of their files touched outside the planned DOCX engine seam (see §3).
- **Secrets/credentials**: no signing keys, tokens, or credential files committed; push-time token used only from `/tmp/opencode/gh.token`, never staged.
- **Untracked user items preserved**: `Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/` never staged by any Phase 2 commit.
- **Dependencies**: none added (gradle files untouched).

## §2. Self-review notes (issues caught and fixed during implementation/CI)

1. `DocxLook.kt` missing `import …Rev` — CI compile, run 41173260920; fixed at `27a3e6c`.
2. JVM platform declaration clash (`setTracking`) — run 41173573165; fixed at `f775515` via `@JvmName` on the guarded function.
3. Invalid `const val` in the revisions test fixture — run 41211255995; fixed at `8431130`.
4. Pre-existing `DocxRoundTripTest` asserted the old "w:ins ⇒ read-only" contract — run 41212020365; updated to the Phase 2 contract (editable + revision captured) at `537ac3d`.
5. `putIfAbsent` requires API 24 (minSdk 23) in `DocxRevisions.pending` — run 41213044213 lint; replaced with an `!in` idiom at `c99d3bc`.

## §3. Known limitations (accepted; detailed in the test report)

- `w:moveFrom`/`w:moveTo` move-tracking not presented as revisions.
- Paragraph-mark revisions preserved verbatim, not resolvable as rows.
- Multi-line paste while tracking: caret approximation mirrors pre-Phase 2 behavior.
- Field-buffer nuance: tracked `typed()` returns the model caret so the screen re-syncs via `activate(cur.id, caret)` — intentional, ViewModel-tested.

## §4. Verdict

Phase 2 implemented per audit + plan: tracked insertions/deletions at the OOXML level, per-revision accept/reject, Accept All/Reject All, revision indicators in the editor, and save/reopen survival — with the untracked path byte-exact and the tracked path round-tripping through `DocxWriter`, the sole write path. All verification green on run 41213743269 (head `c99d3bc`). Awaiting user authorization for the `main` branch; no `main` work was performed and none will be without it.
