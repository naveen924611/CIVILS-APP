# V5: Settings, Storage, Focus, Videos, Widget, Notifications, Setup (M7, M11)

Status: server tested (pytest, ruff, `import app.main`). Android written and read line by line as a compiler would; NOT compiled, JVM tests NOT run (no Kotlin compiler here).

## Built

**Server** (`backend/app/features/storage`, `backend/app/features/videos`)
- storage: usage by type (+ `limit_bytes` from setting `storage.limit_gb`, default 20), old-audio cleanup (clears references), export zip, nightly backup 03:30 with 7 kept, backup now, list backups, rate limits. Test added for a real restore round trip.
- videos: `POST /videos/resolve` (add by link, oEmbed, optional YouTube API), `GET /videos/search` (needs `YOUTUBE_API_KEY` on the server, otherwise a plain message), `GET /videos/{id}/summary`, job `video_summary` (`summaries.py`, prompt `videos_summary.*`), `setup()` (nothing to schedule). Only ids, titles, channel, links and the owner's notes are stored; no video file is ever downloaded.
- Tests: `test_storage.py`, `test_videos.py`, `test_videos_summary.py` (package coverage about 96 percent).

**Tablet** (`com.naveen.civilscompanion`)
- `ui/settings/`: Settings (colours + text size, daily briefs, downloads, voice, study plan = Planner/Revise/Library/Test/Telugu sections, notifications, storage, AI usage, tablet setup, log out), `StorageScreen` (route `storage`: usage bar, limit, sync now, delete audio older than 60 days, export, backups), `StorageApi` (+ Hilt module), `StorageLogic`.
- `ui/setup/`: wizard (Welcome, Exams = `ExamSetupStep`, Syllabus = `SyllabusSetupStep`, Brief times, This tablet = permissions + voice + mic + camera + Do Not Disturb, Finish = asks the server for the first plan).
- `ui/focus/`: Focus screen (timer 50+10, 25+5, custom; Pause, Finish early, Open material, "how much did you finish" 25/50/75/100, today's log), `FocusTimer` singleton (survives screens and app restarts, alarm for the end of each phase), `FocusDnd`, `FocusLogic`, `FocusRoutes` (registers focus, videos, video/{id}, storage).
- `ui/videos/`: Videos list (paste a link, search, filters), player screen (official YouTube IFrame player in a WebView, "+ Note at mm:ss", tap a time to jump, Open in YouTube fallback, "What to listen for"), `TopicVideosPanel(nav, topicId)` for a Videos tab in Notes/Library.
- `widget/`: Glance widget (next task with tap-to-start, tasks left, cards to revise, studied today, next brief).
- `notify/DayNotifier*`: revision reminder, evening day summary, Sunday weekly report, focus end; quiet hours.
- JVM tests: `DayNotifyLogicTest`, `FocusLogicTest`, `VideoLogicTest`, `WidgetLogicTest`, `StorageLogicTest`.

## Assumptions
1. Settings sections of other builders are shown as they are; my Settings adds only a heading. Study hours are edited in `PlannerSettingsSection` (not duplicated).
2. Do Not Disturb uses "priority only" (calls and alarms still ring) and restores the previous filter. Without DND access the timer works and a chip explains how to allow it.
3. Android shows at most 3 notification buttons, so the focus-end notification has 50%, 75%, 100%; the Focus screen offers 25% too.
4. The break after a full session starts automatically; Finish early has no break. A session shorter than 30 seconds is not logged.
5. Videos: adding a link and searching need internet (the server checks the video); notes work offline. A video the owner of which blocks embedding is marked `embeddable = false` and opens in YouTube. Search needs `YOUTUBE_API_KEY` on the server (free quota); unset = message only.
6. The video summary is made from title, channel, topic and the owner's notes only, and is labelled so. Stored in setting `video.summary.<video id>`.
7. Widget always uses the light palette (a widget cannot follow the in-app theme). Data comes from the tablet database, so it works offline. `updatePeriodMillis` 30 min; it also refreshes when a day alarm fires and on "Sync now".
8. Watched videos tick a today's plan block only when a block has `kind == "video"` and its `ref` is `video/<id>` or its topic matches (the planner does not create such blocks yet).
9. Weekly report notification (Sunday 19:30 India) is shown only if a ready report updated in the last 3 days exists on the tablet. If the server also pushes a "report ready" message the owner may see two.
10. Revision reminder counts cards with `due_at` up to now, capped by `revision.max_cards`, about 0.7 min per card.
11. Export is saved in `filesDir/exports` (FileProvider path already exists) and offered to the Android share sheet.

## Contract
KV keys (tablet and server): `ui.theme`, `ui.text_scale`, `notify.day_summary` `{"enabled":true,"time":"21:00"}`, `notify.revision` true, `notify.weekly_report` true, `notify.quiet_hours` `{"enabled":true,"start":"23:00","end":"06:00"}`, `storage.limit_gb` 20, `video.summary.<video id>` `{"text","note"}`. Reads `revision.slot_time`, `revision.max_cards` (V2).
Server routes: `GET /storage/usage`, `POST /storage/cleanup {audio_older_than_days}`, `GET /storage/export` (zip), `GET /storage/backups`, `POST /storage/backups/run`, `GET /usage` (AI quota, foundation), `POST /videos/resolve {url_or_id, topic_id}`, `GET /videos/search?q=&topic_id=`, `GET /videos/{id}/summary`.
Job `video_summary` `{video_id}` -> `{video_id, text, note}` (in `docs/job-types.md`).
Synced rows written by the tablet: `focus_sessions` (`minutes`, `completion_pct`, `style`, `topic_id`, `block_id`, `started_at`), `videos` (`watched`, `embeddable`), `video_notes`. The planner already reads `focus_sessions`.
Notification channels used: `revision`, `summary`, `general` (foundation) and `focus` (created by `DayNotifier`).

## Manual test steps (for docs/tablet-test-checklist.md)
1. Settings: change Colours to Dark and back; move the Text size slider, let go: the whole app changes size.
2. Settings, Notifications: turn the evening summary on for 2 minutes from now (use the time buttons), wait: "Your day" arrives. Turn Quiet hours on around now: nothing arrives.
3. Settings, Storage: the bar and numbers load. Change the limit. Press Export my data, choose Drive or email: a zip arrives with tables, notes and settings. Press Sync now.
4. Storage page: Make a backup now; it appears in the list. Delete audio older than 60 days asks first.
5. Focus: type a task, choose 25 + 5, Start. Allow Do Not Disturb when asked: the chip says it is on. Pause, Resume, open Notes (the timer keeps going), come back, Finish early, answer 75%. Today's log shows the minutes. Screen off: at the end "Session done" arrives with 50/75/100 buttons.
6. Videos: paste a YouTube link: it appears. Open it: the video plays inside the app. Press "+ Note at ..." while it plays; tap the time on the note to jump there. Airplane mode: notes still save.
7. Paste a link of a video that does not allow embedding: it shows "Opens in YouTube · link saved here" and Open in YouTube works.
8. "What to listen for" -> Make it: a few lines appear after the server answers (waits when offline).
9. Home screen: add the Civils widget. It shows the next task, cards to revise and time studied; tapping opens the app.
10. Setup: Settings, "Check notifications, battery and voice" reopens the wizard; step through all 6 steps.

## Known gaps / foundation needs
- Nothing compiled: Glance calls (`actionStartActivity(Intent)`, `ColorProvider(Color)`, `TextStyle`), `AndroidView` WebView and Compose `Canvas` are the least certain.
- `DayNotifier.rescheduleAll()` runs on Settings open, Setup finish, boot and app update. Please also call it after login in `CivilsApp` (one line, `appEntryPoint`-style) so a fresh install never waits for those. A changed revision time is picked up at the next fire or Settings visit.
- Notes/Library "Videos" tab: add `TopicVideosPanel(nav, topicId)` (ui.videos) inside the tab; not wired by me.
- Weekly-report and focus-end buttons cannot show a fourth choice (Android limit).
- Ticks on the tablet's fallback plan (local_plan_ticks, V2) are not counted in the day summary or widget; only server plan rows are.
