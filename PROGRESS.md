# Progress
Current shoot: 2 done (code) | Current milestone: M3-M12 all WRITTEN | Status: every milestone's code is written; server tests pass; Android code has NEVER been compiled. Next: owner pushes, reads the first GitHub Actions build, sends me the red lines, then tablet testing (docs/m3-m12-owner-steps.md)
Last updated: 2026-09-21

## Done
- M1 repo skeleton: CLAUDE.md (copy of FINAL-SPEC), PROGRESS.md, README, .gitignore, .env.example, gitleaks pre-commit config (2026-09-20)
- M1 backend (`backend/`): FastAPI app, single-user login (argon2 + access/refresh tokens, refresh rotation, login rate limit), device registration for push, FCM sender + test script, SQLite (WAL) + Alembic migration 0001. 12 tests pass, 83% coverage, ruff clean (Python 3.10 locally; CI uses 3.12).
- M1 deploy files: backend/Dockerfile, docker-compose.yml (api + caddy), docker-compose.dev.yml, Caddyfile (HTTPS via DuckDNS), docs/setup-windows.md, docs/server-runbook.md, docs/tablet-test-checklist.md
- M1 Android (`android/`), NOT YET COMPILED: Compose app, custom theme (spec section 5, light + dark), bundled Fraunces / IBM Plex Sans / Noto Sans Telugu, 96 dp navigation rail with all 10 destinations as placeholders, login screen (server address, username, password), Keystore-encrypted token storage, silent token refresh, FCM token registration, notification channel, unit test for the rail.
- M1 CI: .github/workflows/android-apk.yml (signed APK -> artifact + release), backend-ci.yml

## M2a server (2026-09-20) - done, untested against real feeds/AI
- LLM gateway (Gemini + Groq, fallback chain, own daily budgets, degrade levels, strict JSON), news pipeline (robots-aware feeds, dedup, extraction, summary, flashcards), Piper audio + ffmpeg, brief builder + scheduler (07:00 / 19:00 IST, editable), FCM silent push, API: /briefs, /sync/pull, /audio, /settings/briefs, /usage, /alerts. Migration 0002. 88% coverage, ruff clean.
- Owner steps: docs/m2-server-steps.md

## M2b Android (2026-09-20) - written, NOT YET COMPILED (GitHub Actions is the compiler)
- Room database (news items, briefs, cards, alerts, per-item "heard"), sync (`GET /sync/pull`) via WorkManager (on push, at brief-time alarms, every 3 h, on app open), audio saved on the tablet for offline.
- Briefs screen (6.2): item list, story detail, past briefs, "Prepare a brief now", player bar (-15/+15, speed, sleep timer, seek). Media3 `PlaybackService`: keeps playing with the screen off, lock-screen + headphone buttons.
- Alerts screen (6.7), notification channels (briefs, player, answers, revision, summary, general), "brief ready" notification with Play now / Read / Remind in 30 min, alarms at brief times with boot re-register.
- Settings: brief times, days, extra briefs, Wi-Fi-only downloads. First-run permissions setup (6.19 part): notifications, exact alarms, battery, offline voice.
- Not done on purpose (later milestones): line-by-line highlight while audio plays (needs timestamps), "Say next / repeat" voice hint and mic button (M6), linked syllabus topic (M4), end-of-brief quiz (M5).
- 3 JVM unit-test files (times, formats, server JSON parsing). Versions Room 2.8.0, Media3 1.8.0, WorkManager 2.10.3 are UNVERIFIED (no Maven access here); the first CI run is the check.

## M3-M12 (2026-09-21) - written unattended, server tested, Android NOT YET COMPILED
Foundation: generic Room `records` store (31 tables) + generic `/sync/pull` and `/sync/push`, a job queue (tablet writes a job, server answers, one combined push), RAG (BM25 + embeddings), feature loader. Builder rulebook: docs/build-guide.md. Each builder's assumptions and manual test steps: docs/agent-notes/*.md. Job contracts: docs/job-types.md. Verified facts and unverified data: docs/decisions.md.
- **M3 Library, Reader, Capture** - done (code). Upload PDF, search inside, Reader with read-aloud and highlight, resume, camera scan with on-device OCR + AI reading of Telugu pages, offline photo queue. Sources list unverified.
- **M4 Syllabus, Notes** - done (code). Syllabus import + review, 4 starter outlines (unverified), one note per topic, AI note merge (your edits always win), in-the-news matching, syllabus map.
- **M5 Revision, Planner, Today, Exams** - done (code). FSRS-6 in Python and Kotlin (shared test vectors), hands-free revision, daily plan, exam setup.
- **M6 Ask, voice, tutor** - done (code). Ask screen (typed/voice, offline queue, sources, read-aloud), floating mic with 14 offline commands.
- **M7 Settings, Storage, Widget, Setup** - done (code). Settings, storage usage/limit/cleanup/backups/export, setup wizard, Glance widget, day notifications.
- **M8 Tests, mistakes** - done (code). Mock/topic/past-paper tests, negative marking, mistake book feeding revision.
- **M9 Explain-back, Answer writing** - done (code).
- **M10 Sheets, weekly report, last-month mode** - done (code). Revision sheets (PDF), weekly report (Sunday), last-month mode.
- **M11 Focus, Videos** - done (code). Focus timer with Do Not Disturb, video links + notes (nothing downloaded).
- **M12 Telugu, monthly compilation** - done (code). Telugu practice (192 unverified items), monthly digest (markdown + PDF; Telugu letters not in the PDF, shown on the tablet).
- Server tests: all pass (about 400), ruff clean, migrations 0001-0003 match the models. Kotlin JVM tests written, never run.
- Reviewed by "human compiler" agents (no Android SDK here): a handful of likely compile errors fixed.
- Integration edits: `CivilsMessagingService` shows `weekly_report` and `mock_ready` pushes; `CivilsApp` schedules day notifications after login; `SyncWorker` refreshes the widget after each sync.

