# KidGuard — Parental Control & Device Monitoring Platform

A cloud-based multi-tenant parental control SaaS platform that enables parents to monitor their children's Android devices in real time, from anywhere across the internet.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Project Structure](#project-structure)
- [Android App](#android-app)
  - [Permissions](#permissions)
  - [Components](#components)
  - [Data Collection](#data-collection)
  - [Stealth Mode](#stealth-mode)
  - [Uninstall Protection](#uninstall-protection)
  - [Remote Update System](#remote-update-system)
- [Cloud Server](#cloud-server)
  - [API Endpoints](#api-endpoints)
  - [Database Models](#database-models)
  - [Real-Time WebSocket Events](#real-time-websocket-events)
  - [Deployment](#deployment)
- [Web Dashboard](#web-dashboard)
- [Setup Guide](#setup-guide)
  - [Prerequisites](#prerequisites)
  - [Local Development](#local-development)
  - [Cloud Deployment](#cloud-deployment)
- [Security](#security)

---

## Architecture Overview

```
┌─────────────────────┐       ┌─────────────────────────┐       ┌──────────────────┐
│   Child's Phone     │       │     Cloud Server         │       │   Parent's       │
│   (Android App)     │◄─────►│  (Flask + WebSocket)     │◄─────►│   Web Browser    │
│                     │  HTTP │                          │  HTTP │   (Dashboard)    │
│  ┌───────────────┐  │  +WS  │  ┌────────────────────┐  │  +WS   │                  │
│  │ TrackerService │──┼──────┼─►│  /api/report/bulk   │──┼───────►  Live Map        │
│  │ (Foreground)   │  │       │  │  /api/device/*      │  │       │  Activity Feed   │
│  ├───────────────┤  │       │  │  /api/auth/*        │  │       │  Screen Time     │
│  │ Collectors     │  │       │  │  /api/pairing/*    │  │       │  Geofences       │
│  ├───────────────┤  │       │  │  /api/command/*    │  │       │  Remote Commands │
│  │ Accessibility  │  │       │  │  /api/parent/*    │  │       │  Media Files     │
│  │ Service        │  │       │  │  /api/app/*       │  │       │                  │
│  └───────────────┘  │       │  └────────────────────┘  │       └──────────────────┘
└─────────────────────┘       └─────────────────────────┘
```

### Key Design Decisions

| Decision | Rationale |
|---|---|
| JWT tokens (not session cookies) | Mobile app + web dashboard share same auth seamlessly |
| WebSocket (Flask-SocketIO) | Parent receives live activity without polling |
| 8-char pairing codes | Secure one-time parent-child linking |
| Bulk report endpoint | Single POST bundles all data types — saves battery |
| `async_mode='threading'` | Avoided eventlet dependency issues |
| `base-config` cleartext traffic | Development convenience; lock down for production |

---

## Project Structure

```
D:\ParentalControl\
├── app/                                    # Android app (Kotlin)
│   ├── build.gradle.kts                    # Build config with server.url injection
│   ├── src/main/
│   │   ├── AndroidManifest.xml             # Permissions, components, receivers
│   │   ├── java/com/parentalcontrol/app/
│   │   │   ├── MainActivity.kt             # Login, pairing, settings, stealth toggle
│   │   │   ├── TrackerService.kt           # Foreground service — periodic reporting
│   │   │   ├── TrackerAccessibilityService.kt  # App switch, keylog, click capture
│   │   │   ├── DeviceAdminReceiver.kt      # Prevents uninstall without password
│   │   │   ├── UpdateManager.kt            # Silent APK download & install (OTA)
│   │   │   ├── BootReceiver.kt             # Restart on device boot
│   │   │   ├── AlarmReceiver.kt            # Scheduled alarm handler
│   │   │   ├── WebDashboardActivity.kt     # Embedded WebView for dashboard
│   │   │   ├── api/
│   │   │   │   ├── ApiClient.kt            # HTTP client: auth, report, commands, updates
│   │   │   │   └── CloudConfig.kt          # Persistent prefs: tokens, server URL, stealth
│   │   │   └── utils/
│   │   │       └── Collectors.kt           # Location, SMS, calls, apps, screen time, etc.
│   │   └── res/
│   │       ├── drawable/                   # Backgrounds, launcher icons
│   │       ├── layout/                     # Activity XML layouts
│   │       ├── values/strings.xml          # Disguised as "System Service"
│   │       └── xml/
│   │           ├── network_security_config.xml
│   │           ├── accessibility_service_config.xml
│   │           ├── device_admin_policies.xml
│   │           └── file_provider_paths.xml
│   └── build/outputs/apk/debug/app-debug.apk   # Built APK
│
├── cloud-server/                           # Flask cloud backend
│   ├── app.py                              # All routes, SocketIO, helpers (~1270 lines)
│   ├── config.py                           # App configuration
│   ├── models.py                           # 18 SQLAlchemy models (~270 lines)
│   ├── requirements.txt                    # Python dependencies
│   ├── Dockerfile                          # Container build
│   ├── docker-compose.yml                  # PostgreSQL + Flask + Nginx
│   ├── nginx.conf                          # Reverse proxy with SSL
│   ├── wsgi.py                             # WSGI entry point
│   ├── templates/                          # Jinja2 HTML
│   │   ├── login.html                      # Login / Register
│   │   ├── dashboard.html                  # Full parent dashboard
│   │   └── device.html                     # Per-device detail view
│   ├── static/js/                          # Dashboard JavaScript
│   │   └── cloud-dashboard.js              # Map, WebSocket, charts, commands
│   ├── static/css/                         # Dark theme styling
│   └── uploads/apk/version.json            # APK version metadata for OTA updates
│
├── build.gradle.kts                        # Root Gradle config
├── settings.gradle.kts                     # Project settings
├── gradlew / gradlew.bat                   # Gradle wrapper
├── local.properties                        # server.url for build injection
├── patch_apk.py                            # Binary APK patching utility
└── start_server.bat                        # Quick server start script
```

---

## Android App

### Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Cloud API communication |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS location tracking |
| `ACCESS_BACKGROUND_LOCATION` | Background location (Android 10+) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | Foreground service with location |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `READ_SMS` | Monitor incoming/outgoing SMS |
| `READ_CALL_LOG` | Monitor call history |
| `READ_EXTERNAL_STORAGE` | Media file access |
| `READ_CONTACTS` | Contact monitoring |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent battery killing |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `QUERY_ALL_PACKAGES` | Detect installed apps |
| `BIND_ACCESSIBILITY_SERVICE` | Accessibility monitoring |
| `BIND_DEVICE_ADMIN` | Uninstall protection |
| `INSTALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` | Silent OTA updates |

### Components

#### `MainActivity.kt`
Entry point with login, registration, pairing code entry, stealth mode toggle, device admin activation, uninstall password setup, and server URL configuration.

**Key features:**
- Login / Register as child account
- Enter 8-character pairing code to link with parent
- Settings dialog: Server config, Uninstall password, Device Admin, Stealth mode, Pairing, Update check, Logout
- Request all runtime permissions on first launch
- Battery optimization exemption (asked only once via SharedPreferences)
- `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` + `noHistory="true"` — app disappears from recent tasks

#### `TrackerService.kt`
Foreground service that runs continuously. Reports data to cloud every 5 minutes. Checks for remote commands every 60 seconds. Checks for OTA updates every hour.

**Reporting cycle:**
1. Register device on first run
2. Collect: location, battery, SMS, call logs, installed apps, activities (foreground app changes), screen time, web history
3. Bundle into single `POST /api/report/bulk`
4. Handle any pending commands from the response

**Command handling:**
| Command | Action |
|---|---|
| `lock` | Lock screen |
| `screenshot` | Capture screenshot |
| `alarm` | Play alarm sound for N seconds |
| `block_apps` | Block specified apps (AccessibilityService enforces) |
| `wipe` | Acknowledged but rejected (safety measure) |

**Notification:** Uses `IMPORTANCE_MIN` — silent, no sound/vibration, no badge, no timestamp. Displays disguised name "System Service — Running in background".

#### `TrackerAccessibilityService.kt`
AccessibilityService that monitors:
- **App switches** (`TYPE_WINDOW_STATE_CHANGED`) — detects when the user opens any app
- **Text input** (`TYPE_VIEW_TEXT_CHANGED`) — captures typed text (up to 200 chars)
- **Click tracking** (`TYPE_VIEW_CLICKED`) — logs view interactions

Reports are sent as activity records to the cloud server. Debounced (min 2s between reports).

#### `DeviceAdminReceiver.kt`
Device admin receiver that prevents uninstallation:
- On `onDisableRequested`: shows warning requiring uninstall password
- Password must be set via app settings before admin can be activated
- Without deactivating device admin in Settings → Security, standard uninstall is blocked

#### `UpdateManager.kt`
Silent OTA update system:
1. Checks `POST /api/app/check-update` with current `version_code`
2. If newer version available, downloads APK to `filesDir/updates/update.apk`
3. Attempts `PackageInstaller.Session` API (Android 8+ silent install)
4. Falls back to `ACTION_VIEW` intent with FileProvider URI
5. AccessibilityService can auto-confirm the install prompt

### Data Collection (`Collectors.kt`)

| Method | Data Collected | Frequency |
|---|---|---|
| `collectLocation` | GPS lat/lng, accuracy, speed, altitude | Every report cycle |
| `collectBatteryInfo` | Level, temperature, charging status, plugged state | Every report cycle |
| `collectSmsMessages` | Address (phone number), body, date, type (inbox/sent) | Every report cycle |
| `collectCallLogs` | Number, type (incoming/outgoing/missed), duration, date | Every report cycle |
| `collectInstalledApps` | Package name, app name, install date, version | Every report cycle |
| `collectForegroundApp` | Currently visible app package name | On app switch |
| `collectScreenTime` | Daily usage per app (via `UsageStatsManager`) | Every report cycle |
| `collectWebHistory` | Browser URLs, titles, visit counts (Android < 10) | Every report cycle |
| `collectDeviceInfo` | Device model, manufacturer, OS version, SDK level | On registration |

### Stealth Mode

When enabled from settings:
1. **Hides launcher icon** — `PackageManager.COMPONENT_ENABLED_STATE_DISABLED`
2. **Finishes activity** — calls `finishAffinity()`
3. **Launches system launcher** — switches to home screen
4. **Excludes from recents** — `android:excludeFromRecents="true"`
5. **No history** — `android:noHistory="true"` prevents back-navigation
6. **Disguised app name** — shows as "System Service"
7. **Minimal notification** — `IMPORTANCE_MIN`, silent, no badge

To re-open the app: dial secret code (e.g., `*#*#1234#*#*`) if configured, or use another launcher app.

### Uninstall Protection

1. User sets an uninstall password (≥4 chars) in Settings
2. User activates Device Admin via Settings → Enable Device Admin
3. Device admin blocks uninstall — system refuses to uninstall while admin is active
4. To uninstall: go to Settings → Security → Device Admin → deactivate
5. System calls `onDisableRequested()` which returns warning message
6. User must enter the uninstall password to proceed

### Remote Update System

Flow:
1. Server stores latest APK in `uploads/apk/` with `version.json` metadata
2. Parent (admin) uploads new APK via `POST /api/app/upload`
3. Child app checks `POST /api/app/check-update` every hour
4. If `version_code > current_version_code`, downloads via `GET /api/app/download/<version>`
5. Downloads to `filesDir/updates/update.apk`
6. Installs using `PackageInstaller.Session` (Android 8+, silent)
7. Falls back to `Intent.ACTION_VIEW` with FileProvider

---

## Cloud Server

### API Endpoints

#### Authentication

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | None | Create account (email, password, display_name, role) |
| POST | `/api/auth/login` | None | Login — returns access + refresh tokens |
| POST | `/api/auth/refresh` | Refresh token | Get new access token |
| GET | `/api/auth/me` | JWT | Get current user info |

#### Device Registration

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/device/register` | JWT | Register device (model, manufacturer, OS, screen) |
| GET | `/api/device/<device_id>/config` | JWT | Get device config + pending commands |

#### Pairing

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/pairing/generate` | Parent | Generate 8-char pairing code |
| POST | `/api/pairing/claim` | Child | Claim pairing code (link to parent) |
| GET | `/api/pairing/pending` | Parent | List pending pairing requests |
| POST | `/api/pairing/approve/<id>` | Parent | Approve child pairing |
| GET | `/api/pairing/children` | Parent | List all paired children |

#### Data Reporting

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/report/location` | JWT | Location report |
| POST | `/api/report/activity` | JWT | Activity event |
| POST | `/api/report/battery` | JWT | Battery status |
| POST | `/api/report/screentime` | JWT | Screen time data |
| POST | `/api/report/sms` | JWT | SMS messages |
| POST | `/api/report/calls` | JWT | Call logs |
| POST | `/api/report/apps` | JWT | Installed apps |
| POST | `/api/report/webhistory` | JWT | Browser history |
| POST | `/api/report/media` | JWT | Media file upload |
| POST | `/api/report/bulk` | JWT | **All data bundled in one request** |

#### Commands

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/command/<command_id>/status` | JWT | Update command status |

#### Parent Dashboard

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| GET | `/api/parent/stats` | Parent | Aggregate stats for all children |
| GET | `/api/parent/devices` | Parent | List all children's devices |
| GET | `/api/parent/activity/<device_id>` | Parent | Activity feed for a device |
| GET | `/api/parent/locations/<device_id>` | Parent | Location history |
| GET | `/api/parent/sms/<device_id>` | Parent | SMS messages |
| GET | `/api/parent/calls/<device_id>` | Parent | Call logs |
| GET | `/api/parent/apps/<device_id>` | Parent | Installed apps |
| GET | `/api/parent/screentime/<device_id>` | Parent | Screen time stats |
| GET | `/api/parent/webhistory/<device_id>` | Parent | Browser history |
| GET | `/api/parent/media/<device_id>` | Parent | Media files |

#### Geofences

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| GET | `/api/parent/geofences/<device_id>` | Parent | List geofences |
| POST | `/api/parent/geofences/<device_id>` | Parent | Create geofence |
| DELETE | `/api/parent/geofences/<geofence_id>` | Parent | Delete geofence |
| POST | `/api/parent/commands/<device_id>` | Parent | Send remote command |
| GET | `/api/parent/restrictions/<device_id>` | Parent | Get app restrictions |
| POST | `/api/parent/restrictions/<device_id>` | Parent | Set app restrictions |
| GET | `/api/parent/schedule/<device_id>` | Parent | Get schedule rules |
| POST | `/api/parent/schedule/<device_id>` | Parent | Set schedule rules |

#### Remote Update

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/app/check-update` | JWT | Check if newer APK available |
| GET | `/api/app/download/<version>` | JWT | Download APK file |
| POST | `/api/app/upload` | Admin | Upload new APK version |

#### Web Routes

| Method | Endpoint | Description |
|---|---|---|
| GET | `/` | Login page |
| GET | `/dashboard` | Parent dashboard |
| GET | `/device/<device_id>` | Per-device detail page |

### Database Models

18 tables defined in `models.py`:

| Table | Key Fields | Description |
|---|---|---|
| `users` | id, email, password_hash, role | Parent/child/admin accounts |
| `child_relations` | parent_id, child_id, pairing_code | Links parent to child |
| `devices` | device_id, user_id, device_name, model | Registered devices |
| `location_reports` | device_id, latitude, longitude, accuracy | GPS location history |
| `activity_reports` | device_id, activity_type, package_name, app_name | App usage & events |
| `battery_reports` | device_id, level, temperature, is_charging | Battery telemetry |
| `screen_time_reports` | device_id, package_name, app_name, total_time | Daily screen time |
| `sms_messages` | device_id, address, body, date, type | SMS monitoring |
| `call_logs` | device_id, number, type, duration, date | Call history |
| `installed_apps` | device_id, package_name, app_name, version | Installed applications |
| `media_files` | device_id, file_path, media_type, thumbnail | Media (photos, recordings) |
| `web_history` | device_id, url, title, visit_count, timestamp | Browser history |
| `geofences` | device_id, name, latitude, longitude, radius | Geofence boundaries |
| `geofence_events` | geofence_id, device_id, event_type, timestamp | Enter/exit events |
| `remote_commands` | device_id, command, params, status | Pending commands |
| `app_restrictions` | device_id, package_name, blocked, start_hour, end_hour | App blocking rules |
| `schedule_rules` | device_id, day_of_week, start_hour, end_hour, restrictions | Time-based schedules |

### Real-Time WebSocket Events

Using Flask-SocketIO:

| Event | Direction | Payload | Description |
|---|---|---|---|
| `connect` | Client→Server | — | Auto-connect with JWT token |
| `join` | Client→Server | `{room: "user_{userId}"}` | Join personal notification room |
| `leave` | Client→Server | `{room}` | Leave room |
| `location_update` | Server→Parent | `{device_id, lat, lng, ...}` | Real-time child location |
| `activity_update` | Server→Parent | `{device_id, activity_type, ...}` | Live activity feed |
| `geofence_event` | Server→Parent | `{device_id, geofence_name, event_type}` | Geofence enter/exit |
| `command_response` | Server→Parent | `{command_id, status}` | Command acknowledgment |

### Deployment

#### Docker (recommended)

```bash
cd cloud-server
docker-compose up -d
```

Services:
- `db` — PostgreSQL 15 (persistent volume)
- `server` — Flask app with gunicorn + eventlet
- `nginx` — Reverse proxy with SSL termination

#### Manual

```bash
cd cloud-server
pip install -r requirements.txt
python app.py
# Starts on http://0.0.0.0:5000
```

#### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SECRET_KEY` | `cloud-parental-control-secret-2024` | Flask secret key |
| `JWT_SECRET_KEY` | `jwt-secret-change-in-production` | JWT signing key |
| `DATABASE_URL` | `sqlite:///tracking.db` | PostgreSQL for production |
| `CLOUD_SERVER_URL` | `http://localhost:5000` | Public-facing URL |

---

## Web Dashboard

Built with: Flask templates + Vanilla JS + Leaflet.js + Socket.IO client

### Features

- **Live Map** — Leaflet map showing all children's real-time locations
- **Activity Feed** — Chronological log of app switches, SMS, calls, web visits
- **Screen Time Charts** — Daily usage per app (bar charts)
- **SMS & Call Logs** — Searchable message/call history per device
- **Installed Apps** — List of all apps on child's device
- **Web History** — Browser history with timestamps
- **Geofence Management** — Create/delete circular geofences; receive enter/exit events
- **Remote Commands** — Send lock, alarm, screenshot commands with one click
- **Pairing Code Generator** — Generate 8-char codes to link child devices
- **Media Gallery** — Photos and recordings captured from child device

### Dashboard Pages

| Route | Template | Purpose |
|---|---|---|
| `/` | `login.html` | Login / Register |
| `/dashboard` | `dashboard.html` | Main overview: map + activity + all children |
| `/device/{id}` | `device.html` | Per-device detail: history, geofences, commands |

---

## Setup Guide

### Prerequisites

- **Android SDK** (API 34) with Gradle 8.5
- **Python 3.11+** for cloud server
- **Docker** (optional, for production deployment)
- **Android device** (physical or emulator) for testing

### Local Development

#### 1. Start the Cloud Server

```bash
cd cloud-server
pip install -r requirements.txt
python app.py
```

Server starts at `http://0.0.0.0:5000`

#### 2. Update Server URL

Edit `local.properties` in the project root:

```properties
server.url=http://YOUR_MACHINE_IP:5000
```

Replace `YOUR_MACHINE_IP` with your actual local network IP (e.g., `10.90.4.102`).

#### 3. Build the APK

```bash
./gradlew assembleDebug
```

APK generated at: `app/build/outputs/apk/debug/app-debug.apk`

#### 4. Install on Device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### 5. Create Parent Account

1. Open `http://YOUR_MACHINE_IP:5000` in a browser
2. Register as a parent
3. Generate a pairing code from the dashboard

#### 6. Configure & Pair the Android App

1. Open the app on the child device
2. Enter server URL (if not already configured)
3. Register as child, enter pairing code
4. Approve pairing from parent dashboard
5. Accept all permission requests
6. Enable Accessibility Service in Settings → Accessibility
7. Enable Device Admin in Settings → Security → Device Admin

### Cloud Deployment

#### Deploy to Production (Render / Railway / DigitalOcean)

1. Set environment variables:
   - `SECRET_KEY` — strong random value
   - `JWT_SECRET_KEY` — different strong random value
   - `DATABASE_URL` — PostgreSQL connection string
   - `CLOUD_SERVER_URL` — `https://yourdomain.com`

2. Update `nginx.conf` with your domain and SSL certificates

3. Build and deploy with Docker:

```bash
cd cloud-server
docker-compose up -d
```

4. Upload the APK via the admin endpoint:

```bash
curl -X POST https://yourdomain.com/api/app/upload \
  -H "Authorization: Bearer <admin_token>" \
  -F "apk=@app-debug.apk" \
  -F "version_code=2" \
  -F "changelog=New features and fixes"
```

5. Update `local.properties` with production URL and rebuild APK

6. Install production APK on child device

---

## Security

### Current Measures

| Measure | Implementation |
|---|---|
| Password hashing | SHA-256 (upgrade to bcrypt for production) |
| JWT authentication | Access token (30d) + Refresh token (90d) |
| Multi-tenant isolation | Parent sees only their children's data |
| Pairing codes | 8-char alphanumeric, 10-minute TTL |
| Uninstall protection | Device Admin + password |
| Stealth mode | Hidden icon, disguised name, excluded from recents |
| Backup disabled | `android:allowBackup="false"` prevents data restoration |

### Production Recommendations

1. **Replace SHA-256** with **bcrypt** or **argon2** for password hashing
2. **Enforce HTTPS** — update `network_security_config.xml` to pin production domain
3. **Set strong secrets** — `SECRET_KEY` and `JWT_SECRET_KEY` must be random 64+ chars
4. **Use PostgreSQL** — SQLite is for development only
5. **Rotate JWT keys** — configure shorter token expiry
6. **Audit logging** — track admin actions for accountability
7. **Rate limiting** — protect auth endpoints from brute force
8. **APK signing** — use release keystore, never debug keys for production

---

## Build Configuration

The `server.url` is injected at build time from `local.properties` via `buildConfigField` in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
```

The app reads it at runtime through `CloudConfig.DEFAULT_SERVER` but allows the user to override it in Settings → Configure Server.
