# PHASE 1.1 BUG AUDIT — HMXPDF DOCX (View-mode security + Add-comment hotfix)

**Stage A deliverable.** Committed `git status`: clean tree (only the two expected untracked items: `Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/`); HEAD = `52502f5` (Phase 1 verified green in CI run 41081663181). Branch `release/hmxpdf-2.1.1-build` → remote `origin/bharti1233/Hmxpdf`. No user changes present to preserve beyond those untracked paths, which will not be staged.

Evidence model: **[C]** confirmed from source, **[A]** assumption marked as such.

## How the editor works today [C]

- `DocxEditorScreen.kt` holds mode as a single state: `var editMode by rememberSaveable { mutableStateOf(false) }` (line 468) — the document **does** open in View Mode, and one header toggle (line 882–891) is "the existing Edit action".
- The list it renders is pre-flattened with `flatten(doc.blocks, styles, readOnly = !editMode)` (line 496) and `DocEnv(editMode = editMode, …)` (line 959–980) — so paragraph/table-cell text fields are already rendered read-only in View Mode.
- **But `editMode` lives only in the screen.** `DocxEditorViewModel` has no mode knowledge: its ten mutating methods all apply edits unconditionally (see below). UI hiding is the *only* enforcement point today, and it leaks.
- Edit-mode toolbar dock: `AnimatedVisibility(visible = editMode)` (line 1052) — undo/redo, character/paragraph formatting, comment tool, save-as are shown only while editing. Reading chrome (page counter, print, PDF, share) shows only when `!editMode` (line 1112). [C]

### Complete mutation entry points (all route to the VM) [C]

| Entry (screen) | VM method | Guard today |
|---|---|---|
| paragraph text field (`onChange`/IME) | `typed` | none |
| Enter / pasted line breaks (`onChange`) | `split` | none |
| Backspace-at-start join (`onChange`) | `mergeWithPrevious` | none |
| character/paragraph formatting toolbar | `replace` (formatted para) | none |
| toolbar batch ops, comment anchoring | `mapParas` | none |
| **Find & Replace UI** (search bar + replace row, lines 1155–1229) | `replaceOne` / `replaceAll` | none |
| **comments dialog** (1280–1386) | `addComment` / `deleteComment` | none |
| toolbar undo / redo (1069–1070) | `undo` / `redo` | none |
| keyboard IME paths go through the same `centerChange`/`typed` | same as above | none |

Viewing operations that must stay available in View Mode — reading/scrolling, page navigation (`showGoTo`), zoom, print, share, PDF export — never call VM mutators [C].

## BUG 1 — View Mode is not strictly read-only [C]

### Concrete leaks (reproduction reasoning, source-verified)

1. **Search is *presented* in View Mode**: the header’s find button (`Icons.Rounded.Search` → `showSearch = true`) renders inside `if (!editMode)` (lines 867–870). Opening search in View Mode works today — and the search bar row (`AnimatedVisibility(showSearch)`, line 1155) shows *replace* controls too (`if (viewModel.editable)` line 1181) regardless of mode. A writable document + open search bar = live find & replace in View Mode.
2. **Leaking search across the mode switch**: `exitEdit()` (689–695) resets focus and `panel` but **not** `showSearch`/`searchQuery`/`focusTarget`; the Done button branch (884) only clears search when *entering* edit. So: enter edit → open search → press Done → the find/replace bar remains fully live while `editMode == false`.
3. **Comments dialog ignores mode**: its three affordances — draft field, Add, delete icon (lines 1355, 1338, 1362) — are gated on `editable` (= *file is writable*, `pkg.writable && fileUri scheme == "file"`, VM line 82), not on `editMode`. The View-mode header button `panel = DocPanel.COMMENTS` (line 871) opens the dialog with Add/Delete fully “enabled” on a writable doc: **add and delete comments execute in View Mode today**.
4. **No action-level guard**: all ten VM mutators (table above) mutate even when called from View Mode — e.g. the hardware-Enter guard only prevents *focus*, `flatten` only *renders* read-only; anything that reaches `viewModel.typed(...)` (a custom keyboard action, a stale invocation) still commits.
5. Optional-risk check: tracked-changes/ref-only docs (`writable == false`) already hide the Edit button (`if (editable && doc != null)`, 882) — out of scope for this bug. **[A]** unstated: the Tasks do not specify View/Edit on non-writable docs; unchanged behavior preserved.

### Root cause (one sentence)

Mode is enforced *only* by what the screen happens to render/hide, while the VM — the single choke point every mutation already flows through — is unguarded, and three UI gates (`showSearch`, `exitEdit`, the comments dialog) don’t consult `editMode` at all.

## BUG 2 — “Add comment” does nothing [C]

### Trigger chain (source-traced end to end)

1. Dialog opens (view-mode header button 871, or the edit-dock comment tool 1077).
2. User types into `draft` (BasicTextField, 1365).
3. Add button onClick (1337–1339): `focusTarget?.let { if (viewModel.addComment(it, draft)) { … } }`.
4. `focusTarget` is the paragraph that most recently *requested* caret focus — set in `activate()` (614), and **cleared again as soon as the field consumes the request** (`onFocusConsumed = { focusTarget = null }`, 973). It is therefore almost always `null` at Add time: right after entering edit mode without tapping into text, or after having tapped into a paragraph (the focus request was already consumed), or in View Mode.
5. `focusTarget?.let { … }` is a silent no-op — no error, no toast, no dialog close, draft preserved. **This is the reported failure.** [C]
6. Even when non-null, the flow then calls `viewModel.addComment(paraId, text)` — VM code is correct: blank-check, `cm.add` (unique id via `nextId`), `mapParas(listOf(paraId)) { DocxComments.anchorPara(it, id) }` (one undo step), displayed via `comments()` which lists only *anchored* items. Persistence goes through `DocxWriter.parts()` (comments rel + `[Content_Types]` override + part bytes) — already covered by CI-green `DocxCommentsTest` (add→save→reopen, resurrect, byte-preservation). [C]
7. Consequence of (4): the comment is **never created on device** — the ViewModel never sees the call.

