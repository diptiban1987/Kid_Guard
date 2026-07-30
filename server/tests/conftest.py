"""Pytest configuration for the AnonChat / KidGuard server tests.

Each test runs against a fresh in-memory SQLite database. The app factory is
invoked with a test config that isolates uploads + disables SocketIO. A helper
``make_user`` simplifies creating parent/child accounts.
"""
import os
import sys
import tempfile

import pytest

# Ensure the server package is importable.
_here = os.path.dirname(os.path.abspath(__file__))
_server = os.path.dirname(_here)
_parent = os.path.dirname(_server)
for p in (_parent, _server):
    if p not in sys.path:
        sys.path.insert(0, p)

# Use in-memory SQLite + disable SocketIO for every test.
os.environ['DATABASE_URL'] = 'sqlite:///:memory:'
os.environ['DISABLE_SOCKETIO'] = '1'
os.environ['FLASK_AUTO_CREATE'] = '1'
os.environ['SECRET_KEY'] = 'test-secret-key'
os.environ['JWT_SECRET_KEY'] = 'test-jwt-secret'


@pytest.fixture
def app():
    """Create a fresh app + DB for each test."""
    from server import create_app
    from server.extensions import db

    app = create_app()
    app.config['TESTING'] = True
    app.config['WTF_CSRF_ENABLED'] = False
    app.config['RATELIMIT_ENABLED'] = False  # disable rate limits in tests

    with app.app_context():
        db.drop_all()
        db.create_all()
        yield app
        db.session.remove()
        db.drop_all()


@pytest.fixture
def client(app):
    return app.test_client()


@pytest.fixture
def db_session(app):
    from server.extensions import db
    return db.session


def make_user(db_session, email, password='testpass123', role='parent', display_name=None):
    """Helper to create a user directly in the DB."""
    from server.models import User
    from server.security import hash_password
    user = User(
        email=email,
        password_hash=hash_password(password),
        display_name=display_name or email.split('@')[0],
        role=role,
    )
    db_session.add(user)
    db_session.commit()
    return user


def login_user(client, email, password='testpass123'):
    """Login via the API and return the access token."""
    r = client.post('/api/v1/auth/login', json={'email': email, 'password': password})
    assert r.status_code == 200, f'Login failed: {r.get_json()}'
    return r.get_json()['token']


def auth_header(token):
    return {'Authorization': f'Bearer {token}'}
