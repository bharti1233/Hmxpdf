# HMXPDF — Phase 3 Historical-Claims Revalidation & Engine/Engine-vs-UI Coverage Gap

Branch `release/hmxpdf-2.1.1-build` @ `60b8218` — verified identical working content to the audited checkout (same branch, same HEAD, same untracked-user-file state; only diff: none — the earlier clone's audit directory was carried over intact and no experiment branch exists local or remote). Companion reports: `DOCX_PAGE_SETUP_AUDIT.md`, `BUTTON_ISSUE_REGISTER.md`, `FULL_SOURCE_CODE_AUDIT.md`, `AUDIT_TEST_RESULTS.md`.

Historical context read: `docs/phase3/PHASE3_SOURCE_AUDIT.md` (head `efa139d`, pre-implementation), `PHASE3_IMPLEMENTATION_PLAN.md` (pre-implementation), `PHASE3_TEST_REPORT.md` (green run `41232855647` @ `567414c`), `PHASE3_FINAL_REVIEW.md` (`efa139d..567414c`). All four treated as **claims about the past, not proof of current functionality**.

**Continuity check (the base for every verdict below):** `git log 567414c..60b8218` = exactly two commits (`d3d8dc2`, `60b8218`), both docs-only. The code the Phase-3 documents describe is *byte-identical* to the audited head; CI re-ran green at this head (`41328856513`, build/release/release-guard). Therefore every difference found below is a **documents-vs-reality gap, not code drift**.

Legend: ✔ verified at this head · E verified **engine-only** (true but does not cover the UI path) · ⚠ partially accurate (scope caveats) · ✗ inaccurate vs source · U not verifiable statically.

---

## 1. Claim-by-claim revalidation

### PHASE3_SOURCE_AUDIT.md (pre-implementation claims about the legacy engine)

| Claim (doc line) | Verdict | Evidence at `60b8218` |
|---|---|---|
| Source audit `:59` — "The previewer already pages at paragraph-level section breaks (`flatten()` … `sectPr` and not `continuous` → page gap)" | **✔** | `DocxEditorScreen.kt:361-362`: `Props.child(b.pPr, "sectPr")` + `breakHere()` when not containing `"continuous"`. Line drifted 358 → 361-362 (no behavioral drift). Note: substring check, not a parsed `w:type` |
| Source audit `:35/:49` — `pkg.page` parsed from body trailing `sectPr` only (`w:orient`/`header`/`footer`/`gutter` unparsed pre-Phase-3) | **✔** | `DocxReader.kt:99-107`; extended parsing shipped by Phase 3 lives in the kernels (`DocxPageLayout.pageSetupOf :54-64`), not the reader's per-section model |
| Source audit `:64` — pre-Phase-3 HF support "None" | **✔** | matches commit history (HF plumbing arrives with `7bcc864`) |
| Source audit `:80/:111-112` — plan that Edit-Mode/read-only gates shared with all mutators; UI in existing conventions | **E** | implemented (`VM:493-494,503,516,543,585,599,617`; UI `live` flags `Screen:1494,1608,1650-1661,1684,1698`) — see §2 for why "gated" ≠ "covered" |
| Source audit `:94` — plan: preview derives live setup from blocks; "`pkg.page` … synced immediately before **export/save** so undo cannot leave a stale paper" | **⚠** | `livePage()` exists (`VM:481-485`) but its **single** call site is `exportToPdf` (`VM:802`). Save doesn't need the mirror (writer serializes blocks), but the *preview* never syncs → `PS-04`/`R4`. The plan's preview sentence was **not** what shipped |
| Source audit `:100` — "new addRel calls reserve ids by scanning existing ones, `rIdCpN` collision-free pattern unchanged" | **⚠** | true for the auto-id pattern (`DocxWriter.kt:150-155`), but Phase 3 introduced the caller-chosen path (`addRelWithId :161-165`, no dedup) fed by `freeRelId` (`VM:622-627`, scans only the as-loaded rels) → `PS-03` corruption on the second `hfCreate` |

### PHASE3_IMPLEMENTATION_PLAN.md

| Claim | Verdict | Evidence |
|---|---|---|
| `:13/:21` — `beginSection`: "clone of the section's current sectPr + chosen `w:type`" into the **new paragraph's pPr** | ⚠ implemented-as-planned | `DocxPageLayout.kt:151-163` matches the plan exactly — including the OOXML-semantics flaw: per §17.6.22 the type governs the section *terminated by that sectPr* (the pre-break section), not the post-break one. **The defect was designed in, then faithfully built** (`PS-01`, Strong Evidence). The plan never re-examined type placement |
| `:18-19` — `livePage()` "block-derived (undo-proof preview source)" / `pageLivePkg()` for export | **✗/⚠** | implemented (`VM:800-802`) but preview-side never uses either — only export does. Plan promised an undo-proof *preview*; what ships is an undo-proof *export* (`PS-04`) |
| `:26` — "All new ids come from the VM (`freeRelId` numbered scan)" | **✔ as-built** | `VM:622-627` — but the scan doesn't see session-allocated ids (`hfRelIds`) or the writer's additions → `PS-03` |
| `:20-24` — VM op set (setSectionSetup, both breaks, titlePg, HF ops, setEvenOdd) | **E** | all present at the cited roles (`VM:501-620`) — zero of them exercised by tests (§3) |
| `:48` plan item 1 — "multi-section docs change **only the edited section**" | **✗ missing test** | no test in `DocxPageLayoutTest.kt` edits a non-trailing section's `pPr`-held `sectPr` and asserts the sibling section untouched; `withSect`'s paragraph route (`kernel:126-129`) is untested. The TEST_REPORT's coverage table silently dropped this plan row |
| `:50` plan item 3 — "two sections with different orientation/margins" round-trip | **✗ missing test** | likewise absent (test list has exactly 18 names, none multi-section-geometry) |
| `:49/:54` — validation negative tests; regression byte checks incl. tracked changes | **✔** | `dimensionValidationBounds`, `sectionEditKeepsTrackedChangesAndAcceptStillWorks` |
| `:31` — strings ×7 locales | **✔** | `docx_page_setup` present in all 7 `values*` dirs |

### PHASE3_TEST_REPORT.md

| Claim | Verdict | Evidence |
|---|---|---|
| `:8` — "every round-trip op goes through real reader → edit kernel → save through `DocxWriter` → reopen through `DocxReader`, **the same pipeline the app uses**" | **E** | true at the engine layer (test helpers `:51,:53` call `DocxReader.read`/`DocxWriter.write`); but the VM op layer — where `freeRelId`, `dirty`, `hfCreate` live — is bypassed entirely: no test touches `DocxEditorViewModel` for page layout (`primeForTest` users: `DocxEditorViewModeTest`, `DocxTrackingStateTest` only). "The same pipeline" is accurate for serialization, less for the op layer |
| `:14` CI totals `41232855647` (261/0/44 at `567414c`) | ✔ (historical) | plus re-green at `60b8218` (`41328856513`) |
| `:27` — page-break fix landed (`PAGE_BREAK_OBJ` full run) | **✔** | `DocxPageLayout.kt:166`; covered by `pageBreakInsertionSurvivesSaveReopen` |
| `:28` — P3-3 section breaks ✅ via `sectionBreakInsertCarriesTypeBeforeTargetParagraph` | **⚠ key instance** | the test exists and passes — and it **locks the wrong OOXML placement** (`DocxPageLayoutTest.kt:210-232`, asserts the type inside the *inserted pre-break* sectPr). This is the report's own headline case of *"a passing OOXML round-trip does not prove the UI button works in Word"* (`PS-01`). Also note the [T] row labels the *engine* behavior; the actual buttons were never exercised (`UR` below) |
| `:32` — Header creation "part + rel + content-type + reference ✅ [T]" | **⚠** | `headerCreationAddsPartRelAndContentType` passes a **caller-chosen** id directly into the writer `Extras` (`DocxPageLayoutTest.kt:290`), skipping `freeRelId` — the exact component (`PS-03`) that breaks on the second create. Engine coverage true; the VM allocation flow untested |
| `:33` — "Reference removal leaves part bytes + rel intact ✅" | ✔, scope-limited | true for removing the reference of a **pre-existing** part; the UI flow "create (session state) → edit → remove → save → recreate" (which leaks an orphan part+rel, `PS-10`) is untested |
| `:39` — "[C] Gated ops … compile-verified" | **E** honest | accurate — and it is itself the admission that no gate has behavioral coverage |
| `:44-52` — landing log (compile fixes, 5-test failures converging) | ✔ historical | consistent with FINAL_REVIEW commit list |
| `:56` — "Continuous break … on-screen effect is not drawn" | **✔** | `Screen:361-362` skips the gap for `"continuous"` |
| `:57` — internal PDF follows live page setup via `pkg.page`; no HF drawing | **✔** with a sharpening | `VM:802` sync → `DocxPdfWriter.kt:71-78` read; no HF/titlePg rendering anywhere in `DocxPdfWriter`; **additionally** it inherits the single-paper model, so multi-section geometry is wrong there too (`PS-07`) |
| `:60` — "HF edits are package-level, not undo steps (comments precedent): intentional" | ✔ as-designed | `VM:583` comment — with the caveat that this very precedent family carries `PS-02` (no dirty) and `A3` (orphan comment) |
| `:61` — "Panel behavior … compiled + code-reviewed; instrumented UI tests are outside this phase's CI envelope" | **✔** | no `androidTest` source set exists at all (A13). This sentence is the factual root of the entire engine-vs-UI gap in §3 |

### PHASE3_FINAL_REVIEW.md

| Claim | Verdict | Evidence |
|---|---|---|
| `:21-22` engine/reader/writer descriptions | **✔** | match code reads (`SECTPR_ORDER` `DocxProps.kt:253-261`; `Extras` `DocxWriter.kt:105-133`) |
| `:23` — "`pkg.page` synced from the undo stack **before preview/PDF export**" | **✗ claim, ✔ facts** | only one sync point exists (`VM:802`, inside `exportToPdf`). The preview (`Screen:970-993`) reads the mirror **without** a sync. (Difference #1.) |
| `:31` — "`page` moved `val`→`var` … the mirror is synced only at preview/PDF touches — stale-mirror risk retained by design" | **✗ internally contradicts :23** | code supports the narrower truth: synced **only at the PDF touch** (`grep livePage` → single call site). The two review sentences disagree; the code sides with neither's "preview" wording |
| `:23` — "`resetPageLayoutExtras()` at both load sites + `primeForTest`" | **✔** | 3 occurrences in `VM` (definition + 2 load calls + hook `:114` used by two non-layout VM tests) |
| `:24` — UI inventory of LAYOUT panel | **✔** | every listed element found at the audited lines (panel `Screen:1490-1709`, HEADERS `:1713-1758`) |
| `:24` — "section breaks only on body-level targets" | **⚠** | actually: only the **first** body-level target, hardwired (`Screen:1611` → `VM:451-455`); placement unselectable (R5). Kernel guards all body-level targets already, so the panel's narrow choice adds nothing |
| `:26` — "18 tests — every op verified through save→reopen round trips" | **E** | 18 @Test confirmed (`grep`); all engine-layer, none VM/UI |
| `:30-31, :33` — safety contracts (byte-preservation, gates, sole write path) | **✔** | held in code under audit |

---

## 2. The engine-vs-UI coverage gap — control by control

Three layers exist: **engine** (reader→kernel→writer→reader round-trips), **VM state** (logic between UI and kernels: gates, allocation, dirty), **UI interaction** (dialogs, focus, feedback, save flow). Page Setup has full coverage in layer 1, none in layers 2-3.

| Control (Page Setup) | Engine test | VM-state test | UI-interaction test | UI-level verdict from this audit |
|---|---|---|---|---|
| Orientation toggle | ✔ orientationRoundTripThroughSave | ✗ | **✗ (none anywhere)** | Works in model; preview stale (PS-04), toggle-thumb artifact risk (A6) |
| Paper presets | ✔ (kernel dims) | ✗ | ✗ | PS-08 (landscape interplay) |
| 9 inch fields | ✔ dimensionValidationBounds / marginsRoundTripIndependently | ✗ (coalescing, silent-drop UX) | ✗ | PS-09 |
| Page-break pill | ✔ pageBreakInsertion… | ✗ (VM caret/coalescing path `VM:526-537`) | ✗ | Working |
| Section breaks ×4 | ⚠ "✔" but **locks wrong semantics** | ✗ (`sectionAt` `kernel:136-143`, `insertSectionBreak` wiring untested) | ✗ | **PS-01 broken effect + R5 placement** |
| First-page-different | ✔ titlePgRoundTrip | ✗ | ✗ | Works (persist) |
| Odd/Even toggle | ✔ evenOddSettingsInsertedInOrderAndRemoved (splice only) | ✗ (**dirty semantics untested — PS-02 lives here**) | ✗ | PS-02 silent loss |
| HF part edit | ✔ headerEditReplacesExistingPartAndBodyUntouched | ✗ (`hfTyped`/`hfCache` wiring; dirty untested) | ✗ | Works *when saved*; PS-02 exit-loss |
| HF create | ⚠ part+rel+content-type, **caller-chosen id** | ✗ (`hfCreate` sequence, `freeRelId` allocation — PS-03's exact home) | ✗ | **PS-03 corrupts on 2nd create** |
| HF remove | ✔ footerReferenceRemoval… (existing-part scope) | ✗ | ✗ | PS-10 orphan on create-flow |
| Section picker / apply scope | ⚠ enumeration ✔; **pPr-route selectivity test missing vs plan** | ✗ | ✗ | Works (static trace) |
| Gear entry | — | ✗ | ✗ | PS-05 over-broad gate |
| Preview geometry | — | ✗ (`livePage` unused by preview) | ✗ | PS-04 |
| Save/export of layout state | ✔ (writer engine) | ✗ (`currentFile` has-edits check) | ✗ | PS-02 companion: share/PDF of settings-only session exports the original |

**Headline numbers: 18 engine tests; 0 VM-state tests for Page Setup; 0 UI-interaction tests for the editor at all** (no instrumented source set exists). The two known UI-layer defects invisible to engine tests: PS-02/PS-03 (both live in VM state) and PS-04/PS-05 (projection/wiring). The one defect engine tests *mis-cover*: PS-01 (test asserts the very behavior that is wrong in Word).

**Why the round-trip passes while buttons misbehave** — the mechanism, stated once: round-trips validate that *what the writer writes, the reader reads back*. They cannot validate (a) that the OOXML a control writes *means* what the button says semantically (PS-01 — spec violation within valid syntax), (b) that the VM allocates consistent ids across an interactive session (PS-03 — cross-call state machine corruption), (c) that package-side state integrates with dirty/save UX (PS-02), (d) that the preview mirrors state (PS-04). Each gap maps to one of the three missing coverage layers, and none of them shows up in the 261-test CI run — which is exactly why the Phase-3 verdict signed "ready for repair-on-device" (TEST_REPORT:65).

## 3. Documented differences (documents vs current source) — consolidated

1. **Preview sync** — FINAL_REVIEW:23 and the audit-plan `:18-19` both promise undo-proof preview; code gives undo-proof *export only* (one `livePage()` call). Source-audit `:94` planned sync before "export/save" — save doesn't need it, preview didn't get it. → PS-04/R4.
2. **Doc-internal contradiction** — FINAL_REVIEW:23 vs :31 (both quoted above); the narrower `:31` wording is the code-accurate one.
3. **`w:type` placement is plan-inherited** — the plan's `beginSection` design (new paragraph = clone + chosen type) contradicts ECMA-376 §17.6.22; implemented faithfully; TEST_REPORT's ✅ [T] row validates the wrong artifact. Plan-vs-spec difference; code-vs-plan is 100% faithful. → PS-01.
4. **Rel-id generation under-covered** — audit `:100`'s "collision-free pattern unchanged" omits that the new caller-chosen pattern is not dedup-safe across a session. → PS-03.
5. **Plan items dropped without documentation** — multi-section editing selectivity (plan items 1b, 3b) never shipped as tests and never listed as a limitation in the TEST_REPORT. Coverage gap now recorded (also DV-9 below).
6. **"Only on body-level targets" narrower than stated** — first *body-level paragraph* only (placement unselectable). *Difference of phrasing with a real UX consequence.* → R5.
7. **"Gated" means silent no-op** — in read-only/view mode the LAYOUT panel opens, fields remain editable-looking, and every op is silently rejected inside the VM (no disable, no toast). Historical docs describe this as Edit-Mode gating; at the UI level it reads as an unresponsive panel (P3 UX defect, folded under PS-09/PS-05 in the register).
8. **Claims that verified clean** — flatten() section pagination (line-drifted), pkg.page legacy parsing, page-break full-run engine fix, titlePg/even-odd splices, HF part plumbing, 18-test inventory, 261/0/44 CI totals (historical) + green at this head, instrumented-gap admission.

## 4. Corrections to my own audit reports made during this revalidation

- `DOCX_PAGE_SETUP_AUDIT.md`: flow §1, inventory-note §2, **R6 restated** — preview **does** break pages at non-continuous `sectPr` paragraphs (`Screen:361-362`); the true residual limitation is **no per-section geometry** (single paper for all pages). DV-2 tooling note updated.
- `BUTTON_ISSUE_REGISTER.md`: **PS-07 rewritten** (per-section geometry, not "no boundary"); fix-order wording updated.
- `AUDIT_TEST_RESULTS.md`: §4 already stated the coverage gaps; still accurate (no test touches PS-02/PS-03/PS-04 inputs).

## 5. Updated device-verification item

- **DV-9 (new, static evidence):** open a two-section document, edit **section 1's** page setup, verify section 2 unchanged and preview/PDF remain per-section — untested at every layer (`withSect` paragraph route has no test; plan item dropped). Static read suggests the code path is correct (targeted `withSect`), but nothing asserts it — the exact class of gap this report exists to surface.

No application source was modified; all four historical documents were read in full and every Page-Setup-relevant claim in them was checked against the current checkout. Differences are recorded above; nothing in the four pre-existing audit reports is contradicted except the R6/PS-07 impressions of the audit's own reports, corrected here and in those files.
