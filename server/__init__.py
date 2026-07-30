"""AnonChat / KidGuard cloud server — Flask application factory.

Replaces the monolithic ``app.py`` with a package whose blueprints each own a
concern. The factory wires extensions, registers blueprints, sets up error
handlers, and auto-detects SocketIO availability (disabled on PythonAnywhere).

API versioning: all blueprints are mounted under ``/api/v1``. A compatibility
redirect maps legacy ``/api/*`` paths to ``/api/v1/*`` so the existing Android
client keeps working without a rebuild.
"""
import os

from flask import Flask, redirect, url_for
from dotenv import load_dotenv

from .config import Config
from .extensions import db, jwt, cors, limiter

load_dotenv()

# SocketIO is optional — PythonAnywhere WSGI does NOT support WebSockets.
try:
    from flask_socketio import SocketIO
    _SOCKETIO_INSTALLED = True
except ImportError:
    _SOCKETIO_INSTALLED = False

_HAS_SOCKETIO = False
socketio = None


def create_app(config_class=Config):
    """Application factory."""
    app = Flask(__name__)
    app.config.from_object(config_class)

    # ── Trust the reverse proxy (nginx) ─────────────────────────────────
    # Without this, request.remote_addr is always the proxy's IP, so per-IP
    # rate limits and audit-log IPs collapse to a single shared address.
    # x_for=1 trusts one proxy hop (nginx sets X-Forwarded-For).
    from werkzeug.middleware.proxy_fix import ProxyFix
    app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)

    # ── Extensions ──────────────────────────────────────────────────────
    db.init_app(app)
    jwt.init_app(app)
    cors.init_app(app, supports_credentials=True)
    limiter.init_app(app)

    os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)

    # ── SQLite hardening (dev / fallback DB only) ────────────────────────
    # Enable WAL + a busy timeout so concurrent device reports don't fail with
    # "database is locked" 500s. No-op for Postgres (production). The engine is
    # created lazily by Flask-SQLAlchemy, so attach the pragma listener once the
    # engine exists on first real use, inside an app context.
    uri = app.config.get('SQLALCHEMY_DATABASE_URI', '')
    if uri.startswith('sqlite'):
        from sqlalchemy import event

        with app.app_context():
            @event.listens_for(db.engine, 'connect')
            def _sqlite_pragmas(dbapi_conn, _):
                cur = dbapi_conn.cursor()
                cur.execute('PRAGMA journal_mode=WAL')
                cur.execute('PRAGMA busy_timeout=5000')
                cur.execute('PRAGMA synchronous=NORMAL')
                cur.close()

            # Open + close one connection so the pragmas take effect immediately.
            db.engine.connect().close()

    # ── SocketIO detection ──────────────────────────────────────────────
    global _HAS_SOCKETIO, socketio
    _IS_PYTHONANYWHERE = bool(os.environ.get('PYTHONANYWHERE_SITE'))
    if (_SOCKETIO_INSTALLED and not _IS_PYTHONANYWHERE
            and not app.config.get('DISABLE_SOCKETIO')):
        _HAS_SOCKETIO = True
        socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')
    else:
        _HAS_SOCKETIO = False
        socketio = None
        if _IS_PYTHONANYWHERE:
            app.logger.info("[SocketIO] Disabled — PythonAnywhere WSGI does not support WebSockets.")
        elif app.config.get('DISABLE_SOCKETIO'):
            app.logger.info("[SocketIO] Disabled — DISABLE_SOCKETIO env var is set.")
        elif not _SOCKETIO_INSTALLED:
            app.logger.info("[SocketIO] Disabled — flask-socketio not installed.")

    # Expose for templates / blueprints that need to know
    app.config['HAS_SOCKETIO'] = _HAS_SOCKETIO

    # ── JWT blocklist callback (token revocation — fixes V12) ──────────
    @jwt.token_in_blocklist_loader
    def check_if_token_revoked(jwt_header, jwt_payload):
        from .models import TokenBlocklist
        jti = jwt_payload['jti']
        blocked = TokenBlocklist.query.filter_by(jti=jti).first()
        return blocked is not None

    # ── Register blueprints (mounted under /api/v1) ─────────────────────
    # Each blueprint's routes already include their section prefix (e.g.
    # /auth/register, /pairing/generate), so the url_prefix is just /api/v1.
    from .blueprints.auth import bp as auth_bp
    from .blueprints.pairing import bp as pairing_bp
    from .blueprints.device import bp as device_bp
    from .blueprints.reports import bp as reports_bp
    from .blueprints.parent import bp as parent_bp
    from .blueprints.command import bp as command_bp
    from .blueprints.admin import bp as admin_bp
    from .blueprints.app_mgmt import bp as app_mgmt_bp
    from .blueprints.files import bp as files_bp
    from .blueprints.pages import bp as pages_bp

    API_V1 = '/api/v1'
    app.register_blueprint(auth_bp, url_prefix=API_V1)
    app.register_blueprint(pairing_bp, url_prefix=API_V1)
    app.register_blueprint(device_bp, url_prefix=API_V1)
    app.register_blueprint(reports_bp, url_prefix=API_V1)
    app.register_blueprint(parent_bp, url_prefix=API_V1)
    app.register_blueprint(command_bp, url_prefix=API_V1)
    app.register_blueprint(admin_bp, url_prefix=API_V1)
    app.register_blueprint(app_mgmt_bp, url_prefix=API_V1)
    app.register_blueprint(files_bp, url_prefix=API_V1)
    # Pages are at the root (web UI), not under /api.
    app.register_blueprint(pages_bp)

    # ── Legacy /api/* → /api/v1/* compat redirect ───────────────────────
    # Preserves compatibility with the installed Android client so it keeps
    # working against the restructured server without a rebuild.
    @app.route('/api/<path:rest>', methods=['GET', 'POST', 'PUT', 'DELETE', 'PATCH'])
    def _api_compat_redirect(rest):
        from flask import request
        target = f'/api/v1/{rest}'
        if request.query_string:
            target += '?' + request.query_string.decode()
        return redirect(target, code=308)

    # ── Structured request logging ──────────────────────────────────────
    # Emits one line per non-2xx API response with a request id, method, path,
    # status, and latency so intermittent failures (429/403/500) are greppable
    # instead of silent. The request id is also returned to the client in the
    # X-Request-ID header for correlation.
    import time as _time
    import uuid as _uuid
    from flask import request as _request, g as _g

    @app.before_request
    def _req_start():
        _g._req_start = _time.time()
        _g.request_id = _request.headers.get('X-Request-ID') or _uuid.uuid4().hex[:12]

    @app.after_request
    def _req_log(response):
        try:
            latency_ms = int((_time.time() - getattr(_g, '_req_start', _time.time())) * 1000)
            rid = getattr(_g, 'request_id', '-')
            response.headers['X-Request-ID'] = rid
            if response.status_code >= 400:
                app.logger.warning(
                    'req id=%s %s %s -> %s (%dms) ip=%s',
                    rid, _request.method, _request.path,
                    response.status_code, latency_ms, _request.remote_addr,
                )
        except Exception:
            pass
        return response

    # ── Error handlers ──────────────────────────────────────────────────
    from flask import jsonify

    @app.errorhandler(400)
    def bad_request(e):
        return jsonify({'error': 'Bad request', 'detail': str(e)}), 400

    @app.errorhandler(401)
    def unauthorized(e):
        return jsonify({'error': 'Unauthorized'}), 401

    @app.errorhandler(403)
    def forbidden(e):
        return jsonify({'error': 'Forbidden'}), 403

    @app.errorhandler(404)
    def not_found(e):
        return jsonify({'error': 'Not found'}), 404

    @app.errorhandler(405)
    def method_not_allowed(e):
        return jsonify({'error': 'Method not allowed'}), 405

    @app.errorhandler(413)
    def payload_too_large(e):
        return jsonify({'error': 'Payload too large (max 50MB)'}), 413

    @app.errorhandler(429)
    def rate_limit_exceeded(e):
        return jsonify({'error': 'Too many requests', 'detail': str(e.description)}), 429

    @app.errorhandler(500)
    def internal_error(e):
        db.session.rollback()
        return jsonify({'error': 'Internal server error'}), 500

    @app.errorhandler(Exception)
    def unhandled_exception(e):
        db.session.rollback()
        app.logger.exception("Unhandled exception")
        return jsonify({'error': 'Internal server error'}), 500

    # ── Dev: auto-create tables (prod uses Alembic) ─────────────────────
    if app.config.get('FLASK_DEBUG') or os.environ.get('FLASK_AUTO_CREATE') == '1':
        with app.app_context():
            db.create_all()

    return app


def get_socketio():
    """Return the SocketIO instance if available, else None."""
    return socketio if _HAS_SOCKETIO else None
