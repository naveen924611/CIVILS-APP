# Milestone 2 - update your laptop server (about 15 minutes)

Do these in PowerShell, in the project folder (`D:\Mine\UPSC And CIVILS\civils-companion`).
Never paste key values into chat. Keys live only in `.env`.

## 1. Get the new code and add your keys
```powershell
git pull
notepad .env
```
In `.env` make sure these lines exist (values are yours; no quotes, no spaces):
```
GEMINI_API_KEY=your-gemini-key
GROQ_API_KEY=your-groq-key
```
Save and close Notepad.

## 2. Rebuild and start
```powershell
docker compose -f docker-compose.laptop.yml up -d --build
```
The first build is slower (it installs ffmpeg and the speech engine). Then check:
```powershell
docker compose -f docker-compose.laptop.yml ps
docker compose -f docker-compose.laptop.yml logs --tail 30 api
```
You should see "scheduled morning brief" and "scheduled evening brief".

## 3. Test the AI keys
```powershell
docker compose -f docker-compose.laptop.yml exec api python -m app.llm.check
```
Every line should say `OK`. If a model says FAIL with "bad_request: 404", that model name has changed: open `.env`, edit `LLM_CHAIN`, and run again. Copy the output to me (it never shows your keys).

## 4. Check the news sources
```powershell
docker compose -f docker-compose.laptop.yml exec api python -m app.pipelines.news.verify_feeds
Get-Content docs\feeds-report.md
```
Copy the report to me. Feeds that fail get switched off in `data/feeds.yaml` (`enabled: false`).

## 5. Choose the reading voice
```powershell
docker compose -f docker-compose.laptop.yml exec api python -m app.tts.voices download
docker compose -f docker-compose.laptop.yml exec api python -m app.tts.samples
explorer backend-data\voice-samples
```
Play the MP3 files (each is one voice). Put your favourite name in `.env` as `PIPER_VOICE=...`, then:
```powershell
docker compose -f docker-compose.laptop.yml exec api python -m app.tts.voices download <that-name>
docker compose -f docker-compose.laptop.yml up -d
```

## 6. Make a test brief
Log in from the tablet app (after installing the new APK from the next GitHub build) and tap "Prepare a brief now" on the Briefs screen. It takes a few minutes: it reads the news, asks the AI, and records audio.
