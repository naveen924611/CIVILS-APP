# Server on your laptop (default), Oracle later (if it works)

**Plan:** your Windows laptop is the server now. The tablet reaches it from anywhere through **Tailscale**
(a free private network with a free HTTPS address). Only one server is "the real one" at any time.
If the laptop is not good enough, or Oracle is approved, you **move** the data to Oracle with one backup file.

## What this is good for, and what it is not
- Good: free, no card, works today, perfect for building and testing Milestones 1-4.
- Limit: the laptop must be **on, awake and online** at brief time (about 6:30 AM and 6:30 PM). If it is off, the
  app still works from what it already downloaded, but no new brief arrives until the laptop is back.
- If you find yourself missing briefs, that is the signal to move to Oracle (or another cloud server).

## One-time setup (Windows, PowerShell)
1. Install **Docker Desktop** (WSL2) - docker.com. Open it once; Settings > General > tick "Start Docker Desktop when you sign in".
2. Install **Tailscale** on the laptop (tailscale.com/download) and sign in (Google or GitHub login; the free Personal plan is enough).
3. Install the **Tailscale app on the tablet** (Play Store), sign in with the **same** account, turn it on.
4. In the Tailscale admin page (login.tailscale.com) > DNS, switch on **HTTPS certificates**.
   Note: the machine name becomes visible in public certificate logs, so keep the laptop's name neutral (for example `study-laptop`).
5. Fill the server settings:
   cd "D:\Mine\UPSC And CIVILS\civils-companion"
   copy .env.example .env
   Set JWT_SECRET and OWNER_PASSWORD_HASH exactly as in docs/setup-windows.md.
6. Start the server:
   docker compose -f docker-compose.laptop.yml up -d --build
7. Publish it as HTTPS (run in PowerShell; if the command differs on your version, run `tailscale serve --help`):
   tailscale serve --bg 8000
   tailscale serve status        # shows your address: https://study-laptop.<something>.ts.net
8. Check in the laptop browser: https://study-laptop.<something>.ts.net/health  ->  {"status":"ok"}
9. In the app login screen type that same address as the **Server address**.

## Keep the laptop reachable
- Settings > System > Power: "When plugged in, put my device to sleep after" = **Never**. Screen can turn off.
- Tailscale and Docker Desktop both start at sign-in (Docker: step 1; Tailscale: default).
- Windows updates restart the laptop: set "active hours" to include 5-8 AM and 5-8 PM.

## Backups (do this weekly for now; automatic nightly backups arrive in Milestone 7)
docker compose -f docker-compose.laptop.yml exec api python -m app.tools.backup create
The file appears in `backend-data\backups\`. Copy it to another drive or cloud storage.

## Moving to Oracle (or any cloud server) later
1. On the laptop: run the backup command above.
2. Set up the Oracle VM (docs/server-runbook.md), deploy with `docker compose up -d --build`.
3. Copy the backup file to the VM's `backend-data/backups/` (scp or WinSCP), then on the VM:
   docker compose stop api
   docker compose run --rm api python -m app.tools.backup restore /app/data/backups/<file> --force
   docker compose up -d
4. In the app, log out and log in again with the new server address (Oracle's DuckDNS name).
5. Turn the laptop server off: docker compose -f docker-compose.laptop.yml down
Never run both servers as "real" at the same time: they would each generate different briefs and notes.
