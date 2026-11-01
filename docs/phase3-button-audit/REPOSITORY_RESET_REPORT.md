# HMXPDF — Repository Reset Report (Pre Phase-3 Button Audit)

Date: 2026-11-01. Operator contract: delete the rejected `experiment/page-setup-direct-ui` branch (local + GitHub), remove the old experimental project copy, fresh-clone the verified testing branch, verify, and stop before any code change.

## 1. Deleted experimental branch

| Where | Branch | Deleted HEAD | Verification |
|---|---|---|---|
| Local | `experiment/page-setup-direct-ui` | `e53947a30a6d95625d95f5996a344cead07a4aed` | Recorded before deletion; `git branch -D` after switching to `release/hmxpdf-2.1.1-build`; branch list clean. |
| GitHub (`origin`) | `experiment/page-setup-direct-ui` | same | `git push origin --delete` accepted; `git ls-remote` empty; GitHub API `GET /branches/experiment/page-setup-direct-ui` → HTTP 404. |

The branch existed only with these commits (experiment implementation + experiment docs); its deletion removes the rejected implementation from GitHub and all local clones. `main`, the default branch, and `release/hmxpdf-2.1.1-build` were not touched (default branch = `release/hmxpdf-2.1.1-build`, verified via API before any operation). No force-push, no history rewrite, no reset/clean used.

## 2. Preserved unrelated user files

Untracked user items that lived in the old project directory were copied (verified with `diff -r` / `cmp`, byte-identical) to `/mnt/sdcard/AIProjects/HMXPDF-preserved/` before deleting the old directory, then returned **untracked** into the fresh clone:

- `Word_16.0.20618.20008.apks` (131,319,323 bytes)
- `docs/word-competitive-analysis/` (directory)

They are never staged; see §4 for their status in the fresh clone.

## 3. Fresh clone

| Item | Value |
|---|---|
| Clone path | `/mnt/sdcard/AIProjects/HMXPDF-phase3-testing` |
| Remote URL | `https://github.com/bharti1233/Hmxpdf.git` |
| Checked-out branch | `release/hmxpdf-2.1.1-build` (explicit `--branch`, not `main`, not the experimental branch) |
| HEAD (local origin-fetched equality) | `d3d8dc27a61b8491eb86659a2c54e50cf898e868` ← `refs/heads/release/hmxpdf-2.1.1-build` (matches `ls-remote` byte-for-byte) |
| Branch history tail | `d3d8dc2` docs: phase 3 test report + final review · `567414c` page-break run fix · `d5755af` · `c6b8270` (CI compile fixes) |
| Git status immediately after clone | **clean (empty)** |

## 4. Verification results of the fresh clone

- **Experimental implementation absent:** no `docs/experimental-page-setup/`; `pgGuides|applyScope|PgGuide|scopeToast` → 0 hits in `DocxEditorScreen.kt`/`DocxEditorViewModel.kt`; experiment strings (`docx_scope_selected`, `docx_guides_done`) → 0 hits in `strings.xml`.
- **Original Phase 3 Page Setup present:** `setSectionSetup` (VM), `validMargin`/`validDim` (`docx/DocxPageLayout.kt` kernels), `DocPanel.LAYOUT` glass-dialog panel in `DocxEditorScreen.kt`, plus `docx/DocxPageLayoutTest.kt` and the full `docx/` test tree.
- **Phase 1 / 1.1 / 2 present:** Track Changes (`w:ins`/`w:del` in `DocxModel.kt`/`DocxReader.kt`/`DocxWriter.kt` + `DocxRevisionsTest.kt`), comments (`DocxCommentsTest.kt`), page/section breaks (`insertPageBreak`/`insertSectionBreak` in the VM), header/footer create/remove (`hfCreate`/`hfRemove`), tables (`DocxTableCellEditTest.kt`, `DocxTableLayoutTest.kt`), round-trips (`DocxRoundTripTest.kt`), 20 test suites under `app/src/test/`.
- **Post-restore status:** after returning the preserved user files from §2 into the clone, they appear as the only untracked entries in `git status` (same arrangement as before the reset; never staged).

## 5. Standing protection contract

- No pushes to `main`; no merges into `main`; default branch unchanged.
- `release/hmxpdf-2.1.1-build` (the default branch) is the working branch for upcoming fixes; it is never deleted, never force-pushed.
- The original `HMXPANEL/HMXPDF` repository is not referenced or modified; `origin` is `bharti1233/Hmxpdf` only.

## 6. Next task (handed to the following instruction)

The clean clone at `HMXPDF-phase3-testing` is ready for the comprehensive Phase 3 Page Setup button/controls audit (identify non-functional buttons/actions, fix on `release/hmxpdf-2.1.1-build`). No code was modified in this reset.
