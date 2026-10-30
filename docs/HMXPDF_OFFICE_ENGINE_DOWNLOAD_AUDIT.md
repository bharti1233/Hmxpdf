# Office Engine Download Audit — Intermittent "Waiting for network" Stall

**Scope:** investigation only. No source files were modified for this audit; the eight
fixes from the previous deep audit are present and untouched. No APK was built, run, or
installed. No dependencies added. Verdict at the end.

---

## 1. Complete download architecture

**The mechanism is Jetpack WorkManager — not `DownloadManager`, not a custom HTTP
service.** A one-time pending-work row is created with a `NetworkType.CONNECTED`
constraint, queued by the OS, executed by a `CoroutineWorker`, and the UI mirrors the
`WorkInfo` via a Flow.

```
OfficeEngineSettingsSection (Compose screen)
  └─ Download tap → startInstall()
       ├─ API 33+: requestPermission(POST_NOTIFICATIONS) first
       └─ installer.install()
            └─ DownloadOfficeEngineInstaller.install()
                 ├─ Constraints: NetworkType.CONNECTED + setRequiresStorageNotLow(true)
                 └─ WorkManager.enqueueUniqueWork("office-engine-install",
                       ExistingWorkPolicy.KEEP, OneTimeWorkRequest<OfficeEngineDownloadWorker>)

WorkManager (OS JobScheduler) defers the row until constraints are satisfied
  └─ OfficeEngineDownloadWorker.doWork()        [RUNNING]
       ├─ setForeground(dataSync notification)
       ├─ download(): HttpURLConnection → sha256.part resume file
       ├─ verify: SHA-256 + exact size vs pinned manifest
       ├─ extractTar.xz into staging dir (OfficeEngineStore/staging)
       └─ commit: staging → <version>-<abi>/, flip `current`, atomic

UI state (independent of the worker, live Flow):
  DownloadOfficeEngineInstaller.init
    └─ getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).collect { map(it.firstOrNull()) }
```

Key point about the failure mode: **while the work row sits in `ENQUEUED` (any deferral
reason) the UI shows "Waiting for network…"** — see §3/§4.

### Flavors

| Flavor | Engine availability |
|---|---|
| *foss*, *gms* (play) | shared `OfficeEngineFlavor.available = true` (in `app/src/main/java/...`) → `DownloadOfficeEngineInstaller` |
| `fdroid` | `OfficeEngineFlavor.available = false` → `UnavailableOfficeEngineInstaller` (section hidden) |

### Distribution bundle

`OfficeEngineManifest` pins everything (nothing about the download is trusted at runtime
except these constants):

- `VERSION = "lo-lite-2.0"` (source: GitHub `vasuki-re/LibreOffice-Lite` release v2.0, MPL-2.0)
- `APPROX_DOWNLOAD_BYTES = 47_521_152` (~48 MB, the number shown while waiting)
- `arm64-v8a → LibreOffice-arm64.tar.xz`, sha256 `6665ad47db41d116248c40e6fc608a3425b9eb4a304f5ad5e5d5c80dbea61e30`
- `armeabi-v7a → LibreOffice-arm.tar.xz`, sha256 `7546e244f66c8c1b5a5a4ede7cb26c8f7e767879bbf3b39d405a5d8fa148b045`
- x86 / x86_64 devices: no bundle → section hidden (`Unsupported`).

### On-disk layout

Everything lives under `context.noBackupFilesDir/office-engine/` (internal storage, never
backed up): `download/` (resumable `<sha256>.part`), `staging/` (extract-in-progress,
renamed atomically), `<version>-<abi>/.ready` (installed engine), `current` (active-engine
pointer). Install footprint is far larger than the 48 MB archive: unpacking the `.tar.xz`
may decompress up to the XZ dictionary-sized block window internally (≈256 MiB working
set) plus the extracted tree — relevant to §3 root cause 1.

---

## 2. Relevant source files and functions

