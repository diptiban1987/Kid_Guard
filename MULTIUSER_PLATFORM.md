# Multi-User Platform Conversion

This document describes the conversion of the AnonChat / KidGuard server from a
single-user monolith (`app.py`, 2028 lines) into a hardened, multi-tenant Flask
package. All 62 original routes are preserved 1:1; the changes are security
hardening, project structure, and Postgres + Alembic migration support.

---

## What changed

### 1. Project structure (WS-1)

The monolithic `app.py` is replaced by a Flask package:

```
server/
├── __init__.py            ← create_app() factory
├── config.py              ← Config (Postgres + env vars)
├── extensions.py          ← db, jwt, cors, limiter + in-memory stores
├── models.py             ← 22 models (was 20; added TokenBlocklist, AuditLog)
├── security.py            ← hashing, ownership decorators, vuln fixes
├── blueprints/
│   ├── auth.py           ← register, login, refresh, logout, forgot/reset
│   ├── pairing.py        ← generate, claim, claim-direct, pending, approve
│   ├── device.py         ← register, config
│   ├── reports.py        ← all /api/report/* (12 endpoints)
│   ├── parent.py         ← all /api/parent/* dashboard (~25 routes)
│   ├── command.py        ← command status
│   ├── admin.py          ← retention-cleanup, user mgmt, audit log
│   ├── app_mgmt.py       ← APK check-update, download, upload
│   ├── files.py          ← media file serving
│   └── pages.py          ← web UI (login, dashboard, device)
├── migrations/            ← Alembic
│   └── versions/
│       ├── 0001_baseline.py    ← full schema reproduction
│       └── 0002_multiuser.py    ← multi-user changes
├── scripts/
│   ├── create_admin.py          ← admin bootstrap CLI
│   └── migrate_sqlite_to_postgres.py  ← ETL script
├── tests/                ← pytest scaffold (46 tests)
├── alembic.ini
├── wsgi.py               ← PythonAnywhere / WSGI entry point
├── run.py                ← local dev entry point
└── requirements.txt
```

### 2. API versioning + backward compatibility

All API routes are now under `/api/v1/*`. A **308 redirect** maps legacy
`/api/*` paths to `/api/v1/*`, so the existing installed Android client keeps
working against the new server **without a rebuild**.

### 3. Security hardening (WS-2 — 12 vulnerabilities fixed)

All fixes are centralized in `security.py`:

| # | Vulnerability | Fix |
|---|---------------|-----|
| V1 | Device-config IDOR | `resolve_device_id()` verifies the device belongs to a parent's child before returning config |
| V2 | Report injection | Every `/api/report/*` route calls `assert_device_ownership()` — device must belong to the caller or their parent |
| V3 | Media poison | `report_media` verifies a linked `command_id` belongs to a device owned by the caller |
| V4 | Command-status hijack | `update_command_status` verifies the command's device belongs to the caller |
| V5 | Pairing-approval hijack | `approve_pairing` checks `pairing.parent_id == caller` before granting |
| V6 | Device re-registration hijack | Existing devices are NOT reassigned to a new user; only the owner or admin can re-register |
| V7 | Unauthenticated claim-direct | Now requires a JWT (the device must login first); rate-limited 3/min |
| V8 | Audio-poll partial scope | `poll_mic_audio` verifies the command belongs to the resolved device |
| V9 | Reset token in response | `forgot-password` never returns the token; logs it in dev, emails it in prod |
| V10 | Account enumeration | `forgot-username` returns only `{exists: bool}` — no emails, roles, or masked addresses |
| V11 | Weak hashing (SHA-256) | Replaced with Werkzeug scrypt; legacy hashes transparently upgraded on next login |
| V12 | No token revocation | `TokenBlocklist` model + `/auth/logout` blocklists the jti; reset-password revokes all sessions |

**Rate limiting** (WS-2b): `flask-limiter` applies 5/min on auth endpoints,
3/min on pairing claim/claim-direct, 10/min on web pages. Redis-backed in prod,
in-memory in dev.

### 4. Data model updates (WS-3)

- **`User.firebase_uid`** — nullable, unique. Forward-compat hook for unifying
  chat-side Firebase auth with server JWT in a later client phase.
- **`User.last_password_change`** — timestamp; supports invalidating old sessions.
- **`TokenBlocklist`** — `id`, `jti` (unique, indexed), `user_id`, `expires_at`, `created_at`.
- **`AuditLog`** — `id`, `actor_id`, `action`, `target_type`, `target_id`, `ip_address`, `metadata_json`, `created_at`. Populated on auth, pairing, device, command, and admin events.
- **Partial unique index** on `child_relations(child_id) WHERE is_active = 1` — enforces strict-one-parent-per-child at the DB level.

