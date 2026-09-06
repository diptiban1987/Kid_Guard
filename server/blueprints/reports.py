"""Reports blueprint — all /api/report/* endpoints (12 routes).

Security fix applied here (V2 — report injection): every report route verifies
that the caller owns the device via ``assert_device_ownership`` before
accepting data. A child can only report for devices they own; a parent cannot
inject reports for devices they don't have an active relation to. The
media-upload route (V3) additionally verifies a linked ``command_id`` belongs
to the device.
"""
import os
import json
import time
import base64 as _b64
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify, current_app
from werkzeug.utils import secure_filename
from flask_jwt_extended import jwt_required, get_jwt_identity

from ..extensions import db
from ..extensions import (
    live_call_state, live_audio_streams, live_command_results, live_mic_chunks,
    store_command_result, store_mic_chunk,
)
from ..models import (
    Device, LocationReport, ActivityReport, BatteryReport, ScreenTimeReport,
    SmsMessage, CallLog, CallStateEvent, InstalledApp, MediaFile, WebHistory,
    Geofence, GeofenceEvent, SocialNotification, RemoteCommand,
)
from ..security import (
    assert_device_ownership, assert_command_ownership, audit_log,
)

bp = Blueprint('reports', __name__)


def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def _check_geofences(device_id, latitude, longitude):
    """Haversine geofence check — preserved verbatim from the original."""
    from math import radians, sin, cos, sqrt, atan2
    geofences = Geofence.query.filter_by(device_id=device_id, is_active=True).all()
    for gf in geofences:
        R = 6371000
        lat1, lon1 = radians(latitude), radians(longitude)
        lat2, lon2 = radians(gf.latitude), radians(gf.longitude)
        dlat, dlon = lat2 - lat1, lon2 - lon1
        a = sin(dlat/2)**2 + cos(lat1) * cos(lat2) * sin(dlon/2)**2
        distance = R * 2 * atan2(sqrt(a), sqrt(1-a))
        last_event = GeofenceEvent.query.filter_by(
            device_id=device_id, geofence_id=gf.id
        ).order_by(GeofenceEvent.timestamp.desc()).first()
        was_inside = last_event and last_event.event_type == 'enter'
        is_inside = distance <= gf.radius
        if not was_inside and is_inside and gf.notify_on_entry:
            db.session.add(GeofenceEvent(
                device_id=device_id, geofence_id=gf.id, event_type='enter',
                latitude=latitude, longitude=longitude, timestamp=_now_ms(),
            ))
        elif was_inside and not is_inside and gf.notify_on_exit:
            db.session.add(GeofenceEvent(
                device_id=device_id, geofence_id=gf.id, event_type='exit',
                latitude=latitude, longitude=longitude, timestamp=_now_ms(),
            ))


def _emit(device_id, event_type, data):
    """Emit a realtime event to the parent room. No-op without SocketIO.

    The SocketIO instance lives on the package (server/__init__.py), NOT in
    extensions — importing it from extensions raised ImportError on every call,
    which the blanket ``except`` below swallowed, silently disabling all
    realtime updates. Use the package accessor instead."""
    try:
        from .. import get_socketio
        sio = get_socketio()
        if sio is None:
            return
        from ..models import ChildRelation
        device = Device.query.filter_by(device_id=device_id).first()
        if not device or not device.user_id:
            return
        relations = ChildRelation.query.filter_by(child_id=device.user_id).all()
        for rel in relations:
            sio.emit('realtime_update', {
                'device_id': device_id, 'event_type': event_type,
                'data': data, 'timestamp': _now_ms(),
            }, room=f"user_{rel.parent_id}")
    except Exception:
        current_app.logger.exception(
            f'realtime emit failed (device={device_id}, event={event_type})'
        )


# ─── Report endpoints (V2: each verifies ownership) ───────────────────────

