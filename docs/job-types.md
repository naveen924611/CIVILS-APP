# Job types

The tablet writes a `jobs` row (`type`, `payload_json`); the server runs the handler and fills `result_json`. Each builder adds rows for the types it owns.

| type | handler owner | creator | payload | result |
|---|---|---|---|---|
| `ocr_page` | V1a `features/library/ocr.py` | V1a tablet (Reader "Read with AI"), and the server itself for scanned PDFs / photos without text | `{document_id, page, only_this_page?, force?, language?}` | `{document_id, page, chars, pages_done, remaining}` |
| `mock_test` / `test_generate` | V4 `features/tests/jobs.py` | V4 tablet (Tests screen); server queues the weekly mock the night before the mock day | `{kind: weekly\|topic\|past_paper\|mistakes\|aptitude, topic_id?, exam?, year?, paper?, count?, date?, area?}`; kind `aptitude` (SI drill, generated on the server without AI): `{kind: "aptitude", area?: one of the aptitude area keys (else a mix of four areas), count?: default 20, date?: India day YYYY-MM-DD, default today}` | `{test_id}` (silent push `mock_ready`) |
| `pyq_import` | V4 `features/tests/jobs.py` | V4 tablet (Tests, Import past paper) | `{document_id, exam, year, paper?}` | `{added, skipped, no_answer, mapped, pages}` |
| `revision_sheet` | V4 `features/reports/jobs.py` | V4 tablet (Sheets); server rebuilds changed sheets nightly | `{topic_id}` or `{topic_ids:[...]}` | `{sheet_id, sheet_ids}` |
| `weekly_report` | V4 `features/reports/jobs.py` | V4 tablet (Report); server queues it Sunday 20:00 IST | `{week_start?, scheduled?}` | `{report_id}` (silent push `weekly_report`) |
| `video_summary` | V5 `features/videos/summaries.py` | V5 tablet (video screen, "What to listen for") | `{video_id}` | `{video_id, text, note}` (also saved as setting `video.summary.<video id>`; made from title, channel, topic and the owner's notes only, never from the video) |
| `telugu_feedback` | V6 `features/telugu/jobs.py` | V6 tablet (Telugu practice: translation, letter, essay) | `{item_id, answer, progress_id?}` | `{item_id, score (0-10), feedback: {summary, strengths[], corrections[{said,better,why}], model_answer}}`; also sets `telugu_progress.score` (0-1) when `progress_id` is given |
| `compilation_build` | V6 `features/compilation/jobs.py` | V6 tablet (Monthly digests, "Make last month's digest"); the server also runs it on the 1st at 04:30 IST (no job row) | `{month?: "YYYY-MM"}` (default: last month) | `{compilation_id, month}`; the row is in `compilations` (no AI needed) |
| `note_merge` | V1b `features/notes/` | Notes, Capture, Ask ("Save to notes") | `{topic_id?, text?, source?:{document_id,page}, mode?: merge\|generate\|fix, mcq_count?, title?}` | `{note_id, added, summary}`; owner-edited notes always win: new material goes into a `## Suggested additions` block |
| `syllabus_import` | V1b `features/syllabus/jobs.py` | Syllabus import (paste text or pick a Library document) | `{exam, title, text?, document_id?, import_id?}` | `{import_id}` (a pending import the owner reviews and approves) |
| `tutor_question` | V3 `features/tutor/jobs.py` | Ask screen (typed or spoken; queued when offline) | `{conversation_id, question, topic_ids?, via: text\|voice, mode?: simple\|depth\|quiz\|evaluate}` | `{message_id, answer, sources[]}`; also writes the assistant chat message |
| `explain_feedback` | V3 `features/answers/jobs.py` | Explain-back screen | `{session_id}` | `{session_id}`; feedback in `explain_sessions.feedback_json` |
| `answer_eval` | V3 `features/answers/jobs.py` | Answer writing screen | `{answer_id, text?}` | `{answer_id, score}`; feedback in `answers.feedback_json` |
