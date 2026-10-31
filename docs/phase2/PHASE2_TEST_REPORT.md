# HMXPDF — Phase 2 Test Report: DOCX Track Changes, Review, Accept & Reject

Date: 2026-10-31 · Branch: `release/hmxpdf-2.1.1-build` · Head: `c99d3bc3bbbe9c5c436a744f3cc172ad556df897` · Final CI run: **41213743269**

Legend: **[T]** verified on CI as part of this phase · **[C]** verified by reading the code at this head · **[L]** known limitation, accepted and documented.

---

## 1. How verification worked [T]

As with Phase 1, all verification ran on GitHub Actions (`.github/workflows/android.yml`) — the same workflow that guards `main`. No local APK or Gradle build was executed. Unit tests run in the `build` job via `testPlayDebugUnitTest :pdf-core:test :ocr-core:test`; `assemblePlayDebug` and `assemblePlayRelease` produce the debug APK and signed release artifacts; the `release-guard` job rejects any release task that runs without signing.

- Six runs cover the whole phase; the first three failure logs each had exactly one root defect (no cascades). Fix-up history:
  - Run 41173260920 — `DocxLook.kt` missing `import …Rev`; fixed in `27a3e6c`.
  - Run 41173573165 — JVM platform declaration clash between the `tracking` property's generated setter and `fun setTracking(…)`; fixed in `f775515`.
  - Run 41211255995 — invalid `const val` in `DocxRevisionsTest.kt` fixture; fixed in `8431130`.
  - Run 41212020365 — pre-existing `DocxRoundTripTest` asserting the old "w:ins ⇒ read-only" contract; updated to the Phase 2 contract in `537ac3d` (assertions only; no test added or removed).
  - Run 41213044213 — lint `NewApi`: `HashMap.putIfAbsent` needs API 24 (minSdk 23) in `DocxRevisions.pending`; fixed in `c99d3bc`.
- Final green run covering the complete Phase 2 head: **41213743269** (conclusion `success`, head `c99d3bc`).

## 2. What was executed [T]

| Task | Where | Result |
|---|---|---|
| `testPlayDebugUnitTest` | CI `build` job | 243 app tests: 0 failed, 44 skipped |
| `:pdf-core:test`, `:ocr-core:test` | CI `build` job | succeed |
| `assemblePlayDebug` | CI `build` job | succeed — artifact `hmx-pdf-debug-apk` |
| `assemblePlayRelease` | CI `build` job | succeed — artifacts `hmx-pdf-release-apk`, `hmx-pdf-release-bundle` |
| `:app:lintPlayDebug` | CI `build` job | succeed |
| `release-guard` | separate job | succeed |

The app-module counts are the actual numbers Gradle reported on run 41212020365 ("243 tests completed, 1 failed, 44 skipped") adjusted by the one assertion-only fix (`537ac3d`) — no test was added or removed after that counted run, so the identical 243-test set ran green with the failure replaced by a pass.

## 3. Test counts

- App module: **243** tests — **0 failed, 44 skipped** (skip-by-annotation legacy Android-dependent tests, pre-existing).
- pre-Phase-2 baseline in the same module: **217** tests, all green in the same run — no app regression in comments, tables, Find & Replace, undo/redo, or mode guards.
- New in Phase 2: `DocxRevisionsTest` — **17** tests; `DocxTrackingStateTest` — **9** tests.

## 4. Feature behaviors proven by the new tests [T]

`DocxRevisionsTest` (kernels + reader + writer round-trip):
1. Document with a tracked insertion reads, shows underlined annotation spans, accepts to clean text, rejects to the pre-insertion text.
2. Document with a tracked deletion reads with the deleted text visible (strikethrough spans), accepts by removing the text, rejects restores per-run format (bold deletion survives).
3. Multi-revision document: per-revision accept/reject is independent (accept one of two insertions leaves the other).
4. Accept-all / reject-all return the document, tracked semantics aside, to clean state.
5. Round-trip: read → tracked edit → save → re-read shows the same revisions with author/date preserved; a tracked save does not corrupt unrelated package entries.
6. Excerpt building (panel rows) and span extraction agree with the model flags.
7. Revision id arithmetic: `seed` from parsed ids, monotonic `nextId()`.
8. Missing optional metadata (`w:id` only, no author/date) parses safely to empty strings, paragraph stays editable.
9. Unmodelable wrapper content (hyperlink inside `w:ins`) falls back read-only and keeps original XML on save — no data loss.

`DocxTrackingStateTest` (ViewModel state machine):
1. Tracking defaults off every session.
2. `setTracking(true)` refused while not in Edit Mode or when the doc is not editable; `false` always allowed.
3. Enabling works in Edit Mode; leaving Edit Mode does not silently disable tracking (revisions survive).
4. `capture()` returns `null` with tracking off (plain path byte-exact) and a `(revId, RevMeta)` pair when on.
5. Typing coalescing: one revision id per typing spurt within the undo window; a later burst and fresh-key ops (`freshKey()`) start new revisions.
6. Disabling tracking never auto-resolves pending revisions.

## 5. Manual-path coverage [L]

CI executes the unit suites and builds both artifacts; interactive behaviors (dock button, Revisions panel rendering, caret reconciliation on the field buffer) are covered by unit assertions at the ViewModel/screen-seam level and were not exercised on a device in this phase. The panel and toggle rows reuse the same `GlassDialog`/`LiquidToggle` conventions Phase 1 verified.

## 6. Known limitations (accepted, documented)

- **[L]** `w:moveFrom`/`w:moveTo` are not presented as Track Changes revisions (Word move-tracking); they load/edit as ordinary or read-only content as before Phase 2.
- **[L]** Paragraph-mark revisions (`w:pPr/w:rPr/w:ins|w:del`) are preserved verbatim in the paragraph's `pPr` and re-emitted; they are not resolvable as per-revision rows (accept/reject works on text, not the pilcrow mark).
- **[L]** Multi-line paste while tracking on produces revisions per `typed` call; the caret approximation mirrors pre-Phase 2 paste behavior.
- **[L]** Field-buffer nuance: when tracking is on, `typed()` returns the model caret (-1 on the plain path) so the screen re-syncs the field buffer via `activate(cur.id, caret)`; intentional, covered by the ViewModel tests.

## 7. Regression evidence [C/T]

- Full range diff `d1652dd..c99d3bc` touches only: the DOCX engine files, the DOCX editor screen/look/viewmodel, string resources ×7 locales, two test files (one updated, one new), and two phase-2 docs. No gradle, no signing, no other-feature files.
- Pre-existing comment, table, Find & Replace, undo/redo, and Phase 1/1.1 view-mode guard tests all pass in the same green run.
- The two intentionally-untracked user items (`Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/`) remain untracked; Phase 2 never staged them.
