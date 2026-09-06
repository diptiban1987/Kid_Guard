# 🔐 KidGuard Security Remediation — Step-by-Step

> Companion to `KidGuard_FIREBASE_API_SECRETS_BACKUP.md` (saved OUTSIDE this repo — move it
> to your personal/offline disk or a password manager NOW). Created: September 6, 2026.

## What is exposed (audit result)

| # | Item | Where it lives | Severity | Status |
|---|------|----------------|----------|--------|
| 1 | **Firebase API key** `AIzaSyDYe…Sbh-E` | `app/google-services.json` + root `google-services.json` — **both committed to git** | Medium* | 🔴 Exposed |
| 2 | **Firebase project id / bucket / app ids** | same files + `PROJECT_GUIDE.md` | Low | 🔴 Exposed |
| 3 | **Debug signing SHA-1 + OAuth client IDs** | `app/google-services.json` | Medium | 🔴 Exposed |
| 4 | **OpenRouter API key** `sk-or-v1-…fafa3` | `local.properties` → `BuildConfig.OPENROUTER_API_KEY` | **High** | 🟢 Not in git (gitignored) — but rotate, previously shared |
| 5 | **Web-monitor X-API-Key** `parental-control-key-2024` | hardcoded in `web-monitor/app.py` + `app/build.gradle.kts` — **committed** | **High** | 🔴 Exposed |
| 6 | Server `SECRET_KEY` / `JWT_SECRET_KEY` | `server/config.py` defaults `dev-insecure-change-me` | High | 🟡 Dev defaults — set real values in Render env |
| 7 | web-monitor default login `admin/admin123` | `web-monitor/app.py` | High | 🟡 Change |

\* Firebase Android client config is **public-by-design** (it ships inside the APK). The real risk
is a *leaked* API key being un-restricted — fix by **restricting** the key in Google Cloud, not
just hiding the file. **OpenRouter and web-monitor keys are true secrets and must be rotated.**

---

## PART A — Rotate / secure real secrets (do these FIRST, ~15 min)

### A1. Rotate the OpenRouter key
1. https://openrouter.ai/settings/keys → revoke the old key.
2. Create a new key → put it in `local.properties`: `openrouter.api.key=<new>`.
3. Rebuild + reinstall the `.gpt` (ChatGPT) APK.

### A2. Restrict the Firebase API key (Android keys aren't revocable from the API page)
1. https://console.cloud.google.com/apis/credentials → project `anonchat-a690b`
2. Find the Android key `AIzaSyDYe…Sbh-E` → **Edit API key**.
3. **Application restrictions** → *Android apps* → add `com.anonchat.app` SHA-1 `c36c353a…18ddd`
   (add `.gpt` flavor package likewise).
4. **API restrictions** → *Restrict key* → allow ONLY Firebase Auth API, Cloud Storage API,
   Cloud Firestore API, Cloud Messaging API (FCM). Save.
---

## PART B — Remove secrets from the repo (code changes, ~30 min)

### B1. Stop tracking both `google-services.json` files (keep them on disk for builds)
```bash
git rm --cached google-services.json app/google-services.json
```
Add to `.gitignore`:
```
# Firebase client config — injected locally, never committed
google-services.json
app/google-services.json
```
> The files REMAIN on your disk so local/CI builds keep working. If you clone fresh,
> restore them from the offline backup.

### B2. (Optional) Keep a placeholder-free setup
A missing `google-services.json` fails the build loudly instead of silently embedding bad
values — the simplest safe setup is just B1 (local file only).

### B3. Remove `parental-control-key-2024` from `app/build.gradle.kts`
Replace the hardcoded literal:
```kotlin
buildConfigField("String", "API_KEY", "\"parental-control-key-2024\"")
```
with a value read from gitignored config (same pattern as `openrouter.api.key`):
```kotlin
val webMonitorApiKey = localProperties.getProperty("webmonitor.api.key", "")
buildConfigField("String", "API_KEY", "\"$webMonitorApiKey\"")
```
Add `webmonitor.api.key=<new-value>` to `local.properties`.

### B4. Move `API_KEY` out of `web-monitor/app.py`
```python
import os
API_KEY = os.environ.get("WEB_MONITOR_API_KEY", "change-me")
```
Run web-monitor with the env var set (it's a local dev tool).

### B5. Sanitize docs
`PROJECT_GUIDE.md` / `FIREBASE_INTEGRATION.md` list project id/bucket and the owner Gmail.
Replace concrete Firebase IDs with `<project-id>` placeholders so the guide is safe for public use.

---

## PART C — Clean git history (do ONLY after A+B; it rewrites history)

The secrets exist in **commit history**, not just HEAD. To truly remove them:

1. Install `git-filter-repo` (recommended) or BFG: https://rtyley.github.io/bfg-repo-cleaner/
2. Remove the two files from ALL history:
   ```bash
   git filter-repo --path google-services.json --path app/google-services.json --invert-paths
   git filter-repo --replace-text conflicts.txt   # file listing secret==>REDACTED lines
   ```
3. Force-push: `git push origin --force --all && git push origin --force --tags`
4. `git reflog expire --expire=now --all && git gc --prune=now` locally.
5. **Make the remote safe:** if repo is public/shared, delete-and-recreate or set **private**
   (GitHub → repo → Settings → Danger Zone).
6. Re-apply B1 (files were untracked during the rewrite).

> ⚠️ The OpenRouter key + API key are already burned in HISTORY. Even after cleanup, treat
> them as compromised — rotation (Part A) is the real fix.

---

## PART D — Prevent recurrence (~10 min)

1. Add a **gitleaks** pre-commit hook (https://gitleaks.io):
   `gitleaks protect --staged --config .gitleaks.toml`
2. Keep ALL secrets in:
   - `local.properties` (already gitignored) for app build values
   - Render **Environment variables** for server secrets
   - A password manager + the offline backup file for the master copy
3. Rotate every 90 days; re-download `google-services.json` if Google rotates the Android key.

---

## ✅ Definition of done
- [ ] Backup file moved to personal disk
- [ ] OpenRouter key rotated & `local.properties` updated
- [ ] Firebase API key restricted in Google Cloud console
- [ ] `SECRET_KEY` + `JWT_SECRET_KEY` set in Render env
- [ ] `git rm --cached` both google-services.json + added to `.gitignore`
- [ ] `parental-control-key-2024` removed from both source files
- [ ] Git history purged (Part C) or repo made private
- [ ] gitleaks hook active; `git grep -i 'sk-or-v1\|AIzaS'` returns nothing

### A3. Set strong server secrets (Render env vars)
1. Render dashboard → your service → **Environment** → add:
   - `SECRET_KEY` = `python -c "import secrets; print(secrets.token_hex(32))"`
   - `JWT_SECRET_KEY` = another `token_hex(32)`
2. Deploy. (App already reads `os.environ.get(...)` — `server/config.py`.)

### A4. Rotate the web-monitor X-API-Key
1. Generate a new random string: `python -c "import secrets; print(secrets.token_urlsafe(32))"`
2. Continue to **B3/B4** to move it out of source, then set the new value in env / `local.properties`.