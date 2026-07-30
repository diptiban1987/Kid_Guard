"""Admin blueprint — retention cleanup, app upload, user management.

All routes require the admin role (``admin_required``). The retention-cleanup
and app-upload behaviour is preserved 1:1 from the original monolith; user
management is a new addition for the multi-user platform (list/promote/disable
users, view the audit log).
"""
import os
import json
from datetime import datetime, timezone, timedelta

from flask import Blueprint, request, jsonify, current_app

from ..extensions import db
from ..models import (
    User, LocationReport, ActivityReport, BatteryReport, SmsMessage, CallLog,
    WebHistory, SocialNotification, MediaFile, GeofenceEvent, RemoteCommand,
    AuditLog,
)
from ..security import admin_required, audit_log

bp = Blueprint('admin', __name__)


def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


# ─── Data retention / cleanup ────────────────────────────────────────────

def cleanup_old_telemetry(max_age_days=30):
    """Delete telemetry rows older than max_age_days to bound table growth.
    Returns a dict of {table: deleted_count}."""
    cutoff_ms = int((datetime.now(timezone.utc) - timedelta(days=max_age_days)).timestamp() * 1000)
    deleted = {}
    for model, ts_col in [
        (LocationReport, LocationReport.timestamp),
        (ActivityReport, ActivityReport.timestamp),
        (BatteryReport, BatteryReport.timestamp),
        (SmsMessage, SmsMessage.date),
        (CallLog, CallLog.date),
        (WebHistory, WebHistory.timestamp),
        (SocialNotification, SocialNotification.timestamp),
        (MediaFile, MediaFile.timestamp),
        (GeofenceEvent, GeofenceEvent.timestamp),
    ]:
        result = db.session.query(model).filter(ts_col < cutoff_ms).delete(synchronize_session=False)
        deleted[model.__tablename__] = result
    deleted['remote_commands'] = db.session.query(RemoteCommand).filter(
        RemoteCommand.created_at < cutoff_ms
    ).delete(synchronize_session=False)
    db.session.commit()
    return deleted


@bp.route('/admin/retention-cleanup', methods=['POST'])
@admin_required
def run_retention_cleanup():
    data = request.get_json(silent=True) or {}
    max_age_days = data.get('max_age_days', 30)
    if not isinstance(max_age_days, int) or max_age_days < 1:
        return jsonify({'error': 'max_age_days must be a positive integer'}), 400
    deleted = cleanup_old_telemetry(max_age_days)
    audit_log(_caller_id(), 'retention_cleanup', metadata={'max_age_days': max_age_days, 'deleted': deleted})
    return jsonify({'status': 'ok', 'max_age_days': max_age_days, 'deleted': deleted})


# ─── User management (new in multi-user platform) ────────────────────────

def _caller_id():
    from flask_jwt_extended import get_jwt_identity
    return get_jwt_identity()


@bp.route('/admin/users', methods=['GET'])
@admin_required
def list_users():
    users = User.query.order_by(User.created_at.desc()).all()
    return jsonify([u.to_dict() for u in users])


@bp.route('/admin/users/<user_id>/role', methods=['POST'])
@admin_required
def set_user_role(user_id):
    data = request.get_json() or {}
    role = data.get('role')
    if role not in ('parent', 'child', 'admin'):
        return jsonify({'error': 'Invalid role'}), 400
    user = User.query.get(user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    old_role = user.role
    user.role = role
    db.session.commit()
    audit_log(_caller_id(), 'user_role_change', target_type='user', target_id=user_id,
              metadata={'old': old_role, 'new': role})
    return jsonify({'status': 'ok', 'user': user.to_dict()})


@bp.route('/admin/users/<user_id>/active', methods=['POST'])
@admin_required
def set_user_active(user_id):
    data = request.get_json() or {}
    is_active = bool(data.get('is_active', True))
    user = User.query.get(user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    user.is_active = is_active
    db.session.commit()
    audit_log(_caller_id(), 'user_active_change', target_type='user', target_id=user_id,
              metadata={'is_active': is_active})
    return jsonify({'status': 'ok', 'user': user.to_dict()})


@bp.route('/admin/audit-log', methods=['GET'])
@admin_required
def get_audit_log():
    limit = request.args.get('limit', 100, type=int)
    action = request.args.get('action')
    query = AuditLog.query
    if action:
        query = query.filter_by(action=action)
    entries = query.order_by(AuditLog.created_at.desc()).limit(limit).all()
    return jsonify([{
        'id': e.id, 'actor_id': e.actor_id, 'action': e.action,
        'target_type': e.target_type, 'target_id': e.target_id,
        'ip_address': e.ip_address, 'metadata': json.loads(e.metadata_json) if e.metadata_json else None,
        'created_at': e.created_at,
    } for e in entries])
