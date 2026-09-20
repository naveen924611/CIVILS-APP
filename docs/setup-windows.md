# Windows laptop setup (do once)

Open **PowerShell** for every command below. Install in this order.

1. **Git** - https://git-scm.com/download/win (accept defaults). Check: `git --version`
2. **Python 3.12** - https://www.python.org/downloads/ (tick "Add python.exe to PATH"). Check: `python --version`
3. **VS Code** - https://code.visualstudio.com/
4. **Docker Desktop** (WSL2 backend) - https://www.docker.com/products/docker-desktop/ then restart. Check: `docker --version`
5. **JDK 17** - https://adoptium.net/ (Temurin 17). Check: `java -version`
6. **Android Studio** (optional) - only if you want to debug the app on your laptop. The APK is normally built by GitHub.

## Run the backend on your laptop (to try it)

```powershell
cd "D:\Mine\UPSC And CIVILS\civils-companion"
copy .env.example .env
cd backend
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements-dev.txt
python -m app.auth.hash_password      # type a strong password; copy the OWNER_PASSWORD_HASH line into ..\.env
python -c "import secrets;print(secrets.token_urlsafe(48))"   # copy the result into JWT_SECRET in ..\.env
uvicorn app.main:app --reload
```

Open http://localhost:8000/docs to see the API. Run the tests with `pytest`.

## Create the Android signing key (once, keep it safe)

```powershell
keytool -genkeypair -v -keystore civils-release.jks -alias civils -keyalg RSA -keysize 2048 -validity 10000
```

You will type two passwords and a few details. **Do not lose this file or the passwords** - future app updates must use the same key.
Then turn it into text for GitHub (see `docs/server-runbook.md`, step "GitHub secrets"):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("civils-release.jks")) | Set-Clipboard
```
