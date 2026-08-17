# Deploy to PythonAnywhere (Free)

## Step 1: Create a PythonAnywhere Account

1. Go to https://www.pythonanywhere.com
2. Click **"Create a Beginner Account"** (free tier)
3. Verify your email and log in

---

## Step 2: Clone the GitHub Repository

In the PythonAnywhere dashboard:

1. Go to **Consoles** tab → Start a **Bash** console
2. Clone the repository:

```bash
git clone https://github.com/diptiban1987/Kid_Guard.git kidguard
cd kidguard
```

3. Create required upload directories:

```bash
mkdir -p uploads/apk
```

4. Create the APK metadata file:

```bash
echo '{"latest_version": 1, "changelog": "", "apk_filename": ""}' > uploads/apk/version.json
```

5. The project structure on PythonAnywhere will be:
```
/home/yourusername/kidguard/
├── server/                ← Flask package (factory + blueprints) — USE THIS
│   ├── __init__.py        ← create_app() application factory
│   ├── wsgi.py            ← WSGI entry point — point your web app at this
│   ├── blueprints/        ← auth.py, reports.py, parent.py, ...
│   ├── security.py        ← password hashing (scrypt), ownership checks
│   ├── models.py
│   └── config.py
├── app.py                 ← LEGACY monolith (deprecated — do NOT use)
└── uploads/
```

> ⚠️ The OLD `app.py` at the repo root is **deprecated**. New deployments must
> point their WSGI file at `server/wsgi.py:application` (Step 4 below) to pick
> up the security fixes (bcrypt password hashing, JWT revocation, device
> ownership enforcement) and the bug fixes for the forgot-password 500s
> described in the Troubleshooting section.

---

## Step 3: Set Up the Python Environment

1. Go to **Consoles** tab → **Bash** (or use the same console from Step 2)
2. Install dependencies:

```bash
cd ~/kidguard
pip3 install --user -r server/requirements.txt
```

This installs Flask, JWT, SQLAlchemy, **PyMySQL** (for the MySQL DB below),
flask-limiter, and all dependencies. **SocketIO is NOT installed** (the app
works without it).

---

## Step 4: Create a MySQL Database (IMPORTANT — fix for forgot-password 500s)

PythonAnywhere's free tier includes **one MySQL database**. Use it instead
of the default SQLite, because SQLite on PythonAnywhere's network filesystem
frequently raises `sqlite3.OperationalError: disk I/O error` on writes —
which is the exact error that breaks `/api/auth/forgot-password` and
`/api/auth/register` (any write fails; reads still succeed, which is why
login returns a normal 401 for a wrong password instead of a 500).

1. Go to the **Databases** tab
2. Under **MySQL**, click **Initialize MySQL** if you haven't already
3. Note the **password** that PythonAnywhere shows you (you only see it once)
4. Create a new database, e.g. `kidguard$server`
5. The connection details are exposed in your WSGI process as environment
   variables (e.g. `PA_MYSQL_DATABASE`, `PA_MYSQL_USER`, `PA_MYSQL_PASSWORD`,
   `PA_MYSQL_HOST`, `PA_MYSQL_PORT`). `server/wsgi.py` auto-detects these and
   builds a `mysql+pymysql://...` URL — no manual config needed.

Verify in a Bash console:

```bash
env | grep -i mysql
mysql -u $PA_MYSQL_USER -p$PA_MYSQL_PASSWORD -h $PA_MYSQL_HOST $PA_MYSQL_DATABASE
Welcome to the MySQL monitor. ...
```

---

## Step 5: Configure WSGI

1. Go to **Web** tab
2. Click **"Add a new web app"**
3. Select **"Manual configuration"** → **Python 3.10+**
4. In the **"Code"** section:
   - **Source code**: `/home/yourusername/kidguard`
   - **Working directory**: `/home/yourusername/kidguard`
5. In the **"WSGI configuration file"** section, click the file link to edit it
6. Replace the contents with:

