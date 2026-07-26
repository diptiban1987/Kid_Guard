# KidGuard — Architecture & Design Document

A cloud-based, multi-tenant parental control platform that lets parents monitor and remotely manage their children's Android devices from anywhere over the internet.

This document is the authoritative technical reference for the system. It covers system design, component responsibilities, data flow, the data model, the security model, and production/scalability considerations. It is intended for engineers onboarding onto the project or inheriting the codebase.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Actors & Roles](#2-actors--roles)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Technology Stack](#4-technology-stack)
5. [Project Structure](#5-project-structure)
6. [Android App — Component Responsibilities](#6-android-app--component-responsibilities)
7. [Cloud Server — Component Responsibilities](#7-cloud-server--component-responsibilities)
8. [Web Dashboard — Component Responsibilities](#8-web-dashboard--component-responsibilities)
9. [Data Flow](#9-data-flow)
10. [Data Model](#10-data-model)
11. [API Surface](#11-api-surface)
12. [Security Model](#12-security-model)
13. [Scalability & Production Considerations](#13-scalability--production-considerations)
14. [Known Issues & Technical Debt](#14-known-issues--technical-debt)
15. [Glossary](#15-glossary)

---

## 1. System Overview

KidGuard is a three-tier monitoring platform:

- **Child device (Android):** A Kotlin app that runs as a resilient foreground service, harvesting device telemetry (location, battery, SMS, calls, apps, screen time, web/social activity) and pushing it to the cloud. It also executes remote commands issued by the parent.
- **Cloud server (Flask):** A Python backend that stores telemetry, enforces multi-tenant isolation, serves the parent dashboard, and brokers remote commands.
- **Parent dashboard (web):** A browser-based SPA (Flask templates + vanilla JS + Leaflet) giving parents a live map, activity feeds, geofences, app restrictions, schedules, and one-click remote commands.

The design prioritizes reliability of child-side data collection (multiple restart mechanisms, stealth, auto-permissions), and simplicity on the server side (single-file Flask app, pull-based command delivery).

### Design Principles

| Principle | How it's realized |
|---|---|
| Resilient collection | Foreground service + `START_STICKY` + boot receiver + alarm receiver + wake lock |
| Battery efficiency | One bulk POST every 30s bundles all data types instead of many small requests |
| Pull-based commands | Device polls config & bulk endpoints; no push channel dependency on the device |
| Multi-tenancy | `get_child_device_ids(parent_id)` isolation gate on every parent endpoint |
| Graceful degradation | SocketIO optional; dashboard uses polling regardless; OTA install falls back to Intent |
| Stealth | Disguised name, hidden icon, secret dialer code, minimal notification |

---

## 2. Actors & Roles

### 2.1 User Roles

| Role | Who | Capabilities |
|---|---|---|
| `parent` | The adult monitoring a child | Pair children, view telemetry, send commands, set geofences/restrictions/schedules |
| `child` | The monitored minor's account | Register a device, claim a pairing code, report telemetry, execute commands |
| `admin` | Platform operator | Everything a parent can do, plus upload new APK versions for OTA distribution |

Roles are stored on `User.role` and enforced by the `@parent_required` and `@admin_required` decorators.

### 2.2 Pairing Relationship

A parent and child are linked through a `ChildRelation` row created via an 8-character pairing code. A device belongs to a child user; a parent accesses devices transitively via their paired children. This is the foundation of the multi-tenant model (see [§9.4](#94-pairing-flow)).

---

## 3. High-Level Architecture

```
┌─────────────────────────┐        ┌──────────────────────────────┐        ┌──────────────────────────┐
│   Child's Android Phone  │        │        Cloud Server          │        │      Parent's Browser     │
│                          │  HTTPS │   (Flask + SQLAlchemy)        │  HTTPS │      (Dashboard SPA)      │
│  TrackerService (FGS)    │ ──────►│                              │ ◄──────►                          │
│   ├─ Collectors          │  bulk  │  /api/auth/*   (JWT)          │  fetch │  Login / Register         │
│   ├─ Command handler     │  report│  /api/device/*               │  +WS   │  Live Map (Leaflet)       │
│   └─ Update checker      │ ◄──────│  /api/pairing/*              │ (opt.) │  Activity / SMS / Calls   │
│                          │ config│  /api/report/*               │        │  Screen Time Charts        │
│  AccessibilityService    │ +cmds │  /api/command/*               │        │  Geofences / Restrictions  │
│  NotificationListener    │        │  /api/parent/*               │        │  Remote Commands           │
│  DeviceAdminReceiver     │        │  /api/app/* (OTA)            │        │  Media Gallery             │
│                          │        │  /api/files/*                │        │                            │
│  Media: camera/mic/snap  │ upload │                              │ serve  │                            │
└─────────────────────────┘        └──────────────────────────────┘        └──────────────────────────┘
        │                                          │
        │              PostgreSQL / SQLite           │
        └────────────  (16 tables, BigInt ms TS) ────┘
```

### Key Architectural Decisions

| Decision | Rationale |
|---|---|
| JWT (access 30d + refresh 90d), not session cookies | Mobile app and web dashboard share the same auth seamlessly |
| Pull-based command delivery | Device already polls; avoids maintaining a persistent push channel and FCM complexity |
| Bulk report endpoint | Single POST carries all data types — fewer radio wake-ups, saves battery |
| Optional SocketIO | Avoids hard dependency on eventlet/gevent; dashboard works via polling; realtime is a bonus |
| `async_mode='threading'` for SocketIO | Avoids eventlet compatibility issues with some dependencies |
| Single-file `app.py` | Pragmatic for current scale; no blueprints yet (see [§14](#14-known-issues--technical-debt)) |
| String PKs with prefixes (`usr_`, `dev_`, `loc_`…) | Readable IDs, no sequential enumeration leakage, easy debugging |
| BigInt millisecond timestamps | Consistent across Android epoch ms and server; avoids TZ ambiguity |
| Cleartext traffic permitted (dev) | Convenience for local builds; must be locked down for production |

---

## 4. Technology Stack

### 4.1 Android App

| Concern | Technology |
|---|---|
| Language | Kotlin 1.9, JVM target 17 |
| Build | Gradle 8.2 (Kotlin DSL), AGP, `compileSdk=34`, `minSdk=26`, `targetSdk=34` |
| UI | Android View bindings, Material 1.11, ConstraintLayout |
| HTTP | OkHttp 4.12 (no Retrofit), Gson 2.10 |
| Concurrency | Kotlin Coroutines (SupervisorJob + Dispatchers.IO) |
| Background work | `androidx.work` 2.9 (declared; runtime uses coroutines + AlarmManager) |
| Camera | Camera2 API |
| Audio | MediaRecorder (AAC, 44.1kHz, 128kbps) |
| Maps/dash | Embedded WebView (`WebDashboardActivity`) |
| Package | `com.parentalcontrol.app` |

### 4.2 Cloud Server

| Concern | Technology |
|---|---|
| Runtime | Python 3.11 |
| Framework | Flask 3.0 |
| Auth | flask-jwt-extended 4.7 (access + refresh) |
| ORM | flask-sqlalchemy 3.1 |
| CORS | flask-cors 4.0 (credentials) |
| Realtime | flask-socketio 5.6 (optional; `async_mode='threading'`) |
| Imaging | Pillow 10.2 (thumbnails/QR) |
| WSGI | gunicorn + eventlet (Docker), PythonAnywhere (wsgi.py) |
| Database | SQLite (dev), PostgreSQL 15 (production) |

### 4.3 Web Dashboard

| Concern | Technology |
|---|---|
| Rendering | Flask Jinja2 templates (shell) + vanilla JS (SPA logic) |
| Maps | Leaflet.js + OpenStreetMap / CARTO tiles |
| Charts | Hand-drawn Canvas 2D (no chart library) |
| Styling | Vanilla CSS, dark glassmorphism theme, Inter font |
| Auth storage | `localStorage` (`kidguard_token`, `kidguard_refresh`, `kidguard_server`) |

### 4.4 Infrastructure

| Concern | Technology |
|---|---|
| Containerization | Docker (`python:3.11-slim`), docker-compose (db + server + nginx) |
| Reverse proxy | Nginx (SSL termination, WebSocket upgrade, 50M body) |
| SSL | Let's Encrypt (via certbot volume) |
| DB persistence | Named volume `pgdata` |

---

## 5. Project Structure

```
D:\ParentalControl\
├── app/                                    # Android app (Kotlin)
│   ├── build.gradle.kts                    # Build config; server.url injected via buildConfigField
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml             # 24 permissions, 3 services, 6 receivers, 2 activities + alias
│       ├── java/com/parentalcontrol/app/
│       │   ├── MainActivity.kt             # Login, pairing, setup wizard, settings, stealth
│       │   ├── WebDashboardActivity.kt      # Embedded WebView of /dashboard
│       │   ├── TrackerService.kt           # Core FGS: periodic reporting + command execution
│       │   ├── TrackerAccessibilityService.kt  # App/text/click monitoring + auto-permission + screenshot
│       │   ├── SocialNotificationService.kt    # NotificationListener for 18 social apps
│       │   ├── BootReceiver.kt             # Restart service on boot
│       │   ├── AlarmReceiver.kt            # AlarmManager fallback restart
│       │   ├── SetupReceiver.kt            # ADB-driven zero-touch setup broadcast
│       │   ├── DialerSecretCodeReceiver.kt # Reveal app via *#*#CODE#*#*
│       │   ├── DeviceAdminReceiver.kt      # Uninstall protection
│       │   ├── AutoPermissionHelper.kt     # Auto-tap system dialogs
│       │   ├── AutoConnectManager.kt       # LAN server discovery
│       │   ├── ShizukuPermissionManager.kt # Shell-based permission granting (ADB UID)
│       │   ├── RemoteCaptureManager.kt     # Camera2 photos + MediaRecorder audio
│       │   ├── UpdateManager.kt            # OTA APK download + install
│       │   ├── api/
│       │   │   ├── ApiClient.kt            # OkHttp REST client (auth, report, commands, updates)
│       │   │   └── CloudConfig.kt          # SharedPreferences singleton config store
│       │   └── utils/
│       │       └── Collectors.kt           # Device data harvesters + data classes
│       └── res/
│           ├── drawable/                   # Backgrounds, launcher icons
│           ├── layout/                     # activity_main.xml, activity_web_dashboard.xml
│           ├── values/strings.xml          # Disguised as "SystemService"
│           └── xml/                         # accessibility, device_admin, network_security, file_provider
│
├── cloud-server/                           # Flask cloud backend
│   ├── app.py                              # All routes + helpers + SocketIO (~1537 lines)
│   ├── config.py                           # Config class (env-driven)
│   ├── models.py                           # 18 SQLAlchemy models (~307 lines)
│   ├── wsgi.py                             # WSGI entry (PythonAnywhere/gunicorn)
│   ├── requirements.txt
│   ├── Dockerfile                          # gunicorn + eventlet
│   ├── docker-compose.yml                  # postgres + server + nginx
│   ├── nginx.conf                          # SSL reverse proxy + WS upgrade
│   ├── templates/                          # login.html, dashboard.html, device.html
│   ├── static/css/cloud-style.css          # Dark glassmorphism theme (~1140 lines)
│   ├── static/js/cloud-dashboard.js        # Dashboard SPA logic (~1138 lines)
│   ├── static/js/device-detail.js          # Per-device page logic (~777 lines)
│   ├── uploads/                            # Media + APK storage
│   │   └── apk/version.json               # OTA metadata
│   └── tracking.db                         # SQLite (dev)
│
├── build.gradle.kts                        # Root Gradle (AGP + Kotlin plugins)
├── settings.gradle.kts                     # Project settings
├── gradle/ gradlew gradlew.bat
├── local.properties                        # server.url for build injection
├── patch_apk.py                            # Binary APK patching utility
├── start_server.bat                        # Quick server start
├── FREE_PLATFORM_COMPARISON.md
├── RENDER_DEPLOY.md
├── PYTHONANYWHERE_DEPLOY.md
└── README.md                              # This document
```

---

## 6. Android App — Component Responsibilities

The Android app is the most complex tier. It is organized into five logical layers.

### 6.1 Layered Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  UI Layer                                                     │
│   MainActivity (setup wizard, settings, login)                 │
│   WebDashboardActivity (embedded dashboard WebView)           │
├──────────────────────────────────────────────────────────────┤
│  Service Layer                                                │
│   TrackerService          — periodic reporting + commands     │
│   TrackerAccessibilityService — monitoring + auto-perms + snap │
│   SocialNotificationService — social notification capture     │
├──────────────────────────────────────────────────────────────┤
│  Receiver Layer                                               │
│   BootReceiver            — auto-start on boot                 │
│   AlarmReceiver           — AlarmManager restart fallback      │
│   SetupReceiver           — ADB zero-touch setup               │
│   DialerSecretCodeReceiver — reveal hidden app via dialer      │
│   DeviceAdminReceiver     — uninstall protection               │
├──────────────────────────────────────────────────────────────┤
│  Manager Layer                                                │
│   AutoPermissionHelper    — auto-tap system dialogs             │
│   AutoConnectManager      — LAN server discovery               │
│   ShizukuPermissionManager — shell-based setup (ADB UID)       │
│   RemoteCaptureManager    — camera + mic capture               │
│   UpdateManager           — OTA APK install                    │
├──────────────────────────────────────────────────────────────┤
│  API / Utils Layer                                            │
│   ApiClient (OkHttp)     — all HTTP communication              │
│   CloudConfig (prefs)     — persistent config singleton        │
│   Collectors             — device data harvesting              │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 Component Reference

#### `MainActivity.kt` (855 lines)
Entry activity. Handles login/registration as a child, pairing code entry, a 5-step setup wizard (accessibility → device admin → permissions → battery optimization → hide), settings dialog (11 options including server config, uninstall password, stealth toggle, dialer code, ADB setup, update check, logout), alarm playback, and ADB setup command display. Uses an activity-alias (`LauncherAlias`) for the launcher icon so the icon can be hidden without disabling the real activity.

#### `TrackerService.kt` (341 lines) — **core of the system**
A foreground service (`foregroundServiceType="location"`) running three concurrent coroutines:

| Coroutine | Interval | Responsibility |
|---|---|---|
| `startPeriodicReporting` | 30s | Register device (once), then loop `collectAndReport()` |
| `startConfigRefresh` | 60s | Fetch `/api/device/{id}/config` → process pending commands |
| `startUpdateChecker` | 1h (30s initial) | Check `/api/app/check-update` → download + install |

`collectAndReport()` assembles a single bulk payload (device info, location, battery, SMS ≤100, calls ≤100, installed apps, foreground app, screen time, web history, social notifications) via `Collectors` + `ApiClient.buildReportPayload()`, POSTs to `/api/report/bulk`, and processes any commands returned in the response.

`handleCommand(cmd)` dispatches:

| Command | Action |
|---|---|
| `lock` | `DevicePolicyManager.lockNow()` |
| `screenshot` | `TrackerAccessibilityService.captureScreenshot()` (Android 11+) |
| `alarm` | Launch `MainActivity` with alarm action; play ringtone N seconds |
| `block_apps` | Enforced by AccessibilityService (back-press when blocked app opens) |
| `camera_front` / `camera_back` | `RemoteCaptureManager.capturePhoto()` |
| `record_audio` | `RemoteCaptureManager.recordAudio()` |
| `wipe` | **Rejected** — marked failed (safety measure) |

Each command's lifecycle: server `pending` → device sets `delivered` → `completed`/`failed` via `POST /api/command/{id}/status`.

**Notification:** channel `parental_control_channel`, `IMPORTANCE_MIN`, silent, no badge, `VISIBILITY_SECRET`. Title "AndroidSystem", text "Optimizing device performance" — disguised as a system service.

**Resilience:** `START_STICKY`, partial wake lock (10 min), `BootReceiver` (auto-start on boot), `AlarmReceiver` (inexact repeating 30s fallback).

#### `TrackerAccessibilityService.kt` (264 lines)
An `AccessibilityService` with three responsibilities:

1. **Auto-permission granting** — via `AutoPermissionHelper`, auto-taps "Allow"/"Install"/"Activate" buttons in system dialogs (permissioncontroller, packageinstaller, settings). This enables near-zero-touch setup once accessibility is initially enabled.
2. **Secret dialer code detection** — reads dialer screen text for `*#*#CODE#*#*` patterns (Android 12+ fallback) and triggers `DialerSecretCodeReceiver.revealAndLaunch()`.
3. **Activity monitoring** — when `TrackerService.isRunning`:
   - `TYPE_WINDOW_STATE_CHANGED` → app switch report (debounced 2s)
   - `TYPE_VIEW_TEXT_CHANGED` → keylog (skips password fields, >3 chars, truncated 200)
   - `TYPE_VIEW_CLICKED` → click report (uses `contentDescription` as view ID)

   Each report spawns a thread that POSTs to `/api/report/bulk`.

Also exposes a static `captureScreenshot(callback)` using the Android 11+ `takeScreenshot()` API → Bitmap → JPEG → `ApiClient.uploadScreenshot()` → delete file.

#### `SocialNotificationService.kt` (153 lines)
A `NotificationListenerService` capturing notifications from **18 social apps** (WhatsApp, Instagram, Facebook, Messenger, Snapchat, Telegram, YouTube, TikTok, X, Discord, Pinterest, Reddit, LinkedIn, Viber, LINE, Skype, WhatsApp Business, Facebook Lite). Buffers into a `ConcurrentLinkedQueue`, classifies message type (message/like/comment/dm/video/snap/notification), extracts sender, truncates content to 500 chars. `TrackerService.collectAndReport()` calls `flushBuffer()` each cycle.

#### `DeviceAdminReceiver.kt` (29 lines)
Prevents uninstallation. `onDisableRequested()` returns a warning; the uninstall password (set in app settings, default "admin") must be entered. Enables `force-lock`, `wipe-data`, `watch-login`, `reset-password` policies.

#### `BootReceiver.kt` (16 lines) / `AlarmReceiver.kt` (60 lines)
Restart mechanisms. `BootReceiver` starts `TrackerService` on `BOOT_COMPLETED`. `AlarmReceiver` schedules an inexact repeating alarm (`RTC_WAKEUP`, 30s) that restarts the service if `TrackerService.isRunning` is false.

#### `SetupReceiver.kt` (173 lines)
ADB-driven zero-touch setup. Receives broadcasts (`SETUP_ALL`, `GRANT_PERMISSIONS`, `HIDE_APP`, `SHOW_APP`) protected by a shared secret (`kidguard2024`). `performFullSetup()` runs 8 steps on a background thread: configure server → set dialer code + auto-hide → set uninstall password → `ShizukuPermissionManager.runFullSetup()` → register/login → claim pairing → start TrackerService → mark complete. This allows deploying to many devices from a PC via ADB without touching each phone.

#### `DialerSecretCodeReceiver.kt` (131 lines)
Re-opens the hidden app. Listens for `SECRET_CODE` broadcasts (legacy + Android 12+). If the dialed host matches `CloudConfig.secretDialerCode` (default "132580"), re-enables the `LauncherAlias` component and launches `MainActivity` with a `from_secret_code` flag.

#### `AutoPermissionHelper.kt` (215 lines)
Auto-taps allow/install/activate buttons. Maintains 27 text patterns ("Allow", "Allow all the time", "Activate", "Install", "Use service", "Don't optimize"…) and resource IDs (`permission_allow_button`, `install_button`, `button1`…). Falls back to `dispatchGesture()` (tap at node center) if `ACTION_CLICK` fails. 3s debounce per package.

#### `AutoConnectManager.kt` (177 lines)
LAN server discovery with 4-priority fallback: saved URL → production domain → `BuildConfig.SERVER_URL` → LAN scan (derives subnet from `NetworkInterface`, pings gateway neighbors 1–20 and common last-octet IPs in parallel). Pings via HTTP GET `/api/auth/me` (true if 200–499).

#### `ShizukuPermissionManager.kt` (249 lines)
Shell-based configuration when invoked from ADB (shell UID). Grants 12 runtime permissions via `pm grant`, enables accessibility via `settings put secure`, activates device admin via `dpm set-active-admin`, disables battery optimization via `dumpsys deviceidle whitelist +PACKAGE`, hides icon via `pm disable`.

#### `RemoteCaptureManager.kt` (206 lines)
Camera2 API photo capture (front/back, JPEG ≤1280px) and MediaRecorder ambient audio (AAC, 44.1kHz, 128kbps, configurable duration). All on background threads with a `HandlerThread` for camera callbacks. Files written to cache, uploaded via `ApiClient.uploadScreenshot()` / `uploadAudioFile()`, then deleted.

#### `UpdateManager.kt` (152 lines)
OTA self-update. Checks `/api/app/check-update`; if `version_code > current`, downloads APK to `filesDir/updates/update.apk`, installs via `PackageInstaller.Session` (Android 8+ silent) or falls back to `Intent.ACTION_VIEW` + FileProvider. The accessibility service auto-confirms the install prompt.

#### `api/ApiClient.kt` (530 lines)
OkHttp client (15s connect / 30s read / 60s write). All requests carry `Authorization: Bearer <token>` via `authHeaders()`. Groups: auth (register/login/refresh/reset), pairing (claim), device (register/config), reporting (buildReportPayload/sendBulkReport/media uploads), updates (checkForUpdate). Auto-refreshes token on 401 in `sendBulkReport()`.

#### `api/CloudConfig.kt` (96 lines)
SharedPreferences-backed singleton (`cloud_config`). Stores: `serverUrl`, tokens, userId/email/role, `deviceId` (default `Build.DEVICE`), `stealthMode`, `deviceAdminActive`, `uninstallPassword`, `currentVersionCode`, `pendingUpdatePath`, `secretDialerCode` (default "132580"), `autoHideEnabled` (default true), `setupFullyCompleted`. Computed: `isLoggedIn`, `isChildAccount`, `apiBaseUrl`.

#### `utils/Collectors.kt` (343 lines)
Data harvesters. Each returns a data class:

| Method | Data | Source |
|---|---|---|
| `collectDeviceInfo` | Model, manufacturer, OS, SDK | `Build.*` |
| `collectLocation` | Lat/lng, accuracy, provider, timestamp | `LocationManager` last known (best of all providers) |
| `collectBatteryInfo` | Level %, charging, temperature | Sticky `BATTERY_CHANGED` broadcast |
| `collectSmsMessages` | Address, body (≤500), date, type | `Telephony.Sms` (last 100) |
| `collectCallLogs` | Number, name, duration, type, date | `CallLog.Calls` (last 100) |
| `collectInstalledApps` | Package, name, version, system flag | `PackageManager.getInstalledPackages` |
| `collectScreenTime` | Total minutes, unlocks, per-app usage | `UsageStatsManager` (24h window, excludes self) |
| `collectWebHistory` | URL, title, browser, visits | `browser/bookmarks` (empty on Android 10+) |
| `collectForegroundApp` | Current package | `UsageStatsManager` (last 60s) |

### 6.3 Permissions (24)

| Category | Permissions |
|---|---|
| Location | `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` |
| Foreground service | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION/CAMERA/MICROPHONE` |
| Comms | `READ_SMS`, `READ_CALL_LOG`, `READ_CONTACTS` |
| Media | `READ_EXTERNAL_STORAGE`, `CAMERA`, `RECORD_AUDIO` |
| Persistence | `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `WAKE_LOCK` |
| OTA | `INSTALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES` |
| System binding | `BIND_ACCESSIBILITY_SERVICE`, `BIND_DEVICE_ADMIN`, `BIND_NOTIFICATION_LISTENER_SERVICE` (service-level) |
| Misc | `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES` |

### 6.4 Stealth Mode

When enabled:
1. Hides launcher icon via disabling `LauncherAlias` component (fallback: `pm disable` shell command for Vivo/MIUI/ColorOS)
2. `finishAffinity()` + launch system launcher (go home)
3. `excludeFromRecents="true"` + `noHistory="true"`
4. Disguised name "SystemService", channel "AndroidSystem"
5. `IMPORTANCE_MIN` silent notification, `VISIBILITY_SECRET`

Re-open: dial `*#*#132580#*#*` (configurable). `DialerSecretCodeReceiver` re-enables the icon and launches `MainActivity`. If `autoHideEnabled` is true, the app re-hides on `onStop()`.

### 6.5 Uninstall Protection

1. User sets uninstall password (≥4 chars, default "admin") in Settings
2. User activates Device Admin
3. System blocks uninstall while admin is active
4. To uninstall: Settings → Security → Device Admin → deactivate → `onDisableRequested()` warning → password required

### 6.6 Setup Flow

The setup wizard in `MainActivity` (5 steps):
1. Enable Accessibility Service (manual — the only non-automatable step)
2. Enable Device Admin
3. Grant runtime permissions (auto-requested)
4. Disable battery optimization
5. Complete & optionally hide app

Once accessibility is on, `AutoPermissionHelper` auto-taps subsequent dialogs. For mass deployment, `SetupReceiver` + ADB (`ShizukuPermissionManager`) performs the full setup with no on-device interaction.

---

## 7. Cloud Server — Component Responsibilities

The server is a single-file Flask app (`app.py`, ~1537 lines) with no blueprints. Routes are grouped by comment sections.

### 7.1 Bootstrap & Extensions (`app.py:1-42`)

- Flask app + `Config` load
- `CORS(app, supports_credentials=True)`
- SocketIO conditionally imported; `SocketIO(app, cors_allowed_origins="*", async_mode='threading')` if available, else `socketio = None`
- `JWTManager(app)`, `db.init_app(app)`
- Upload folder created on startup
- `init_db()` creates all tables (called at module load for WSGI)

### 7.2 Decorators & Helpers (`app.py:44-77`)

| Helper | Location | Purpose |
|---|---|---|
| `hash_password(password)` | `app.py:46` | SHA-256 hexdigest (no salt — see [§12.2](#122-weaknesses)) |
| `parent_required(fn)` | `app.py:49` | `@jwt_required()` + role ∈ {parent, admin} |
| `admin_required(fn)` | `app.py:60` | `@jwt_required()` + role == admin |
| `get_child_device_ids(parent_id)` | `app.py:71` | **Core isolation primitive**: active ChildRelations → active Devices → list of `device_id` strings |
| `emit_realtime(device_id, event_type, data)` | `app.py:936` | No-op if no SocketIO; else emits `realtime_update` to room `user_{parent_id}` for each parent of the device's child |
| `check_geofences(device_id, lat, lng)` | `app.py:889` | Haversine distance; creates enter/exit `GeofenceEvent`; emits `geofence` event |

### 7.3 Route Groups

#### Authentication (`app.py:79-278`)
Register, login, refresh, me, forgot-password (token returned in response — dev), reset-password, forgot-username (masked email lookup).

#### Pairing (`app.py:279-405`)
Generate code (parent) → claim (child) → pending list → approve → children list. Uses `'pending'` sentinel strings in `ChildRelation`.

#### Device & Reporting (`app.py:407-885`)
Device register/config; individual report endpoints (location, activity, battery, screentime, sms, calls, apps, webhistory, media); **bulk report** (the primary endpoint); command status update.

#### Parent Dashboard API (`app.py:974-1403`)
All `@parent_required`. Stats, devices, activity (paginated), locations, sms, calls, social, apps, screentime, webhistory, media, geofences (CRUD), commands (create), restrictions (upsert), schedule (create). Every endpoint checks `device_id in get_child_device_ids(parent_id)`.

#### App Updates / OTA (`app.py:1405-1465`)
check-update, download (send_file), upload (admin only, updates `version.json`).

#### Files (`app.py:1467-1491`)
Media file serving by ID; parents access children's media, children access own media.

#### Web Routes (`app.py:1493-1512`)
`/` (login), `/dashboard`, `/device/<id>` (Jinja2 shell pages).

### 7.4 Command Delivery

Commands are **pull-based**:

```
Parent (dashboard)                 Cloud Server                   Child device
     │                                  │                              │
     │ POST /api/parent/commands/<id>   │                              │
     │ {command, params}               │                              │
     ├─────────────────────────────────►│ creates RemoteCommand        │
     │                                  │   status='pending'           │
     │                                  │                              │
     │                                  │◄── GET /api/device/<id>/config (every 60s)
     │                                  │    returns pending commands  │
     │                                  │                              │ handleCommand()
     │                                  │                              │
     │                                  │◄── POST /api/report/bulk (every 30s)
     │                                  │    returns pending commands  │
     │                                  │                              │ handleCommand()
     │                                  │                              │
     │                                  │◄── POST /api/command/<id>/status
     │                                  │    status='delivered' then 'completed'/'failed'
```

### 7.5 Geofence Monitoring

`check_geofences()` runs on every location/bulk report. For each active geofence: Haversine distance from the device's current position; fetches the latest `GeofenceEvent` to know `was_inside`; emits enter/exit events and `geofence` realtime events when transitions occur.

### 7.6 Realtime (SocketIO)

Optional. If `flask_socketio` is installed, the server emits `realtime_update` events to per-parent rooms (`user_{parent_id}`). Event types: `location`, `activity`, `battery`, `sms`, `call`, `web`, `media`, `heartbeat`, `geofence`. The web dashboard currently uses polling (30s) rather than the SocketIO client — SocketIO is a future optimization.

---

## 8. Web Dashboard — Component Responsibilities

### 8.1 Templates

| Route | Template | Purpose |
|---|---|---|
| `/` | `login.html` (490 lines) | Glassmorphism auth card with 4 tabs: Login, Register, Forgot Password, Forgot Username. Custom server URL field. Stores tokens in localStorage. |
| `/dashboard` | `dashboard.html` (496 lines) | Parent dashboard SPA shell: navbar, sidebar (children/devices/pending pairings), overview (stat cards + live map), child detail (map + screen time + battery + activity tabs + restrictions + schedule + geofences + remote control). |
| `/device/<id>` | `device.html` (779 lines) | Per-device detail: 6 stat cards, location history map (CARTO dark tiles), tabbed data (activity/SMS/calls/apps/web/social/media), geofences + remote control grid, restrictions + schedule grid. |

### 8.2 `cloud-dashboard.js` (1138 lines)

Vanilla JS SPA logic. Key functions:

| Function | Purpose |
|---|---|
| `fetchWithAuth(url, options)` | `fetch()` wrapper with Bearer token; auto-refreshes on 401 via `/api/auth/refresh`; redirects to `/` on refresh failure |
| `loadDashboard()` | Fetches stats + devices; renders stat cards, map markers, sidebar, children overview |
| `loadChildDetail(deviceId)` | Parallel-fetches 11 endpoints (locations, screentime, activity, sms, calls, apps, geofences, webhistory, media, restrictions, schedule) |
| `startPolling()` / `pollDashboard()` | 30s interval (configurable 15s–2min) calling loadDashboard + checkForUpdates + checkPendingPairings |
| `drawScreenTimeChart(data)` | Hand-drawn Canvas 2D bar chart (gradient `#667eea`→`#764ba2`, DPI-aware) |
| `sendRemoteCommand(command)` | POST `/api/parent/commands/{id}` (lock, alarm, screenshot) |
| `saveGeofence()` / `deleteGeofence()` | Geofence CRUD |
| `saveRestriction()` / `saveSchedule()` | App restrictions + schedule rules |
| `generatePairingCode()` / `approvePairing()` | Pairing flow |
| `isOnline(lastSeen)` | true if last_seen < 10 min ago |

### 8.3 `device-detail.js` (777 lines)

Companion script for `device.html`. Uses CARTO dark map tiles. `loadAllData()` parallel-fetches 12 endpoints. Supports extended commands: lock, alarm, screenshot, camera_front, camera_back, record_audio, wipe (with confirmation). 30s auto-refresh.

### 8.4 `cloud-style.css` (1140 lines)

Dark glassmorphism theme: background `#0f0c29`, accent gradient `#667eea`→`#764ba2`, Inter font, `backdrop-filter: blur()`. Responsive (sidebar collapses at 1024px). Animations: pulse, shimmer (skeletons), slideIn (toasts), fadeIn, badgePop.

---

## 9. Data Flow

### 9.1 Reporting Cycle (every 30s)

```
TrackerService.collectAndReport()
    │
    ├─ Collectors.collectDeviceInfo()
    ├─ Collectors.collectLocation() ──────────► LocationData
    ├─ Collectors.collectBatteryInfo() ───────► BatteryInfo
    ├─ Collectors.collectSmsMessages() ───────► List<SmsMessage> (≤100)
    ├─ Collectors.collectCallLogs() ─────────► List<CallLogEntry> (≤100)
    ├─ Collectors.collectInstalledApps() ─────► List<InstalledApp>
    ├─ Collectors.collectForegroundApp() ─────► package name
    ├─ Collectors.collectScreenTime() ────────► ScreenTimeData (per-app map)
    ├─ Collectors.collectWebHistory() ────────► List<WebHistoryEntry>
    ├─ SocialNotificationService.flushBuffer()► List<JSONObject>
    │
    ├─ ApiClient.buildReportPayload(...) ─────► JSON string
    │
    └─ POST /api/report/bulk
           │
           ├─ server stores: location, battery, activities[], sms[], calls[],
           │                apps[] (replace), screentime (upsert),
           │                webhistory[], social[]
           ├─ check_geofences() → enter/exit events
           ├─ emit_realtime() (if SocketIO)
           │
           └─ response: { success, server_time, commands[] }
                  │
                  └─ handleCommand() for each → POST /api/command/{id}/status
```

### 9.2 Command Execution

Commands arrive via two channels: the config endpoint (every 60s) and the bulk report response (every 30s). `TrackerService.handleCommand()` executes and updates status `pending → delivered → completed/failed`. See [§7.4](#74-command-delivery).

### 9.3 OTA Update Flow

```
Server: admin uploads APK via /api/app/upload → stores kidguard_v{n}.apk + version.json
                                                                          │
Child (every 1h): POST /api/app/check-update ─────────────────────────────►│
                   ◄── { has_update, download_url, changelog }              │
                   │                                                        │
                   ├─ if has_update: download APK → filesDir/updates/       │
                   ├─ installViaPackageInstaller() (Android 8+ silent)      │
                   │    fallback: installViaIntent() (ACTION_VIEW + FileProvider)
                   └─ AccessibilityService auto-taps "Install" button
```

### 9.4 Pairing Flow

```
Parent                           Cloud Server                     Child
  │                                  │                              │
  │ POST /api/pairing/generate       │                              │
  ├─────────────────────────────────►│ creates ChildRelation        │
  │  ◄── { pairing_code: "AB12CD34" }│  child_id='pending',          │
  │                                  │  is_active=false              │
  │                                  │                              │
  │ (shares code out-of-band) ──────────────────────────────────────►│
  │                                  │                              │ POST /api/pairing/claim
  │                                  │◄─────────────────────────────┤  { pairing_code }
  │                                  │ links child_id, is_active=false│
  │                                  │  (awaiting approval)          │
  │ GET /api/pairing/pending         │                              │
  ├─────────────────────────────────►│                              │
  │  ◄── [pending pairings]          │                              │
  │ POST /api/pairing/approve/<id>    │                              │
  ├─────────────────────────────────►│ is_active=true, paired_at=now │
  │                                  │                              │
  │  ◄── device now visible ─────────┤                              │
```

### 9.5 Geofence Event Flow

```
Device reports location (bulk or /report/location)
        │
        ▼
check_geofences(device_id, lat, lng)
        │
        ├─ for each active geofence:
        │    distance = Haversine(device, geofence)
        │    was_inside = (last GeofenceEvent for this geofence was 'enter')
        │    now_inside = distance <= radius
        │
        ├─ if !was_inside && now_inside && notify_on_entry:
        │    create GeofenceEvent('enter')
        │    emit_realtime('geofence', {event_type:'enter', ...})
        │
        └─ if was_inside && !now_inside && notify_on_exit:
             create GeofenceEvent('exit')
             emit_realtime('geofence', {event_type:'exit', ...})
```

### 9.6 Remote Capture Flow

```
Parent clicks "Front Camera" / "Record Audio"
        │
        ▼ POST /api/parent/commands/<device_id> {command: 'camera_front'}
        │
Child polls config/bulk → handleCommand('camera_front')
        │
        ├─ RemoteCaptureManager.capturePhoto(useFront=true)
        │     Camera2 → ImageReader (JPEG ≤1280px) → file in cacheDir
        │     ApiClient.uploadScreenshot(file) → multipart /api/report/media
        │     delete file
        │
        └─ POST /api/command/{id}/status {status:'completed'}
Parent sees media in dashboard Media gallery
```

---

## 10. Data Model

18 SQLAlchemy models. All use prefixed string PKs (`generate_id(prefix)`, 12-char lowercase+digits). All timestamps are BigInteger epoch milliseconds. Foreign keys reference `devices.device_id` (the Android device ID string), not the `devices.id` PK.

### 10.1 Entity Relationship

```
users ──┬── child_relations ──── users
        │   (parent_id, child_id,   (parent / child)
        │    pairing_code)
        │
        └── devices
              │
              ├── location_reports
              ├── activity_reports      (index: device_id, timestamp)
              ├── battery_reports
              ├── screen_time_reports   (upsert by device_id + date)
              ├── sms_messages          (dedup by sms_id)
              ├── call_logs             (dedup by call_id)
              ├── installed_apps        (full replace on report)
              ├── media_files
              ├── web_history
              ├── social_notifications
              ├── geofences ──── geofence_events
              ├── remote_commands       (parent_id FK)
              ├── app_restrictions
              └── schedule_rules

users ──── password_reset_tokens
```

### 10.2 Model Reference

| Model | Table | Key Fields | Notes |
|---|---|---|---|
| `User` | `users` | id, email (unique), password_hash, display_name, role, is_active, created_at, last_login | Roles: parent/child/admin |
| `ChildRelation` | `child_relations` | parent_id, child_id, pairing_code (unique), paired_at, is_active | Uses `'pending'` sentinel for unclaimed |
| `Device` | `devices` | id, device_id (unique, from Android), user_id, model, manufacturer, android_version, sdk_version, is_active, stealth_mode, reporting_interval (300s), first_seen, last_seen | |
| `LocationReport` | `location_reports` | device_id, latitude, longitude, accuracy, altitude, speed, bearing, provider, timestamp | |
| `ActivityReport` | `activity_reports` | device_id, activity_type, package_name, app_name, data (JSON text), timestamp | Composite index on (device_id, timestamp) |
| `BatteryReport` | `battery_reports` | device_id, level, is_charging, temperature, voltage, plugged, timestamp | |
| `ScreenTimeReport` | `screen_time_reports` | device_id, date (YYYY-MM-DD), total_minutes, unlocks, app_usage_json | Upserted by device+date |
| `SmsMessage` | `sms_messages` | device_id, sms_id, address, body, date, type | Deduped by sms_id |
| `CallLog` | `call_logs` | device_id, call_id, number, name, duration, date, type | Deduped by call_id |
| `InstalledApp` | `installed_apps` | device_id, package_name, app_name, version_name, version_code, first_install_time, last_update_time, is_system_app | Full replace on each report |
| `MediaFile` | `media_files` | device_id, media_type (photo/screenshot/audio/video), file_path, file_size, mime_type, thumbnail_path, timestamp | |
| `WebHistory` | `web_history` | device_id, url, title, browser, visit_count, timestamp | No dedup |
| `Geofence` | `geofences` | device_id, name, latitude, longitude, radius (500m default), notify_on_entry, notify_on_exit, is_active | |
| `GeofenceEvent` | `geofence_events` | device_id, geofence_id, event_type (enter/exit), latitude, longitude, timestamp | |
| `RemoteCommand` | `remote_commands` | device_id, parent_id, command, params (JSON), status (pending/delivered/completed/failed), created_at, delivered_at, completed_at, result | |
| `AppRestriction` | `app_restrictions` | device_id, package_name, app_name, is_blocked, max_minutes_per_day, block_start_time, block_end_time, is_active | Upserted by package_name |
| `ScheduleRule` | `schedule_rules` | device_id, name, day_of_week (0=Mon..6=Sun, -1=everyday), start_time, end_time, is_block_time, is_active | |
| `PasswordResetToken` | `password_reset_tokens` | user_id, token (unique, indexed), used, expires_at | 30-min TTL |
| `SocialNotification` | `social_notifications` | device_id, package_name, app_name, sender, content, message_type, timestamp | Captured by NotificationListener |

### 10.3 Data Volume Considerations

High-volume tables (location_reports, activity_reports, sms_messages, call_logs, social_notifications) grow with each 30s report cycle per device. With N devices reporting every 30s:
- Location: ~2,880 rows/device/day
- Activity: variable (debounced, 2s min)
- SMS/Calls: deduped, bounded by device activity

**Retention strategy is not yet implemented** — see [§14](#14-known-issues--technical-debt).

---

## 11. API Surface

A condensed reference. All `/api/*` routes return JSON. Auth via `Authorization: Bearer <access_token>`.

### 11.1 Authentication

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | — | Create account (email, password ≥6, display_name, role) → tokens + user |
| POST | `/api/auth/login` | — | Login → tokens + user |
| POST | `/api/auth/refresh` | refresh token | New access token |
| GET | `/api/auth/me` | JWT | Current user profile |
| POST | `/api/auth/forgot-password` | — | Generate reset token (returned in response — dev) |
| POST | `/api/auth/reset-password` | — | Set new password with token → auto-login |
| POST | `/api/auth/forgot-username` | — | Masked email lookup by display_name hint |

### 11.2 Pairing

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/pairing/generate` | parent | Generate 8-char code (10-min TTL) |
| POST | `/api/pairing/claim` | JWT (child) | Claim code → pending approval |
| GET | `/api/pairing/pending` | parent | Pending pairing requests |
| POST | `/api/pairing/approve/<id>` | parent | Approve child pairing |
| GET | `/api/pairing/children` | parent | All paired children + devices |

### 11.3 Device & Reporting

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/device/register` | JWT | Register/update device |
| GET | `/api/device/<id>/config` | JWT | Config + geofences + blocked apps + **pending commands** |
| POST | `/api/report/bulk` | JWT | **All data in one request** → returns pending commands |
| POST | `/api/report/{location,activity,battery,screentime,sms,calls,apps,webhistory,media}` | JWT | Individual report endpoints |
| POST | `/api/command/<id>/status` | JWT | Update command status |

### 11.4 Parent Dashboard

All `@parent_required` + `device_id ∈ get_child_device_ids()`.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/parent/stats` | Aggregate stats |
| GET | `/api/parent/devices` | All child devices (with battery, screen time, child info) |
| GET | `/api/parent/activity/<id>` | Paginated activity (`limit`, `offset`, `type`) |
| GET | `/api/parent/{locations,sms,calls,social,apps,screentime,webhistory,media}/<id>` | Per-data-type history |
| GET/POST | `/api/parent/geofences/<id>` | List / create geofence |
| DELETE | `/api/parent/geofences/<geofence_id>` | Delete geofence |
| POST | `/api/parent/commands/<id>` | Create remote command |
| GET/POST | `/api/parent/restrictions/<id>` | List / upsert app restrictions |
| GET/POST | `/api/parent/schedule/<id>` | List / create schedule rules |

### 11.5 OTA Updates

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/app/check-update` | JWT | Compare version_code → has_update + download_url |
| GET | `/api/app/download/<version>` | JWT | Serve APK file |
| POST | `/api/app/upload` | admin | Upload new APK + update version.json |

### 11.6 Files & Web

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| GET | `/api/files/<media_id>` | JWT | Serve media (parents: children's; children: own) |
| GET | `/` | — | Login page |
| GET | `/dashboard` | — (client-side auth) | Dashboard SPA shell |
| GET | `/device/<id>` | — (client-side auth) | Device detail page |

### 11.7 WebSocket Events (optional SocketIO)

| Event | Direction | Payload | Description |
|---|---|---|---|
| `connect` | client→server | — | Connect |
| `join` | client→server | `{user_id}` | Join room `user_{user_id}` |
| `leave` | client→server | `{user_id}` | Leave room |
| `realtime_update` | server→parent room | `{device_id, event_type, data, timestamp}` | Realtime telemetry |

---

## 12. Security Model

### 12.1 Current Measures

| Measure | Implementation |
|---|---|
| Authentication | JWT (access 30d + refresh 90d); Bearer token in `Authorization` header |
| Authorization | `@parent_required` / `@admin_required` decorators; `get_child_device_ids()` per-request isolation |
| Multi-tenancy | Parent sees only their paired children's devices/data; checked on every parent endpoint |
| Pairing security | 8-char alphanumeric codes, 10-min TTL, unique, single-use |
| Uninstall protection | Device Admin + password (default "admin") |
| Stealth | Hidden icon, disguised name "SystemService", secret dialer code, `allowBackup=false` |
| Media access control | Parents access children's media; children access own only |
| Token refresh | Frontend auto-refreshes on 401; redirects to login on refresh failure |
| OTA integrity | Admin-only upload; version metadata in `version.json` |

### 12.2 Weaknesses

| Weakness | Risk | Recommendation |
|---|---|---|
| **SHA-256 password hashing (no salt)** | Rainbow table attacks | Migrate to bcrypt/argon2 |
| **Default secrets** (`'Satyadeep'`, `'SatyadeepNayak'`) | Token forgery if deployed as-is | Set strong env vars in production |
| **Cleartext traffic permitted** (`usesCleartextTraffic="true"`) | MITM on HTTP | Enforce HTTPS; pin domain in `network_security_config.xml` |
| **30-day access tokens** | Long token theft window | Shorten to 15–60 min; rely on refresh |
| **Reset token returned in API response** | Token leakage over network/logs | Send via email (SMTP) in production |
| **No rate limiting** | Brute-force on auth endpoints | Add `flask-limiter` |
| **No input validation library** | Injection / malformed data | Add marshmallow/pydantic schemas |
| **No audit logging** | No accountability for admin actions | Log sensitive operations |
| **APK signed with debug keys (dev)** | Update spoofing | Use a release keystore |
| **Shared setup secret** (`kidguard2024`) in client | Reverse-engineerable | Rotate per-deployment or remove post-setup |

### 12.3 Security Boundary Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  Untrusted: Parent Browser (anyone with parent credentials)     │
├─────────────────────────────────────────────────────────────────┤
│  TLS (nginx) ── Flask ── @parent_required ── get_child_device_ids│
│                    │                                            │
│                    ▼                                            │
│  Per-request isolation: device_id must belong to a child        │
│  paired with the authenticated parent                           │
├─────────────────────────────────────────────────────────────────┤
│  Trusted: Child Device (owns its JWT, reports own data only)    │
│  Hardened: Device Admin, stealth, auto-restart, OTA            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 13. Scalability & Production Considerations

### 13.1 Current Scale

- Single Flask process (gunicorn + eventlet, 1 worker in Docker)
- SQLite (dev) or single PostgreSQL (Docker)
- Pull-based polling: device every 30s, dashboard every 30s
- No caching layer, no message queue, no horizontal scaling

### 13.2 Bottlenecks

| Bottleneck | Impact | Mitigation |
|---|---|---|
| Bulk report writes (many inserts per 30s per device) | DB write contention at ~100+ devices | Batch inserts, connection pooling, read replicas |
| `/api/parent/devices` fan-out (queries per device) | Slow at many devices | Aggregate query / materialized view |
| Polling overhead (dashboard) | N parents × 30s = server load | Enable SocketIO push; eliminate polling |
| Media storage on local filesystem | Disk fill, no redundancy | S3/object storage |
| Single gunicorn worker | No concurrency beyond eventlet | Multiple workers behind load balancer |

### 13.3 Production Hardening Checklist

- [ ] Replace SHA-256 with bcrypt/argon2
- [ ] Set strong `SECRET_KEY` and `JWT_SECRET_KEY` (64+ random chars)
- [ ] Shorten JWT access token to ≤1h; keep refresh 7–30d
- [ ] Enforce HTTPS; update `network_security_config.xml` to pin production domain
- [ ] Migrate password reset to email (SMTP)
- [ ] Add rate limiting (`flask-limiter`) on auth endpoints
- [ ] Add input validation (marshmallow/pydantic)
- [ ] Use PostgreSQL (not SQLite)
- [ ] Enable connection pooling (`SQLALCHEMY_ENGINE_OPTIONS`)
- [ ] Add database indexes on high-frequency query columns (timestamps, device_id)
- [ ] Implement data retention/cleanup (archive or prune old telemetry)
- [ ] Move media to object storage (S3/GCS)
- [ ] Add audit logging for admin actions
- [ ] Sign APK with release keystore
- [ ] Add health checks and monitoring (Prometheus/Grafana)
- [ ] Containerize with multiple gunicorn workers behind a load balancer
- [ ] Enable SocketIO for real-time push (eliminate dashboard polling)

### 13.4 Scaling Path

```
Single Flask + SQLite (dev)
        │
        ▼
Flask + PostgreSQL + Nginx (current Docker)
        │
        ▼
Flask (N workers) + Pg pooler + Redis (cache) + S3 (media)  ← ~1,000 devices
        │
        ▼
Flask + Pg replicas + Redis + Celery (async jobs) + CDN  ← ~10,000 devices
```

---

## 14. Known Issues & Technical Debt

| Issue | Location | Description |
|---|---|---|
| Field mismatches (restrictions/schedule) | `device-detail.js` ↔ `models.py` | Frontend sends `start_hour`/`end_hour`/`daily_limit_minutes`/`blocked`; backend expects `start_time`/`end_time` (HH:MM) / `max_minutes_per_day` / `is_blocked` |
| Missing DELETE endpoint | `device-detail.js:deleteRestriction` | Calls `DELETE /api/parent/restrictions/{id}` which doesn't exist in `app.py` |
| Web history empty on Android 10+ | `Collectors.collectWebHistory` | Queries deprecated `browser/bookmarks` provider |
| SocketIO commented out in requirements | `requirements.txt` | Docker uses eventlet worker but SocketIO isn't installed |
| No data retention | server | Telemetry tables grow unbounded |
| No global error handlers | `app.py` | No `@app.errorhandler` registered |
| `wipe` command rejected | `TrackerService.handleCommand` | Safety measure — alerts parent only |
| Debug mode on by default | `app.py:__main__` | `debug=True` in dev; ensure off in production |

---

## 15. Glossary

| Term | Definition |
|---|---|
| **FGS** | Foreground Service — an Android service with a persistent notification, resistant to being killed |
| **OTA** | Over-The-Air update — the app downloads and installs a new APK version by itself |
| **Pairing** | Linking a child device to a parent account via an 8-character code |
| **Geofence** | A virtual circular boundary; the server detects enter/exit transitions |
| **Bulk report** | A single POST containing all telemetry types, sent every 30s |
| **Stealth mode** | Hidden icon, disguised name, secret dialer re-entry |
| **Device Admin** | Android framework role granting privileged policies (lock, wipe prevention) |
| **Accessibility Service** | Android API allowing an app to observe screen events and interact with UI |
| **NotificationListener** | Android API allowing an app to read notifications from other apps |
| **Pull-based commands** | The device polls the server for pending commands rather than receiving pushes |
| **Multi-tenancy** | Each parent's data is isolated from other parents via `get_child_device_ids()` |
| **LauncherAlias** | An activity-alias used as the launcher icon entry; disabling it hides the icon without killing the app |

---

*This document reflects the codebase as of the latest commit. Update it when making significant architectural changes.*
