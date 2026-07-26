import os, json, hashlib, uuid, hmac, base64, time, random
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

# SocketIO is optional — PythonAnywhere WSGI does NOT support WebSockets/SocketIO
# even if flask-socketio is installed. Auto-detect the environment and disable it.
try:
    from flask_socketio import SocketIO, emit, join_room, leave_room
    _SOCKETIO_INSTALLED = True
except ImportError:
    _SOCKETIO_INSTALLED = False

from config import Config
from models import db, User, ChildRelation, Device, LocationReport, ActivityReport, \
    BatteryReport, ScreenTimeReport, SmsMessage, CallLog, CallStateEvent, InstalledApp, MediaFile, \
    WebHistory, Geofence, GeofenceEvent, RemoteCommand, AppRestriction, ScheduleRule, \
    SocialNotification, PasswordResetToken, generate_pairing_code

load_dotenv()

app = Flask(__name__)
app.config.from_object(Config)
CORS(app, supports_credentials=True)

# Disable SocketIO on PythonAnywhere (no WebSocket support) or if explicitly disabled.
# PythonAnywhere always sets the PYTHONANYWHERE_SITE env var on hosted apps.
_IS_PYTHONANYWHERE = bool(os.environ.get('PYTHONANYWHERE_SITE'))
_SOCKETIO_DISABLED = bool(os.environ.get('DISABLE_SOCKETIO', ''))

if _SOCKETIO_INSTALLED and not _IS_PYTHONANYWHERE and not _SOCKETIO_DISABLED:
    HAS_SOCKETIO = True
    socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')
else:
    HAS_SOCKETIO = False
    socketio = None
    if _IS_PYTHONANYWHERE:
        print("[SocketIO] Disabled — PythonAnywhere WSGI does not support WebSockets.")
    elif _SOCKETIO_DISABLED:
        print("[SocketIO] Disabled — DISABLE_SOCKETIO env var is set.")
    else:
        print("[SocketIO] Disabled — flask-socketio not installed.")

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
    child_ids = [r.child_id for r in relations]
    if parent_id not in child_ids:
        child_ids.append(parent_id)
    devices = Device.query.filter(
        Device.user_id.in_(child_ids),
        Device.is_active == True
    ).all()
    ids = set()
    for d in devices:
        ids.add(d.device_id)
        ids.add(d.id)
    return list(ids)


def get_child_internal_device_ids(parent_id):
    relations = ChildRelation.query.filter_by(parent_id=parent_id, is_active=True).all()
    devices = Device.query.filter(
        Device.user_id.in_([r.child_id for r in relations]),
        Device.is_active == True
    ).all()
    return [d.id for d in devices]


def resolve_device_id(provided_id, parent_id):
    # Normalise to strings for comparison (device_ids may contain ints from d.id)
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    provided_str = str(provided_id)
    if provided_str in device_ids_str:
        # Try matching by device_id (string) first
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
    existing = User.query.filter_by(email=email).first()
    if existing:
        if role == 'child' and existing.role == 'parent':
            pass  # Allow child to register with parent's email
        elif role == 'parent' and existing.role == 'child':
            pass  # Allow parent to register with child's email
        else:
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
    role = data.get('role', '').strip().lower()
    
    if role and role in ('parent', 'child'):
        user = User.query.filter_by(email=email, role=role).first()
    else:
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

