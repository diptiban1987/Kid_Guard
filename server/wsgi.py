"""
WSGI entry point for PythonAnywhere / production.

The server is now a Flask package (``server``). This file creates the app via
the ``create_app()`` factory and exposes it as ``application`` for any WSGI
server (PythonAnywhere, Gunicorn, uWSGI).

For PythonAnywhere:
  1. Set your web app's WSGI config to import this file's ``application``.
  2. Ensure the parent of this ``server/`` directory is on ``sys.path`` so
     ``import server`` works (handled below).
  3. Set environment variables for DATABASE_URL, JWT_SECRET, etc. in the
     PythonAnywhere "Web" tab or a ``.env`` file alongside this file.

Database note for PythonAnywhere free tier:
  SQLite on PythonAnywhere's network filesystem frequently raises
  ``sqlite3.OperationalError: disk I/O error`` on writes (NFS + SQLite file
  locking don't mix well). PythonAnywhere's own guidance is to use the free
  MySQL database they provision for web apps. If you have not set
  ``DATABASE_URL`` explicitly, this entry point auto-detects the PythonAnywhere
  MySQL environment variables (``PA_MYSQL_DATABASE``, etc.) and connects to
  MySQL instead — avoiding the SQLite disk-I/O class of failures entirely.

  To force SQLite (NOT recommended on PA), explicitly set
  ``DATABASE_URL=sqlite:////home/<user>/kidguard_data/tracking.db`` AND chmod
  the parent directory for the WAL/-shm files.
"""
import os
import sys

# Add the project directory (parent of server/) to sys.path so the package
# imports work when this file is used as a WSGI entry point.
_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _path not in sys.path:
    sys.path.insert(0, _path)


def _auto_pa_mysql_url() -> str | None:
    """Build a SQLAlchemy ``mysql+pymysql://`` URL from the PythonAnywhere
    MySQL env vars IF they're set AND no DATABASE_URL has been specified.

    PythonAnywhere free tier provisions ONE MySQL database per user. The
    connection details are exposed as environment variables inside the WSGI
    process — typically ``PA_MYSQL_DATABASE``, ``PA_MYSQL_USER``, and either
    ``PA_MYSQL_PASSWORD`` or a file at ``/etc/mysql-db-credentials``. We map
    the common variants here so that a fresh deploy Just Works the moment the
    user creates their MySQL DB in the PA "Databases" tab."""
    if os.environ.get('DATABASE_URL'):
        return None

    db = os.environ.get('PA_MYSQL_DATABASE') or os.environ.get('MYSQL_DATABASE')
    user = os.environ.get('PA_MYSQL_USER') or os.environ.get('MYSQL_USER')
    passwd = os.environ.get('PA_MYSQL_PASSWORD') or os.environ.get('MYSQL_PASSWORD')
    host = os.environ.get('PA_MYSQL_HOST') or os.environ.get('MYSQL_HOST') or '127.0.0.1'
    port = os.environ.get('PA_MYSQL_PORT') or os.environ.get('MYSQL_PORT') or '3306'

    if not (db and user and passwd):
        return None
    return f'mysql+pymysql://{user}:{passwd}@{host}:{port}/{db}?charset=utf8mb4'


_mysql_url = _auto_pa_mysql_url()
if _mysql_url:
    os.environ.setdefault('DATABASE_URL', _mysql_url)

# PythonAnywhere deploys typically skip ``alembic upgrade head``. Auto-create
# any missing tables on boot so first-run deployments (or ones that stamped
# the baseline migration against a legacy schema) don't 500 on
# /api/auth/forgot-password et al. ``db.create_all()`` only creates tables that
# do not yet exist, so this is a no-op on deployments that DID run migrations.
# Override by setting FLASK_AUTO_CREATE=0 in the WSGI config.
os.environ.setdefault('FLASK_AUTO_CREATE', '1')

from server import create_app  # noqa: E402

application = create_app()

# Optionally configure the cloud URL when hosted on PythonAnywhere.
_pa_site = os.environ.get('PYTHONANYWHERE_SITE')
if _pa_site:
    application.config['CLOUD_SERVER_URL'] = f'https://{_pa_site}'
