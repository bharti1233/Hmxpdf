# PHASE 1.1 IMPLEMENTATION REPORT — View-mode enforcement + Add-comment anchor fix

**Scope:** exactly the two phase-1.1 bugs; no UI redesign, no theme/typography/navigation changes, no new libraries, no new strings. Files touched: `DocxEditorViewModel.kt`, `DocxEditorScreen.kt`, plus a new test class and the `docs/phase1.1/` documents.

## Bug 1 — View Mode is now strictly read-only

Single owned mode state + corridor of guards at the action layer (root-cause fix per audit):

### `app/src/main/java/com/hmx/pdf/ui/viewmodel/DocxEditorViewModel.kt`

1. **Mode state moved into the VM** (line ~84–106):
   - `var editMode by mutableStateOf(false); private set` — one flag, survives recomposition/screen re-creation (document always reopens in View Mode after process death, as before).
   - `fun setEditMode(on)`: entering requires `editable` (writable package + local mirror); leaving is always allowed and ends any typing coalescing window (`lastKey = null`, same policy as `breakUndoGroup`) so no stale undo group crosses the mode line.
   - Test seams: `internal var testFileMirror: Boolean?` (Elvis-override of the `fileUri` half of `editable` — `android.net.Uri` cannot exist on plain JVM) and `internal fun primeForTest(parsed: Pair<DocxPackage, DocState>)`.
2. **Action-level guards** (`if (!editMode) return …`) added at the top of every mutating method:
   - `typed`, `split` (returns null), `mergeWithPrevious` (returns null), `replace`, `mapParas`, `undo`, `redo` — and `addComment` / `deleteComment` (Bug-1 §3 leak, same line).
   - `replaceAll` / `replaceOne` need no separate guard: both funnel their mutation through the guarded `mapParas` (their scans are read-only).
   - Keyboard/IME text paths already route through `typed`/`split`/`mergeWithPrevious`, so every mutation reaches a guard (screen audit: all calls funnel to these eleven methods).
   - `save` deliberately unguarded: mutations are blocked in View Mode, so the doc cannot become dirty there; an (unreachable-in-practice) save of an unchanged doc is an idempotent write used by viewing flows (`saveAs`/share-export paths). Guarding it would break viewing ops.
3. **UI-level enforcement** (`DocxEditorScreen.kt`):
   - Screen reads `val editMode = viewModel.editMode` (the screen-owned `rememberSaveable` flag removed).
   - Header: the find button moved from the `if (!editMode)` group to a new `else` branch — search entry now exists **only in Edit Mode** (before this fix search was only openable in View Mode; find/replace is an editing tool). View Mode keeps the comments *viewer* (`panel = DocPanel.COMMENTS` — list stays readable; only Add/Delete are mode-gated inside the dialog) and the paper-dark toggle. No icons, slots, or gestures changed elsewhere.
   - `exitEdit()` now also closes the search bar (`showSearch = false`, `searchQuery = ""`) and clears `focusTarget`, so nothing editing-related leaks across the mode line (the audit's leak #2).
   - Entering Edit via the existing toggle still clears any open search (`showSearch = false; searchQuery = ""`) as before, now calling `viewModel.setEditMode(true)`.
   - Comments dialog: Add button `enabled = editable && editMode && draft.isNotBlank()`; delete icon and draft field render only when `editable && editMode` (dialog list remains available for reading in View Mode).
   - Find/replace row (`replace current` / `replace all` circles + Aa toggle): additionally gated `editable && editMode` — redundant with the VM guard (defense in depth; the bar is openable only from Edit Mode as of this phase).
   - The `replaceOne`/`replaceAll` calls, `BackHandler`, `requestBack`, reading chrome, dock, `LaunchedEffect(editMode)`, and `DocEnv(editMode=…)`/`flatten(…, !editMode)` render layer are unchanged, reading the same flag through the val.

Straight-View behaviors preserved (audit requirement): reading/scroll, page navigation, zoom, print, share/PDF export, comments list viewing, paper-dark toggle — none call mutators.

## Bug 2 — Add Comment works (root fix: the anchor, not the button)

- **Root cause** (audit): the dialog Add handler was `focusTarget?.let { viewModel.addComment(it, draft) }` — `focusTarget` is the transient caret-focus request (consumed to `null` the moment a field takes focus), so it was almost always `null` and the `?.let` swallowed the no-op silently. The real VM insertion was never reached.
- **Fix:** `ViewModel.addComment(paraId: Long?, text: String): Boolean` accepts a nullable anchor:
  - resolves the given `paraId` if still found and editable,
  - otherwise falls back to the **first editable paragraph** of the document (new private helper `firstEditable(blocks)`),
  - none → returns `false` and the dialog honestly stays open with the draft (the only reachable false-paths are blank text and a zero-editable-paragraph doc, where the button is disabled anyway).
- **Screen:** Add onClick is now plain `if (viewModel.addComment(focusTarget, draft)) { draft = ""; panel = DocPanel.NONE }` — anchor may be null (after `onFocusConsumed`, after entering Edit without tapping text, in any focus-less session).
- No new feedback string added (existing style kept: failure keeps the dialog open rather than faking success; success closes and clears as before).
- Comment id generation, anchoring (`commentRangeStart`/`commentRangeEnd`/`commentReference` runs), and part writing in `DocxComments.kt`/`DocxWriter.kt` are untouched (already CI-proven by `DocxCommentsTest`).

## Explicitly not done (ponytail notes)

- No search-bar redesign, no new dialogs/labels for "comments are read-only in View Mode" — the gates make the actions impossible, which is the requirement.
- No guard on `save` (see above); no mode flag in `DocxComments.kt` (persistence layer stays mode-blind — guards live at the MV boundary).
