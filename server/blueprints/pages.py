"""Pages blueprint — the web UI routes (login, dashboard, device view).

These are unauthenticated HTML endpoints (the templates handle auth client-side
via JWT). Rate-limited lightly (WS-2b) to blunt brute-force/scraping.
"""
import os

from flask import Blueprint, render_template, request, current_app

from ..extensions import limiter

bp = Blueprint('pages', __name__)


@bp.route('/')
@limiter.limit('10/minute')
def index():
    return render_template('login.html')


@bp.route('/dashboard')
@limiter.limit('10/minute')
def dashboard_page():
    return render_template('dashboard.html')


@bp.route('/device/<device_id>')
@limiter.limit('10/minute')
def device_page(device_id):
    return render_template('device.html', device_id=device_id,
                          has_socketio=current_app.config.get('HAS_SOCKETIO', False))


@bp.route('/static/<path:filename>')
def static_files(filename):
    """Static file fallback for environments where the WSGI server doesn't
    serve the static folder automatically (e.g. PythonAnywhere)."""
    from flask import send_from_directory
    static_dir = os.path.join(current_app.root_path, 'static')
    return send_from_directory(static_dir, filename)
