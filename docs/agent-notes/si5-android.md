# SI5 Android notes (SI Civil as the third goal)

Written 2026-09-21. Nothing here was compiled or run; GitHub Actions is the first compile. Every file was re-read as the compiler would.

## Files changed
- `ui/today/StudyPrefs.kt`: priority is now three-way (UPSC, APPSC, SI). `priorityMode` 0 equal, 1 UPSC, 2 APPSC, 3 SI (the single strictly largest weight, else 0).
- `ui/exams/ExamsScreen.kt`: `PriorityCard` has four choices in two rows (Equal, More on UPSC, More on APPSC, More on SI (Civil)); new `SiGoalCard` ("SI (Civil) goal and checklist") opens `Routes.GOALS`; the screen calls `seedSiOnce()` when opened. The empty-list button now says "Add my exams".
- `ui/exams/ExamsViewModel.kt`: `seedDefaults()` (empty list gives the four defaults) then `addSiRowsOnce()`; new `seedSiOnce()` (existing installs, non-empty list only). A process-wide `Mutex` makes concurrent calls safe; the KV flag `goal.si_seeded` is checked first and set right after adding, so a deleted SI row is not re-added. Also skips adding when a row named "SLPRB SI (Civil)" already exists (for example synced from another device).
- `ui/nav/Routes.kt`: `GOALS = "goals"` (rail highlight: Settings, like Exams). `MainActivity.kt`: `goalsRoutes(nav)`.
- `ui/today/TodayScreen.kt` + `TodayViewModel.kt`: small "SI (Civil)" card when an SI exam row exists (`TodayUi.hasSi`); Today calls `ExamsViewModel.seedSiOnce()` once so existing installs get the SI rows.
- Plan block ref "goals": no mapping was needed. `startBlock` in TodayScreen navigates to the ref string as a route, so `ref = "goals"` opens the Goals screen because the route is registered.
- SI filter chips: `notes/NotesTreePane.kt`, `syllabus/SyllabusScreen.kt`, `SyllabusReviewScreen.kt` (two places), `SyllabusReviewParts.kt` ("Exam: SI only"), `SyllabusTree.kt` (tag filter accepts SI), `tests/TestDialogs.kt` (SI chip for past papers), `SyllabusImportDialog.kt` (button "SI (Civil)" fills exam "SLPRB SI (Civil)"), doc comments in `SyllabusViewModel.kt`, `TopicTree.kt`. Labels "Both" became "All" / "All exams".
- `ui/revise/ReviseData.kt`: `examProximity` now matches exam tags as WHOLE WORDS of the exam name (before, `contains`, so tag "SI" would match any name containing "si"). Existing behaviour for UPSC and APPSC is unchanged.
- `ui/tests/*`: `kindLabel("aptitude")` = "Aptitude drill"; button "Aptitude drill (arithmetic and reasoning)" opens `AptitudeDialog` (new file, area list and counts 10/20/30); `TestsViewModel.makeAptitude(area, count)` enqueues `test_generate` with `{kind:"aptitude", count, area?}` (area left out for Mixed). The only place that reads `Test.kind` is `kindLabel`; nothing else branches on it.
- `ui/answers/*`: `WritingPrompts.kt` (pure parse and filter), `WritingPromptLoader.kt` (thin assets reader), `PromptPickerDialog.kt`, `AnswersViewModel` (loads prompts, `usePrompt` calls the existing `createOwn(question, wordLimit)`), `AnswersScreen` (button "Practice prompt"). `AnswersViewModel` now takes an `@ApplicationContext Context`.
- `android/app/src/main/assets/writing_prompts.json`: `cp` of `data/writing/si_and_group1_prompts.json` (byte identical, checked with `cmp`).
- New `ui/goals/`: `SiRules.kt`, `GoalsData.kt` (pure), `GoalsViewModel.kt`, `GoalsScreen.kt`, `GoalsCards.kt`, `GoalsBodyCards.kt`, `GoalsParts.kt`, `GoalsRoutes.kt`.

## Tests added or changed
- New: `goals/SiRulesTest.kt`, `goals/GoalsDataTest.kt`, `answers/WritingPromptsTest.kt`; `SyllabusTreeTest.siTagIsKeptAndFiltered`; `ReviseDataTest.examProximityMatchesWholeWordsSoSiIsSafe`.
- Changed: `PlanBlocksTest.priorityAndTeluguSettings` loops modes 0..3.

