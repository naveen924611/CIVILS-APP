# Civils Companion — Final Project Specification (Native Android + Server)

> **To the AI agent (Claude Cowork or Claude Code):** This is the single source of truth for the project.
> 1. Copy this file to the project root as `CLAUDE.md` and keep it there.
> 2. Create `PROGRESS.md` (template in section 14) and update it at the end of **every** milestone and before any session ends.
> 3. At the start of every new session, read `CLAUDE.md` and `PROGRESS.md` first, then continue from the next unfinished milestone. Do not re-read the whole repository.
> 4. The project is built in **two shoots**: Shoot 1 = Version 1 (milestones M1–M7), Shoot 2 = Version 2 (milestones M8–M12). Finish each milestone completely, with its "done when" checks, before starting the next.
> 5. Follow the working rules in section 13 at all times.

This replaces the earlier spec, which described a PWA. The tablet app is now a **native Android app**.

---

## 1. Owner and goal

**Owner:** Naveen. He is a B.Tech CSE graduate (2026) with Docker, Kubernetes, Oracle Cloud and full-stack experience, and he builds on a **Windows laptop**. He is preparing for **APPSC Group-I (Notification 07/2026)** and **UPSC Civil Services** with **equal priority**, starting from scratch, and can study **3–5 hours a day**. He is weak in Telugu and writes Mains in English. The compulsory Telugu qualifying paper changed in the 2026 notification.

**Goal:** a personal, single-user study companion that:

- collects current affairs twice a day;
- makes category-wise notes and highlights what matters;
- tracks what has been studied and what is left;
- schedules study and revision, both smartly and by custom rules;
- reads everything aloud and takes voice commands and questions;
- queues questions and AI jobs while offline and completes them automatically when back online;
- actively sends notifications.

**Device:** Lenovo Tab M10 FHD Plus (2nd gen). MediaTek Helio P22T, 4 GB RAM, 128 GB storage, Android 9 or 10, used in landscape.

**Design reference:** the owner approved an 18-screen design. Its files are in `docs/design/`: one HTML mockup per screen (`Main.dc.html` = Today, `Brief.dc.html`, `Reader.dc.html` and so on; see `docs/design/README.md`). Open them as text to copy layout, wording, spacing and colours. They are **visual references only**: rebuild every screen natively in Jetpack Compose and never ship or embed the HTML. Section 6 describes all 23 screens in enough detail to build even the 5 that have no mockup, in the same style.

---

## 2. Hard constraints

| Constraint | Consequence |
|---|---|
| Tablet cannot run a useful local LLM | All AI work (summaries, notes, tutor, feedback, tests) runs on the server. The tablet does only lightweight on-device work: OCR, speech-to-text, text-to-speech, rule-based fact spotting, and local search. |
| Budget: free tiers first, ₹500/month absolute maximum, paid discouraged | Free LLM tiers with a hard usage guard; paid usage is off by default (section 9). |
| Must work offline | Everything except AI answers, new downloads and YouTube playback works in airplane mode. AI requests made offline are queued and run automatically on reconnect. |
| Exam accuracy | Notes and answers are grounded in the owner's sources (RAG) and show their sources. No unsourced AI "facts". |
| Single user | Simple, secure single-user authentication; no multi-tenancy. |
| Low-end hardware | Keep the app light: R8/minify on, baseline profiles, no heavy animations, lazy lists, images downsampled. Target cold start under 3 s. |
| Owner builds on Windows | All setup steps are for Windows (PowerShell). Explain each step in simple English. |
| Not on Play Store | The app is installed by sideloading a signed APK. Google Play policies do not apply, but still ask only for the permissions needed. |

---

## 3. Architecture

```
[RSS feeds, official sites, YouTube Data API]   [Syllabus PDFs, past papers, owner's uploads]
                  \                                   /
                   v                                 v
  +--------------------------------------------------------------------+
  | SERVER: Oracle Cloud Always Free VM, Docker Compose                |
  |  api (FastAPI) · worker (APScheduler jobs) · caddy (HTTPS)         |
  |  SQLite (WAL) + vector index · LLM gateway (Gemini free -> Groq)   |
  |  Piper TTS -> MP3 · FCM sender · nightly backups to Object Storage |
  +--------------------------------------------------------------------+
                  |  HTTPS REST (JSON, files, audio)  |  FCM push
                  v                                   v
  +--------------------------------------------------------------------+
  | ANDROID APP (Kotlin, Jetpack Compose), on the Tab M10              |
  |  Room DB + FTS (offline store and search) · WorkManager (sync,     |
  |  downloads, offline job queue) · AlarmManager (brief alarms)       |
  |  Media3 player + MediaSession (background audio, lock screen,      |
  |  headphone buttons) · Android TTS · SpeechRecognizer + Vosk        |
  |  ML Kit OCR · CameraX · PDF rendering · Glance widget              |
  +--------------------------------------------------------------------+
```

### 3.1 Server stack

