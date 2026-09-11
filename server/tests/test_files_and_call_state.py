"""Regression tests for two dashboard bugs:

1. Dead media rows — media whose bytes were wiped by Render's ephemeral disk
   (or whose row was purged) used to 404, breaking <img> tiles referenced
   directly from activity/social payloads. The /files endpoint now serves a
   placeholder SVG tile (HTTP 200) while keeping 401/403 as real errors.

2. Zombie "Active Call" — a live call-state entry whose device died before
   reporting IDLE stayed visible forever. The /live endpoint now expires
   entries older than the self-heal TTL (the app refreshes its state every
   ~15s while a call is genuinely ongoing).
"""
from conftest import auth_header, login_user, make_user


def _setup_parent_with_device(db_session, tag='a'):
    """Parent + child + device, wired together. Returns (parent, device_id)."""
    from server.models import ChildRelation, Device
    parent = make_user(db_session, f'p{tag}@test', role='parent')
    child = make_user(db_session, f'c{tag}@test', role='child')
    device_id = f'dev_{tag}'
    db_session.add_all([
        ChildRelation(parent_id=parent.id, child_id=child.id, is_active=True),
        Device(device_id=device_id, user_id=child.id, is_active=True),
    ])
    db_session.commit()
    return parent, device_id


# ─── /files placeholder instead of 404 ────────────────────────────────────

def test_missing_media_row_serves_placeholder(app, client, db_session):
    parent, _ = _setup_parent_with_device(db_session)
    token = login_user(client, parent.email)
    r = client.get('/api/v1/files/med_doesnotexist', headers=auth_header(token))
    assert r.status_code == 200
    assert r.content_type.startswith('image/svg+xml')
    assert b'Media no longer available' in r.data


def test_media_bytes_gone_serves_placeholder(app, client, db_session):
    """Row survives in Postgres but /app/uploads was wiped by a redeploy."""
    from server.models import MediaFile
    parent, device_id = _setup_parent_with_device(db_session)
    db_session.add(MediaFile(
        id='med_deadbytes', device_id=device_id, media_type='photo',
        file_path='/app/uploads/long-gone.jpg', mime_type='image/jpeg',
        file_size=12345, timestamp=1700000000000,
    ))
    db_session.commit()
    token = login_user(client, parent.email)
    r = client.get('/api/v1/files/med_deadbytes', headers=auth_header(token))
    assert r.status_code == 200
    assert r.content_type.startswith('image/svg+xml')


def test_media_ownership_still_enforced(app, client, db_session):
    """The placeholder must not weaken the cross-tenant check."""
    from server.models import MediaFile
    parent_a, device_a = _setup_parent_with_device(db_session, tag='a')
    parent_b, _ = _setup_parent_with_device(db_session, tag='b')
    db_session.add(MediaFile(
        id='med_secret', device_id=device_a, media_type='photo',
        file_path='/app/uploads/secret.jpg', mime_type='image/jpeg',
    ))
    db_session.commit()
    token_b = login_user(client, parent_b.email)
    r = client.get('/api/v1/files/med_secret', headers=auth_header(token_b))
    assert r.status_code == 403


def test_media_auth_still_required(app, client, db_session):
    r = client.get('/api/v1/files/med_whatever')
    assert r.status_code == 401


def test_legacy_api_files_redirects_to_v1(client):
    """The dashboard builds /api/files/<id> URLs; the compat layer must keep
    the query string (the ?token= JWT) when redirecting to /api/v1."""
    r = client.get('/api/files/med_abc?token=jwt-here')
    assert r.status_code == 308
    assert r.headers['Location'].startswith('/api/v1/files/med_abc?token=jwt-here')


# ─── /live zombie call expiry ─────────────────────────────────────────────

def test_live_call_state_expires_stale_zombie(app, client, db_session):
    """Entry older than the self-heal TTL (app re-POSTs every ~15s while a
    call is ongoing) must come back as state=0, not a days-old ghost call."""
    from server.extensions import live_call_state
    parent, device_id = _setup_parent_with_device(db_session)
    token = login_user(client, parent.email)

    live_call_state[device_id] = {
        'state': 2, 'phone_number': '', 'streaming': False,
        'timestamp': 1700000000000,  # Nov 2023 — infinitely stale
    }
    try:
        r = client.get(f'/api/v1/parent/calls/{device_id}/live', headers=auth_header(token))
        assert r.status_code == 200
        data = r.get_json()
        assert data['state'] == 0, f'zombie call not expired: {data}'
        assert device_id not in live_call_state, 'stale entry should be evicted'
    finally:
        live_call_state.pop(device_id, None)


def test_live_call_state_fresh_call_still_visible(app, client, db_session):
    import time
    from server.extensions import live_call_state
    from server.blueprints.parent import _CALL_STATE_TTL_MS
    parent, device_id = _setup_parent_with_device(db_session)
    token = login_user(client, parent.email)

    # Reported 9 minutes ago — inside the 10-minute TTL, must still show.
    fresh_ts = int(time.time() * 1000) - (_CALL_STATE_TTL_MS - 60_000)
    live_call_state[device_id] = {
        'state': 2, 'phone_number': '+15551234', 'streaming': False,
        'timestamp': fresh_ts,
    }
    try:
        r = client.get(f'/api/v1/parent/calls/{device_id}/live', headers=auth_header(token))
        data = r.get_json()
        assert data['state'] == 2
        assert data['phone_number'] == '+15551234'
    finally:
        live_call_state.pop(device_id, None)


def test_live_call_state_defaults_empty(app, client, db_session):
    parent, device_id = _setup_parent_with_device(db_session)
    token = login_user(client, parent.email)
    r = client.get(f'/api/v1/parent/calls/{device_id}/live', headers=auth_header(token))
    assert r.status_code == 200
    assert r.get_json()['state'] == 0
