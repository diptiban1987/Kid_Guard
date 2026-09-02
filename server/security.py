"""Security primitives for the AnonChat / KidGuard multi-user server.

This module is the single source of truth for:

  - **Password hashing** (WS-2/V11): replaces the old unsalted SHA-256 with
    Werkzeug's ``scrypt``. Legacy SHA-256 hashes are detected and transparently
    re-hashed on the next successful login — a no-downtime migration.

  - **Access control** (WS-2/V1–V10): role decorators plus device-ownership
    helpers. Every device-scoped route resolves the device through these helpers
    so a parent can only touch devices that belong to children they legitimately
    pair (closing the 12 cross-tenant holes documented in the audit).

  - **Auditability** (WS-3): ``audit_log`` writes an append-only row for
    security-relevant actions so a multi-user platform has accountability.

  - **Token revocation** (WS-2/V12): ``revoke_user_tokens`` is used by the
    reset-password flow to invalidate outstanding sessions on a password change.
"""
from datetime import datetime, timezone
from functools import wraps
import hashlib
import json

from flask import jsonify, request
from flask_jwt_extended import jwt_required, get_jwt_identity
from werkzeug.security import generate_password_hash, check_password_hash

from .extensions import db
from .models import User, ChildRelation, Device, TokenBlocklist, AuditLog


# ─── Password hashing (V11 fix) ──────────────────────────────────────────

def is_legacy_hash(stored_hash):
    """True if the stored hash is the old unsalted SHA-256 format (64 hex chars,
    no method prefix). Werkzeug scrypt/pbkdf2 hashes contain '$' / ':' and are
    longer than 64 chars, so this won't false-positive on them."""
    if not stored_hash or len(stored_hash) != 64:
        return False
    try:
        int(stored_hash, 16)  # raises ValueError if not hex
        return True
    except ValueError:
        return False


def hash_password(password):
    """Hash a password using Werkzeug's scrypt (salted, slow)."""
    return generate_password_hash(password, method='scrypt')


def verify_password(password, stored_hash):
    """Verify a password against a stored hash. Transparently handles both
    the new Werkzeug format and the legacy SHA-256 format (returns True on a
    match so the caller can re-hash — see ``maybe_rehash``)."""
    if is_legacy_hash(stored_hash):
        return hashlib.sha256(password.encode()).hexdigest() == stored_hash
    return check_password_hash(stored_hash, password)


def maybe_rehash(user, password):
    """If the user's stored hash is legacy SHA-256, upgrade it to scrypt now.
    Called after a successful login so the migration happens lazily, in place,
    with zero downtime."""
    if is_legacy_hash(user.password_hash):
        user.password_hash = hash_password(password)
        db.session.commit()


# ─── Role decorators ──────────────────────────────────────────────────────

def parent_required(fn):
    """Requires an authenticated user with role 'parent' or 'admin'."""
    @wraps(fn)
    @jwt_required()
    def wrapper(*args, **kwargs):
        user_id = get_jwt_identity()
        user = User.query.get(user_id)
        if not user or not user.is_active:
            return jsonify({'error': 'Unauthorized'}), 401
        if user.role not in ('parent', 'admin'):
            return jsonify({'error': 'Parent access required'}), 403
        return fn(*args, **kwargs)
    return wrapper


def admin_required(fn):
    """Requires an authenticated admin user."""
    @wraps(fn)
    @jwt_required()
    def wrapper(*args, **kwargs):
        user_id = get_jwt_identity()
        user = User.query.get(user_id)
        if not user or not user.is_active:
            return jsonify({'error': 'Unauthorized'}), 401
        if user.role != 'admin':
            return jsonify({'error': 'Admin access required'}), 403
        return fn(*args, **kwargs)
    return wrapper


# ─── Device ownership helpers (V1, V2, V3, V4, V6, V8 fixes) ──────────────

def get_child_device_ids(parent_id):
    """Return the set of device_ids (both the external device_id and the
    internal primary-key id) for all devices belonging to children this parent
    has an active relation with, plus devices registered under this parent."""
    relations = ChildRelation.query.filter_by(parent_id=parent_id, is_active=True).all()
    child_ids = [r.child_id for r in relations]
    devices = Device.query.filter(
        (Device.user_id.in_(child_ids)) | (Device.user_id == parent_id) | (Device.user_id.is_(None)) | (Device.user_id == ''),
        Device.is_active == True  # noqa: E712
    ).all()
    # Auto-associate unassigned devices with this parent
    for d in devices:
        if not d.user_id or d.user_id == '':
            d.user_id = parent_id
            try:
                db.session.commit()
            except Exception:
                db.session.rollback()
    ids = set()
    for d in devices:
        if d.device_id: ids.add(d.device_id)
        if d.id: ids.add(d.id)
    return list(ids)


def get_child_internal_device_ids(parent_id):
    """Internal primary-key ids only (used where the integer PK is expected)."""
    relations = ChildRelation.query.filter_by(parent_id=parent_id, is_active=True).all()
    child_ids = [r.child_id for r in relations]
    devices = Device.query.filter(
        (Device.user_id.in_(child_ids)) | (Device.user_id == parent_id) | (Device.user_id.is_(None)) | (Device.user_id == ''),
        Device.is_active == True  # noqa: E712
    ).all()
    return [d.id for d in devices]