- Python 3.12, FastAPI, Pydantic v2, SQLAlchemy 2, Alembic.
- SQLite in WAL mode behind a repository layer, so it can be swapped for Postgres later.
- Vector search with `sqlite-vec`; fall back to embedded ChromaDB if it doesn't work on the VM's CPU architecture. Record the choice in `docs/decisions.md`.
- Embeddings: local `sentence-transformers` (e.g. `BAAI/bge-small-en-v1.5`) if RAM allows, else the Gemini embeddings API.
- APScheduler for jobs. Owner-set times are stored in the DB, and jobs are rebuilt whenever settings change. Timezone Asia/Kolkata.
- `feedparser`, `httpx` and `trafilatura` for news; `pypdf`/`pdfplumber` for PDFs.
- Gemini vision for OCR of scanned PDFs, Telugu pages and handwriting.
- Piper TTS producing MP3 at 64 kbps mono, plus a sentence timing index for text highlighting.
- `firebase-admin` for FCM pushes; WeasyPrint for revision-sheet PDFs.
- Caddy with a DuckDNS subdomain for automatic HTTPS.
- Nightly backup of SQLite, uploads and audio to Oracle Object Storage (always-free tier), keeping 14 days. Restore must be tested.

### 3.2 Android stack

- Kotlin, Jetpack Compose with a custom Material 3 theme (section 5), Hilt, kotlinx.serialization, and Retrofit/OkHttp or the Ktor client.
- `minSdk 28` (Android 9). Target the current stable SDK; check at build time.
- **Room** for all offline data, with **FTS4** tables for offline search of notes, briefs and documents. **DataStore** for settings.
- Auth tokens stored with Android Keystore-backed encryption.
- **WorkManager** for:
  - periodic sync (every 30 minutes while the app is used, plus on reconnect);
  - Wi-Fi-only downloads;
  - the **offline job queue**: questions, note merges, explain-back feedback and answer evaluations, which run when the network is available.
- **AlarmManager** `setExactAndAllowWhileIdle` for brief notifications, so they fire at the set time even offline. Re-register alarms on boot (`RECEIVE_BOOT_COMPLETED`) and whenever the time changes. The tablet runs Android 9/10, where no extra permission is needed; still handle the exact-alarm permission of Android 12+ gracefully in case the app is used on a newer device.
- **FCM** for server-triggered notifications, such as "answers ready" or "brief ready early".
- **Media3 (ExoPlayer) + MediaSessionService** for background audio:
  - lock-screen and notification controls;
  - speed control and sleep timer;
  - headphone button mapping (single press play/pause, double next, triple back 15 s).
  - Long-press is often taken by the system assistant; test it and fall back gracefully.
- **Android TextToSpeech** to read any text offline (English India voice), with sentence highlighting driven by utterance progress callbacks.
- **Speech-to-text:** `SpeechRecognizer` with the offline preference. If offline recognition isn't available on the device, use **Vosk** (small English model, about 50 MB, downloaded on first use).
- **ML Kit Text Recognition v2 (Latin)** for on-device OCR of English pages. Telugu pages and handwriting go to the server (queued if offline).
- **CameraX** for scanning pages.
- **PDF:** `PdfRenderer` for page display and PdfBox-Android to extract the text layer. Scanned pages are converted with ML Kit OCR.
- **YouTube:** the `android-youtube-player` library, which uses YouTube's official IFrame player and respects embed restrictions. If a video can't be embedded, open it in the YouTube app or browser with an Intent. **Never download YouTube videos.**
- **Jetpack Glance** for the home-screen widget.
- **Do Not Disturb** during focus sessions uses `NotificationManager.setInterruptionFilter`. The owner grants notification-policy access once, from setup.
- **Fonts:** Fraunces (display) and IBM Plex Sans (body), bundled (both SIL OFL), plus Noto Sans Telugu.
- **APK builds:** a GitHub Actions workflow builds a signed release APK on every push to `main` and attaches it as an artifact or release. The owner downloads it on the tablet and installs it. Android Studio on the laptop is optional, for debugging.

### 3.3 Where each capability runs

| Capability | Tablet (offline) | Server (online) |
|---|---|---|
| Read aloud | Android TTS for any text | Piper MP3s for briefs, notes and sheets |
| Voice input | Offline STT, fixed commands, answers found in local notes | Tutor answers, explain-back feedback |
| OCR | English printed pages (ML Kit) | Telugu, handwriting, scanned PDFs |
| Fact spotting | Regex/rules: Articles, dates, numbers, amendments, committees | AI merge into topic notes, summaries, past-paper links |
| Search | Room FTS over everything stored | Vector search (RAG) |
| Scheduling | Alarms, revision queue order, planner display | Planner generation, FSRS parameter fitting |

---

## 4. Accounts, keys and setup (the owner does these himself)

The agent must never create accounts, type passwords, or handle card or payment details. Give step-by-step instructions and wait for the owner to confirm each one.

1. **Oracle Cloud Always Free.** Region Hyderabad or Mumbai; it can't be changed later. Card verification is done by the owner. If the ARM A1 shape shows "out of capacity", retry later or use the AMD micro fallback and switch embeddings/OCR to APIs.
2. **Google AI Studio:** Gemini API key.
3. **Groq:** API key (fallback provider).
4. **Google Cloud project:** enable YouTube Data API v3 and create an API key restricted to that API.
5. **Firebase project** (free Spark plan): add the Android app and download `google-services.json`. Server service-account JSON goes on the server only.
6. **DuckDNS** subdomain and token.
7. **GitHub** private repository. Store the APK signing keystore and passwords as **GitHub Actions secrets**; the owner creates the keystore with a command you give him.

**Secrets rules**
- All secrets live in `.env` on the server and laptop, or in GitHub secrets.
- `.env`, `google-services.json`, keystores and service-account files are in `.gitignore`. Commit a `.env.example` with placeholders.
- Add a `gitleaks` pre-commit hook. Never print secrets.

