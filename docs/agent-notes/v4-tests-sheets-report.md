# V4: Tests, Mistake book, Revision sheets, Weekly report (M8, M10)

Status: server done and tested (`tests/test_tests.py`, `tests/test_reports.py`, about 90 percent coverage, ruff clean).
Android: see the "Android" section at the end (written last; if it is missing the Android part was not finished).

## Server (backend/app/features/tests, backend/app/features/reports)

- `tests/`: `generate.py` (weekly mock, topic test, past paper, mistakes retest; questions only from the owner's notes, each one
  validated), `scoring.py` (analysis), `mistakes.py` (cards from the mistake book), `pyq.py` (past-paper import + topic mapping),
  `jobs.py`, `hooks.py`, `plan.py`, `api.py`, `__init__.py` (router + scheduler).
- `reports/`: `sheets.py` (sheet per topic), `weekly.py` (report data), `pdf.py` (fpdf2), `jobs.py`, `plan.py` (last-month block), `api.py`.
- Prompts: `tests_mcq`, `tests_pyq` (earlier attempt), `reports_sheet`, `reports_weekly`.

### Jobs (also in docs/job-types.md)
| type | payload | result |
|---|---|---|
| `mock_test` | `{kind:"weekly", date?, week_start?, count?}` | `{test_id}` |
| `test_generate` | `{kind:"topic"\|"past_paper"\|"mistakes"\|"weekly", topic_id?, exam?, year?, paper?, count?}` | `{test_id}` |
| `pyq_import` | `{document_id, exam, year, paper?}` | `{added, skipped, no_answer, mapped, pages}` |
| `revision_sheet` | `{topic_id}` or `{topic_ids:[...]}` (max 10) | `{sheet_id, sheet_ids}` |
| `weekly_report` | `{week_start?, scheduled?}` | `{report_id}` |
Test jobs send a silent push `{type:"mock_ready", test_id}`. The scheduled weekly report sends `{type:"weekly_report", week_start}`
(not when KV `notify.weekly_report` is false). These jobs have no generic "jobs_done" push (`notify=""`).

### Routes (login required)
`POST /tests/generate`, `GET /tests/{id}/analysis`, `POST /tests/pyq/map`, `GET /tests/pyq/stats`,
`GET /reports/sheets/{id}/pdf`, `GET /reports/weekly/{id}/pdf`, `POST /reports/sheets/generate {topic_id}`, `POST /reports/weekly/generate {week_start?}`.

### KV keys
`test.negative_marking` (bool, default false, written by TestSettingsSection; new tests copy it into `Test.negative_marking`, the owner can flip it before starting),
`test.weekly_day` ("mon".."sun", default "sun"; the mock is generated the night before at 22:00 IST), reads `notify.weekly_report`, writes nothing else.
The tablet writes `plan.adjustments` when the owner taps "Accept next week's plan" (format from build-guide 9.1).

### How the tablet and server share the work
- Tests, MCQs are made by the server and synced (`tests`, `mcqs`). The tablet runs the test offline, writes one `attempts` row per question
  (skipped = `chosen -1`), then the `tests` row (`status:"done"`, `score`, `started_at`, `finished_at`, `negative_marking`) and `mistakes` rows.
- Server hook: when the test row and ALL its attempts have arrived (whichever comes last) it writes `Test.analysis_json` and sets status `analysed`.
  The tablet also computes the same analysis locally (works offline) and shows that.
- Mistake book is kept by the tablet (`mistakes` rows). Server hook on a pushed mistake makes ONE revision card per question
  (`source_type:"mistake"`, `source_id: mcq id`, group = subject): plain card for "didnt_know", comparison card for "confused"; "silly" makes no card.
- Mistake type default from confidence: guess or unmarked = didnt_know, unsure = confused, sure = silly (owner can change it on the review screen).
- Mistakes retest is built on the tablet (no server needed) from mistakes that are due; it is not a `tests` row (route `test/mistakes`).
- Mistake leaves the book after 2 correct answers in a row, the second one at least when it is due (first correct: next due in 3 days; wrong: due in 1 day).
- Weekly mock: 25 questions, 30 minutes, topics worked on in the 7 days up to the mock day, past-paper style examples from imported PYQs.
  The plan gets a `mock` block (`ref: test/<id>`) on that day.
- PYQ to topic mapping: `pyq.map_all` uses the same word matching as "In the news" (threshold 2.0), run after every import and nightly at 02:50 (only
  questions without topics; the owner can correct `Pyq.topic_ids` on the tablet), then recomputes importance (V1b `syllabus.importance.recompute`).
- Sheets: built from the note (key points, must remember, mains angle, in-the-news), real past-paper questions of the topic, and (optional AI, skipped when
  budget level >= 2 or the AI fails) tidied key facts + memory hooks. Never fails because of the AI. Rebuilt nightly 03:20 (10 per night max) when the note changed.
  Audio: the tablet reads `sections.script` with its own offline voice (about 3 minutes); `audio_path` stays empty (no Piper dependency).
- Weekly report: counted from plans, focus sessions, reviews, attempts, tests, topics; `data_json` shape is in `reports/weekly.py` docstring.
  Sunday 20:00 IST job. The AI only writes the short spoken summary; a plain text is used when it is unavailable.
- Last-month mode: 30 days or fewer before any dated exam. Plan block `sheets-<date>` (kind `sheet`, ref `sheets`), 9 sheets a day, rotation
  identical on the tablet (`SheetLogic.pickSheets`).

## Assumptions
1. No answer key import: past-paper questions get an answer only when the paper text itself printed it; a full past-paper test only uses those.
2. PDF text is Latin only. The bundled IBM Plex font has no Telugu and fpdf2 cannot join Telugu letters without `uharfbuzz` (not installed), so Telugu text becomes "[Telugu]" in PDFs.
3. Sheets/report audio is read by the tablet's own voice, not a server MP3.
4. Skipped questions are not mistakes (only wrong answers enter the book).
5. Weekly report week = Monday to Sunday (India).
6. The nightly jobs only register when the scheduler is enabled (tests run with it off; the functions are tested directly).

## Needs from the integrator
- `CivilsMessagingService.onMessageReceived`: add `"weekly_report" -> showPlain(title.ifBlank { "Weekly report ready" }, body, CivilsApp.GENERAL_CHANNEL, Routes.REPORT)`
  and `"mock_ready" -> showPlain(title.ifBlank { "Test ready" }, body, CivilsApp.GENERAL_CHANNEL, Routes.TESTS)`. (Unknown types already trigger a sync.)
- V5 Settings must call `TestSettingsSection(nav)`.
- No manifest changes. PDFs are shared through the existing FileProvider path `exports/`.
