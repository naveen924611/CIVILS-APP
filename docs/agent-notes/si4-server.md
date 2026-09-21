# si4: server side of the SI (Civil) goal

Written 2026-09-21 by the server agent. Everything below is under `backend/` unless a path says otherwise. No commit was made.

## What changed, file by file

| File | Change |
|---|---|
| `app/features/examnames.py` (new) | `has_word(text, word)`: whole-word, case-insensitive match (boundaries = anything but letters and digits). `is_si_exam(name)`: whole word `si` or `slprb`. |
| `app/features/syllabus/trees.py` | `EXAM_TAGS = (UPSC, APPSC, SI)`; `clean_tree` keeps "SI"; `exam_tags_for("SLPRB SI (Civil)") == ["SI"]` ("Mission", "Basic", "Silicon" give nothing; UPSC / APPSC / "both" behaviour unchanged). `filter_tree` already worked for any tag. |
| `app/features/syllabus/service.py` | `seed_starters`: `"supersedes": "<old key>"`. When the new starter is ADDED in this run and the old import (`seed_id(old key)`) is `pending` and not deleted, the old one gets `deleted=True`. Approved ones are never touched; if the new row already existed nothing happens (idempotent). A starter that another file in the folder supersedes is not seeded at all (fresh install with both files). |
| `app/features/syllabus/__init__.py`, `app/llm/prompts/syllabus_import.system.md` | docs and the LLM rule 4 now list "SI" (SLPRB Sub-Inspector). |
| `app/features/planner/service.py`, `planner/engine.py` | `_priority_for` and `matching_exams` match by whole word (`examnames.has_word`). "UPSC CSE" and "APPSC Group-I" behave as before; "SLPRB SI (Civil)" matches tag/key "SI". New `engine.counted_minutes`; the summary text, `plan_dict()["minutes"]` and `fit_to_hours` leave `phys-` blocks out. |
| `app/features/revision/service.py` | `exam_proximity` had the same substring test (`tag in exam name`); now whole word (extra fix, not in the brief). |
| `app/features/plan_hooks.py` | `UNCOUNTED_ID_PREFIXES = ("phys-",)` and `counts_toward_hours(block)`. |
| `app/features/reports/weekly.py` | weekly planned/done minutes skip `phys-` blocks (so the report does not treat training as study hours). |
| `app/features/tests/aptitude.py` (new) | the deterministic generator (see below). |
| `app/features/tests/generate.py` | `generate_test` kind `"aptitude"` -> `generate_aptitude_test(db, day, area, count)`; `_finish_test(..., negative=None)` takes an explicit negative-marking flag; helpers `find_aptitude`, `aptitude_title`, `_si_topic_for`, `_area_key`. |
| `app/features/tests/api.py`, `tests/jobs.py` | `POST /tests/generate` accepts `area`; `_announce` says "Aptitude drill"; the `test_generate` job passes the payload through unchanged (it already did). |
| `app/features/si/__init__.py`, `si/plan.py` (new) | `GET /si/spec`; two plan hooks. Feature registered in `app/features/__init__.py` (`"si"`, after `"answers"`). |
| `app/db/models_v2.py` | comment only: `Test.kind` may be `aptitude` (column is 20 chars). |
| `docs/job-types.md` | `test_generate` row documents the new payload. |
| Tests (new) | `tests/test_aptitude.py`, `tests/test_si.py`, `tests/test_syllabus_coverage.py`. |

## Contracts the tablet relies on

* **Exam tag** `"SI"` on topics (`Topic.exam_tags`), accepted by `GET /syllabus/tree?exam=SI`.
* **KV `exam.priority`**: dict keyed by exam tag / name word; `{"UPSC":1,"APPSC":1,"SI":2}`. A key matches an exam when it is a whole word of the exam name (case-insensitive). Exam name for the SI goal: `SLPRB SI (Civil)` (anything with the word `SI` or `SLPRB` counts as the SI exam).
* **KV `si.physical_plan`**: `false` switches the physical block off; anything else (missing, true) = on.
* **Plan blocks** (only while a non-deleted SI exam exists whose date is not in the past; undated = live; with several SI exams one still to come is enough; nothing is added to days before today):
  * `phys-<YYYY-MM-DD>`: kind `other`, start `06:00`, 45 min (Sunday 20), ref `goals`, `topic_id` null. Titles: Mon/Wed/Fri "Run: 1600 m pace work", Tue/Thu "Sprint and long jump drills", Sat "PET simulation: 1600 m + 100 m or long jump", Sun "Mobility and rest". Not counted in the day's study hours (id prefix `phys-`).
  * `drill-<YYYY-MM-DD>` (not Sunday): kind `practice`, start `19:30`, 25 min, title "Aptitude drill (20 questions)", detail "Focus today: <label>. Also mixed in: <3 labels>.", ref `test/<test id>`. Counted in the hours (it can shorten other study/practice).
