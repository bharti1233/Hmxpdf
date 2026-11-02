# HMXPDF — DOCX Editor: Page Setup Audit

Branch `release/hmxpdf-2.1.1-build` @ `60b8218`. Companion reports: `FULL_SOURCE_CODE_AUDIT.md` (app-wide), `BUTTON_ISSUE_REGISTER.md` (prioritized), `AUDIT_TEST_RESULTS.md` (test/build evidence).

Method: full execution-path tracing **UI control → event handler → callback → ViewModel → kernel/model → DOCX writer → saved file → reopen/render**, plus existing-round-trip test review. Static analysis only — no local builds or device runs were performed (per audit constraints). Every claim below carries a file:line reference; Word-rendering consequences are explicitly marked *Needs Device Verification*.

The one unit system end-to-end is **twips** (`w:pgSz`/`w:pgMar` native), so no unit-conversion bug exists anywhere in this flow (UI converts inches↔twips at `DocxEditorScreen.kt:1500-1508` only).

---

## 1. The flow, as built

- **UI**: one dock entry — the gear `GlassToolButton` (`DocxEditorScreen.kt:1103`) opens `DocPanel.LAYOUT` (a glass dialog defined at `DocxEditorScreen.kt:1490-1709`); the header/footer sub-dialog `DocPanel.HEADERS` is at `:1713-1758`. Toolbar gating flag: `has = activePara != null` (`:1091`).
- **Panel state**: `sections = viewModel.sectionsOf()` (`:1493`, → `DocxPageLayout.sections`), picker index `sel` (`:1495`), current section's `PageSetup cur = DocxPageLayout.pageSetupOf(section.sectPr)` (`:1497`).
- **Commit primitive**: `commitIf` (`:1502-1508`) — a field applies when its text parses as inches (last char must be a digit) and passes the kernel bounds; each field coalesces undo under key `"pg:m<i>"` etc. via `viewModel.setSectionSetup(section, apply(parsed), key)`.
- **ViewModel section ops** (`DocxEditorViewModel.kt:475-637`): `setSectionSetup` (`:501-507` → `DocxPageLayout.withSect`/`withPageSetup`), `setFirstPageDifferent` (`:509-512`), `insertSectionBreak` (`:515-523`), `insertPageBreak` (`:526-537`), `setEvenOddHeaders` (`:542-552`), HF ops (`:556-636`) and package-extras plumbing `extras()` (`:644-648`).
- **Kernels** (`docx/DocxPageLayout.kt`, 231 lines): `pageSetupOf :54-64`, `withPageSetup :81-82`, `titlePg/withTitlePg :84-90`, `sectionType/withType :93-99`, `withReference/reference :106-120`, `withSect :123-131`, `sectionAt :136-143`, `insertSectionBreak :151-163`, page-break OBJ `:166`, `freePart :169-175`, `hfXml/hfState :178-192`, even/odd splice `:196-230`. Every op rewrites only the children it touches; unknown `sectPr` children (`cols`, `docGrid`, `pgBorders`…) survive character-for-character.
- **Dirty & save**: `dirty = doc != null && doc !== saved` (`DocxEditorViewModel.kt:88`, reference compare of the `DocState` snapshot). `save()` (`:672-701`) → `buildPatched` → `DocxWriter.write(p, d, src, out, extras())` (`:658`), where `extras()` carries `hfChanged` (HF part contents), `hfRelIds` (new rel ids), and `wantSettings` (spliced settings.xml) — writer splice at `DocxWriter.kt:105-133`.
- **Reopen/render**: `DocxReader.kt:99` parses the *body trailing* `sectPr` into `DocxPackage.page` (a plain snapshot field). Paragraph pagination in `flatten()` honors `lastRenderedPageBreak` marks and page-break objects (`DocxReader.kt:218`) **and** paragraph-held section boundaries — `DocxEditorScreen.kt:361-362` ends the page at any paragraph whose `pPr` holds a `sectPr` not containing the substring "continuous" (raw substring check, not a parsed `w:type`). But all pages are laid out with the one paper/margins set read from `pkg.page` (`DocxEditorScreen.kt:970-993`) — the reader parses no per-section geometry.
- **PDF export**: `exportToPdf` (`DocxEditorViewModel.kt:800-824`) syncs `pkg?.page = livePage()` (`:802`) and falls back to `DocxPdfWriter` / Office Engine.

---