| File | Role | Key lines |
|---|---|---|
| `app/src/main/java/com/hmx/pdf/ui/screen/OfficeEngineSettingsSection.kt` | Download button, permission request, state→UI rendering | L93 `LaunchedEffect { installer.refresh() }` (state restore on Settings entry); L147–166 `Downloading` branch — `waitingForNetwork` label + only a small **Cancel** pill; L170+ `Installing` |
| `app/src/main/java/com/hmx/pdf/office/DownloadOfficeEngineInstaller.kt` | Enqueue, constraints, unique-work policy, **state mapping** | L36–40 live WorkInfo Flow; **L44–46 `ENQUEUED/BLOCKED → Downloading(0, approx, waitingForNetwork=true)`**; **L75–76 `NetworkType.CONNECTED` + `setRequiresStorageNotLow(true)`**; **L80 `ExistingWorkPolicy.KEEP`**; L83–85 cancel; L96–101 `refresh()` |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineDownloadWorker.kt` | Actual download/verify/install | L40 `UNIQUE_NAME = "office-engine-install"`; L94 `instanceFollowRedirects=true`; **L95–96 connect timeout 20 s / read timeout 60 s**; L97 Range resume `bytes=$have-`; L85 `<sha256>.part`; L115 `ensureActive()` in read loop; L124 `setProgress` |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineInstaller.kt` | State surface | L14 `Downloading(downloaded, total, waitingForNetwork=false)` |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineStore.kt` | Disk layout, atomic commit | root/staging/download/`.ready`/`current` |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineManifest.kt` | Pinned URL/size/sha256 | §1 above |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngine.kt` | Prefs, `installedState()`, focus hook | |
| `app/src/main/java/.../OfficeEngineFlavor.kt` (+ `app/src/fdroid/...` override) | Flavor gating; `foss` and `gms` (play) resolve to the shared default; `fdroid` overrides it | `available` flag |
| `gradle/libs.versions.toml` | WorkManager version | L27 `work = "2.10.1"` |
| `app/src/main/res/values/strings_office_engine.xml` | The misleading string | L12 `office_engine_waiting_network` = "Waiting for network…" |
| `app/src/test/java/com/hmx/pdf/office/OfficeEngineDownloadTest.kt` | Existing tests | All 3 classes `@Ignore("Requires Android Context")` (L13, L75, L96) — incl. `OfficeEngineDownloadWorkerIntegrationTest` |

**Network monitoring search results (whole tree):** `ConnectivityManager`,
`NetworkCallback`, `isConnected` — **zero usages in app source**. The app never asks the
OS whether a network is actually available, usable, validated, or unmetered. It relies
entirely on the `NetworkType.CONNECTED` constraint inside WorkManager.

`androidx.startup` provider: untouched in all manifests → on-demand WorkManager
initialization intact (no custom init path that could fail to schedule work).

---

## 3. Exact root causes

### Root cause 1 — CONFIRMED (code-level): every `ENQUEUED`/`BLOCKED` state is labeled "Waiting for network", regardless of the actual deferral reason

`DownloadOfficeEngineInstaller.map()`:

```kotlin
WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
    OfficeEngineState.Downloading(0, OfficeEngineManifest.APPROX_DOWNLOAD_BYTES, waitingForNetwork = true)
```

The one-time request carries **two** constraints:

```kotlin
Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresStorageNotLow(true)     // ← second unsatisfied constraint, invisible to the user
    .build()
