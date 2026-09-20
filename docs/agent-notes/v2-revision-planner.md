# V2 notes: Revision + Planner + Today + FSRS

Status: complete (server tested; Android hand-reviewed, NOT compiled).

## Built

Server (backend/app):
- srs/: FSRS-6 (fsrs6.py), topic strength (strength.py), vector generator. data/fsrs_vectors.json (48 review cases + 12 strength cases).
- features/revision/: queue.py (pure rules), service.py (DB side), cardgen.py (cards from notes), api.py, __init__.py (router, nightly recompute 02:30 IST when scheduler on).
- features/planner/: engine.py (pure), service.py, api.py, __init__.py (router, nightly plan 00:10 IST).
- Tests: tests/test_srs_vectors.py, test_revision.py (16), test_planner.py (~24). All pass; ruff clean on my files.

Android (com.naveen.civilscompanion):
- srs/: Fsrs.kt, Strength.kt, RevisionQueue.kt (ports of the Python; same vectors in src/test/resources/fsrs_vectors.json).
- ui/revise/: hub, session (hands-free TTS + voice grading), rules screen, ReviseSettingsSection, routes.
- ui/today/: Today, Planner, PlannerSettingsSection, PlanRepository, PlannerApi (Retrofit + Hilt module), local tick table, routes.
- ui/exams/: ExamsScreen, ExamSetupStep(onNext), editors.
- JVM tests: FsrsVectorsTest, RevisionQueueTest, PlanBlocksTest, ReviseDataTest.

## Server / tablet contract

Routes (all authed):
- /revision: GET queue, GET sunday-review, POST generate, POST review, GET cards/{id}/intervals, PUT+DELETE order, POST snooze, POST recompute.
- /planner: GET plan, GET week, POST regenerate {days,start} -> {planned, plans, missed_days}, POST complete, GET exams.

KV keys: study.hours, study.telugu_minutes, exam.priority, study.library_day, plan.adjustments, revision.slot, revision.max, revision.sunday, plus extras revision.retention, revision.new_per_day, revision.weights.

Plan block: {id, kind, start, minutes, title, detail, topic_id, ref}. Server writes DailyPlan.blocks_json; tablet writes completion_json. Plan post-processors register via app/features/plan_hooks.py.

Job types registered: none. Cron only (planner 00:10, revision 02:30).

## Assumptions

- The revision_sheet job handler is V4's per build-guide; NOT registered here.
- Default exams seeded in setup: UPSC CSE and APPSC Group-I (Prelims + Mains).
- New cards per day 20 (revision.new_per_day). New cards use due_at = creation time.
- Snooze moves due_at to 06:00 IST, 1 to 3 days ahead.
- Hub reorders with up/down arrows (no long-press drag). Rules screen has no "+ Add rule".
- Tablet ticks on the offline fallback plan live in local-only table local_plan_ticks, so an empty DailyPlan row never blocks server generation.
- Planner adds an "Extra study time" block when picked topics do not fill core time.
- Day-based FSRS, no fuzz, no learning steps; elapsed days floored; Kotlin uses Math.rint to match Python round.

## Verified vs unverified

Verified: pytest for my files, ruff, import app.main, vectors on the Python side.
Unverified: everything Kotlin (no compiler). Hand-checked imports, nullability, symbols and test expectations. CI must run the JVM tests and assembleDebug.

## Manual tablet test steps

1. Setup: reach the exams step, confirm 4 default exams, tap Continue.
2. Exams: change priority and hours; reopen, values persist.
3. Settings: Planner and Revise sections appear and open their screens.
4. Today: plan blocks show; tap a block, it opens the linked screen; tick done, restart, tick persists.
5. Airplane mode: Today still shows a fallback plan; ticks persist; reconnect, plan refreshes.
6. Planner: Regenerate; week view shows 7 days; change study hours, regenerate, block minutes change.
7. Revise: queue shows groups with reason pill; up/down moves; Snooze hides a topic.
8. Session: rate Again/Hard/Good/Easy; interval labels look sane; undo works.
9. Hands-free: TTS reads the front; say "good"/"again" to grade.
10. Sunday: Sunday review tab lists fading cards from the last 7 days.

## Manifest / foundation needs

- Manifest: none (ACCESS_NETWORK_STATE and RECORD_AUDIO already declared).
- V5 Settings must call PlannerSettingsSection(nav) and ReviseSettingsSection(nav); Setup must show ExamSetupStep(onNext).
- Hilt PlannerModule is auto-discovered.

## Known gaps

- No drag reorder; no add-rule UI; nothing compiled on Android.
