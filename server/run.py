"""Local development entry point.

    python run.py

Runs the server with the app factory, creating tables automatically in dev.
Set ``DISABLE_SOCKETIO=1`` to disable WebSocket support (e.g. on Windows
where the threading async mode may have issues).
"""
import os
import sys

_path = os.path.dirname(os.path.abspath(__file__))
if _path not in sys.path:
    sys.path.insert(0, _path)

from server import create_app  # noqa: E402
from server import get_socketio  # noqa: E402

# Auto-create tables in dev (prod uses Alembic migrations).
os.environ.setdefault('FLASK_AUTO_CREATE', '1')

app = create_app()


if __name__ == '__main__':
    debug = os.environ.get('FLASK_DEBUG', '1') == '1'
    print('=' * 60)
    print('  ANONCHAT / KIDGUARD MULTI-USER SERVER')
    print('=' * 60)
    print(f"  Database: {app.config['SQLALCHEMY_DATABASE_URI']}")
    print(f"  Uploads:  {app.config['UPLOAD_FOLDER']}")
    print(f"  Server:   {app.config['CLOUD_SERVER_URL']}")
    print(f"  Debug:    {debug}")
    print(f"  SocketIO: {bool(get_socketio())}")
    print('=' * 60)
    print('  Admin bootstrap: python scripts/create_admin.py')
    print('  Migrations:      alembic upgrade head')
    print('=' * 60)

    sio = get_socketio()
    if sio:
        sio.run(app, host='0.0.0.0', port=5000, debug=debug, allow_unsafe_werkzeug=True)
    else:
        app.run(host='0.0.0.0', port=5000, debug=debug)