```python
import sys, os

# The Flask package lives in server/ — the parent (kidguard/) must be on the
# path so `import server` resolves.
project_home = '/home/yourusername/kidguard'
if project_home not in sys.path:
    sys.path.insert(0, project_home)

# ----- Replace these with strong random strings -----
os.environ['SECRET_KEY']           = 'change-me-32-char-secret'
os.environ['JWT_SECRET_KEY']        = 'change-me-too-32-char-secret'
os.environ['CLOUD_SERVER_URL']      = 'https://yourusername.pythonanywhere.com'

# ----- MySQL conn (recommended) — leave commented to auto-pick PA MySQL env vars -----
# os.environ['DATABASE_URL']         = 'mysql+pymysql://USER:PASS@HOST:PORT/DBNAME?charset=utf8mb4'

# Auto-create missing tables (no need to run alembic by hand on the free tier)
os.environ['FLASK_AUTO_CREATE']    = '1'

from server.wsgi import application  # noqa
```

7. Replace `yourusername` with your actual PythonAnywhere username
8. Replace the secret keys with random strings (use `python3 -c "import secrets; print(secrets.token_hex(32))"`)
9. **Save** the file

---

## Step 6: Configure Static Files

In the **Web** tab → **Static Files** section:

| URL | Directory |
|---|---|
| `/static/` | `/home/yourusername/kidguard/server/static/` |

---

## Step 7: Initialize the Database

Tables are created **automatically** on first request (because
`FLASK_AUTO_CREATE=1` is set in the WSGI file). The MySQL database you created
in Step 4 will receive all 18 tables on the first successful request — no
manual SQL needed.

If you prefer to run the Alembic migrations by hand instead:

```bash
cd ~/kidguard
export DATABASE_URL="mysql+pymysql://$PA_MYSQL_USER:$PA_MYSQL_PASSWORD@$PA_MYSQL_HOST:$PA_MYSQL_PORT/$PA_MYSQL_DATABASE?charset=utf8mb4"
pip3 install --user alembic
cd server && alembic upgrade head
```

---

## Step 8: Reload & Test

1. Go to **Web** tab
2. Click the green **"Reload"** button
3. Visit: `https://yourusername.pythonanywhere.com`
4. You should see the **KidGuard** login page
5. Test the **Forgot Password** flow: enter an email, press *Request Reset
   Token*. The 8-character token will be displayed in the yellow box (no SMTP
   is configured in dev — the server only logs it and returns it to the page).
   Paste the token + new password into the reset form.

---

## Step 9: Update the APK

Build the APK with your PythonAnywhere URL:

1. On your local machine, edit `local.properties`:
```properties
server.url=https://yourusername.pythonanywhere.com
```

2. Rebuild the APK:
```bash
./gradlew assembleDebug
```

