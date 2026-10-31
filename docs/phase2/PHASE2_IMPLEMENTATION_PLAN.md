# HMXPDF — Phase 2 Implementation Plan: DOCX Track Changes, Review, Accept & Reject

Date: 2026-10-31 · Branch: `release/hmxpdf-2.1.1-build` · Basis: `PHASE2_SOURCE_AUDIT.md` (same HEAD).

The byte-preserving `DocxWriter` stays the **sole** document-writing path. No new dependencies. No UI/UX redesign — all new UI reuses the existing dock button (`GlassToolButton`) and dialog (`GlassDialog`) patterns.

---

## Safety review (mandatory gate — result: PROCEED)

- **No revision data silently discarded:** today's loader *drops* `w:del` text and unmakes `w:ins` wrappers on edited paragraphs `(G1/G2)`. Phase 2's loader records them instead; nothing that parsed before is lost, and paragraphs we still don't model (`moveFrom/moveTo`, strict OOXML) keep their `while-unedited byte-stable` treatment.
- **Comments intact:** revision flags live on per-char `CharFmt`; comment markers ride `extras` and reference runs are `OBJ_CHAR` object chars. Revision ops touch only revision-flagged chars and revision metadata maps — never `extras`, never object chars. Comments' anchoring and their part-writer logic are untouched.
- **Unedited paragraphs remain byte-preserved:** `origin`-verbatim copy path is untouched; only paragraphs the user edits (or resolves revisions on) are re-serialized.
- **Bulk operations reversible by existing undo:** Accept All / Reject All flow through `mapParas(all-ids) → one commit → one whole-`DocState` snapshot` — exactly the comments' add/delete and Replace-all pattern already in production.
- **Find & Replace, table-cell editing, undo/redo, View/Edit gates, the eight prior fixes, and Office Engine reliability:** untouched code paths with additive parameters defaulting to today's behavior.
- **No UI redesign:** one new dock glyph + one dialog panel cloned from the comments panel; indicators are `SpanStyle` decorations (underline/strike) injected at the existing `annotate` seam.

No blocker. Proceed.

---

## P2-1 — Track Changes ON/OFF

