# KidGuard — Deployment Guide (PythonAnywhere Free → Render Fallback)

> **Important:** PythonAnywhere's free tier **no longer includes MySQL**.
> The only database option is SQLite, which has known `disk I/O error` issues
> on their network filesystem. This means **write operations** (register,
> forgot-password, pairing, device reporting) **will fail** on PA free tier.
>
> **Recommendation:** Go directly to **Render** (Part B) for a working
> deployment with free PostgreSQL. Part A is included for reference if you
> want to try PA anyway.

---

## Part A — Deploy on PythonAnywhere (Free, SQLite Only)

### Known Limitations (Free Tier, No MySQL)

| Feature | Status | Reason |
|---|---|---|
| Login (read) | Works | SQLite reads succeed |
| Register (write) | **FAILS 500** | `sqlite3.OperationalError: disk I/O error` |
| Forgot-password (write) | **FAILS 500** | Same NFS write issue |
| Reset-password (write) | **FAILS 500** | Same NFS write issue |
| Pairing (write) | **FAILS 500** | Same NFS write issue |
| Device reporting (write) | **FAILS 500** | Same NFS write issue |
| Dashboard (read) | Works | Reads succeed |
| Commands (read) | Works | Reads succeed |

> If you need write operations to work, **skip to Part B (Render)**.

---

### Step 1 — Clone the Repository

1. PythonAnywhere dashboard → **Consoles** → start a **Bash** console.
2. Run:

```bash
git clone https://github.com/diptiban1987/Kid_Guard.git kidguard
cd kidguard
```

3. Create upload directories:

```bash
mkdir -p uploads/apk
echo '{"latest_version": 1, "changelog": "", "apk_filename": ""}' > uploads/apk/version.json
```

---

### Step 2 — Install Python Dependencies

```bash
cd ~/kidguard
pip3 install --user -r server/requirements.txt
```

---

### Step 3 — Configure WSGI

1. Dashboard → **Web** tab → click **"Add a new web app"**.
2. Select **Manual configuration** → **Python 3.10+**.
3. Set **Source code** and **Working directory** to:
   ```
   /home/yourusername/kidguard
   ```
4. Click the **WSGI configuration file** link to edit it.
5. Replace the **entire** contents with:

```python
import sys, os

project_home = '/home/yourusername/kidguard'
if project_home not in sys.path:
    sys.path.insert(0, project_home)

# Generate secrets: python3 -c "import secrets; print(secrets.token_hex(32))"
os.environ['SECRET_KEY']       = 'PASTE_32_CHAR_HEX_SECRET_HERE'
os.environ['JWT_SECRET_KEY']   = 'PASTE_ANOTHER_32_CHAR_HEX_SECRET_HERE'
os.environ['CLOUD_SERVER_URL'] = 'https://yourusername.pythonanywhere.com'

# SQLite is the only option on PA free tier (no MySQL available).
# WARNING: Writes will fail with "disk I/O error" on PA's NFS.
# For a working deployment, use Render (Part B) instead.
db_path = '/home/yourusername/kidguard_data/tracking.db'
os.makedirs(os.path.dirname(db_path), exist_ok=True)
os.environ['DATABASE_URL'] = f'sqlite:///{db_path}'

os.environ['FLASK_AUTO_CREATE'] = '1'

from server.wsgi import application  # noqa
```

6. Replace `yourusername` with your actual PythonAnywhere username.
7. Generate secrets in the Bash console:
   ```bash
   python3 -c "import secrets; print(secrets.token_hex(32))"
   ```
8. **Save** the file.

---

### Step 4 — Configure Static Files

In the **Web** tab → **Static Files** section, add:

| URL | Directory |
|---|---|
| `/static/` | `/home/yourusername/kidguard/server/static/` |

---

### Step 5 — Reload & Test

1. **Web** tab → click the green **Reload** button.
2. Visit: `https://yourusername.pythonanywhere.com`
3. You should see the login page.

