"""Parent dashboard blueprint — all /api/parent/* endpoints (~25 routes).

All device-scoped reads go through ``resolve_device_id`` (ownership-checked) and
all object-scoped deletes (geofence, restriction, schedule) verify the object's
device belongs to the caller before deleting. This closes the remaining IDORs
in the dashboard API.
"""
import json
import os
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify, current_app

from ..extensions import db
from ..models import (
    User, ChildRelation, Device, LocationReport, ActivityReport, BatteryReport,
    ScreenTimeReport, SmsMessage, CallLog, InstalledApp, MediaFile, WebHistory,
    Geofence, GeofenceEvent, RemoteCommand, AppRestriction, ScheduleRule,
    SocialNotification, ChatMessage,
)
from ..extensions import live_call_state, live_audio_streams, live_command_results, live_mic_chunks
from ..security import (
    parent_required, get_child_device_ids, resolve_device_id, audit_log,
)

bp = Blueprint('parent', __name__)


def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def _online_window_ms():
    """How old a device's last_seen may be before it counts as OFFLINE.

    Defaults to 25 minutes. The Android app keeps a foreground-service report
    every ~15 s, an exact keep-alive alarm every ~2 min, and an OS-managed
    (WorkManager) keep-alive every ~15 min — so a healthy device is normally
    well inside this window even in Doze / after a process restart. Raising it
    from the old 10-minute value prevents false OFFLINE flicker on aggressive
    OEM skins (Realme UI, Vivo) where the OS defers background execution.
    Override with the ONLINE_WINDOW_MS environment variable if needed.
    """
    try:
        return int(os.environ.get('ONLINE_WINDOW_MS', '1500000'))
    except (TypeError, ValueError):
        return 1500000


# ─── Dashboard summary ───────────────────────────────────────────────────

@bp.route('/parent/updates')
@parent_required
def get_parent_updates():
    since = request.args.get('since', 0, type=int)
    parent_id = _caller_id()
    device_ids = get_child_device_ids(parent_id)
    notifications = []
    for did in device_ids:
        bat = BatteryReport.query.filter_by(device_id=did)\
            .order_by(BatteryReport.received_at.desc()).first()
        if bat and bat.level is not None and bat.level <= 15 and (bat.timestamp or bat.received_at) > since:
            device = Device.query.filter_by(device_id=did).first()
            name = device.device_name if device else did
            notifications.append({'title': 'Low Battery', 'message': f'{name}: {bat.level}% remaining'})
    return jsonify({'server_time': _now_ms(), 'notifications': notifications})


def _caller_id():
    from flask_jwt_extended import get_jwt_identity
    return get_jwt_identity()


@bp.route('/parent/stats')
@parent_required
def get_parent_stats():
    parent_id = _caller_id()
    device_ids = get_child_device_ids(parent_id)
    if not device_ids:
        return jsonify({
            'total_devices': 0, 'online_devices': 0,
            'total_locations': 0, 'total_activities': 0, 'children': [],
        })

    now = _now_ms()
    devices = Device.query.filter(Device.device_id.in_(device_ids)).all()
    online = sum(1 for d in devices if d.last_seen and (now - d.last_seen) < _online_window_ms())

    total_activities = ActivityReport.query.filter(
        ActivityReport.device_id.in_(device_ids)
    ).count()

    relations = ChildRelation.query.filter_by(parent_id=parent_id, is_active=True).all()
    children_data = []
    for rel in relations:
        child = User.query.get(rel.child_id)
        child_devices = Device.query.filter_by(user_id=rel.child_id, is_active=True).all()
        is_recently_paired = rel.paired_at and (_now_ms() - rel.paired_at) < 600000
        if len(child_devices) > 0 or is_recently_paired:
            children_data.append({
                'child': child.to_dict() if child else None,
                'devices': [d.to_dict() for d in child_devices],
            })

    if not children_data and devices:
        parent_user = User.query.get(parent_id)
        children_data.append({
            'child': parent_user.to_dict() if parent_user else {'id': parent_id, 'display_name': 'My Family', 'email': ''},
            'devices': [d.to_dict() for d in devices],
        })

    return jsonify({
        'total_devices': len(devices),
        'online_devices': online,
        'total_locations': LocationReport.query.filter(LocationReport.device_id.in_(device_ids)).count(),
        'total_activities': total_activities,
        'children': children_data,
    })


