"""Tests for security.py — ownership decorators, hashing, and the 12 vuln fixes.

These directly exercise the security primitives without going through HTTP,
which makes them fast and targeted at the exact logic that closes each
vulnerability.
"""
import hashlib

from conftest import make_user


# ─── V11: Password hashing (scrypt replaces SHA-256) ──────────────────────

def test_hash_password_is_scrypt(app, db_session):
    from server.security import hash_password, is_legacy_hash
    h = hash_password('mypassword')
    assert not is_legacy_hash(h), 'New hash should not be legacy SHA-256'
    assert h.startswith('scrypt:') or '$' in h, 'Should be werkzeug scrypt format'


def test_verify_password_scrypt(app, db_session):
    from server.security import hash_password, verify_password
    h = hash_password('mypassword')
    assert verify_password('mypassword', h) is True
    assert verify_password('wrong', h) is False


def test_verify_password_legacy_sha256(app, db_session):
    """Legacy SHA-256 hashes must still verify (for migration-in-place)."""
    from server.security import verify_password, is_legacy_hash
    legacy = hashlib.sha256('oldpw'.encode()).hexdigest()
    assert is_legacy_hash(legacy) is True
    assert verify_password('oldpw', legacy) is True
    assert verify_password('wrong', legacy) is False


def test_maybe_rehash_upgrades_legacy(app, db_session):
    """On successful login with a legacy hash, it should upgrade to scrypt."""
    from server.security import maybe_rehash, is_legacy_hash
    user = make_user(db_session, 'legacy@test', password='IGNORED')
    legacy = hashlib.sha256('rehashme'.encode()).hexdigest()
    user.password_hash = legacy
    db_session.commit()

    assert is_legacy_hash(user.password_hash) is True
    maybe_rehash(user, 'rehashme')
    assert not is_legacy_hash(user.password_hash), 'Should be upgraded to scrypt'

    from server.security import verify_password
    assert verify_password('rehashme', user.password_hash) is True


# ─── V1 + V2: Device ownership / report injection ───────────────────────

def _setup_two_parents_one_child_each(db_session):
    """Parent A owns child A + device A. Parent B owns child B + device B."""
    from server.models import ChildRelation, Device
    pa = make_user(db_session, 'pa@test', role='parent')
    pb = make_user(db_session, 'pb@test', role='parent')
    ca = make_user(db_session, 'ca@test', role='child')
    cb = make_user(db_session, 'cb@test', role='child')
    db_session.add_all([
        ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=True),
        ChildRelation(parent_id=pb.id, child_id=cb.id, is_active=True),
        Device(device_id='dev_a', user_id=ca.id, is_active=True),
        Device(device_id='dev_b', user_id=cb.id, is_active=True),
    ])
    db_session.commit()
    return pa, pb, ca, cb


def test_parent_can_access_own_child_device(app, db_session):
    from server.security import resolve_device_id
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    assert resolve_device_id('dev_a', pa.id) == 'dev_a'


def test_parent_cannot_access_other_child_device(app, db_session):
    """V1: Parent B must NOT resolve Parent A's device."""
    from server.security import resolve_device_id
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    assert resolve_device_id('dev_a', pb.id) is None
    assert resolve_device_id('dev_b', pa.id) is None


def test_child_owns_own_device(app, db_session):
    from server.security import assert_device_ownership
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    ok, dev = assert_device_ownership('dev_a', ca.id)
    assert ok is True
    assert dev == 'dev_a'


def test_child_cannot_report_as_other_device(app, db_session):
    """V2: Child B must not inject reports as Child A's device."""
    from server.security import assert_device_ownership
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    ok, _ = assert_device_ownership('dev_a', cb.id)
    assert ok is False


def test_parent_is_owner_of_child_device(app, db_session):
    from server.security import assert_device_ownership
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    ok, dev = assert_device_ownership('dev_a', pa.id)
    assert ok is True
    assert dev == 'dev_a'


def test_nonexistent_device_denied(app, db_session):
    from server.security import assert_device_ownership
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    ok, _ = assert_device_ownership('dev_nonexistent', pa.id)
    assert ok is False


def test_none_device_id_denied(app, db_session):
    from server.security import assert_device_ownership
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    ok, _ = assert_device_ownership(None, pa.id)
    assert ok is False


# ─── V5: Pairing-approval hijack ──────────────────────────────────────────

def test_parent_can_approve_own_pairing(app, db_session):
    from server.security import assert_pairing_ownership
    from server.models import ChildRelation
    pa = make_user(db_session, 'pa@test', role='parent')
    ca = make_user(db_session, 'ca@test', role='child')
    rel = ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=False)
    db_session.add(rel)
    db_session.commit()
    assert assert_pairing_ownership(rel.id, pa.id) is not None


def test_parent_cannot_approve_others_pairing(app, db_session):
    """V5: Parent B must not approve Parent A's pending pairing."""
    from server.security import assert_pairing_ownership
    from server.models import ChildRelation
    pa = make_user(db_session, 'pa@test', role='parent')
    pb = make_user(db_session, 'pb@test', role='parent')
    ca = make_user(db_session, 'ca@test', role='child')
    rel = ChildRelation(parent_id=pa.id, child_id=ca.id, is_active=False)
    db_session.add(rel)
    db_session.commit()
    assert assert_pairing_ownership(rel.id, pb.id) is None


# ─── V3 + V4 + V8: Command ownership ──────────────────────────────────────

def test_command_ownership_blocks_cross_parent(app, db_session):
    from server.security import assert_command_ownership
    from server.models import RemoteCommand
    pa, pb, ca, cb = _setup_two_parents_one_child_each(db_session)
    cmd = RemoteCommand(device_id='dev_a', parent_id=pa.id, command='screenshot')
    db_session.add(cmd)
    db_session.commit()
    # Parent A owns the command's device → ok
    assert assert_command_ownership(cmd.id, pa.id)[0] is True
    # Parent B does not own the command's device → blocked
    assert assert_command_ownership(cmd.id, pb.id)[0] is False