## 2. Control inventory and verdicts

Verdicts: **Working** · **Working w/ defect** (wired correctly, minor bugs) · **Partial** (works in some circumstances) · **Broken effect** (the XML produced does not do what the control promises).

| # | Control | Source | Verdict | Notes |
|---|---------|--------|---------|-------|
| 1 | Gear entry (toolbar) | `Screen:1103`; gate `:1091` | Working w/ defect (PS-05, P3) | Opens LAYOUT; disabled whenever no text paragraph is focused — including a freshly opened document — so it *looks* dead exactly when a user first hunts for it. Page setup needs no paragraph; the gate concept is wrong for this button (it is right for B/I/U). |
| 2 | Section picker | `Screen:1517-1534` | Working | Clickable rows list every section with its size; selection re-derives `section`/`cur` and re-seeds the inch fields (`:1584` remembers per `sel`). Hidden for single-section docs. |
| 3 | Orientation toggle | `Screen:1541-1545` | Working w/ defect (PS-08) | Correct model change (dimension swap + `w:pgSz/@w:orient`, kernels `:71-82`; round-trip covered). Defects share root causes R2/R4 (silent no-dirty, stale preview); interacts badly with paper presets — see PS-08. |
| 4 | Paper presets A4/Letter/Legal | `Screen:1548-1571` | Working w/ defect (PS-08, P3) | Writes real twip dims. In a landscape section a preset lands portrait dims while `orient="landscape"` survives (mismatched `pgSz`); the `chosen` highlight compares exact dims so it also disappears after an orientation swap. |
| 5 | Page width | `Screen:1573-1574` + `commitIf` | Working w/ defect (PS-09) | `validDim` 144–31680 twips (0.1"–22"). UX issues: comma-locale entry ("1,5") silently never commits; intermediate values commit (e.g. margin "0" appears mid-typing of "0.75"); out-of-bounds and unparseable input fail silently with no error surface. |
| 6 | Page height | `:1574` | Working w/ defect (PS-09) | Same. |
| 7–10 | Margins top/bottom/left/right | `:1575-1578` | Working w/ defect (PS-09) | `validMargin` 0–31680. Same UX issues; commits round-trip and persist (covered by `DocxPageLayoutTest`/`DocxRoundTripTest`). |
| 11 | Header distance | `:1579` | Working w/ defect (PS-09) | Same commit path. |
| 12 | Footer distance | `:1580` | Working w/ defect (PS-09) | Same. |
| 13 | Gutter | `:1581` | Working (persist) / no visual | Value is written to `w:pgMar/@w:gutter` and survives; nothing renders gutter anywhere (reader ignores it). Not a defect of this panel; documented as preview limitation. |
| 14 | Insert page break | `Screen:1609-1623` → `VM:526-537` | Working | Targets the focused paragraph with its caret (`pid = focusedField ?: firstEditableId()`), inserts the structural break object (`DocxPageLayout.kt:166`) through the text kernel; reader renders the gap (`DocxReader.kt:218` + OBJ). Covered by tests. |
| 15 | **Next Page section break** | `Screen:1625,1636-1638` → `VM:515-523` → kernel `:151-163` | **Broken effect** (PS-01/PS-06) | Wire is complete and produces well-formed XML, but see root causes **R1** and **R5**: placement is hardwired to document top and the break `type` is stamped on the wrong `sectPr`. In Word, effectively identical to the other three (Needs Device Verification for the exact Word symptom). |
| 16 | **Continuous section break** | same + `type="continuous"` | **Broken effect** | Same; the `continuous` semantics never reach the section that actually starts after the break → behaves like Next Page in Word (R1). |
| 17 | **Even Page section break** | same + `"evenPage"` | **Broken effect** | Same. |
| 18 | **Odd Page section break** | same + `"oddPage"` | **Broken effect** | Same. |
| 19 | Different First Page toggle | `Screen:1650-1652` → `VM:509-512` | Working | `titlePg` kernel correct (`:84-90`); round-trip verified. In-app effect not visualized (preview has no header/footer variant rendering). |
| 20 | **Different Odd/Even toggle** | `Screen:1659-1661` → `VM:542-552` | **Partial** (PS-02, P1) | Writes `wantSettings` + `settingsChanged` (spliced into settings.xml only at save — writer `:122-130`). **Never touches `doc` → `dirty` stays false → no save dot and no exit prompt** (R2). Editing looks lost-free but exits silently discard it unless some body edit happens first. |
| 21 | Header · Default row (edit/create/remove) | `Screen:1664-1706` → `VM:597-620` | **Partial** (PS-03/PS-02/PS-10) | Edit works and persists when saved (HEADERS panel, `VM:573-595` / `Screen:1713-1758`). `hfCreate` works exactly once per session; a second create in the same session produces a **duplicate `rId` → invalid relationships part → Word repair prompt** (R3, PS-03). Text edits never dirty (R2). Remove/recreate leaves orphan part+rel (R9). |
| 22 | Header · First Page row | same | Partial / same defects | Additionally has no effect in Word unless `titlePg` is also set (legal Word behavior, not enforced or hinted — by design). |
| 23 | Header · Even Page row | same | Partial / same defects | Additionally inert in Word unless the odd/even setting (control #20) is on — again legal nuance the panel doesn't communicate. |
| 24–26 | Footer · Default/First/Even rows | same | Partial / same defects | Same three defects as the header rows. |
| 27 | HEADERS panel text field | `Screen:1713-1758` → `hfTyped` `VM:584-595` | Working w/ defect | Edits reach `hfChanged` and are serialized through the same paragraph kernel (`DocxPageLayout.hfXml :178-184`) — fonts/styles survive. No undo step (deliberate, "comments precedent" comment `:583`), but the R2 dirty gap applies. |
| 28 | Apply scope (per-section) | `commitSect`/`withSect` `VM:493-498`, kernel `:123-131` | Working | The chosen target section — paragraph-held `sectPr`, body-level trailing `sectPr`, or a synthetic empty one — is edited in place; `withPageSetup` preserves unknown children. Verified by tests. |

Also observed — *corrected during claims revalidation (§ report `PHASE3_CLAIMS_REVALIDATION.md`)*: the preview **does** paginate at inserted (non-continuous) section-break paragraphs (`DocxEditorScreen.kt:361-362`), so an earlier draft's "preview cannot show section boundaries" was wrong. What the preview genuinely cannot show: header/footer part contents, first-page variants, and per-section paper/margins — every rendered page uses the trailing section's `pkg.page` (`DocxReader.kt:99-107`), root cause **R6** restated below. An Even/Odd break is drawn as a plain new page just like Next Page — indistinguishable in-app; only Word would tell them apart.

---

## 3. Root causes

### R1 — Section-break `w:type` is stamped on the wrong `sectPr` (P1 · Strong Evidence)
`DocxPageLayout.insertSectionBreak` (`docx/DocxPageLayout.kt:151-163`) builds the inserted boundary paragraph from a **modified copy** of the source section's sectPr:

```kotlin
val sectPr = withType(source.sectPr, type)      // :153 — type stamped on the copy
val np = DocPara(..., pPr = Props.setP("", "sectPr", sectPr), ...)   // :154-157
```

The **original** `source.sectPr` (which after the insert terminates the section containing everything *after* the break) is left untouched (`:158-161` only inserts `np`).

Per ECMA-376 / ISO 29500 Part 1 §17.6.22 (`w:type`, MS OpenXML `SectionType`): the type of a `sectPr` says **how the section *that this sectPr terminates* begins, relative to the previous section**. A `sectPr` in a paragraph's `pPr` *ends* the section up to that paragraph. So the code stamps the chosen type onto the pre-break (split-off) section — whose start is inert for a document-first section — and never changes the post-break section's start, which is the thing the button is supposed to control.

**Net effect:** all four break buttons (Next Page / Continuous / Even Page / Odd Page) produce the same OOXML behavior; `continuous`/`evenPage`/`oddPage` are no-ops in Word. *The correct shape*: stamp `withType` on the source `sectPr` **after** inserting the boundary paragraph (the copy carried by the inserted paragraph keeps the pre-break section's original start type). *(Strong Evidence: code + spec; the on-screen Word symptom — and whether Word also shows a phantom empty first page — Needs Device Verification, DV-2/DV-3.)*

**Test debt:** `DocxPageLayoutTest.sectionBreakInsertCarriesTypeBeforeTargetParagraph` (`app/src/test/java/com/hmx/pdf/docx/DocxPageLayoutTest.kt:210-232`) **locks the current, wrong placement** (`inserted.pPr.contains("<w:type w:val=\"continuous\"/>")`). It must be rewritten, not preserved, when R1 is fixed.

### R2 — Package-side changes never become dirty → silent loss (P1 · Confirmed)
`dirty` (`DocxEditorViewModel.kt:88`) compares the `DocState` snapshot: `doc !== saved`. Three Phase-3 controls mutate state that lives **beside** `doc`:

- `setEvenOddHeaders` (`:542-552`) — writes `wantSettings`/`settingsChanged` only;
- `hfTyped` (`:584-595`) — edits header/footer paragraph text into `hfChanged` only;
- (consequence for) any HF part created earlier — its text edits likewise never touch `doc`.

`dirty` therefore stays `false`: no save-dot indicator, and the BackHandler dirty-exit dialog (`DocxEditorScreen.kt:820-886`, `:1760-1771`) reports the document as clean — the user exits and silently loses even/odd and header/footer edits (DV-4). The data itself is not lost *if* a save runs (extras splice `VM:644-648` → `DocxWriter.kt:105-133` verified working).

**Companion defect (same root, P2):** `currentFile` ("Open as PDF" / share) tests `doc !== original` (`VM:784-787`) — a settings/HF-only session is treated as unedited, so share/PDF-export of such a session publishes the **original** document without the header/footer or odd/even changes.

### R3 — Duplicate relationship IDs across header/footer creates → corrupt package (P1 · Confirmed)
`freeRelId` (`DocxEditorViewModel.kt:622-627`) computes `max(rId#)+1` scanning **only** `pkg?.relsXml` — the as-loaded rels string. Session-assigned ids (`hfRelIds`, `:559`, `:610`) and anything written at save are invisible; `pkg` is never refreshed after a save either (`save()` `:672-701` only sets `saved`; `resetPageLayoutExtras` runs at load, `:638-641`).

Writer-side there is no safety net: `addRelWithId` (`DocxWriter.kt:161-165`) appends the caller-chosen id without any dedup (`addRel`'s own auto ids use a disjoint `rIdCpN` namespace, `:150-155`, so only the HF path can collide).

**Net effect:** the **second** `hfCreate` in one session (any two header/footer variants — e.g., create Header Default, then create Footer Even; even across a successful save in between) receives the same `rId` as the first. At save the relationships part carries two `<Relationship Id="rIdN">` entries → invalid package → **Word opens with a repair prompt**, risking repaired/dropped content (DV-1). The panel itself shows both rows referencing the same id (`Screen:1676` prints `rid`), a visible tell.

### R4 — Preview page geometry is frozen at load (P2 · Confirmed)
`DocxPackage.page` is a plain snapshot field set at load (`DocxReader.kt:99-107`) and exactly once more inside `exportToPdf` (`DocxEditorViewModel.kt:802`). `livePage()` (`:481-485`) exists to read the undo stack, and its own doc comment says "preview and PDF follow the undo stack, never a stale value" — but it has **exactly one call site** (`grep`: only `:802`). The on-screen page chrome and scale factor read `pkg.page` and its margins (`DocxEditorScreen.kt:970-993`).

**Net effect:** page size / orientation / margin commits are invisible in the editor preview until an export happens to run (PDF button). The Phase-3 final review's claim that `pkg.page` is "synced from the undo stack before preview/PDF export" is accurate for export only — **not** for preview. `docs/phase3/PHASE3_FINAL_REVIEW.md:23`.

### R5 — Section-break placement is hardwired to document top (P2 · Confirmed)
`Screen:1611`: `val sbid = viewModel.firstBodyId()` (`VM:451-455` = first body-level editable paragraph). Every section break is inserted **before the document's first paragraph**:
- the user cannot choose where it lands (no caret/target selection, unlike the page-break pill at `:1609-1623`);
- the inserted empty paragraph holding a `sectPr` becomes an **empty leading section** in Word — a phantom section at the top (DV-3).

The inline comment (`Screen:1605-1607`) claims this is to avoid section breaks inside table cells; the valid restriction (body-level only) is implemented in the kernel already (`DocxPageLayout.kt:149-152` checks `beforeId` is a body-level paragraph via `mapContainerOf`), so the panel's own hardcode is an unnecessary over-restriction.

### R6 — The reader models no per-section geometry (P3 · Confirmed · corrected 2026-11-02)
**Correction:** an earlier draft claimed the preview "cannot show section boundaries at all" — wrong. `flatten()` breaks the page at every non-continuous `sectPr` paragraph (`DocxEditorScreen.kt:361-362`), confirming the pre-implementation claim in `docs/phase3/PHASE3_SOURCE_AUDIT.md:59` (line 358 then, 361-362 now).

The *actual* limitation: sections are enumerated as **boundaries only** — the reader parses page geometry from the body trailing `sectPr` alone (`DocxReader.kt:99-107`), so every preview page — and every internal-PDF page (`DocxPdfWriter.kt:71-78` reads the same `pkg.page`) — is drawn with the **final** section's paper and margins even when a multi-section document's sections differ. Side details: the `continuous` check is a substring test on the sectPr XML string rather than a parsed `w:type` attribute (fix opportunistically alongside R1); DocxPdfWriter routes `flatten()`'s gap/section items as page starts (`DocxPdfWriter.kt:131-134`), so exported fallback PDFs mirror the preview, per-section setups still not honored.

### R7 — Gear gated on paragraph focus (P3 · Confirmed)
`Screen:1091` `val has = activePara != null`; gear `:1103` `enabled = has`. Correct for B/I/U/alignment (they need a target paragraph), wrong for a layout dialog. Harmless to correctness (VM ops double-gate on `editMode`/`editable`, e.g. `VM:493-494,501-503`) but it manufactures the "gear button does nothing" report.

### R8 — Field-entry UX defects (P3 · Confirmed)
`commitIf` (`Screen:1502-1508`):
- `txt.lastOrNull()?.isDigit() != true` return → typing "0.75" first commits margin **0** ("0" is a whole number) as its own undo step, then "0.75" commits again;
- `toDoubleOrNull()` rejects comma locales ("1,5" silently never applies — no error surface);
- out-of-bounds entries sit in the field and are silently dropped at commit; no message explains why.
`KeyboardType.Decimal` (`:1596`) allows "." only, but soft keyboards with comma default locales still produce "," on some devices (DV-5).

### R9 — Header/footer remove/recreate leaks parts and rels (P3 · Confirmed)
`hfRemove` (`VM:616-620`) strips only the section reference. `hfChanged[path]`/`hfRelIds[path]` survive, so a save still **writes the part and its relationship** even with zero references (writer `:105-121`; for pre-existing parts the pristine zip copy keeps the original bytes, `:174-193`). Recreating the variant allocates the *next* part name (`freePart`, kernel `:169-175`) — the old file remains in the package forever. Bloat, not corruption; Word tolerates unreferenced parts (DV-6 optional).

---

## 4. Verified-fine (do not churn when fixing)

- **Field-value round-trips**: `pgSz`/`pgMar` rewrites touch only their own children in order-preserving fashion (`DocxProps.setSect`/`SECTPR_ORDER`, `DocxProps.kt:253-261`), and `DocxPageLayoutTest` + `DocxRoundTripTest` verify persistence.
- **Section targeting**: `withSect` (`kernel:123-131`) edits the correct carrier (paragraph pPr / raw body sectPr / synthetic creation).
- **Page break**: kernel object + reader rendering + tests.
- **`titlePg`**: correct element add/remove; round-trip verified.
- **Even/odd splice**: placement per the settings schema child order (`kernel:201-230`); writer handles part-present vs part-absent packages (`DocxWriter.kt:122-130`).
- **HF part plumbing**: part + rel + content-type override all written together (`DocxWriter.kt:105-121`); part-name allocation avoids existing names (`kernel:169-175`).
- **Undo/coalescing** of panel ops; **write byte-preservation** (`DocxWriter.rewrite :174-203`).

## 5. Test coverage present / absent

Present (`app/src/test/java/com/hmx/pdf/docx/DocxPageLayoutTest.kt`, 18 @Test): bounds rails, `pageSetupOf`/`withPageSetup`, `withSect` targeting, section-break insert (locks the R1 behavior — see R1 note), `freePart`, `titlePg`, even/odd splice, HF part naming.

Absent: any test for (a) `dirty` semantics of package-side state (R2), (b) multiple `hfCreate`s in one session / rel-id uniqueness (R3), (c) preview geometry sync (R4), (d) *placement* of section breaks (R5), (e) `w:type` semantics versus a real Word document. Regression tests for each are proposed in `BUTTON_ISSUE_REGISTER.md`.

## 6. Device-verification checklist

| ID | What to verify | Tool |
|----|----------------|------|
| DV-1 | Word's behavior opening a file after 2 `hfCreate`s in one session + save (repair prompt? content kept?) | Word Android/desktop |
| DV-2 | Render in Word: doc with app-inserted "Continuous" break — does content continue on the same page, or does it start a new page (and is there an empty leading section from R5)? The in-app preview hides continuous boundaries by design (`Screen:361-362`) — compare against Word, not the preview | Word |
| DV-3 | Same with Even/Odd break buttons — confirm they too behave identically today | Word |
| DV-4 | Toggle odd/even (or edit a header), press back — confirm no exit prompt appears and edit is lost without saving | Device |
| DV-5 | Typing margins with a comma-locale keyboard — confirm silent no-commit | Device, locale e.g. de_DE |
| DV-6 | Reopen a remove-then-recreate HF document; confirm unreferenced part is tolerated, no repair prompt | Word |

Bottom line: **no Page Setup control is a dead wire** — every listener reaches a real VM op and a real kernel — but the four section-break buttons are functionally indistinguishable (R1), two controls silently lose data on exit (R2), and one control can corrupt the saved package (R3). Fix order and regression tests: see `BUTTON_ISSUE_REGISTER.md`.

---

## 7. Fix status (implementation wave, 2026-11-02 @ `60b8218`, uncommitted)

Full details: `PHASE3_BUG_FIX_REPORT.md`; test evidence: `PHASE3_REGRESSION_TEST_REPORT.md`.

| Root cause | Status | Shape of the fix |
|------------|--------|------------------|
| R1 | **Fixed** (kernel-verified; Word render = DV-2/DV-3) | `insertSectionBreak` inserts the boundary paragraph with an **unmodified byte-copy** of the source sectPr (minimal `<w:sectPr/>` when the document has none), then rewrites the source terminator itself with the chosen `w:type` (`withType`); `type = null` ("Next Page") strips a non-default type so the split-off section starts on Word's default fresh page. The locking test was rewritten to the ECMA-376 §17.6.22 semantics. |
| R2 | **Fixed & test-verified** (exit prompt on device = DV-4) | `dirty` also flags `wantSettings != packageSavedSettings` and any session `hfChanged` not yet in `packageSavedHf`; the baselines are seeded at load and refreshed via `recordSaved` when a save reports success; `currentFile.hasEdits` counts settings/HF edits; the even/odd toggle round-trip lands back on the loaded settings part (a settings-less package returns to *no part*, never an empty `<w:settings>` shell). |
| R3 | **Fixed & test-verified** | `freeRelId` scans the loaded rels **plus** session `hfRelIds`; writer `addRelWithId` deduplicates an id representing the same type+target and throws (fail-loud save) on a real conflict. |
| R4 | **Fixed** (visual = device) | Preview chrome reads `viewModel.livePage()`; PDF export retains its existing `pkg.page = livePage()` sync. No competing page-setup state introduced. |
| R5 | **Fixed** (DV-3) | Break placement: focused body-level paragraph (`isBodyLevel`), falling back to the first *top-level* editable paragraph (`firstBodyLevelId`); `firstBodyId` is gone; hint text explains placement. |
| R6 | **Not fixed (by design this wave)** — per-section geometry stays a documented limitation | `flatten()`'s boundary check was replaced by a precomputed holder→following-`w:type` map (parsed semantics instead of the substring check), so page breaks at section boundaries follow the spec; each page is still drawn with the trailing section's paper/margins. |
| R7 | **Fixed** (visual = device) | Gear `enabled = pkg != null`. |
| R8 | **Partially fixed** (DV-5) | `,`→`.` normalization before parse + invalid/out-of-bounds fields tint `danger(...)` instead of failing silently; fields disabled in view/read-only. The intermediate-commit debounce (blur-based commit) is deferred on purpose — see register PS-09. |
| R9 | **Fixed & test-verified** | `hfRemove` purges the session's part text and rel claim once no section references the rid; a surviving variant of the same part is untouched. |

**Additional root cause found and fixed during the wave** (register appendix): a `Section` captured before another panel edit rebased session ops on the stale terminator, silently dropping sibling edits — `commitSect` now resolves the section's *current* `sectPr` (identity by `paraId`/`rawId`), refuses a vanished synthetic section, and treats content-preserving rebases as no-ops (no phantom undo entries). Same class as R2/R5: present only in the panel's session chain, covered by the VM tests.
