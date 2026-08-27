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


# ─── Chat History Management (Admin Web Panel) ───────────────────────────

@bp.route('/admin/chats', methods=['GET'])
@admin_required
def get_admin_chats():
    """Retrieve archived chat history with search and filtering."""
    from ..models import ChatMessage
    q = request.args.get('q', '').strip()
    chat_id = request.args.get('chat_id', '').strip()
    user_id = request.args.get('user_id', '').strip()
    from_date = request.args.get('from_date', type=int)
    to_date = request.args.get('to_date', type=int)
    limit = min(request.args.get('limit', 200, type=int), 5000)

    query = ChatMessage.query

    if q:
        search_pat = f"%{q}%"
        query = query.filter(
            db.or_(
                ChatMessage.content.ilike(search_pat),
                ChatMessage.sender_name.ilike(search_pat),
                ChatMessage.recipient_name.ilike(search_pat)
            )
        )
    if chat_id:
        query = query.filter_by(chat_id=chat_id)
    if user_id:
        query = query.filter(
            db.or_(
                ChatMessage.sender_id == user_id,
                ChatMessage.recipient_id == user_id
            )
        )
    if from_date:
        query = query.filter(ChatMessage.timestamp >= from_date)
    if to_date:
        query = query.filter(ChatMessage.timestamp <= to_date)

    messages = query.order_by(ChatMessage.timestamp.desc()).limit(limit).all()
    return jsonify({
        'total': len(messages),
        'messages': [m.to_dict() for m in messages]
    })


@bp.route('/admin/chats/export', methods=['GET'])
@admin_required
def export_admin_chats():
    """Export chat history as CSV or JSON."""
    import csv
    import io
    from flask import Response
    from ..models import ChatMessage

    fmt = request.args.get('format', 'csv').lower()
    q = request.args.get('q', '').strip()
    query = ChatMessage.query
    if q:
        search_pat = f"%{q}%"
        query = query.filter(
            db.or_(
                ChatMessage.content.ilike(search_pat),
                ChatMessage.sender_name.ilike(search_pat),
                ChatMessage.recipient_name.ilike(search_pat)
            )
        )

    messages = query.order_by(ChatMessage.timestamp.asc()).all()

    if fmt == 'json':
        return jsonify([m.to_dict() for m in messages])

    # CSV Format
    si = io.StringIO()
    writer = csv.writer(si)
    writer.writerow(['ID', 'Chat ID', 'Sender ID', 'Sender Name', 'Recipient ID', 'Recipient Name', 'Type', 'Content', 'Image URL', 'Timestamp (ms)', 'Date UTC'])
    for m in messages:
        dt_str = datetime.fromtimestamp(m.timestamp / 1000.0, timezone.utc).strftime('%Y-%m-%d %H:%M:%S') if m.timestamp else ''
        writer.writerow([
            m.id, m.chat_id, m.sender_id, m.sender_name, m.recipient_id,
            m.recipient_name, m.type, m.content, m.image_url or '', m.timestamp, dt_str
        ])

    audit_log(_caller_id(), 'chat_export', metadata={'count': len(messages), 'format': fmt})

    return Response(
        si.getvalue(),
        mimetype='text/csv',
        headers={'Content-Disposition': f'attachment;filename=anonchat_history_{int(datetime.now(timezone.utc).timestamp())}.csv'}
    )


@bp.route('/admin/chats/purge', methods=['POST'])
@admin_required
def purge_admin_chats():
    """Manually wipe all chat history or specific chat thread."""
    from ..models import ChatMessage
    data = request.get_json(silent=True) or {}
    chat_id = data.get('chat_id')

    if chat_id:
        count = ChatMessage.query.filter_by(chat_id=chat_id).delete(synchronize_session=False)
    else:
        count = ChatMessage.query.delete(synchronize_session=False)

    db.session.commit()
    audit_log(_caller_id(), 'chat_purge', metadata={'chat_id': chat_id, 'deleted_count': count})

    return jsonify({
        'status': 'ok',
        'deleted_count': count,
        'message': f'Successfully erased {count} chat messages.'
    })