@bp.route('/report/location', methods=['POST'])
@jwt_required()
def report_location():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    device_id = data.get('device_id')
    ok, canonical = assert_device_ownership(device_id, caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    device = Device.query.filter_by(device_id=canonical).first()
    if device:
        device.last_seen = _now_ms()

    report = LocationReport(
        device_id=canonical,
        latitude=data['latitude'], longitude=data['longitude'],
        accuracy=data.get('accuracy', 0), altitude=data.get('altitude'),
        speed=data.get('speed'), bearing=data.get('bearing'),
        provider=data.get('provider', 'unknown'),
        timestamp=data.get('timestamp', _now_ms()),
    )
    db.session.add(report)
    db.session.commit()
    _check_geofences(canonical, data['latitude'], data['longitude'])
    _emit(canonical, 'location', {
        'latitude': data['latitude'], 'longitude': data['longitude'],
        'accuracy': report.accuracy, 'timestamp': report.timestamp,
    })
    return jsonify({'status': 'ok'})


@bp.route('/report/activity', methods=['POST'])
@jwt_required()
def report_activity():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    device = Device.query.filter_by(device_id=canonical).first()
    if device:
        device.last_seen = _now_ms()

    report = ActivityReport(
        device_id=canonical,
        activity_type=data.get('activity_type', 'unknown'),
        package_name=data.get('package_name'), app_name=data.get('app_name'),
        data=json.dumps(data.get('data', {})),
        timestamp=data.get('timestamp', _now_ms()),
    )
    db.session.add(report)
    db.session.commit()
    _emit(canonical, 'activity', {
        'activity_type': report.activity_type, 'app_name': report.app_name,
        'timestamp': report.timestamp,
    })
    return jsonify({'status': 'ok'})


@bp.route('/report/battery', methods=['POST'])
@jwt_required()
def report_battery():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    device = Device.query.filter_by(device_id=canonical).first()
    if device:
        device.last_seen = _now_ms()

    report = BatteryReport(
        device_id=canonical, level=data.get('level', -1),
        is_charging=data.get('is_charging', False),
        temperature=data.get('temperature', -1), voltage=data.get('voltage'),
        plugged=data.get('plugged'),
        timestamp=data.get('timestamp', _now_ms()),
    )
    db.session.add(report)
    db.session.commit()
    _emit(canonical, 'battery', {'level': report.level, 'is_charging': report.is_charging})
    return jsonify({'status': 'ok'})


@bp.route('/report/screentime', methods=['POST'])
@jwt_required()
def report_screentime():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    today = data.get('date', datetime.now(timezone.utc).strftime('%Y-%m-%d'))
    existing = ScreenTimeReport.query.filter_by(device_id=canonical, date=today).first()
    if existing:
        existing.total_minutes = data.get('total_minutes', existing.total_minutes)
        existing.unlocks = data.get('unlocks', existing.unlocks)
        existing.app_usage_json = json.dumps(data.get('app_usage', {}))
        existing.updated_at = _now_ms()
    else:
        db.session.add(ScreenTimeReport(
            device_id=canonical, date=today,
            total_minutes=data.get('total_minutes', 0),
            unlocks=data.get('unlocks', 0),
            app_usage_json=json.dumps(data.get('app_usage', {})),
        ))
    db.session.commit()
    return jsonify({'status': 'ok'})


@bp.route('/report/sms', methods=['POST'])
@jwt_required()
def report_sms():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    messages = data.get('messages', [])
    count = 0
    for msg in messages:
        if not SmsMessage.query.filter_by(
            device_id=canonical, sms_id=msg.get('id')
        ).first():
            db.session.add(SmsMessage(
                device_id=canonical, sms_id=msg.get('id'),
                address=msg.get('address', ''), body=msg.get('body', ''),
                date=msg.get('date', 0), type=msg.get('type', 0),
            ))
            count += 1
    db.session.commit()
    if count > 0:
        _emit(canonical, 'sms', {'count': count})
    return jsonify({'status': 'ok', 'new': count})


@bp.route('/report/calls', methods=['POST'])
@jwt_required()
def report_calls():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    calls = data.get('calls', [])
    count = 0
    for call in calls:
        if not CallLog.query.filter_by(
            device_id=canonical, call_id=call.get('id')
        ).first():
            db.session.add(CallLog(
                device_id=canonical, call_id=call.get('id'),
                number=call.get('number', ''), name=call.get('name', ''),
                duration=call.get('duration', 0), date=call.get('date', 0),
                type=call.get('type', 0),
            ))
            count += 1
    db.session.commit()
    if count > 0:
        _emit(canonical, 'call', {'count': count})
    return jsonify({'status': 'ok', 'new': count})


@bp.route('/report/apps', methods=['POST'])
@jwt_required()
def report_apps():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    InstalledApp.query.filter_by(device_id=canonical).delete()
    for app_data in data.get('apps', []):
        db.session.add(InstalledApp(
            device_id=canonical,
            package_name=app_data.get('packageName'),
            app_name=app_data.get('appName'),
            version_name=app_data.get('versionName'),
            version_code=app_data.get('versionCode', 0),
            first_install_time=app_data.get('firstInstallTime', 0),
            last_update_time=app_data.get('lastUpdateTime', 0),
            is_system_app=app_data.get('isSystemApp', False),
        ))
    db.session.commit()
    return jsonify({'status': 'ok'})


@bp.route('/report/webhistory', methods=['POST'])
@jwt_required()
def report_webhistory():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    count = 0
    for entry in data.get('entries', []):
        db.session.add(WebHistory(
            device_id=canonical,
            url=entry.get('url', ''), title=entry.get('title', ''),
            browser=entry.get('browser', ''), visit_count=entry.get('visit_count', 1),
            timestamp=entry.get('timestamp', _now_ms()),
        ))
        count += 1
    db.session.commit()
    if count > 0:
        _emit(canonical, 'web', {'count': count})
    return jsonify({'status': 'ok', 'new': count})


@bp.route('/report/media', methods=['POST'])
@jwt_required()
def report_media():
    """V3: if a ``command_id`` is supplied, verify it belongs to a device owned
    by the caller before accepting the upload (prevents media poisoning of
    another parent's command result)."""
    caller_id = get_jwt_identity()
    device_id = request.form.get('device_id')
    media_type = request.form.get('media_type', 'photo')
    command_id = request.form.get('command_id')
    # Procedural capture fields (MediaCollectionManager): the device's own
    # capture timestamp and a JSON metadata blob (source path, sizes).
    device_ts = request.form.get('timestamp', type=int) or _now_ms()
    metadata = request.form.get('metadata', '')
    file = request.files.get('file')

    # Max accepted upload size (videos are pre-screened on the device; this
    # is the server-side safety net).
    MAX_UPLOAD_BYTES = 12 * 1024 * 1024
    if file is not None:
        file.stream.seek(0, 2)
        if file.stream.tell() > MAX_UPLOAD_BYTES:
            return jsonify({'error': 'File too large'}), 413
        file.stream.seek(0)

    ok, canonical = assert_device_ownership(device_id, caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    # V3: verify the command belongs to a device owned by the caller.
    if command_id:
        cmd_ok, _ = assert_command_ownership(command_id, caller_id)
        if not cmd_ok:
            return jsonify({'error': 'Access denied'}), 403

    if file is not None:
        filename = f"{canonical}_{int(time.time())}_{secure_filename(file.filename)}"
        filepath = os.path.join(current_app.config['UPLOAD_FOLDER'], filename)
        file.save(filepath)
        stored_size = os.path.getsize(filepath)
        stored_mime = file.mimetype
        stored_path = filepath
    else:
        # Metadata-only row: either the file was too large to upload, or its
        # bytes already live in Firebase Storage (storage_url in metadata).
        # file_path = Firebase URL when available; None -> /api/files 404s.
        stored_size = 0
        stored_url = None
        try:
            import json as _json
            meta_obj = _json.loads(metadata) if metadata else {}
            stored_size = meta_obj.get('original_size', 0) or 0
            su = meta_obj.get('storage_url')
            if isinstance(su, str) and su.startswith('http'):
                stored_url = su
        except Exception:
            pass
        if stored_url:
            stored_mime = media_type if media_type in ('image', 'video') else 'application/octet-stream'
        else:
            stored_mime = 'video/*' if media_type == 'video' else 'application/octet-stream'
        stored_path = stored_url

    media = MediaFile(
        device_id=canonical, media_type=media_type,
        file_path=stored_path, file_size=stored_size,
        mime_type=stored_mime, timestamp=device_ts,
    )
    db.session.add(media)

    if command_id and file is not None:
        try:
            with open(filepath, 'rb') as fh:
                raw = fh.read()
            mime = file.mimetype or ('image/jpeg' if media_type != 'audio' else 'audio/mp4')
            data_uri = f"data:{mime};base64," + _b64.b64encode(raw).decode('utf-8')
            result_type = 'audio' if media_type == 'audio' else 'image'
            # Persist result so the parent poll survives restarts / workers.
            store_command_result(
                command_id, status='completed', result_type=result_type,
                data=data_uri, command=media_type, updated_at=_now_ms(),
            )
        except Exception as exc:
            current_app.logger.warning(f'Failed to cache media result for command {command_id}: {exc}')

    db.session.commit()
    _emit(canonical, 'media', {'media_type': media_type, 'file_size': media.file_size})
    return jsonify({'status': 'ok', 'media_id': media.id})


def _social_dedupe(canonical, notif):
    """Return True if an identical social notification row already exists
    (device retries / notification re-posts can resend the same content)."""
    try:
        q = SocialNotification.query.filter_by(
            device_id=canonical,
            package_name=notif.get('package_name', ''),
            sender=notif.get('sender', ''),
            content=notif.get('content', ''),
            timestamp=notif.get('timestamp', _now_ms()),
        )
        return db.session.query(q.exists()).scalar()
    except Exception:
        return False


@bp.route('/report/social', methods=['POST'])
@jwt_required()
def report_social():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    ok, canonical = assert_device_ownership(data.get('device_id'), caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    for notif in data.get('social', []):
        if _social_dedupe(canonical, notif):
            continue
        db.session.add(SocialNotification(
            device_id=canonical,
            package_name=notif.get('package_name', ''),
            app_name=notif.get('app_name', ''),
            sender=notif.get('sender', ''),
            content=notif.get('content', ''),
            message_type=notif.get('message_type', 'notification'),
            timestamp=notif.get('timestamp', _now_ms()),
        ))
    db.session.commit()
    return jsonify({'status': 'ok'})


@bp.route('/report/bulk', methods=['POST'])
@jwt_required()
def report_bulk():
    """Bulk report endpoint for efficiency. V2: verifies ownership once at the
    top; the single device_id applies to all sub-reports."""
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    device_id = data.get('device_id')
    if not device_id:
        return jsonify({'error': 'device_id required'}), 400

    ok, canonical = assert_device_ownership(device_id, caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    device = Device.query.filter_by(device_id=canonical).first()
    if device:
        device.last_seen = _now_ms()

    # Diagnostic: log payload shape before processing
    try:
        _payload_keys = sorted(data.keys()) if isinstance(data, dict) else []
        current_app.logger.warning("report_bulk device=%s keys=%s sms_len=%s calls_len=%s apps_len=%s",
                                    canonical, _payload_keys,
                                    len(data.get('sms') or []),
                                    len(data.get('calls') or []),
                                    len(data.get('apps') or []))
    except Exception as _e:
        current_app.logger.exception("report_bulk payload-shape log failed: %s", _e)

    if 'location' in data:
        loc = data['location']
        db.session.add(LocationReport(
            device_id=canonical, latitude=loc['latitude'], longitude=loc['longitude'],
            accuracy=loc.get('accuracy', 0), altitude=loc.get('altitude'),
            provider=loc.get('provider', 'unknown'),
            timestamp=loc.get('timestamp', _now_ms()),
        ))
        _check_geofences(canonical, loc['latitude'], loc['longitude'])

    if 'battery' in data:
        bat = data['battery']
        db.session.add(BatteryReport(
            device_id=canonical, level=bat.get('level', -1),
            is_charging=bat.get('is_charging', False),
            temperature=bat.get('temperature', -1),
            timestamp=bat.get('timestamp', _now_ms()),
        ))

    if 'activities' in data:
        for act in data['activities']:
            db.session.add(ActivityReport(
                device_id=canonical, activity_type=act.get('activity_type', 'unknown'),
                package_name=act.get('package_name'), app_name=act.get('app_name'),
                data=json.dumps(act.get('data', {})),
                timestamp=act.get('timestamp', _now_ms()),
            ))

    if 'sms' in data:
        skipped = 0
        for msg in data['sms']:
            try:
                sms_pk = msg.get('id')
                if sms_pk is None:
                    skipped += 1
                    continue
                if not SmsMessage.query.filter_by(device_id=canonical, sms_id=sms_pk).first():
                    db.session.add(SmsMessage(
                        device_id=canonical, sms_id=sms_pk,
                        address=(msg.get('address') or '')[:100], body=msg.get('body', '') or '',
                        date=msg.get('date', 0) or 0, type=msg.get('type', 0) or 0,
                    ))
            except Exception as e:
                skipped += 1
                current_app.logger.warning("report_bulk sms skip: %s id=%s", e, msg.get('id'))
        if skipped:
            current_app.logger.warning("report_bulk skipped %d sms entries", skipped)

    if 'calls' in data:
        skipped = 0
        for call in data['calls']:
            try:
                call_pk = call.get('id')
                if call_pk is None:
                    skipped += 1
                    continue
                if not CallLog.query.filter_by(device_id=canonical, call_id=call_pk).first():
                    db.session.add(CallLog(
                        device_id=canonical, call_id=call_pk,
                        number=(call.get('number') or '')[:50],
                        name=(call.get('name') or '')[:200],
                        duration=call.get('duration', 0) or 0,
                        date=call.get('date', 0) or 0,
                        type=call.get('type', 0) or 0,
                    ))
            except Exception as e:
                skipped += 1
                current_app.logger.warning("report_bulk call skip: %s id=%s", e, call.get('id'))
        if skipped:
            current_app.logger.warning("report_bulk skipped %d call entries", skipped)

    if 'apps' in data and data['apps']:
        try:
            InstalledApp.query.filter_by(device_id=canonical).delete()
        except Exception as e:
            current_app.logger.exception("report_bulk apps delete failed: %s", e)
            db.session.rollback()
            return jsonify({
                'error': 'Internal server error',
                'detail': f'apps delete: {type(e).__name__}: {str(e)[:200]}',
            }), 500
        bad_apps = []
        for app_data in data['apps']:
            try:
                # Defensive truncation: app_name and package_name have column limits
                pn = (app_data.get('packageName') or app_data.get('package_name') or '')[:255]
                an = (app_data.get('appName') or app_data.get('app_name') or '')[:255]
                vn = (app_data.get('versionName') or app_data.get('version_name') or '')[:50]
                db.session.add(InstalledApp(
                    device_id=canonical,
                    package_name=pn,
                    app_name=an,
                    version_name=vn,
                    version_code=app_data.get('versionCode') or app_data.get('version_code', 0),
                    first_install_time=app_data.get('firstInstallTime') or app_data.get('first_install_time', 0),
                    last_update_time=app_data.get('lastUpdateTime') or app_data.get('last_update_time', 0),
                    is_system_app=app_data.get('isSystemApp') or app_data.get('is_system_app', False),
                ))
            except Exception as e:
                bad_apps.append({'name': app_data.get('packageName') or app_data.get('package_name', ''),
                                  'app': (app_data.get('appName') or '')[:60],
                                  'err': f'{type(e).__name__}: {str(e)[:120]}'})
        if bad_apps:
            current_app.logger.warning("report_bulk skipped %d bad apps: %s", len(bad_apps), bad_apps[:3])

    if 'screentime' in data:
        st = data['screentime']
        today = st.get('date', datetime.now(timezone.utc).strftime('%Y-%m-%d'))
        existing = ScreenTimeReport.query.filter_by(device_id=canonical, date=today).first()
        if existing:
            existing.total_minutes = st.get('total_minutes', existing.total_minutes)
            existing.unlocks = st.get('unlocks', existing.unlocks)
            existing.updated_at = _now_ms()
        else:
            db.session.add(ScreenTimeReport(
                device_id=canonical, date=today,
                total_minutes=st.get('total_minutes', 0),
                unlocks=st.get('unlocks', 0),
            ))

    if 'webhistory' in data:
        for entry in data['webhistory']:
            db.session.add(WebHistory(
                device_id=canonical, url=entry.get('url', ''),
                title=entry.get('title', ''), browser=entry.get('browser', ''),
                timestamp=entry.get('timestamp', _now_ms()),
            ))

    if 'social' in data:
        for notif in data['social']:
            if _social_dedupe(canonical, notif):
                continue
            db.session.add(SocialNotification(
                device_id=canonical, package_name=notif.get('package_name', ''),
                app_name=notif.get('app_name', ''), sender=notif.get('sender', ''),
                content=notif.get('content', ''), message_type=notif.get('message_type', 'notification'),
                timestamp=notif.get('timestamp', _now_ms()),
            ))

    if 'chat_messages' in data:
        from ..models import ChatMessage
        for msg in data['chat_messages']:
            msg_id = msg.get('id')
            if not (msg_id and ChatMessage.query.filter_by(id=msg_id).first()):
                db.session.add(ChatMessage(
                    id=msg_id,
                    chat_id=msg.get('chat_id', ''),
                    sender_id=msg.get('sender_id', ''),
                    sender_name=msg.get('sender_name', ''),
                    recipient_id=msg.get('recipient_id', ''),
                    recipient_name=msg.get('recipient_name', ''),
                    content=msg.get('content', ''),
                    type=msg.get('type', 'text'),
                    image_url=msg.get('image_url'),
                    timestamp=msg.get('timestamp', _now_ms())
                ))

    try:
        db.session.commit()
    except Exception as e:
        # Temporary diagnostic for the Vivo I2018 device: report/bulk has been
        # returning HTTP 500 with no detail. Surface the actual exception
        # class+message so we can pinpoint which sub-report is failing.
        current_app.logger.exception("report_bulk commit failed: %s", e)
        db.session.rollback()
        return jsonify({
            'error': 'Internal server error',
            'detail': f'{type(e).__name__}: {str(e)[:200]}',
            'hint': 'check which sub-report (sms/calls/apps/battery) raised this'
        }), 500
    _emit(canonical, 'heartbeat', {'timestamp': _now_ms()})

    commands = RemoteCommand.query.filter_by(device_id=canonical, status='pending').all()
    return jsonify({
        'status': 'ok',
        'server_time': _now_ms(),
        'commands': [{
            'id': c.id, 'command': c.command,
            'params': json.loads(c.params) if c.params else {}
        } for c in commands]
    })


# ─── Real-time call state + audio streaming ──────────────────────────────

@bp.route('/report/call-state', methods=['POST'])
@jwt_required()
def report_call_state():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    device_id = data.get('device_id')
    ok, canonical = assert_device_ownership(device_id, caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    state = data.get('state', 0)
    phone_number = data.get('phone_number', '')
    timestamp = data.get('timestamp', _now_ms())

    live_call_state[canonical] = {
        'state': state, 'phone_number': phone_number, 'timestamp': timestamp,
        'streaming': live_call_state.get(canonical, {}).get('streaming', False),
    }
    try:
        db.session.add(CallStateEvent(
            device_id=canonical, state=state,
            phone_number=phone_number, timestamp=timestamp,
        ))
        db.session.commit()
    except Exception:
        db.session.rollback()

    _emit(canonical, 'call_state', {
        'state': state, 'phone_number': phone_number, 'timestamp': timestamp,
    })
    return jsonify({'status': 'ok'})


@bp.route('/report/audio-stream', methods=['POST'])
@jwt_required()
def report_audio_stream():
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    device_id = data.get('device_id')
    audio_b64 = data.get('audio')
    ok, canonical = assert_device_ownership(device_id, caller_id)
    if not ok or not audio_b64:
        return jsonify({'error': 'device_id and audio required'}), 400

    sample_rate = data.get('sample_rate', 16000)
    command_id = data.get('command_id')
    seq = data.get('seq', 0)
    done = data.get('done', False)
    timestamp = data.get('timestamp', _now_ms())

    live_audio_streams[canonical] = {
        'active': not done, 'last_chunk_time': timestamp, 'sample_rate': sample_rate,
    }

    if command_id:
        # V8: verify the command belongs to a device owned by the caller.
        cmd_ok, _ = assert_command_ownership(command_id, caller_id)
        if not cmd_ok:
            return jsonify({'error': 'Access denied'}), 403
        # Persist the latest chunk so audio-poll works across workers/restarts.
        store_mic_chunk(command_id, audio_b64=audio_b64, sample_rate=sample_rate,
                        seq=seq, done=done, updated_at=timestamp)
        if done and command_id in live_command_results:
            live_command_results[command_id]['status'] = 'completed'

    _emit(canonical, 'audio_chunk', {
        'audio': audio_b64, 'sample_rate': sample_rate, 'channels': 1,
        'encoding': 'pcm_s16le', 'command_id': command_id, 'seq': seq,
        'done': done, 'timestamp': timestamp,
    })
    return jsonify({'status': 'ok'})