## KV keys
| Key | Value |
|---|---|
| `exam.priority` | `{"UPSC":1,"APPSC":1,"SI":1}` (one of them 2.0 when chosen) |
| `goal.si_seeded` | `true` once the SI exam rows were added |
| `goal.applied` | `{"appsc-g1":bool,"slprb-si":bool}` |
| `si.profile` | `{gender:"male"/"female", category, dob:"yyyy-MM-dd"/null, local, ex_serviceman, service_years, govt_employee, ncc_instructor, abo_st, degree_done}` |
| `si.checklist` | `{degree, ssc, community, ncl_ews, local, photo_sign, fee, medical, id : bool}` |
| `si.pmt` | `{height_cm, chest_cm, chest_expanded_cm, weight_kg}` (missing key = not entered) |
| `si.pet_log` | `[{id, date, run1600 (seconds), sprint100?, longJump?}]`, oldest first, last 200 kept |

## Decisions and things I was unsure about
- Age window: I followed `born_between` exactly (born 1999-07-02 to 2005-07-01). So the limit means "not yet 27", and born 1999-07-01 (exactly 27 on 2026-07-01) is NOT eligible. With relaxation R the older end moves back R years: BC is eligible if born on or after 1994-07-02 (completed age 31 at most), not "up to 32".
- Relaxations are not added: the largest applies. Ex-serviceman and NCC instructor = 3 plus the years of service the owner types; State Government employee = the years of service, capped at 5. One "years of service" number serves whichever of these is ticked. The NCC minimum of 6 months service is not checked. The "retrenched Census employee 1991" relaxation (3 years) is in the spec but NOT in the profile (skipped, rare).
- Fee: SC/ST who are local pay Rs 300; everybody else and every non-local candidate pays Rs 600 (my reading of `fee_general`, `fee_sc_st`, `fee_non_local`).
- PMT: a men's chest expansion is expanded chest minus chest, rounded to 0.01 cm before comparing (avoids float noise like 4.999999). Heights and weights are compared without rounding.
- PET profile: women use the women's table (also if ticked ex-serviceman); men who are ex-servicemen use that table.
- `parseRun` accepts only "m:ss" (optionally with decimals in the seconds) and plain seconds. The PET form additionally requires a 1600 m time between 150 and 1800 seconds.
- `seedSiOnce()` deliberately does nothing while the exam list is empty, so a fresh install still gets the four defaults from `seedDefaults()` (setup step) and does not end up with only SI rows.
- The goals Group-I text says "closes at 11:59 PM" (from the spec file). No other dates were invented.
- Text fields for numbers (PMT, PET form, calculator, date of birth) keep their own typed text and save on each change; they read the saved values only when the screen opens. A server refresh while the screen is open does not update them until re-opened.
- Telugu prompts in the picker use `NotoSansTelugu`. The answer screen itself was not changed, so a Telugu question there uses the normal font (system fallback).

## Not done
- No Group-I DSP rules (unknown until the Detailed Notification).
- No Urdu, no figure-based reasoning.
- No unit test for `ExamsViewModel` seeding (needs Android classes); it is a small function, please check it on the tablet: after update, Exams shows three "SLPRB SI (Civil)" rows once; delete one and reopen Exams, it must not come back.

## Manual checks on the tablet
1. Exams: choose each of the four priority chips; the choice stays after leaving and re-opening.
2. Exams: tap "SI (Civil) goal and checklist"; the Goals screen opens; Back returns.
3. Goals: enter a date of birth (day, month, year); the age line changes; try 2005-07-01 (eligible), 2005-07-02 (too young).
4. Goals: PMT numbers show Pass or Short with the shortfall; ABO-ST switches the limits.
5. Goals: add a PET entry with 8:00 and a long jump of 3.80; it shows pass; delete works; "Best so far" updates.
6. Tests: "Aptitude drill" asks area and count; the job is queued (and later a Test with kind "aptitude" arrives, labelled "Aptitude drill").
7. Answers: "Practice prompt" lists prompts; filter chips work; choosing one opens a new draft with the prompt as the question, in airplane mode too.
