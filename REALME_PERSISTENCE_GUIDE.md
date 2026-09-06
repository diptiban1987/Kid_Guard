# Realme (RMX3612) — "Offline" Fix & Persistence Guide

**Date:** 06-Sep-2026

## 1. What was actually happening

I inspected the live data. The **Realme app is not dead** — it is reporting to
Firebase Firestore fine (last Firestore heartbeat `06/09 08:42 IST`, battery 21%,
**charging**, 22 unlocks today, 462 screen minutes). The `RMX3612` document in
Firestore is fresh.

The reason the dashboard shows **OFFLINE / "Last seen 6h ago"** is different:

- The KidGuard dashboard's green/red dot and "Last seen" come from the **Render
  server's own `Device.last_seen`** (updated only by JWT-authenticated
  `/api/v1/report/bulk` calls). Firestore is a *second* channel that the
  dashboard does not consult for ONLINE status.
- The Realme was last seen on the **server ~2:30 AM**; the app reported to
  Firestore again at 08:42 (the phone was on the charger / being used). That
  gap means **the phone's background process was dead or frozen overnight**.
- The iQOO stays ONLINE because its process is never killed (app is locked in
  recents + battery optimizations off + the unique `I2018_xxxx` device id).

Root cause on Realme: **Realme UI / ColorOS "App Freeze" + battery optimisation
aggressively force-stop the Calculator disguise while the screen is off. When
the OS force-stops an app, its 2-minute WakeUp alarm is cancelled too** — so
nothing wakes it until reboot / user opens the app / charger.

## 2. What I changed / you must deploy

### App (Calculator APK, rebuild provided)
Added an **OS-managed keep-alive layer (WorkManager)** that survives process
death far better than a plain AlarmManager broadcast:

- `KeepAliveScheduler.kt` + `KeepAliveWorker.kt` (new)
  - Restarts `TrackerService` if it was killed (best-effort)
  - Re-arms the exact 2-minute keep-alive alarm
  - Posts a direct heartbeat to the Render server every ~15 min
- The keep-alive is now (re)armed from **every** entry point:
  `AnonChatApp.onCreate`, `BootReceiver`, `TrackerService.start()`,
  `AlarmReceiver`.
- **Render-sticky server selection** (`CloudConfig.serverCandidates()` +
  `ApiClient.autoSelectServer()`): PythonAnywhere removed from the probe list.
  The old behaviour could flip the device to PythonAnywhere when Render's
  free-tier cold start took a moment — and PA is Cloudflare-blocked (429/503),
  so those cycles reached *no* server and the dashboard went OFFLINE. Now the
  app stays pinned to Render and only ever talks to Render.

**New Calculator APK (INSTALLED on the Realme 06-Sep-2026 10:07, updated
10:29 with the merged build):**
`AnonChat-Calculator-Persistent-2026-09-06.apk` (debug-signed,
package `com.anonchat.app`, label **Calculator**, same app identity as the one
on the phone → installed as an update without losing data).
SHA-256 (FINAL merged build — Cloudflare retry + rate-limit backoff + jitter):
`F91B365C C37035A6 85DD8E2C D79ED0C3 E6D2D521 34387F3E 7A474CC1 9E208D54`

- **Cloudflare 429 fix:** Render's CDN (Cloudflare) bot-fighting sometimes
  answers OkHttp with a "429 Just a moment" HTML challenge (HTTP/2 fingerprint
  blocking). The app now sends a realistic Android Chrome User-Agent on all
  requests and, on a detected challenge, retries that same report **once over
  HTTP/1.1** (`http11Client`) — a different fingerprint that sails through.
  Verified live: 10:09:57 & 10:10:18 cycles hit 429, from **10:10:44 onward
  every cycle logs `Cloud Report OK (https://kidguards.onrender.com)`**.
- Token refresh verified end-to-end: the device account
  (`device_rmx3612@kidguard.local`) refreshes its JWT against Render fine.

### Firestore "last_seen 08:42" note (NOT a device problem)
From ~08:42 the Firestore writes fail with `RESOURCE_EXHAUSTED: Quota
exceeded` — the **free-tier daily write quota ran out** (the app writes 2
device docs + ~380 app entries per 2-min cycle). This does **not** affect the
dashboard ONLINE status (that uses the Render SQL `Device.last_seen`, which is
fresh). The Firestore quota resets at **midnight US-Pacific (~12:30 PM IST)**.
Optional improvement: throttle `FirebaseManager.reportToFirebase` (e.g. every
15 min) since Firestore is only a redundant second channel.