```

WorkManager defers a row in `ENQUEUED` until **all** constraints are satisfied — and also
keeps it in `ENQUEUED` during OS backoff and Doze/App-Standby deferral. The mapping
cannot tell *why* the row is queued and always blames the network:

- If the device is in the OS **storage-low** state (vendor-dependent threshold, generally
  signaled well before a disk is genuinely full — WorkManager's `requiresStorageNotLow`
  simply follows `StorageManager.isLowStorage`), the row waits on the **storage**
  constraint while the UI says "Waiting for network…". This can persist for as long as
  the device stays in the storage-low state — effectively forever on a nearly-full
  phone.
- If the OS defers the job (Doze, App Standby bucket, OEM battery optimization, network
  flapping), the row stays `ENQUEUED` and again shows "Waiting for network…".
- BLOCKED has the same fate.

User impact: the message is plainly false in a whole class of cases, is
unbounded in duration, and the only affordance while it shows is a small
**Cancel** pill (`OfficeEngineSettingsSection` L149→160). There is no
"check connection", no "queued", and no "why am I stuck" explanation.

### Root cause 2 — CONFIRMED: no app-side network validation anywhere

No `ConnectivityManager`/`NetworkCallback` usage in the app (search results in §2). The
UI therefore can never distinguish:

- "there is genuinely no network" (true waiting), vs
- "a network exists but the job is deferred for another constraint / battery state" (false waiting), vs
- "the job is queued and hasn't started running yet" (scheduler hold).

Combined with root cause 1, a silently-wedged `ENQUEUED` row always presents itself as
"Waiting for network" — the message the user sees is indistinguishable between the two
fundamentally different problems, making diagnosis impossible from the inside of the app.

### Root cause 3 — SUSPECTED (code-level mechanism, environmental trigger): `ExistingWorkPolicy.KEEP` + restarts make a wedged row feel permanent

`enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)` (L80):

- **KEEP semantics:** while a row with this unique name is still *pending*
  (`ENQUEUED`/`BLOCKED`/`RUNNING`), a re-enqueue from a new "Download" tap is a
  **no-op**. Tapping Download again while waiting for network does not nudge, replace,
  or re-check anything. (Terminal states are replaceable — see the precision note below.)
- **Persistence semantics:** the unique-name row lives in WorkManager's own DB. When the
  process is killed and the user reopens Settings, the Flow (L36–40) re-collects the same
  `ENQUEUED` row and re-renders "Waiting for network…" — apparently frozen.
- **Interactions that make it worse:**
  - Because `KEEP` refuses to create a new row, there is no honest *fresh* enqueue; to
    actually force a fresh attempt the user must tap the small Cancel pill and then
    Download again (which will then succeed in creating a new row) — a non-obvious
    escape hatch the UI doesn't teach.
  - `startedHere` (L32, L58–60) hides FAILED results that predate the current process
    lifetime: after a restart, a genuinely failed row renders as "Not installed / Download"
    with the previous error message dropped (a deliberate anti-stale-error display
    choice). This costs only *information*, not functionality — see the precision note.

  *(Precision on KEEP semantics: KEEP only treats existing work as present when it is
  still **pending** — `ENQUEUED`/`BLOCKED`/`RUNNING`. A row in a terminal state
    (`FAILED`/`SUCCEEDED`/`CANCELLED`) IS completed, and the next `install()` cleanly
  inserts a fresh one. So the hidden-FAILED display issue costs only information
  (the previous error is masked after a restart), not functionality — the functional
  KEEP harm is the no-op nudge against a wedged *pending* row, above.)*

### Root cause 4 — SUSPECTED (environmental): OS deferral of one-time CONNECTED work

WorkManager one-time work with a network-availability constraint is subject to:

- **Doze / App Standby** (background network + JobScheduler batching),
- **App Standby Buckets** — a restricted bucket can defer jobs to roughly once per day,
- **OEM battery optimizers / "usage access managers"** that force-stop apps and place
  them into restrictive buckets,
- **`force-stop`** (from Recents or an OEM "boost" app): WorkManager's JobScheduler
  listener for the stopped app's package is dropped by the system; a pending job may sit
  until the user manually reopens the app.

This explains the *intermittency* the user observed: identical code path, but the
execution environment differs between attempts (open foreground → runs; killed + Doze +
low-end OEM shell → deferral window of minutes or days). Once the app itself is reopened
fore- or background the Job may be run at that point (or the settings Flow reads the
still-ENQUEUED row anew), which is why the incident *appears* to resolve by simply
opening the app again sometimes.

These triggers are not directly verifiable from a source tree — see §9.

---

## 4. Evidence and code references (rule-outs)

Each candidate cause with what the code shows:

| Candidate | Status | Evidence |
|---|---|---|
| Hanging HTTP connection keeps worker stuck | **Ruled out** | `HttpURLConnection` with `connectTimeout = 20_000` and `readTimeout = 60_000` (Worker L95–96); failure surfaces as `FAILED` with an error string, not a Waiting state |
| Redirect handling breaks download | **Ruled out** | `instanceFollowRedirects = true` (L94); single-protocol HTTPS redirect chain to same host family; cert errors would fail fast → FAILED |
| Captive portal / garbage response corrupts download | **Ruled out as "Waiting"**; would cause FAILED | Pinned sha256 + exact-size verification after download; error surfaces in the `Failed` UI state with Retry affordance |
| Metered / mobile-data restriction blocks download | **Ruled out** | Constraint is `NetworkType.CONNECTED` (L75), not `UNMETERED`; explicitly permits mobile data |
| WM VPN / partial-connectivity tracking bug | **Ruled out** with version proof | WorkManager pinned to **2.10.1** (`gradle/libs.versions.toml:27`); the known constraint-tracking defects of ≤2.8.x (unvalidated networks, VPN lock-on) were fixed since 2.8.1 |
| Duplicate parallel downloads | **Ruled out** | Unique work name + `KEEP` (L80) enforces a single row; progress persisted through `<sha256>.part` and per-bundle staging |
| Stale `.part` file / an interrupted range breaks integrity | **Ruled out** | Part files are keyed by bundle sha256 (L85), stale-purity cleanup deletes foreign-version parts (L84), `Range: bytes=$have-` resume (L97), full-file SHA-256 + size verification after download |
| Cancellation leaves a bad engine installed | **Ruled out** | Worker installs only after verification, atomically from `staging → <version>-<abi>/`; the `.ready` sentinel is written last; `cancel()` at any point leaves the engine absent |
| WorkManager initialization broken | **Ruled out** | No `androidx.startup` provider overrides anywhere in the manifests; default on-demand init is guaranteed, and there is no custom initialization path in the app that could fail to schedule work |

The worker itself is solidly implemented. **Root cause(s) are not in the download code path, but in the enqueue/state surface above it.**

---

## 5. Reproduction scenario matrix

| Scenario | Expected behavior (spec) | Actual behavior in this code | Verdict |
|---|---|---|---|
| Stable Wi-Fi, phone not special-cased by battery saver | Download starts | Job with `CONNECTED` constraint → runs → download with progress | **PASS** (the "sometimes it works" case) |
| Mobile data, `UNMETERED` not required | Starts if user permits | `CONNECTED` allows metered data | **PASS** |
| Wi-Fi → mobile switch | Resumes or retries | Worker holds a single open connection; mid-download switch kills the socket → IOException → backoff retry (WM restart attempt); `.part` resume keeps progress. Interruption during backoff shows correctly as *Downloading/downloaded bytes kept*... **but** during the backoff window the state shows `ENQUEUED` → **"Waiting for network…"** even though Wi-Fi is still connected | **PARTIAL** (correct data behavior, misleading label; backoff is typically seconds-to-minutes) |
| Temporary internet loss | Waiting state, automatic recovery | `ENQUEUED` (network constraint unsatisfied) → waiting label. Recovery when network returns | **PASS** behavior; label correct in this one scenario |
| Internet restored | Download restarts without reopening settings | WM satisfied → RUNNING; the running Flow updates UI live | **PASS** |
| App backgrounded | Matches declared scheduling policy | One-time KEEP row is the policy: keeps running (foreground service notification during download); no additional battery hops taken | **PASS** |
| App process killed mid-wait | Download state recovers correctly | Row persists; Settings reopen → `LaunchedEffect` `refresh()` (L93) + Flow re-collection → same ENQUEUED row → "Waiting for network…" | **PASS data-wise**, **FAIL UX-wise**: user sees a frozen "Waiting…" implying a stalled process state; the Cancel→redownload escape is unexplained |
| Server timeout | Controlled retry and useful error | 20 s/60 s timeouts → `Result.failure` → `FAILED`; `startedHere` → shows error | **PASS** |
| Insufficient storage | Clear error, no false waiting state | **The row sits ENQUEUED on the `requiresStorageNotLow` constraint and the UI shows "Waiting for network…" indefinitely** | **FAIL** — direct manifestation of root cause 1 |
| Repeated Download taps | No duplicate downloads | `KEEP` prevents duplicates. But it also prevents any *nudge* at a wedged row; repeated taps while a row is ENQUEUED are no-ops | **PASS** on duplicates, **FAIL** on recoverability from the UI |

**Failing scenarios are exactly the ones associated with root causes 1 and 3 (storage-wait
mislabel and KEEP-induced no-op taps).** All "real-network-cause" waiting scenarios work.

---

## 6. Severity and user impact

- **Severity: P1** (UX/reliability defect on a premium feature; data-loss: none;
  crash: none; built-in renderers unaffected — `UniversalDocumentConverter` still falls
  back to built-in PDF/Office renderers when the engine is absent or conversion errors).
- **Impact:** users see "Waiting for network…" with an indeterminate progress bar pinned
  at 0 %, no explanation, and no obvious remedy; the wait is unbounded (can last until
  storage or battery conditions change — potentially days). When the download *does* run,
  it behaves well — which makes the defect feel flaky and leads users to report it as
  "the download is broken".

---

## 7. Recommended fix

> **Resolution (2026-10-30):** implemented — see `docs/HMXPDF_OFFICE_ENGINE_DOWNLOAD_FIX_REPORT.md`.
> Items 1, 2, and 4 are implemented as written; item 3 is resolved as an explicitly
> *visible* KEEP (worked semantics kept to protect in-progress downloads per the task
> instructions) — re-taps now land on the honest pending state instead of doing nothing silently.

Fix the **state surface** first (this is the actually-broken part); the worker needs no
changes.

1. **Make the ENQUEUED label honest (root cause 1).** When the row is `ENQUEUED` but the
   network is fine, the "Waiting for network" claim is false. Minimal change path that
   doesn't need a new WorkManager API: before rendering the wait state, check
   `ConnectivityManager.activeNetwork` capabilities in the app (one platform API call,
   no new dependency) and map:
   - no network → "Waiting for network…" (as today),
   - network present + storage-low → "Waiting for free space" (with a hint of what to free),
   - network present + storage ok → "Queued / will retry shortly" (covers Doze/bucket/backoff),
   all three states stay a `Downloading(0, approx, …)`-style state — only the label and
   its explanation change. This is contained entirely to `DownloadOfficeEngineInstaller.map()`
   + the Compose branch that renders it.
2. **Drop `setRequiresStorageNotLow(true)`** (or don't gate the download on it): a 48 MB
   download shouldn't be silently deferred in perpetuity by the OS storage-low state. Instead let the *user-facing* installation surface the lack of space
   explicitly as a Failed state ("Not enough free space") — the current design converts
   a low-storage condition into an invisible infinite wait. If the intent was to safeguard
   the extraction phase (~256 MiB working set), the worker already surfaces an
   out-of-memory failure itself; a pre-flight free-space check at `install()` time would
   be a better, visible guard.
3. **Keep `KEEP` for deduplication but give the user an honest retry:** change *re-tapping
   Download* on an existing ENQUEUED row to at minimum communicate ("Already queued…"),
   or — better — do a cancel+re-enqueue on explicit user action (Cancel is already the
   primary affordance; a `REPLACE`-style uniqueness policy applied on the
   *second/after-cancellation* path is targeted and directs a retry through constraints again).
4. **Expose a stable explanation of OS deferral:** the intermittency users see is
   OS-scheduling, not the network. At minimum the UI should not claim "network"; at
   maximum, a Setting > battery-optimization deep link (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   / `IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) is a well-supported way for users to lift the
   Doze/bucket deferral.

