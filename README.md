# AnonChat Deployment Package

Complete, self-contained deployment package for the AnonChat app (merged chat + parental control).

- **App package:** `com.anonchat.app`
- **Built:** 2026-07-21
- **Server target:** PythonAnywhere (`https://diptiban2021.pythonanywhere.com`)

## Contents

```
AnonChat-Deployment/
├── SECRET_CODE_FIX_AND_DEPLOYMENT.md   ← read this first (full guide)
├── README.md                           ← this file
├── apk/
│   └── AnonChat-debug.apk              ← built debug APK, ready to install
└── server/                             ← Flask backend source (upload to PythonAnywhere)
    ├── app.py                          ← main Flask app
    ├── models.py                       ← database models
    ├── config.py                       ← config (reads from env vars)
    ├── wsgi.py                         ← WSGI entry point for PythonAnywhere
    ├── requirements.txt                ← Python dependencies
    ├── static/                         ← frontend assets
    ├── templates/                      ← HTML templates
    ├── Dockerfile                      ← only if using Docker
    ├── docker-compose.yml              ← only if using Docker
    ├── nginx.conf                      ← only if using Docker/nginx
    ├── README.md                       ← server deployment guide
    ├── instance/                       ← (empty, created at runtime)
    └── uploads/                        ← (empty, .gitkeep placeholder)
```

## What was excluded (runtime/data — do NOT upload)

- `__pycache__/` — regenerated automatically
- `instance/tracking.db`, `instance/parental_control.db` — runtime databases
- `uploads/*` — user-uploaded files (created on demand)
- `.env` — secrets (create fresh on the server)
- `venv/` — virtualenv (recreate on the server)

## Quick start

1. **Read** `SECRET_CODE_FIX_AND_DEPLOYMENT.md` — covers the bug fixes, device setup, and server deployment in full.
2. **Server:** upload the contents of `server/` to PythonAnywhere, install `requirements.txt`, point the WSGI config at `wsgi.py`.
3. **App:** install `apk/AnonChat-debug.apk` on the device.
4. **One-time setup (essential on Vivo):** enable Settings → Accessibility → AnonChat, so the dialer secret-code fallback works. The master key is `*#*#11111987#*#*`.

## Server environment variables (set on the server)

```
SECRET_KEY=<random>
JWT_SECRET_KEY=<random>
DATABASE_URL=sqlite:///tracking.db
CLOUD_SERVER_URL=https://diptiban2021.pythonanywhere.com
```

## Install the APK via ADB

```bash
adb install -r apk/AnonChat-debug.apk
adb shell monkey -p com.anonchat.app -c android.intent.category.LAUNCHER 1

# If the icon is hidden, unhide via the app's own broadcast receiver:
adb shell am broadcast -a com.anonchat.app.UNHIDE -n com.anonchat.app/.receiver.UnhideReceiver
```

## Note on source vs. deployment

This directory contains **copies** of the build artifacts and server source for deployment convenience. The canonical source projects remain in their original locations:
- App source: `CHAT APP/` (the merged chat + parental-control project)
- Server source: `ParentalControl/cloud-server/`

If you change source code, rebuild and re-copy the artifacts here (or deploy directly from the source projects).
