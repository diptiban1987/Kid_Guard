"""Tests for the pairing blueprint — strict one-parent-per-child enforcement.

Covers:
  - V5: a parent cannot approve a pairing they didn't create.
  - V7: claim-direct requires authentication.
  - Strict one-parent-per-child: activating a new relation deactivates the old.
"""
from conftest import make_user, login_user, auth_header


def test_generate_pairing_requires_parent(client, app, db_session):
    """A child cannot generate pairing codes."""
    make_user(db_session, 'child@test', password='secret123', role='child')
    token = login_user(client, 'child@test', 'secret123')
    r = client.post('/api/v1/pairing/generate', headers=auth_header(token))
    assert r.status_code == 403


def test_generate_pairing_success(client, app, db_session):
    make_user(db_session, 'parent@test', password='secret123', role='parent')
    token = login_user(client, 'parent@test', 'secret123')
    r = client.post('/api/v1/pairing/generate', headers=auth_header(token))
    assert r.status_code == 200
    data = r.get_json()
    assert 'pairing_code' in data
    assert len(data['pairing_code']) == 8


def test_claim_direct_requires_auth(client):
    """V7: claim-direct without a token is rejected."""
    r = client.post('/api/v1/pairing/claim-direct', json={'pairing_code': 'ABCD1234'})
    assert r.status_code == 401


def test_pairing_approve_own(client, app, db_session):
    from server.models import ChildRelation
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')

    # Parent creates a pending pairing
    rel = ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=False)
    db_session.add(rel)
    db_session.commit()

    token = login_user(client, 'pa@test', 'secret123')
    r = client.post(f'/api/v1/pairing/approve/{rel.id}', headers=auth_header(token))
    assert r.status_code == 200

    # Verify it's active
    db_session.refresh(rel)
    assert rel.is_active is True


def test_pairing_approve_hijack_blocked(client, app, db_session):
    """V5: Parent B cannot approve Parent A's pending pairing."""
    from server.models import ChildRelation
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    pb = make_user(db_session, 'pb@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')

    # Parent A creates a pending pairing
    rel = ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=False)
    db_session.add(rel)
    db_session.commit()

    # Parent B tries to approve it
    token = login_user(client, 'pb@test', 'secret123')
    r = client.post(f'/api/v1/pairing/approve/{rel.id}', headers=auth_header(token))
    assert r.status_code == 404

    # Verify it's still inactive
    db_session.refresh(rel)
    assert rel.is_active is False


def test_strict_one_parent_per_child(client, app, db_session):
    """Activating a new relation for a child should deactivate the old one."""
    from server.models import ChildRelation
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    pb = make_user(db_session, 'pb@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')

    # Parent A has an active relation with the child
    rel_a = ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True)
    db_session.add(rel_a)
    db_session.commit()

    # Parent B creates a pending pairing for the same child
    rel_b = ChildRelation(parent_id=pb.id, child_id=ca.id, is_active=False)
    db_session.add(rel_b)
    db_session.commit()

    # Parent B approves it
    token = login_user(client, 'pb@test', 'secret123')
    r = client.post(f'/api/v1/pairing/approve/{rel_b.id}', headers=auth_header(token))
    assert r.status_code == 200

    # The old relation should be deactivated
    db_session.refresh(rel_a)
    db_session.refresh(rel_b)
    assert rel_a.is_active is False, 'Old relation should be deactivated'
    assert rel_b.is_active is True, 'New relation should be active'


def test_get_children(client, app, db_session):
    from server.models import ChildRelation, Device
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')
    db_session.add_all([
        ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True),
        Device(device_id='dev_ca', user_id=ca.id, is_active=True),
    ])
    db_session.commit()

    token = login_user(client, 'pa@test', 'secret123')
    r = client.get('/api/v1/pairing/children', headers=auth_header(token))
    assert r.status_code == 200
    children = r.get_json()
    assert len(children) == 1
    assert children[0]['child']['email'] == 'ca@test'


def test_get_pending_pairings(client, app, db_session):
    from server.models import ChildRelation
    pa = make_user(db_session, 'pa@test', password='secret123', role='parent')
    ca = make_user(db_session, 'ca@test', password='secret123', role='child')
    db_session.add(ChildRelation(
        parent_id=pa.id, child_id=ca.id, is_active=False
    ))
    db_session.commit()

    token = login_user(client, 'pa@test', 'secret123')
    r = client.get('/api/v1/pairing/pending', headers=auth_header(token))
    assert r.status_code == 200
    pending = r.get_json()
    assert len(pending) == 1
    assert pending[0]['child_email'] == 'ca@test'