---

## 8. Regression test plan

The state-mapping logic is plain Kotlin and parameterizable — the right seam to test:

1. **Extract the mapping into a pure function** — signatures like
   `mapState(state: WorkInfo.State, phase: String?, done: Long, total: Long,
   networkAvailable: Boolean, storageLow: Boolean, startedHere: Boolean,
   installedState: OfficeEngineState): OfficeEngineState` and call *that* from `map()`.
2. **Unit test the pure function** (pure JVM, plain JUnit — no Robolectric needed if
   constructed parameters replace the `WorkInfo` wrapper; the `WorkInfo.State` enum and
   `Data` are real classes that work in local tests):
   - `ENQUEUED` + no network → waiting-label state,
   - `ENQUEUED` + network + storage-low → storage-wait state,
   - `ENQUEUED` + network + storage-ok → queued/waiting state,
   - `RUNNING` + phase install → `Installing`,
   - `RUNNING` + phase download → `Downloading(done, total)` with `waitingForNetwork=false`,
   - `FAILED` + `startedHere` → `Failed(message)`; `FAILED` without → installed state,
   - `SUCCEEDED`/absent → installed state.
3. **CI wiring:** these JVM tests run in the existing `android.yml`
   (`:app:testPlayDebugUnitTest` on PRs and `main` pushes — no new workflow needed).
   The existing `@Ignore`d tests in `OfficeEngineDownloadTest.kt` remain untouched.