@app.route('/api/auth/forgot-password', methods=['POST'])
def forgot_password():
    """Request a password reset token. Returns the token (for display / dev use,
    since this project has no SMTP configured). The client should prompt the
    user to enter the token shown/known to them to set a new password."""
    data = request.get_json() or {}
    email = data.get('email', '').strip().lower()
    if not email:
        return jsonify({'error': 'Email is required'}), 400

    user = User.query.filter_by(email=email).first()
    # Generic response to avoid leaking which emails are registered
    if not user:
        return jsonify({'message': 'If that email exists, a reset token has been issued.',
                        'token': None}), 200

    # Invalidate any unused previous tokens for this user
    PasswordResetToken.query.filter_by(user_id=user.id, used=False).update({'used': True})

    # Generate a short, human-friendly token (8 chars, uppercase + digits)
    import string as _string
    token = ''.join(random.choices(_string.ascii_uppercase + _string.digits, k=8))
    ttl_ms = 30 * 60 * 1000  # 30 minutes
    reset = PasswordResetToken(
        user_id=user.id,
        token=token,
        expires_at=int(datetime.now(timezone.utc).timestamp() * 1000) + ttl_ms
    )
    db.session.add(reset)
    db.session.commit()

    # No SMTP configured in this project, so we return the token directly.
    # In production, you would email it and NOT return it here.
    return jsonify({
        'message': 'Reset token generated. Use it to set a new password.',
        'token': token,                        # remove in prod when SMTP is wired
        'expires_in_seconds': 1800,
        'email_masked': _mask_email(user.email)
    }), 200

@app.route('/api/auth/reset-password', methods=['POST'])
def reset_password():
    """Set a new password using a valid reset token."""
    data = request.get_json() or {}
    email = data.get('email', '').strip().lower()
    token = data.get('token', '').strip().upper()
    new_password = data.get('new_password', '')

    if not email or not token or not new_password:
        return jsonify({'error': 'Email, token, and new_password are required'}), 400
    if len(new_password) < 6:
        return jsonify({'error': 'New password must be at least 6 characters'}), 400

    user = User.query.filter_by(email=email).first()
    if not user:
        return jsonify({'error': 'Invalid or expired token'}), 400

    reset = PasswordResetToken.query.filter_by(
        user_id=user.id, token=token, used=False
    ).order_by(PasswordResetToken.created_at.desc()).first()

    if not reset:
        return jsonify({'error': 'Invalid or expired token'}), 400
    if reset.expires_at < int(datetime.now(timezone.utc).timestamp() * 1000):
        reset.used = True
        db.session.commit()
        return jsonify({'error': 'Token has expired. Please request a new one.'}), 400

    user.password_hash = hash_password(new_password)
    reset.used = True
    db.session.commit()

    # Issue fresh tokens so the user is logged in immediately
    access_token = create_access_token(identity=user.id)
    refresh_token = create_refresh_token(identity=user.id)
    return jsonify({
        'message': 'Password updated successfully',
        'token': access_token,
        'refresh_token': refresh_token,
        'user': user.to_dict()
    }), 200

@app.route('/api/auth/forgot-username', methods=['POST'])
def forgot_username():
    """Lookup helper for users who forgot their email/username.
    Accepts a display_name hint and returns all matching emails (masked)
    so the user can identify theirs."""
    data = request.get_json() or {}
    name_hint = data.get('display_name', '').strip().lower()
    if not name_hint or len(name_hint) < 2:
        return jsonify({'error': 'display_name hint (min 2 chars) is required'}), 400

    users = User.query.filter(
        User.display_name.ilike(f'%{name_hint}%')
    ).limit(10).all()

    if not users:
        return jsonify({'message': 'No matching accounts found', 'accounts': []}), 200

    accounts = [{
        'email_masked': _mask_email(u.email),
        'display_name': u.display_name,
        'role': u.role
    } for u in users]
    return jsonify({'message': 'Matching accounts', 'accounts': accounts}), 200


def _mask_email(email):
    """Mask an email for safe display: a***@example.com"""
    if not email or '@' not in email:
        return email
    local, domain = email.split('@', 1)
    if len(local) <= 2:
        return local[0] + '*' + '@' + domain
    return local[0] + '*' * (len(local) - 2) + local[-1] + '@' + domain

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