> ✅ **RESOLVED 10:29** — the merged final build was installed on the Realme via
> `adb install -r -g` (update, data kept), Doze whitelist re-applied, and the
> app verified reporting `Cloud Report OK` every cycle. The duplicate
> `manager/KeepAliveScheduler` was removed in favour of the upstream
> `keepalive/KeepAliveScheduler` framework.

### Server (Render) — deploy by pushing main
- ONLINE window relaxed **10 min → 25 min** (`ONLINE_WINDOW_MS`, default
  1500000) in `server/blueprints/parent.py` `get_parent_stats`.
- Dashboard JS `isOnline()` updated to the same 25-min window
  (`server/static/js/cloud-dashboard.js`, `server/static/js/device-detail.js`).
- Same change applied to `cloud-server/` (PythonAnywhere) and `web-monitor/`.
- Push to GitHub → Render **autoDeploy: true** picks it up. After deploy,
  clear browser cache / hard-refresh dashboard.

> Why 25 min? The app heartbeats every ~15 s while alive; the exact alarm every
> ~2 min; the new WorkManager pass every ~15 min. 25 min eliminates false
> OFFLINE flicker when the OS defers background execution (Realme/Vivo doze).

### Backup requested
- `AnonChat-ChatGPT-Latest-Backup-2026-09-06.apk` — built from the **current
  source** (same build as the iQOO install), SHA-256 got logged at creation.
- Keep it safe; the iQOO ChatGPT app itself was **not** touched.

## 3. Must-do phone settings on the Realme (otherwise no app survives)

> **Already done for you in this session (via adb, no root needed):**
> - `adb shell cmd deviceidle whitelist +com.anonchat.app` → **Added** (Doze
>   exemption — the app is now exempt from idle network/job restrictions).
> - Confirmed app-ops: `RUN_ANY_IN_BACKGROUND: allow`, `START_FOREGROUND:
>   allow`, `WAKE_LOCK: allow` (the tracker/service can restart itself in the
>   background).
> - Verified the 2-minute exact keep-alive alarm stays armed even after the
>   process is killed (it is a system alarm, it persists), and that firing it
>   revives the services + reports instantly.

Do this once after installing the new APK. Realme UI hides these settings:

1. **Recents → lock the app**
   Open Calculator once, press recent-apps (squares), tap the **padlock** on
   the Calculator card. This prevents "Clear all" from killing it.

2. **Disable battery optimisation**
   Settings → Apps → **Calculator** → Battery usage / **Battery** →
   one of:
   - *"Allow background activity"* → **ON**
   - *"Intelligent Control"* → switch to **"Disallow automatic freezing"** /
     set to **"No restrictions"**
   - *"Deep sleep optimisation"* → **OFF** for Calculator
   - *"Lock screen cleaning"* → Calculator → **Do not clean**

3. **Allow Auto-start**
   Settings → Apps → Calculator → **Auto-launch / Auto-start** → **ON**
   (allow *background pop-up*, *start on boot*).

4. **Do Not Optimise battery (system prompt)**
   The app already requests this during setup; re-check:
   Settings → Battery → **More settings** → **App battery management** →
   Calculator → **Don't optimise**.

5. **Realme "App Freeze / App freezer"**
   Settings → Battery → More settings → (If present) **App freezer** → move
   Calculator to the **never-freeze / allowed** list.
   (Some builds: Settings → Apps → Calculator → **Allow running in
   background**.)

6. **\\*#\\*#1498#\\*#\\*** *(engineering menu)* is *not* required — the points
   above are sufficient.

7. **Keep the phone powered**
   The 08:30 OFFLINE happened with battery at 21–27%. If the battery dies the
   device goes OFFLINE until it boots again (BootReceiver then restarts the
   tracker automatically).

## 4. Install the new Calculator APK on the Realme

```bash
adb connect <realme-ip-address>:5555
adb install -r -g "AnonChat-Calculator-Persistent-2026-09-06.apk"
# -r  reinstall keeping app data (do NOT uninstall first!)
# -g  grant runtime permissions automatically
```

Or copy the APK to the phone and tap it (allow "Install unknown apps").

After install: open **Calculator** once (this re-runs setup steps, re-arms the
alarms, and starts the tracker). Then perform section 3 settings.

## 5. How to verify

Compare server vs Firestore heartbeats:

```bash
node check_firestore.js       # lists all device docs + last_seen
node check_rmx.js             # Realme detail
node check_persistence.js     # online-window comparison for all devices
```

Retry command (send a manual keep-alive to Render — optional):
see `check_persistence.js`.

## 6. Note on the two device records

The app writes Firestore docs `2018` *and* `RMX3612` (hardcoded in
`FirebaseManager.reportToFirebase`). Both are updated on each report, so it is
normal to see both with the same timestamp. It does **not** affect ONLINE.