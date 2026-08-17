# Deploy KidGuard to Render — Step-by-Step (Modern `server/` Package)

This guide replaces the older `cloud-server/`-based Render guide and uses the
**modern Flask package** (`server/` — the same one with the forgot-password
fix, JWT revocation, multi-tenant ownership enforcement, etc.). Use this guide
if you want the latest fixes deployed to Render.

> If you've hit PythonAnywhere's free-quota limit, this is the recommended
> migration path. See the **"Free up PythonAnywhere quota"** section of
> `DEPLOY_STEPS.md` for how to clean up there before you migrate.

---

## Why Render instead of PythonAnywhere?

| Issue | PythonAnywhere Free | Render Free |
|---|---|---|
| DB writes (`INSERT`/`UPDATE`) | ❌ `sqlite3.OperationalError: disk I/O error` on NFS — breaks forgot-password, register, etc. | ✅ Managed PostgreSQL, no disk-I/O class of failures |
| MySQL on free tier | ✅ Free 512 MB | ❌ No MySQL (Render offers PostgreSQL instead) |
| Always-on | ❌ Spins down after idle | ❌ Spins down after 15 min idle (Starter: always-on) |
| Persistent disk | ✅ With paid plan | ❌ Free tier only; Starter ($7/mo) for disk |
| Build from GitHub | ❌ Manual `git pull` on console | ✅ Auto-deploy on every `git push` |
| Cold-start | ~1–2 s | ~30 s after idle (Starter: no cold start) |

Forgets PostgreSQL is the **recommended** production DB for this project, so
Render is a clean fit — you also get push-to-deploy for free.

---

## Prerequisites