@app.route('/api/pairing/claim-direct', methods=['POST'])
def claim_pairing_direct():
    data = request.get_json()
    code = data.get('pairing_code', '').strip().upper()
    device_id = data.get('device_id', '')

    if not code:
        return jsonify({'error': 'pairing_code required'}), 400

    pairing = ChildRelation.query.filter_by(
        pairing_code=code, child_id='pending', is_active=False
    ).first()

    if not pairing:
        return jsonify({'error': 'Invalid or expired pairing code'}), 404

    # Auto-create child account
    child_email = f"child_{device_id}_{int(datetime.now(timezone.utc).timestamp())}@kidguard.local"
    child_password = f"pair_{code}_{device_id}"

    child = User(
        email=child_email,
        password_hash=hash_password(child_password),
        display_name=f"Child ({device_id})",
        role='child'
    )
    db.session.add(child)
    db.session.commit()

    # Link child to parent
    pairing.child_id = child.id
    db.session.commit()

    access_token = create_access_token(identity=child.id)
    refresh_token = create_refresh_token(identity=child.id)

    return jsonify({
        'token': access_token,
        'refresh_token': refresh_token,
        'user': child.to_dict(),
        'pairing_id': pairing.id,
        'message': 'Account created and paired. Waiting for parent approval.'
    }), 201

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

    # Include parent's own directly registered devices
    parent_devices = Device.query.filter_by(user_id=parent_id, is_active=True).all()
    if parent_devices:
        parent_user = User.query.get(parent_id)
        children_data.append({
            'relation_id': f"parent_{parent_id}",
            'child': parent_user.to_dict() if parent_user else None,
            'devices': [d.to_dict() for d in parent_devices],
            'paired_at': int(datetime.now(timezone.utc).timestamp() * 1000)
        })

    for rel in relations:
        if rel.child_id == parent_id:
            continue
        child = User.query.get(rel.child_id)
        devices = Device.query.filter_by(user_id=rel.child_id, is_active=True).all()
        child_devices = [d.to_dict() for d in devices]
        
        # Only show child if they have at least one active device OR were paired very recently (last 10 mins)
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        is_recently_paired = rel.paired_at and (now_ms - rel.paired_at) < 600000
        if len(child_devices) > 0 or is_recently_paired:
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
        existing.is_active = True  # Reactivate if it was previously soft-deleted
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
    command_id  = request.form.get('command_id')   # links upload to a remote command
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

    # If this upload is linked to a remote command, cache the image/audio in memory
    # so the parent dashboard can show it immediately via poll_command_result
    if command_id:
        try:
            import base64 as _b64
            with open(filepath, 'rb') as fh:
                raw = fh.read()
            mime = file.mimetype or ('image/jpeg' if media_type != 'audio' else 'audio/mp4')
            data_uri = f"data:{mime};base64," + _b64.b64encode(raw).decode('utf-8')
            result_type = 'audio' if media_type == 'audio' else 'image'
            live_command_results[command_id] = {
                'status': 'completed',
                'result_type': result_type,
                'data': data_uri,
                'command': media_type,
                'updated_at': int(datetime.now(timezone.utc).timestamp() * 1000)
            }
            # Mark command completed in DB too
            cmd = RemoteCommand.query.get(command_id)
            if cmd:
                cmd.status = 'completed'
                cmd.completed_at = int(datetime.now(timezone.utc).timestamp() * 1000)
        except Exception as e:
            app.logger.warning(f"Failed to cache media result for command {command_id}: {e}")

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
    user_id = get_jwt_identity()
    if not device:
        device = Device(
            device_id=device_id,
            user_id=user_id,
            device_name=data.get('device_name', 'Android Device'),
            manufacturer=data.get('manufacturer', ''),
            model=data.get('model', ''),
            android_version=data.get('android_version', ''),
            is_active=True,
            last_seen=int(datetime.now(timezone.utc).timestamp() * 1000)
        )
        db.session.add(device)
    else:
        device.is_active = True
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
            temperature=bat.get('temperature', -1),
            timestamp=bat.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
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
    
    if 'social' in data:
        for notif in data['social']:
            db.session.add(SocialNotification(
                device_id=device_id,
                package_name=notif.get('package_name', ''),
                app_name=notif.get('app_name', ''),
                sender=notif.get('sender', ''),
                content=notif.get('content', ''),
                message_type=notif.get('message_type', 'notification'),
                timestamp=notif.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))
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

