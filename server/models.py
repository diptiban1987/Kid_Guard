"""Database models for the AnonChat / KidGuard cloud server.

All report/data models are device-scoped (FK to ``devices.device_id``). The
security fixes (ownership enforcement) live in ``security.py`` — the schema
itself is unchanged for these models; the fix is runtime checks, not columns.

New in this multi-user conversion:
  - ``User.firebase_uid`` — forward-compat hook for unifying chat (Firebase) auth
    with server JWT in a later client phase. Nullable + unique.
  - ``User.last_password_change`` — supports invalidating old sessions on reset.
  - ``TokenBlocklist`` — enables JWT revocation (logout / password reset).
  - ``AuditLog`` — accountability for a multi-user platform.
  - Partial unique index on ``child_relations(child_id) WHERE is_active`` —
    enforces strict-one-parent-per-child at the DB level.
"""
from datetime import datetime, timezone
import random
import string

from .extensions import db


def generate_id(prefix='', length=12):
    chars = string.ascii_lowercase + string.digits
    return prefix + ''.join(random.choices(chars, k=length))


def generate_pairing_code():
    return ''.join(random.choices(string.ascii_uppercase + string.digits, k=8))


def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


# ─── Core identity models ───────────────────────────────────────────────

class User(db.Model):
    __tablename__ = 'users'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('usr_'))
    email = db.Column(db.String(255), unique=True, nullable=False)
    password_hash = db.Column(db.String(255), nullable=False)
    display_name = db.Column(db.String(100), nullable=False)
    role = db.Column(db.String(20), default='parent')  # parent, child, admin
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=_now_ms)
    last_login = db.Column(db.BigInteger)
    # ── New: forward-compat hook for Firebase↔server auth unification ──
    firebase_uid = db.Column(db.String(128), unique=True, nullable=True)
    last_password_change = db.Column(db.BigInteger)

    children = db.relationship('ChildRelation', foreign_keys='ChildRelation.parent_id',
                               backref='parent', lazy='dynamic')

    def to_dict(self):
        return {
            'id': self.id,
            'email': self.email,
            'display_name': self.display_name,
            'role': self.role,
            'is_active': self.is_active,
            'created_at': self.created_at,
            'last_login': self.last_login,
            'has_firebase_link': self.firebase_uid is not None,
        }


class ChildRelation(db.Model):
    """Links a parent to a child. Strict one-parent-per-child is enforced by a
    partial unique index (created via Alembic migration) on ``child_id`` where
    ``is_active = true``. A child CAN have multiple parents only across inactive
    (historical) relations — at most one active parent at a time.
    """
    __tablename__ = 'child_relations'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('rel_'))
    parent_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    child_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    pairing_code = db.Column(db.String(20), unique=True)
    paired_at = db.Column(db.BigInteger, default=_now_ms)
    is_active = db.Column(db.Boolean, default=True)

    child = db.relationship('User', foreign_keys=[child_id], backref='parent_relations')


class TokenBlocklist(db.Model):
    """Revoked JWTs. ``jti`` (JWT ID) is unique so a token can be blocked once."""
    __tablename__ = 'token_blocklist'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('blk_'))
    jti = db.Column(db.String(64), unique=True, nullable=False, index=True)
    user_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    expires_at = db.Column(db.BigInteger, nullable=False)
    created_at = db.Column(db.BigInteger, default=_now_ms)


class AuditLog(db.Model):
    """Append-only audit trail for accountability in a multi-user platform."""
    __tablename__ = 'audit_log'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('aud_'))
    actor_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=True)  # null = anonymous
    action = db.Column(db.String(50), nullable=False)      # login, register, pairing_approve, command_send...
    target_type = db.Column(db.String(30))                  # user, device, pairing, command...
    target_id = db.Column(db.String(64))
    ip_address = db.Column(db.String(45))
    metadata_json = db.Column(db.Text)
    created_at = db.Column(db.BigInteger, default=_now_ms)


# ─── Device & report models (unchanged from original) ───────────────────

