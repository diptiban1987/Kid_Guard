"""Parent dashboard blueprint — all /api/parent/* endpoints (~25 routes).

All device-scoped reads go through ``resolve_device_id`` (ownership-checked) and
all object-scoped deletes (geofence, restriction, schedule) verify the object's
device belongs to the caller before deleting. This closes the remaining IDORs
in the dashboard API.
"""
import json
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify, current_app

from ..extensions import db
from ..models import (
    User, ChildRelation, Device, LocationReport, ActivityReport, BatteryReport,
    ScreenTimeReport, SmsMessage, CallLog, InstalledApp, MediaFile, WebHistory,
    Geofence, GeofenceEvent, RemoteCommand, AppRestriction, ScheduleRule,
    SocialNotification,
)
from ..extensions import live_call_state, live_audio_streams, live_command_results, live_mic_chunks
from ..security import (
    parent_required, get_child_device_ids, resolve_device_id, audit_log,
)

bp = Blueprint('parent', __name__)


def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


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
    online = sum(1 for d in devices if d.last_seen and (now - d.last_seen) < 600000)

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


@bp.route('/parent/activity/<device_id>')
@parent_required
def get_device_activity(device_id):
    real_id, err = _resolve_or_403(device_id)
    if err:
        return err
    limit = request.args.get('limit', 100, type=int)
    offset = request.args.get('offset', 0, type=int)
    activity_type = request.args.get('type')
    query = ActivityReport.query.filter_by(device_id=real_id)
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
    limit = request.args.get('limit', 200, type=int)
    locations = LocationReport.query.filter_by(device_id=real_id)\
        .order_by(LocationReport.timestamp.desc()).limit(limit).all()
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
    limit = request.args.get('limit', 50, type=int)
    messages = SmsMessage.query.filter_by(device_id=real_id)\
        .order_by(SmsMessage.date.desc()).limit(limit).all()
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
    limit = request.args.get('limit', 50, type=int)
    calls = CallLog.query.filter_by(device_id=real_id)\
        .order_by(CallLog.date.desc()).limit(limit).all()
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
    limit = request.args.get('limit', 100, type=int)
    notifications = SocialNotification.query.filter_by(device_id=real_id)\
        .order_by(SocialNotification.timestamp.desc()).limit(limit).all()
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
    limit = request.args.get('limit', 100, type=int)
    history = WebHistory.query.filter_by(device_id=real_id)\
        .order_by(WebHistory.timestamp.desc()).limit(limit).all()
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
    limit = request.args.get('limit', 50, type=int)
    media_type = request.args.get('type')
    query = MediaFile.query.filter_by(device_id=real_id)
    if media_type:
        query = query.filter_by(media_type=media_type)
    media = query.order_by(MediaFile.timestamp.desc()).limit(limit).all()
    return jsonify([{
        'id': m.id, 'media_type': m.media_type, 'file_size': m.file_size,
        'mime_type': m.mime_type, 'timestamp': m.timestamp,
    } for m in media])


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
    ok, real_id = resolve_device_id(device_id, _caller_id())
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    from ..models import ChatMessage, Device
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