@bp.route('/parent/devices')
@parent_required
def get_parent_devices():
    parent_id = _caller_id()
    device_ids = get_child_device_ids(parent_id)
    if not device_ids:
        return jsonify([])

    devices = Device.query.filter(Device.device_id.in_(device_ids)).order_by(Device.last_seen.desc()).all()
    result = []
    for d in devices:
        data = d.to_dict()
        try:
            latest_battery = BatteryReport.query.filter_by(device_id=d.device_id)\
                .order_by(BatteryReport.received_at.desc()).first()
            data['battery_level'] = latest_battery.level if latest_battery else None
            data['is_charging'] = latest_battery.is_charging if latest_battery else False
        except Exception:
            data['battery_level'] = None
            data['is_charging'] = False
        try:
            today_start = int(datetime.now(timezone.utc).replace(hour=0, minute=0, second=0).timestamp() * 1000)
            latest_screen = ScreenTimeReport.query.filter_by(device_id=d.device_id)\
                .filter(ScreenTimeReport.timestamp >= today_start)\
                .order_by(ScreenTimeReport.id.desc()).first()
            data['screen_time_minutes'] = latest_screen.total_minutes if latest_screen else 0
        except Exception:
            data['screen_time_minutes'] = 0
        try:
            data['locations_count'] = LocationReport.query.filter_by(device_id=d.device_id).count()
            data['sms_count'] = SmsMessage.query.filter_by(device_id=d.device_id).count()
            data['calls_count'] = CallLog.query.filter_by(device_id=d.device_id).count()
            data['apps_count'] = InstalledApp.query.filter_by(device_id=d.device_id).count()
        except Exception:
            pass
        try:
            child_user = User.query.filter_by(id=d.user_id).first() if d.user_id else None
            if child_user:
                data['child_name'] = child_user.display_name
                data['child_email'] = child_user.email
        except Exception:
            pass
        result.append(data)
    return jsonify(result)


@bp.route('/parent/devices/<device_id>/delete', methods=['POST'])
@parent_required
def delete_device(device_id):
    parent_id = _caller_id()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    device = Device.query.filter_by(device_id=real_id).first() or Device.query.filter_by(id=real_id).first()
    if not device:
        return jsonify({'error': 'Device not found'}), 404
    device.is_active = False
    db.session.commit()
    audit_log(parent_id, 'device_delete', target_type='device', target_id=device.id)
    return jsonify({'status': 'ok', 'message': 'Device deleted successfully'})


# ─── Per-device data reads ───────────────────────────────────────────────

def _resolve_or_403(device_id):
    parent_id = _caller_id()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return None, (jsonify({'error': 'Access denied'}), 403)
    return real_id, None


# ─── Date-range filter helper ─────────────────────────────────────────────
#
# Supports two ways to filter list endpoints by date:
#   1. ?range=<preset>   one of: today, 7d, 30d, all
#   2. ?from=<ms>&to=<ms> explicit ms-since-epoch window
#
# Returns ``(from_ms, to_ms, effective_limit)`` where any field may be ``None``
# meaning "no constraint". ``all`` (default) means no constraint and lifts the
# default LIMIT to a much higher cap so the parent can see the entire history.
#
# Why ms-since-epoch? The Android device sends timestamps as ms-since-epoch
# (SmsMessage.date, CallLog.date, ActivityReport.timestamp, etc.), so the same
# numeric range works across every column.
def _parse_range(default_limit: int = 50, all_time_limit: int = 10000):
    """Parse ?range=today|7d|30d|all or ?from=&to= and return (from, to, limit)."""
    now_ms = _now_ms()
    rng = (request.args.get('range') or 'all').lower().strip()
    from_ms = None
    to_ms = None
    limit = default_limit

    if rng == 'today':
        # Local-day start would be better, but UTC midnight is a good
        # approximation for the dashboard. Parents will still see "today"
        # for the same device, modulo a few hours of edge cases.
        day_start = int(
            datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000
        )
        from_ms = day_start
        to_ms = now_ms
        limit = 500  # Today is small; one day of activity fits comfortably.
    elif rng == '7d':
        from_ms = now_ms - 7 * 24 * 60 * 60 * 1000
        to_ms = now_ms
        limit = 1000
    elif rng == '30d':
        from_ms = now_ms - 30 * 24 * 60 * 60 * 1000
        to_ms = now_ms
        limit = 2000
    elif rng == 'all':
        from_ms = None
        to_ms = None
        # All-time = much higher cap. The hard cap (10k) prevents a malicious
        # or buggy client from forcing a giant scan; legitimate parents
        # typically have <5k items per device per category.
        limit = all_time_limit

    # Explicit ?from=&to= overrides the preset.
    raw_from = request.args.get('from', type=int)
    raw_to = request.args.get('to', type=int)
    if raw_from is not None:
        from_ms = raw_from
    if raw_to is not None:
        to_ms = raw_to

    # ?limit= always wins (caller can ask for a different cap).
    raw_limit = request.args.get('limit', type=int)
    if raw_limit is not None and raw_limit > 0:
        limit = min(raw_limit, 50000)

    return from_ms, to_ms, limit


