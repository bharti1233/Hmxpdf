# HMXPDF — Button / Interaction Issue Register

Branch `release/hmxpdf-2.1.1-build` @ `60b8218`. Companion: `DOCX_PAGE_SETUP_AUDIT.md` (Page-Setup deep dive), `FULL_SOURCE_CODE_AUDIT.md` (app-wide audit, findings A1–A13), `AUDIT_TEST_RESULTS.md`.

Legend — Severity: P0 blocks release · P1 data loss/corruption/critical feature dead · P2 major · P3 minor/polish. Confidence: **Confirmed** (execution path traced, or code+test evidence) · **Strong Evidence** (code + spec/logic analysis, final symptom needs a device/Word run) · **Needs Device Verification** (plausibly intentional or device-dependent).

Entry format: *Control → where it breaks → Expected / Actual → Root cause (file:line) → Repro → Existing coverage → Recommended fix + regression test.*

---

## P1 — fix first

### PS-01 · Continuous / Even Page / Odd Page section breaks behave exactly like Next Page — **Broken effect · Strong Evidence**
- **Where the path breaks:** `DocxPageLayout.insertSectionBreak` stamps `withType(source.sectPr, type)` onto the **copy** written into the inserted boundary paragraph (`docx/DocxPageLayout.kt:153,:156`). Per ECMA-376/ISO 29500 §17.6.22, `w:type` describes how the section *terminated by that sectPr* starts relative to the previous one — so the type lands on the pre-break section (inert) and never on the post-break section, whose start the button is meant to change.
- **Expected:** "Continuous" starts the following section on the same page; Even/Odd push it to an even/odd page. **Actual:** all four buttons (Next Page `Screen:1625` / Continuous `:1626` / Even `:1627` / Odd `:1628` → `VM:515-523`) produce identical documents in Word (Needs Device Verification for the exact Word symptom, DV-2/DV-3).
- **Also wrong (folded here):** placement hardwired before the **first** body paragraph (`Screen:1611 sbid = firstBodyId()`; `VM:451-455`) → phantom empty leading section; user cannot choose the break location (kernel already rejects non-body targets, so the hardcode adds nothing).
- **Existing coverage:** `DocxPageLayoutTest.kt:210-232` **locks the wrong behavior**.
- **Fix + regression test:** stamp the type on the source `sectPr` *after* inserting the unmodified copy (kernel-only change); rewrite the locked test to assert the **post-break** section carries the chosen type and round-trips; add a Word-rendered ground-truth fixture (DV-2). Optionally pass the caret-target paragraph instead of `firstBodyId()`.