### Root cause (one sentence)

The UI hands the anchor target as a transient caret marker that is normally `null` (and silently swallowed by `?.let`), instead of letting the ViewModel resolve the anchor — so the real VM insertion (`addComment`) is never called.

Secondary defect in the same handler: the Add gate `enabled = editable && draft.isNotBlank()` (1340) uses the *file-writable* flag, not Edit Mode — which is also Bug 1’s leak #3.

## Mode-state consistency check [C]

Single reliable mode state? **One state, wrong owner.** `editMode` (screen `rememberSaveable`) is the only mode flag — no duplicated/inconsistent copies exist. But it is screen-owned, so the VM cannot reconcile actions with mode; and the screen leaks it across `exitEdit` (see Bug 1 #2). Phase-1 anchors (`focusTarget` / `activeId`) are *caret-session* states, not modes — conflating them with anchoring is what breaks Bug 2.

## Required changes

### Bug 1 (guard, not hiding)
1. **VM owns mode**: `var editMode by mutableStateOf(false); private set` + `fun setEditMode(on: Boolean)`. Entering Edit requires a writable package+mirror (`editable`); leaving always allowed, and ends the typing-coalescing window (`lastKey = null`) so no stale undo-group crosses the mode line.
2. **Action-level guards** in all ten VM mutators: `typed`, `split`, `mergeWithPrevious`, `replace`, `mapParas`, `replaceAll`, `replaceOne`, `undo`, `redo` return without touching state when `!editMode`; `deleteComment` likewise. `save` stays unguarded by design: with mutations blocked in View Mode the document cannot become dirty, so Save there is an idempotent write of an unchanged file; the save function is only reachable when `dirty` in practice. **[A]** Products need “save” available while viewing (export/share flows read from the same path), so guarding it would break viewing, not protect it.
3. **UI level**:
   - Move the header find button from `if (!editMode)` to `if (editMode)` — same icon/slot/gesture, no redesign (in Edit Mode it is *added*; before this fix search was only ever openable from View Mode).
   - `exitEdit()` additionally closes the search bar (`showSearch = false`, `searchQuery = ""`) and clears `focusTarget`.
   - Comments dialog gates (draft field, Add enabled, delete icon) become `editable && editMode`.
   - Screen reads `val editMode = viewModel.editMode` (VM flag becomes the single source; `rememberSaveable` removed — after a process death the editor reopens read-only by design).

### Bug 2 (root fix)
- `VM.addComment(paraId: Long?, text: String)`: nullable anchor. Resolution: given `paraId` → that paragraph **if** still found and editable; otherwise the **first editable paragraph** of the document; none → `false` (and the dialog honestly stays open with the draft). The screen passes `focusTarget` as-is (plain `viewModel.addComment(focusTarget, draft)`).
- No new status string needed: with the fix, the only `false` paths are (a) blank draft — already prevented by `enabled`, (b) a document with no editable paragraph at all — for which the Add button is disabled and the draft field hidden by `editable`, and (c) defensive mid-call races that cannot occur synchronously (state is snapshot synchronous). No fake success path remains. **[A]**

### Risks
- Removing `rememberSaveable(editMode)`: after process death while editing, the editor reopens in View Mode with the document unchanged on disk — same as every fresh open; unsaved in-memory edits from the dead process vanish with the process (unchanged from Phase 1 behavior for a killed app).
- Mode flag in the VM must survive recomposition — `mutableStateOf` used identically to `canUndo`/`meta` [C].
- The comments dialog is shared by both modes; only its write affordances change, read-only list untouched.

## Tests to add (`app/src/test/…/ui/viewmodel/DocxEditorViewModeTest.kt`, plain JVM JUnit)

Plain JVM is the only harness available (no Robolectric, no local gradle/device). One test seam is required and added to the VM: `primeForTest(parsed)` installs a parsed package/state without `load()`'s picker pipeline, and `testFileMirror` overrides the `meta.fileUri?.scheme == "file"` half of `editable` (an `android.net.Uri` cannot exist in a plain JVM test). Test set:

1. opens in View Mode (`editMode == false`).
2. `setEditMode(true)` → edit mode; `setEditMode(false)` → view.
3. text typing blocked in View (doc object unchanged), allowed in Edit.
4. table-cell typing blocked in View (same `typed` path, cell paragraph id), allowed in Edit.
5. `replaceOne` / `replaceAll` blocked in View, allowed in Edit.
6. `addComment` blocked in View (returns false, part unmodified).
7. `deleteComment` blocked in View (part unmodified, para unchanged).
8. `undo` blocked in View (and `redo`), still correct in Edit.
9. comment add with `paraId == null` anchors to the first editable paragraph (the Bug-2 fix contract).
10. whitespace-only comment rejected; empty rejected.
11. multiple adds → distinct ids, both listed.
12. after returning to View, further mutations are rejected.
13. viewing ops remain functional in View: `comments()` lists pre-anchored comments; `para()` resolves; `dirty` stays false.
14. Save/reopen persistence of comments is *already* proven by `DocxCommentsTest`; the VM test asserts state without re-testing the writer.

Screen-level gates (hidden controls) are verified by source review + CI lint; not unit-testable without Robolectric (absent by design, no new libraries allowed).
