"""Tests for the reports blueprint — cross-tenant report injection is rejected.

Covers V2: a child cannot report data for a device they don't own, and a parent
cannot inject reports for devices they don't have a relation to.
"""
from conftest import make_user, login_user, auth_header


def _setup_parent_child_device(db_session):
    from server.models import ChildRelation, Device
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')
    db_session.add_all([
        ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True),
        Device(device_id='dev_ca', user_id=ca.id, is_active=True),
    ])
    db_session.commit()
    return pa, ca


def test_child_reports_own_device(client, app, db_session):
    pa, ca = _setup_parent_child_device(db_session)
    token = login_user(client, 'ca@test', 'secret123')
    r = client.post('/api/v1/report/location', headers=auth_header(token), json={
        'device_id': 'dev_ca', 'latitude': 37.77, 'longitude': -122.41
    })
    assert r.status_code == 200, r.get_json()


def test_child_cannot_report_other_device(client, app, db_session):
    """V2: child B cannot inject a location report for child A's device."""
    from server.models import ChildRelation, Device
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')
    cb = make_user(db_session, 'cb@test', password='secret123', role='child')
    db_session.add_all([
        ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True),
        Device(device_id='dev_ca', user_id=ca.id, is_active=True),
        Device(device_id='dev_cb', user_id=cb.id, is_active=True),
    ])
    db_session.commit()

    token = login_user(client, 'cb@test', 'secret123')
    r = client.post('/api/v1/report/location', headers=auth_header(token), json={
        'device_id': 'dev_ca', 'latitude': 0, 'longitude': 0
    })
    assert r.status_code == 403


def test_parent_can_report_for_child_device(client, app, db_session):
    """A parent has an ownership relation to their child's device, so they
    can report for it. The V2 fix targets UNRELATED users — see the next test."""
    pa, ca = _setup_parent_child_device(db_session)
    token = login_user(client, 'pa@test', 'secret123')
    r = client.post('/api/v1/report/location', headers=auth_header(token), json={
        'device_id': 'dev_ca', 'latitude': 0, 'longitude': 0
    })
    assert r.status_code == 200


def test_unrelated_user_cannot_report(client, app, db_session):
    """V2: a parent with NO relation to a child cannot inject reports for
    that child's device."""
    from server.models import ChildRelation, Device
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    pb = make_user(db_session, 'pb@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')
    db_session.add_all([
        ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True),
        Device(device_id='dev_ca', user_id=ca.id, is_active=True),
    ])
    db_session.commit()

    token = login_user(client, 'pb@test', 'secret123')
    r = client.post('/api/v1/report/location', headers=auth_header(token), json={
        'device_id': 'dev_ca', 'latitude': 0, 'longitude': 0
    })
    assert r.status_code == 403


def test_report_nonexistent_device_rejected(client, app, db_session):
    _setup_parent_child_device(db_session)
    ca = make_user(db_session, 'ca2@test', password='secret123', role='child')
    token = login_user(client, 'ca2@test', 'secret123')
    r = client.post('/api/v1/report/location', headers=auth_header(token), json={
        'device_id': 'nonexistent', 'latitude': 0, 'longitude': 0
    })
    assert r.status_code == 403


def test_report_battery_own_device(client, app, db_session):
    pa, ca = _setup_parent_child_device(db_session)
    token = login_user(client, 'ca@test', 'secret123')
    r = client.post('/api/v1/report/battery', headers=auth_header(token), json={
        'device_id': 'dev_ca', 'level': 85, 'is_charging': False
    })
    assert r.status_code == 200


def test_report_bulk_own_device(client, app, db_session):
    pa, ca = _setup_parent_child_device(db_session)
    token = login_user(client, 'ca@test', 'secret123')
    r = client.post('/api/v1/report/bulk', headers=auth_header(token), json={
        'device_id': 'dev_ca',
        'location': {'latitude': 37, 'longitude': -122},
        'battery': {'level': 50, 'is_charging': True},
    })
    assert r.status_code == 200
    assert r.get_json()['status'] == 'ok'


def test_report_bulk_other_device_rejected(client, app, db_session):
    from server.models import ChildRelation, Device
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')
    cb = make_user(db_session, 'cb@test', password='secret123', role='child')
    db_session.add_all([
        ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True),
        Device(device_id='dev_ca', user_id=ca.id, is_active=True),
        Device(device_id='dev_cb', user_id=cb.id, is_active=True),
    ])
    db_session.commit()

    token = login_user(client, 'cb@test', 'secret123')
    r = client.post('/api/v1/report/bulk', headers=auth_header(token), json={
        'device_id': 'dev_ca',
        'battery': {'level': 50},
    })
    assert r.status_code == 403


def test_parent_reads_child_data(client, app, db_session):
    """After a child reports data, the parent can read it."""
    pa, ca = _setup_parent_child_device(db_session)

    # Child reports a location
    child_token = login_user(client, 'ca@test', 'secret123')
    client.post('/api/v1/report/location', headers=auth_header(child_token), json={
        'device_id': 'dev_ca', 'latitude': 37.77, 'longitude': -122.41
    })

    # Parent reads it
    parent_token = login_user(client, 'pa@test', 'secret123')
    r = client.get('/api/v1/parent/locations/dev_ca', headers=auth_header(parent_token))
    assert r.status_code == 200
    locations = r.get_json()
    assert len(locations) == 1
    assert abs(locations[0]['latitude'] - 37.77) < 0.01


def test_parent_cannot_read_other_child_data(client, app, db_session):
    """Parent B cannot read Parent A's child data."""
    from server.models import ChildRelation, Device
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    pb = make_user(db_session, 'pb@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')
    db_session.add_all([
        ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True),
        Device(device_id='dev_ca', user_id=ca.id, is_active=True),
    ])
    db_session.commit()

    token = login_user(client, 'pb@test', 'secret123')
    r = client.get('/api/v1/parent/locations/dev_ca', headers=auth_header(token))
    assert r.status_code == 403