# ─── Real-time Call State + Audio Streaming ──────────────────────────────

# In-memory store for live call state per device
live_call_state = {}   # device_id -> {state, phone_number, timestamp, streaming}
live_audio_streams = {}  # device_id -> {active, last_chunk_time}

# In-memory command result cache (never persisted to disk for privacy)
# Holds latest result per command_id: {status, result_type, data, updated_at}
live_command_results = {}   # command_id -> {status, result_type, data, updated_at}
live_mic_chunks = {}        # command_id -> {audio_b64, sample_rate, seq, updated_at}

@app.route('/api/report/call-state', methods=['POST'])
@jwt_required()
def report_call_state():
    data = request.get_json()
    device_id = data.get('device_id')
    state = data.get('state', 0)
    phone_number = data.get('phone_number', '')
    timestamp = data.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))

    if not device_id:
        return jsonify({'error': 'device_id required'}), 400

    # Store live state
    live_call_state[device_id] = {
        'state': state,
        'phone_number': phone_number,
        'timestamp': timestamp,
        'streaming': live_call_state.get(device_id, {}).get('streaming', False)
    }

    # Persist to database
    try:
        db.session.add(CallStateEvent(
            device_id=device_id,
            state=state,
            phone_number=phone_number,
            timestamp=timestamp
        ))
        db.session.commit()
    except Exception:
        db.session.rollback()

    # Emit WebSocket event
    emit_realtime(device_id, 'call_state', {
        'state': state,
        'phone_number': phone_number,
        'timestamp': timestamp
    })

    return jsonify({'status': 'ok'})

@app.route('/api/report/audio-stream', methods=['POST'])
@jwt_required()
def report_audio_stream():
    data = request.get_json()
    device_id = data.get('device_id')
    audio_b64 = data.get('audio')
    sample_rate = data.get('sample_rate', 16000)
    command_id = data.get('command_id')   # optional: link chunk to a mic command
    seq = data.get('seq', 0)              # sequence number for ordering
    done = data.get('done', False)        # True when recording finished
    timestamp = data.get('timestamp', int(datetime.now(timezone.utc).timestamp() * 1000))

    if not device_id or not audio_b64:
        return jsonify({'error': 'device_id and audio required'}), 400

    # Track streaming state
    live_audio_streams[device_id] = {
        'active': not done,
        'last_chunk_time': timestamp,
        'sample_rate': sample_rate
    }

    # Buffer in mic_chunks for HTTP polling (parent polls audio-poll endpoint)
    if command_id:
        live_mic_chunks[command_id] = {
            'audio_b64': audio_b64,
            'sample_rate': sample_rate,
            'seq': seq,
            'done': done,
            'updated_at': timestamp
        }
        # Mark command completed when done
        if done and command_id in live_command_results:
            live_command_results[command_id]['status'] = 'completed'

    # Emit via WebSocket too if available
    emit_realtime(device_id, 'audio_chunk', {
        'audio': audio_b64,
        'sample_rate': sample_rate,
        'channels': 1,
        'encoding': 'pcm_s16le',
        'command_id': command_id,
        'seq': seq,
        'done': done,
        'timestamp': timestamp
    })

    return jsonify({'status': 'ok'})

