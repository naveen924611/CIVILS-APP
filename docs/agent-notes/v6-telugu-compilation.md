# V6 notes: Telugu practice + monthly compilation (M12)

Status: server tested (pytest + ruff, own files). Android hand-reviewed like a compiler, NOT compiled. An earlier attempt was
interrupted; only `data/telugu/vocab.yaml` and `passages.yaml` survived, everything else was rebuilt.

## Built

Server (`backend/app/features/telugu`, `backend/app/features/compilation`, prompt `telugu_feedback_v1`):
- telugu: `seed.py` (data/telugu/*.yaml -> `telugu_items`, idempotent, stable ids from the key), `practice.py` (pure daily-set rules),
  `service.py`, `api.py` (`GET /telugu/today`, `GET /telugu/progress`, `POST /telugu/reseed`), `jobs.py` (`telugu_feedback`).
- compilation: `build.py` (briefs' news + notes + mistakes -> markdown, no AI), `pdf.py` (fpdf2 + IBM Plex from `features/reports/fonts`),
  `api.py` (`GET /compilation`, `POST /compilation/generate`, `GET /compilation/{id}/pdf`), `jobs.py` (`compilation_build`),
  monthly cron (1st, 04:30 IST) when the scheduler is on.
- Tests: `tests/test_telugu.py` (18), `tests/test_compilation.py` (10).

Tablet (`ui/telugu`, `ui/compilation`): Telugu screen (Today / Progress / My writing), flash cards with Telugu sound, reading passages
with checked questions, translation and letter/essay writing with tutor feedback, progress tab, Settings section (minutes a day, links);
Monthly digests screen (list, digest text, Share text, Open PDF, Share PDF, "Make last month's digest").
JVM tests: `TeluguLogicTest`, `CompilationLogicTest` (hand-checked, not run).

## Data audit (data/telugu)
- 160 words, 8 passages, and (new) 18 translation sentences and 6 letter/essay templates. Structure checked by script: unique keys, every
  passage question has 4 different options and a valid answer, every item has Telugu text. One romanisation fixed (v137).
- NOT verified: the Telugu itself (no teacher, no official source). Everything is `source: general`; the app shows "General practice, not from the
  official syllabus". The 2026 official Telugu syllabus was not available; the detailed notification PDF is still an open item (spec 16).
  When it arrives, add items with `source: syllabus` (they then count as `official`).
- Facts inside examples I checked from general knowledge: Constitution in force 26 Jan 1950, independence 15 Aug 1947, Godavari longest river in
  South India, Nannaya is the Adikavi.

## Contract
- Items: table `telugu_items` (server-written). `content_json` = the yaml item minus kind/level, plus `source`, `official`, `seeded`.
  kinds: vocab{te,roman,en,ex_te,ex_en} | passage{title,title_en,text_te,text_en,questions[{q_te,q_en,options[4],answer}]} |
  translation{direction en_to_te|te_to_en,en|te,reference,hint_words[{te,en}]} | template{form,title_en,title_te,task_en,min_words,structure[],phrases[],sample_te}.
- Progress: tablet writes `telugu_progress` (item_id, done=true, score 0-1 or null, answer, at). Vocab: 1.0 knew / 0.0 not yet. Passage: correct/total.
  Writing: score null until feedback; then 0-1 (score/10).
- Job `telugu_feedback` and `compilation_build`: see docs/job-types.md.
- KV: `study.telugu_minutes` (default 15, 0 to 120), read by the Telugu screen and the server; written by the Settings section (and V2's planner UI).
- Daily set rules (identical in `practice.py` and `TeluguPlan`): vocab (m+1)//2 (min 4); passage 1 if m>=10; translation 1 if m>=10, 2 if m>=20;
  template 1 on Wed and Sat if m>=15. Done-today items stay in the set; then up to half the free places go to weak items (last score < 0.6), then never-done in file
  order, then well-done oldest first.
- Compilation: `compilations` rows (month YYYY-MM, title, content_md, pdf_path relative to DATA_DIR, status). One live row per month; rebuilding updates it.

## Assumptions
1. The tablet picks the daily set itself (offline); `GET /telugu/today` gives the same set and is for checking.
2. Vocab is self-graded; passages are graded on the tablet; only translation, letters and essays go to the AI.
3. No time tracking for Telugu; the planner's Telugu block (kind `telugu`, ref `telugu`) is the reservation.
4. PDF: IBM Plex has no Telugu, and text shaping (uharfbuzz) is not installed, so Telugu letters (and arrows/rupee sign are replaced) are left out of the PDF;
   the markdown digest on the tablet shows everything. Needs `uharfbuzz` + Noto Sans Telugu on the server to change this.
5. Digest is built without AI (deterministic), from briefs' items (or, with no briefs that month, non-hidden news of the month), notes updated in the month and
   unresolved mistakes touched in the month. Empty months are skipped by the monthly cron but can be made by hand.
6. Telugu speech uses the tablet's `te-IN` voice; if the tablet has no Telugu voice it falls back to English and reads nothing useful. Install the Google Telugu voice.
7. The Telugu screen is one route (`telugu`) with internal state (no extra routes). The compilation route is registered from `teluguRoutes`.

## Manual tests for the owner (for docs/tablet-test-checklist.md)
1. Sync once. Open Telugu practice (Settings > Study plan > Open Telugu practice). Today shows a Words card and a Reading card.
2. Start words: tap Hear the word (Telugu voice), Show the meaning, "I knew it" / "Not yet". Finish; the card says All done.
3. Open the reading passage, answer all questions, Check my answers; right answers turn green. Show English.
4. Open a translation: type a Telugu answer, Send for feedback. Airplane mode: it says Waiting for the tutor. Internet on: feedback and score arrive.
5. On a Wednesday or Saturday a letter/essay task appears. Write and send it.
6. Progress tab: streak, 14-day bars, per-kind numbers. My writing tab lists what you sent.
7. Settings > Telugu practice: change minutes with -5 / +5; the planner keeps that time (Today plan shows Telugu practice).
8. Settings > Monthly digests: "Make last month's digest", wait for the server, open it, Share text, Open PDF, Share PDF.

## Manifest / foundation needs
- None. Uses the existing FileProvider path `exports/`, Hilt modules `CompilationModule` (own file), `KvRepository`, `JobRepository`.
- Entry points to add elsewhere (optional): Routes.COMPILATION and Routes.TELUGU already exist; a "Monthly digests" link on Briefs (V-owner of Briefs) would help.
  Today's Telugu block opens `telugu` (ref already set by the planner).

## Known gaps
- Nothing compiled on Android. Telugu text has no teacher review. No spaced-repetition beyond the simple weak-first rule. PDF has no Telugu.