class Device(db.Model):
    __tablename__ = 'devices'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('dev_'))
    device_id = db.Column(db.String(100), unique=True, nullable=False)
    user_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=True)
    device_name = db.Column(db.String(200))
    manufacturer = db.Column(db.String(100))
    model = db.Column(db.String(100))
    android_version = db.Column(db.String(20))
    sdk_version = db.Column(db.Integer)
    is_active = db.Column(db.Boolean, default=True)
    stealth_mode = db.Column(db.Boolean, default=False)
    reporting_interval = db.Column(db.Integer, default=300)
    first_seen = db.Column(db.BigInteger, default=_now_ms)
    last_seen = db.Column(db.BigInteger)

    def to_dict(self):
        return {
            'id': self.id, 'device_id': self.device_id, 'user_id': self.user_id,
            'device_name': self.device_name, 'manufacturer': self.manufacturer,
            'model': self.model, 'android_version': self.android_version,
            'sdk_version': self.sdk_version, 'is_active': self.is_active,
            'stealth_mode': self.stealth_mode, 'reporting_interval': self.reporting_interval,
            'first_seen': self.first_seen, 'last_seen': self.last_seen
        }


class LocationReport(db.Model):
    __tablename__ = 'location_reports'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('loc_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    latitude = db.Column(db.Float, nullable=False)
    longitude = db.Column(db.Float, nullable=False)
    accuracy = db.Column(db.Float, default=0)
    altitude = db.Column(db.Float)
    speed = db.Column(db.Float)
    bearing = db.Column(db.Float)
    provider = db.Column(db.String(20))
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class ActivityReport(db.Model):
    __tablename__ = 'activity_reports'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('act_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    activity_type = db.Column(db.String(50), nullable=False)
    package_name = db.Column(db.String(255))
    app_name = db.Column(db.String(255))
    data = db.Column(db.Text)
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)

    __table_args__ = (db.Index('idx_activity_device_ts', 'device_id', 'timestamp'),)


class BatteryReport(db.Model):
    __tablename__ = 'battery_reports'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('bat_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    level = db.Column(db.Integer, default=-1)
    is_charging = db.Column(db.Boolean, default=False)
    temperature = db.Column(db.Float, default=-1)
    voltage = db.Column(db.Float)
    plugged = db.Column(db.String(20))
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class ScreenTimeReport(db.Model):
    __tablename__ = 'screen_time_reports'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('scr_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    date = db.Column(db.String(10), nullable=False)
    total_minutes = db.Column(db.Integer, default=0)
    unlocks = db.Column(db.Integer, default=0)
    app_usage_json = db.Column(db.Text)
    updated_at = db.Column(db.BigInteger, default=_now_ms)


class SmsMessage(db.Model):
    __tablename__ = 'sms_messages'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('sms_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    sms_id = db.Column(db.Integer)
    address = db.Column(db.String(100))
    body = db.Column(db.Text)
    date = db.Column(db.BigInteger)
    type = db.Column(db.Integer)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class CallLog(db.Model):
    __tablename__ = 'call_logs'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('cal_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    call_id = db.Column(db.Integer)
    number = db.Column(db.String(50))
    name = db.Column(db.String(200))
    duration = db.Column(db.Integer)
    date = db.Column(db.BigInteger)
    type = db.Column(db.Integer)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class CallStateEvent(db.Model):
    __tablename__ = 'call_state_events'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('cse_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    state = db.Column(db.Integer)
    phone_number = db.Column(db.String(50))
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class InstalledApp(db.Model):
    __tablename__ = 'installed_apps'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('app_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    package_name = db.Column(db.String(255))
    app_name = db.Column(db.String(255))
    version_name = db.Column(db.String(50))
    version_code = db.Column(db.BigInteger)
    first_install_time = db.Column(db.BigInteger)
    last_update_time = db.Column(db.BigInteger)
    is_system_app = db.Column(db.Boolean, default=False)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class MediaFile(db.Model):
    __tablename__ = 'media_files'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('med_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    media_type = db.Column(db.String(20))
    file_path = db.Column(db.String(500))
    file_size = db.Column(db.BigInteger)
    mime_type = db.Column(db.String(100))
    thumbnail_path = db.Column(db.String(500))
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class WebHistory(db.Model):
    __tablename__ = 'web_history'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('web_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    url = db.Column(db.String(2000))
    title = db.Column(db.String(500))
    browser = db.Column(db.String(50))
    visit_count = db.Column(db.Integer, default=1)
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class Geofence(db.Model):
    __tablename__ = 'geofences'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('geo_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    name = db.Column(db.String(200))
    latitude = db.Column(db.Float, nullable=False)
    longitude = db.Column(db.Float, nullable=False)
    radius = db.Column(db.Float, default=500)
    notify_on_entry = db.Column(db.Boolean, default=True)
    notify_on_exit = db.Column(db.Boolean, default=True)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=_now_ms)


class GeofenceEvent(db.Model):
    __tablename__ = 'geofence_events'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('gev_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    geofence_id = db.Column(db.String(32), db.ForeignKey('geofences.id'))
    event_type = db.Column(db.String(10))
    latitude = db.Column(db.Float)
    longitude = db.Column(db.Float)
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)


class RemoteCommand(db.Model):
    __tablename__ = 'remote_commands'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('cmd_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    parent_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    command = db.Column(db.String(50), nullable=False)
    params = db.Column(db.Text)
    status = db.Column(db.String(20), default='pending')
    created_at = db.Column(db.BigInteger, default=_now_ms)
    delivered_at = db.Column(db.BigInteger)
    completed_at = db.Column(db.BigInteger)
    result = db.Column(db.Text)
    # Persisted so poll_command_result survives worker restarts / multi-worker.
    # result_type: text | image | audio. Mirrors the in-memory cache payload.
    result_type = db.Column(db.String(20), default='text')
    updated_at = db.Column(db.BigInteger, default=_now_ms)


class MicChunk(db.Model):
    """Latest mic-audio chunk per command, persisted so audio-poll survives
    worker restarts and works across multiple gunicorn workers (the in-memory
    live_mic_chunks dict is process-local). Only the newest chunk per command
    is kept — this is a streaming buffer, not an archive."""
    __tablename__ = 'mic_chunks'

    command_id = db.Column(db.String(32), db.ForeignKey('remote_commands.id'), primary_key=True)
    audio_b64 = db.Column(db.Text, nullable=False)
    sample_rate = db.Column(db.Integer, default=16000)
    seq = db.Column(db.Integer, default=0)
    done = db.Column(db.Boolean, default=False)
    updated_at = db.Column(db.BigInteger, default=_now_ms)


class AppRestriction(db.Model):
    __tablename__ = 'app_restrictions'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('res_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    package_name = db.Column(db.String(255))
    app_name = db.Column(db.String(255))
    is_blocked = db.Column(db.Boolean, default=False)
    max_minutes_per_day = db.Column(db.Integer, default=0)
    block_start_time = db.Column(db.String(5))
    block_end_time = db.Column(db.String(5))
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=_now_ms)


class ScheduleRule(db.Model):
    __tablename__ = 'schedule_rules'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('sch_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    name = db.Column(db.String(200))
    day_of_week = db.Column(db.Integer)
    start_time = db.Column(db.String(5), nullable=False)
    end_time = db.Column(db.String(5), nullable=False)
    is_block_time = db.Column(db.Boolean, default=True)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=_now_ms)


class PasswordResetToken(db.Model):
    __tablename__ = 'password_reset_tokens'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('rst_'))
    user_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    token = db.Column(db.String(64), unique=True, nullable=False, index=True)
    used = db.Column(db.Boolean, default=False)
    expires_at = db.Column(db.BigInteger, nullable=False)
    created_at = db.Column(db.BigInteger, default=_now_ms)

    user = db.relationship('User', backref='reset_tokens')

    def to_dict(self):
        return {
            'id': self.id, 'user_id': self.user_id, 'token': self.token,
            'used': self.used, 'expires_at': self.expires_at, 'created_at': self.created_at
        }


class SocialNotification(db.Model):
    __tablename__ = 'social_notifications'

    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('soc_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    package_name = db.Column(db.String(200))
    app_name = db.Column(db.String(100))
    sender = db.Column(db.String(200))
    content = db.Column(db.Text)
    message_type = db.Column(db.String(50), default='notification')
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=_now_ms)