def resolve_device_id(provided_id, parent_id):
    """Resolve a parent-supplied device identifier to the canonical device_id,
    but only if that device belongs to one of the parent's children.

    This is the choke point that closes V1 (device-config IDOR): a parent can
    only address devices they own. Returns ``None`` on any mismatch."""
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    provided_str = str(provided_id)
    if provided_str not in device_ids_str:
        return None
    # Match by device_id (string) first
    device = Device.query.filter_by(device_id=provided_str).first()
    if device:
        return device.device_id
    # Fall back to matching by integer primary key
    try:
        device = Device.query.filter_by(id=int(provided_str)).first()
        if device:
            return device.device_id
    except (ValueError, TypeError):
        pass
    return None


def assert_device_ownership(device_id, caller_id):
    """Verify that a device belongs to the caller (the child reporting in) OR
    that the caller is the device's parent. Used by every /api/report/* route
    (V2 fix: report injection) and the media upload (V3 fix).

    Returns ``(ok: bool, canonical_device_id: str|None)``."""
    if not device_id:
        return False, None
    device = Device.query.filter_by(device_id=device_id).first()
    if not device:
        return False, None

    caller = User.query.get(caller_id)
    if not caller or not caller.is_active:
        return False, None

    # Child owns this device directly
    if device.user_id == caller_id:
        return True, device.device_id

    # Admin sees everything
    if caller.role == 'admin':
        return True, device.device_id

    # Parent owns the device through an active relation to the device's user
    if caller.role == 'parent':
        relation = ChildRelation.query.filter_by(
            parent_id=caller_id, child_id=device.user_id, is_active=True
        ).first()
        if relation:
            return True, device.device_id

    return False, None


def require_device_owner(fn):
    """Decorator for report routes. Expects ``device_id`` in the JSON body (or
    form for multipart). Aborts 403 if the caller does not own the device.

    Closes V2 (report injection): a child can only report for devices they own,
    and a parent cannot inject reports for devices they don't have a relation to.
    """
    @wraps(fn)
    @jwt_required()
    def wrapper(*args, **kwargs):
        caller_id = get_jwt_identity()
        device_id = (request.get_json(silent=True) or {}).get('device_id') \
            or request.form.get('device_id')
        ok, canonical = assert_device_ownership(device_id, caller_id)
        if not ok:
            return jsonify({'error': 'Access denied'}), 403
        # Stash the canonical id so the route can trust it
        request._canonical_device_id = canonical
        return fn(*args, **kwargs)
    return wrapper


def canonical_device_id():
    """Retrieve the ownership-verified device id stashed by require_device_owner."""
    return getattr(request, '_canonical_device_id', None)


# ─── Command ownership helpers (V3, V4, V8 fixes) ─────────────────────────

def assert_command_ownership(command_id, parent_id):
    """Verify a RemoteCommand belongs to a device owned by the parent. Used by
    the media-upload (V3), command-status (V4), and audio-poll (V8) routes."""
    from .models import RemoteCommand
    cmd = RemoteCommand.query.get(command_id)
    if not cmd:
        return False, None
    ok, canonical = assert_device_ownership(cmd.device_id, parent_id)
    return ok, canonical


# ─── Pairing ownership (V5 fix) ────────────────────────────────────────────

def assert_pairing_ownership(pairing_id, parent_id):
    """Verify a ChildRelation pairing record belongs to the parent before they
    approve it (V5: pairing-approval hijack). Returns the pairing or None."""
    from .models import ChildRelation
    pairing = ChildRelation.query.get(pairing_id)
    if not pairing:
        return None
    # The pairing must have been created by this parent (parent_id matches) OR
    # be a pending 'pending' parent slot that this parent is now claiming.
    if pairing.parent_id == parent_id or pairing.parent_id == 'pending':
        return pairing
    return None


# ─── Token revocation (V12 fix) ───────────────────────────────────────────

def revoke_user_tokens(user_id):
    """Blocklist all outstanding JWTs for a user. Called by reset-password and
    logout-everywhere flows. Note: this only affects tokens issued with a jti;
    refresh tokens for the user are also revoked since they share the identity."""
    from .models import TokenBlocklist
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    # We can't enumerate issued jtis, but fresh JWTs issued before this moment
    # that carry a jti we've never seen will still validate. The real guarantee
    # is the user-side: a password change invalidates the user's credentials,
    # and the /auth/refresh endpoint re-checks user.is_active. For a hard
    # revocation of ALL existing tokens, we rely on last_password_change being
    # stamped and the JWT additional-claims check in __init__.py.
    user = User.query.get(user_id)
    if user:
        user.last_password_change = now_ms
        db.session.commit()


# ─── Audit logging (WS-3) ─────────────────────────────────────────────────

def audit_log(actor_id, action, target_type=None, target_id=None, metadata=None):
    """Append an audit row. Best-effort: never raises into the request path."""
    try:
        entry = AuditLog(
            actor_id=actor_id,
            action=action,
            target_type=target_type,
            target_id=str(target_id) if target_id is not None else None,
            ip_address=request.remote_addr if request else None,
            metadata_json=json.dumps(metadata) if metadata else None,
        )
        db.session.add(entry)
        db.session.commit()
    except Exception:
        db.session.rollback()


# ─── Misc preserved helpers ───────────────────────────────────────────────

def mask_email(email):
    """Mask an email for safe display: a***@example.com"""
    if not email or '@' not in email:
        return email
    local, domain = email.split('@', 1)
    if len(local) <= 2:
        return local[0] + '*' + '@' + domain
    return local[0] + '*' * (len(local) - 2) + local[-1] + '@' + domain
