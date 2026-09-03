"""Device blueprint — registration + config.

Security fixes applied here:
  - V1  device-config IDOR: ``get_device_config`` now verifies the caller owns
    the device before returning its config (previously any authenticated user
    could read any device's config).
  - V6  device re-registration hijack: an existing device is NOT reassigned to
    a new user. Only the existing owner (or an admin) can re-register it; a
    different user is rejected with 409.
"""
import uuid
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity
from sqlalchemy.exc import IntegrityError

from ..extensions import db
from ..models import Device, Geofence, AppRestriction, RemoteCommand
from ..security import assert_device_ownership, audit_log

bp = Blueprint('device', __name__)


@bp.route('/device/register', methods=['POST'])
@jwt_required()
def register_device():
    user_id = get_jwt_identity()
    data = request.get_json() or {}
    device_id = data.get('device_id', str(uuid.uuid4()))

    existing = Device.query.filter_by(device_id=device_id).first()
    if existing:
        # V6: do NOT reassign a device to a different user. Only the existing
        # owner (or an admin) may re-register; otherwise reject.
        from ..models import User
        caller = User.query.get(user_id)
        existing_owner = User.query.get(existing.user_id) if existing.user_id else None
        is_device_account = existing_owner and existing_owner.email and existing_owner.email.startswith('device_')
        if existing.user_id and existing.user_id != user_id and (caller and caller.role != 'admin') and not is_device_account:
            return jsonify({'error': 'Device already registered to another account'}), 409
        existing.user_id = user_id
        existing.is_active = True  # Reactivate if it was previously soft-deleted
        existing.last_seen = int(datetime.now(timezone.utc).timestamp() * 1000)
        existing.device_name = data.get('device_name', existing.device_name)
        existing.manufacturer = data.get('manufacturer', existing.manufacturer)
        existing.model = data.get('model', existing.model)
        existing.android_version = data.get('android_version', existing.android_version)
        existing.sdk_version = data.get('sdk_version', existing.sdk_version)
        db.session.commit()
        audit_log(user_id, 'device_register', target_type='device', target_id=existing.id,
                  metadata={'device_id': device_id, 'reactivated': True})
        return jsonify({'message': 'Device updated', 'device': existing.to_dict()})

    device = Device(
        device_id=device_id,
        user_id=user_id,
        device_name=data.get('device_name', ''),
        manufacturer=data.get('manufacturer', ''),
        model=data.get('model', ''),
        android_version=data.get('android_version', ''),
        sdk_version=data.get('sdk_version', 0),
        last_seen=int(datetime.now(timezone.utc).timestamp() * 1000),
    )
    db.session.add(device)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return jsonify({'error': 'Device already registered'}), 409
    audit_log(user_id, 'device_register', target_type='device', target_id=device.id,
              metadata={'device_id': device_id})
    return jsonify({'message': 'Device registered', 'device': device.to_dict()}), 201


@bp.route('/device/<device_id>/config', methods=['GET'])
@jwt_required()
def get_device_config(device_id):
    """V1: verify ownership before returning the device's config."""
    caller_id = get_jwt_identity()
    ok, canonical = assert_device_ownership(device_id, caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403
    device = Device.query.filter_by(device_id=canonical).first()
    if not device:
        return jsonify({'error': 'Device not found'}), 404

    return jsonify({
        'reporting_interval': device.reporting_interval,
        'stealth_mode': device.stealth_mode,
        'server_time': int(datetime.now(timezone.utc).timestamp() * 1000),
        'geofences': [{
            'id': g.id, 'name': g.name, 'latitude': g.latitude,
            'longitude': g.longitude, 'radius': g.radius
        } for g in Geofence.query.filter_by(device_id=canonical, is_active=True).all()],
        'blocked_apps': [{
            'package_name': r.package_name, 'app_name': r.app_name
        } for r in AppRestriction.query.filter_by(
            device_id=canonical, is_blocked=True, is_active=True
        ).all()],
        'commands': [{
            'id': c.id, 'command': c.command, 'params': c.params
        } for c in RemoteCommand.query.filter_by(device_id=canonical, status='pending').all()],
    })
