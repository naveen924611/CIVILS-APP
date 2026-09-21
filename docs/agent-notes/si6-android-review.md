# SI6 Android review (independent, skeptical read of the SI (Civil) goal change)

Written 2026-09-21. Nothing was compiled (no Gradle here); this is a by-hand compile check plus hand-recomputed test expectations.
Result: no definite compile error and no failing unit-test expectation found, so no source file was edited.

## What was checked
- All 25 modified and all new Kotlin files (40 files scanned) for nested block comments (a script that skips string literals and line comments): none.
- Every call into existing code was compared with its real definition: `CcCard(modifier, onClick, content)`, `ScreenTitle(title, modifier, subtitle, actions)`, `Pill(text, modifier, tone)`, `BigButton(text, onClick, modifier, filled, enabled)`, `SectionLabel`, `Stepper(label, valueText, onMinus, onPlus, modifier, canMinus, canPlus)`, `ChipButton`/`ActionText` (ui.ask), `TChip` (ui.tests), `KvRepository.get/put/observe`, `TimeUtil.india/today/newId`, `NotoSansTelugu`, `AnswersViewModel.createOwn(String, Int)`, `TestsViewModel.enqueue(vararg Pair<String, Any?>)`, `Exam(...)` constructor, `SyllabusTree.keepExam`, `ReviseData.examProximity`.
- Build facts: minSdk 28 (java.time is fine, no desugaring needed), Kotlin 2.2.20, JVM target 17, Compose BOM 2025.09.00, kotlinx.serialization 1.9.0, no warnings-as-errors. No `reversed()` on lists (the code uses `asReversed()`).
- `assets/writing_prompts.json`: top-level `items` array of 90 objects, all with keys key, exam, language, paper, form, marks (int), word_limit (int), prompt, source; keys are unique (needed for the LazyColumn `key`); `version` and `note` are ignored by `ignoreUnknownKeys = true`. Matches `WritingPrompt`.
- Aptitude area ids in `AptitudeAreas` (22 areas) equal `AREAS` in `backend/app/features/tests/aptitude.py`.
- SiRules numbers against `data/exam-specs/slprb_si_2026.json`: age window 1999-07-02 to 2005-07-01 (ref 2026-07-01, 21 to 27), relaxations 5/5/5/5, govt up to 5, ex-serviceman and NCC 3 plus service; PMT men 167.6/86.3/5, women 152.5/40, ABO-ST men 160/80/3 and women 150/38; PET 1600 m 480/570/630 s, 100 m 15/16.5/18 s, long jump 3.80/3.65/2.75 m; prelim cut-offs OC 40, EWS 40, BC 35, SC 30, ST 30 (each paper); fee 600, SC/ST 300, non-local 600. All equal.
- Unit-test expectations recomputed by hand: SiRulesTest (all 17 tests), GoalsDataTest (all 16), WritingPromptsTest (4), PlanBlocksTest loop 0..3, ReviseDataTest whole-word test (90 days = 0.5), SyllabusTreeTest.siTagIsKeptAndFiltered (keepExam inheritance). All hold.

## Findings table
| File | Line | Problem | Fix or "left as is because" |
|---|---|---|---|
| all touched .kt | - | Nested comment risk (slash-star inside a comment) | None found (script-checked). `image/*` in AnswerScreen is inside a string and untouched. |
| ui/goals/SiRules.kt | 176-181 | `measure()` rounds the shortfall to 0.1: a miss under 0.05 (for example height 167.57) shows "short by 0 cm", and 86.25 chest is displayed as 86.3 but fails | Left as is because: only reachable with 2-decimal input, the pass/fail verdict itself is right (compared with 1e-9 tolerance, not rounded). Cosmetic. |
| ui/goals/GoalsCards.kt | 66-83 | Date of birth is saved only when day, month and year form a real date; clearing a box never clears the saved dob, so the age line keeps the old result | Left as is because: safe (never saves garbage), the hint line "Write a real date" is shown. Cosmetic. |
| ui/exams/ExamsViewModel.kt | 79-97 | `addSiRowsOnce` holds the process-wide lock while `kv.put` does its network call | Left as is because: put returns false quickly when offline and the lock only serialises seeding. |
| ui/exams/ExamsViewModel.kt | 84 | On a second device whose KV has not synced yet, `goal.si_seeded` reads false, so a deleted SI row could be re-added once (the "name already exists" check covers the normal case) | Left as is because: rare, one-off, harmless. |
| ui/answers/PromptPickerDialog.kt, ui/tests/AptitudeDialog.kt | - | LazyColumn inside an AlertDialog text slot | OK: bounded with `heightIn(max = ...)`, so no infinite-height crash. Keys are unique strings. |
| ui/goals/GoalsData.kt | 34-44 | JSON helpers: `booleanOrNull` on a string primitive "false" returns false, `intOrNull` on "7" returns 7 | OK: tests rely on this and it holds for kotlinx.serialization 1.9. |

## Riskiest remaining spots (first CI run)
1. `GoalsCards.kt` / `GoalsBodyCards.kt`: many lambdas of type `((T) -> T) -> Unit` called as `onChange { it.copy(...) }`; type inference looks right (checked), but this is the densest generic code.
2. `SiRules.Measure.text()`: smart cast of `value` inside a subject-less `when` (valid Kotlin, same module, `val`).
3. `GoalsData.checklistJson`: explicit type arguments on `mapValues<String, Boolean, JsonElement>`.
4. `AnswersViewModel` now needs `@ApplicationContext Context` (Hilt provides it; no test builds this class).
