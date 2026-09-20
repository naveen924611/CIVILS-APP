# Decisions and verified facts
Log build-time verifications here (model names, quotas, feed URLs, library versions, Oracle shapes).

| Date | Topic | Finding |
|---|---|---|
| 2026-09-20 | Gradle | Current stable Gradle reported as 9.7.1 (services.gradle.org). CI pins 8.14.3 to match AGP 8.13. |
| 2026-09-20 | AGP | developer.android.com lists AGP 9.4.0 as newest (needs Gradle 9.6+, JDK 17). Not used yet: AGP 9 changes Kotlin/KSP/Hilt integration and could not be test-built here. |
| 2026-09-20 | Android versions | Chosen: AGP 8.13.0, Kotlin 2.2.20, KSP 2.2.20-2.0.2, Hilt 2.57.1, Compose BOM 2025.09.00, Retrofit 3.0.0, OkHttp 5.1.0, Firebase BOM 34.3.0. **Unverified**: Maven was blocked from the build sandbox; the first GitHub Actions run is the check. |
| 2026-09-20 | Package name | `com.naveen.civilscompanion` (`in` is a Kotlin keyword). |
| 2026-09-20 | Fonts | Fraunces, IBM Plex Sans, Noto Sans Telugu (all SIL OFL 1.1) from Fontsource npm packages, converted woff2 -> ttf; Latin/Telugu subsets only. Licences in app assets. |
| 2026-09-20 | Vector search | Not needed until M4. Decide sqlite-vec vs ChromaDB then. |
| 2026-09-20 | Server hosting | Oracle sign-up error. Laptop = default server via Tailscale (free personal plan, HTTPS certs, `tailscale serve`); cloud (Oracle/Hetzner ~EUR 5.49 CX23/GCP e2-micro US-only) is the later move. Free hosts (Render/Koyeb) rejected: sleep + no persistent disk. Tailscale commands not yet run by us; verify with `tailscale serve --help`. |
| 2026-09-20 | LLM models (M2) | Default chain `gemini:gemini-3.5-flash-lite` -> `gemini:gemini-3.8-flash` -> `groq:openai/gpt-oss-20b`, taken from Google/Groq model pages at build time. Names change often: the owner runs `python -m app.llm.check` on the server, and the chain is one line in `.env` (`LLM_CHAIN`), no code change needed. Own daily budgets: Gemini 400, Groq 1000 requests (conservative; real free limits are shown in AI Studio / Groq console). |
| 2026-09-20 | Scheduler | Spec says a separate worker container. Deviation: APScheduler runs inside the API process (one container, one SQLite writer, simpler on a laptop). Jobs are rebuilt from the Settings table at start and whenever briefs settings change. Move to a worker later if the API ever gets heavy. |
| 2026-09-20 | Dedup | Uses cleaned URL + title-word overlap (Jaccard >= 0.6) instead of embeddings. Embeddings/sqlite-vec come in M4 with the syllabus; this keeps the Docker image small now. |
| 2026-09-20 | News feeds | `data/feeds.yaml` lists candidate feeds (PIB, The Hindu, Indian Express, RBI). URLs are UNVERIFIED from the build sandbox: run `python -m app.pipelines.news.verify_feeds` on the laptop server; it writes `docs/feeds-report.md`. Disable dead ones with `enabled: false`. robots.txt is honoured for feeds and articles. Article text is read only to summarise and is never stored: only summary, metadata and link. |
| 2026-09-20 | Blocked sites | WebFetch refuses some news sites (The Hindu, Indian Express). Not worked around; their feeds are fetched by the owner's own server at run time under robots.txt rules. |
| 2026-09-20 | TTS | Piper (`piper-tts` pip package, CLI `piper -m <voice>.onnx -f out.wav`, voices fetched with `python -m piper.download_voices`) then ffmpeg to 64 kbps mono MP3. Default voice `en_GB-alan-medium`; `python -m app.tts.samples` makes samples of six voices so the owner can choose. Piper CLI flags are from its docs; first real run on the server is the check. |
| 2026-09-20 | Push for briefs | FCM data-only message (high priority) so the app builds its own notification with Play / Open buttons. Tablet also catches up via `GET /sync/pull`, so a missed push loses nothing. |