* **Test rows**: `Test.kind == "aptitude"`, title "Aptitude drill: <Label>" or "Aptitude drill: mixed", `negative_marking` false, `status` "ready", `duration_min = ceil(count*1.25)` (20 questions = 25), `scheduled_for` = 19:30 India time of the day. Its `Mcq` rows: `source_type "mock"`, `source_id` = test id, `topic_id` = the approved topic tagged SI whose title equals the area label (ignoring case, punctuation and "and" vs "&"), else null. One drill per (India day, area or mixed, count): asking again returns the same test.
* **Job / API payload** (`test_generate` job and `POST /tests/generate`): `{"kind":"aptitude","area"?: key or label,"count"?: 5..40 (default 20),"date"?: "YYYY-MM-DD" (default today India)}`. Result `{test_id}`; push `mock_ready` says "Aptitude drill is ready...". Needs no AI, works when the budget guard is closed. Unknown area = job failed / HTTP 400.
* **Area keys and labels** (`aptitude.AREAS`): percentage "Percentage", profit_loss "Profit & loss", simple_interest "Simple interest", compound_interest "Compound interest", ratio_proportion "Ratio & proportion", average "Average", time_work "Time & work", work_wages "Work & wages", time_distance "Time & distance", clocks_calendars "Clocks & calendars", partnership "Partnership", mensuration "Mensuration", number_system "Number system", series "Number and letter series", coding_decoding "Coding-decoding", blood_relations "Blood relations", direction_sense "Direction sense", ranking_order "Ranking and order", odd_one_out "Odd one out", analogy "Analogies", syllogism "Syllogism", statements_conclusions "Statements and conclusions".
* **`GET /si/spec`**: the JSON of `data/exam-specs/slprb_si_2026.json` (folder found beside `feeds.yaml`, `EXAM_SPECS_DIR` overrides, else the repo's `data/exam-specs`); 404 when missing or unreadable.

## The generator (`features/tests/aptitude.py`)

Pure Python, `random.Random`, integers and `Fraction`; answers with more than two decimals are never shown (the template is redrawn). 99 templates over 22 areas. `generate_questions(area, count, seed)`, `area_for_date(day)` (one area per day, all 22 rotate), `areas_for_date` (focus + 3 others), `mixed_questions(day, 20)` (8 from the focus area, 4 from each other). Syllogism answers come from an exhaustive check of all arrangements of three non-empty groups (a table built once); "statements and conclusions" uses ordering closure, the same model check and average/sum facts.

Tests recompute every answer with a different method (the calendar module, Zeller's congruence, brute force, an element-based model checker, simulation on compass names, ...). While writing them one real generator bug was found and fixed (a pass-mark question used an inexact percentage).

## Tests (all green, ruff clean)

* `tests/test_aptitude.py`: 54 passed (22 areas x 200 seeds each recomputed, template coverage, batches of 20 without repeats, determinism, rotation, mixed drill, formatting, syllogism engine).
* `tests/test_si.py`: 33 passed (tags, whole-word matching, priorities, SI scoring, seeder `supersedes` cases, the three shipped outlines vs `clean_tree`, tree route, the aptitude kind via generate / job / API, scoring and mistake cards for the kind, both hooks, the physical block outside the study hours through `plan_range`, `/si/spec`).
* `tests/test_syllabus_coverage.py`: 8 passed (3 coverage files: items, page ints, paths resolve, all papers referenced, outlines verified).
* Also run and green: test_planner, test_syllabus, test_tests, test_revision, test_reports, test_contract, test_foundation, test_answers, test_notes, test_library, test_health, test_devices, test_api_briefs, test_storage, test_backup, test_compilation, test_telugu, test_videos, test_videos_summary, test_tutor, test_auth. `ruff check backend` clean. `create_app()` imports and lists the `si` feature.

## Where I departed from the brief (smallest safe change)

1. "No such SI exam is in the past": implemented as "at least one SI exam that is not in the past" (undated = live). With one SI exam it is identical; with two (say Prelims done, Final to come) training keeps going.
2. "Not counted in study hours" needed a small planner change (id prefix `phys-` is skipped by `fit_to_hours`, the summary, `plan_dict.minutes` and the weekly report); nothing else in the planner changed.
3. The drill hook makes the day's test while planning, so planning several days ahead creates several tests (about 6 per week, 20 questions each, idempotent per day).
4. The revision service had the same substring bug as the planner; fixed the same way.
5. Blood-relation, direction and ordering questions are text-only; figure reasoning is not generated (as the plan says).