3. Install on the child's phone:
```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

---

## Step 10: Push an APK Update (OTA)

Upload the APK to PythonAnywhere's server so the app can auto-update:

1. Go to **Files** → `kidguard/uploads/apk/`
2. Upload your `app-debug.apk`
3. Rename it (e.g., `kidguard_v2.apk`)
4. Update `uploads/apk/version.json`:
```json
{"latest_version": 2, "changelog": "Bug fixes and improvements", "apk_filename": "kidguard_v2.apk"}
```

The child's app will auto-detect the update within 1 hour and install silently.

---

## What Works vs What Doesn't

| Feature | PythonAnywhere Free | Notes |
|---|---|---|
| Phone reporting (30s) | ✅ Works | Phone pushes every 30s |
| Dashboard (polling) | ✅ Works | Dashboard refreshes every 30s |
| Commands (lock/alarm) | ✅ Works | Piggybacked on report response |
| Geofencing | ✅ Works | Checked on each report |
| Media uploads | ✅ Works | Stored in uploads/ |
| OTA updates | ✅ Works | Version check on each report |
| Forgot / reset password | ✅ Works | Dev-mode: token shown in page (no SMTP) |
| **Real-time WebSocket** | ❌ **Not available** | Replaced by 30s polling |
| PostgreSQL | ❌ **Not available** | Using MySQL (free tier) |

---

## Limitations of Free Tier

| Limit | Value |
|---|---|
| Storage | 512 MB |
| Bandwidth | Unlimited (but throttled) |
| CPU | 1 core (shared) |
| Concurrent requests | 1 (processed sequentially) |
| Background tasks | Not supported |
| HTTPS | Included (forced) |
| Database | MySQL (free tier) — **NOT SQLite** | SQLite causes disk-I/O errors (see Troubleshooting) |

For a single family (1-2 children), these limits are perfectly fine.

---

## Updating the Server Later

```bash
cd ~/kidguard
git pull
```

Then go to **Web** tab → click **Reload**. Changes take effect immediately.

---

## Troubleshooting

**`POST /api/auth/forgot-password` returns 500 with `"sqlite3.OperationalError) disk I/O error"`**

Root cause: SQLite on PythonAnywhere's network filesystem (NFS) does not
support the file locking SQLite needs for writes. Reads succeed (so login
returns a normal 401 for a wrong password), but every write 500s — which
breaks register, forgot-password (for a registered email), reset-password,
pairing, device reporting, etc.

Fix (one-time, recommended):
1. Create the free MySQL DB as described in **Step 4** above.
2. Edit your WSGI configuration file and ensure the new entry point is in
   place (`from server.wsgi import application` — see Step 5). The
   `server/wsgi.py` helper auto-detects the PythonAnywhere MySQL env vars
   and switches to MySQL without any manual `DATABASE_URL` setting.
3. In a Bash console: `pip3 install --user pymysql cryptography`
   (also added to `server/requirements.txt`).
4. Go to **Web** tab → **Reload**.

Verify the switch took effect (in a Bash console):
```bash
mysql -u $PA_MYSQL_USER -p$PA_MYSQL_PASSWORD -h $PA_MYSQL_HOST \
  -e "USE $PA_MYSQL_DATABASE; SHOW TABLES;"
```
You should see all 18 `kidguard` tables after the first request to the
reloaded web app.

---

**`POST /api/auth/forgot-password` returns 500 with `no such table: password_reset_tokens`**

Different root cause from the disk-I/O error above: your DB exists but is
missing the `password_reset_tokens` table (this happens when the baseline
Alembic migration was stamped against a schema that pre-dated the
`PasswordResetToken` model).

Fix: either re-deploy with the new `server/wsgi.py` entry point (which sets
`FLASK_AUTO_CREATE=1` and runs `db.create_all()` on boot, creating any
missing tables), or run the migration by hand:

```bash
cd ~/kidguard
export DATABASE_URL="mysql+pymysql://$PA_MYSQL_USER:$PA_MYSQL_PASSWORD@$PA_MYSQL_HOST:$PA_MYSQL_PORT/$PA_MYSQL_DATABASE?charset=utf8mb4"
cd server && alembic upgrade head
```

---

**My existing WSGI uses `from app import app as application` — how do I switch?**

Edit your WSGI configuration file in the **Web** tab and replace the import
line at the bottom with:

```python
from server.wsgi import application  # noqa
```

`(server.wsgi storage_helper)` takes care of `sys.path` setup,
`FLASK_AUTO_CREATE=1`, the PythonAnywhere MySQL env-var auto-pickup, and the
factory call. You no longer need the manually-set `SECRET_KEY` / `JWT_SECRET_KEY` /
`CLOUD_SERVER_URL` lines from the legacy WSGI — but keeping them set in the
WSGI file takes precedence (recommended, so you can rotate keys without
editing the repo).

---

**Error: "Internal Server Error" (other tracebacks)**
- Go to **Web** tab → **Error log** to see the traceback
- Most common: missing file upload, wrong path

**Error: "Module not found"**
- Go to **Consoles** → **Bash** → `pip3 install --user <module>`
- After installing **PyMySQL** + **cryptography** for MySQL support, the
  forgot-password 500 disappears automatically.

**Error: "Database is locked"**
- Was a SQLite limitation (now fixed by switching to MySQL).

**Phone can't connect**
- Verify the URL: `https://yourusername.pythonanywhere.com`
- Check that the APK was built with the correct `server.url`
- Make sure the phone has internet access