### 5. Postgres + Alembic (WS-4)

- `config.py` reads `DATABASE_URL` (Postgres in prod, SQLite fallback in dev).
- Alembic migrations: `0001_baseline` (full schema) → `0002_multiuser` (new columns/tables/index).
- `scripts/migrate_sqlite_to_postgres.py` — idempotent ETL that copies the existing `tracking.db` to Postgres.
- See `MIGRATIONS_GUIDE.md` for step-by-step instructions.

### 6. Auth upgrades (WS-5)

- `hash_password` / `verify_password` in `security.py` using Werkzeug scrypt.
- **Transparent re-hashing**: legacy SHA-256 hashes are detected and upgraded on next login (no downtime).
- `/api/v1/auth/logout` (new) — blocklists the current token's jti.
- `/api/v1/auth/forgot-password` — no token in response; logs in dev, emails in prod.
- `/api/v1/auth/reset-password` — rate-limited, revokes all existing sessions.
- `scripts/create_admin.py` — CLI to bootstrap/promote an admin from env vars or args.
- Token TTLs reduced: access 60 min, refresh 7 days (was 30/90).

### 7. Admin tools (new)

- `GET /api/v1/admin/users` — list all users.
- `POST /api/v1/admin/users/<id>/role` — promote/demote.
- `POST /api/v1/admin/users/<id>/active` — enable/disable.
- `GET /api/v1/admin/audit-log` — view the audit trail (filterable by action).

### 8. Tests (WS-7)

46 pytest tests covering all 12 vulnerability fixes + auth/pairing/report flows:

```bash
cd server/tests
python -m pytest -v
```

---

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `DATABASE_URL` | `sqlite:///tracking.db` | Postgres in prod, SQLite in dev |
| `SECRET_KEY` | `dev-insecure-...` | Flask session signing |
| `JWT_SECRET_KEY` | `dev-insecure-...` | JWT signing |
| `ACCESS_TOKEN_TTL_MINUTES` | `60` | Access token lifetime |
| `REFRESH_TOKEN_TTL_DAYS` | `7` | Refresh token lifetime |
| `REDIS_URL` | `memory://` | Rate-limit storage backend |
| `MAIL_SERVER` | (empty) | SMTP for reset emails (empty = dev logging) |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | (empty) | SMTP auth |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | (empty) | Admin bootstrap |
| `DISABLE_SOCKETIO` | (empty) | Set to `1` to disable WebSocket |
| `FLASK_AUTO_CREATE` | (empty) | Set to `1` to auto-create tables (dev) |

---

## Deployment

### PythonAnywhere

1. Upload the `server/` package to your home directory.
2. Create a Postgres database (or use the built-in MySQL — set `DATABASE_URL`).
3. Install dependencies: `pip install -r server/requirements.txt`.
4. Run migrations: `cd server && python -m alembic upgrade head`.
5. Bootstrap admin: `python scripts/create_admin.py --email admin@... --password '...'`.
6. Set the WSGI config to import `server/wsgi.py`'s `application`.
7. Set env vars in the Web tab (or a `.env` file).
8. Reload the web app.

### Docker

The existing `Dockerfile` and `docker-compose.yml` work with the new structure.
The `docker-compose.yml` includes a Postgres 15 service with a volume. Set
`DATABASE_URL` to point at it.

### Local development

```bash
cd server
pip install -r requirements.txt
python run.py    # creates tables automatically, runs on :5000
```

---

## Deferred to a follow-up client phase

These are real blockers for end-to-end multi-user but are client-side and out
of scope for this server-first round (per the "Server-first, Postgres now"
decision):

1. **`CloudConfig.deviceId`** defaults to `android.os.Build.DEVICE` (collides
   across same-model phones) → needs a generated per-install UUID.
2. **No centralized auth interceptor** → only one report path refreshes on 401;
   all other calls silently fail.
3. **No in-app pairing UI** → onboarding is ADB-broadcast-only.
4. **Chat-side Firebase→server JWT unification** (the `firebase_uid` column
   added now is the hook for this).

The server is built so these can land cleanly afterward without server rework.

---

## Verification checklist

- [x] `python -m pytest -v` — 46 tests pass
- [x] `python -m alembic upgrade head` — succeeds on fresh SQLite
- [x] App factory imports cleanly, 70 routes registered under `/api/v1`
- [x] Legacy `/api/*` paths redirect to `/api/v1/*` (308)
- [x] Cross-tenant access blocked (parent A cannot read parent B's data)
- [x] Legacy SHA-256 hashes transparently re-hashed on login
- [x] Logout blocklists the token (revoked token returns 401)
- [x] Forgot-password returns no token in the response
- [x] Admin bootstrap script creates a working admin account
- [x] Audit log records auth, pairing, device, and command events
