# Build guide for feature builders (M3 to M12)

Read this first. It explains the foundation that already exists, so you write only feature code.
The full product spec is `CLAUDE.md` (same as `civils-companion-FINAL-SPEC.md`); the section numbers below refer to it.

## 0. The situation

- Owner: Naveen, preparing for APPSC Group-I and UPSC. Personal app for ONE user. Tablet: Lenovo Tab M10, Android 10, landscape.
- The owner is away. **Nobody can answer questions.** When something is unclear, choose the most reasonable option, write it
  down in your notes file (see section 8), and carry on. Never stop to ask.
- Everything must be free: Gemini and Groq free tiers only, no paid API, no paid service. Never create accounts, never handle
  passwords or payment details, never put keys or secrets in code, docs or logs.
- The Android app CANNOT be compiled here (no Maven/Google access). GitHub Actions compiles it later. A single compile error
  breaks the whole build, so you must act as a meticulous "human compiler" for your own Android code (section 5).
- The server CAN be tested here (`pytest`, `ruff`).
- Repo root on the device VM: `$HOME/mnt/civils-companion` (use the `mcp__remote-devices__device_bash` tool; load it with
  ToolSearch if it is deferred). Python venv with all packages: `$HOME/venv/bin/python` (use `$HOME/venv/bin/python -m pytest`,
  `$HOME/venv/bin/ruff`). Server code is Python 3.10 compatible (production uses 3.12). Read/write files with the shell
  (`cat`, `python3` scripts, heredocs) on that device.
- Other builders are working IN PARALLEL in the same working tree, each on different files. Section 2 says which files are yours.

## 1. Rules