- **Files:** `DocxEditorViewModel.kt`, `DocxEditorScreen.kt`, `strings.xml`.
- **Model:** `private var tracking by mutableStateOf(false)` in the VM; `val tracking: Boolean` exposed; `fun setTracking(on: Boolean)` — enabling allowed **only** when `editMode` (no-op otherwise); disabling always allowed and **never** resolves pending revisions. Default **off** every session (consistent with documented editor behavior; no persisted preference exists in this app for editor toggles, and none is invented).
- **UI:** the Revisions panel (P2-4/P2-5/P2-7 host, below) carries the Track toggle in its header; the panel itself only opens in edit mode. View Mode stays strictly read-only (all new controls share the `editable && editMode` gate the comments dialog already uses).
- **Tests:** toggle refused in view mode; enabling then disabling leaves existing revisions exactly intact (byte-compare of the paragraph's rounded-trip); default state off.

## P2-2 — Record inserted text

- **Files:** `DocxModel.kt` (CharFmt.rev, DocPara.revs), new `DocxRevisions.kt`, `DocxEdits.kt`, `DocxEditorViewModel.kt`, `DocxWriter.kt`.
- **Model:** `data class Rev(val ins: Boolean, val id: Int)`; `CharFmt(rPr, link, obj, rev)` (new defaulted field last) — inserted chars are real text flagged `Rev(ins=true, id)`; `data class RevMeta(val author: String, val date: String)` and `DocPara.revs: Map<Int, RevMeta>` recorded at capture (and by the reader from parsed wrappers, preserving original metadata).
- **Capture:** `DocxEdits.typed(p, newText, typing, rev: Rev? = null)` — inserted-range chars take `typing` **with** the capture rev; the VM passes a fresh revision when `tracking` is on. Keystroke coalescing: the VM reuses the revision id of the previous `typed` commit when it coalesced into the same undo group (`"type$id"` key within the window) — one continuous typing spurt = one `w:ins`, mirroring Word.
- **Writer:** in `runs()`, extend run grouping to `(rPr, rev)`; an `ins` group emits `<w:ins w:id="N" w:author="HMXPDF" w:date="…Z">…</w:ins>`; nested inside link groups where applicable.
- **IDs:** `DocxRevisions.nextId()` (AtomicLong) **seeded at parse-time** from the max `w:id` in the document, hoisted into the package — no collision with pre-existing revisions.
- **Tests:** insert with tracking on → serialized XML has `w:ins` with id/author/date; tracking off → identical to today's output; multi-spurt coalescing; mixed formatting insertions split into separate `w:ins` runs within one wrapper or adjacent wrappers.

## P2-3 — Record deleted text

- **Files:** `DocxEdits.kt`, `DocxEditorViewModel.kt`, `DocxReader.kt`, `DocxWriter.kt`, `DocxRevisions.kt`.
- **Capture:** same kernels with `rev: Rev(ins=false)`: removed middle-range chars are **retained** as real chars flagged del (`text` keeps them; they render struck through). Chars already flagged `del` that the user removes again resolve as plain removal (a deletion of a deletion) — they drop without a new revision. Chars flagged `ins` that get removed vanish outright (undoing a pending insertion is not double-tracked). `replaceRange(…, rev)` gains the same treatment (its deleted span becomes del-flagged, its inserted text ins-flagged — one shared rev id per replace operation).
- **Reader:** paragraph-level `w:del` parses its runs' `w:delText` (plus `tab/br/noBreakHyphen/softHyphen`) as del-flagged chars; `moveFrom/moveTo` stay read-only-skipped (documented limitation, no change to today); the paragraph's `docPara.revs` absorbs wrapper metadata.
- **Writer:** del groups emit `<w:del …>` with `run(…, dele = true)` switching `w:t` → `<w:delText xml:space="preserve">`; `tab/br/…` elements unchanged inside deleted runs (ECMA-376-legal); object chars inside a del flag are wrapped as deleted object runs.
- **Tests:** delete with tracking on → `w:del/w:delText` round-trips; reject (P2-6) restores it; mixed ins+del in one edit; deletion adjacent to existing insertion groups correctly.

## P2-4 — Display tracked changes

- **Files:** `DocxLook.kt` (annotate), nothing else.
- **Rendering:** in `annotate`, after the per-run group style, add a second cached span for rev chars: ins → `TextDecoration.Underline`, del → `TextDecoration.LineThrough` (existing theme colors only — no new palette). Because the same annotated string feeds `DocTransformation`, indicators render **inside the editable field** too, without touching layout, selection, caret, or the field's value plumbing (the field's text already contains del chars as normal characters — they are ordinary text the user can see and act on through P2-5/P2-6).
- **Tests:** annotate produces underline span over ins-range and strike span over del-range (test via styles/annotations accessors); unchanged paragraphs' annotation output byte-equal to today's.

## P2-5 — Accept individual change (& UI host for P2-1)

- **Files:** new `DocxRevisions.kt`, `DocxEditorViewModel.kt`, `DocxEditorScreen.kt`, `strings.xml`.
- **Kernel:** `DocxRevisions.accept(p, id)` — chars flagged `Rev(ins=false, id)` are removed; chars flagged `Rev(ins=true, id)` are unflagged to their plain `CharFmt` (insertion becomes document content); the id's metadata entry drops from `p.revs`. Only that id is affected.
- **UI host:** `DocPanel.REVISIONS` + one dock `GlassToolButton` (Revisions icon, enabled like the comments dock button); `GlassDialog` cloned from the comments panel: header row (Track toggle — P2-1), `docx_accept_all` / `docx_reject_all` actions (P2-7), one row per pending revision (paragraph excerpt, INS/DEL kind, author · date) with accept/reject icon pair per row, gated by `editable && editMode`.
- **VM:** `fun pendingRevisions(): List<…>` (per-paragraph walk via `forEachPara`, mapping id → para span excerpt); `fun accept(id: Int)` / `fun reject(id: Int)` → locate the paragraph via `findPara` + `mapParas(listOf(paraId)) { DocxRevisions.accept(it, id) }` → **one undo step, containing only that change**; guards `if (!editMode) return`.
- **Save:** nothing special — the resolved paragraph re-serializes on next save; `moveFrom/moveTo` and paragraph-mark `ins/del` (in `pPr`) stay verbatim pending (documented limitation; Word keeps them visible).
- **Tests:** accept an ins → text stays, wrapper gone, *other* revisions untouched; accept a del → text gone, wrapper gone; unrelated paragraph untouched; one undo step restores both model and, after save, the XML.

## P2-6 — Reject individual change

- **Files:** same as P2-5.
- **Kernel:** `DocxRevisions.reject(p, id)` — ins-flagged chars removed; del-flagged chars unflagged (deleted text restored to normal content). Comments (`extras`/object runs) untouched; paragraph structure and `rPr` preserved because only the rev field changes.
- **Tests:** mirror P2-5 (reject restores deleted text byte-identical to its pre-edit formatting run properties).

