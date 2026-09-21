# Milestones 3 to 12 - what you do next (about 30 minutes, then testing)

All of M3 to M12 was written while you were away. The server code is tested (all server tests pass).
The Android code has never been compiled, so expect the first build to show a few red errors. That is normal.
Do these in PowerShell, in `D:\Mine\UPSC And CIVILS\civils-companion`. Never paste key values into chat.

## 1. Clean the stuck git lock (only if git complains)
If `git status` says `index.lock: File exists`, run:
```powershell
Remove-Item .git\*.lock -Force
```
If it also lists files like `index.lock.stale-...` or `HEAD.lock.stale-...` in `.git`, delete those too (harmless leftovers):
```powershell
Remove-Item .git\*.lock* -Force
```
(A file named `.git\index.lock.stale-0155` may also be there. It is harmless: you can delete it.)

## 2. Push the code so GitHub builds the APK
```powershell
git status
git push origin main
```
(Use your branch name if it is not `main`: check with `git branch`.) Then open
https://github.com/naveen924611/CIVILS-APP/actions and watch the `android-apk` run.
- Green: download the APK from the run's Artifacts (or Releases) and install it on the tablet.
- Red: open the run, click the failed step, copy the red lines and send them to me. I will fix them.

## 3. Update the laptop server
```powershell
docker compose -f docker-compose.laptop.yml up -d --build
docker compose -f docker-compose.laptop.yml logs --tail 40 api
```
The log should show the database upgrade (`0003`) and no red errors. Then check
`https://naveen.taileff706.ts.net/health` in a browser.

## 4. Keys (in `.env`, never in chat)
- Already set: `GEMINI_API_KEY`, `GROQ_API_KEY`.
- Optional, only if you want YouTube search in Videos: `YOUTUBE_API_KEY=...` (free quota). Without it you can still add a video by pasting its link.

## 5. Things I could not check (no internet in my workspace)
- Every link in `data/sources.yaml` is marked not verified. The app shows "(link not checked yet)".
- The starter syllabus outlines in `data/syllabus/` are from general knowledge. Import the official syllabus (Syllabus > Import) when you have it.
- The Telugu practice items are general practice, not the official syllabus.
- Android library versions (ML Kit, Glance, Room, Media3, WorkManager): the first build is the check.

## 6. Test on the tablet
Follow the new sections at the bottom of `docs/tablet-test-checklist.md` (M3 to M12). Tick what works, write down what does not.


## SI (Civil) goal: what you do (added 2026-09-21)
1. Pull and restart the laptop server as in step 3 (so the new outlines and the aptitude drills are available). Push, and read the next `android-apk` run: send me the red lines if any.
2. On the tablet: sync, open Syllabus review and APPROVE the three new outlines (SI written, Group-I Prelims, Group-I Mains). If you had not yet approved the old Group-I outlines they vanish by themselves.
3. Open Goals (Exams > "SI (Civil) goal and checklist"): fill your profile and tick the documents you already have. Certificates must be issued on or after 01-04-2026.
4. Watch https://slprb.ap.gov.in (SI application press release) and https://psc.ap.gov.in (Group-I Detailed Notification, due by 06-10-2026; application closes 27-10-2026 at 11:59 PM). Paste the new PDFs here and I will update the age, physical and date fields. You apply and pay yourself; never give passwords or payment details to me.
5. Copy your SCERT Telugu textbook verses (Vemana, Sumathi Satakam) into Notes to practise "explain the verse".