1. Stay inside the files you own (section 2). To change anything else, do NOT edit it: describe the need in your report.
   The foundation files (sync, jobs, gateway, rag, models, RecordStore, Tables, Models.kt, Routes.kt, MainActivity, the
   other builders' files) are read-only for you.
2. **Do NOT run `git commit`, `git add`, `git stash` or anything else that changes git state.** The integrator commits.
3. Do not run tools that touch the whole repo (`ruff --fix .`, `pytest` on everything). Run `pytest` only on your own test files
   and `ruff` only on your own files (`$HOME/venv/bin/ruff check <your files> --fix`).
4. Do not edit `backend/requirements.txt`, `migrations/`, `docs/decisions.md`, `PROGRESS.md`, `build.gradle.kts`,
   `libs.versions.toml`, `AndroidManifest.xml` (EXCEPTION: the Settings/Focus/Widget builder V5 may append entries inside `<application>` in the manifest and add new files under `res/xml`). Already installed: numpy, pypdf, fpdf2, pillow, fsrs (python); ML Kit text
   recognition, CameraX, Glance app widget (android). If you need something else, say so in your report and write your code so
   it degrades gracefully (feature off) until it exists. Manifest permissions already declared: INTERNET, POST_NOTIFICATIONS,
   RECORD_AUDIO, CAMERA, ACCESS_NOTIFICATION_POLICY, SCHEDULE_EXACT_ALARM, foreground media, boot completed.
   If you need a manifest entry (a widget receiver, a service), put the exact XML snippet in your report.
5. Do not download or store YouTube video files; only store titles, channel, ids, links and the owner's own notes.
6. Respect sources: use `robots.txt`-honouring fetching, and do not work around blocked sites. Never invent facts (dates, URLs, syllabus
   content). When a fact must come from an official source and you cannot verify it, mark it "unverified" in the data and in your notes.
7. Prefer simple and robust over clever. The owner will test it later on a real tablet, with no developer to help.
8. Write plain, kind, short user-facing texts (the owner is a student, not a developer). English UI. Telugu only where the spec says.
9. New user-facing strings: write literals in Compose code (no strings.xml edits). New resources only in new files with unique names.
10. Keep every screen usable one-handed in landscape at about 1280x800 dp; touch targets at least 48 dp; both light and dark theme
    (use `Cc.colors`, never hard-coded colours).

## 2. Ownership map

| Builder | Server packages (`backend/app/features/<name>/`) | Android packages / files | Spec |
|---|---|---|---|
| V1a Library | `library` | `ui/library/*` (LibraryRoutes, LibrarySettingsSection), `ui/read/*`, `ui/capture/*`, `reader/*` | 6.3, 6.9, 6.11 (capture part), 7.2, M3 |
| V1b Syllabus+Notes | `syllabus`, `notes` | `ui/notes/*`, `ui/syllabus/*` | 6.6, 6.20, 7.1, 7.3, M4 |
| V2 Revision+Planner | `revision`, `planner`, plus `app/srs/` | `ui/revise/*`, `ui/today/*`, `srs/*` (Kotlin FSRS), `ui/exams/*` | 6.1, 6.5, 6.15, 6.19 (data steps), 7.5, 7.6, M5 |
| V3 Ask+Explain+Answers | `tutor`, `answers` | `ui/ask/*` (except AskDraft.kt), `ui/voice/*`, `ui/explain/*`, `ui/answers/*` | 6.4, 6.16, 6.21, 7.7, 7.8, M6, M9 |
| V4 Tests+Sheets+Report | `tests`, `reports` | `ui/tests/*`, `ui/sheets/*`, `ui/report/*` | 6.13, 6.17, 6.18, 6.23, 7.9, M8, M10 |
| V5 Settings+Focus+Videos | `storage`, `videos` | `ui/settings/*`, `ui/focus/*`, `ui/videos/*`, `widget/*`, `ui/setup/*`, `notify/DayNotifier*` | 6.8, 6.10, 6.12, 6.14, 6.19, 11, 12, M7, M11 |
| V6 Telugu+Compilation+Polish | `telugu`, `compilation` | `ui/telugu/*`, `ui/compilation/*` | 6.22, M12 |

Cross-cutting facts you can rely on: 
- Each builder owns the `*Routes.kt` stub of its package (it returns `Unit`; add your `composable(...)` routes there).
- Screens from other builders are opened only through `Routes` constants: `nav.navigate(Routes.readDoc(id))`.
- "Ask about this": call `AskDraft.set(text, topicId)` (inject `AskDraft`) then `nav.navigate(Routes.ASK)`.
- Settings sections: V5's Settings screen calls each builder's `XSettingsSection(nav)` composable (stubs exist, owned by that builder).
- Server features that need data another builder produces read it from the database tables (models in `backend/app/db/models_v2.py`)
  or call the shared helpers listed in section 3.

## 3. Server foundation (backend/)

### 3.1 The tables
All tables exist already: `backend/app/db/models.py` (`Card`, plus older ones) and `models_v2.py` (topics, documents, notes,
mcqs, mistakes, tests, plans, ... 40 tables). **Do not change them.** If you need extra columns or tables, put JSON in the existing
JSON columns, or define your own table in `app/features/<name>/models.py` (`class X(Base): ...`, import it from your package's
`__init__.py`; the integrator creates the migration). Column names are the JSON keys sent to the tablet.
Tablet-syncable tables use `SyncMixin`: see `app/db/sync_mixin.py`. `push_fields` are the only columns the tablet may write; everything else
is written by the server. The tablet reads every synced table through `GET /sync/pull` (rows changed since the last sync).
So: **to make data appear on the tablet, just write the rows** (set `updated_at` by writing through the ORM; it updates automatically).
Soft delete = set `deleted = True` (never physically delete synced rows).
The list of synced tables is `app/sync/registry.py`, the rules are documented at the top of `app/api/sync.py`.
Your own new tables are NOT synced unless they use `SyncMixin` and have `id`, `updated_at`, `deleted` (then also tell the integrator, and
the tablet needs a matching model, so avoid this).

### 3.2 A feature package (`backend/app/features/<name>/__init__.py`)
```python
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.services import Services, get_services

router = APIRouter(prefix="/library", tags=["library"])   # login is already required for every feature router

@router.get("/x")
def x(db: Session = Depends(get_db), svc: Services = Depends(get_services)): ...

def setup(services: Services) -> None:      # optional, called once at start-up
    services.scheduler.add_job(fn, "cron", hour=3, id="library:nightly", replace_existing=True, args=[services])
    # only when settings.scheduler_enabled is true you may register jobs; tests run with it false, so test the functions directly

from . import jobs   # importing registers your job handlers  (@job_handler) and sync hooks (@on_push)
```
The package must be importable without a network or API keys. `app/features/__init__.py` lists the names that are loaded.
Database sessions in background code: `with services.session_factory() as db: ...; db.commit()`.

### 3.3 The job queue (offline questions and slow work)
The tablet writes a `jobs` row (`type`, `payload_json`, status `queued`); the server runs it (immediately after the push, and
every 5 minutes for retries), stores `result_json`, sets `status` `done` / `failed`, and sends ONE combined push ("3 answers ready").
```python
from app.jobs.registry import job_handler, JobContext, AiUnavailable, JobFailed

@job_handler("tutor_question", feature="tutor_answer", notify="answer")   # notify: "" | "answer" | "feedback" | "ready"
def run(ctx: JobContext) -> dict:
    q = ctx.payload["question"]; db = ctx.db
    out = ctx.gateway.generate_text(feature="tutor_answer", system=..., user=...)
    if out is None: raise AiUnavailable("busy")      # retried later (up to job_max_attempts)
    return {"answer": out}                            # becomes jobs.result_json (must be JSON-able)
```
`feature` is the budget feature. Protected (always tried): `brief_item`, `tutor_answer`, `check`. Deferrable (wait when the day's AI budget
is used up): `note_merge`, `revision_sheet`, `weekly_report`, `mock_test`. Anything else is normal. Add your job types to `docs/job-types.md`
(a table: type, payload, result, who creates it). The handler may also create/update other rows (e.g. a ChatMessage) itself.

### 3.4 The AI gateway (`ctx.gateway` / `services.gateway`, see `app/llm/gateway.py`)
- `generate_json(feature=, system=, user=, schema=PydanticModel, max_output_tokens=, images=[(mime, bytes)])` -> model or None
- `generate_text(feature=, system=, user=, max_output_tokens=, images=None)` -> str or None
- `embed_texts(list[str], query=False)` -> list of vectors or None (None = use word search)
- `gateway.guard.level()` 0..3 budget pressure (1 skip MCQs, 2 shorter, 3 defer); `gateway.guard.allow(feature)`.
Never call providers directly. Prompts: put files in `app/llm/prompts/<name>.system.md` and `<name>.user.md`, load with `app.llm.promptlib.load(name)`
and `render(template, key=value)` (`{{key}}`). Ask for strict JSON where you parse the answer. Keep prompts short. Telugu text is allowed.
In tests use the fakes in `tests/helpers.py`: `FullFakeGateway(answers=[pydantic objects...], texts=[...], vectors=callable)` and
`FakeServices.build(settings, factory, gateway)`; the `env` and `client` fixtures are in `tests/conftest.py`.

### 3.5 Retrieval over the owner's own material (`app/rag/`)
`index_document(db, gateway, document_id, topic_ids=None)` builds chunks + embeddings from `DocPage` rows. `search(db, gateway, query, topic_ids=None,
document_ids=None, k=6)` returns `Hit(chunk_id, document_id, document_title, page, text, score)`. Hybrid meaning + word search, works without embeddings.

### 3.6 Other helpers
- `app/services.py`: `Services` (settings, session_factory, gateway, http client, scheduler, `push(title, body, data)`, `extras` dict, `kick_jobs()`).
- `app/api/kv.py`: `get_kv(db, key, default)`, `set_kv(db, key, value)`: owner settings shared with the tablet (`/kv`). Use dotted keys like `study.hours`.
  Each builder documents its keys in its notes file. The tablet reads and writes them with `KvRepository`.
- `app/files.py`: `uploads_dir(settings)`, `data_path(settings, ...)`, `safe_name(...)` for files under `DATA_DIR`.
- `app/tts/piper.py`: `synthesize_mp3(settings, text, path)` -> seconds (Piper + ffmpeg). `app/push/fcm.py` via `services.push`.
- `app/sync/hooks.py`: `@on_push("highlights") def h(db, row, is_new)` runs when the tablet pushes a row (keep it fast).
- Time: store UTC; the study day is India time (`Asia/Kolkata`, `settings.timezone`).
- File downloads for the tablet (PDF, audio): return `FileResponse` from an authenticated route.

### 3.7 Server tests
`backend/tests/test_<yourfeature>*.py`; aim for at least 70 percent line coverage of your package, no network (fake gateway, `httpx.MockTransport`).
Run: `cd backend && $HOME/venv/bin/python -m pytest -q -p no:warnings tests/test_<yours>.py`. Keep ruff clean on your files (`pyproject.toml` has the config).

## 4. Android foundation (android/app/src/main/java/com/naveen/civilscompanion/)

Read these files first: `data/records/RecordStore.kt`, `Table.kt`, `Tables.kt`, `data/model/Models.kt`, `data/repo/JobRepository.kt`,
`data/repo/KvRepository.kt`, `speech/TtsSpeaker.kt`, `speech/VoiceInput.kt`, `ui/common/*`, `ui/nav/Routes.kt`, and an existing screen such as
`ui/briefs/BriefsScreen.kt` (style reference), `theme/*`.

- **Data**: every synced table is a Kotlin class in `Models.kt` and a `Table` in `Tables.kt` (`Tables.Topics`, `Tables.Cards`, ...). Read with
  `store.observe(Tables.Notes, RecordQuery(k1 = topicId))` (Flow) or `store.list(...)` / `store.get(table, id)`. Write with `store.save(table, obj)`,
  `store.update(table, id) { it.copy(...) }`, `store.delete(table, id)`. Writes are local first, marked dirty, pushed by sync. `save` sets `updatedAt`.
  IDs for new rows: `TimeUtil.newId()`. Timestamps: ISO strings from `TimeUtil.nowIso()` (UTC, ending in Z). Study day: `TimeUtil.today()` (India).
  `RecordQuery` filters on the table's indexed keys k1, k2 (exact text), `numberMin/Max` on n1 (dates are epoch millis), `contains` (word search), `order`, `limit`.
  See `Tables.kt` for what k1/k2/n1/text mean per table. If a filter you need is not indexed, load with `list()` and filter in Kotlin.
  A private local-only table (never synced) is allowed: `val X = Table("local_x", X.serializer(), { it.id }, localOnly = true)` in your own file.
- **Jobs**: `jobs.enqueue("tutor_question", jobPayload("question" to q))` returns the id at once (works offline); observe with `jobs.observe(id)`; results
  arrive when the server finishes (`Job.result`, `Job.status`). `jobs.observeWaiting()` counts queued/running (for "Waiting for internet - N").
- **Settings shared with the server**: `KvRepository` (`get/put/observe` with a serializer). Local-only preferences: `Prefs` (SharedPreferences) or your own SharedPreferences.
- **Speech**: `TtsSpeaker.speakSentences(...)` (offline TTS with per-sentence callback), `VoiceInput.listen()` Flow (needs `rememberMicPermission`).
- **Shared UI**: `ScreenTitle`, `CcCard`, `Pill`, `BigButton`, `SectionLabel`, `EmptyState`, `MarkdownText(md)`, permission helpers. Icons: NO material-icons dependency exists;
  draw with `lineIcon(name, "M...svg path")` from `ui/nav/NavRail.kt` (24x24 stroke paths) or use text/emoji-free labels.
- **Navigation**: `Routes` constants; your `xxxRoutes(nav)` in your package. Route arguments: `composable(Routes.READ_DOC) { e -> ReadScreen(nav, e.arguments?.getString("docId").orEmpty()) }`
  (a `{docId}` placeholder in the route needs no `navArgument` when it is a plain string).
- **Dependency injection**: `@HiltViewModel class XViewModel @Inject constructor(private val store: RecordStore, ...) : ViewModel()` and
  `@Composable fun XScreen(nav: NavHostController, vm: XViewModel = hiltViewModel())`. Workers/receivers use `context.appEntryPoint()` (AppEntryPoint.kt is a foundation file; it exposes
  records(), jobs(), kv(), tts(), prefs(), sync()). If you need another singleton in a Worker or receiver, tell the integrator in your report (or get it via a Hilt `@EntryPoint` interface declared in YOUR own file).
- **Network calls of your own** (file upload, PDF download, YouTube search): declare your own Retrofit interface in your package and get it with a
  small Hilt `@Module` in YOUR own file (`@Provides fun myApi(@Named("authed") client: OkHttpClient, json: Json): MyApi = Retrofit.Builder()...`; copy the pattern in `di/NetworkModule.kt`:
  base URL is a placeholder and `ServerUrlInterceptor` fills in the real server). Keep `@Named("authed")` so the login token is added.
- **Theme**: `Cc.colors` (background, surface, rail, border, ink, muted, primary, onPrimary, primaryTint, accentTint, dangerTint, ...), fonts Fraunces (titles), PlexSans (text),
  `NotoSansTelugu` (Telugu text). Text style: `MaterialTheme.typography.*`.

## 5. Compile-safety checklist (Android). Apply to every file you write

The build uses Kotlin 2.2 (K2), Compose BOM 2025.09, Material3 (BOM version), Hilt + KSP, Room 2.8, release build with R8 and lint-vital.
1. Every symbol you use has an import; every import exists in the dependencies listed in `app/build.gradle.kts`. Do not import material-icons, coil, accompanist, or anything not in gradle.
2. Experimental APIs need `@OptIn(...)`: Material3 `TopAppBar`, `ModalBottomSheet`, `ExposedDropdownMenuBox`, `DatePicker`, `TimePicker`, `combinedClickable`,
   `stickyHeader`, `animateItem`, pull-to-refresh, Media3 (`@OptIn(UnstableApi::class)`). Prefer stable simple components: Text, Button, OutlinedButton, TextButton, Row/Column/Box,
   LazyColumn/LazyRow, Card-like `CcCard`, TextField/OutlinedTextField, Switch, Checkbox, Slider, LinearProgressIndicator(progress = { p }), CircularProgressIndicator, DropdownMenu, AlertDialog, HorizontalDivider.
   Tabs: build your own with Row + clickable Text (avoids experimental APIs).
3. Catch parameters must be named (`catch (e: IOException)`, never `catch (_: X)`).
4. Smart casts do not work on `var` properties or on captured mutable locals: copy to a `val` first.
5. Do not shadow: a local named like a top-level function/property you call afterwards breaks calls (an earlier bug: a property `mcqs` hid a function `mcqs()`).
6. `when` used as an expression must be exhaustive; `sealed` types list all cases.
7. kotlinx.serialization: classes need `@Serializable`; `@SerialName` for snake_case; give every property a default when reading server JSON; `JsonObject/JsonElement` fields are fine.
8. Room: do not add entities or DAOs (the foundation database is final). Use RecordStore. `Flow` collection in Compose: `collectAsStateWithLifecycle()` (import `androidx.lifecycle.compose.collectAsStateWithLifecycle`).
9. ViewModels: `viewModelScope.launch { ... }`; use `SharingStarted.WhileSubscribed(5_000)` for `stateIn`. No blocking calls on the main thread.
10. Lint that fails release: hard-coded `Intent` flags misuse, missing `PendingIntent.FLAG_IMMUTABLE` (use `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`), missing permission checks before camera/mic/notification/alarm APIs (wrap in try/catch of `SecurityException` or check the permission),
    `@SuppressLint` only when justified. `registerReceiver` needs an export flag on Android 13+ (`ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`).
11. Do not use APIs newer than minSdk 28 without a version check (`Build.VERSION.SDK_INT >= ...`) or `@RequiresApi`. The tablet runs Android 10 (API 29).
12. Compose: `Modifier` order matters but never breaks the build; `LazyColumn` items need stable keys (`key = { it.id }`); `remember` results used in lambdas must not be `var` smart-cast.
13. Keep files under about 400 lines; split screens into small composables.
14. Unit tests (`app/src/test/java/...`) are plain JVM JUnit4 only (no Android classes, no Robolectric, no coroutines-test unless it is a dependency: it is NOT). Put pure logic (scheduling maths, parsers, grammar,
    ordering, statistics) in plain Kotlin files with no Android imports so it can be tested. Each builder must ship JVM tests for its pure logic.
15. After writing, re-read every file top to bottom once as the compiler would: imports, parameter names and order of every call you make into the foundation, nullability, types.

## 6. Verification you can do

- Server: pytest + ruff on your files. Also check that the whole app still imports: `cd backend && $HOME/venv/bin/python -c "import app.main"`.
- Cross-check foundation calls by opening the foundation source (do not guess signatures).
- Server/tablet contract: what your Android code reads/writes must match your server code (routes, JSON keys, job payloads and results). Write the contract in
  your notes file and check both sides against it at the end.

## 7. Data you may need to ship (seeds)

Static data (recommended books, starter outlines, Telugu word lists, etc.) goes into `data/*.yaml|json` (repo root `data/`; the server container mounts it read-only at `/app/config`, see
`FEEDS_FILE`; read files through a setting with a sensible default like `Settings.feeds_file`'s folder, or `Path(settings.data_dir)`; mirror how `app/pipelines/news/feeds.py` finds `feeds.yaml`).
Anything you seed must be idempotent (safe to run at every start-up: upsert by a stable `key`).

## 8. What to hand back

1. Working code + tests in your files.
2. `docs/agent-notes/<your-builder-name>.md` with: what you built (short), the assumptions you made, the server/tablet contract (routes, KV keys, job types, JSON shapes),
   facts you verified or could not verify, manual test steps for the owner (numbered, plain language, for `docs/tablet-test-checklist.md`), known gaps.
3. Your final message (under 400 words): files created (grouped), tests run and results, foundation changes you would like (exact requests), manifest snippets, anything the integrator must do,
   and anything you are unsure compiles.

## 9. Contracts between builders (all builders read this)

### 9.1 Settings keys (KV, shared tablet <-> server). Owner writes the default in code; reader falls back to the same default.
| Key | Value / default | Written by (settings UI) | Read by |
|---|---|---|---|
| `ui.theme` | "system" \| "light" \| "dark" | V5 | MainActivity |
| `ui.text_scale` | 1.0 (0.8 to 1.6) | V5 | MainActivity |
| `study.hours` | `{"mon":4,"tue":4,"wed":4,"thu":4,"fri":4,"sat":4,"sun":3}` | V2 (PlannerSettingsSection, ExamSetupStep) | planner, Today |
| `study.telugu_minutes` | 15 | V2 | planner, Telugu |
| `study.library_day` | `{"enabled":false,"date":null}` | V1a (LibrarySettingsSection) | planner |
| `exam.priority` | `{"UPSC":1,"APPSC":1}` | V2 | planner |
| `revision.slot_time` | "18:00" (India time, HH:mm) | V2 | V5 reminders, planner |
| `revision.max_cards` | 80 | V2 | Revise |
| `revision.sunday_review` | true | V2 | Revise, planner |
| `plan.adjustments` | `{"week_start":"YYYY-MM-DD","extra_revision_minutes":0,"focus_topic_ids":[],"reduce_new_topics":false}` | V4 (report "Accept next week's plan") | planner |
| `voice.*` (`voice.name`, `voice.speed` 1.0, `voice.speak_on_headphones` false, `voice.hands_free_revision` false, `voice.wake_phrase` false, `voice.headphone_map`) | see V3 | V3 (AskSettingsSection) | Reader, Revise, TTS users |
| `notify.day_summary` | `{"enabled":true,"time":"21:00"}` | V5 | V5 |
| `notify.revision` | true | V5 | V5 |
| `notify.weekly_report` | true | V5 | V5 |
| `storage.limit_gb` | 20 | V5 | V5 |
| `test.negative_marking` | false | V4 (TestSettingsSection) | Tests |
A builder may add its own keys (prefix with its area) and must document them in its notes file.

### 9.2 Plan blocks
The planner (V2) writes `DailyPlan.blocks_json`. Other features add blocks through `app/features/plan_hooks.py` (`@plan_postprocessor`). Block: `{id, kind, start "HH:MM", minutes, title, detail, topic_id, ref}`; `ref` is a tablet route
(`Routes` constants, e.g. `test/<id>`, `read/<docId>`, `revise/session`) that the Start button opens. The tablet marks completion in `DailyPlan.completion_json` (`{block_id: "done"|"skipped"}`).
V2 also ships a tablet-side fallback plan (brief + revision blocks) when no DailyPlan row exists for today (offline first day).

### 9.3 Job types (details in `docs/job-types.md`; the owner of the handler is listed first)
| type | handler owner | creator | payload | result |
|---|---|---|---|---|
| `ocr_page` | V1a | V1a tablet | `{document_id, page, image_ref?}` | `{document_id, page, chars}` |
| `syllabus_import` | V1b | V1b tablet | `{exam, title, text? , document_id?}` | `{import_id}` |
| `note_merge` | V1b | V1a Capture, V1b Notes | `{topic_id?, text, source?:{document_id,page}, title?, mode?:"merge"\|"generate"\|"fix"}` | `{note_id, added:int, summary}` |
| `tutor_question` | V3 | V3 Ask | `{conversation_id, question, topic_ids?:[], via}` | `{message_id, answer, sources:[]}` |
| `explain_feedback` | V3 | V3 Explain | `{session_id}` | `{session_id}` (feedback is written to the ExplainSession row) |
| `answer_eval` | V3 | V3 Answers | `{answer_id}` | `{answer_id, score}` |
| `mock_test` / `test_generate` | V4 | V4 | `{kind, topic_id?, week_start?}` | `{test_id}` |
| `revision_sheet` | V4 | V4 | `{topic_id}` | `{sheet_id}` |
| `telugu_feedback` | V6 | V6 | `{item_id, answer}` | `{feedback, score}` |
Handlers write their main output to the proper table rows (so it syncs), and put only a short pointer in `result_json`.

### 9.4 Who reads which rows (server-written data the tablet shows)
Notes (`Note.sections.in_the_news` = `[{id,title,summary,url}]` written by V1b's server code, so the tablet never needs the news table for a topic),
Cards (created by V1b from notes and highlights, by the news pipeline for briefs, by V3 from missed explain points): `fsrs_state_json` null and `due_at` = creation time means "new".
Cards use `group` for the revision groups ("Current affairs", "Polity", ...); cards from notes use the topic's subject name.
Topic `strength` (0-1) is written by the tablet's revision code (V2) from card memory state; `status` by the owner (Syllabus map) or automatically.

### 9.5 Cross-screen helpers
`AskDraft` (ui/ask/AskDraft.kt) for "Ask about this"; `CaptureFiles` (util/CaptureFiles.kt) + `ActivityResultContracts.TakePicture` for camera photos (no CameraX needed);
`ExamSetupStep(onNext)` (V2) and `SyllabusSetupStep(onNext)` (V1b) are first-run setup pages that V5's Setup screen shows; `XSettingsSection(nav)` composables are shown by V5's Settings screen.