4. **Device-side manual matrix** (cannot be exercised from this audit environment):
   `adb shell dumpsys deviceidle force-idle`, forced restricted buckets
   (`am set-standby-bundle`), a storage-low state induced by filling internal storage,
   and OEM battery-optimization toggles — verifying each repaired label comes out right
   on hardware.

---

## 9. Remaining uncertainty

- **Which environmental trigger produced the user's actual incidents** (Doze vs
  restricted bucket vs OEM battery optimization vs force-stop vs genuinely offline) —
  cannot be determined from a source tree; it varies per device and per attempt. What is
  proven from source: whatever the reason for the deferral, the UI claims "network" and
  offers no explanation. Pinning the trigger would require device logs
  (`adb shell dumpsys jobscheduler` at a stuck moment) on an affected handset.
- **Exact WorkManager version behaviors in the wild:** behavior is pinned to
  `work = "2.10.1"` (source), and the vendor-specific Doze/bucket/optimizer behavior
  differs between OEM builds; the audit deliberately treats these as environmental
  variables rather than asserting device-specific facts.
- **Server/CDN availability window** (the GitHub release CDN could have one bad episode):
  not verifiable from here, and the pinned-sha256 design means a corrupted fetch would
  fail out as FAILED, not "Waiting". Treated as a non-factor for this bug's signature.