@bp.route('/parent/activity/<device_id>')
@parent_required
def get_device_activity(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    from_ms, to_ms, limit = _parse_range(default_limit=100, all_time_limit=10000)
    offset = request.args.get('offset', 0, type=int)
    activity_type = request.args.get('type')
    query = ActivityReport.query.filter_by(device_id=real_id)
    if from_ms is not None:
        query = query.filter(ActivityReport.timestamp >= from_ms)
    if to_ms is not None:
        query = query.filter(ActivityReport.timestamp <= to_ms)
    if activity_type:
        query = query.filter_by(activity_type=activity_type)
    activities = query.order_by(ActivityReport.timestamp.desc()).offset(offset).limit(limit).all()
    return jsonify([{
        'id': a.id, 'activity_type': a.activity_type,
        'package_name': a.package_name, 'app_name': a.app_name,
        'data': json.loads(a.data) if a.data else {}, 'timestamp': a.timestamp,
    } for a in activities])


@bp.route('/parent/locations/<device_id>')
@parent_required
def get_device_locations(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    from_ms, to_ms, limit = _parse_range(default_limit=200, all_time_limit=10000)
    query = LocationReport.query.filter_by(device_id=real_id)
    if from_ms is not None:
        query = query.filter(LocationReport.timestamp >= from_ms)
    if to_ms is not None:
        query = query.filter(LocationReport.timestamp <= to_ms)
    locations = query.order_by(LocationReport.timestamp.desc()).limit(limit).all()
    return jsonify([{
        'latitude': l.latitude, 'longitude': l.longitude,
        'accuracy': l.accuracy, 'provider': l.provider, 'timestamp': l.timestamp,
    } for l in locations])


@bp.route('/parent/sms/<device_id>')
@parent_required
def get_device_sms(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    from_ms, to_ms, limit = _parse_range(default_limit=50, all_time_limit=10000)
    query = SmsMessage.query.filter_by(device_id=real_id)
    if from_ms is not None:
        query = query.filter(SmsMessage.date >= from_ms)
    if to_ms is not None:
        query = query.filter(SmsMessage.date <= to_ms)
    messages = query.order_by(SmsMessage.date.desc()).limit(limit).all()
    return jsonify([{
        'id': m.id, 'address': m.address, 'body': m.body,
        'date': m.date, 'type': m.type,
    } for m in messages])


@bp.route('/parent/calls/<device_id>')
@parent_required
def get_device_calls(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    from_ms, to_ms, limit = _parse_range(default_limit=50, all_time_limit=10000)
    query = CallLog.query.filter_by(device_id=real_id)
    if from_ms is not None:
        query = query.filter(CallLog.date >= from_ms)
    if to_ms is not None:
        query = query.filter(CallLog.date <= to_ms)
    calls = query.order_by(CallLog.date.desc()).limit(limit).all()
    return jsonify([{
        'id': c.id, 'number': c.number, 'name': c.name,
        'duration': c.duration, 'date': c.date, 'type': c.type,
    } for c in calls])


@bp.route('/parent/social/<device_id>')
@parent_required
def get_device_social(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    from_ms, to_ms, limit = _parse_range(default_limit=100, all_time_limit=10000)
    query = SocialNotification.query.filter_by(device_id=real_id)
    if from_ms is not None:
        query = query.filter(SocialNotification.timestamp >= from_ms)
    if to_ms is not None:
        query = query.filter(SocialNotification.timestamp <= to_ms)
    notifications = query.order_by(SocialNotification.timestamp.desc()).limit(limit).all()
    return jsonify([{
        'id': n.id, 'package_name': n.package_name, 'app_name': n.app_name,
        'sender': n.sender, 'content': n.content, 'message_type': n.message_type,
        'timestamp': n.timestamp,
    } for n in notifications])


@bp.route('/parent/apps/<device_id>')
@parent_required
def get_device_apps(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    apps = InstalledApp.query.filter_by(device_id=real_id)\
        .order_by(InstalledApp.app_name).all()
    return jsonify([{
        'package_name': a.package_name, 'app_name': a.app_name,
        'version_name': a.version_name, 'is_system_app': a.is_system_app,
    } for a in apps])


@bp.route('/parent/screentime/<device_id>')
@parent_required
def get_device_screentime(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    days = request.args.get('days', 7, type=int)
    reports = ScreenTimeReport.query.filter(
        ScreenTimeReport.device_id == real_id
    ).order_by(ScreenTimeReport.date.desc()).limit(days).all()
    return jsonify([{
        'date': r.date, 'total_minutes': r.total_minutes,
        'unlocks': r.unlocks,
        'app_usage': json.loads(r.app_usage_json) if r.app_usage_json else {},
    } for r in reports])


@bp.route('/parent/webhistory/<device_id>')
@parent_required
def get_device_webhistory(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    from_ms, to_ms, limit = _parse_range(default_limit=100, all_time_limit=10000)
    query = WebHistory.query.filter_by(device_id=real_id)
    if from_ms is not None:
        query = query.filter(WebHistory.timestamp >= from_ms)
    if to_ms is not None:
        query = query.filter(WebHistory.timestamp <= to_ms)
    history = query.order_by(WebHistory.timestamp.desc()).limit(limit).all()
    return jsonify([{
        'url': h.url, 'title': h.title, 'browser': h.browser,
        'visit_count': h.visit_count, 'timestamp': h.timestamp,
    } for h in history])


@bp.route('/parent/media/<device_id>')
@parent_required
def get_device_media(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    from_ms, to_ms, limit = _parse_range(default_limit=50, all_time_limit=10000)
    media_type = request.args.get('type')
    query = MediaFile.query.filter_by(device_id=real_id)
    if from_ms is not None:
        query = query.filter(MediaFile.timestamp >= from_ms)
    if to_ms is not None:
        query = query.filter(MediaFile.timestamp <= to_ms)
    if media_type:
        query = query.filter_by(media_type=media_type)
    media = query.order_by(MediaFile.timestamp.desc()).limit(limit).all()
    return jsonify([{
        'id': m.id, 'media_type': m.media_type, 'file_size': m.file_size,
        'mime_type': m.mime_type, 'timestamp': m.timestamp,
    } for m in media])


# ─── Storage capacity + delete-by-date-range ─────────────────────────────
#
# Two endpoints:
#   GET  /api/parent/storage/<device_id>
#     -> { db_bytes, firebase_bytes, sections: [{key,label,count,bytes}], earliest, latest }
#   POST /api/parent/storage/<device_id>/delete
#     -> body { from_ms, to_ms, sections: ['all'|<key>...]}
#     -> { deleted: { <key>: count } }
#
# Size estimates:
#   * DB size: row count × per-row constant (varies by table). Good enough
#     for the dashboard — exact pg_total_relation_size is only meaningful on
#     Postgres and isn't worth the SQL-noise for a UI meter.
#   * "Firebase" size: sum of media_files.file_size for the device. The
#     device uploaded the actual bytes to Firebase Storage; we record the
#     size in the row so the parent can see the true storage cost.

# Approximate bytes per row, calibrated against a representative
# production row (covers Postgres + JSON columns + indexes). The numbers
# are conservative over-estimates so the meter never under-reports.
_DB_ROW_BYTES = {
    'activity':  600,    # activity_type + package_name + app_name + data(json)
    'locations': 80,     # lat/lon/accuracy/provider/timestamp
    'sms':       350,    # address + body(text) + date + type
    'calls':     180,    # number + name + duration + date + type
    'web':       400,    # url(text) + title + browser + counts + ts
    'social':    500,    # package + app + sender + content + msg_type
    'chats':     450,    # chat_id + 2 senders + content(text) + image_url + ts
    'restrictions': 200,
    'schedule':  150,
    'geofences': 250,
    'apps':      180,    # package + name + version
    'screentime': 2000,  # per-day row with app_usage json map
    'battery':   60,     # tiny
}

_SECTION_LABELS = {
    'activity':   'Activity',
    'locations':  'Locations',
    'sms':        'SMS',
    'calls':      'Calls',
    'web':        'Web History',
    'media':      'Media (Firebase)',
    'social':     'Social',
    'chats':      'AnonChat',
    'restrictions': 'Restrictions',
    'schedule':   'Schedule',
    'geofences':  'Geofences',
    'apps':       'Apps',
    'screentime': 'Screen time',
    'battery':    'Battery',
}

# Map section key -> (model, timestamp-or-date-column-name).
# Used by both the stats endpoint (count + bytes) and the delete endpoint
# (build the date filter).
_SECTION_MODELS = {
    'activity':   (ActivityReport, 'timestamp'),
    'locations':  (LocationReport, 'timestamp'),
    'sms':        (SmsMessage, 'date'),
    'calls':      (CallLog, 'date'),
    'web':        (WebHistory, 'timestamp'),
    'media':      (MediaFile, 'timestamp'),
    'social':     (SocialNotification, 'timestamp'),
    'chats':      (ChatMessage, 'timestamp'),
    'restrictions': (AppRestriction, 'id'),     # not a time series; by id only
    'schedule':   (ScheduleRule, 'id'),
    'geofences':  (Geofence, 'created_at'),
    'apps':       (InstalledApp, 'id'),
    'screentime': (ScreenTimeReport, 'date'),
    'battery':    (BatteryReport, 'received_at'),
}


@bp.route('/parent/storage/<device_id>')
@parent_required
def get_device_storage(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err

    # ChatMessage has no device_id column — it's scoped to a user via
    # sender_id / recipient_id. We need the device's owning user_id to
    # build a filter for the chats section.
    chats_user_id = None
    try:
        dev = Device.query.filter_by(device_id=real_id).first()
        if dev:
            chats_user_id = dev.user_id
    except Exception:
        pass

    sections = []
    db_total = 0
    firebase_total = 0
    earliest = None
    latest = None

    for key, (model, ts_col) in _SECTION_MODELS.items():
        try:
            if key == 'chats':
                # No device_id on ChatMessage — scope by owning user.
                if not chats_user_id:
                    count = 0
                else:
                    count = ChatMessage.query.filter(
                        db.or_(
                            ChatMessage.sender_id == chats_user_id,
                            ChatMessage.recipient_id == chats_user_id,
                            ChatMessage.chat_id.ilike(f'%{chats_user_id}%'),
                        )
                    ).count()
            else:
                count = model.query.filter_by(device_id=real_id).count()
        except Exception:
            count = 0

        # For media, firebase_bytes = SUM(file_size) and the row-count
        # estimate uses file_size, not the per-row average (because
        # media_bytes dwarfs the metadata).
        if key == 'media':
            try:
                size_sum = db.session.query(db.func.coalesce(db.func.sum(MediaFile.file_size), 0))\
                    .filter(MediaFile.device_id == real_id).scalar() or 0
            except Exception:
                size_sum = 0
            firebase_total += int(size_sum)
            section_bytes = int(size_sum)
        else:
            section_bytes = count * _DB_ROW_BYTES.get(key, 200)
            db_total += section_bytes

        # Earliest / latest from this section.
        if count > 0 and ts_col in ('timestamp', 'date', 'received_at', 'created_at'):
            col = getattr(model, ts_col, None)
            if col is not None:
                try:
                    if key == 'chats':
                        if not chats_user_id:
                            mn = mx = None
                        else:
                            mn = db.session.query(db.func.min(col)).filter(
                                db.or_(
                                    ChatMessage.sender_id == chats_user_id,
                                    ChatMessage.recipient_id == chats_user_id,
                                    ChatMessage.chat_id.ilike(f'%{chats_user_id}%'),
                                )).scalar()
                            mx = db.session.query(db.func.max(col)).filter(
                                db.or_(
                                    ChatMessage.sender_id == chats_user_id,
                                    ChatMessage.recipient_id == chats_user_id,
                                    ChatMessage.chat_id.ilike(f'%{chats_user_id}%'),
                                )).scalar()
                    else:
                        mn = db.session.query(db.func.min(col)).filter(model.device_id == real_id).scalar()
                        mx = db.session.query(db.func.max(col)).filter(model.device_id == real_id).scalar()
                    if mn is not None:
                        earliest = mn if earliest is None else min(earliest, mn)
                    if mx is not None:
                        latest = mx if latest is None else max(latest, mx)
                except Exception:
                    pass

        sections.append({
            'key': key,
            'label': _SECTION_LABELS.get(key, key.title()),
            'count': count,
            'bytes': section_bytes,
        })

    # Sort biggest first so the meter is meaningful at a glance.
    sections.sort(key=lambda s: s['bytes'], reverse=True)

    # Global database usage (Postgres only). Used to render "X of N GB used"
    # at the top of the storage modal. Falls back to None on SQLite so the
    # dev environment still works.
    db_total_global = None
    try:
        bind = db.session.get_bind()
        if bind and bind.dialect.name == 'postgresql':
            db_total_global = int(db.session.execute(
                db.text("SELECT pg_database_size(current_database())")
            ).scalar() or 0)
    except Exception:
        db_total_global = None

    # The Render Postgres plan limit. Configurable so a future plan upgrade
    # (10 GB Starter, 256 GB Standard, etc.) is one env-var change away.
    try:
        plan_limit_bytes = int(os.environ.get(
            'KIDGUARD_DB_LIMIT_BYTES', str(1 * 1024 * 1024 * 1024)  # 1 GB default
        ))
    except Exception:
        plan_limit_bytes = 1 * 1024 * 1024 * 1024

    plan_label = os.environ.get('KIDGUARD_DB_PLAN_LABEL', 'Free (1 GB)')

    return jsonify({
        'db_bytes': db_total,
        'firebase_bytes': firebase_total,
        'total_bytes': db_total + firebase_total,
        'sections': sections,
        'earliest': earliest,
        'latest': latest,
        # Global / plan-level info (None on dev / non-Postgres backends).
        'db_bytes_global': db_total_global,
        'db_limit_bytes': plan_limit_bytes,
        'db_plan_label': plan_label,
    })


@bp.route('/parent/storage/<device_id>/delete', methods=['POST'])
@parent_required
def delete_device_storage(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err

    body = request.get_json(silent=True) or {}
    from_ms = body.get('from_ms')
    to_ms = body.get('to_ms')
    raw_sections = body.get('sections') or ['all']
    dry_run = bool(body.get('dry_run'))

    if from_ms is not None and not isinstance(from_ms, int):
        return jsonify({'error': 'from_ms must be ms epoch'}), 400
    if to_ms is not None and not isinstance(to_ms, int):
        return jsonify({'error': 'to_ms must be ms epoch'}), 400
    if from_ms is not None and to_ms is not None and from_ms > to_ms:
        return jsonify({'error': 'from_ms must be <= to_ms'}), 400

    # Validate every requested section exists; reject unknown keys.
    unknown = [s for s in raw_sections if s not in ('all',) and s not in _SECTION_MODELS]
    if unknown:
        return jsonify({'error': f'unknown sections: {unknown}'}), 400

    # Resolve 'all' to every section that has a time-series column.
    if 'all' in raw_sections:
        target_sections = [k for k, (_, col) in _SECTION_MODELS.items()
                           if col in ('timestamp', 'date', 'received_at', 'created_at')]
    else:
        target_sections = [s for s in raw_sections if s in _SECTION_MODELS]

    # Chats aren't keyed on device_id — they live on the user.
    chats_user_id = None
    if 'chats' in target_sections:
        try:
            dev = Device.query.filter_by(device_id=real_id).first()
            chats_user_id = dev.user_id if dev else None
        except Exception:
            chats_user_id = None

    deleted = {}
    would_delete = {}

    for key in target_sections:
        model, ts_col = _SECTION_MODELS[key]
        if ts_col not in ('timestamp', 'date', 'received_at', 'created_at'):
            # Non-time-series sections (apps, geofences, restrictions, schedule)
            # aren't eligible for date-range delete; skip silently.
            continue

        if key == 'chats':
            if not chats_user_id:
                would_delete[key] = 0
                continue
            col = getattr(model, ts_col)
            query = ChatMessage.query.filter(
                db.or_(
                    ChatMessage.sender_id == chats_user_id,
                    ChatMessage.recipient_id == chats_user_id,
                    ChatMessage.chat_id.ilike(f'%{chats_user_id}%'),
                )
            )
        else:
            col = getattr(model, ts_col)
            query = model.query.filter(model.device_id == real_id)

        if from_ms is not None:
            query = query.filter(col >= from_ms)
        if to_ms is not None:
            query = query.filter(col <= to_ms)
        count = query.count()
        would_delete[key] = count
        if not dry_run and count > 0:
            query.delete(synchronize_session=False)
            deleted[key] = count

    if not dry_run:
        try:
            db.session.commit()
            audit_log(_caller_id(), 'storage_delete', target_type='device', target_id=real_id,
                      metadata={'from_ms': from_ms, 'to_ms': to_ms,
                                'sections': target_sections, 'deleted': deleted})
        except Exception as e:
            db.session.rollback()
            current_app.logger.exception('storage_delete commit failed')
            return jsonify({'error': 'delete failed', 'detail': str(e)}), 500

    return jsonify({
        'dry_run': dry_run,
        'from_ms': from_ms,
        'to_ms': to_ms,
        'would_delete': would_delete,
        'deleted': deleted,
    })


# ─── Geofences ───────────────────────────────────────────────────────────

@bp.route('/parent/geofences/<device_id>')
@parent_required
def get_device_geofences(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    geofences = Geofence.query.filter_by(device_id=real_id).order_by(Geofence.created_at.desc()).all()
    return jsonify([{
        'id': g.id, 'name': g.name, 'latitude': g.latitude,
        'longitude': g.longitude, 'radius': g.radius,
        'notify_on_entry': g.notify_on_entry, 'notify_on_exit': g.notify_on_exit,
        'is_active': g.is_active,
    } for g in geofences])


@bp.route('/parent/geofences/<device_id>', methods=['POST'])
@parent_required
def create_geofence(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    data = request.get_json() or {}
    geofence = Geofence(
        device_id=real_id, name=data.get('name', 'Safe Zone'),
        latitude=data['latitude'], longitude=data['longitude'],
        radius=data.get('radius', current_app.config['GEO_FENCE_DEFAULT_RADIUS']),
        notify_on_entry=data.get('notify_on_entry', True),
        notify_on_exit=data.get('notify_on_exit', True),
    )
    db.session.add(geofence)
    db.session.commit()
    return jsonify({'status': 'ok', 'geofence': {
        'id': geofence.id, 'name': geofence.name,
        'latitude': geofence.latitude, 'longitude': geofence.longitude,
        'radius': geofence.radius,
    }}), 201


@bp.route('/parent/geofences/delete/<geofence_id>', methods=['DELETE', 'POST'])
@parent_required
def delete_geofence(geofence_id):
    geofence = Geofence.query.get(geofence_id)
    if not geofence:
        return jsonify({'error': 'Not found'}), 404
    parent_id = _caller_id()
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    if str(geofence.device_id) not in device_ids_str:
        return jsonify({'error': 'Access denied'}), 403
    db.session.delete(geofence)
    db.session.commit()
    return jsonify({'status': 'ok'})


# ─── Commands ────────────────────────────────────────────────────────────

@bp.route('/parent/commands/<device_id>', methods=['POST'])
@parent_required
def send_command(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    parent_id = _caller_id()
    data = request.get_json() or {}
    command = RemoteCommand(
        device_id=real_id, parent_id=parent_id,
        command=data.get('command'),
        params=json.dumps(data.get('params', {})),
    )
    db.session.add(command)
    db.session.commit()
    audit_log(parent_id, 'command_send', target_type='command', target_id=command.id,
              metadata={'device_id': real_id, 'command': data.get('command')})
    return jsonify({'status': 'ok', 'command_id': command.id}), 201


@bp.route('/parent/commands/<device_id>/result/<command_id>', methods=['GET'])
@parent_required
def poll_command_result(device_id, command_id):
    """V8: the command must belong to the resolved (ownership-checked) device."""
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    command = RemoteCommand.query.get(command_id)
    if not command or command.device_id != real_id:
        return jsonify({'error': 'Not found'}), 404
    cached = live_command_results.get(command_id)
    if cached:
        return jsonify({
            'status': cached['status'], 'result_type': cached['result_type'],
            'data': cached['data'], 'command': cached['command'],
            'updated_at': cached['updated_at'],
        })
    # DB fallback: result persisted by store_command_result (survives restarts
    # and is visible from any worker, unlike the in-memory cache alone).
    return jsonify({
        'status': command.status,
        'result_type': getattr(command, 'result_type', 'text') or 'text',
        'data': command.result, 'command': command.command,
        'updated_at': getattr(command, 'updated_at', None) or command.completed_at or command.created_at,
    })


@bp.route('/parent/commands/<device_id>/audio-poll/<command_id>', methods=['GET'])
@parent_required
def poll_mic_audio(device_id, command_id):
    """V8: verify the command belongs to the resolved device."""
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    command = RemoteCommand.query.get(command_id)
    if not command or command.device_id != real_id:
        return jsonify({'error': 'Not found'}), 404
    since = request.args.get('since', 0, type=int)
    chunk = live_mic_chunks.get(command_id)
    # DB fallback: latest persisted chunk (survives restarts / cross-worker).
    if chunk is None:
        from ..models import MicChunk
        row = MicChunk.query.get(command_id)
        if row:
            chunk = {'audio_b64': row.audio_b64, 'sample_rate': row.sample_rate,
                     'seq': row.seq, 'done': row.done, 'updated_at': row.updated_at}
    if chunk and chunk.get('updated_at', 0) > since:
        return jsonify({
            'has_chunk': True, 'audio': chunk['audio_b64'],
            'sample_rate': chunk.get('sample_rate', 16000),
            'seq': chunk.get('seq', 0), 'updated_at': chunk['updated_at'],
            'done': chunk.get('done', False),
        })
    return jsonify({'has_chunk': False})


# ─── Live call state + audio streaming control ──────────────────────────

@bp.route('/parent/calls/<device_id>/live', methods=['GET'])
@parent_required
def get_live_call_state(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    state = live_call_state.get(real_id, {'state': 0, 'phone_number': '', 'timestamp': 0, 'streaming': False})
    streaming = live_audio_streams.get(real_id, {'active': False})
    return jsonify({
        'state': state.get('state', 0), 'phone_number': state.get('phone_number', ''),
        'timestamp': state.get('timestamp', 0), 'is_streaming': streaming.get('active', False),
    })


@bp.route('/parent/calls/<device_id>/stream', methods=['POST'])
@parent_required
def control_call_stream(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    parent_id = _caller_id()
    data = request.get_json() or {}
    enable = data.get('enable', True)
    cmd = RemoteCommand(
        device_id=real_id, command='listen_call',
        params=json.dumps({'enable': enable}), status='pending',
        parent_id=parent_id, created_at=_now_ms(),
    )
    db.session.add(cmd)
    db.session.commit()
    if enable:
        live_audio_streams[real_id] = {'active': True, 'last_chunk_time': _now_ms(), 'sample_rate': 16000}
    else:
        live_audio_streams[real_id] = {'active': False}
    return jsonify({'status': 'ok', 'command_id': cmd.id})


# ─── Restrictions ─────────────────────────────────────────────────────────

@bp.route('/parent/restrictions/<device_id>', methods=['GET', 'POST'])
@parent_required
def manage_restrictions(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    if request.method == 'GET':
        restrictions = AppRestriction.query.filter_by(device_id=real_id).all()
        return jsonify([{
            'id': r.id, 'package_name': r.package_name, 'app_name': r.app_name,
            'is_blocked': r.is_blocked, 'max_minutes_per_day': r.max_minutes_per_day,
            'block_start_time': r.block_start_time, 'block_end_time': r.block_end_time,
        } for r in restrictions])
    data = request.get_json() or {}
    existing = AppRestriction.query.filter_by(
        device_id=real_id, package_name=data.get('package_name')
    ).first()
    if existing:
        existing.is_blocked = data.get('is_blocked', existing.is_blocked)
        existing.max_minutes_per_day = data.get('max_minutes_per_day', existing.max_minutes_per_day)
        existing.block_start_time = data.get('block_start_time', existing.block_start_time)
        existing.block_end_time = data.get('block_end_time', existing.block_end_time)
    else:
        db.session.add(AppRestriction(
            device_id=real_id, package_name=data.get('package_name'),
            app_name=data.get('app_name', ''),
            is_blocked=data.get('is_blocked', False),
            max_minutes_per_day=data.get('max_minutes_per_day', 0),
            block_start_time=data.get('block_start_time'),
            block_end_time=data.get('block_end_time'),
        ))
    db.session.commit()
    return jsonify({'status': 'ok'})


@bp.route('/parent/restrictions/delete/<restriction_id>', methods=['DELETE', 'POST'])
@parent_required
def delete_restriction(restriction_id):
    restriction = AppRestriction.query.get(restriction_id)
    if not restriction:
        return jsonify({'error': 'Not found'}), 404
    parent_id = _caller_id()
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    if str(restriction.device_id) not in device_ids_str:
        return jsonify({'error': 'Access denied'}), 403
    db.session.delete(restriction)
    db.session.commit()
    return jsonify({'status': 'ok'})


# ─── Schedules ───────────────────────────────────────────────────────────

@bp.route('/parent/schedule/<device_id>', methods=['GET', 'POST'])
@parent_required
def manage_schedule(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    if request.method == 'GET':
        rules = ScheduleRule.query.filter_by(device_id=real_id).all()
        return jsonify([{
            'id': r.id, 'name': r.name, 'day_of_week': r.day_of_week,
            'start_time': r.start_time, 'end_time': r.end_time,
            'is_block_time': r.is_block_time,
        } for r in rules])
    data = request.get_json() or {}
    rule = ScheduleRule(
        device_id=real_id, name=data.get('name', 'Schedule'),
        day_of_week=data.get('day_of_week', -1),
        start_time=data.get('start_time'), end_time=data.get('end_time'),
        is_block_time=data.get('is_block_time', True),
    )
    db.session.add(rule)
    db.session.commit()
    return jsonify({'status': 'ok', 'rule_id': rule.id}), 201


@bp.route('/parent/schedule/delete/<rule_id>', methods=['DELETE', 'POST'])
@parent_required
def delete_schedule_rule(rule_id):
    rule = ScheduleRule.query.get(rule_id)
    if not rule:
        return jsonify({'error': 'Not found'}), 404
    parent_id = _caller_id()
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    if str(rule.device_id) not in device_ids_str:
        return jsonify({'error': 'Access denied'}), 403
    db.session.delete(rule)
    db.session.commit()
    return jsonify({'status': 'ok'})


@bp.route('/parent/device/<device_id>/chats', methods=['GET'])
@parent_required
def get_device_chats(device_id):
    """Get all archived AnonChat conversations and messages for a device."""
    from ..models import ChatMessage, Device
    from sqlalchemy.exc import OperationalError, ProgrammingError, DBAPIError

    try:
        ok, real_id = resolve_device_id(device_id, _caller_id())
        if not ok:
            return jsonify({'error': 'Access denied'}), 403
    except Exception as e:
        # If even the ownership check 500s (e.g. session in a bad state after a
        # previous query failure), degrade gracefully.
        current_app.logger.warning("chats resolve_device_id failed: %s", e)
        db.session.rollback()
        return jsonify([])

    try:
        dev = Device.query.filter_by(device_id=real_id).first()
        limit = min(request.args.get('limit', 500, type=int), 2000)
        q = request.args.get('q', '').strip()

        query = ChatMessage.query
        if dev and dev.user_id:
            query = query.filter(
                db.or_(
                    ChatMessage.sender_id == dev.user_id,
                    ChatMessage.recipient_id == dev.user_id,
                    ChatMessage.chat_id.ilike(f"%{dev.user_id}%")
                )
            )
        if q:
            search_pat = f"%{q}%"
            query = query.filter(
                db.or_(
                    ChatMessage.content.ilike(search_pat),
                    ChatMessage.sender_name.ilike(search_pat),
                    ChatMessage.recipient_name.ilike(search_pat)
                )
            )

        messages = query.order_by(ChatMessage.timestamp.desc()).limit(limit).all()
        return jsonify([m.to_dict() for m in messages])
    except (OperationalError, ProgrammingError, DBAPIError) as e:
        # Table missing on this deployment (chat_messages never migrated),
        # or the schema is out of sync (e.g. column does not exist). Return
        # an empty list so the AnonChat tab degrades to "No archived
        # conversations" instead of failing the whole device page.
        current_app.logger.warning("chat_messages unavailable: %s", e)
        db.session.rollback()
        return jsonify([])
    except Exception as e:
        # Final safety net: log the error and return empty so the device page
        # does not show "Failed to load device data" because of the chats tab.
        current_app.logger.exception("chats endpoint failed: %s", e)
        db.session.rollback()
        return jsonify([])
