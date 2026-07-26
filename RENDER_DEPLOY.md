# Deploy KidGuard Cloud Server to Render

This guide covers every step to deploy the Flask cloud server (`cloud-server/`) to [Render](https://render.com) using a Docker image, a managed PostgreSQL database, and a persistent disk for uploaded files.

---

## Prerequisites

| Item | Details |
|---|---|
| GitHub repo | The project pushed to a GitHub repository (e.g. `https://github.com/diptiban1987/Kid_Guard.git`) |
| Render account | Free or paid account at [render.com](https://render.com) |
| Local clone | A local checkout of the repo so you can build the production APK afterwards |

---

## Step 1 — Push the Code to GitHub

Render builds from a GitHub branch, so the latest code must be on GitHub.

```bash
git add .
git commit -m "Prepare for Render deployment"
git push origin main
```

Make sure `cloud-server/` is **not** git-ignored. Confirm:

```bash
git ls-files cloud-server/
```

You should see `app.py`, `config.py`, `Dockerfile`, `requirements.txt`, etc.

---

## Step 2 — Create a PostgreSQL Database on Render

The app supports PostgreSQL via the `DATABASE_URL` environment variable.

1. Log in to the [Render Dashboard](https://dashboard.render.com).
2. Click **New +** → **PostgreSQL**.
3. Fill in:
   | Field | Value |
   |---|---|
   | Name | `kidguard-db` |
   | Database | `kidguard` |
   | User | `kidguard` |
   | Region | Choose the closest to your users |
   | PostgreSQL Version | 15 (default) |
   | Instance Type | Free (or Starter for production) |
4. Click **Create Database**.
5. Once created, note the **Internal Database URL** — it looks like:
   ```
   postgresql://kidguard:PASSWORD@dpg-xxxxx-a.kidguard-db.internal:5432/kidguard
   ```
   You will use this in Step 4.

> The app calls `init_db()` on startup (`app.py:1521`), so all 18 tables are created automatically — no manual migration needed.

---

## Step 3 — Add `gunicorn` and `eventlet` to `requirements.txt`

The Dockerfile runs the app with `gunicorn` + `eventlet`, but these two packages are **missing** from `requirements.txt`. Add them:

```diff
  Pillow==10.2.0
  qrcode==7.4.2
+ gunicorn==21.2.0
+ eventlet==0.35.1
  # flask-socketio is optional — install only if WebSocket is needed
  # flask-socketio==5.6.1
```

Commit and push:

```bash
git add cloud-server/requirements.txt
git commit -m "Add gunicorn + eventlet for Render deployment"
git push origin main
```

---

## Step 4 — Create a Web Service on Render

1. In the Render Dashboard click **New +** → **Web Service**.
2. Choose **"Build and deploy from a Git repository"**.
3. Connect your GitHub account if not already connected, then select the `Kid_Guard` repository.
4. Fill in the service settings:

   | Field | Value |
   |---|---|
   | Name | `kidguard-server` |
   | Region | Same region as the database |
   | Branch | `main` (or your default branch) |
   | Root Directory | `cloud-server` |
   | Runtime | **Docker** |
   | Instance Type | Free (or Starter for production) |

   > **Important:** Set **Root Directory** to `cloud-server` so Render builds from the `Dockerfile` inside that folder.

5. Scroll down to **Environment Variables** and add:

   | Key | Value |
   |---|---|
   | `SECRET_KEY` | A random 64+ char string (e.g. run `python -c "import secrets;print(secrets.token_hex(32))"`) |
   | `JWT_SECRET_KEY` | A different random 64+ char string |
   | `DATABASE_URL` | The **Internal Database URL** from Step 2 |
   | `CLOUD_SERVER_URL` | `https://kidguard-server.onrender.com` (your service URL — see below) |
   | `PYTHON_VERSION` | `3.11` (optional, the Dockerfile already pins 3.11) |

   > The `CLOUD_SERVER_URL` should match the public URL Render assigns. You can set a placeholder now and update it after the first deploy once you know the URL.

6. Click **Create Web Service**.

Render will now:
- Pull the repo
- Build the Docker image (installs `requirements.txt`, copies code)
- Start `gunicorn --worker-class eventlet --workers 1 --bind 0.0.0.0:5000 app:app`

> **Port note:** Render's Docker services expect the container to listen on the port specified by the `PORT` env var (default `10000` on Render). The Dockerfile binds to `5000`. To fix this, either:
> - **Option A (recommended):** Add a `PORT` env var set to `5000` in Render's environment tab, OR
> - **Option B:** Edit the Dockerfile CMD to use the `PORT` env var:
>   ```dockerfile
>   CMD gunicorn --worker-class eventlet --workers 1 --bind 0.0.0.0:${PORT:-5000} app:app
>   ```

---

## Step 5 — Add a Persistent Disk for Uploads

The app stores APK files and media uploads in `uploads/`. On Render's free tier, the filesystem is ephemeral — files are lost on every deploy/restart. To persist them:

1. Go to your web service → **Settings**.
2. Scroll to **Disks** → click **Add Disk**.
3. Fill in:
   | Field | Value |
   |---|---|
   | Name | `uploads` |
   | Mount Path | `/app/uploads` |
   | Size | 1 GB (or more as needed) |
4. Click **Save**.

> The `Dockerfile` already does `RUN mkdir -p uploads`, and the mount path `/app/uploads` matches `config.py:16` (`UPLOAD_FOLDER`).

---

## Step 6 — Initialize the APK Version File

The OTA update system reads `uploads/apk/version.json`. The persistent disk starts empty, so create the file after the first deploy:

**Option A — via Render Shell:**

1. In the Render Dashboard, go to your web service → **Shell** tab.
2. Run:
   ```bash
   mkdir -p /app/uploads/apk
   echo '{"latest_version": 1, "changelog": "", "apk_filename": ""}' > /app/uploads/apk/version.json
   ```

**Option B — via the API (after Step 7):**

```bash
curl -X POST https://kidguard-server.onrender.com/api/app/upload \
  -H "Authorization: Bearer <admin_token>" \
  -F "apk=@app-debug.apk" \
  -F "version_code=2" \
  -F "changelog=Initial release"
```

---

## Step 7 — Verify the Deployment

1. Wait for the build and deploy to finish (check the **Logs** tab for `Booting worker`).
2. Visit your service URL:
   ```
   https://kidguard-server.onrender.com
   ```
3. You should see the **KidGuard** login page.
4. Register a parent account at `/api/auth/register` or via the web UI.
5. Generate a pairing code from the dashboard.

### Health check (optional):

```bash
curl https://kidguard-server.onrender.com/api/auth/me
```

Should return a `401` (unauthorized) — this confirms the server is running.

---

## Step 8 — Update `CLOUD_SERVER_URL` (if placeholder was used)

If you set a placeholder `CLOUD_SERVER_URL` in Step 4, update it now with the real URL:

1. Go to your web service → **Environment** tab.
2. Edit `CLOUD_SERVER_URL` to `https://kidguard-server.onrender.com`.
3. Save — Render will auto-redeploy.

---

## Step 9 — Build the Production APK

The Android app embeds the server URL at build time from `local.properties`.

1. Edit `local.properties` in the project root:
   ```properties
   server.url=https://kidguard-server.onrender.com
   ```
   > `local.properties` is git-ignored, so this stays local.

2. Build the APK:
   ```bash
   ./gradlew assembleDebug
   ```

3. APK generated at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

4. Install on the child's device:
   ```bash
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Step 10 — Upload the APK for OTA Updates

To enable over-the-air updates from the dashboard:

```bash
curl -X POST https://kidguard-server.onrender.com/api/app/upload \
  -H "Authorization: Bearer <admin_token>" \
  -F "apk=@app/build/outputs/apk/debug/app-debug.apk" \
  -F "version_code=2" \
  -F "changelog=Production release for Render"
```

The child app checks for updates every hour via `/api/app/check-update`.

---

## Step 11 — (Optional) Set Up a Custom Domain

1. Go to your web service → **Settings** → **Custom Domains**.
2. Add your domain (e.g. `kidguard.yourdomain.com`).
3. Render provides a CNAME target — add it to your DNS provider.
4. Once DNS propagates, Render provisions an SSL certificate automatically.
5. Update `CLOUD_SERVER_URL` env var to `https://kidguard.yourdomain.com`.
6. Rebuild the APK with the new URL in `local.properties`.

---

## Environment Variables Summary

| Variable | Required | Example | Purpose |
|---|---|---|---|
| `SECRET_KEY` | Yes | `a1b2c3...` | Flask session signing |
| `JWT_SECRET_KEY` | Yes | `x7y8z9...` | JWT token signing |
| `DATABASE_URL` | Yes | `postgresql://...` | PostgreSQL connection string |
| `CLOUD_SERVER_URL` | Yes | `https://kidguard-server.onrender.com` | Public URL (for APK, OTA links) |
| `PORT` | Optional | `5000` | Override if Dockerfile binds to 5000 |

---

## Render Free Tier Limitations

| Limit | Free Tier | Impact |
|---|---|---|
| Web service | Spins down after 15 min of inactivity | First request after idle takes ~30s to wake up |
| Build minutes | 750 hrs/month | Plenty for this project |
| PostgreSQL | 90 days then deleted | Move to Starter for long-term use |
| Persistent disk | Not available on free tier | Uploads lost on restart (use Starter for disk) |
| Bandwidth | 100 GB/month | Fine for a family deployment |

> For production use, upgrade the web service to **Starter ($7/mo)** to get:
> - Always-on (no spin-down)
> - Persistent disk support
> - Custom domains

---

## Troubleshooting

### Build fails: "Module not found"
- Ensure `gunicorn` and `eventlet` are in `requirements.txt` (Step 3).
- Check the **Logs** tab for the exact import error.

### App crashes on startup: "Could not connect to database"
- Verify `DATABASE_URL` uses the **Internal Database URL** (not external).
- Ensure the database and web service are in the **same region**.
- Check the database is active in the Render dashboard.

### "502 Bad Gateway" on visit
- The container is likely not listening on the port Render expects.
- Fix: Set `PORT=5000` env var or update the Dockerfile CMD (see Step 4).

### Uploads disappear after redeploy
- You are on the free tier without a persistent disk.
- Upgrade to Starter and add a disk at `/app/uploads` (Step 5).

### Phone can't connect
- Verify the APK was built with `server.url=https://kidguard-server.onrender.com`.
- Check `CLOUD_SERVER_URL` env var matches the Render URL.
- Ensure the child device has internet access.
- If on free tier, the first request may be slow (cold start) — wait 30s and retry.

### WebSocket not working
- The current `requirements.txt` does **not** include `flask-socketio`.
- The app falls back to 30-second polling automatically (see `app.py:14-19`).
- To enable WebSocket, uncomment `flask-socketio==5.6.1` in `requirements.txt`, rebuild, and ensure `eventlet` is installed (gunicorn worker-class handles it).

---

## Updating the Server Later

Any push to the `main` branch triggers an automatic rebuild and redeploy on Render:

```bash
git add .
git commit -m "Update server"
git push origin main
```

Monitor progress in the Render Dashboard → **Events** and **Logs** tabs.

---

## Quick Deployment Checklist

- [ ] Code pushed to GitHub
- [ ] `gunicorn` + `eventlet` added to `requirements.txt`
- [ ] PostgreSQL database created on Render
- [ ] Web service created (Docker runtime, root = `cloud-server`)
- [ ] Environment variables set (`SECRET_KEY`, `JWT_SECRET_KEY`, `DATABASE_URL`, `CLOUD_SERVER_URL`)
- [ ] Port configured (`PORT=5000` or Dockerfile updated)
- [ ] Persistent disk mounted at `/app/uploads` (Starter tier)
- [ ] `uploads/apk/version.json` initialized
- [ ] Server reachable at `https://kidguard-server.onrender.com`
- [ ] `local.properties` updated with Render URL
- [ ] APK rebuilt and installed on child device
- [ ] APK uploaded via `/api/app/upload` for OTA updates