@app.route('/api/parent/calls/<device_id>/live', methods=['GET'])
@parent_required
def get_live_call_state(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403

    state = live_call_state.get(real_id, {'state': 0, 'phone_number': '', 'timestamp': 0, 'streaming': False})
    streaming = live_audio_streams.get(real_id, {'active': False})
    return jsonify({
        'state': state.get('state', 0),
        'phone_number': state.get('phone_number', ''),
        'timestamp': state.get('timestamp', 0),
        'is_streaming': streaming.get('active', False)
    })

@app.route('/api/parent/calls/<device_id>/stream', methods=['POST'])
@parent_required
def control_call_stream(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403

    data = request.get_json() or {}
    enable = data.get('enable', True)

    # Send command to device
    cmd = RemoteCommand(
        device_id=real_id,
        command='listen_call',
        params=json.dumps({'enable': enable}),
        status='pending',
        parent_id=parent_id,
        created_at=int(datetime.now(timezone.utc).timestamp() * 1000)
    )
    db.session.add(cmd)
    db.session.commit()

    if enable:
        live_audio_streams[real_id] = {'active': True, 'last_chunk_time': int(datetime.now(timezone.utc).timestamp() * 1000), 'sample_rate': 16000}
    else:
        live_audio_streams[real_id] = {'active': False}

    return jsonify({'status': 'ok', 'command_id': cmd.id})

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
    if 'result' in data:
        command.result = data.get('result')

    db.session.commit()

    # Cache result in memory for real-time parent polling (no disk save for media)
    result_payload = data.get('result')
    result_type = data.get('result_type', 'text')  # text | image | audio
    live_command_results[command_id] = {
        'status': command.status,
        'result_type': result_type,
        'data': result_payload,  # base64 string or text
        'command': command.command,
        'updated_at': int(datetime.now(timezone.utc).timestamp() * 1000)
    }

    return jsonify({'status': 'ok'})


@app.route('/api/parent/commands/<device_id>/result/<command_id>', methods=['GET'])
@parent_required
def poll_command_result(device_id, command_id):
    """Parent polls this endpoint to get real-time command result (in-memory, not saved)."""
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403

    command = RemoteCommand.query.get(command_id)
    if not command or command.device_id != real_id:
        return jsonify({'error': 'Not found'}), 404

    # Return from in-memory cache first (includes unsaved image/audio data)
    cached = live_command_results.get(command_id)
    if cached:
        return jsonify({
            'status': cached['status'],
            'result_type': cached['result_type'],
            'data': cached['data'],
            'command': cached['command'],
            'updated_at': cached['updated_at']
        })

    # Fall back to DB status
    return jsonify({
        'status': command.status,
        'result_type': 'text',
        'data': command.result,
        'command': command.command,
        'updated_at': command.completed_at or command.created_at
    })


@app.route('/api/parent/commands/<device_id>/audio-poll/<command_id>', methods=['GET'])
@parent_required
def poll_mic_audio(device_id, command_id):
    """Returns the latest audio chunk for a mic recording command.
    Frontend polls this and plays each chunk via Web Audio API."""
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403

    since = request.args.get('since', 0, type=int)
    chunk = live_mic_chunks.get(command_id)
    if chunk and chunk.get('updated_at', 0) > since:
        return jsonify({
            'has_chunk': True,
            'audio': chunk['audio_b64'],
            'sample_rate': chunk.get('sample_rate', 16000),
            'seq': chunk.get('seq', 0),
            'updated_at': chunk['updated_at'],
            'done': chunk.get('done', False)
        })
    return jsonify({'has_chunk': False})

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
            .order_by(BatteryReport.received_at.desc()).first()
        if bat and bat.level is not None and bat.level <= 15 and (bat.timestamp or bat.received_at) > since:
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

    # Include parent's directly registered devices as a profile
    parent_devices = Device.query.filter_by(user_id=parent_id, is_active=True).all()
    if parent_devices:
        parent_user = User.query.get(parent_id)
        children_data.append({
            'child': parent_user.to_dict() if parent_user else {'display_name': 'My Device', 'email': ''},
            'devices': [d.to_dict() for d in parent_devices]
        })

    for rel in relations:
        if rel.child_id == parent_id:
            continue
        child = User.query.get(rel.child_id)
        child_devices = Device.query.filter_by(user_id=rel.child_id, is_active=True).all()
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        is_recently_paired = rel.paired_at and (now_ms - rel.paired_at) < 600000
        if len(child_devices) > 0 or is_recently_paired:
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
                .order_by(BatteryReport.received_at.desc()).first()
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

@app.route('/api/parent/devices/<device_id>/delete', methods=['POST'])
@parent_required
def delete_device(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    device = Device.query.filter_by(device_id=real_id).first()
    if not device:
        # Check by internal db ID
        device = Device.query.filter_by(id=real_id).first()
        
    if not device:
        return jsonify({'error': 'Device not found'}), 404
        
    device.is_active = False
    db.session.commit()
    return jsonify({'status': 'ok', 'message': 'Device deleted successfully'})

@app.route('/api/parent/activity/<device_id>')
@parent_required
def get_device_activity(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
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
        'data': json.loads(a.data) if a.data else {},
        'timestamp': a.timestamp
    } for a in activities])

@app.route('/api/parent/locations/<device_id>')
@parent_required
def get_device_locations(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 200, type=int)
    locations = LocationReport.query.filter_by(device_id=real_id)\
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
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 50, type=int)
    messages = SmsMessage.query.filter_by(device_id=real_id)\
        .order_by(SmsMessage.date.desc()).limit(limit).all()
    
    return jsonify([{
        'id': m.id, 'address': m.address, 'body': m.body,
        'date': m.date, 'type': m.type
    } for m in messages])