### PS-02 · Different Odd/Even toggle + header/footer text edits are silently lost on exit — **Partially working · Confirmed**
- **Where:** `dirty = doc !== saved` (`DocxEditorViewModel.kt:88`, snapshot reference compare). `setEvenOddHeaders` (`:542-552`), `hfTyped` (`:584-595`) write only `wantSettings`/`hfChanged` — `doc` untouched → dirty stays false → no save dot, and the BackHandler exit dialog (`DocxEditorScreen.kt:820-886,1760-1771`) treats the document as clean.
- **Expected:** exiting with unsaved settings/HF edits prompts to save. **Actual:** exits silently; edits lost. (Data survives **if** a save runs — `extras()` `:644-648` → writer `DocxWriter.kt:105-133` verified.)
- **Companion (same root):** `currentFile`'s `doc !== original` check (`VM:786`) treats a settings/HF-only session as unedited → "Open as PDF"/share publishes the original document (P2).
- **Existing coverage:** none.
- **Fix + regression test:** make `dirty` (and `currentFile`'s `hasEdits`) account for `settingsChanged` and non-empty/changed `hfChanged` vs. the load-time state; unit test: toggle odd/even → `dirty == true`; back-exit shows prompt.

### PS-03 · Second header/footer create in a session corrupts the saved package — **Confirmed (code) · Word symptom DV-1**
- **Where:** `freeRelId` (`DocxEditorViewModel.kt:622-627`) scans only the as-loaded `pkg?.relsXml`; `hfRelIds` (session-assigned, `:559,:608-610`) and writer-added rels are invisible; `pkg` is never refreshed after a save. `addRelWithId` (`DocxWriter.kt:161-165`) appends without dedup.
- **Expected:** each new part gets a fresh `rId`. **Actual:** any two `hfCreate`s in one session (even across a successful save) get the **same id** → at save, two `<Relationship Id="rIdN">` entries in document.xml.rels → invalid package → **Word repair prompt** (panel prints the shared id on both rows, `Screen:1676` — visible tell).
- **Existing coverage:** none (writer tests only cover single-part flows).
- **Fix + regression test:** freeRelId must also consider `hfRelIds.values` (and, robustly, guard in `addRelWithId`); regression: create header default + footer even → save → parse rels, assert unique ids — fails today and is one kernel-side assert away until the VM fix.

### A1 · Find → Replace-Current can crash the app (stale match) — **P2 · Strong Evidence**
- **Where:** `DocxEdits.replaceRange` (`docx/DocxEdits.kt:107-118`) does no bounds revalidation — `p.text.substring(end)` / `p.fmts[start]` throw `StringIndexOutOfBoundsException` if the paragraph text changed between match collection (debounced up to ~160 ms — `DocxEditorScreen.kt:1242`, `:513-528`) and the replace.
- **Repro:** find a short match, quickly edit/reflow that paragraph, tap replace (DV-timing; the missing bound itself is objective).
- **Existing coverage:** `DocxReplaceTest` — happy paths only.
- **Fix + regression test:** reclamp `start/end` against current text length before slicing (root cause = shared kernel guard); regression: replace against a shrunk/empty paragraph text.

---

## P2 — fix next

### PS-04 · Page Setup changes never show in the editor preview until an export happens — **P2 · Confirmed**
- **Where:** chrome reads `pkg.page` (`DocxEditorScreen.kt:970-993`); that plain field is set at load (`DocxReader.kt:99-107`) and only again inside `exportToPdf` (`DocxEditorViewModel.kt:802`). `livePage()` (`:481-485`, doc-comment promises live follow) has exactly one call site.
- **Repro:** open any DOCX → set margins/paper/orientation → observe the page frame and text width unchanged; print/export → geometry suddenly correct.
- **Fix + regression test (repair-wave version):** one observable sync point (re-render chrome from `livePage()` or refresh `pkg.page` on commit of `pg:*` ops); test: commit a size change → preview state changes in the same composition. Any reflow fix must remain chrome-sync-observable, not a resurrected experimental UI.

### PS-05 · Gear looks dead until a paragraph is focused — **P3 · Confirmed**
- **Where:** `Screen:1091 has = activePara != null`; gear `:1103 enabled = has`. A freshly opened/unfocused document shows a disabled Page Setup gear, though a layout dialog needs no paragraph (the VM re-gates on `editMode` anyway, `VM:516,617`).
- **Fix:** enable the gear on `editable && editMode` (format glyph buttons keep the `has` gate). Trivially testable via panel-open state.

### PS-06/A8 · No table row/column structural operations exist — **P2 · Confirmed (absence)** — grep across app sources: zero insert/delete row/col handlers; cell-text editing only (`DocxTableCellEdit*`). Product-gap decision, not a dead wire.

### A4 · No autosave / process death loses all edits (page-setup sessions included) — **P2 · Confirmed by inspection** — `save()` runs only from the button/dialog (`VM:672-701`); `SaveRecovery` journal covers interrupted writes only. Fix decision required; must include package-side state (see PS-02) in whatever dirty/autosave design lands.

---

## P3 — minor / polish

| ID | Issue | Root (file:line) | Fix sketch |
|----|-------|------------------|------------|
| PS-07 *(corrected 2026-11-02 — see `PHASE3_CLAIMS_REVALIDATION.md` §5)* | Multi-section documents render **every page with the trailing section's paper/margins** — per-section size/margin edits are invisible in preview and internal-PDF export (boundary *gaps* do split pages — `Screen:361-362`) | `DocxReader.kt:99-107` parses geometry from the trailing `sectPr` only; `DocxPdfWriter.kt:71-78` inherits it | Parse per-section `PageSetup` in the reader (chrome + PDF use it); pair with PS-01 so visual work doesn't bake in wrong break semantics |
| PS-08 | Paper preset in a landscape section lands portrait dims + `orient="landscape"`; preset highlight lost after orientation swap | `Screen:1549-1567` (dims hardcoded portrait; `chosen` compares raw dims) | Preset should apply dims in current orientation (or flip landscape) and highlight on normalized dims |
| PS-09 | Numeric fields: comma-locale entry silently never commits; intermediate commits (margin "0" while typing "0.75"); out-of-bounds silently dropped | `Screen:1502-1508` | Normalize "," → "."; debounce commit to blur/whole valid value; surface invalid state |
| PS-10 | HF remove/recreate leaks unreferenced part + rel into the package | `hfRemove` `VM:616-620` keeps `hfChanged`/`hfRelIds`; writer writes them regardless (`DocxWriter.kt:105-121`) | Drop orphaned session state on remove; (optionally) skip parts with no references at save |
| A2 | Revisions inside content-control previews list but Accept/Reject silently no-ops (`findPara`/`mapParas` skip preview paragraphs) | `DocxModel.kt:128-152`, `VM:253-263,368-377` | Skip preview paragraphs in `pendingRevisions` (or route resolution through containers) |
| A3 | Undo right after **Add comment** leaves an orphan `w:comment` in the saved file | `DocxComments.document()` filters only the deletion side (`docx/DocxComments.kt:124-145`, adds at `:140-143`) | Filter additions not referenced, mirroring the removed-side logic |
| A5 | `save()` early-return drops `onDone` (`if (meta.saving || !editable) return`) — latent dead-end for a future caller | `VM:673` | Invoke `onDone(Failed …)` or assert the contract |
| A6 | LiquidToggle thumb flips optimistically; a guarded reject can leave the thumb wrong until recomposition | `LiquidToggle.kt:82-117` + guards (`VM:129-133`) | Reflect state back / restore on reject (device-confirm reachability first) |
| A7 | `SelectionContainer` wraps the whole viewport incl. editable fields — selection conflicts on some Android versions | `DocxEditorScreen.kt:1011-1045, 2003-2013` | Scope the container to static text (device verify) |
| A9 | "Open as PDF" has no progress state and stays tappable (double-tap = double conversion); Print path toasts but PDF does not | `Screen:798-807`, `VM:800-824` | Reuse the `meta.saving` style guard + progress chip |
| A10 | No comments count badge on toolbar/panel | `Screen:889-891,1101` | Small text badge from `comments().size` |
| A11 | Phase-2 documented limits stand: `w:moveFrom/moveTo` unmodeled; paragraph-mark revisions unresolvable | `docs/phase2/PHASE2_TEST_REPORT.md:65-69` | Backlog, no action now |
| A12 | Vendored debt (backdrop `LayerBackdrop` TODO, cropper TODO) + **lint baseline contains 410 recorded issues** (incl. 46 `NewApi`) | `backdrop/…/LayerBackdrop.kt:67`; `app/lint-baseline.xml` | Debt ledger; new lint errors still fail CI |
| A13 | No instrumented tests anywhere; `:ocr-core` has no test sources | source sets + `.github/workflows/android.yml` | Start androidTest tier when device work resumes |

## Recommended fix order

1. **PS-03** (one-guard data-corruption fix, smallest diff, biggest risk removed) + its rels uniqueness test.
2. **PS-02** (dirty/exit-loss: settings + HF state) — unblocks honest save semantics for everything else; keep writer untouched (write path stays sole + byte-preserving).
3. **PS-01** (kernel `w:type` target swap ×, placement target) + rewrite the locking test + Word ground-truth fixture.
4. **PS-04** (minimal chrome sync — observable, no UI redesign) then **PS-05/PS-09/PS-08** UX trio.
5. **A1** (bounds guard), then **A4** autosave decision, then P3 cleanup (PS-07 per-section geometry, PS-10 orphan state, A2/A3/A5-A10).

Every fix must stay inside `docx/` kernels + VM + chrome-sync; the `DocxWriter` remains the sole DOCX write path; no experimental Page-Setup UI returns.

---

## Phase 3 fix status — 2026-11-02 (implementation wave @ `60b8218`, uncommitted)

States: **Fixed & test-verified** (JVM unit test covers it) · **Fixed — needs device verification** · **Partially fixed** · **Not fixed** (reason). Details per issue in `PHASE3_BUG_FIX_REPORT.md`; test inventory in `PHASE3_REGRESSION_TEST_REPORT.md`.

| ID | Status | Fix (file) | Test |
|----|--------|-----------|------|
| PS-01 (R1 break type) | **Fixed** — kernel-verified; Word render needs device (DV-2/DV-3) | `DocxPageLayout.insertSectionBreak` stamps type on the FOLLOWING terminator; boundary carries an unmodified copy (minimal `<w:sectPr/>` when the doc has none) | `sectionBreakTypeStampsTheFollowingSectionNotTheBoundary`, `sectionBreakNextPageSplitsSectPrlessDocumentWithoutStamping` (locking test rewritten) |
| PS-01 placement (R5) | **Fixed — needs device verification (DV-3)** | `Screen` targets the focused body-level paragraph (`isBodyLevel`), falls back to `firstBodyLevelId()`; hint string `docx_section_target`; `firstBodyId` removed | `sectionBreakRespectsBodyLevelTargeting` (cell refusal, no ghost section) |
| PS-02 (R2 dirty/exit loss) | **Fixed & test-verified**; exit prompt on device = DV-4 | `dirty` compares `wantSettings`/`hfChanged` against the saved snapshot (`packageSaved*`, seeded at load; refreshed by `recordSaved` on save success); `currentFile.hasEdits` covers settings/hf; see/Odd toggle round-trip returns to clean (no empty settings shell) | `evenOddToggleCycleReturnsToCleanState`, `recordSavedSetsTheDirtyBaseline`, `viewModeRefusesEveryPageSetupOperation` |
| PS-03 (R3 rel-id collision) | **Fixed & test-verified** | `freeRelId` counts `hfRelIds.values`; writer `addRelWithId` no-ops on same type+target, **throws** `IllegalStateException` on a real conflict (save fails loud) | `repeatedHfCreatesNeverShareARelationshipId`, `writerRelWithIdDedupesSameRelationshipAndRefusesCollisions` |
| PS-04 (R4 stale preview) | **Fixed — needs device verification (visual)** | Preview chrome reads `viewModel.livePage()` (`Screen`); PDF export keeps its `pkg.page = livePage()` sync — one source of truth, no second state | `setSectionSetupAppliesInEditModeAndMarksDirty` (live state follows the doc's sectPr) |
| PS-05 (R7 gear gate) | **Fixed — needs device verification** | gear `enabled = pkg != null` (layout needs no focused paragraph) | UI-only; no JVM render test exists (see report §limits) |
| PS-06 (A8 table rows) | **Not fixed — product gap, out of wave scope** | — | — |
| PS-07 (R6 per-section render) | **Not fixed (by design this wave)** — documented limitation: reader/PDF use trailing-section geometry. `flatten()` boundary logic was rewired to parsed `w:type` semantics (`followType` pre-pass), so breaks are correct; per-page geometry is not | — | — |
| PS-08 (presets vs landscape) | **Fixed — needs device verification (visual)** | Presets normalize dims to the section's current orientation (A4 landscape = 16838×11906) and the highlight compares normalized dims so it survives orientation swaps | UI-only; kernel dim writes already covered |
| PS-09 (R8 field UX) | **Partially fixed** — comma→`.` normalization; invalid/out-of-bounds values tint the field with the danger ink + surface view-mode disable. Intermediate-commit debounce ("0" mid-typing "0.75") intentionally deferred: whole-number commit is also the trigger for live preview updates, and a blur-based commit needs device-grade focus handling (DV-5) | `commitIf`, field `bad` state | UI-only |
| PS-10 (R9 orphan parts) | **Fixed & test-verified** | `hfRemove` purges `hfChanged`/`hfRelIds` once no section references the rid; writer keeps skipping unreferenced parts | `repeatedHfCreatesNeverShareARelationshipId` (purge + surviving sibling) |
| A1 (replace crash) | **Fixed & test-verified** | `DocxEdits.replaceRange` clamps a stale range to the current text/fmts; fully-shrunk matches no-op instead of throwing | `staleRangeAgainstShrunkParagraphNoOpsInsteadOfCrashing` |
| A5 (save() dead-end) | **Fixed** | `save()` reports `Failed` through `onDone` for in-flight-save and read-only instead of a silent return | inspection-only (needs Android Context; documented in the regression report) |
| A6 (toggle flip on reject) | **Fixed for the Page Setup surface** — panel toggles are `enabled = live`, so the guarded-reject path is unreachable there; full reject-reflection for other screens deferred | `LiquidToggle.enabled` param | UI-only |
| A2 / A3 / A4 / A7–A13 | **Not fixed** — outside this wave's priority set (Page Setup P0–P2 + confirmed P1 crash); unchanged behavior | — | — |

**Found during fixing (same class as PS-03, fixed together):** a captured `Section` object could predate other panel edits in the same session; any later section op rebuilt its sectPr content from that stale string and silently dropped the earlier edit (page setup then titlePg, header then footer). `commitSect` now rebases content on the section's *current* terminator (matched by `paraId`/`rawId`), refuses a vanished synthetic section instead of double-splicing a trailing sectPr, and skips pure no-ops (no phantom undo steps). Covered by `repeatedHfCreatesNeverShareARelationshipId` (header+footer coexist) and the stale-capture assertions in `setSectionSetupAppliesInEditModeAndMarksDirty`.
