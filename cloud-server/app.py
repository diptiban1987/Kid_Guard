import os, json, hashlib, uuid, hmac, base64, time
from datetime import datetime, timezone, timedelta
from functools import wraps

from flask import Flask, request, jsonify, render_template, redirect, url_for, session, send_file
from flask_cors import CORS
from flask_jwt_extended import (
    JWTManager, create_access_token, create_refresh_token,
    jwt_required, get_jwt_identity, get_jwt
)
from werkzeug.utils import secure_filename
from dotenv import load_dotenv

# SocketIO is optional — falls back to polling when unavailable
try:
    from flask_socketio import SocketIO, emit, join_room, leave_room
    HAS_SOCKETIO = True
except ImportError:
    HAS_SOCKETIO = False

from config import Config
from models import db, User, ChildRelation, Device, LocationReport, ActivityReport, \
    BatteryReport, ScreenTimeReport, SmsMessage, CallLog, InstalledApp, MediaFile, \
    WebHistory, Geofence, GeofenceEvent, RemoteCommand, AppRestriction, ScheduleRule, \
    generate_pairing_code

load_dotenv()

app = Flask(__name__)
app.config.from_object(Config)
CORS(app, supports_credentials=True)

# SocketIO only if available (PythonAnywhere has no WebSocket support)
if HAS_SOCKETIO:
    socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')
else:
    socketio = None

jwt = JWTManager(app)
db.init_app(app)

os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)

# ─── Helpers ──────────────────────────────────────────────────────────────

def hash_password(password):
    return hashlib.sha256(password.encode()).hexdigest()

def parent_required(fn):
    @wraps(fn)
    @jwt_required()
    def wrapper(*args, **kwargs):
        user_id = get_jwt_identity()
        user = User.query.get(user_id)
        if not user or user.role not in ('parent', 'admin'):
            return jsonify({'error': 'Parent access required'}), 403
        return fn(*args, **kwargs)
    return wrapper

def admin_required(fn):
    @wraps(fn)
    @jwt_required()
    def wrapper(*args, **kwargs):
        user_id = get_jwt_identity()
        user = User.query.get(user_id)
        if not user or user.role != 'admin':
            return jsonify({'error': 'Admin access required'}), 403
        return fn(*args, **kwargs)
    return wrapper

def get_child_device_ids(parent_id):
    relations = ChildRelation.query.filter_by(parent_id=parent_id, is_active=True).all()
    devices = Device.query.filter(
        Device.user_id.in_([r.child_id for r in relations]),
        Device.is_active == True
    ).all()
    return [d.device_id for d in devices]

# ─── Auth Routes ──────────────────────────────────────────────────────────

@app.route('/api/auth/register', methods=['POST'])
def register():
    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data'}), 400
    
    email = data.get('email', '').strip().lower()
    password = data.get('password', '')
    display_name = data.get('display_name', '').strip()
    role = data.get('role', 'parent')
    
    if not email or not password or len(password) < 6:
        return jsonify({'error': 'Email and password (min 6 chars) required'}), 400
    if User.query.filter_by(email=email).first():
        return jsonify({'error': 'Email already registered'}), 409
    
    user = User(
        email=email,
        password_hash=hash_password(password),
        display_name=display_name or email.split('@')[0],
        role=role if role in ('parent', 'child') else 'parent'
    )
    db.session.add(user)
    db.session.commit()
    
    access_token = create_access_token(identity=user.id)
    refresh_token = create_refresh_token(identity=user.id)
    
    return jsonify({
        'token': access_token,
        'refresh_token': refresh_token,
        'user': user.to_dict()
    }), 201

@app.route('/api/auth/login', methods=['POST'])
def login():
    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data'}), 400
    
    email = data.get('email', '').strip().lower()
    password = data.get('password', '')
    
    user = User.query.filter_by(email=email).first()
    if not user or user.password_hash != hash_password(password):
        return jsonify({'error': 'Invalid credentials'}), 401
    
    if not user.is_active:
        return jsonify({'error': 'Account disabled'}), 403
    
    user.last_login = int(datetime.now(timezone.utc).timestamp() * 1000)
    db.session.commit()
    
    access_token = create_access_token(identity=user.id)
    refresh_token = create_refresh_token(identity=user.id)
    
    return jsonify({
        'token': access_token,
        'refresh_token': refresh_token,
        'user': user.to_dict()
    })

