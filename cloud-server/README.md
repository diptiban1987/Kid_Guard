# KidGuard Cloud Parental Control - Deployment Guide

## Quick Start (Development)

### Install Dependencies
```bash
cd cloud-server
pip install -r requirements.txt
```

### Run Server
```bash
python app.py
```

The server starts at `http://0.0.0.0:5000`.

### Access Dashboard
1. Open `http://localhost:5000` in a browser
2. Create a parent account (email + password)
3. Click "Add Child" to generate a pairing code
4. Install the patched APK on the child's phone
5. In the app: enter email/password for child account, enter pairing code
6. Approve the pairing from the parent dashboard

---

## Docker Deployment (Production)

### Using Docker Compose (Recommended)
```bash
docker-compose up -d
```

### Configuration
Create a `.env` file:
```
SECRET_KEY=your-secure-random-secret-key-here
JWT_SECRET_KEY=your-jwt-secret-key-here
DATABASE_URL=postgresql://kidguard:password@db:5432/kidguard
CLOUD_SERVER_URL=https://your-domain.com
```

### Manual Docker
```bash
docker build -t kidguard-server .
docker run -d -p 5000:5000 \
  -e SECRET_KEY=your-secret \
  -e DATABASE_URL=sqlite:///data.db \
  -v ./data:/app/data \
  kidguard-server
```

---

## Cloud Deployment Options

### Option 1: Render.com (Recommended)
1. Push code to GitHub
2. Create new Web Service on Render
3. Set build command: `pip install -r requirements.txt`
4. Set start command: `gunicorn -k eventlet -w 1 app:app`
5. Add environment variables:
   - `SECRET_KEY`
   - `JWT_SECRET_KEY`
   - `CLOUD_SERVER_URL=https://your-app.onrender.com`

### Option 2: Railway.app
1. Connect GitHub repo
2. Set start command: `gunicorn -k eventlet -w 1 app:app`
3. Add environment variables

### Option 3: VPS (DigitalOcean, Linode)
```bash
# SSH into server
git clone https://github.com/your-org/kidguard.git
cd kidguard/cloud-server

# Using Docker
docker-compose up -d

# Or manually
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
gunicorn -k eventlet -w 1 -b 0.0.0.0:5000 app:app
```

### Option 4: Using ngrok (Quick Test)
```bash
python app.py &
ngrok http 5000
```
Use the ngrok URL in the app's server configuration.

---

## Android App Configuration

The Android app connects to the cloud server. After deployment:

1. **Rebuild the APK** with your server URL:
   - Update `local.properties`: `server.url=https://your-domain.com`
   - Run: `./gradlew assembleDebug`

2. **Or use the patched APK** with in-app configuration:
   - Open app → Settings → Configure Server
   - Enter your cloud server URL

---

## Architecture

```
┌─────────────────────┐     ┌─────────────────┐     ┌──────────────┐
│  Parent Dashboard   │────▶│   KidGuard API  │◀────│ Child's Phone│
│  (Web Browser)      │     │   Flask Server  │     │ (Android App)│
│                     │     │   + WebSocket   │     │              │
│  - Live Map         │     │                 │     │  - Location  │
│  - Activity Feed    │     │   PostgreSQL    │     │  - SMS/Calls │
│  - Screen Time      │     │   or SQLite     │     │  - Screen    │
│  - Geofences        │     │                 │     │  - Apps      │
│  - Remote Control   │     │   File Storage  │     │  - Web Hist. │
└─────────────────────┘     └─────────────────┘     └──────────────┘
```

## Features

### For Parents
- Live location tracking on map
- Screen time monitoring (daily/weekly)
- SMS and call log viewing
- Installed apps listing
- Web browsing history
- Geofence alerts (enter/exit zones)
- Real-time activity feed
- Remote device commands (lock, alarm, screenshot)
- Multi-child support
- Set app restrictions

### For Child's Device
- Silent background monitoring
- Stealth mode (hide app icon)
- JWT-based secure connection
- 5-minute reporting interval
- Battery-efficient location tracking

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/auth/register | Create account |
| POST | /api/auth/login | Login |
| GET | /api/auth/me | Get user profile |
| POST | /api/pairing/generate | Generate pairing code |
| POST | /api/pairing/claim | Claim pairing code |
| POST | /api/device/register | Register Android device |
| POST | /api/report/bulk | Main data report endpoint |
| GET | /api/parent/stats | Dashboard statistics |
| GET | /api/parent/devices | All child devices |
| GET | /api/parent/locations/:id | Location history |
| GET | /api/parent/activity/:id | Activity log |
| GET | /api/parent/sms/:id | SMS messages |
| GET | /api/parent/calls/:id | Call logs |
| GET | /api/parent/apps/:id | Installed apps |
| GET | /api/parent/screentime/:id | Screen time data |
| POST | /api/parent/commands/:id | Send remote command |
| POST | /api/parent/geofences/:id | Create geofence |
| WebSocket | / | Real-time updates |
