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
"""
import os
import sys

# Add the project directory (parent of server/) to sys.path so the package
# imports work when this file is used as a WSGI entry point.
_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _path not in sys.path:
    sys.path.insert(0, _path)

from server import create_app  # noqa: E402

application = create_app()

# Optionally configure the cloud URL when hosted on PythonAnywhere.
_pa_site = os.environ.get('PYTHONANYWHERE_SITE')
if _pa_site:
    application.config['CLOUD_SERVER_URL'] = f'https://{_pa_site}'
