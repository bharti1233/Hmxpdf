# PHASE 1.1 TEST REPORT — View-mode enforcement + Add-comment anchor fix

**Verification channel:** GitHub Actions only (`.github/workflows/android.yml`). Per the phase-1.1 instructions no local Gradle/Android build or APK/AAB packaging was run locally ("don't run or build apk locally — device is low"); all compile, unit-test, lint, signing and artifact verification happened in CI.

## Regression tests added

`app/src/test/java/com/hmx/pdf/ui/viewmodel/DocxEditorViewModeTest.kt` — plain JVM JUnit 4 (no Robolectric, no new libraries; uses the VM test seams `primeForTest` + `testFileMirror` added for it, and the existing `unitTests.isReturnDefaultValues` setup). 16 tests:

Mode state

1. `opensInViewMode` — document opens in View Mode, not dirty, nothing undoable.
2. `setEditModeTogglesBothWays` — View → Edit → View round trip.
3. `setEditModeRefusesNonEditableDocuments` — entering Edit refused when the writable-file half of `editable` is false.

Blocked in View Mode / allowed in Edit Mode (each blocked half asserts the document object is unchanged via `dirty`/identity, the allowed half asserts the real result):

4. `typingBlockedInViewModeAllowedInEdit`
5. `tableCellTypingBlockedInViewModeAllowedInEdit`
6. `enterKeyBlockedInViewMode` — `split` returns null in View; correct "be"/"ta" split in Edit.
7. `findAndReplaceBlockedInViewModeAllowedInEdit` — `replaceAll` + `replaceOne` blocked in View, work in Edit; also covers undo restoring "alpha".
8. `formattingBlockedInViewModeAllowedInEdit` — formatting through `replace` blocked in View, committed in Edit.
9. `undoRedoBlockedInViewModeStillCorrectInEdit` — View-mode undo/redo are no-ops; Edit-mode undo/redo restore the exact texts. (Covers "undo/redo mutate nothing while viewing".)

Comments (Bug 2 contract):

10. `addCommentBlockedInViewMode` — anchored and caret-less requests both rejected; comment part and doc untouched.
11. `deleteCommentBlockedInViewMode` — the anchored note survives.
12. `addCommentWithoutCaretAnchorsToFirstEditableParagraph` — the Bug-2 fix contract: `addComment(null, …)` anchors the first editable paragraph.
13. `blankCommentsRejected` — empty and whitespace-only drafts rejected, nothing dirty.
14. `multipleCommentsGetDistinctIdsAndBothList` — two adds, distinct ids, both listed.
15. `returningToViewModeRelocksEverything` — after Edit closes, typing/Enter/add/delete/replace/format are all rejected again (`doc` object identical).
16. `viewingOperationsRemainAvailableInViewMode` — anchored comments list, paragraph lookup and clean `dirty` state read fine while viewing.

Screen-level gates (button visibility/disabling) are enforced in `DocxEditorScreen.kt` and verified by source review + CI lint; they are not Compose-unit-testable without Robolectric (absent by design, no new libraries allowed). Writer persistence (add → save → reopen, byte preservation) remains covered by the pre-existing `DocxCommentsTest` (untouched, CI-green).

## CI runs on this phase (all on `release/hmxpdf-2.1.1-build`)

| Run | Mostly about | Outcome |
|---|---|---|
| 41102201527 | first push of fixes + tests | FAIL — compile: the mode property's generated private setter clashed with `setEditMode(Z)` (same JVM signature). Fixed by a private backing `mode` state behind a read-only `val editMode` (commit `66acb46`). |
| 41103829284 | clash fix | FAIL — 10 of the 16 mode tests failed; every test whose path requires `setEditMode(true)` no-oped. |
| 41106296206 / 41108304610 / 41110252484 | probe runs (temporary `EditableProbeTest`, one editable term per line) | isolated the false half: `writable` true, VM `pkg` identical, mirror true — `editable` still false. |
| 41112446418 | **root-cause fix** | **GREEN** — details below. |