**What works:**
- Login with correct credentials ✅
- Dashboard loads ✅
- Commands work ✅

**What fails:**
- Register → 500 ❌
- Forgot-password → 500 ❌
- Pairing → 500 ❌

> If write operations are required (they are for real usage),
> **proceed to Part B (Render)**.

---

### Step 6 — Update the Android APK

1. Edit `local.properties`:
   ```properties
   server.url=https://yourusername.pythonanywhere.com
   ```
2. Rebuild:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install on the child device:
   ```bash
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

---

### PythonAnywhere Free Tier — Limits

| Limit | Value |
|---|---|
| Storage | 512 MB |
| CPU | 1 core (shared) — 2,500 s/day |
| Concurrent requests | 1 (sequential) |
| Database | **SQLite only** (no MySQL on free tier) |
| WebSocket | Not available |
| HTTPS | Included (forced) |

---

## Part B — Deploy on Render (Free, Recommended)

Render's free tier includes **PostgreSQL** — all read/write operations work.
This is the recommended path.

### Step 1 — Push Latest Code to GitHub

```bash
git add .
git commit -m "Deploy to Render"
git push origin main
```

Verify at: `https://github.com/diptiban1987/Kid_Guard/commits/main`

---

### Step 2 — Create a Render Account

1. Go to [render.com](https://render.com).
2. Sign up with your GitHub account.

---

### Step 3 — Deploy via Blueprint (Easiest)

1. Render dashboard → **New +** → **Blueprint**.
2. Select your `Kid_Guard` repository.
3. Render reads `render.yaml` and shows two resources:
   - **kidguard-db** (PostgreSQL)
   - **kidguard-server** (Web Service)
4. Fill in the two secret env vars when prompted:
   - `SECRET_KEY` = generate a 64-char hex string
   - `JWT_SECRET_KEY` = generate a different 64-char hex string

   Generate in PowerShell:
   ```powershell
   python -c "import secrets; print(secrets.token_hex(32))"
   ```

5. Pick a **region** (e.g., Oregon).
6. Click **Apply**.

Render will:
- Provision the PostgreSQL database (~30s)
- Build the Docker image (~3min)
- Start the web service (~15s)
- Wire `DATABASE_URL` automatically

**Your server goes live at:**
```
https://kidguard-server.onrender.com
```

---

### Step 4 — Manual Deploy (If Not Using Blueprint)

1. **Create PostgreSQL DB:**
   - Dashboard → **New +** → **PostgreSQL**
   - Name: `kidguard-db`, Database: `kidguard`, User: `kidguard`
   - Region: Oregon, Plan: Free
   - Click **Create Database**
   - Copy the **Internal Database URL**

2. **Create Web Service:**
   - Dashboard → **New +** → **Web Service** → select `Kid_Guard` repo
   - Name: `kidguard-server`
   - Region: **same as DB** (Oregon)
   - Branch: `main`
   - Runtime: **Docker**
   - Dockerfile path: `/Dockerfile`
   - Plan: Free

3. **Add Environment Variables:**

   | Key | Value |
   |---|---|
   | `SECRET_KEY` | `<64-char hex string>` |
   | `JWT_SECRET_KEY` | `<different 64-char hex string>` |
   | `DATABASE_URL` | Internal Database URL from step 1 |
   | `CLOUD_SERVER_URL` | `https://kidguard-server.onrender.com` |
   | `FLASK_AUTO_CREATE` | `1` |

4. Click **Create Web Service**.

---

### Step 5 — Verify Deployment

1. Visit: `https://kidguard-server.onrender.com`
2. You should see the KidGuard login page. First request may take ~30s (cold start).
3. Test the full flow:

```bash
# Test forgot-password
curl -X POST https://kidguard-server.onrender.com/api/auth/forgot-password \
     -H "Content-Type: application/json" \
     -d '{"email":"nonexistent@example.com"}'
# Returns 200 with {"message":"If that email exists...","token":null}

# Test health check
curl https://kidguard-server.onrender.com/api/auth/me
# Returns 401 (correct — no token sent)
```

4. Verify tables in Render dashboard → `kidguard-db` → **Query** tab:
   ```sql
   SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';
   -- Should return ~23
   ```

---

### Step 6 — Update the Android APK

1. Edit `local.properties`:
   ```properties
   server.url=https://kidguard-server.onrender.com
   ```
2. Rebuild:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install on child device:
   ```bash
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

---

### Step 7 — (Optional) Add Persistent Disk (Starter $7/mo)

Free tier has no persistent disk — uploads are lost on redeploy.

1. Dashboard → `kidguard-server` → **Settings** → **Disks** → **Add Disk**.
2. Name: `uploads`, Mount Path: `/app/uploads`, Size: 1 GB.
3. Save.

---

### Step 8 — (Optional) Initialize APK Version File

On free tier (no disk), do this after every redeploy:

Dashboard → `kidguard-server` → **Shell** tab:

```bash
mkdir -p /app/uploads/apk
cat > /app/uploads/apk/version.json <<'EOF'
{"latest_version": 1, "changelog": "Initial release", "apk_filename": ""}
EOF
```

---

### Step 9 — (Optional) Configure SMTP

Add these env vars in Render dashboard → **Environment**:

| Key | Value |
|---|---|
| `MAIL_SERVER` | `smtp.gmail.com` |
| `MAIL_PORT` | `587` |
| `MAIL_USE_TLS` | `1` |
| `MAIL_USERNAME` | `<your gmail>` |
| `MAIL_PASSWORD` | `<app password>` |
| `MAIL_FROM` | `<your gmail>` |

---

### Updating Render Deploys

Every `git push` to `main` auto-redeploys:

```bash
git add .
git commit -m "..."
git push origin main
```

---

### Render Free Tier — Limits

| Limit | Value |
|---|---|
| Spins down after | 15 min idle |
| Cold start | ~30s |
| Persistent disk | No (Starter only) |
| PostgreSQL | Free (deleted after 90 days) |
| Build minutes | 750/month |
| Bandwidth | 100 GB/month |

---

## Troubleshooting

| Symptom | Platform | Fix |
|---|---|---|
| 500 on register/forgot-password with `disk I/O error` | PA | **Expected** — PA free has no MySQL. Use Render instead. |
| 404 on all pages | PA | WSGI import must be `from server.wsgi import application` |
| 502 Bad Gateway | Render | Check Dockerfile CMD uses `${PORT:-5000}` |
| Health check fails | Render | Change `healthCheckPath` to `/` in `render.yaml` |
| Uploads lost on redeploy | Render Free | Upgrade to Starter + add disk |
| First request takes 30s | Both Free | Accept cold start or upgrade |
| Tables not created | Render | Set `FLASK_AUTO_CREATE=1` env var |

---

## Quick Comparison

| Item | PythonAnywhere Free | Render Free |
|---|---|---|
| URL | `https://yourusername.pythonanywhere.com` | `https://kidguard-server.onrender.com` |
| Database | **SQLite (writes break)** | **PostgreSQL (works)** |
| Register | Fails 500 | Works |
| Forgot-password | Fails 500 | Works |
| Deploy method | `git pull` + Reload | `git push` (auto-deploy) |
| Cold start | ~1-2s | ~30s |
| WebSocket | No | Yes |
| **Verdict** | **Not recommended** | **Recommended** |

---

## TL;DR

**Skip PythonAnywhere. Go straight to Render:**

1. Push code to GitHub
2. render.com → New + → Blueprint → select repo
3. Set `SECRET_KEY` + `JWT_SECRET_KEY`
4. Click Apply
5. Done — `https://kidguard-server.onrender.com`
