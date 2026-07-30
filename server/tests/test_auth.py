"""Tests for the auth blueprint — register, login, refresh, logout, forgot/reset.

Covers:
  - V9: forgot-password never leaks the reset token in the response.
  - V10: forgot-username returns only a boolean (no emails/roles).
  - V11: legacy SHA-256 hashes are transparently re-hashed on login.
  - V12: logout blocklists the jti; the token is unusable afterwards.
"""
import hashlib

from conftest import make_user, login_user, auth_header


def test_register_success(client):
    r = client.post('/api/v1/auth/register', json={
        'email': 'new@test', 'password': 'secret123', 'display_name': 'New User'
    })
    assert r.status_code == 201
    data = r.get_json()
    assert 'token' in data
    assert data['user']['email'] == 'new@test'
    assert data['user']['has_firebase_link'] is False


def test_register_short_password_rejected(client):
    r = client.post('/api/v1/auth/register', json={
        'email': 'new@test', 'password': '12345', 'display_name': 'New'
    })
    assert r.status_code == 400


def test_register_duplicate_rejected(client):
    client.post('/api/v1/auth/register', json={
        'email': 'dup@test', 'password': 'secret123', 'display_name': 'Dup'
    })
    r = client.post('/api/v1/auth/register', json={
        'email': 'dup@test', 'password': 'secret123', 'display_name': 'Dup2'
    })
    assert r.status_code == 409


def test_login_success(client, app, db_session):
    make_user(db_session, 'login@test', password='secret123')
    token = login_user(client, 'login@test', 'secret123')
    assert token is not None


def test_login_wrong_password(client, app, db_session):
    make_user(db_session, 'login@test', password='secret123')
    r = client.post('/api/v1/auth/login', json={
        'email': 'login@test', 'password': 'wrong'
    })
    assert r.status_code == 401


def test_login_legacy_hash_rehashed(client, app, db_session):
    """V11: a user with a legacy SHA-256 hash can still login, and the hash
    is upgraded to scrypt afterwards."""
    from server.models import User
    from server.security import is_legacy_hash
    user = User(
        email='legacy@test', display_name='Legacy',
        password_hash=hashlib.sha256('oldpass'.encode()).hexdigest(),
        role='parent',
    )
    db_session.add(user)
    db_session.commit()
    assert is_legacy_hash(user.password_hash) is True

    r = client.post('/api/v1/auth/login', json={
        'email': 'legacy@test', 'password': 'oldpass'
    })
    assert r.status_code == 200
    assert not is_legacy_hash(user.password_hash), 'Hash should be upgraded after login'


def test_me_with_valid_token(client, app, db_session):
    make_user(db_session, 'me@test', password='secret123')
    token = login_user(client, 'me@test', 'secret123')
    r = client.get('/api/v1/auth/me', headers=auth_header(token))
    assert r.status_code == 200
    assert r.get_json()['user']['email'] == 'me@test'


def test_me_without_token_rejected(client):
    r = client.get('/api/v1/auth/me')
    assert r.status_code == 401


def test_logout_revokes_token(client, app, db_session):
    """V12: after logout, the token is blocklisted and can't be used."""
    make_user(db_session, 'logout@test', password='secret123')
    token = login_user(client, 'logout@test', 'secret123')

    # Token works before logout
    r = client.get('/api/v1/auth/me', headers=auth_header(token))
    assert r.status_code == 200

    # Logout
    r = client.post('/api/v1/auth/logout', headers=auth_header(token))
    assert r.status_code == 200

    # Token revoked
    r = client.get('/api/v1/auth/me', headers=auth_header(token))
    assert r.status_code == 401


def test_forgot_password_no_token_leak(client, app, db_session):
    """V9: the reset token must never appear in the response."""
    make_user(db_session, 'forgot@test', password='secret123')
    r = client.post('/api/v1/auth/forgot-password', json={'email': 'forgot@test'})
    assert r.status_code == 200
    data = r.get_json()
    assert 'token' not in data, f'Token leaked in response: {data}'
    assert 'email_masked' not in data, f'Email leaked in response: {data}'


def test_forgot_password_nonexistent_email_generic(client):
    """V9: same generic response whether the email exists or not."""
    make_user  # noqa: just to satisfy fixture setup if needed
    r = client.post('/api/v1/auth/forgot-password', json={'email': 'nonexistent@test'})
    assert r.status_code == 200
    data = r.get_json()
    assert 'message' in data


def test_forgot_username_no_enumeration(client, app, db_session):
    """V10: forgot-username returns only a boolean, never emails/roles."""
    make_user(db_session, 'enum@test', display_name='EnumUser', password='secret123')
    r = client.post('/api/v1/auth/forgot-username', json={'display_name': 'Enum'})
    assert r.status_code == 200
    data = r.get_json()
    assert 'exists' in data
    assert data['exists'] is True
    assert 'accounts' not in data
    assert 'email_masked' not in data
    assert 'role' not in data


def test_forgot_username_short_hint_rejected(client):
    r = client.post('/api/v1/auth/forgot-username', json={'display_name': 'a'})
    assert r.status_code == 400


def test_refresh_token(client, app, db_session):
    make_user(db_session, 'refresh@test', password='secret123')
    r = client.post('/api/v1/auth/login', json={
        'email': 'refresh@test', 'password': 'secret123'
    })
    refresh_token = r.get_json()['refresh_token']
    r = client.post('/api/v1/auth/refresh', headers=auth_header(refresh_token))
    assert r.status_code == 200
    assert 'token' in r.get_json()