## P2-7 — Accept All / Reject All

- **Files:** `DocxRevisions.kt`, `DocxEditorViewModel.kt`, `DocxEditorScreen.kt`.
- **Kernel:** single-pass `acceptAll(p)` / `rejectAll(p)` over `fmts` (O(chars) per paragraph; no per-id scans, no stale-id risk since no ids are kept across paragraphs). Empty paragraphs with only-mark revisions unaffected (their `pPr` verbatim).
- **VM:** collect **every** paragraph id via `forEachPara` (tables and previews included — same walk comments' delete uses today) → one `mapParas(all) → commit` = **one undo step**, atomic; failure before commit leaves the doc untouched because the kernel returns untouched paragraphs when their flags are absent (nothing to do). No partial application is possible: the kernel is pure and total per paragraph; the whole document commits in one snapshot.
- **UI:** the two panel actions; no-op when no revisions pending (button enabled state reflects `pendingRevisions().isNotEmpty()`).
- **Tests:** accept all / reject all across paragraphs, tables and mixed formatting; one undo restores pre-bulk state fully (assert); after-save XML has no residual `w:ins/w:del` from modeled revisions.

## Save / reopen (cross-cutting)

- **Files:** `DocxReader.kt`, `DocxWriter.kt`.
- Reader per §audit: parse `w:ins` runs (flag `ins`), `w:del` `w:delText` (flag del), absorb wrapper `id/author/date` per paragraph; unknown children inside wrappers fall back to today's read-only behavior for that paragraph (never degrading data); paragraph-mark and `moveFrom/moveTo` untouched.
- Writer per P2-2/P2-3: `(rPr, rev)` grouping, ins/del wrappers, `w:delText`, object-char wrapping; **byte-preserving paths untouched** (`origin` verbatim; all other zip entries streamed).
- Round-trip guarantee under test: save with pending revisions → reopen shows the same pending revisions with the same author/date metadata; resolvable again.

---

## Cross-cutting decisions

- **Tracking default OFF, session-scoped.** The editor's own history (audit §3.5–3.7) has no persisted toggle storage for docx editing; inventing prefs is out of scope per plan rules.
- **Deleted text stays in the field as real (struck-through) characters.** Alternatives rejected: excluding del chars from field text would break commit-time undo coalescing, offset math, and Replace-all ranges for one feature.
- **Individual ops touch one id on one paragraph;** bulk ops one pass over all paragraphs — both via the existing immutable rebuild helpers, so composition (undo windows, `restoreEpoch`) is untouched.
- **Unsupported-case policy:** operations the platform kernel already refuses (OBJ_CHAR ranges in `replaceRange`) remain refused under tracking — an op that cannot be captured safely **does not silently degrade to an untracked destructive edit**: the kernels return the paragraph unchanged rather than performing a tracked-looking non-recorded change; `moveFrom/moveTo` remains read-only, stated in the final report/original limitation note.
- **Author/date:** reuse the comment attributes' provenance pattern — `w:author="HMXPDF"`, `w:date` = ISO-8601 in US locale (same comment code); every wrapper carries all three attributes Word needs.
- **Performance:** capture is per-keystroke-O(diff) unchanged; render adds one style map lookup per span group; collect ops per-paragraph O(chars); bulk over all paragraphs O(doc chars) once. No full-document rewrites, no new zip reads/writes, no unbounded lists (`revs` entries die with their last chars on resolve; snapshots unchanged).

## Test matrix (Stage D — executed in CI only)

1. Parse: ins wrapper / del wrapper / both + multiple ids in one para / author+date retained / formatting inside wrappers / missing attributes tolerated (defaults blank) / `moveFrom|moveTo` still read-only.
2. Edit: insert+tracking, delete+tracking, tracking-off unchanged behavior, toggle doesn't resolve, OBJ adjacent edits supported (via typed) / OBJ ranges refused (replaceRange), mixed formatting, formatting-tool edits on flagged text keep flags.
3. Resolve: 4 ops per P2-5/P2-6, both bulk ops, per-revision isolation, undo of individual + bulk.
4. Round-trip: save→reopen for ins, del, multiple pending, after-accept, after-reject; comments preserved through revision ops; tables edited with revisions inside cells; unrelated zip parts byte-identical (existing writer tests extended).
5. Regression: full existing suite (Find & Replace, comments, table cells, view/edit gates, writer byte-preservation).
