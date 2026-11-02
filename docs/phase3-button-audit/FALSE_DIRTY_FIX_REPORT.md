# HMXPDF — DOCX False Dirty State: Root-Cause Fix Report

Branch `release/hmxpdf-2.1.1-build`. Reported: an unedited DOCX opens with the blue Save dot and Android Back shows "Save changes?" although nothing was modified.

## 1. Root cause (exact evidence)

The reported symptom is **not** about rendering, pagination, scroll/zoom, mode switching or lifecycle callbacks. Loading assigns one identical state instance to both the live document and the saved baseline (`DocxEditorViewModel.kt` `load()`: `doc = parsed.second; saved = parsed.second; original = parsed.second`), so the body half of `dirty` was clean from the first frame.

The false positive came from the **settings half** of the dirty computation:

| Pre-fix code | Effect |
|---|---|
| `wantSettings` defaults to `null` ("no settings edit this session") — `DocxEditorViewModel.kt:591` | untouched session state |
| `resetPageLayoutExtras()` sets `packageSavedSettings = pkg?.settings` — `:693` | a real Word document's loaded `word/settings.xml` |
| `val dirty … = … \|\| wantSettings != packageSavedSettings \|\| …` — `:98-99` | `null != "<w:settings…>"` ⇒ **dirty immediately on open** |

`DocxReader` loads `word/settings.xml` whenever the part exists (`DocxReader.kt:116-117`, `:139`) and virtually every Word-authored DOCX contains it. The test suite missed this because every existing fixture was settings-less, where `null == null` hid the bug. With `dirty == true` the header shows the Save dot (`DocxEditorScreen.kt:902`), prefixes the title with `•` (`:895`), and Back opens the unsaved dialog (`requestBack` at `:843`, `BackHandler` at `:847`, dialog at `:1797`).

## 2. Fix

`DocxEditorViewModel.kt`:

- New `effectiveSettings() = wantSettings ?: pkg?.settings` — the settings part the session would actually save.
- `dirty` compares `effectiveSettings()` against the saved baseline (was: raw `wantSettings`).
- `recordSaved()` baselines `effectiveSettings()` (was: raw `wantSettings`), so a save with no settings edit cannot leave the document dirty against its own loaded settings part.

No UI change, no suppression: the dot and dialog still read the same single `dirty` predicate; they are simply correct now.

## 3. Files modified

| File | Change |
|------|--------|
| `app/src/main/java/com/hmx/pdf/ui/viewmodel/DocxEditorViewModel.kt` | effective-settings dirty comparison + save baseline |
| `app/src/test/java/com/hmx/pdf/ui/viewmodel/DocxPageSetupViewModelTest.kt` | fixture gains a real `word/settings.xml` (part + rel + content type); 2 new lifecycle tests |
| `app/src/test/java/com/hmx/pdf/ui/viewmodel/DocxEditorViewModeTest.kt` | insert/delete dirty assertions + `deletingContentMarksDirty` |

## 4. Tests added

- `openingSettingsBearingWordDocumentIsCleanInViewAndEditMode` — open a Word-like package with `settings.xml`: clean in View Mode, clean after repeated section/page-setup/layout reads, clean after entering Edit Mode. **Fails pre-fix** (`dirty == true` on line 1 of the test).
- `settingsBearingDocumentGoesCleanAfterSaveAndStillDirtyOnRealSettingsEdits` — body edit dirties; save baselines the loaded settings part; even/odd on dirties; on→off restores loaded bytes and cleans; on + save cleans.
- `enterKeyBlockedInViewMode` — now asserts insert dirties (and View Mode insert does not).
- `deletingContentMarksDirty` — split then merge (delete) stays dirty.

Existing coverage retained: typing/formatting dirty in Edit Mode, every mutation blocked in View Mode, `recordSaved` baseline.

## 5. Verification

| Environment | Result |
|---|---|
| Local unit tests / builds | **Not run** — per the user's instruction this device is low on resources; no local Gradle/APK execution. |
| CI (`android.yml`: `testPlayDebugUnitTest`, lint, signed release) | Runs on push. Status to be appended. |

## 6. Remaining limitations

- Compose-level Back/dialog behaviour has no JVM test (no instrumented runner in this repo/CI); it is covered indirectly because the dialog and dot are strictly `viewModel.dirty`-driven, which is now test-covered.
- `save()` requires an Android `Context`; its failure path is inspection-verified (baseline only recorded on `Saved`, exit only on `Saved`).
- The post-save `pkg.settings` mirror is not refreshed; the dirty baseline uses the effective written value, so dirty is correct, but a settings-less file that gained a settings part via save keeps the old `pkg` view for later part-removal edgecases (pre-existing, unrelated to this report).
- Device checks: open an unedited Word DOCX → no dot; Back exits directly; touch nothing else. Then type one character → dot appears, Back shows the dialog; Discard exits; Save exits and re-opening the saved file is clean.
