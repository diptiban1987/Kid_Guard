# KidGuard - PythonAnywhere Deployment Guide

## Step 1: Deploy to PythonAnywhere

1. Go to [PythonAnywhere](https://www.pythonanywhere.com/)
2. Open **Bash console**
3. Clone the repo:
   ```bash
   git clone https://github.com/diptiban1987/Kid_Guard.git
   ```
4. Go to **Web** tab → **Add new web app**
5. Choose **Manual configuration** → **Python 3.10**
6. Set **Source code**: `/home/diptiban2021/Kid_Guard/cloud-server`
7. Set **Working directory**: `/home/diptiban2021/Kid_Guard/cloud-server`
8. Edit **WSGI configuration file** and replace contents with:
   ```python
   import sys
   project_home = '/home/diptiban2021/Kid_Guard/cloud-server'
   if project_home not in sys.path:
       sys.path.insert(0, project_home)
   from app import app as application
   ```
9. Go to **Web** tab → click **Reload**

Your app is now live at: `https://diptiban2021.pythonanywhere.com/`

---

## Step 2: Create Parent Account

1. Open **https://diptiban2021.pythonanywhere.com/** in browser
2. Click **"Create Account"** tab
3. Fill in:
   - **Email**: `parent@local.com` (or any email)
   - **Password**: `parent123` (min 6 characters)
   - **Display Name**: `Parent`
4. Click **"Create Account"**
5. You'll be redirected to the **Dashboard**

---

## Step 3: Generate Pairing Code

1. On the dashboard, find the **"Pairing"** or **"Add Child"** section
2. Click **"Generate Pairing Code"**
3. A code like `ABCD1234` will appear
4. **Copy this code** — you'll need it for the phone

---

## Step 4: Set Up the Phone App

1. Open the **KidGuard app** on the child's phone
2. If already set up, clear data first:
   - Go to **Phone Settings → Apps → KidGuard → Clear Data**
   - Or reinstall the APK
3. The app will try auto-discovering the server
4. If it doesn't find it automatically, **enter server URL manually**:
   ```
   https://diptiban2021.pythonanywhere.com
   ```
5. Enter the **same email and password** you used for the parent account:
   - **Email**: `parent@local.com` (same as parent)
   - **Password**: `parent123` (same as parent)
6. Enter the **pairing code** from Step 3
7. Click **Login / Connect**

> **Note**: The phone automatically registers as a "child" account using the same email. Both parent and child accounts can share the same email address.

---

## Step 5: Approve Pairing

1. Go back to the **Dashboard** on the web browser
2. You'll see a **pending pairing request** notification
3. Click **"Approve"** to link the phone to your parent account

---

## Step 6: Verify

1. On the dashboard, you should now see the device listed
2. The phone will start reporting:
   - Location
   - SMS messages
   - Call logs
   - Web history
   - Screen time
   - Installed apps
3. Click on the device to see detailed info

---

## How Auto-Discovery Works

The app tries servers in this order:
1. **Saved URL** (if previously connected)
2. **BuildConfig default** (local server IP)
3. **Local network scan** (scans WiFi subnet for server on port 5000)
4. Falls back to **manual URL entry**

When you're **at home**, it connects to local server (`http://192.168.1.5:5000`).
When you're **away**, it falls back to cloud (`https://diptiban2021.pythonanywhere.com`).

---

## Quick Reference

| What | Value |
|------|-------|
| Parent email | `parent@local.com` |
| Parent password | `parent123` |
| Child email | `parent@local.com` (same as parent) |
| Child password | `parent123` (same as parent) |
| Cloud URL | `https://diptiban2021.pythonanywhere.com` |
| Local URL | `http://192.168.1.5:5000` |

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| App can't connect | Enter server URL manually in the app |
| Pairing code not working | Generate a new code, make sure to approve within 5 minutes |
| Dashboard shows no data | Check phone has internet, app is running, permissions granted |
| Web history shows 0 | Open Chrome and browse some websites, then wait for next report cycle |
| Call monitoring not working | Grant `READ_PHONE_STATE` permission on the phone |
| "Email already registered" | This shouldn't happen now — parent and child can share the same email |
