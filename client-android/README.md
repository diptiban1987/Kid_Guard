# KidGuard child-agent client notes

This folder contains **drop-in modules** for the Android child app. The repo
does not include the full Android project source — only the compiled
`apk/AnonChat-debug.apk` shipped to devices. Use these files in the real app
project.

## What's here

| File | Purpose |
|------|---------|
| `ApiClient.kt` | Central OkHttp client. Points at `/api/v1` directly (not the legacy `/api` redirect) and retries once after a token refresh on 401. |
| `UpdateManager.kt` | **Consent-prompted** updater. Downloads only after a visible user prompt; install goes through the Android system installer. |
| `AndroidManifest.snippet.xml` | FileProvider + `POST_NOTIFICATIONS` entries the updater needs. |
| `res/xml/file_paths.xml` | FileProvider path config. |

## Why the client changes fix intermittent fetch

1. **No more 308 redirects for API calls.** The installed app calls
   `/api/auth/...`, `/api/report/...`, etc. The server 308-redirects those to
   `/api/v1/...`. Some Android HTTP stacks drop the request **body** or the
   `Authorization` header when re-issuing a redirected POST, which made report
   calls fail intermittently while GETs looked fine. `ApiClient` builds
   `/api/v1/...` URLs directly so no redirect ever happens for API traffic.
   (Parent-dashboard browser traffic still gets the compat redirect.)

2. **Token refresh race removed.** Previously the first expired-token 401
   surfaced to callers as an empty fetch until the next scheduled sync.
   `AuthInterceptor` refreshes synchronously *once* and retries the request.

## Consent-prompted updates (the only supported flow)

`UpdateManager` intentionally has **no silent-install path**:

- `checkForUpdate()` polls `/api/v1/app/check-update` and, if newer, posts a
  high-visibility notification. Nothing downloads until the user opens the app.
- The in-app screen shows the changelog and an **"Update"** button.
- `downloadAndPromptInstall()` saves the APK to app-private storage and shows a
  "tap to install" notification. Android's own package installer shows the
  final consent screen; the user can cancel.

Trying to install without a user gesture is what turns a parental-control app
into stalkerware — don't add such a path. Google Play policy and the law in
most jurisdictions also require a **persistent, non-dismissible** on-device
indication that monitoring is active; keep whatever disclosure your app already
shows, and surface it in the updater UI too.

## Hosting reality check (why "sometimes fails" also comes from the server)

The shipped APK points at `https://diptiban2021.pythonanywhere.com`. PythonAnywhere
**free** accounts have constraints that directly cause intermittent failures:

- **No WebSockets** on free tier → SocketIO is auto-disabled server-side;
  realtime dashboard updates rely on polling (already handled by the server's
  DB-backed fallback).
- **Request/response size limits** and 30-second request cap → large media
  uploads can time out. Keep per-upload payloads small and retry with backoff.
- **Daily CPU allowance + hourly worker restarts** → in-memory state was being
  lost; the server now persists command results / mic chunks (see
  `server/migrations/versions/0003_command_persistence.py`).
- **Sleep after inactivity** on free tier → the first request after idle can
  be slow (~seconds). The client's 15s connect timeout in `ApiClient` accounts
  for this.

If you outgrow the free tier, move to paid PythonAnywhere (websockets +
always-on) or a Docker host and set `DATABASE_URL` to Postgres per
`server/docker-compose.yml`.