---

## 10. Final verdict

**Root cause CONFIRMED at code level, with an environmental co-factor.**

- **Confirmed (this is the bug's signature):** `ENQUEUED/BLOCKED → "Waiting for network…"`
  in `DownloadOfficeEngineInstaller.map()` (L44–46) mislabels ALL constraint deferrals
  and OS-scheduled backoff as a network wait; `setRequiresStorageNotLow(true)` (L76)
  adds an invisibly-gated second constraint that can keep the row queued for unbounded
  time; there is no app-side network/queueing insight (`ConnectivityManager` absent) and
  no meaningful recovery affordance besides a small Cancel pill, with `ExistingWorkPolicy.KEEP`
  (L80) turning repeat taps into no-ops.
- **Suspected environmental trigger (explains intermittency, not the label bug):**
  Doze / App Standby buckets / OEM battery optimization / force-stop deferring the
  one-time CONNECTED job.
- **Insufficient evidence (cannot be confirmed from source alone):** the specific
  device/environmental trigger in each user report, and any server/CDN-side episode —
  both would need on-device `jobscheduler`/`networkstats` captures at the stuck moment.

The download/verify/install machinery itself is well-built (timeouts, Range resume,
checksum integrity, atomic install) and requires no change for this defect.

---

*Audit performed read-only. No source files, docs of previous audits, or pinned
configuration were touched. Full call-chain, root-cause register, rule-outs, scenario
verdicts, and this deliverable were produced as the complete Phase 1–5 evidence set.*
