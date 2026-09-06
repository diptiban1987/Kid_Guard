import os
from datetime import timedelta


class Config:
    """Base configuration.

    All sensitive values are read from environment variables with safe-but-
    clearly-insecure defaults so a fresh dev clone runs without setup. Production
    deployments MUST override every default via env vars or a .env file.
    """

    # ── Core secrets ──────────────────────────────────────────────────────
    # In production these MUST be set to strong random values via env vars.
    SECRET_KEY = os.environ.get('SECRET_KEY', 'dev-insecure-change-me')
    JWT_SECRET_KEY = os.environ.get('JWT_SECRET_KEY', 'dev-insecure-change-me-jwt')

    # Media files are rendered in <img> tags, which cannot send Authorization
    # headers. The dashboard appends the access token as a query param; this
    # location setup makes flask-jwt-extended accept it (Windows: harmless,
    # header auth still enforced for every other API route).
    JWT_TOKEN_LOCATION = ['headers', 'query_string']
    JWT_QUERY_STRING_NAME = 'token'

    # ── Token lifetimes ───────────────────────────────────────────────────
    # Reduced from the original 30/90-day windows for multi-user safety.
    # Configurable via env so long-lived kiosk scenarios still work.
    JWT_ACCESS_TOKEN_EXPIRES = timedelta(
        minutes=int(os.environ.get('ACCESS_TOKEN_TTL_MINUTES', '60'))
    )
    JWT_REFRESH_TOKEN_EXPIRES = timedelta(
        days=int(os.environ.get('REFRESH_TOKEN_TTL_DAYS', '7'))
    )

    # ── Database ──────────────────────────────────────────────────────────
    # Postgres in production (set DATABASE_URL), SQLite fallback for dev.
    SQLALCHEMY_DATABASE_URI = os.environ.get(
        'DATABASE_URL',
        'sqlite:///' + os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tracking.db')
    )
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    SQLALCHEMY_ENGINE_OPTIONS = {
        'pool_pre_ping': True,   # recover from dropped connections
        'pool_recycle': 300,    # recycle connections every 5 min
    }

    # ── Uploads ───────────────────────────────────────────────────────────
    UPLOAD_FOLDER = os.environ.get(
        'UPLOAD_FOLDER',
        os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'uploads')
    )
    MAX_CONTENT_LENGTH = 50 * 1024 * 1024  # 50MB

    # ── Server URL (for self-referencing links in API responses) ─────────
    CLOUD_SERVER_URL = os.environ.get('CLOUD_SERVER_URL', 'http://localhost:5000')

    # ── Pairing ──────────────────────────────────────────────────────────
    PAIRING_CODE_TTL = int(os.environ.get('PAIRING_CODE_TTL', '600'))  # seconds

    # ── Geofence ─────────────────────────────────────────────────────────
    GEO_FENCE_DEFAULT_RADIUS = 500  # meters

    # ── Rate limiting (flask-limiter) ─────────────────────────────────────
    # Redis in production, in-memory in dev.
    RATELIMIT_STORAGE_URI = os.environ.get('REDIS_URL', 'memory://')
    RATELIMIT_STRATEGY = 'fixed-window'

    # ── Password reset email (SMTP) ──────────────────────────────────────
    # When MAIL_SERVER is unset, reset tokens are logged server-side (dev only).
    MAIL_SERVER = os.environ.get('MAIL_SERVER', '')
    MAIL_PORT = int(os.environ.get('MAIL_PORT', '587'))
    MAIL_USE_TLS = os.environ.get('MAIL_USE_TLS', '1') == '1'
    MAIL_USERNAME = os.environ.get('MAIL_USERNAME', '')
    MAIL_PASSWORD = os.environ.get('MAIL_PASSWORD', '')
    MAIL_FROM = os.environ.get('MAIL_FROM', 'noreply@kidguard.local')

    # ── Admin bootstrap ─────────────────────────────────────────────────
    # scripts/create_admin.py reads these to provision the first admin.
    ADMIN_EMAIL = os.environ.get('ADMIN_EMAIL', '')
    ADMIN_PASSWORD = os.environ.get('ADMIN_PASSWORD', '')

    # ── SocketIO ─────────────────────────────────────────────────────────
    # Auto-detected in create_app(); this flag forces it off.
    DISABLE_SOCKETIO = os.environ.get('DISABLE_SOCKETIO', '') == '1'