@app.route('/api/auth/refresh', methods=['POST'])
@jwt_required(refresh=True)
def refresh():
    user_id = get_jwt_identity()
    user = User.query.get(user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    return jsonify({
        'token': create_access_token(identity=user_id)
    })

@app.route('/api/auth/me', methods=['GET'])
@jwt_required()
def get_me():
    user_id = get_jwt_identity()
    user = User.query.get(user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    return jsonify({'user': user.to_dict()})

# ─── Parent-Child Pairing ─────────────────────────────────────────────────

@app.route('/api/pairing/generate', methods=['POST'])
@parent_required
def generate_pairing():
    parent_id = get_jwt_identity()
    parent = User.query.get(parent_id)
    
    code = generate_pairing_code()
    
    # Store the pairing code linked to this parent so the child can claim it
    pairing = ChildRelation(
        parent_id=parent_id,
        child_id='pending',
        pairing_code=code,
        is_active=False
    )
    db.session.add(pairing)
    db.session.commit()
    
    return jsonify({
        'pairing_code': code,
        'expires_in': app.config['PAIRING_CODE_TTL'],
        'parent_name': parent.display_name
    })

@app.route('/api/pairing/claim', methods=['POST'])
@jwt_required()
def claim_pairing():
    child_id = get_jwt_identity()
    code = request.get_json().get('pairing_code', '').strip().upper()
    
    child = User.query.get(child_id)
    if child.role != 'child':
        return jsonify({'error': 'Only child accounts can be claimed'}), 400
    
    # Find the pairing record that the parent created with this code
    pairing = ChildRelation.query.filter_by(
        pairing_code=code, child_id='pending', is_active=False
    ).first()
    
    if pairing:
        # Found the parent's code — link the child to it
        pairing.child_id = child_id
        db.session.commit()
        
        return jsonify({
            'message': 'Pairing request submitted. Parent must approve.',
            'pairing_id': pairing.id
        })
    else:
        # No matching code found — create a pending request anyway
        pairing = ChildRelation(
            parent_id='pending',
            child_id=child_id,
            pairing_code=code,
            is_active=False
        )
        db.session.add(pairing)
        db.session.commit()
        
        return jsonify({
            'message': 'Pairing request submitted. Parent must approve.',
            'pairing_id': pairing.id
        })

@app.route('/api/pairing/pending', methods=['GET'])
@parent_required
def get_pending_pairings():
    parent_id = get_jwt_identity()
    
    # Get pending pairings for this parent that have a real child linked
    pendings = ChildRelation.query.filter_by(
        parent_id=parent_id, is_active=False
    ).filter(ChildRelation.child_id != 'pending').all()
    
    result = []
    for p in pendings:
        child = User.query.get(p.child_id)
        result.append({
            'id': p.id,
            'child_id': p.child_id,
            'child_email': child.email if child else None,
            'child_name': child.display_name if child else None,
            'pairing_code': p.pairing_code,
            'paired_at': p.paired_at
        })
    
    return jsonify(result)

@app.route('/api/pairing/approve/<pairing_id>', methods=['POST'])
@parent_required
def approve_pairing(pairing_id):
    parent_id = get_jwt_identity()
    pairing = ChildRelation.query.get(pairing_id)
    
    if not pairing:
        return jsonify({'error': 'Pairing not found'}), 404
    
    pairing.parent_id = parent_id
    pairing.is_active = True
    pairing.paired_at = int(datetime.now(timezone.utc).timestamp() * 1000)
    
    db.session.commit()
    
    return jsonify({'message': 'Child paired successfully'})

@app.route('/api/pairing/children', methods=['GET'])
@parent_required
def get_children():
    parent_id = get_jwt_identity()
    relations = ChildRelation.query.filter_by(parent_id=parent_id, is_active=True).all()
    
    children_data = []
    for rel in relations:
        child = User.query.get(rel.child_id)
        devices = Device.query.filter_by(user_id=rel.child_id, is_active=True).all()
        child_devices = [d.to_dict() for d in devices]
        
        children_data.append({
            'relation_id': rel.id,
            'child': child.to_dict() if child else None,
            'devices': child_devices,
            'paired_at': rel.paired_at
        })
    
    return jsonify(children_data)

# ─── Device Registration & Reporting ─────────────────────────────────────

@app.route('/api/device/register', methods=['POST'])
@jwt_required()
def register_device():
    user_id = get_jwt_identity()
    data = request.get_json()
    
    device_id = data.get('device_id', str(uuid.uuid4()))
    existing = Device.query.filter_by(device_id=device_id).first()
    
    if existing:
        existing.user_id = user_id
        existing.last_seen = int(datetime.now(timezone.utc).timestamp() * 1000)
        existing.device_name = data.get('device_name', existing.device_name)
        existing.manufacturer = data.get('manufacturer', existing.manufacturer)
        existing.model = data.get('model', existing.model)
        existing.android_version = data.get('android_version', existing.android_version)
        existing.sdk_version = data.get('sdk_version', existing.sdk_version)
        db.session.commit()
        return jsonify({'message': 'Device updated', 'device': existing.to_dict()})
    
    device = Device(
        device_id=device_id,
        user_id=user_id,
        device_name=data.get('device_name', ''),
        manufacturer=data.get('manufacturer', ''),
        model=data.get('model', ''),
        android_version=data.get('android_version', ''),
        sdk_version=data.get('sdk_version', 0),
        last_seen=int(datetime.now(timezone.utc).timestamp() * 1000)
    )
    db.session.add(device)
    db.session.commit()
    
    return jsonify({'message': 'Device registered', 'device': device.to_dict()}), 201

@app.route('/api/device/<device_id>/config', methods=['GET'])
@jwt_required()
def get_device_config(device_id):
    device = Device.query.filter_by(device_id=device_id).first()
    if not device:
        return jsonify({'error': 'Device not found'}), 404
    
    return jsonify({
        'reporting_interval': device.reporting_interval,
        'stealth_mode': device.stealth_mode,
        'server_time': int(datetime.now(timezone.utc).timestamp() * 1000),
        'geofences': [g.to_dict() if hasattr(g, 'to_dict') else {
            'id': g.id, 'name': g.name, 'latitude': g.latitude,
            'longitude': g.longitude, 'radius': g.radius
        } for g in Geofence.query.filter_by(device_id=device_id, is_active=True).all()],
        'blocked_apps': [{
            'package_name': r.package_name, 'app_name': r.app_name
        } for r in AppRestriction.query.filter_by(device_id=device_id, is_blocked=True, is_active=True).all()],
        'commands': [{
            'id': c.id, 'command': c.command, 'params': c.params
        } for c in RemoteCommand.query.filter_by(device_id=device_id, status='pending').all()]
    })

@app.route('/api/report/location', methods=['POST'])
@jwt_required()
def report_location():
    data = request.get_json()
    device_id = data.get('device_id')
    
    device = Device.query.filter_by(device_id=device_id).first()
    if device:
        device.last_seen = int(datetime.now(timezone.utc).timestamp() * 1000)
    
    report = LocationReport(
        device_id=device_id,
        latitude=data['latitude'],
        longitude=data['longitude'],
        accuracy=data.get('accuracy', 0),
        altitude=data.get('altitude'),
        speed=data.get('speed'),
        bearing=data.get('bearing'),
        provider=data.get('provider', 'unknown'),
        timestamp=data.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
    )
    db.session.add(report)
    db.session.commit()
    
    # Check geofences
    check_geofences(device_id, data['latitude'], data['longitude'])
    
    # Emit to parent room
    emit_realtime(device_id, 'location', {
        'latitude': data['latitude'], 'longitude': data['longitude'],
        'accuracy': report.accuracy, 'timestamp': report.timestamp
    })
    
    return jsonify({'status': 'ok'})

@app.route('/api/report/activity', methods=['POST'])
@jwt_required()
def report_activity():
    data = request.get_json()
    device_id = data.get('device_id')
    
    device = Device.query.filter_by(device_id=device_id).first()
    if device:
        device.last_seen = int(datetime.now(timezone.utc).timestamp() * 1000)
    
    report = ActivityReport(
        device_id=device_id,
        activity_type=data.get('activity_type', 'unknown'),
        package_name=data.get('package_name'),
        app_name=data.get('app_name'),
        data=json.dumps(data.get('data', {})),
        timestamp=data.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
    )
    db.session.add(report)
    db.session.commit()
    
    emit_realtime(device_id, 'activity', {
        'activity_type': report.activity_type,
        'app_name': report.app_name,
        'timestamp': report.timestamp
    })
    
    return jsonify({'status': 'ok'})

@app.route('/api/report/battery', methods=['POST'])
@jwt_required()
def report_battery():
    data = request.get_json()
    device_id = data.get('device_id')
    
    device = Device.query.filter_by(device_id=device_id).first()
    if device:
        device.last_seen = int(datetime.now(timezone.utc).timestamp() * 1000)
    
    report = BatteryReport(
        device_id=device_id,
        level=data.get('level', -1),
        is_charging=data.get('is_charging', False),
        temperature=data.get('temperature', -1),
        voltage=data.get('voltage'),
        plugged=data.get('plugged'),
        timestamp=data.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
    )
    db.session.add(report)
    db.session.commit()
    
    emit_realtime(device_id, 'battery', {
        'level': report.level, 'is_charging': report.is_charging
    })
    
    return jsonify({'status': 'ok'})

@app.route('/api/report/screentime', methods=['POST'])
@jwt_required()
def report_screentime():
    data = request.get_json()
    device_id = data.get('device_id')
    today = data.get('date', datetime.now(timezone.utc).strftime('%Y-%m-%d'))
    
    existing = ScreenTimeReport.query.filter_by(device_id=device_id, date=today).first()
    
    if existing:
        existing.total_minutes = data.get('total_minutes', existing.total_minutes)
        existing.unlocks = data.get('unlocks', existing.unlocks)
        existing.app_usage_json = json.dumps(data.get('app_usage', {}))
        existing.updated_at = int(datetime.now(timezone.utc).timestamp() * 1000)
    else:
        report = ScreenTimeReport(
            device_id=device_id,
            date=today,
            total_minutes=data.get('total_minutes', 0),
            unlocks=data.get('unlocks', 0),
            app_usage_json=json.dumps(data.get('app_usage', {}))
        )
        db.session.add(report)
    
    db.session.commit()
    return jsonify({'status': 'ok'})

@app.route('/api/report/sms', methods=['POST'])
@jwt_required()
def report_sms():
    data = request.get_json()
    device_id = data.get('device_id')
    messages = data.get('messages', [])
    
    count = 0
    for msg in messages:
        existing = SmsMessage.query.filter_by(
            device_id=device_id, sms_id=msg.get('id')
        ).first()
        if not existing:
            sms = SmsMessage(
                device_id=device_id,
                sms_id=msg.get('id'),
                address=msg.get('address', ''),
                body=msg.get('body', ''),
                date=msg.get('date', 0),
                type=msg.get('type', 0)
            )
            db.session.add(sms)
            count += 1
    
    db.session.commit()
    
    if count > 0:
        emit_realtime(device_id, 'sms', {'count': count})
    
    return jsonify({'status': 'ok', 'new': count})

@app.route('/api/report/calls', methods=['POST'])
@jwt_required()
def report_calls():
    data = request.get_json()
    device_id = data.get('device_id')
    calls = data.get('calls', [])
    
    count = 0
    for call in calls:
        existing = CallLog.query.filter_by(
            device_id=device_id, call_id=call.get('id')
        ).first()
        if not existing:
            cl = CallLog(
                device_id=device_id,
                call_id=call.get('id'),
                number=call.get('number', ''),
                name=call.get('name', ''),
                duration=call.get('duration', 0),
                date=call.get('date', 0),
                type=call.get('type', 0)
            )
            db.session.add(cl)
            count += 1
    
    db.session.commit()
    
    if count > 0:
        emit_realtime(device_id, 'call', {'count': count})
    
    return jsonify({'status': 'ok', 'new': count})

@app.route('/api/report/apps', methods=['POST'])
@jwt_required()
def report_apps():
    data = request.get_json()
    device_id = data.get('device_id')
    apps = data.get('apps', [])
    
    InstalledApp.query.filter_by(device_id=device_id).delete()
    
    for app_data in apps:
        app_entry = InstalledApp(
            device_id=device_id,
            package_name=app_data.get('packageName'),
            app_name=app_data.get('appName'),
            version_name=app_data.get('versionName'),
            version_code=app_data.get('versionCode', 0),
            first_install_time=app_data.get('firstInstallTime', 0),
            last_update_time=app_data.get('lastUpdateTime', 0),
            is_system_app=app_data.get('isSystemApp', False)
        )
        db.session.add(app_entry)
    
    db.session.commit()
    return jsonify({'status': 'ok'})

@app.route('/api/report/webhistory', methods=['POST'])
@jwt_required()
def report_webhistory():
    data = request.get_json()
    device_id = data.get('device_id')
    entries = data.get('entries', [])
    
    count = 0
    for entry in entries:
        wh = WebHistory(
            device_id=device_id,
            url=entry.get('url', ''),
            title=entry.get('title', ''),
            browser=entry.get('browser', ''),
            visit_count=entry.get('visit_count', 1),
            timestamp=entry.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
        )
        db.session.add(wh)
        count += 1
    
    db.session.commit()
    
    if count > 0:
        emit_realtime(device_id, 'web', {'count': count})
    
    return jsonify({'status': 'ok', 'new': count})

@app.route('/api/report/media', methods=['POST'])
@jwt_required()
def report_media():
    device_id = request.form.get('device_id')
    media_type = request.form.get('media_type', 'photo')
    file = request.files.get('file')
    
    if not file:
        return jsonify({'error': 'No file'}), 400
    
    filename = f"{device_id}_{int(time.time())}_{secure_filename(file.filename)}"
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    file.save(filepath)
    
    media = MediaFile(
        device_id=device_id,
        media_type=media_type,
        file_path=filepath,
        file_size=os.path.getsize(filepath),
        mime_type=file.mimetype,
        timestamp=int(datetime.now(timezone.utc).timestamp() * 1000)
    )
    db.session.add(media)
    db.session.commit()
    
    emit_realtime(device_id, 'media', {
        'media_type': media_type, 'file_size': media.file_size
    })
    
    return jsonify({'status': 'ok', 'media_id': media.id})

@app.route('/api/report/bulk', methods=['POST'])
@jwt_required()
def report_bulk():
    """Bulk report endpoint for efficiency"""
    data = request.get_json()
    device_id = data.get('device_id')
    
    if not device_id:
        return jsonify({'error': 'device_id required'}), 400
    
    device = Device.query.filter_by(device_id=device_id).first()
    if device:
        device.last_seen = int(datetime.now(timezone.utc).timestamp() * 1000)
    
    # Process each report type
    if 'location' in data:
        loc_data = data['location']
        db.session.add(LocationReport(
            device_id=device_id,
            latitude=loc_data['latitude'],
            longitude=loc_data['longitude'],
            accuracy=loc_data.get('accuracy', 0),
            altitude=loc_data.get('altitude'),
            provider=loc_data.get('provider', 'unknown'),
            timestamp=loc_data.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
        ))
        check_geofences(device_id, loc_data['latitude'], loc_data['longitude'])
    
    if 'battery' in data:
        bat = data['battery']
        db.session.add(BatteryReport(
            device_id=device_id,
            level=bat.get('level', -1),
            is_charging=bat.get('is_charging', False),
            temperature=bat.get('temperature', -1)
        ))
    
    if 'activities' in data:
        for act in data['activities']:
            db.session.add(ActivityReport(
                device_id=device_id,
                activity_type=act.get('activity_type', 'unknown'),
                package_name=act.get('package_name'),
                app_name=act.get('app_name'),
                data=json.dumps(act.get('data', {})),
                timestamp=act.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
            ))
    
    if 'sms' in data:
        device_id = data.get('device_id') or device_id
        for msg in data['sms']:
            if not SmsMessage.query.filter_by(device_id=device_id, sms_id=msg.get('id')).first():
                db.session.add(SmsMessage(
                    device_id=device_id,
                    sms_id=msg.get('id'),
                    address=msg.get('address', ''),
                    body=msg.get('body', ''),
                    date=msg.get('date', 0),
                    type=msg.get('type', 0)
                ))
    
    if 'calls' in data:
        for call in data['calls']:
            if not CallLog.query.filter_by(device_id=device_id, call_id=call.get('id')).first():
                db.session.add(CallLog(
                    device_id=device_id,
                    call_id=call.get('id'),
                    number=call.get('number', ''),
                    name=call.get('name', ''),
                    duration=call.get('duration', 0),
                    date=call.get('date', 0),
                    type=call.get('type', 0)
                ))
    
    if 'apps' in data:
        InstalledApp.query.filter_by(device_id=device_id).delete()
        for app_data in data['apps']:
            db.session.add(InstalledApp(
                device_id=device_id,
                package_name=app_data.get('packageName'),
                app_name=app_data.get('appName'),
                version_name=app_data.get('versionName'),
                version_code=app_data.get('versionCode', 0),
                first_install_time=app_data.get('firstInstallTime', 0),
                last_update_time=app_data.get('lastUpdateTime', 0),
                is_system_app=app_data.get('isSystemApp', False)
            ))
    
    if 'screentime' in data:
        st = data['screentime']
        today = st.get('date', datetime.now(timezone.utc).strftime('%Y-%m-%d'))
        existing = ScreenTimeReport.query.filter_by(device_id=device_id, date=today).first()
        if existing:
            existing.total_minutes = st.get('total_minutes', existing.total_minutes)
            existing.unlocks = st.get('unlocks', existing.unlocks)
            existing.updated_at = int(datetime.now(timezone.utc).timestamp() * 1000)
        else:
            db.session.add(ScreenTimeReport(
                device_id=device_id, date=today,
                total_minutes=st.get('total_minutes', 0),
                unlocks=st.get('unlocks', 0)
            ))
    
    if 'webhistory' in data:
        for entry in data['webhistory']:
            db.session.add(WebHistory(
                device_id=device_id,
                url=entry.get('url', ''),
                title=entry.get('title', ''),
                browser=entry.get('browser', ''),
                timestamp=entry.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
            ))
    
    db.session.commit()
    
    emit_realtime(device_id, 'heartbeat', {'timestamp': int(datetime.now(timezone.utc).timestamp() * 1000)})
    
    # Return pending commands
    commands = RemoteCommand.query.filter_by(device_id=device_id, status='pending').all()
    return jsonify({
        'status': 'ok',
        'server_time': int(datetime.now(timezone.utc).timestamp() * 1000),
        'commands': [{
            'id': c.id, 'command': c.command,
            'params': json.loads(c.params) if c.params else {}
        } for c in commands]
    })

@app.route('/api/command/<command_id>/status', methods=['POST'])
@jwt_required()
def update_command_status(command_id):
    data = request.get_json()
    command = RemoteCommand.query.get(command_id)
    if not command:
        return jsonify({'error': 'Command not found'}), 404
    
    command.status = data.get('status', command.status)
    if command.status == 'completed':
        command.completed_at = int(datetime.now(timezone.utc).timestamp() * 1000)
    
    db.session.commit()
    return jsonify({'status': 'ok'})

# ─── Geofence Helper ──────────────────────────────────────────────────────

def check_geofences(device_id, latitude, longitude):
    from math import radians, sin, cos, sqrt, atan2
    
    geofences = Geofence.query.filter_by(device_id=device_id, is_active=True).all()
    
    for gf in geofences:
        # Haversine distance
        R = 6371000
        lat1, lon1 = radians(latitude), radians(longitude)
        lat2, lon2 = radians(gf.latitude), radians(gf.longitude)
        dlat, dlon = lat2 - lat1, lon2 - lon1
        a = sin(dlat/2)**2 + cos(lat1) * cos(lat2) * sin(dlon/2)**2
        distance = R * 2 * atan2(sqrt(a), sqrt(1-a))
        
        # Check latest event for this geofence
        last_event = GeofenceEvent.query.filter_by(
            device_id=device_id, geofence_id=gf.id
        ).order_by(GeofenceEvent.timestamp.desc()).first()
        
        was_inside = last_event and last_event.event_type == 'enter'
        is_inside = distance <= gf.radius
        
        if not was_inside and is_inside and gf.notify_on_entry:
            event = GeofenceEvent(
                device_id=device_id, geofence_id=gf.id,
                event_type='enter', latitude=latitude, longitude=longitude,
                timestamp=int(datetime.now(timezone.utc).timestamp() * 1000)
            )
            db.session.add(event)
            emit_realtime(device_id, 'geofence', {
                'event': 'enter', 'geofence_name': gf.name,
                'latitude': latitude, 'longitude': longitude
            })
        elif was_inside and not is_inside and gf.notify_on_exit:
            event = GeofenceEvent(
                device_id=device_id, geofence_id=gf.id,
                event_type='exit', latitude=latitude, longitude=longitude,
                timestamp=int(datetime.now(timezone.utc).timestamp() * 1000)
            )
            db.session.add(event)
            emit_realtime(device_id, 'geofence', {
                'event': 'exit', 'geofence_name': gf.name,
                'latitude': latitude, 'longitude': longitude
            })

# ─── WebSocket / Real-time ────────────────────────────────────────────────

def emit_realtime(device_id, event_type, data):
    """Emit event to parent rooms for this device (no-op without SocketIO)"""
    if not HAS_SOCKETIO or socketio is None:
        return
    device = Device.query.filter_by(device_id=device_id).first()
    if not device:
        return
    if device.user_id:
        relations = ChildRelation.query.filter_by(child_id=device.user_id).all()
        for rel in relations:
            room = f"user_{rel.parent_id}"
            try:
                socketio.emit('realtime_update', {
                    'device_id': device_id,
                    'event_type': event_type,
                    'data': data,
                    'timestamp': int(datetime.now(timezone.utc).timestamp() * 1000)
                }, room=room)
            except Exception:
                pass

if HAS_SOCKETIO:
    @socketio.on('connect')
    def handle_connect():
        pass

    @socketio.on('join')
    def handle_join(data):
        user_id = data.get('user_id')
        if user_id:
            join_room(f"user_{user_id}")

    @socketio.on('leave')
    def handle_leave(data):
        user_id = data.get('user_id')
        if user_id:
            leave_room(f"user_{user_id}")

# ─── Parent Dashboard API ─────────────────────────────────────────────────

@app.route('/api/parent/updates')
@parent_required
def get_parent_updates():
    """Polling endpoint — returns any new notifications since given timestamp"""
    since = request.args.get('since', 0, type=int)
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    notifications = []
    # Check for low battery events on children's devices
    for did in device_ids:
        bat = BatteryReport.query.filter_by(device_id=did)\
            .order_by(BatteryReport.id.desc()).first()
        if bat and bat.level is not None and bat.level <= 15 and bat.timestamp > since:
            device = Device.query.filter_by(device_id=did).first()
            name = device.device_name if device else did
            notifications.append({
                'title': 'Low Battery',
                'message': f'{name}: {bat.level}% remaining'
            })
    return jsonify({
        'server_time': int(datetime.now(timezone.utc).timestamp() * 1000),
        'notifications': notifications
    })

@app.route('/api/parent/stats')
@parent_required
def get_parent_stats():
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    
    if not device_ids:
        return jsonify({
            'total_devices': 0, 'online_devices': 0,
            'total_locations': 0, 'total_activities': 0,
            'children': []
        })
    
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
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
        children_data.append({
            'child': child.to_dict() if child else None,
            'devices': [d.to_dict() for d in child_devices]
        })
    
    return jsonify({
        'total_devices': len(devices),
        'online_devices': online,
        'total_locations': LocationReport.query.filter(LocationReport.device_id.in_(device_ids)).count(),
        'total_activities': total_activities,
        'children': children_data
    })

@app.route('/api/parent/devices')
@parent_required
def get_parent_devices():
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    
    if not device_ids:
        return jsonify([])
    
    devices = Device.query.filter(Device.device_id.in_(device_ids)).order_by(Device.last_seen.desc()).all()
    result = []
    for d in devices:
        data = d.to_dict()
        try:
            # Attach latest battery info
            latest_battery = BatteryReport.query.filter_by(device_id=d.device_id)\
                .order_by(BatteryReport.id.desc()).first()
            if latest_battery:
                data['battery_level'] = latest_battery.level
                data['is_charging'] = latest_battery.is_charging
            else:
                data['battery_level'] = None
                data['is_charging'] = False
        except Exception:
            data['battery_level'] = None
            data['is_charging'] = False
        try:
            # Attach today's screen time
            today_start = int(datetime.now(timezone.utc).replace(hour=0, minute=0, second=0).timestamp() * 1000)
            latest_screen = ScreenTimeReport.query.filter_by(device_id=d.device_id)\
                .filter(ScreenTimeReport.timestamp >= today_start)\
                .order_by(ScreenTimeReport.id.desc()).first()
            data['screen_time_minutes'] = latest_screen.total_minutes if latest_screen else 0
        except Exception:
            data['screen_time_minutes'] = 0
        try:
            # Get child user info
            child_user = User.query.filter_by(id=d.user_id).first() if d.user_id else None
            if child_user:
                data['child_name'] = child_user.display_name
                data['child_email'] = child_user.email
        except Exception:
            pass
        result.append(data)
    return jsonify(result)

@app.route('/api/parent/activity/<device_id>')
@parent_required
def get_device_activity(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 100, type=int)
    offset = request.args.get('offset', 0, type=int)
    activity_type = request.args.get('type')
    
    query = ActivityReport.query.filter_by(device_id=device_id)
    if activity_type:
        query = query.filter_by(activity_type=activity_type)
    
    activities = query.order_by(ActivityReport.timestamp.desc()).offset(offset).limit(limit).all()
    
    return jsonify([{
        'id': a.id, 'activity_type': a.activity_type,
        'package_name': a.package_name, 'app_name': a.app_name,
        'data': json.loads(a.data) if a.data else {},
        'timestamp': a.timestamp
    } for a in activities])

@app.route('/api/parent/locations/<device_id>')
@parent_required
def get_device_locations(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 200, type=int)
    locations = LocationReport.query.filter_by(device_id=device_id)\
        .order_by(LocationReport.timestamp.desc()).limit(limit).all()
    
    return jsonify([{
        'latitude': l.latitude, 'longitude': l.longitude,
        'accuracy': l.accuracy, 'provider': l.provider,
        'timestamp': l.timestamp
    } for l in locations])

@app.route('/api/parent/sms/<device_id>')
@parent_required
def get_device_sms(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 50, type=int)
    messages = SmsMessage.query.filter_by(device_id=device_id)\
        .order_by(SmsMessage.date.desc()).limit(limit).all()
    
    return jsonify([{
        'id': m.id, 'address': m.address, 'body': m.body,
        'date': m.date, 'type': m.type
    } for m in messages])

@app.route('/api/parent/calls/<device_id>')
@parent_required
def get_device_calls(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 50, type=int)
    calls = CallLog.query.filter_by(device_id=device_id)\
        .order_by(CallLog.date.desc()).limit(limit).all()
    
    return jsonify([{
        'id': c.id, 'number': c.number, 'name': c.name,
        'duration': c.duration, 'date': c.date, 'type': c.type
    } for c in calls])

@app.route('/api/parent/apps/<device_id>')
@parent_required
def get_device_apps(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    apps = InstalledApp.query.filter_by(device_id=device_id)\
        .order_by(InstalledApp.app_name).all()
    
    return jsonify([{
        'package_name': a.package_name, 'app_name': a.app_name,
        'version_name': a.version_name, 'is_system_app': a.is_system_app
    } for a in apps])

@app.route('/api/parent/screentime/<device_id>')
@parent_required
def get_device_screentime(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    days = request.args.get('days', 7, type=int)
    reports = ScreenTimeReport.query.filter(
        ScreenTimeReport.device_id == device_id
    ).order_by(ScreenTimeReport.date.desc()).limit(days).all()
    
    return jsonify([{
        'date': r.date, 'total_minutes': r.total_minutes,
        'unlocks': r.unlocks,
        'app_usage': json.loads(r.app_usage_json) if r.app_usage_json else {}
    } for r in reports])

@app.route('/api/parent/webhistory/<device_id>')
@parent_required
def get_device_webhistory(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 100, type=int)
    history = WebHistory.query.filter_by(device_id=device_id)\
        .order_by(WebHistory.timestamp.desc()).limit(limit).all()
    
    return jsonify([{
        'url': h.url, 'title': h.title, 'browser': h.browser,
        'visit_count': h.visit_count, 'timestamp': h.timestamp
    } for h in history])

@app.route('/api/parent/media/<device_id>')
@parent_required
def get_device_media(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 50, type=int)
    media_type = request.args.get('type')
    
    query = MediaFile.query.filter_by(device_id=device_id)
    if media_type:
        query = query.filter_by(media_type=media_type)
    
    media = query.order_by(MediaFile.timestamp.desc()).limit(limit).all()
    
    return jsonify([{
        'id': m.id, 'media_type': m.media_type, 'file_size': m.file_size,
        'mime_type': m.mime_type, 'timestamp': m.timestamp
    } for m in media])

@app.route('/api/parent/geofences/<device_id>')
@parent_required
def get_device_geofences(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    geofences = Geofence.query.filter_by(device_id=device_id).order_by(Geofence.created_at.desc()).all()
    return jsonify([{
        'id': g.id, 'name': g.name, 'latitude': g.latitude,
        'longitude': g.longitude, 'radius': g.radius,
        'notify_on_entry': g.notify_on_entry,
        'notify_on_exit': g.notify_on_exit,
        'is_active': g.is_active
    } for g in geofences])

@app.route('/api/parent/geofences/<device_id>', methods=['POST'])
@parent_required
def create_geofence(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    data = request.get_json()
    geofence = Geofence(
        device_id=device_id,
        name=data.get('name', 'Safe Zone'),
        latitude=data['latitude'],
        longitude=data['longitude'],
        radius=data.get('radius', app.config['GEO_FENCE_DEFAULT_RADIUS']),
        notify_on_entry=data.get('notify_on_entry', True),
        notify_on_exit=data.get('notify_on_exit', True)
    )
    db.session.add(geofence)
    db.session.commit()
    
    return jsonify({'status': 'ok', 'geofence': {
        'id': geofence.id, 'name': geofence.name,
        'latitude': geofence.latitude, 'longitude': geofence.longitude,
        'radius': geofence.radius
    }}), 201

@app.route('/api/parent/geofences/<geofence_id>', methods=['DELETE'])
@parent_required
def delete_geofence(geofence_id):
    geofence = Geofence.query.get(geofence_id)
    if not geofence:
        return jsonify({'error': 'Not found'}), 404
    
    device_ids = get_child_device_ids(get_jwt_identity())
    if geofence.device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    db.session.delete(geofence)
    db.session.commit()
    return jsonify({'status': 'ok'})

@app.route('/api/parent/commands/<device_id>', methods=['POST'])
@parent_required
def send_command(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    data = request.get_json()
    command = RemoteCommand(
        device_id=device_id,
        parent_id=parent_id,
        command=data.get('command'),
        params=json.dumps(data.get('params', {}))
    )
    db.session.add(command)
    db.session.commit()
    
    return jsonify({'status': 'ok', 'command_id': command.id}), 201

@app.route('/api/parent/restrictions/<device_id>', methods=['GET', 'POST'])
@parent_required
def manage_restrictions(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    if request.method == 'GET':
        restrictions = AppRestriction.query.filter_by(device_id=device_id).all()
        return jsonify([{
            'id': r.id, 'package_name': r.package_name, 'app_name': r.app_name,
            'is_blocked': r.is_blocked, 'max_minutes_per_day': r.max_minutes_per_day,
            'block_start_time': r.block_start_time, 'block_end_time': r.block_end_time
        } for r in restrictions])
    
    data = request.get_json()
    existing = AppRestriction.query.filter_by(
        device_id=device_id, package_name=data.get('package_name')
    ).first()
    
    if existing:
        existing.is_blocked = data.get('is_blocked', existing.is_blocked)
        existing.max_minutes_per_day = data.get('max_minutes_per_day', existing.max_minutes_per_day)
        existing.block_start_time = data.get('block_start_time', existing.block_start_time)
        existing.block_end_time = data.get('block_end_time', existing.block_end_time)
    else:
        restriction = AppRestriction(
            device_id=device_id,
            package_name=data.get('package_name'),
            app_name=data.get('app_name', ''),
            is_blocked=data.get('is_blocked', False),
            max_minutes_per_day=data.get('max_minutes_per_day', 0),
            block_start_time=data.get('block_start_time'),
            block_end_time=data.get('block_end_time')
        )
        db.session.add(restriction)
    
    db.session.commit()
    return jsonify({'status': 'ok'})

@app.route('/api/parent/schedule/<device_id>', methods=['GET', 'POST'])
@parent_required
def manage_schedule(device_id):
    parent_id = get_jwt_identity()
    device_ids = get_child_device_ids(parent_id)
    if device_id not in device_ids:
        return jsonify({'error': 'Access denied'}), 403
    
    if request.method == 'GET':
        rules = ScheduleRule.query.filter_by(device_id=device_id).all()
        return jsonify([{
            'id': r.id, 'name': r.name, 'day_of_week': r.day_of_week,
            'start_time': r.start_time, 'end_time': r.end_time,
            'is_block_time': r.is_block_time
        } for r in rules])
    
    data = request.get_json()
    rule = ScheduleRule(
        device_id=device_id,
        name=data.get('name', 'Schedule'),
        day_of_week=data.get('day_of_week', -1),
        start_time=data.get('start_time'),
        end_time=data.get('end_time'),
        is_block_time=data.get('is_block_time', True)
    )
    db.session.add(rule)
    db.session.commit()
    return jsonify({'status': 'ok', 'rule_id': rule.id}), 201

# ─── Remote Update / APK Management ─────────────────────────────────────

APK_DIR = os.path.join(app.config['UPLOAD_FOLDER'], 'apk')
APK_METADATA_FILE = os.path.join(APK_DIR, 'version.json')
os.makedirs(APK_DIR, exist_ok=True)

def load_apk_metadata():
    if os.path.exists(APK_METADATA_FILE):
        with open(APK_METADATA_FILE) as f:
            return json.load(f)
    return {'latest_version': 0, 'changelog': '', 'apk_filename': ''}

def save_apk_metadata(meta):
    with open(APK_METADATA_FILE, 'w') as f:
        json.dump(meta, f, indent=2)

@app.route('/api/app/check-update', methods=['POST'])
@jwt_required()
def check_app_update():
    data = request.get_json() or {}
    current_version = data.get('version_code', 0)
    meta = load_apk_metadata()
    latest_version = meta.get('latest_version', 0)

    if latest_version > current_version:
        return jsonify({
            'has_update': True,
            'version_code': latest_version,
            'download_url': f'/api/app/download/{latest_version}',
            'changelog': meta.get('changelog', '')
        })
    return jsonify({'has_update': False})

@app.route('/api/app/download/<int:version_code>')
@jwt_required()
def download_app_update(version_code):
    meta = load_apk_metadata()
    if version_code != meta.get('latest_version', 0):
        return jsonify({'error': 'Version not found'}), 404
    apk_path = os.path.join(APK_DIR, meta.get('apk_filename', ''))
    if not os.path.exists(apk_path):
        return jsonify({'error': 'APK file not found'}), 404
    return send_file(apk_path, mimetype='application/vnd.android.package-archive',
                     as_attachment=True, download_name=meta['apk_filename'])

@app.route('/api/app/upload', methods=['POST'])
@admin_required
def upload_app_update():
    if 'apk' not in request.files:
        return jsonify({'error': 'No APK file provided'}), 400
    file = request.files['apk']
    version = request.form.get('version_code', '1')
    changelog = request.form.get('changelog', '')
    filename = f'kidguard_v{version}.apk'
    file.save(os.path.join(APK_DIR, filename))
    save_apk_metadata({
        'latest_version': int(version),
        'changelog': changelog,
        'apk_filename': filename
    })
    return jsonify({'success': True, 'version_code': int(version)})

# ─── File Access ──────────────────────────────────────────────────────────

@app.route('/api/files/<media_id>')
@jwt_required()
def get_media(media_id):
    media = MediaFile.query.get(media_id)
    if not media:
        return jsonify({'error': 'Not found'}), 404
    
    # Check access
    user_id = get_jwt_identity()
    user = User.query.get(user_id)
    if user.role == 'parent':
        device_ids = get_child_device_ids(user_id)
        if media.device_id not in device_ids:
            return jsonify({'error': 'Access denied'}), 403
    elif user.role == 'child':
        device = Device.query.filter_by(user_id=user_id).first()
        if not device or media.device_id != device.device_id:
            return jsonify({'error': 'Access denied'}), 403
    
    if not os.path.exists(media.file_path):
        return jsonify({'error': 'File not found on disk'}), 404
    
    return send_file(media.file_path, mimetype=media.mime_type)

# ─── Web Routes ──────────────────────────────────────────────────────────

@app.route('/')
def index():
    return render_template('login.html')

@app.route('/dashboard')
def dashboard_page():
    return render_template('dashboard.html')

@app.route('/device/<device_id>')
def device_page(device_id):
    return render_template('device.html', device_id=device_id)

# ─── Static file fallback ────────────────────────────────────────────────

@app.route('/static/<path:filename>')
def static_files(filename):
    from flask import send_from_directory
    return send_from_directory('static', filename)

# ─── Init & Run ──────────────────────────────────────────────────────────

def init_db():
    with app.app_context():
        db.create_all()

# Auto-create tables on module load (required for WSGI / PythonAnywhere)
init_db()

if __name__ == '__main__':
    print("=" * 60)
    print("  PARENTAL CONTROL CLOUD SERVER")
    print("=" * 60)
    print(f"  Database: {app.config['SQLALCHEMY_DATABASE_URI']}")
    print(f"  Uploads:  {app.config['UPLOAD_FOLDER']}")
    print(f"  Server:   {app.config['CLOUD_SERVER_URL']}")
    print("=" * 60)
    print("  Default user creation via /api/auth/register")
    print("  WebSocket enabled for real-time updates")
    print("=" * 60)
    if HAS_SOCKETIO:
        socketio.run(app, host='0.0.0.0', port=5000, debug=True, allow_unsafe_werkzeug=True)
    else:
        app.run(host='0.0.0.0', port=5000, debug=True)
