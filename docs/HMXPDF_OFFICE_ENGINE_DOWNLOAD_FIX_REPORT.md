# Office Engine Download Reliability Fix Report

**Date:** 2026-10-30 · **Based on:** `docs/HMXPDF_OFFICE_ENGINE_DOWNLOAD_AUDIT.md`
**Scope:** state-surface + storage-preflight + worker progress fixes only. The download,
verify, and atomic-install machinery itself was already sound and is unchanged except for
one extra progress emission and a clearer disk-full error. The eight fixes from the
previous deep audit are untouched. Nothing committed or pushed.

---

## 1. Original root cause (from the audit, confirmed)

`DownloadOfficeEngineInstaller.map()` mapped **every** `ENQUEUED`/`BLOCKED` WorkManager
row to `Downloading(0, …, waitingForNetwork = true)` → the UI showed "Waiting for
network…" regardless of the real deferral reason. The request also carried an invisible
second constraint, `setRequiresStorageNotLow(true)`, so a storage-low device waited
**indefinitely on storage** while the UI blamed the network. On top of that, the app had
no connectivity knowledge at all (zero `ConnectivityManager` usage),
`ExistingWorkPolicy.KEEP` silently swallowed repeated Download taps, and the only
affordance was a small Cancel pill. Intermittency came from OS deferral (Doze / standby
buckets / battery savers), which the UI could never explain.

## 2. Changed files

