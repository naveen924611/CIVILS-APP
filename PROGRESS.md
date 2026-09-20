# Progress
Current shoot: 1 | Current milestone: M1 | Status: code written, waiting on owner steps + first CI build
Last updated: 2026-09-20

## Done
- M1 repo skeleton: CLAUDE.md (copy of FINAL-SPEC), PROGRESS.md, README, .gitignore, .env.example, gitleaks pre-commit config (2026-09-20)
- M1 backend (`backend/`): FastAPI app, single-user login (argon2 + access/refresh tokens, refresh rotation, login rate limit), device registration for push, FCM sender + test script, SQLite (WAL) + Alembic migration 0001. 12 tests pass, 83% coverage, ruff clean (Python 3.10 locally; CI uses 3.12).
- M1 deploy files: backend/Dockerfile, docker-compose.yml (api + caddy), docker-compose.dev.yml, Caddyfile (HTTPS via DuckDNS), docs/setup-windows.md, docs/server-runbook.md, docs/tablet-test-checklist.md
- M1 Android (`android/`), NOT YET COMPILED: Compose app, custom theme (spec section 5, light + dark), bundled Fraunces / IBM Plex Sans / Noto Sans Telugu, 96 dp navigation rail with all 10 destinations as placeholders, login screen (server address, username, password), Keystore-encrypted token storage, silent token refresh, FCM token registration, notification channel, unit test for the rail.
- M1 CI: .github/workflows/android-apk.yml (signed APK -> artifact + release), backend-ci.yml

## In progress
- Task: get the first APK build green in GitHub Actions
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
