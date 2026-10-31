# HMXPDF — Phase 2 Main Release Report

Date: 2026-10-31 · Release manager: automated Git release operation · Status: **PUBLISHED & VERIFIED**

1. **Repository URL**: https://github.com/bharti1233/Hmxpdf.git (owner `bharti1233`; no pushes to HMXPANEL/HMXPDF).
2. **Source testing branch**: `release/hmxpdf-2.1.1-build`.
3. **Target main branch**: `main` — did not exist locally or remotely before this operation.
4. **Phase 2 verified source commit**: `de40322de6715bb9e816120a42a569a0677b873f` (matches release-candidate ancestry `d1652dd..de40322`: `cc421ea`, `0097fa9`, `14730bc`, `f1551ce`, `8fa507b`, `2585085`, `27a3e6c`, `f775515`, `8431130`, `537ac3d`, `c99d3bc`, `de40322`).
5. **Main previous HEAD**: N/A (initial branch creation; nothing overwritten, no invented history).
6. **Main final HEAD**: `de40322de6715bb9e816120a42a569a0677b873f` — independently re-fetched via `git fetch` + the GitHub API after publish (identical to the verified candidate: `git diff origin/main origin/release/hmxpdf-2.1.1-build` is empty).
7. **Publication method**: **initial branch creation** (Case C) via refspec push `release/hmxpdf-2.1.1-build:refs/heads/main` — no merge commit, no force, no history rewrite, no extra commits.
8. **GitHub Actions verification run**: [41214838589](https://github.com/bharti1233/Hmxpdf/actions/runs/41214838589) — `completed success` at exactly `de40322`; jobs `build` (unit tests `testPlayDebugUnitTest :pdf-core:test :ocr-core:test`, `lintPlayDebug`, `assemblePlayDebug`, `assemblePlayRelease`), `release` (artifact upload), `release-guard` (rejects unsigned release tasks) — all green.
9. **Release artifacts**: [debug APK](https://github.com/bharti1233/Hmxpdf/actions/runs/41214838589/artifacts), `hmx-pdf-release-apk` (signed), `hmx-pdf-release-bundle`, `hmx-pdf-debug-apk`.
10. **Git history verification**: remote refs listed via API + `ls-remote` pre-publish (only `release/hmxpdf-2.1.1-build` at `de40322`); post-publish `origin/main` = `de40322`; testing branch unchanged; untracked user items (`Word_16.0.20618.20008.apks`, `docs/word-competitive-analysis/`) preserved, never staged; no `reset --hard`, no `clean`, no force-upload used.
11. **Testing branch synchronization**: local HEAD = `origin/release/hmxpdf-2.1.1-build` = `de40322`; no uncommitted tracked changes introduced.
12. **Final active branch**: `release/hmxpdf-2.1.1-build`.
13. **Current default branch**: `release/hmxpdf-2.1.1-build` — **not changed** (re-verified via API after publish; no default-branch setting touched).
14. **Limitations / warnings**: `main` was newly created (it did not exist), so "fast-forward" did not apply; the repository default branch remains the testing branch by deliberate non-action; Phase 2's documented feature limitations are listed in `docs/phase2/PHASE2_TEST_REPORT.md`.