| File | Change |
|---|---|
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineStateMapper.kt` | **NEW** — pure, JVM-tested state mapping + connectivity level + `requiredFreeBytes()` budget |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineInstaller.kt` | Sealed state model extended: `CheckingConnection`, `WaitingForNetwork`, `Queued`, `InsufficientStorage(requiredBytes)`, `Verifying`; `Downloading` dropped the always-wrong `waitingForNetwork` flag |
| `app/src/main/java/com/hmx/pdf/office/DownloadOfficeEngineInstaller.kt` | Rewritten mapper wiring: `ConnectivityManager`/`NetworkCapabilities` probe, visible storage preflight replacing `setRequiresStorageNotLow(true)`, preflight-hold flag, KEEP kept for dedup |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineDownloadWorker.kt` | New `PHASE_VERIFY` progress emission before SHA-256 check; ENOSPC/"No space left" failures mapped to "Not enough free storage to install the engine" |
| `app/src/main/java/com/hmx/pdf/ui/screen/OfficeEngineSettingsSection.kt` | Render all new states (Cancel/Retry affordances), 5 s re-probe loop while a pending state is on screen |
| `app/src/main/res/values/strings_office_engine.xml` (+ `values-ar/es/it/pt/pt-rBR/ru`) | New strings for the new states; `office_engine_installing` now "Installing…" (6 locales updated to match; see §7 limitations) |
| `app/src/test/java/com/hmx/pdf/office/OfficeEngineStateMapperTest.kt` | **NEW** — 17 pure-JVM regression tests |
| `docs/HMXPDF_OFFICE_ENGINE_DOWNLOAD_AUDIT.md` / this report | Documentation |

## 3. Architecture changes

- **State mapping moved to a pure seam.** `OfficeEngineStateMapper.map(state, progress,
  outputError, connectivity, insufficientStorageBytes, startedHere, installedState)` is
  the single source of truth; `DownloadOfficeEngineInstaller` only supplies probe values.
  WorkManager does not expose which constraint deferred a pending row, so pending labels
  come from the app's **own** probes (documented in code):
  - `ConnectivityManager.activeNetwork` → null / no `NET_CAPABILITY_INTERNET` → **Waiting
    for network**; INTERNET but not `NET_CAPABILITY_VALIDATED` → treated as waiting too
    (a captive portal cannot usefully reach the pinned HTTPS release; if the row runs
    anyway it fails fast with a clear error); unknown → **Checking connection**.
  - Space budget → **Insufficient storage (needs %s)**; otherwise → **Queued** (honest
    residual label for backoff/Doze/bucket deferral — never blamed on the network).
- **Storage constraint → visible preflight.** `setRequiresStorageNotLow(true)` is gone
  from the request (network-only constraints). `install()` computes
  `requiredFreeBytes(remaining archive bytes, resumable `<sha256>.part` bytes,
  existing engine tree)` — where the extraction working set is a conservative
  `EXTRACTION_WORKING_BYTES = 256 MiB` constant — and compares against
  `StatFs.freeBytes`. Insufficient space shows a visible state with a Retry pill instead
  of an invisible OS deferral (previously labelled "Waiting for network").
  Double-count guards: resumable parts are counted once (excluded from "existing"
  bytes via the download dir), and the stale staging tree is excluded (the worker wipes
  it before extract).
- **A failed preflight is held** (`storageBlock`) so the InsufficientStorage state
  survives Flow remapping until the next Download/Retry tap re-runs the preflight, or
  uninstall clears it.
- **`ExistingWorkPolicy.KEEP` retained** — per instructions, not blindly replaced:
  KEEP still prevents duplicate downloads (its correct job). What changed is visibility:
  a re-tap on any pending row now lands on the honest state (Checking connection →
  Queued/Waiting/…), the tapping user immediately sees "already queued", and
  Cancel → re-tap remains the explicit restart flow; Cancel→Retry is unnecessary for
  FAILED/CANCELLED rows because those are terminal and KEEP inserts a fresh row.
- **Verifiable phases.** The worker now emits `PHASE_VERIFY` between download and
  unpack, so the UI can honestly show Verifying (previously the SHA-256 check was
  invisible inside the download phase).
- **Process restart.** Unchanged semantics verified: pending rows re-collected by the
  Flow and re-labelled honestly; FAILED rows from before the restart fall back to the
  on-disk state (`startedHere`), and a terminal row lets the next tap create a fresh one.
- **Live re-probe.** The Settings section re-probes every 5 s while a pending state is on
  screen (network return, freed space, scheduler pickup — WorkManager does not re-emit
  on constraint satisfaction, only on row-state changes).

## 4. Before / after behavior

| Situation | Before | After |
|---|---|---|
| Tap Download, network fine, storage fine | "Waiting for network…" (until RUNNING) | "Checking connection…" → "Queued…" → "Downloading…" |
| No network | "Waiting for network…" | "Waiting for network…" (now only when the probe actually says so) |
| Captive portal | "Waiting for network…" forever-or-error ambiguous | Same at tap time; a WM-executed failure surfaces as a clear Failed state |
| Storage low (< budget) | Invisible deferral → "Waiting for network…" indefinitely | "Not enough free space · needs X" + Retry; no download attempts into a full disk |
| Worker hits ENOSPC while extracting | Raw `e.message` e.g. "ENOSPC: No space left on device" | "Not enough free storage to install the engine" |
| SHA-256 verification | Shows "Verifying and installing…" (same state as unpack) | "Verifying archive…" then "Installing…" |
| Doze / scheduler holds the row | "Waiting for network…" — a false label | "Queued · waiting for the device scheduler…" |
| Repeated Download taps while queued | Silently nothing happens | Tap → "Checking connection…" → honest pending state; Cancel pill remains |
| Download failed mid-session | Failed error + Retry | Unchanged (Retry re-preflights then re-enqueues) |
| FAILED row from a previous session | Hidden → reusable install state | Unchanged hidden; terminal row → next tap creates fresh work |

## 5. Tests and results

`OfficeEngineStateMapperTest` (pure JVM, plain JUnit — 17 tests): every required matrix
case — ENQUEUED without network / with validated network / insufficient storage / retry
backoff; BLOCKED both ways; RUNNING download (progress + default), verify, install;
FAILED with and without message; FAILED after restart; CANCELLED after restart; SUCCEEDED;
absent row; already-installed engine pass-through; and the two storage-budget arithmetic
cases (`requiredFreeBytes` sum + clamp).

**Local execution status:** *authored, not locally executed.* This environment (aarch64
Termux) cannot run the AGP resource task that `:app:testPlayDebugUnitTest` depends on —
the x86_64 aapt2 artifact cannot execute on device, so any `:app` JVM test run is
environmentally blocked. The identical tasks run as CI in `android.yml`
(`pull_request` and `main` pushes). Static checks done locally: all construction sites
updated (zero remaining `waitingForNetwork` references), trailing whitespace clean, and
the state model compiled-consumed consistently across installer/section/mapper.

## 6. Remaining limitations

- The only untranslatable-new strings are English-default (4 new strings); 6 locales
  already updated for `office_engine_installing`; the rest fall back to English until a
  localization pass. Missing-translation fallback is standard behavior, no lint blocker.
- `EXTRACTION_WORKING_BYTES = 256 MiB` is conservative (the true extracted tree size is
  not pinned in the manifest); the constant has a `ponytail:` note to shrink it once a
  real measure exists. On a device with < ~256 MB + archive free, the preflight now
  *visibly* refuses instead of silently never bumping that wall.
- Connectivity probe is the *default network*'s; multi-network edge cases (VPN splitting)
  follow the same probe WorkManager uses (INTERNET-capability semantics).
- The storage hold clears on next tap only; it does not auto-resume when space frees
  while the screen is closed. Polling covers the on-screen case.

## 7. Device QA checklist

1. **Basic flow:** Install → observe Checking connection → Queued → Downloading with
   real bytes → Verifying archive → Installing → Installed.
2. **Airplane mode:** airplane ON → tap Download → "Waiting for network…" (+Cancel
   pill); airplane OFF → within seconds auto-transitions to Downloading without
   touching the app (poll + WM start).
3. **Captive portal:** join a portal network, tap Download → expect Waiting/Queued and
   then a quick Failed state with a clear error (never an endless network wait).
4. **Low storage:** fill the device below the budget → tap Download → "Not enough free
   space · needs X" + Retry; free space → Retry → runs.
5. **ENOSPC mid-extract (hard):** impossible to place precisely; verify via logcat that
   a disk-full failure surfaces as Failed("Not enough free storage…") and staging is
   cleaned (no orphan `staging/*`).
6. **Doze / battery:** enable Doze (`adb shell dumpsys deviceidle force-idle`) with a
   pending row → recheck the app → honest Queued label; release idle → download runs.
7. **Process restart:** start download, kill process mid-download, reopen Settings →
   resumes/persists with honest labels; the partial `.part` is preserved.

## 8. Verdict

The confirmed P1 root cause (false "Waiting for network" label + invisible storage
deferral) is fixed at the state-model level and guarded by the new pure mapper tests.
CI (`:app:testPlayDebugUnitTest` + the rest of `android.yml`) remains the build/test
gate; device QA checklist above remains open for hardware. Nothing committed/pushed;
full diff awaiting approval at `/tmp/opencode/office-fix.diff`.
