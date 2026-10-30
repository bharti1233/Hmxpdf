# PHASE 1 TEST REPORT — HMXPDF DOCX (P0-1 / P0-2 / P0-3)

**Stage D deliverable.** Companion to `PHASE1_SOURCE_AUDIT.md` (evidence model: **[S]** source-reviewed, **[T]** confirmed by tests, **[N]** not verified, **[R]** requires runtime device).

## Stage G outcome — CI verified (2026-10-30)

**Final green run: [41079865164](https://github.com/bharti1233/Hmxpdf/actions/runs/41079865164) on commit `f43de2c`, all three jobs success** (`release`, `release-guard`, `build` — tests + lint + packaging). Artifacts: [hmx-pdf-debug-apk](https://github.com/bharti1233/Hmxpdf/actions/runs/41079865164/artifacts), hmx-pdf-release-apk, hmx-pdf-release-bundle. The JVM unit-test task ran with `--rerun-tasks`; no test failure ("194 tests completed" corresponds to the `:app:testPlayDebugUnitTest` run family; skipped counts come from parameterised/preview-compatible omits, unchanged from previous runs).

Everything above marked "not run locally; runs in CI" has now run in CI and passed: the pre-existing DOCX suites stayed green, and all 20 new tests (8 find/replace + 8 comments + 4 table-cell) passed. Same run: `lintPlayDebug`, `assemblePlayDebug`, `assemblePlayRelease`, `bundlePlayRelease`, and the release signing guard all succeeded.

### What CI found before it went green (fix history on this branch)
| Run | Result | Cause → fix |
|---|---|---|
| 41045041243 | failure | 6 compile errors in new code: four importable-rounded icon properties (`RateReview`/`FindReplace`/`DoneAll`/`Delete`) lacked per-symbol imports; comment payload `DocPara` missed the required `editable` flag → commit `1ed538a` |
| 41045825562 | failure | test passed a `ByteArray` where `DocxReader.read` needs an `InputStream` → commit `a160d55` |
| 41046882481 | failure | tests all compiled and ran; 9 failed. Root causes: `DocxComments` parsed comments.xml at the wrong tree level (`XmlScan.scan` returns a pseudo-root above `w:comments`, so items parsed empty and `remove()` was a silent no-op) → `partRoot()` navigation, commit `a139904`; plus two wrong hand-computed test expectations (inclusive `IntRange` from `i until end`; `replaceRange`'s `[start, end)` semantics). Verified locally without gradle by hand-simulating the scanner and edit math |
| 41077288344 | failure | JVM tests all green; `lintPlayDebug` blocked on the Phase-1 diff itself: `java.time.Instant#now` needs API 26 (app min is 23, desugaring deliberately off) → switched to the codebase's `SimpleDateFormat` pattern at a fixed US locale; all 10 new strings lacked translations in ar/ru/pt/it/es (pt-rBR added too) → translated; `docx_match_case` was dead (the Aa switch is its own visible label) → deleted → commit `f43de2c` |
| 41079865164 | **success** | — |

The tests earned their keep beyond presence checks: they caught a real engine bug (the empty-comments-part parse), which would otherwise have shipped silently.

## Environment limitation (explicit)

**Local Unit tests were NOT executed. The user instructed (2026-10-30): "dont run or build apk locally my device is low".** All gradle jobs — including `testPlayDebugUnitTest`, `:pdf-core:test`, `:ocr-core:test`, `lintPlayDebug`, and any APK/AAB packaging — are therefore left entirely to the GitHub Actions `android.yml` run (Stage G), which is the declared build/test authority for this project anyway.

## What was done locally in its place (static, near-zero memory)
- Full source re-read of every changed/new file (Stage E).
- Component contract verification against the installed dependency artifacts: `Icons.Rounded.FindReplace`/`DoneAll`/`RateReview` confirmed in `material-icons-extended` 1.7.6; `Rounded.Delete`/`Search` confirmed in `material-icons-core`; `GlassDialog(title, actions, content)`, `LiquidIconButton(onClick, backdrop, modifier, surfaceColor)`, `GlassSearchPill(...)`, `rememberAdaptiveInk().color/.soft/.modifier` signatures verified in source.
- Kotlin brace/paren balance scan of the changed screen file: balanced.
- No test is hidden: every test in the inventory below is marked "not run locally; runs in CI".

## Test inventory and status

### Existing suites (regression baseline — must stay green in CI)
| Test class | Tests | Status |
|---|---|---|
| `DocxRoundTripTest` | 11 | not run locally; runs in CI [N→CI] |
| `DocxParagraphLayoutTest` | 7 | not run locally; runs in CI [N→CI] |
| `DocxTableLayoutTest` | 3 | not run locally; runs in CI [N→CI] |
| `DocxXmlTest` | 2 | not run locally; runs in CI [N→CI] |

### New: Find & Replace — `app/src/test/java/com/hmx/pdf/docx/DocxReplaceTest.kt` (8 tests)
| Test | Covers (master-prompt requirement) | Status |
|---|---|---|
| `findsCaseVariants` | case-sensitive / case-insensitive matching | not run locally; runs in CI |
| `matchNeverIncludesAnObjectChar` | OBJ-pasted-query guard (cross-run handling deliberate: objects are excluded) | not run locally; runs in CI |
| `replacesOneRunKeepingItsFormat` | one replacement; mixed formatting rule = first replaced run's format | not run locally; runs in CI |
| `replacesAllRangesRightToLeft` | many replacements in one paragraph, format per-range | not run locally; runs in CI |
| `noRangesMeansUnchanged` | no matches ⇛ zero mutation (atomicity) | not run locally; runs in CI |
| `replacementsWithSpacesAndPunctuation` | replacement text with spaces + punctuation | not run locally; runs in CI |
| `pageMarksShiftWithTheEdit` | page marks move with replaced text (no stale positions) | not run locally; runs in CI |
| `replacingNeverRewritesUntouchedParagraphs` | unaffected paragraph byte preservation through the writer | not run locally; runs in CI |

### New: Comments — `app/src/test/java/com/hmx/pdf/docx/DocxCommentsTest.kt` (8 tests)
| Test | Covers | Status |
|---|---|---|
| `readsCommentsAndAnchors` | read existing comments + anchors + reference run | not run locally; runs in CI |
| `addSavesReopens` | add → save (rels + Content_Types when part absent) → reopen | not run locally; runs in CI |
| `deleteCleansElementAnchorsAndReference` | delete = element + markers + reference, atomically | not run locally; runs in CI |
| `undoneDeletionKeepsItsElement` | delete→undo→save writes the element back (no orphan reference) | not run locally; runs in CI |
| `twoAnchorsInOneParagraphLookIndependent` | multiple comments, strip-one-independence | not run locally; runs in CI |
| `unknownIdsAreNoOps` | missing/invalid comment id tolerance | not run locally; runs in CI |
| `writeOnlyWhenTouched` | part byte-identical when the editor does not touch comments | not run locally; runs in CI |
| `unrelatedTextEditKeepsCommentsPartBytes` | comments preserved across unrelated text edits | not run locally; runs in CI |

### New: Table cell editing — `app/src/test/java/com/hmx/pdf/docx/DocxTableCellEditTest.kt` (4 tests)
| Test | Covers | Status |
|---|---|---|
| `severalCellsEditInOneSave` | multiple edited cells in one save | not run locally; runs in CI |
| `typingIntoAnEmptyCellWorks` | empty cells | not run locally; runs in CI |
| `editedCellsLeaveOtherTablesByteIdentical` | untouched table preservation next to edits | not run locally; runs in CI |
| `multiParagraphCellKeepsItsSiblingParagraph` | multi-paragraph cells | not run locally; runs in CI |
P0-3 verdict (audit §4.4): functionality already existed `[S][T]`; no duplication — these tests extend coverage only.

## Document-integrity validation (design-level, verified statically [S]; enforced by tests in CI)
- Untouched paragraphs byte-identical: writer's `origin` splice path untouched; tests assert.
- Unrelated zip parts stream unchanged: writer `parts()` only replaces styles/numbering/document — **and now comments only when `DocxComments.modified`**.
- Comments survive save/reopen: anchor markers ride `DocPara.extras` verbatim; element raw XML preserved or reconstructed.
- Relationships: comments part rel added only when absent (numbering pattern);
- Undo/redo integrity: each replace/comment op = one `commit()` (map-bulk path) — snapshot semantics unchanged; delete→undo resurrect logic prevents orphan references.

## Regression findings during review (fixed before commit)
1. `addComment` performed the part mutation before confirming the target paragraph is editable (tracked-changes para): fixed — editable check before `cm.add`.
2. Deleting a same-session-added comment did not archive its element for the undo-resurrect path: fixed — archive on `remove`.
3. `matchesIn` shared matcher now also guards `replaceRange` (belt: the op re-checks its own range).

## Requires runtime/device testing (`[R]`, deferred — not Phase-1 blockers to code, may need Phase-1 follow-ups)
- Table-cell caret placement/focus UX on device (model+writer confirmed [S][T]; UI reachability static-verified only).
- Replacing inside a live selection whose paragraph is mid-typed (1.5 s coalescing window).
- Comments dialog on a rotated/folded layout (`GlassDialog` used unchanged).
- Find & Replace in a document opened with pending Save Recovery.

## Command to be run by CI (for the record)
`./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test lintPlayDebug assemblePlayDebug assemblePlayRelease bundlePlayRelease` (via `.github/workflows/android.yml`).