**Root cause of the 3 test-failure cycles:** Kotlin parses `testFileMirror ?: meta.fileUri?.scheme == "file"` as `(testFileMirror ?: meta.fileUri?.scheme) == "file"` (elvis binds tighter than `==`, and `==` compiles between any types). With the test seam `testFileMirror = true` the expression became `true == "file"` → `false`, so the VM considered every primed document read-only in tests. The app path (`null ?: "file" == "file"`) was unaffected. Fix: parenthesize the boolean half — `(testFileMirror ?: (meta.fileUri?.scheme == "file"))` (commit `aea0431`); the probe class was removed with the mystery.

## Final green run 41112446418 (HEAD `aea0431`)

- `build` job ✓ —
  - `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test --rerun-tasks` — **BUILD SUCCESSFUL**; no failing-test lines were printed (Gradle prints failing tests only; the failing runs above show that format). The suite is 210 unit tests — the same set as the failing runs (210 completed, 10 failed, 44 skipped there) with the probe class removed. The 44 skipped are the pre-existing environment-gated tests in the `pdf-core`/`ocr-core` modules, unchanged; they are counted as skipped, not passed.
  - `:app:lintPlayDebug` — passes with the project's pre-existing advisory noise: *Lint found 8 warnings (72 errors, 323 warnings and 8 hints filtered by baseline lint-baseline.xml)* — identical structure to Phase 1 (dependency-flash advisory + baseline drift notes), nothing from the phase-1.1 changes.
- `release-guard` job ✓ — signing-secret guard passed; no secrets exposed anywhere.
- `release` job ✓ — signed release APK and bundle built and verified (`Build signed release APK`, `Verify every release APK is signed with the release key`, `Verify release bundle is signed and not debug-signed`), artifacts uploaded:
  - `hmx-pdf-debug-apk`
  - `hmx-pdf-release-apk`
  - `hmx-pdf-release-bundle`

## Checklist results

| Phase-1.1 requirement | Result |
|---|---|
| Document opens in View Mode | ✓ test 1 |
| Search unavailable in View Mode (UI entry moved to Edit-only; find bar cannot open while viewing) | ✓ screen change + tests showing all view-mode mutations rejected |
| Find & Replace blocked in View Mode, working in Edit Mode | ✓ tests 7, replace row additionally `editMode`-gated |
| Comment creation/deletion blocked in View Mode | ✓ tests 10, 11 |
| Text/table-cell edits blocked in View Mode | ✓ tests 4, 5 |
| Formatting & undo/redo blocked in View Mode | ✓ tests 8, 9 |
| Editing actions available in Edit Mode | ✓ tests 2, 4–9, 12, 14 |
| Return to View Mode re-locks immediately | ✓ test 15 |
| Viewing ops (read/scroll/goto/zoom/print/share/export, comments list) intact | ✓ tests 16 + reading chrome/`requestBack` unchanged |
| Valid comment text accepted & anchored without a caret session | ✓ test 12 |
| Empty/whitespace comments rejected | ✓ test 13 |
| Multiple comments, unique ids | ✓ test 14 |
| Save/reopen comment persistence | ✓ pre-existing `DocxCommentsTest` (add→save→reopen, unreferenced-part resurrection, byte preservation) green in CI |
| Unrelated DOCX content preserved | ✓ writer untouched; `DocxRoundTripTest`/`DocxTableCellEditTest` suites green in CI |

## Known limitations

- The UI-level hiding (not just VM guards) for the exact "search button" placement is a one-button move inside the existing header; grouping verified by source review, since a Compose UI unit harness is out of scope (no Robolectric by design).
- After process death the editor reopens in View Mode with the last saved state (screen-local `editMode` no longer survives; edits never saved were lost with the process even before this phase).
- The temporary diagnostic probe class exists only in the run history (commits `01a3ff1`, `93fd6e5`, `1eb6c14`) and is not in the final tree.