@app.route('/api/parent/calls/<device_id>')
@parent_required
def get_device_calls(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 50, type=int)
    calls = CallLog.query.filter_by(device_id=real_id)\
        .order_by(CallLog.date.desc()).limit(limit).all()
    
    return jsonify([{
        'id': c.id, 'number': c.number, 'name': c.name,
        'duration': c.duration, 'date': c.date, 'type': c.type
    } for c in calls])

@app.route('/api/parent/social/<device_id>')
@parent_required
def get_device_social(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 100, type=int)
    notifications = SocialNotification.query.filter_by(device_id=real_id)\
        .order_by(SocialNotification.timestamp.desc()).limit(limit).all()
    
    return jsonify([{
        'id': n.id, 'package_name': n.package_name, 'app_name': n.app_name,
        'sender': n.sender, 'content': n.content, 'message_type': n.message_type,
        'timestamp': n.timestamp
    } for n in notifications])

@app.route('/api/parent/apps/<device_id>')
@parent_required
def get_device_apps(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    apps = InstalledApp.query.filter_by(device_id=real_id)\
        .order_by(InstalledApp.app_name).all()
    
    return jsonify([{
        'package_name': a.package_name, 'app_name': a.app_name,
        'version_name': a.version_name, 'is_system_app': a.is_system_app
    } for a in apps])

@app.route('/api/parent/screentime/<device_id>')
@parent_required
def get_device_screentime(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    days = request.args.get('days', 7, type=int)
    reports = ScreenTimeReport.query.filter(
        ScreenTimeReport.device_id == real_id
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
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 100, type=int)
    history = WebHistory.query.filter_by(device_id=real_id)\
        .order_by(WebHistory.timestamp.desc()).limit(limit).all()
    
    return jsonify([{
        'url': h.url, 'title': h.title, 'browser': h.browser,
        'visit_count': h.visit_count, 'timestamp': h.timestamp
    } for h in history])

@app.route('/api/parent/media/<device_id>')
@parent_required
def get_device_media(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    limit = request.args.get('limit', 50, type=int)
    media_type = request.args.get('type')
    
    query = MediaFile.query.filter_by(device_id=real_id)
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
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    geofences = Geofence.query.filter_by(device_id=real_id).order_by(Geofence.created_at.desc()).all()
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
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    data = request.get_json()
    geofence = Geofence(
        device_id=real_id,
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

@app.route('/api/parent/geofences/delete/<geofence_id>', methods=['DELETE', 'POST'])
@parent_required
def delete_geofence(geofence_id):
    geofence = Geofence.query.get(geofence_id)
    if not geofence:
        return jsonify({'error': 'Not found'}), 404

    parent_id = get_jwt_identity()
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    if str(geofence.device_id) not in device_ids_str:
        return jsonify({'error': 'Access denied'}), 403

    db.session.delete(geofence)
    db.session.commit()
    return jsonify({'status': 'ok'})

@app.route('/api/parent/commands/<device_id>', methods=['POST'])
@parent_required
def send_command(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    data = request.get_json()
    command = RemoteCommand(
        device_id=real_id,
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
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403
    
    if request.method == 'GET':
        restrictions = AppRestriction.query.filter_by(device_id=real_id).all()
        return jsonify([{
            'id': r.id, 'package_name': r.package_name, 'app_name': r.app_name,
            'is_blocked': r.is_blocked, 'max_minutes_per_day': r.max_minutes_per_day,
            'block_start_time': r.block_start_time, 'block_end_time': r.block_end_time
        } for r in restrictions])
    
    data = request.get_json()
    existing = AppRestriction.query.filter_by(
        device_id=real_id, package_name=data.get('package_name')
    ).first()
    
    if existing:
        existing.is_blocked = data.get('is_blocked', existing.is_blocked)
        existing.max_minutes_per_day = data.get('max_minutes_per_day', existing.max_minutes_per_day)
        existing.block_start_time = data.get('block_start_time', existing.block_start_time)
        existing.block_end_time = data.get('block_end_time', existing.block_end_time)
    else:
        restriction = AppRestriction(
            device_id=real_id,
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

@app.route('/api/parent/restrictions/delete/<restriction_id>', methods=['DELETE', 'POST'])
@parent_required
def delete_restriction(restriction_id):
    restriction = AppRestriction.query.get(restriction_id)
    if not restriction:
        return jsonify({'error': 'Not found'}), 404

    parent_id = get_jwt_identity()
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    if str(restriction.device_id) not in device_ids_str:
        return jsonify({'error': 'Access denied'}), 403

    db.session.delete(restriction)
    db.session.commit()
    return jsonify({'status': 'ok'})

@app.route('/api/parent/schedule/<device_id>', methods=['GET', 'POST'])
@parent_required
def manage_schedule(device_id):
    parent_id = get_jwt_identity()
    real_id = resolve_device_id(device_id, parent_id)
    if not real_id:
        return jsonify({'error': 'Access denied'}), 403

    if request.method == 'GET':
        rules = ScheduleRule.query.filter_by(device_id=real_id).all()
        return jsonify([{
            'id': r.id, 'name': r.name, 'day_of_week': r.day_of_week,
            'start_time': r.start_time, 'end_time': r.end_time,
            'is_block_time': r.is_block_time
        } for r in rules])

    data = request.get_json()
    rule = ScheduleRule(
        device_id=real_id,
        name=data.get('name', 'Schedule'),
        day_of_week=data.get('day_of_week', -1),
        start_time=data.get('start_time'),
        end_time=data.get('end_time'),
        is_block_time=data.get('is_block_time', True)
    )
    db.session.add(rule)
    db.session.commit()
    return jsonify({'status': 'ok', 'rule_id': rule.id}), 201

@app.route('/api/parent/schedule/delete/<rule_id>', methods=['DELETE', 'POST'])
@parent_required
def delete_schedule_rule(rule_id):
    rule = ScheduleRule.query.get(rule_id)
    if not rule:
        return jsonify({'error': 'Not found'}), 404

    parent_id = get_jwt_identity()
    device_ids_str = [str(x) for x in get_child_device_ids(parent_id)]
    if str(rule.device_id) not in device_ids_str:
        return jsonify({'error': 'Access denied'}), 403

    db.session.delete(rule)
    db.session.commit()
    return jsonify({'status': 'ok'})

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
def get_media(media_id):
    media = MediaFile.query.get(media_id)
    if not media:
        return jsonify({'error': 'Not found'}), 404
    
    # Check access (header or ?token= parameter)
    token = request.args.get('token')
    user_id = None
    if token:
        try:
            from flask_jwt_extended import decode_token
            decoded = decode_token(token)
            user_id = decoded['sub']
        except Exception:
            return jsonify({'error': 'Invalid token'}), 401
    else:
        try:
            from flask_jwt_extended import verify_jwt_in_request
            verify_jwt_in_request()
            user_id = get_jwt_identity()
        except Exception:
            return jsonify({'error': 'Authorization required'}), 401

    user = User.query.get(user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 401

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
    
    return send_file(media.file_path, mimetype=media.mime_type or 'image/jpeg')

# ─── Web Routes ──────────────────────────────────────────────────────────

@app.route('/')
def index():
    return render_template('login.html')

@app.route('/dashboard')
def dashboard_page():
    return render_template('dashboard.html')

@app.route('/device/<device_id>')
def device_page(device_id):
    return render_template('device.html', device_id=device_id, has_socketio=HAS_SOCKETIO)

# ─── Static file fallback ────────────────────────────────────────────────

@app.route('/static/<path:filename>')
def static_files(filename):
    from flask import send_from_directory
    return send_from_directory('static', filename)

# ─── Data Retention / Cleanup ─────────────────────────────────────────────

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

    # Remote commands older than cutoff
    deleted['remote_commands'] = db.session.query(RemoteCommand).filter(
        RemoteCommand.created_at < cutoff_ms
    ).delete(synchronize_session=False)

    db.session.commit()
    return deleted


@app.route('/api/admin/retention-cleanup', methods=['POST'])
@admin_required
def run_retention_cleanup():
    """Admin-triggered telemetry cleanup. Accepts optional max_age_days (default 30)."""
    data = request.get_json(silent=True) or {}
    max_age_days = data.get('max_age_days', 30)
    if not isinstance(max_age_days, int) or max_age_days < 1:
        return jsonify({'error': 'max_age_days must be a positive integer'}), 400
    deleted = cleanup_old_telemetry(max_age_days)
    return jsonify({'status': 'ok', 'max_age_days': max_age_days, 'deleted': deleted})


# ─── Global Error Handlers ────────────────────────────────────────────────

@app.errorhandler(400)
def bad_request(e):
    return jsonify({'error': 'Bad request', 'detail': str(e)}), 400

@app.errorhandler(401)
def unauthorized(e):
    return jsonify({'error': 'Unauthorized'}), 401

@app.errorhandler(403)
def forbidden(e):
    return jsonify({'error': 'Forbidden'}), 403

@app.errorhandler(404)
def not_found(e):
    return jsonify({'error': 'Not found'}), 404

@app.errorhandler(405)
def method_not_allowed(e):
    return jsonify({'error': 'Method not allowed'}), 405

@app.errorhandler(413)
def payload_too_large(e):
    return jsonify({'error': 'Payload too large (max 50MB)'}), 413

@app.errorhandler(500)
def internal_error(e):
    db.session.rollback()
    return jsonify({'error': 'Internal server error'}), 500

@app.errorhandler(Exception)
def unhandled_exception(e):
    db.session.rollback()
    return jsonify({'error': 'Internal server error', 'detail': str(e)}), 500


# ─── Init & Run ──────────────────────────────────────────────────────────

def init_db():
    with app.app_context():
        db.create_all()

# Auto-create tables on module load (required for WSGI / PythonAnywhere)
init_db()

if __name__ == '__main__':
    debug = os.environ.get('FLASK_DEBUG', '0') == '1'
    print("=" * 60)
    print("  PARENTAL CONTROL CLOUD SERVER")
    print("=" * 60)
    print(f"  Database: {app.config['SQLALCHEMY_DATABASE_URI']}")
    print(f"  Uploads:  {app.config['UPLOAD_FOLDER']}")
    print(f"  Server:   {app.config['CLOUD_SERVER_URL']}")
    print(f"  Debug:    {debug}")
    print("=" * 60)
    print("  Default user creation via /api/auth/register")
    print("  WebSocket enabled for real-time updates")
    print("=" * 60)
    if HAS_SOCKETIO:
        socketio.run(app, host='0.0.0.0', port=5000, debug=debug, allow_unsafe_werkzeug=True)
    else:
        app.run(host='0.0.0.0', port=5000, debug=debug)
