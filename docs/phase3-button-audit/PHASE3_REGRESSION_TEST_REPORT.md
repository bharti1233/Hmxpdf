# HMXPDF — Phase 3 Regression Test Report

Branch `release/hmxpdf-2.1.1-build` @ `60b8218` (baseline CI: run `41328856513`, green). Companion: `BUTTON_ISSUE_REGISTER.md` (per-issue status), `DOCX_PAGE_SETUP_AUDIT.md` (root causes), `PHASE3_BUG_FIX_REPORT.md`.

## 1. Where the tests run

| Environment | Result |
|-------------|--------|
| GitHub Actions (push to this branch, workflow `android.yml`) | **Executor of record.** Jobs: `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test`, `lintPlayDebug`, `assemblePlayDebug`. The new JVM tests in this wave run in `testPlayDebugUnitTest`. **A run with these tests has not been published yet — commit+push await explicit user authorization; the run URL and per-job results will be added to `PHASE3_BUG_FIX_REPORT.md` right after the authorized push.** Baseline at `60b8218` was green before any of these changes. |
| This Android device (Termux/Gradle) | **Not runnable.** Attempt performed per workflow: `sh gradlew testPlayDebugUnitTest --offline` with JDK 17 (`/usr/lib/jvm/java-17-openjdk-arm64`) — Gradle configured and ran for ~3 min, then **failed at SDK resolution** (`SDK location not found… define ANDROID_HOME / sdk.dir`). No Android SDK exists anywhere on this device (searched `ANDROID_HOME`, `/home`, `/opt`, `/mnt/sdcard/Android`, `HMX-Build-Engine`, `android-code-studio-dev`). Per the task rules, no APK/AAB build was attempted locally at any point. Static self-review (diff re-read, call-site greps) is documented in the bug-fix report instead. |

## 2. Tests added or changed in this wave

### A. Engine (`app/src/test/java/com/hmx/pdf/docx/`)

| Suite | Count | Change |
|-------|-------|--------|
| `DocxPageLayoutTest` | 20 @Test (was 18) | `sectionBreakInsertCarriesTypeBeforeTargetParagraph` — which **locked the wrong R1 behavior** — was rewritten as `sectionBreakTypeStampsTheFollowingSectionNotTheBoundary` (boundary = unmodified byte-copy, type lands on the *following* terminator, 2 sections survive save/reopen) + new `sectionBreakNextPageSplitsSectPrlessDocumentWithoutStamping` (null type: no `w:type` anywhere, sectPr-less docs still split in two) + new `writerRelWithIdDedupesSameRelationshipAndRefusesCollisions` (idempotent same-relationship re-add; conflict ⇒ `IllegalStateException`, source part untouched). All pre-existing kernel tests retained unchanged. |
| `DocxReplaceTest` | 9 @Test (was 8) | New `staleRangeAgainstShrunkParagraphNoOpsInsteadOfCrashing` — stale collected range against a shrunk paragraph no-ops (same instance) instead of throwing; partially clipped ranges replace the valid remainder. |

### B. ViewModel (`app/src/test/java/com/hmx/pdf/ui/viewmodel/`)

| Suite | Count | Coverage |
|-------|-------|----------|
| `DocxPageSetupViewModelTest` (**new file**) | 7 @Test | First Page-Setup VM-state suite in the repo. Covers: View-Mode rejection of every setup op; read-only refusal (`testFileMirror = false`); Edit-Mode apply + dirty + `livePage()` follows the document's sectPr; **stale-capture rebase** (same `Section` object: landscape then different-first-page — both survive); even/odd toggle cycle returns to clean (incl. settings-less package ⇒ no empty `<w:settings>` shell); `recordSaved` restores the dirty baseline; section-break targeting (table-cell refusal, body-level apply, 2 sections, no relocation) and repeated `hfCreate`s never share a relationship id (also past pre-existing `rId1`/`rId8`), orphan purge keeps the sibling variant. |
| `DocxEditorViewModeTest` | 16 @Test | Unchanged (Phase 1.1 contract still guards every mutation behind `editMode`). |

### C. Engine suites executed unchanged this wave (regression protection)

`DocxCommentsTest`, `DocxParagraphLayoutTest`, `DocxReplaceTest` (pre-existing cases), `DocxRevisionsTest`, `DocxRoundTripTest`, `DocxTableCellEditTest`, `DocxTableLayoutTest`, `DocxXmlTest` — all left untouched; CI runs them as-is. `DocxPageOptions` value-ordering and byte-preservation invariants are exercised by `DocxPageLayoutTest`/`DocxRoundTripTest` on save→reopen inside the new tests as well.

## 3. Executed / passed / failed / skipped

| Item | Status |
|------|--------|
| **Executed & passed** | 0 — no JVM runner exists on this device (§1). Nothing was executed locally; **nothing is claimed as run-and-passed.** |
| **Skipped locally, scheduled for CI** | All suites above (new: 11 cases across 3 suites; retained: full module test scope). |
| **Failed** | None known; the baseline CI job at `60b8218` was green and no pre-existing assertion was deliberately changed except the two test elements noted in §2-A. |
| **Instrumented UI tests** | **Not created** — the project has no `androidTest` source set, no connected-device harness exists on this device, and CI (ubuntu-latest) has no emulator step. Adding a green-on-paper UI suite that nothing can execute would violate the task's "do not count skipped tests as passing". The strongest feasible automated substitute (JVM ViewModel-state tests) is §2-B; genuine interaction checks are enumerated as device-verification items in `PHASE3_BUG_FIX_REPORT.md` §Device checks. |
| **Static review substitute** | Every changed file's diff was re-read after each edit (Screen: the 1093 mis-edit was repaired and the diff verified empty before resuming; kernel/VM/writer diffs re-read end-to-end; string files verified present in all 7 locales). |

## 4. Not covered by tests — and why

| What | Why | Compensating control |
|------|-----|----------------------|
| `save()` guard emitting `Failed` without composing a second save | Requires an Android `Context` + a real `CoroutineDispatcher` (no JVM seam exists; `meta.saving` is private) | Inspection-only fix (2 lines, contract documented in the register A5) |
| Preview geometry re-render (PS-04), preset highlight (PS-08), gear enablement (PS-05), danger-tint fields (PS-09), toggle dimming | Compose UI — no instrumented runner in this environment | Device checks in the bug-fix report; VM-side state changes those visuals mirror are test-covered |
| Word's on-disk rendering of continuous/even/odd breaks (DV-1/2/3), comma-locale keyboard (DV-5) | Word/device behavior is the ground truth | Unchanged DV checklist in `DOCX_PAGE_SETUP_AUDIT.md` §6; R1/R3 fixes make the emitted OOXML spec-correct on paper before device exposure |
| Per-section render geometry (PS-07) | Deliberate limitation this wave | Documented in register + bug-fix report |

## 5. What counts as "green"

The acceptance criterion "Phase 1/1.1/2 tests remain green" is *verified by CI after the authorized push*; this report preserves the pre-push truth: **nothing ran yet, nothing passed yet.** The two intentionally removed assertions (`sectionBreakInsertCarriesTypeBeforeTargetParagraph`'s wrong-type check) are substitutions locking the corrected ECMA-376 semantics, not skipped failures.