**Verify at build time** instead of assuming, and record findings in `docs/decisions.md`:
- current Gemini and Groq model names and free-tier limits;
- YouTube Data API quota and costs;
- GitHub Actions free minutes;
- every RSS feed URL;
- Oracle Always Free shapes;
- current stable versions of each library.

**Windows laptop setup** (explain each step): Git, Python 3.12, VS Code, Docker Desktop (WSL2 backend), and JDK 17 or the version the Android Gradle plugin needs. Android Studio is optional. If the agent's environment can't run Gradle or Android builds, it must still write complete code and rely on the GitHub Actions build, then tell the owner exactly where to download the APK.

**Tablet setup** (done in the app's first-run setup, section 6.19):
- download the offline English (India) TTS voice and offline speech pack;
- turn off battery optimisation for the app;
- grant notification, microphone, camera and DND access.

---

## 5. Visual design system (match the approved canvas)

**Colours** (light theme; derive a dark theme with the same hues)

| Token | Hex | Used for |
|---|---|---|
| background | `#F6F3EC` | warm paper background |
| surface | `#FFFDF8` | cards |
| rail | `#EFEADF` | side rail, secondary panels |
| border | `#E2DACB` | hairlines |
| ink | `#1C1B19` | primary text |
| muted | `#5E5A52` | secondary text |
| primary | `#0F5E5A` | deep teal: primary buttons, selected states |
| primaryTint | `#DCEBE8` | selected nav item, tags |
| onPrimaryTint | `#0A4441` | text on the teal tint |
| accentTint | `#F6E7D2` | saffron: "Must remember", warnings, offline chip |
| onAccentTint | `#7A4A0E` | text on the saffron tint |
| danger tint / text | `#F4D9D2` / `#6E2515` | "Again" grade, corrections |
| player bar | `#1C1B19`, progress `#7FC4B8` | audio player |

**Type**
- Fraunces for page titles (28–36 sp); IBM Plex Sans for everything else. Body text 16–18 sp; reader text 20 sp.
- A font-size setting scales everything.

**Shape and layout**
- Corners: cards 12–16 dp, buttons 10 dp.
- Touch targets at least 48 dp.
- A left navigation rail, 96 dp wide, with 10 items: **Today, Briefs, Library, Read, Notes, Revise, Ask, Focus, Alerts, Settings**.
- A floating mic button on main screens.
- No emoji. Simple line icons.

**Accessibility:** TalkBack labels on icon buttons, 4.5:1 contrast, and full dark theme support.

---

## 6. Screens

The tablet is in landscape (about 1280×800 dp usable). Screen numbers match the design canvas. Screens marked **[V1]** are built in Shoot 1; **[V2]** in Shoot 2.

**6.1 Today [V1]**
- Greeting, date and hours planned.
- Status chips: an offline indicator ("Offline · using saved content") and "N questions waiting for internet".
- **Today's plan** checklist: time, block name, detail, status, and a Start/Revise button per row. Blocks are morning brief, core study, revision, practice and evening brief.
- Right column: "Continue listening" (last audio or document with position), exam countdown (editable dates; "Date not announced" allowed), and a "This week" summary linking to the weekly report [V2].
- Floating mic.

**6.2 Briefs [V1]**
- Left list of items: number, subject, duration, heard/playing state.
- Right detail:
  - tags (UPSC GS paper, APPSC paper, Prelims/Mains);
  - title, sources, and the note "summary written by AI, check the linked source";
  - summary, a "Must remember" box and a "Mains angle";
  - linked topic, "✓ N facts added as revision cards", and "Ask about this".
- Bottom **player bar**: −15, play/pause, +15, "Item k of n · time", progress, speed, sleep timer, and the hint "Say 'next', 'repeat' or 'save this'".
- Past briefs are browsable by date.

**6.3 Read — document reader [V1]**
- Left outline: document title and chapters, plus "Open PDF" and "Scan a book page".
- Top toolbar: Reading aloud toggle, Ask about this page, Highlight, Make notes, Add to revision, and page indicator.
- Large text view. The current sentence is highlighted while reading, the page auto-scrolls, and tapping a sentence reads from there.
- Bottom player: previous/next sentence, play/pause, voice and offline label, speed, sleep timer.
- Reading position is remembered per document.
- Works for text PDFs, scanned PDFs (after OCR), images and notes.

**6.4 Ask — voice and chat [V1]**
- Conversation with chat bubbles. Answers show badges: "Answered offline from your notes" or the source document and page.
- Bottom bar: large **hold-to-talk mic**, plus a text input.
- Right panel **"Waiting for internet · N"**: each queued question with time, how it was asked, and status (queued, sending, answered). A notification fires when answers arrive.
- "You can say, even offline" chips: Read today's brief, What's next?, Start revision, Read this page, Mark done, Pause.

**6.5 Revise — card session [V1]**
- Header "Card k of n" with the subject mix, a progress bar, and a **Hands-free** toggle: the app reads the card, the owner answers aloud and says the grade.
- Card with tags (topic, "Asked in past papers"), question, revealed answer and Listen button.
- Grade buttons Again, Hard, Good, Easy, each showing its next interval from FSRS.

**6.6 Notes [V1]**
- Left: offline search box, exam filter (Both, APPSC, UPSC), and a subject → topic tree.
- Main:
  - topic title and tags (papers, importance);
  - Listen, Edit and Report error buttons;
  - tabs: **Notes, In the news, Questions, Videos [V2], My doubts, Sources**;
  - notes body with sources, a "Must remember" box, and a progress box (status, next revision, card count, weak cards).

**6.7 Alerts [V1]**
- Notification history inside the app, in the same card style as the system notifications (section 11).

**6.8 Settings [V1]**
- **Daily briefs:** morning and evening times (default 7:00 AM and 7:00 PM), on/off, add up to 2 more briefs, weekdays, Wi-Fi-only downloads.
- **Voice and reading:** voice, speed, text size, speak alerts when headphones are connected, hands-free revision, and an optional wake phrase (off by default, app-open only; warn about battery).
- **Study plan:**
  - exam dates, exam priority (default both equal);
  - hours per weekday (default Mon–Sat 4 h, Sun 3 h);
  - Telugu practice minutes;
  - optional library day;
  - revision slot and rules (links to 6.15).
- **Storage:** usage bar by type (audio, documents, study data, waiting to send), limit (default 20 GB), Sync now, delete audio older than 60 days, Export my data.
- **AI usage:** free quota used today; paid usage (off).
- Account and log out.

**6.9 Library [V1]**
- Actions: **Upload PDF, Upload images, Scan with camera**.
- Tabs:
  - **Recommended:** free official material, each with a reason ("Needed for: Polity · this week"), Download or Open, all downloaded inside the app;
  - **My uploads:** processing status such as "Processed · searchable", "Converting page 3 of 12" or "Waiting for internet (Telugu page)";
  - **Videos [V2].**
- **Standard books · optional:** each with why it helps and "Add to library-day list". The plan never depends on them.
- **Library day · optional** panel: next visit (unset allowed), chapters to read, and a tip to photograph key pages.

**6.10 Videos [V2]**
- In-app YouTube player (official IFrame player); "Open in YouTube" when embedding is blocked.
- Title, channel, duration, and badges "Plays in app" / "Opens in YouTube · link saved here".
- **Timestamped notes:** "+ Note at mm:ss". Tapping a time seeks to it. Notes are saved into the topic and work offline.
- Suggested videos per topic, plus "Paste a YouTube link".
- Watched videos tick off plan items.

**6.11 Smart note capture [V1]**
- Opens after scanning or selecting text.
- **Selection menu:** Save as point, Must remember, Make flashcard, Explain simply, Voice note.
- Three panels:
  - **Spotted on this page** (on device): Articles, amendments, dates, numbers and committees, with "Turn into flashcards";
  - **Already in your notes:** local FTS duplicate check, so only new points are added;
  - **Waiting for internet:** a queued AI merge into the topic note, a one-line summary, and past-paper links. The owner is notified when done.

**6.12 Focus [V2]**
- "Do Not Disturb is on" chip; current task; large circular countdown.
- Pause, Finish early, Open material.
- Session styles: 50+10, 25+5, custom.
- Today's focused time log.
- At the end, asks how much was finished, which feeds the planner's pace model.

**6.13 Weekly report [V2]**
- Every Sunday evening, as a notification and audio ("Listen to report").
- Hours planned vs done, topics finished, cards revised, MCQ accuracy.
- Covered this week; **weak spots** (weak topics, fading cards, low days); **next week adjusts**, with an "Accept next week's plan" button.

**6.14 Widget, lock screen, headphones [V1]**
- Glance widget: next task with Start, Play brief, time done today, cards due, next brief time.
- Lock-screen media controls with a mic button.
- Headphone mapping as in 3.2 (editable in Settings).

**6.15 Revision queue [V1]**
- Revise hub tabs: **Today's queue, Mock tests [V2], Explain it back [V2], Revision sheets [V2].**
- Queue grouped by topic. Each row shows rank, name, **reason** (Weak, Due today, Fading, From your briefs, Often in past papers), card count and time, with ↑ ↓ buttons and long-press drag.
- **Smart order / My order** toggle. Any manual move switches to My order for today; "Smart order" resets it.
- **My schedule rules:** revision slot time, subject always first, daily current affairs cards, max cards per day, Sunday full-week review, "+ Add rule".
- **Snooze** a topic to tomorrow, spreading its cards over the next days.
- "Start revision · N min" opens 6.5.

**6.16 Explain it back [V2]**
- Topic, prompt ("as if teaching a friend", 2–3 min), a big record button, and the transcript preview. Recording and transcription work offline.
- Feedback when online:
  - coverage score ("Covered 6 of 9 key points");
  - **You explained well**, **You missed**, **Needs correcting**;
  - Make cards from missed points, Try again, Hear a model explanation.

**6.17 Mock tests [V2]**
- **Weekly mock** every Sunday: 25 questions, 30 min, only this week's topics in past-paper style, with a negative-marking option. Downloaded in advance so it works offline.
- Other tests: topic test, full past papers (APPSC/UPSC by year), mistakes-only retest.
- **Analysis:**
  - score and per-subject bars;
  - mistakes classified as didn't know (→ added to the study plan), confused two options (→ comparison cards), silly mistake (→ read-slowly tip);
  - guessing analysis.
- Feeds the weekly report and the mistake book.

**6.18 Revision sheets [V2]**
- One page per topic, auto-updated as notes grow: key articles/facts, Must remember, past-paper themes, in the news and mains angle.
- Listen (about 3 min audio) and Save as PDF.
- **Last-month mode:** 30 days before an exam, revision switches to sheets (8–10 a day) plus due cards.

**Screens not on the canvas yet** (design them in the same style)

**6.19 First-run setup [V1]**
- Welcome, then server login.
- Exams and dates (unknown allowed), priority, hours per weekday, brief times.
- Syllabus approval (links to 6.20).
- Voice test and offline voice download; offline speech pack or Vosk download.
- Permissions: notifications, mic, camera, DND access, battery optimisation off.
- Finishes with the first day's plan.

**6.20 Syllabus map [V1]**
- Choose APPSC Prelims/Mains, UPSC Prelims/GS papers, or Combined.
- Paper → subject → topic tree with a status colour per topic: not started, in progress, studied, revised, strong.
- Coverage % per paper and overall, and an importance marker.
- Tap to change status or open notes.
- Approval mode for a newly imported syllabus: accept, edit, merge or split topics.

**6.21 Answer writing [V2]**
- Daily or weekly mains question (topic-linked, past-paper style) with a word limit and timer.
- Write on paper, photograph it (multi-page), and it's sent (queued if offline).
- Feedback on structure (intro/body/conclusion), content coverage, examples and data, word limit and presentation, with a model answer outline.
- History with scores over time.

**6.22 Telugu paper practice [V2]**
- Built from the official 2026 Telugu qualifying syllabus once published; ask the owner for the detailed notification PDF.
- Daily 15–20 minutes: vocabulary cards (Telugu ↔ English with audio), comprehension passages, translation practice, and letter/essay templates with tutor feedback.
- Separate progress; reserved time in the planner.

**6.23 Mistake book [V2]**
- Every wrong MCQ from quizzes, briefs and mocks, with the question, your answer, the correct answer, the explanation and the mistake type.
- Filter by subject and type; "Retest mistakes".
- Entries leave the book after being answered correctly twice in a row, spaced apart.

---

## 7. Core logic

### 7.1 Syllabus and importance
- The owner downloads the official syllabus PDFs (psc.ap.gov.in, upsc.gov.in).
- The server converts each into a topic tree with the LLM. **The owner approves it in 6.20 before use.** Never invent syllabus items.
- Topics carry `exam_tags` and cross-exam mappings (for example UPSC GS-2 Polity ↔ APPSC Polity), proposed by the system and confirmed by the owner. Shared topics count for both exams.
- Past-year questions (official PDFs downloaded by the owner) are mapped to topics by the LLM; the owner can correct mappings.
- `importance = recency-weighted PYQ frequency (0–10) + news boost`.

### 7.2 Sources
The recommended list lives in `data/sources.md`; the agent creates it.

- **Free official:** NCERT textbooks 6–12, Economic Survey, Union Budget, Yojana/Kurukshetra, AP Socio-Economic Survey and AP budget, PIB, PRS, and past papers.
- **Optional books** (print): Laxmikanth, Spectrum, G.C. Leong + atlas, an economy book, and Telugu Akademi (AP history/economy).
- **Never** download or ingest pirated material.

### 7.3 Notes (RAG)
- Ingest: extract text (OCR as needed), chunk at about 800 tokens with overlap, embed, and store with document, page and topic metadata.
- **Note generation:** overview, key points, Must remember, mains angle, 5 MCQs and 5 flashcards. Every section lists its sources.
- The prompt says **use only the provided context**; missing information is marked "Not found in your material".
- **Merge rule:** new material updates the topic note incrementally. **Owner-written or owner-edited text is never overwritten.** Notes are versioned.
- On-device fact spotting uses regexes for `Art(icle)?\s?\d+[A-Z]?`, amendments, years/dates, numbers with units, and committee/commission names.

### 7.4 Current affairs briefs
- **Schedule:** defaults 7:00 AM and 7:00 PM IST, fully customisable (6.8).
  - The server prepares each brief 30 minutes ahead and sends an FCM "ready" message.
  - The tablet downloads it in the background.
  - The **local alarm** fires at the set time regardless of connection. If today's brief isn't downloaded, the notification offers the last downloaded brief plus revision instead.
- **Pipeline:**
  1. fetch the verified feeds in `data/feeds.yaml`, respecting robots.txt and terms;
  2. deduplicate (URL, then embedding cosine > 0.9);
  3. extract text temporarily — **store only summary, metadata and link, never full articles**;
  4. LLM returns strict JSON per item: title, 120–180-word English summary, relevance for UPSC and APPSC (0–10), GS/APPSC papers, topic ids, prelims facts, mains angle, keywords, `is_ap_specific`, and up to 3 MCQs;
  5. validate with Pydantic; retry once, then skip and log;
  6. Telugu sources are translated to English in the same step;
  7. hide items with relevance < 5 for both exams (still searchable);
  8. link items to topics ("In the news");
  9. make Piper audio.
- **Current affairs flashcards:** from items with relevance ≥ 7, create up to 3 cards from `prelims_facts`, added to the "Current affairs" revision group.
- Monthly compilation on the 1st: month-wise notes and PDF by subject.

### 7.5 Spaced repetition and revision queue
- **FSRS** (the `fsrs` package on the server; a Kotlin port or equivalent on device, so grading works offline). Fit parameters on the server weekly from review history.
- Card sources: flashcards, missed explain-back points, wrong MCQs, Must remember points, current affairs facts.
- **Smart order** ranks groups by:
  `priority = w1·(1 − retrievability) + w2·weakness + w3·importance + w4·exam_proximity`.
  Start with w = 0.4, 0.25, 0.2, 0.15, kept in config.
- **My order:** a manual order for the day, stored per date.
- **Rules engine**, stored as data, never hardcoded: fixed slot, pinned subject first, daily group, max cards/day, Sunday review, last-month mode.
- **Snooze** spreads a group's cards over the next 2–3 days, within the max-per-day limit.

### 7.6 Planner
- **Inputs:** exam dates, hours per weekday, remaining topics (estimated hours, importance), reviews due, focus-timer pace data, yesterday's completion, Telugu minutes, and optional library-day chapters.
- **Day template** (scaled to the day's hours): morning brief ~12%, core study ~50%, revision ~19%, practice ~13%, evening brief ~6%.
- **Cut order when short:** new study first, then practice. Revision is protected.
- **Weighting:** both exams equal; in the last 8 weeks before the nearer prelims, tilt 70/30 toward it.
- Interleave subjects, with no more than 2 consecutive days on the same subject.
- Unfinished items roll over. After 3 missed days, re-plan the week with an honest summary.
- Sunday is lighter (review + mock) by default.
- Optional books and library days never block the plan.

### 7.7 Voice
- **Offline command grammar** (on device, no LLM): read brief, next, previous, repeat, pause, resume, faster, slower, what's next, start revision, mark done, read this page, save this, set timer N minutes.
- Unmatched speech becomes a question:
  1. try local FTS over notes and show the best matching paragraph as "Answered offline from your notes";
  2. if nothing good matches, or the owner asks for more, **queue it for the tutor**.
- **Hands-free revision:** TTS reads the question, the owner answers aloud, TTS reads the answer, and the owner says "again / hard / good / easy".
- **Explain it back:** on-device STT produces the transcript. Only the text goes to the server, which compares it against the topic's key points and returns JSON (covered, missed, wrong, score).

### 7.8 Tutor
- RAG over the owner's material, with sources.
- Modes: Explain simply, In depth, Quiz me, Evaluate my answer.
- Useful answers can be saved to notes in one tap.
- History is stored.

### 7.9 Tests, answers, reports
- **Weekly mock:** generated Saturday night from this week's studied topics plus past-paper patterns; downloaded for offline; results synced, then analysed.
- **Answer writing:** Gemini vision reads the handwriting, then LLM feedback is returned as structured JSON.
- **Weekly report:** Sunday 8 PM job produces the report, audio and a notification.
- **Mistake book** is populated from every graded MCQ.

---

## 8. Data model

Room on the device mirrors the server tables it needs. All rows have `id` (UUID), `updated_at` and `deleted` (soft delete) for sync.

- `exams(name, stage, date, is_tentative, weight)`
- `topics(parent_id, title, level, exam_tags[], est_hours, status, importance, strength, approved)`
- `documents(title, type, source_url, file_path, pages, processing_status, reading_position)`
- `chunks(document_id, page, text, topic_ids[], embedding)` (server only)
- `notes(topic_id, version, content_md, sources[], owner_edited)`
- `highlights(document_id, page, text, kind[point|must|card], topic_id)`
- `news_items(url, title, summary, relevance_upsc, relevance_appsc, tags, topic_ids[], prelims_facts[], mains_angle, published_at, source)`
- `briefs(kind, scheduled_for, item_ids[], audio_path, status)`
- `cards(front, back, topic_id, source_type, fsrs_state_json, due_at, group)`
- `reviews(card_id, grade, reviewed_at)`
- `revision_rules(type, params_json, enabled)`
- `revision_order(date, group_order[])`
- `mcqs(source_type, source_id, question, options[], answer_index, explanation, topic_id)`
- `attempts(mcq_id, test_id, chosen, correct, confidence, mistake_type, at)`
- `tests(kind, scheduled_for, mcq_ids[], status, score, analysis_json)`
- `pyqs(exam, year, paper, question, topic_ids[])`
- `explain_sessions(topic_id, transcript, feedback_json, status)`
- `answers(question, image_paths[], feedback_json, status)`
- `videos(topic_id, youtube_id, title, channel, duration, embeddable, watched)`
- `video_notes(video_id, seconds, text)`
- `daily_plans(date, blocks_json, completion_json)`
- `focus_sessions(topic_id, started_at, minutes, completion_pct)`
- `weekly_reports(week_start, data_json, audio_path)`
- `library_list(book, chapters, done)`
- `queued_jobs(type, payload_json, status, created_at, result_ref)` (the offline job queue, device side)
- `settings(key, value_json)`
- `llm_usage(provider, model, feature, tokens_in, tokens_out, at)` (server)
- `devices(fcm_token)` (server)
- `chat_messages(conversation_id, role, content, sources[], at)`

---

## 9. LLM usage and budget guard

- One gateway module handles every call:
  - provider order Gemini free → Groq free;
  - retries with backoff;
  - request/token counting per feature;
  - prompts as versioned files in `backend/app/llm/prompts/`;
  - strict JSON outputs validated with Pydantic.
- **Degrade in this order** when quota is near its limit: skip MCQ generation → shorten summaries → defer note merges, sheets and reports to the next day. **Always protect the two daily briefs and queued tutor questions.**
- `PAID_USAGE_ENABLED=false` by default. If ever enabled: hard cap ₹500/month, with a warning at 80%.
- Batch work (mocks, sheets, reports) runs at night in single batches.
- Explain-back and voice send text only, never audio.
- **Never** send passwords, keys or ID documents to the LLM. News and study content are fine; free tiers may use data to improve models.

---

## 10. Sync and offline behaviour

- **Server is the source of truth** for generated content. **The device is the source of truth** for reviews, completions, highlights, edits and focus data made on it.
- **Outbox pattern:** device changes go into Room with a `dirty` flag and are pushed by WorkManager. Pull uses `updated_since`. Conflicts: last write wins per row, except that owner edits to notes always beat AI updates.
- **Offline job queue:** each `queued_jobs` row is sent when online. Results come back via sync plus an FCM ping. The owner gets **one** combined notification ("3 answers are ready").
- Downloads (audio, recommended PDFs, mock tests) happen on Wi-Fi by default.
- Storage limit is enforced; audio older than 60 days is removed first. Notes and documents are never auto-deleted.
- **Offline acceptance test (airplane mode):**
  - open the app; read and listen to today's brief and any note or document;
  - revise and take a downloaded mock test;
  - scan an English page and save points;
  - ask a question (queued) and use voice commands;
  - receive the 7 PM alarm notification.
  - On reconnect, everything syncs and queued answers arrive.

---

## 11. Notifications

| When | Content | Actions |
|---|---|---|
| Brief times (default 7 AM / 7 PM) | "Your morning brief is ready · 12 items · 22 min · downloaded" | Play now, Read, Remind in 30 min |
| During playback | Media notification | Previous, play/pause, next, mic |
| Offline queue done | "3 answers are ready" | Listen, Open |
| Revision slot | "38 revision cards due · about 25 min" | Start, Snooze 1 h |
| 9:30 PM | Day summary: planned vs done, tomorrow ready | Open |
| Sunday | Weekly mock ready; weekly report ready [V2] | Start / Listen |
| Focus end [V2] | "Session done, how much did you finish?" | 25/50/75/100% |

- Separate Android notification channels per type, so the owner can mute any of them.
- Quiet hours are set in Settings (default 11 PM–6 AM). Nothing notifies during quiet hours except a brief the owner has deliberately scheduled inside them.

---

## 12. Security and testing

**Security**
- Single-user login: argon2 password hash, short access token plus refresh token.
- Rate-limited login, HTTPS only.
- Server: SSH keys only; firewall allows 22, 80 and 443; unattended security updates; fail2ban.
- Explain each step to the owner.

**Testing**
- **Server:** pytest (≥70% coverage) for planner, FSRS, revision ranking/rules, LLM gateway (mocked), news pipeline (fixture feeds), sync and the job queue. Add `make eval-news` to compare prompt changes on 20 saved articles.
- **Android:** unit tests for the sync engine, queue, FSRS port, command grammar and fact-spotting regexes; Compose UI tests for Today, Brief player, Revise and Ask.
- **`docs/tablet-test-checklist.md`**, run at every milestone on the real Tab M10:
  - install;
  - the offline acceptance test;
  - screen-off audio for 30 min;
  - alarms after reboot;
  - FCM delivery;
  - headphone buttons;
  - memory (no crash after 30 min);
  - cold start < 3 s;
  - battery use over a normal day.

---

## 13. Working rules for the agent

1. **Explain before acting**, in simple English, especially for servers, security, signing and deployment. Keep explanations short.
2. **Ask before** anything destructive or irreversible: deleting files or data, force-pushing, changing the firewall, restarting production, or rotating keys.
3. **Never** create accounts, type passwords, handle payment details, or commit secrets. The owner does these.
4. **Verify external facts at build time** (section 4) and log them in `docs/decisions.md`.
5. Prefer simple, well-maintained libraries. Keep the APK small and the app fast on the Helio P22T.
6. **Content honesty:** no AI facts without sources; show "verify this" when unsure. **Copyright:** store news summaries and links, not articles; ingest only material the owner legally has; never download YouTube videos.
7. **Save usage:**
   - read `PROGRESS.md` instead of re-scanning the repo;
   - edit files in place rather than regenerating them;
   - run only the relevant tests;
   - keep chat replies brief;
   - finish and commit one milestone before starting the next.
8. Commit after each working step with clear messages. Push to GitHub so the APK workflow runs.
9. If the environment can't do something (for example Gradle builds, SSH to the server, or USB to the tablet), don't stall. Write the code, then give the owner exact copy-paste commands or clicks, and wait for his result.
10. **End of each milestone:** update `PROGRESS.md`, summarise what was built, list what the owner should test on the tablet, and note known issues. **Stop and wait for his OK.**
11. If a session is about to end or hit a usage limit, **first** write the current state and exact next step into `PROGRESS.md` and commit.

---

## 14. `PROGRESS.md` template

```markdown
# Progress
Current shoot: 1 | Current milestone: M1 | Status: in progress
Last updated: <date/time>

## Done
- (milestone, what works, date)

## In progress
- Task:
- Files touched:
- Exact next step:

## Waiting on owner
- (e.g. "Create Firebase project and put google-services.json in android/app/")

## Known issues
-

## Decisions (short; details in docs/decisions.md)
-
```

**Repository layout**

```
civils-companion/
  CLAUDE.md  PROGRESS.md  README.md  .env.example  .gitignore
  docker-compose.yml  docker-compose.dev.yml  Caddyfile
  .github/workflows/android-apk.yml  .github/workflows/backend-ci.yml
  backend/app/{main.py,config.py,auth/,db/,llm/prompts/,pipelines/{news,ingest,notes,pyq,tests,reports}/,
               planner/,srs/,revision/,tts/,push/,youtube/,api/}  backend/tests/
  android/app/src/main/java/.../{data/(room,remote,sync,queue),domain/,ui/(today,briefs,library,reader,
               notes,revise,ask,focus,alerts,settings,setup,syllabus,videos,tests,answers,telugu,mistakes),
               audio/,voice/,ocr/,alarms/,widget/,theme/}
  data/{feeds.yaml,sources.md,syllabus/}
  docs/{decisions.md,tablet-test-checklist.md,setup-windows.md,server-runbook.md}
```

---

## 15. Build plan: two shoots, twelve milestones

Each milestone ends with a tablet check by the owner. A milestone may take several sessions; `PROGRESS.md` makes that safe.

### SHOOT 1: Version 1 (daily-use app)

**M1 — Foundations**
- Owner account steps (section 4), guided one at a time.
- Repo skeleton, `CLAUDE.md`, `PROGRESS.md`.
- Backend skeleton with auth.
- Oracle VM provisioning and hardening, Docker Compose, Caddy + DuckDNS HTTPS.
- Android project: theme (section 5), navigation rail with all 10 destinations as placeholders, login.
- GitHub Actions signed-APK workflow.
- *Done when:* the APK builds in GitHub Actions, installs on the tablet, and logs in to the HTTPS server; a test FCM push arrives.

**M2 — Briefs, audio, notifications**
- Feed verification, news pipeline, LLM gateway + budget guard, Piper audio.
- Brief scheduling with custom times, FCM, local alarms (+ boot re-register).
- Briefs screen (6.2) and Media3 player with lock-screen and headphone controls.
- Alerts screen, notification channels, current affairs flashcard creation.
- The permissions part of first-run setup (6.19): notifications, battery optimisation off, offline voice download. The rest of setup comes in M5.
- *Done when:* 3 days of briefs arrive at the set times with audio; changing the time works; audio plays with the screen off; everything works offline once downloaded.

**M3 — Library and Reader**
- Uploads (PDF, images, camera), ML Kit OCR, server OCR for Telugu (queued offline).
- Recommended materials and optional books with library-day list (6.9).
- Reader (6.3) with TTS highlighting, tap-to-read and remembered position.
- *Done when:* an NCERT PDF and 5 photographed pages are readable aloud offline with highlighting.

**M4 — Syllabus, Notes, Smart capture**
- Syllabus import + approval, syllabus map (6.20).
- Ingestion + RAG notes; Notes screen (6.6) with offline FTS search.
- Smart capture (6.11) with on-device fact spotting, duplicate check and queued AI merge.
- *Done when:* notes for 5 real topics are generated with correct sources; capture works offline and merges when online.

**M5 — Revision and Planner**
- FSRS (server and device), Revise session (6.5) including hands-free mode.
- Revision queue (6.15) with smart/my order, drag, rules and snooze.
- Planner and Today screen (6.1); the rest of first-run setup (6.19).
- *Done when:* a week of plans is used; reordering and rules behave as specified; reviews sync after offline use.

**M6 — Ask and voice**
- Tutor (7.8), Ask screen (6.4), offline question queue with combined notification.
- Offline voice command grammar, local-notes answers, floating mic everywhere.
- *Done when:* questions asked in airplane mode are answered automatically after reconnecting, and every offline command works by voice.

**M7 — Settings, widget, hardening**
- Settings (6.8), Glance widget (6.14), storage management, export.
- Backups + tested restore; the full offline acceptance test; performance and battery tuning.
- *Done when:* the whole tablet checklist passes. **Shoot 1 complete.**

### SHOOT 2: Version 2 (exam-sharpening tools)

**M8 — Tests and mistakes**
- Past-paper import and importance scores; MCQ engine.
- Weekly mock and other tests (6.17) with analysis; mistake book (6.23).
- *Done when:* a Sunday mock downloads, runs offline and produces its analysis.

**M9 — Explain it back and answer writing**
- 6.16 and 6.21, with queued feedback.
- *Done when:* a recorded explanation and a photographed answer both get feedback after reconnecting.

**M10 — Revision sheets, weekly report, last-month mode**
- 6.18 with PDF and audio; 6.13; the last-month mode rule.
- *Done when:* sheets exist for all studied topics; the Sunday report arrives with audio and next-week changes.

**M11 — Focus and videos**
- Focus timer with DND and pace data (6.12).
- YouTube search per topic, in-app player, fallback, timestamped notes (6.10); Videos tabs in Notes and Library.
- *Done when:* a focus session logs time and adjusts tomorrow's plan; embeddable videos play in the app and blocked ones open YouTube.

**M12 — Telugu module and polish**
- Telugu practice (6.22) once the syllabus is available.
- Monthly current affairs compilation.
- Dark theme review, accessibility pass, final performance pass.
- *Done when:* the full checklist passes again. **Shoot 2 complete.**

---

## 16. Open items (ask the owner when reached)

- APPSC Group-I prelims and mains dates (not announced when this was written; application window 6–27 October 2026). UPSC CSE 2027 dates from the UPSC calendar.
- Detailed 2026 notification PDF for the Telugu paper syllabus.
- Piper voice choice after listening to samples.
- Headphone model, to confirm button behaviour.