## SI (Civil) goal (2026-09-21) - written unattended, server tested, Android NOT YET COMPILED
Plan, official facts and the full coverage matrix: `docs/si-civil-plan.md`. Verified facts: `data/exam-specs/`.
- Third goal SLPRB SI (Civil): exam tag `SI`, priority "More on SI", three exam rows seeded (Prelims, Physical PMT/PET, Final Written).
- Syllabus: `slprb_si_written.json` (Arithmetic and Reasoning, General Studies, English, Telugu) and the two Group-I outlines rebuilt from the official PDF. Coverage files + test prove every official line has a node; two independent re-reads of the PDFs found no omissions.
- Plan: daily 06:00 physical block (outside study hours) and a daily 20-question aptitude drill (server-generated, exact answers).
- Tablet: Goals screen (dates, SI profile and age check, checklist, PMT, PET log, cut-off calculator), aptitude drill dialog in Tests, practice-prompt picker in Answers (90 prompts, offline).
- Server tests: 97 new/related pass; planner, syllabus, revision, reports still pass; ruff clean. Kotlin: reviewed by an independent "human compiler" pass, never compiled.
- After you update the server: the three new outlines appear as pending imports in Syllabus review; approve them (the old unapproved Group-I ones disappear by themselves).
- Not covered (honest): figure-based non-verbal reasoning, Urdu paper, Group-I English grammar MCQ drills. Group-I age and DSP physical rules wait for the Detailed Notification (by 06-10-2026); SI application dates wait for the press release.

## Fixes after first tablet test (2026-09-21)
- Rail taps (Home etc.) no longer restore a previously opened screen (MainActivity.goTo).
- Briefs without an audio file are now read aloud by the tablet's own voice (BriefsViewModel.readWithTablet); the server now downloads the Piper voice by itself the first time it builds a brief (before, it needed the manual voice-download step).
- Screen orientation is no longer locked to landscape. Upright (narrow, under 900 dp) layout: bottom navigation bar instead of the left rail, and panes stack or show one at a time (`isCompact()` in ui/common/Adaptive.kt). Not seen on a real tablet yet.
- Fixed a Kotlin build error (`/*` inside a KDoc comment in TeluguContent.kt).

## Waiting on owner now
0. New (SI goal): approve the new syllabus outlines and fill the Goals profile (docs/si-civil-plan.md section 6).
1. `git push` and read the first `android-apk` run; send me the red lines (docs/m3-m12-owner-steps.md).
2. Update the laptop server (same file, step 3).
3. Install the APK and go through the M3-M12 checklist.

## Known gaps (small, by design)
- Voice: tap-to-talk instead of hold-to-talk; headphone button map and wake phrase are stored but not applied (V3 notes).
- Brief quizzes/note questions do not yet feed the mistake book (`TestsRepository.recordAnswer` not called from Briefs/Notes).
- Videos tab inside a topic (`TopicVideosPanel`) is built but not yet placed in Notes/Library.
- Telugu letters missing in server PDFs; Telugu practice sound needs a te-IN voice on the tablet.
- Voice-note option from spec 6.11 not built.

## In progress
- Task: M2 acceptance on the real tablet (see docs/tablet-test-checklist.md, M2 additions)
- Files touched: everything under android/ (never compiled locally: no Android SDK or Maven access here)
- Exact next step: owner creates the private GitHub repo and pushes; read the first `android-apk` run; fix any build errors (versions in android/gradle/libs.versions.toml are a conservative known-compatible set and are UNVERIFIED until the first run)

## Server plan (2026-09-20)
- Oracle sign-up failed (generic error). Hybrid decided: LAPTOP is the default server now (Docker + Tailscale HTTPS, docs/laptop-server.md); move to Oracle/other cloud later with `python -m app.tools.backup` create/restore. Only one active server at a time.

## Waiting on owner (one at a time, in this order)
1. Create a private GitHub repository and give me the URL (or push this folder himself).
2. Create the Android signing key (docs/setup-windows.md) and add the 4 keystore secrets to GitHub.
3. Oracle Cloud account + VM (docs/server-runbook.md sections 1-4).
4. DuckDNS name (section 2).
5. Choose the login password and make its hash (`python -m app.auth.hash_password`), put it in the server `.env`.
6. Firebase project + `google-services.json` + service-account file (section 6).

## Known issues
- No Docker or Gradle here, so `docker compose` and the Android build are untested.
- Git: the owner's folder does not allow deleting files from here, so a stale `.git/index.lock` must be removed before committing.
- Gradle wrapper is not committed (cannot be generated without Gradle). CI uses `gradle/actions/setup-gradle` with Gradle 8.14.3. Android Studio will offer to create the wrapper.
- App package name is `com.naveen.civilscompanion` (`in` is a Kotlin keyword, so `in.civils...` was avoided).

## Decisions (short; details in docs/decisions.md)
- AGP 8.13 / Kotlin 2.2.20 / Hilt via KSP chosen over AGP 9.x to avoid untested build-plugin changes; upgrade later on purpose.
- HTTPS only; server address editable on the login screen (no rebuild to change server).
- Caddy uses the normal HTTP challenge, so DuckDNS token is only needed for updating the IP.