- A GitHub account with the KidGuard repo: `https://github.com/diptiban1987/Kid_Guard`
- A free [Render account](https://render.com) (sign up with your GitHub login)
- (Optional) The Render CLI if you prefer terminal over web UI

---

## Step 1 — Verify the latest code is on GitHub

The latest commit `609096c` (or newer) was pushed already. Confirm by visiting:

```
https://github.com/diptiban1987/Kid_Guard/commits/main
```

You should see the commit:
> "Merge origin/main + fix forgot-password 500 + add stepwise deploy guide"

If you don't, on your local machine:
```bash
git push origin main
```

---

## Step 2 — Create a PostgreSQL DB on Render (one-click via Blueprint)

**Easiest path — use the included `render.yaml` Blueprint:**

1. Go to the [Render Dashboard](https://dashboard.render.com).
2. Click **New +** in the top right → **Blueprint**.
3. Select your `Kid_Guard` repository. Render reads `render.yaml` and shows you
   the resources it's about to create:
   - 1× PostgreSQL service (`kidguard-db`)
   - 1× Web service (`kidguard-server`)
4. Fill in the two secret env vars in the form (they're marked `sync: false`
   in `render.yaml` so Render prompts for them):
   - `SECRET_KEY` = paste a 32-byte hex string
   - `JWT_SECRET_KEY` = paste a different 32-byte hex string
   - Generate them in PowerShell with:
     ```powershell
     python3 -c "import secrets; print(secrets.token_hex(32))"
     ```
   - Or online at https://generate-random.org/api/passwords?length=64
5. Pick a **region** (e.g. Oregon for US, Frankfurt for EU).
6. Click **Apply**. Render will:
   - Provision the database (~30 s)
   - Build the Docker image (~3 min)
   - Start the web service (~15 s)
   - Wire `DATABASE_URL` automatically from the DB's internal connection string
7. Once both are green, your server is live at:
   ```
   https://kidguard-server.onrender.com
   ```

**Manual path (if you prefer not to use Blueprint):**

1. Dashboard → **New +** → **PostgreSQL**
   - Name: `kidguard-db`
   - Database: `kidguard`
   - User: `kidguard`
   - Region: e.g. Oregon
   - Plan: Free
   - Click **Create Database**
2. Copy the **Internal Database URL** (`postgresql://...`). You'll need it in
   Step 3.
3. Dashboard → **New +** → **Web Service** → pick `Kid_Guard` repo
   - Name: `kidguard-server`
   - Region: SAME as the database
   - Branch: `main`
   - Runtime: **Docker**
   - Dockerfile path: `/Dockerfile` (leave at the repo root)
   - Plan: Free
4. Add environment variables:

   | Key | Value |
   |---|---|
   | `SECRET_KEY` | `<64-char hex string>` |
   | `JWT_SECRET_KEY` | `<different 64-char hex string>` |
   | `DATABASE_URL` | The Internal Database URL from sub-step 2 |
   | `CLOUD_SERVER_URL` | `https://kidguard-server.onrender.com` |
   | `FLASK_AUTO_CREATE` | `1` |

5. Click **Create Web Service**.

---

## Step 3 — Add a Persistent Disk for Uploads (Starter tier only)

> Skip this step if you're on the free tier — disks aren't supported there.
> Uploads (camera photos, mic audio, APK OTA bundles) will be EJECTED on
> every redeploy. For testing/eval that's fine; for real use upgrade to
> Starter ($7/mo) and add the disk.

1. Dashboard → your `kidguard-server` service → **Settings**.
2. Scroll to **Disks** → click **Add Disk**.
   - Name: `uploads`
   - Mount Path: `/app/uploads`
   - Size: 1 GB (smallest available; bump up if you plan to upload many APKs)
3. Click **Save**.

The Dockerfile already does `RUN mkdir -p /app/uploads`, and the mount path
matches the default `UPLOAD_FOLDER` resolution in `server/config.py` (which
computes `/app/server/../uploads` = `/app/uploads`).

---

## Step 4 — Initialize the APK version file (for OTA updates)

On the free tier (no disk) you'll need to recreate this after every redeploy.
On Starter with a disk, do it once.

1. Dashboard → your `kidguard-server` service → **Shell** tab.
2. Run:
   ```bash
   mkdir -p /app/uploads/apk
   cat > /app/uploads/apk/version.json <<'EOF'
   {"latest_version": 1, "changelog": "Initial release", "apk_filename": ""}
   EOF
   cat /app/uploads/apk/version.json  # verify
   ```

---

## Step 5 — Verify the deployment

1. Visit the service URL in your browser:
   ```
   https://kidguard-server.onrender.com
   ```
   You should see the KidGuard login page. The first request after idle may
   take ~30 s (free-tier spin-up).

2. Test the forgot-password flow (the original problem):
   - Click **Forgot Password?** → enter an email → **Request Reset Token**
   - An 8-character token appears in the yellow box
   - Paste token + new password → reset completes → redirected to dashboard

3. (Optional) Health check from the terminal:
   ```bash
   curl https://kidguard-server.onrender.com/api/auth/me
   # 401 Unauthorized (correct — no token sent)
   ```

4. (Optional) Verify tables were created in the Render Postgres:
   - In Render dashboard → `kidguard-db` → **Query** tab
   - Run: `SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';`
   - Should return 23 (or thereabouts)
   - `SELECT * FROM password_reset_tokens LIMIT 1;` → empty result is fine;
     the TABLE EXISTING is what matters (fixes the 500)

---

## Step 6 — (Optional) Configure SMTP for password reset emails

By default (no `MAIL_SERVER` configured), `forgot-password` returns the
8-char token in the response body — it's shown on the login page. This is the
DEV mode. To switch to PROD mode (email the token, never echo it):

In Render dashboard → your `kidguard-server` service → **Environment**:
add the following keys, then **Save** (auto-redeploys):

| Key | Value |
|---|---|
| `MAIL_SERVER` | `smtp.gmail.com` |
| `MAIL_PORT` | `587` |
| `MAIL_USE_TLS` | `1` |
| `MAIL_USERNAME` | `<your-sender-gmail-address>` |
| `MAIL_PASSWORD` | `<16-char Gmail App Password>` (https://myaccount.google.com/apppasswords) |
| `MAIL_FROM` | `<your-sender-gmail-address>` |

Once these are set, `forgot-password` no longer returns the token in the
response body — it only sends it via email. The login page UI hides the
yellow token box and shows "If that email exists, instructions were sent."

---

## Step 7 — Update the APK to point at Render

The Android app embeds the server URL at build time:

1. On your local machine, edit `local.properties`:
   ```properties
   server.url=https://kidguard-server.onrender.com
   ```
   (Already gitignored, stays local.)

2. Rebuild the APK:
   ```bash
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

3. Install on the child device:
   ```bash
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

4. Test the connection: open the app on the device, log in, it should report
   location + receive commands from the parent dashboard at:
   ```
   https://kidguard-server.onrender.com/dashboard
   ```

> If you're on the free tier, the first request from the device after
> 15 min of idle will take ~30 s. Subsequent requests during the next
> 15 min are instant. For always-on responsiveness, upgrade to Starter.

---

## Step 8 — Push an APK Update for OTA (optional)

If you want the dashboard to push APK updates over the air:

```bash
# Get an admin token first:
ADMIN_TOKEN=$(curl -s -X POST https://kidguard-server.onrender.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<admin-email>","password":"<admin-password>"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['token'])")

# Then upload:
curl -X POST https://kidguard-server.onrender.com/api/app/upload \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "apk=@app/build/outputs/apk/debug/app-debug.apk" \
  -F "version_code=2" \
  -F "changelog=Render-server release"
```

The child app auto-detects and installs the update within an hour.

> Note: on the free tier the uploaded APK is lost on every redeploy. Either
> keep it on a persistent disk (Starter tier) or re-upload after each deploy.

---

## Environment Variables — Full Reference

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `SECRET_KEY` | Yes | (none) | Flask session/cookie signing |
| `JWT_SECRET_KEY` | Yes | (none) | JWT token signing |
| `DATABASE_URL` | Yes (auto-set by Blueprint) | SQLite fallback | SQLAlchemy connection string — Render injects the Postgres internal URL |
| `CLOUD_SERVER_URL` | Yes | `http://localhost:5000` | Public URL (used in API responses) |
| `FLASK_AUTO_CREATE` | No | (off) | When `1`, creates missing DB tables on boot — skips the alembic dance |
| `PORT` | Auto-set by Render | `5000` (CMD fallback) | Container bind port — both the Dockerfile + Render cooperate here |
| `MAIL_SERVER` | No | (empty = dev) | If set, forgot-password emails the token instead of echoing it |
| `MAIL_USERNAME`/`PASSWORD` | No | — | SMTP credentials (see Step 6) |
| `MAIL_FROM` | No | `noreply@kidguard.local` | Sender address (must match `MAIL_USERNAME` for Gmail) |
| `DISABLE_SOCKETIO` | No | Auto-detected | Force-disable WebSocket (Render free doesn't expose WS ports natively but gunicorn+eventlet handles it) |

---

## Render Free Tier — Gotchas

| Gotcha | Workaround |
|---|---|
| Spins down after 15 min idle → first request takes 30 s | Upgrade to Starter ($7/mo) for always-on |
| No persistent disk on free → uploads lost on redeploy | Upgrade to Starter + add disk at `/app/uploads` |
| Free PostgreSQL DB is deleted after 90 days | Move to Starter before 90 days OR backup + recreate |
| 750 build minutes/month (plenty for this project) | — |
| 100 GB outbound bandwidth | Plenty for a family deployment |
| Cold start after spin-down | Accept it (free tier) OR upgrade |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Build fails: `psycopg2` not installed | Wrong Dockerfile used | Confirm Dockerfile path is `/Dockerfile` at repo root (NOT the old `cloud-server/Dockerfile`) |
| Build fails: `from server import create_app` import error | Old `server/Dockerfile` was used | Use the new root `Dockerfile` (which `COPY server/ ./server/`) |
| 502 Bad Gateway | Container not listening on Render's PORT | We use `${PORT:-5000}` in CMD — Render auto-injects PORT=10000, the container listens on 10000. Make sure you didn't override PORT to 5000 in Render UI. |
| 500 on forgot-password | DB issue | Check **Logs** tab — should NOT happen on Render (Postgres, not SQLite) |
| Health check fails (a `401` from `/api/auth/me` is treated as failing by Render) | Render persists on 2xx only | Either change `healthCheckPath` to `/` (returns 200 login page) or change `healthCheckPath` to a `200` endpoint. |
| "I can't see uploaded APK / OTA media" | Free tier, no disk | Upgrade to Starter + add disk at `/app/uploads` |
| First request after idle takes 30 s | Free tier spin-down | Upgrade to Starter |

---

## Quick Deploy Checklist (TL;DR)

- [ ] Latest code pushed to GitHub (commit `609096c` or newer)
- [ ] Render dashboard → **New + Blueprint** → select `Kid_Guard` repo
- [ ] Set `SECRET_KEY` + `JWT_SECRET_KEY` (64-char hex strings)
- [ ] Pick region → **Apply**
- [ ] Wait for `kidguard-db` (green) + `kidguard-server` (green)
- [ ] Visit `https://kidguard-server.onrender.com` → login page loads
- [ ] Test forgot-password flow end-to-end
- [ ] (Optional) Add persistent disk on `/app/uploads` (Starter tier)
- [ ] Update `local.properties` with Render URL + rebuild APK
- [ ] Install new APK on child device

---

## Updating Render deploys later

Any `git push` to `main` triggers an automatic rebuild + redeploy:

```bash
git add ...
git commit -m "..."
git push origin main
```

Watch the build status in Render dashboard → **Events** + **Logs** tabs. Once
green, the new version is live — no manual reload (unlike PythonAnywhere).
