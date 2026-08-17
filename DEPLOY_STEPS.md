# Step-by-Step Deployment Guide — PythonAnywhere

This is the **only document you need**. Follow the steps in order. Each step has
a **Verify** box — don't move on until it passes.

> This guide assumes you are deploying to the same account the project is
> already wired to (`diptiban2021.pythonanywhere.com`). If you are using a
> different username, replace `diptiban2021` everywhere.

---

## What this guide fixes

You reported:

```
POST /api/auth/forgot-password   → 500 Internal Server Error
POST /api/auth/login              → 401 Unauthorized
(random)                          → 404 Not Found
```

Root causes (verified by hitting your live URL):

1. **The 500 is `sqlite3.OperationalError: disk I/O error`.** PythonAnywhere's
   network filesystem doesn't reliably support SQLite file locking. Every
   **write** fails with disk-I/O error; reads succeed (which is why login
   gives a normal 401 instead of 500). This breaks register, forgot-password
   for a real email, reset-password, pairing, device reporting — anything that
   writes to the DB.
2. **The 401 on login is correct.** You're resetting the password BECAUSE it's
   wrong — the server is doing the right thing. Once you complete the reset
   flow, login will succeed.
3. **The 404 is `/favicon.ico`** (the login page doesn't link one). Harmless —
   you can ignore it.
4. **Your WSGI entry point is still the deprecated monolith `app.py`**,
   not the new blueprint-based package. The security + bug fixes won't reach
   you until you repoint the WSGI file (Step 4 below).

The fixes (already committed & pushed via the commit attached to this doc):

| File | Fix |
|---|---|
| `server/wsgi.py` | Auto-picks PythonAnywhere MySQL env vars (skipping SQLite) + sets `FLASK_AUTO_CREATE=1` so tables auto-create on boot |
| `server/blueprints/auth.py` | `forgot-password` returns the 8-char token in DEV mode (no SMTP) so the frontend reset flow actually completes |
| `server/migrations/versions/0004_password_reset_tokens.py` | Idempotent migration that recreates `password_reset_tokens` if missing |
| `server/requirements.txt` | Added `PyMySQL` + `cryptography` (needed for MySQL) |
| `PYTHONANYWHERE_DEPLOY.md` | Updated prose version of this guide |

---

## Step 1 — Pull the latest code on PythonAnywhere

1. Go to the PythonAnywhere dashboard → **Consoles** → open the existing
   **Bash** console (or start a new one).
2. Run:

```bash
cd ~/kidguard
git pull origin main
```

Expected output ends with: `Fast-forward` or `Updating <hash>..<hash>` and
a list of changed files including `server/wsgi.py`, `server/blueprints/auth.py`,
`server/migrations/versions/0004_password_reset_tokens.py`.

If you see **merge conflict** markers, force-sync to remote first:

```bash
git fetch origin
git reset --hard origin/main
```

> This discards any local edits on the server. That's almost certainly fine
> because the canonical source is GitHub — but if you edited files on the
> PythonAnywhere console directly, copy them somewhere first.

**Verify:**

```bash
ls server/migrations/versions/0004_password_reset_tokens.py
# Should print the path (file exists)
```

---

## Step 2 — Install the new Python dependencies

Still in the Bash console:

```bash
cd ~/kidguard
pip3 install --user -r server/requirements.txt
```

The new packages are **PyMySQL** (MySQL driver) and **cryptography** (its
auth dependency).

**Verify:**

```bash
python3 -c "import pymysql, cryptography; print('PyMySQL', pymysql.__version__, '— OK')"
# Should print: PyMySQL 1.1.0 — OK
```

---

## Step 3 — Create a MySQL database (the actual fix for the 500s)

SQLite on PythonAnywhere throws `disk I/O error` on writes. Use the **free
MySQL** they provision — it's the only supported DB for web apps on the free
tier.

1. In the PythonAnywhere dashboard, click the **Databases** tab.
2. If you've never used MySQL here, click **Initialize MySQL**. A password
   is shown once — **copy it to a safe place** (you won't see it again).
3. Under **Create a database**, name it `kidguard` (the displayed name will
   be `diptiban2021$kidguard`).
4. Done. No need to create tables — the server does that automatically on
   first request (Step 6 confirms).

**Verify:**

In the Bash console:

```bash
env | grep -i MYSQL
# Should show lines like:
#   PA_MYSQL_DATABASE=diptiban2021$kidguard
#   PA_MYSQL_USER=diptiban2021
#   PA_MYSQL_PASSWORD=<hidden-but-set>
#   PA_MYSQL_HOST=<host>
#   PA_MYSQL_PORT=3306
```

If `PA_MYSQL_PASSWORD` does NOT appear, set it manually in your WSGI file
(in Step 4) — that happens on some older PA accounts. Use the password you
copied in sub-step 2.

---

## Step 4 — Point your web app at the new WSGI entry point

This is the most important step. Your WSGI is currently importing the
deprecated `app.py`; it must import `server.wsgi:application` instead.

1. In the PythonAnywhere dashboard, click the **Web** tab.
2. Click the link to your **WSGI configuration file** (something like
   `/var/www/diptiban2021_pythonanywhere_com_wsgi.py`).
3. Replace its ENTIRE contents with:

```python
import sys, os

# The Flask package lives in server/ — the parent (kidguard/) must be on
# the path so `import server` resolves.
project_home = '/home/diptiban2021/kidguard'
if project_home not in sys.path:
    sys.path.insert(0, project_home)

# ----- Strong random secrets (replace with your own 32-char strings) -----
# Generate fresh ones: python3 -c "import secrets; print(secrets.token_hex(32))"
os.environ['SECRET_KEY']      = 'PASTE_A_32_CHAR_HEX_SECRET_HERE'
os.environ['JWT_SECRET_KEY']  = 'PASTE_ANOTHER_32_CHAR_HEX_SECRET_HERE'
os.environ['CLOUD_SERVER_URL'] = 'https://diptiban2021.pythonanywhere.com'

# ----- MySQL (PythonAnywhere auto-sets these when you create the DB) -----
# Leave DATABASE_URL unset so server/wsgi.py can auto-pick PA_MYSQL_* vars.
# If PA_MYSQL_PASSWORD is missing from env, set it manually:
# os.environ['PA_MYSQL_PASSWORD'] = 'the-password-you-copied-in-step-3'

# Auto-create missing DB tables on boot (no need to run alembic by hand)
os.environ['FLASK_AUTO_CREATE'] = '1'

from server.wsgi import application  # ← THE KEY CHANGE
```

4. **Save** the file.
5. Scroll back up in the Web tab and set **Source code** and **Working
   directory** both to:
   ```
   /home/diptiban2021/kidguard
   ```

**Verify (before reload):**

In a Bash console:

```bash
python3 -c "
import os, sys
sys.path.insert(0, '/home/diptiban2021/kidguard')
os.environ.setdefault('FLASK_AUTO_CREATE','1')
# Don't set DATABASE_URL — let wsgi.py auto-pick MySQL
from server.wsgi import application
print('import OK; DB =', application.config['SQLALCHEMY_DATABASE_URI'])
"
```

Should print something like:
```
import OK; DB = mysql+pymysql://diptiban2021:...@<host>:3306/diptiban2021$kidguard?charset=utf8mb4
```

If it prints `sqlite:///...` instead, your `PA_MYSQL_*` env vars are not
visible. Go back to Step 3, or set `DATABASE_URL` explicitly in the WSGI
file (uncomment + fill in the line shown in the comment above).

---

## Step 5 — Reload the web app

1. Still in the **Web** tab.
2. Click the green **Reload** button (you may need to scroll down to find
   it on mobile).
3. Wait ~5 seconds for it to settle.

**Verify server is up:**

From your local machine (PowerShell or curl):

```bash
curl https://diptiban2021.pythonanywhere.com/
# Returns: HTML of the login page
```

```bash
curl -X POST https://diptiban2021.pythonanywhere.com/api/auth/forgot-password `
     -H "Content-Type: application/json" `
     -d "{\"email\":\"nonexistent@example.com\"}"
# Returns 200 with: {"message":"If that email exists...","token":null}
# (token:null is correct here — that email isn't registered)
```

If you see a 500, open the **Error log** in the Web tab and look at the
bottom 20 lines — that will name the exact problem (usually: PyMySQL not
installed, MySQL env vars not set, or wrong path in the WSGI file).

---

## Step 6 — Confirm tables were created in MySQL

In a Bash console:

```bash
mysql -u "$PA_MYSQL_USER" -p"$PA_MYSQL_PASSWORD" -h "$PA_MYSQL_HOST" \
  "$PA_MYSQL_DATABASE" -e "SHOW TABLES;"
```

You should see **23 tables** (alphabetical) including:
```
alembic_version
audit_log
devices
password_reset_tokens    ← the one that was missing before
users
token_blocklist
...
```

If you see fewer than 23 tables, force table creation:

```bash
python3 -c "
import os, sys
sys.path.insert(0, '/home/diptiban2021/kidguard')
from server import create_app
from server.extensions import db
app = create_app()
with app.app_context():
    db.create_all()
    print('tables created')
"
```

---

## Step 7 — Test the Forgot Password flow end-to-end

This is the original bug. Let's confirm it's fixed.

1. Open `https://diptiban2021.pythonanywhere.com/` in your browser.
2. Click **"Forgot Password?"**.
3. Enter your **registered email** → click **Request Reset Token**.
4. An 8-character token appears in the yellow box (e.g. `AB12CD34`).
5. Copy the token into the **Reset Token** field.
6. Enter a **new password** (min 6 chars) → click **Set New Password**.
7. You'll be redirected to the dashboard with a working login session.

**Verify from the command line (optional, equivalent test):**

```bash
# Replace your.real@email.com with the email you registered with
curl -X POST https://diptiban2021.pythonanywhere.com/api/auth/forgot-password `
     -H "Content-Type: application/json" `
     -d "{\"email\":\"your.real@email.com\"}"
# Returns 200 with: {"message":"...","token":"AB12CD34","expires_in_seconds":1800,...}
```

> The token is shown on the page because no SMTP is configured (dev mode).
> Once you wire up `MAIL_SERVER` (Step 9 — optional), the token will be
> emailed instead and the response won't include it.

---

## Step 8 — Test login + register

Use the new password to verify login works:

1. Still on the website, sign out (or open a private window).
2. Sign in with email + new password.
3. Dashboard should load fully.

Test register as a brand-new parent:

```bash
curl -X POST https://diptiban2021.pythonanywhere.com/api/auth/register `
     -H "Content-Type: application/json" `
     -d "{\"email\":\"test_$(date +%s)@example.com\",\"password\":\"test123456\",\"display_name\":\"Test\",\"role\":\"parent\"}"
# Returns 201 with: {"token":"...","refresh_token":"...","user":{...}}
```

(In the **Web** tab → **Error log** you can watch live requests.)

---

## Step 9 (optional) — Wire up email delivery for reset tokens

Right now reset tokens are displayed in the browser (dev mode). To email them
to users (prod-safe), set up Gmail SMTP:

1. Create an app-password on your Gmail account:
   https://myaccount.google.com/apppasswords
2. Edit your WSGI configuration file (Web tab) and add these env vars next
   to the others:

```python
os.environ['MAIL_SERVER']   = 'smtp.gmail.com'
os.environ['MAIL_PORT']     = '587'
os.environ['MAIL_USE_TLS']  = '1'
os.environ['MAIL_USERNAME'] = 'your_sender@gmail.com'
os.environ['MAIL_PASSWORD'] = 'your_16_char_app_password'
os.environ['MAIL_FROM']     = 'your_sender@gmail.com'
```

3. Save + Reload the web app.

**Verify:** trigger forgot-password again. This time:
- Response body does **NOT** include `token`
- You receive an email with the 8-character code
- Paste the emailed code into the reset form — it works

---

## Troubleshooting by symptom

| Symptom | Likely cause | Fix |
|---|---|---|
| 500 on forgot-password with `disk I/O error` | Still on SQLite | Do Step 3 (create MySQL) + Step 4 (repoint WSGI) |
| 500 on forgot-password with `no such table: password_reset_tokens` | Migration not applied | Step 6 — force `db.create_all()` |
| 500 with `ModuleNotFoundError: No module named 'pymysql'` | Step 2 not done | `pip3 install --user pymysql cryptography` |
| 404 on every page | WSGI import line wrong | Step 4 — must be `from server.wsgi import application` |
| 401 on login | Wrong password (correct behavior) | Use forgot-password flow to reset |
| 404 on `/favicon.ico` | No favicon linked | Harmless, ignore |
| `KeyError: 'PA_MYSQL_PASSWORD'` | PA env var not set (older accounts) | Set it manually in WSGI file (uncomment line in Step 4) |
| Dashboard loads but blank | No devices paired yet | Register parent → generate pairing code → claim on child APK |
| Reload says "error log" | Something crashed on boot | Open Error log in Web tab — bottom 20 lines name the problem |

---

## Rollback (if something breaks)

If the new server doesn't work and you need to revert:

1. In the Bash console:
   ```bash
   cd ~/kidguard
   git log --oneline -5        # find the last working commit hash
   git checkout <hash>         # switch to that commit
   ```
2. In the Web tab, edit your WSGI file and change the import back to:
   ```python
   from app import app as application
   ```
3. Reload the web app.

The OLD `app.py` monolith is still in the repo (deprecated but functional)
so you can run it as a fallback while debugging.

---

## Summary checklist (TL;DR for next time)

- [ ] `git pull origin main` on the server
- [ ] `pip3 install --user -r server/requirements.txt`
- [ ] Create MySQL DB in **Databases** tab (note the password)
- [ ] Edit WSGI file → `from server.wsgi import application`
- [ ] Set `SECRET_KEY` + `JWT_SECRET_KEY` env vars in WSGI file
- [ ] Leave `DATABASE_URL` unset (auto-pick MySQL)
- [ ] **Reload** the web app
- [ ] Test forgot-password flow end-to-end
- [ ] Confirm 23 tables in MySQL via `SHOW TABLES;`

That's it — the deployed server now uses MySQL, has the security fixes from
the blueprint refactor, and the forgot-password flow works.

---

# Part 2 — Free up PythonAnywhere quota (or migrate to Render)

If you're hitting PythonAnywhere's free-tier limit ("free quota exceeded"),
you have two choices:

| Choice | When to pick |
|---|---|
| **A. Free up space on PythonAnywhere** (below) | You want to keep the existing URL `https://diptiban2021.pythonanywhere.com` and the data already there. Best if your kid's phone is paired + reporting, and you don't want to re-pair. |
| **B. Migrate to Render** | Free quota is mostly CPU/concurrent-worker-related (not disk) OR you don't mind re-pairing the child device. Cleaner stack (Postgres, push-to-deploy, no SQLite-on-NFS headaches). See `RENDER_DEPLOY.md` for the full guide. |

Pa's free quota is shared across three resources:
1. **CPU seconds per day** (2,500 s/day on free) — the most common limit
2. **Disk space** (512 MB)
3. **Concurrent workers** (only one web worker allowed)

Most "quota exceeded" errors are **CPU seconds** — the dashboard polling 24/7
burns CPU at a surprising rate. Cleaning up files won't fix that; you need
either to reduce polling frequency or migrate.

---

## How to free up PythonAnywhere quota

### Check what's using your quota

1. Go to the dashboard → **Account** → look at the **"Free Tier Limits"**
   panel. It shows CPU usage / disk usage bars. Identify which is exhausted.

### Reduce disk usage (largest items first)

In a Bash console:

```bash
# 1) See the biggest files/dirs in your home:
du -h -d 2 ~ | sort -h | tail -20

# 2) The single biggest space-hog is usually __pycache__ dirs + the Python
#    package cache ~/.local — safe to clear:
find ~/kidguard -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null
rm -rf ~/.cache/pip

# 3) Delete the old SQLite database that's not being used anymore (you're
#    on MySQL now):
rm -f ~/kidguard/tracking.db
rm -f ~/kidguard/server/tracking.db
rm -f ~/kidguard/instance/*.db 2>/dev/null

# 4) Delete old uploaded APKs / media you no longer need (CAUTION — these
#    may include camera photos captured from the child's device):
ls -lh ~/kidguard/uploads/
# Review carefully, then remove anything you don't need:
# rm ~/kidguard/uploads/apk/old_v1.apk
# rm ~/kidguard/uploads/*.jpg   # camera captures
# rm ~/kidguard/uploads/*.mp4   # audio captures
```

### Drop the legacy MySQL database if unused

If you kicked off the Render migration and no longer use PythonAnywhere:

```bash
# In a Bash console:
python3 -c "
import os
from zoneinfo import ZoneInfo
print('PA_MYSQL_DATABASE =', os.environ.get('PA_MYSQL_DATABASE'))
print('PA_MYSQL_USER     =', os.environ.get('PA_MYSQL_USER'))
"

mysql -u "\$PA_MYSQL_USER" -p"\$PA_MYSQL_PASSWORD" -h "\$PA_MYSQL_HOST" "\$PA_MYSQL_DATABASE" -e "
SHOW TABLES;
SELECT COUNT(*) AS users FROM users;
"

# To WIPE all KidGuard data (irreversible!) — only if you're done with PA:
# mysqldump -u "\$PA_MYSQL_USER" -p"\$PA_MYSQL_PASSWORD" -h "\$PA_MYSQL_HOST" "\$PA_MYSQL_DATABASE" > ~/kidguard-backup-\$(date +%F).sql
# mysql -u "\$PA_MYSQL_USER" -p"\$PA_MYSQL_PASSWORD" -h "\$PA_MYSQL_HOST" "\$PA_MYSQL_DATABASE" -e "SET FOREIGN_KEY_CHECKS=0; SHOW TABLES; -- review, then DROP each: --"
# Or simply delete the database from the **Databases** tab in the dashboard.
```

### Reduce CPU usage (most common limit)

The biggest CPU drain is the dashboard polling every 30 s. If you've already
resolved your immediate issue and just need to stay within the daily quota:

1. In `server/templates/dashboard.html`, find:
   ```js
   startPolling() // 30s interval
   ```
   Change the interval to 60 s or 120 s (one line edit).

2. In the child APK's `TrackerService` (`app/src/main/java/com/anonchat/app/parentalcontrol/service/TrackerService.kt`),
   find `REPORT_INTERVAL_MS = 30_000` and bump it to `60_000`. Rebuild + reinstall.

3. Disable the always-on polling on the OLD PythonAnywhere deploy: log out of
   the dashboard in your browser when you're not actively monitoring (the
   polling stops when the tab is closed or the JWT expires).

### Delete the web app entirely (full teardown)

If you've migrated to Render and want to fully delete PythonAnywhere:

1. Open the **Web** tab.
2. Find your web app entry → click the **Delete** button (trash icon at the
   top right of the web app card).
3. Confirm. This deletes the WSGI config and stops the worker (frees
   CPU-qattach).

Then delete the database:

4. Open the **Databases** tab.
5. Click your `kidguard` MySQL DB → **Delete**.

Then delete the project files:

6. Open a **Bash** console:
   ```bash
   # Move to the bundle folder first (git is huge)
   cd ~
   rm -rf ~/kidguard
   ```
7. (Optional) Delete the bash console itself: **Consoles** tab → click the X
   on each console.

Then (if you really want to leave PA):

8. Account → **Delete account** (irreversible — only do this if you're
   definitely done with PythonAnywhere).

---

## How to migrate to Render (full step-by-step guide)

This is the **cleanest** path now that PythonAnywhere is hitting limits.
Full instructions are in **`RENDER_STEPS.md`** in this repo, but the short
version is:

1. Log in to [render.com](https://render.com) with your GitHub account.
2. Dashboard → **New +** → **Blueprint** → select the `Kid_Guard` repo.
   Render auto-detects `render.yaml` and provisions:
   - 1× PostgreSQL DB (`kidguard-db`)
   - 1× Flask web service (`kidguard-server`) built from the new `Dockerfile`
     at the repo root, running the modern `server/` package (with the
     forgot-password fix).
3. Fill in `SECRET_KEY` and `JWT_SECRET_KEY` (Render prompts for them).
4. Pick a region → **Apply**.
5. Wait ~4 min for the build to complete. Your server goes live at:
   ```
   https://kidguard-server.onrender.com
   ```
6. On your local machine, edit `local.properties`:
   ```properties
   server.url=https://kidguard-server.onrender.com
   ```
7. Rebuild + reinstall the APK on the child's phone:
   ```bash
   ./gradlew assembleDebug
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

The End. The new URL is `https://kidguard-server.onrender.com` — update
any links, bookmarks, or hotspot kickback URLs accordingly.

---

## Recommendation

Given that you're hitting quota **and** the SQLite-on-NFS issue keeps
breaking writes on PythonAnywhere, **migrate to Render**. It's:

- The same codebase
- The same security fixes (forgot-password, JWT revocation, ownership checks)
- A proper PostgreSQL DB (no disk-I/O class of failures)
- Auto-deploy on every `git push` (no manual "git pull + reload" dance)
- A single URL change in your APK, once

Follow `RENDER_DEPLOY.md`. You'll be done in 15 minutes.
