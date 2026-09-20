# START HERE (owner's checklist for Milestone 1)

Your tablet runs Android 10. That is fine: the app supports Android 9 and newer.

## What is finished
- All the M1 code: server with login, the Android app skeleton (menu rail, login), build scripts, guides.
- NOT finished: the app has never been built or installed yet, and the server is not running anywhere yet.
  Only you can do that part (accounts, cards, passwords). Claude cannot create accounts.

## What to install on your Windows laptop (only 3 things)
1. Git - https://git-scm.com/download/win (accept defaults)
2. Python 3.12 - https://www.python.org/downloads/ (tick "Add python.exe to PATH")
3. VS Code (optional, for reading files) - https://code.visualstudio.com/

You do NOT need Android Studio, Java or Docker now. GitHub builds the APK for you, and the server runs on Oracle.

## Accounts to create (free; do them in this order)
| # | Account | Why | Needs | Time |
|---|---|---|---|---|
| 1 | GitHub (github.com) | stores the code and builds the APK | email | 5 min |
| 2 | Oracle Cloud Always Free | the server that runs 24/7 | card for verification (not charged) | 20-40 min |
| 3 | DuckDNS (duckdns.org) | free web address for the server | Google/GitHub login | 5 min |
| 4 | Firebase (console.firebase.google.com) | push notifications to the tablet | Google account | 10 min |
| later (M2) | Google AI Studio (aistudio.google.com) | free Gemini key | Google account | 5 min |
| later (M2) | Groq (console.groq.com) | free backup AI key | email | 5 min |
| later (M11) | Google Cloud YouTube Data API key | video search | Google account | 10 min |

## Steps, in order
1. Create the GitHub account, then a **private** repository named `civils-companion` (do not add a README).
2. Push the project (PowerShell):
   cd "D:\Mine\UPSC And CIVILS\civils-companion"
   git remote add origin https://github.com/<your-username>/civils-companion.git
   git push -u origin main
   (GitHub will ask you to sign in in the browser.)
3. Try the server on your laptop first (guide: docs/setup-windows.md, section "Run the backend on your laptop").
4. Make the Android signing key (docs/setup-windows.md) and add the 4 secrets in GitHub
   (Settings > Secrets and variables > Actions). Keep the .jks file and both passwords safe.
5. Create the Oracle account and the VM (docs/server-runbook.md sections 1-5), then the DuckDNS name.
6. Create the Firebase project, add an Android app named `com.naveen.civilscompanion`, download
   google-services.json, then add it as the GitHub secret GOOGLE_SERVICES_JSON_BASE64.
7. Add the GitHub secret API_BASE_URL = https://<your-name>.duckdns.org
8. On GitHub open Actions > android-apk > Run workflow. When it is green, open Releases and download
   civils-companion.apk.

## Install on the tablet (Android 10)
1. Open the APK link in the tablet's browser (log in to GitHub) and download it.
2. Tap the file. Android asks "Allow this source to install apps?" - allow it for that browser only.
3. Install, open the app, type the server address (https://<name>.duckdns.org), your username and password.
4. M1 is done when: the app installs, you log in, and a test push arrives
   (server command: docker compose exec api python -m app.push.send_test).

## Tell Claude after each step. Claude then fixes any build error and starts Milestone 2 (news briefs + audio).
