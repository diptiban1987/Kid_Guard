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
├── app.py
├── config.py
├── models.py
├── wsgi.py
├── requirements.txt
├── templates/
├── static/
└── uploads/
```

---

## Step 3: Set Up the Python Environment

1. Go to **Consoles** tab → **Bash** (or use the same console from Step 2)
2. Install dependencies:

```bash
cd ~/kidguard
pip3 install --user -r requirements.txt
```

This installs Flask, JWT, SQLAlchemy, and all dependencies. **SocketIO is NOT installed** (the app works without it).

---

## Step 4: Configure WSGI

1. Go to **Web** tab
2. Click **"Add a new web app"**
3. Select **"Manual configuration"** → **Python 3.10+**
4. In the **"Code"** section:
   - **Source code**: `/home/yourusername/kidguard`
   - **Working directory**: `/home/yourusername/kidguard`
5. In the **"WSGI configuration file"** section, click the file link to edit it
6. Replace the contents with:

```python
import sys
import os

path = '/home/yourusername/kidguard'
if path not in sys.path:
    sys.path.append(path)

os.environ['SECRET_KEY'] = 'your-random-secret-key-here'
os.environ['JWT_SECRET_KEY'] = 'your-other-random-secret-here'
os.environ['CLOUD_SERVER_URL'] = 'https://yourusername.pythonanywhere.com'
from app import app as application
```

7. Replace `yourusername` with your actual PythonAnywhere username
8. Replace the secret keys with random strings
9. **Save** the file

---

## Step 5: Configure Static Files

In the **Web** tab → **Static Files** section:

| URL | Directory |
|---|---|
| `/static/` | `/home/yourusername/kidguard/static/` |

---

## Step 6: Initialize the Database

The database tables are created **automatically** when the app loads — no manual step needed. The SQLite file (`tracking.db`) will be created in `~/kidguard/` on the first request.

---

## Step 7: Reload & Test

1. Go to **Web** tab
2. Click the green **"Reload"** button
3. Visit: `https://yourusername.pythonanywhere.com`
4. You should see the **KidGuard** login page

---

## Step 8: Update the APK

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

## Step 9: Push an APK Update (OTA)

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
| **Real-time WebSocket** | ❌ **Not available** | Replaced by 30s polling |
| PostgreSQL | ❌ **Not available** | Using SQLite (good enough) |

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
| Database | SQLite only |

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

**Error: "Internal Server Error"**
- Go to **Web** tab → **Error log** to see the traceback
- Most common: missing file upload, wrong path

**Error: "Module not found"**
- Go to **Consoles** → **Bash** → `pip3 install --user <module>`

**Error: "Database is locked"**
- SQLite limitation on shared hosting
- The app uses `db.session.commit()` frequently to minimize locks

**Phone can't connect**
- Verify the URL: `https://yourusername.pythonanywhere.com`
- Check that the APK was built with the correct `server.url`
- Make sure the phone has internet access
