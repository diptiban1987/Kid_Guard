from flask_sqlalchemy import SQLAlchemy
from datetime import datetime, timezone
import random
import string

db = SQLAlchemy()

def generate_id(prefix='', length=12):
    chars = string.ascii_lowercase + string.digits
    return prefix + ''.join(random.choices(chars, k=length))

def generate_pairing_code():
    return ''.join(random.choices(string.ascii_uppercase + string.digits, k=8))

class User(db.Model):
    __tablename__ = 'users'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('usr_'))
    email = db.Column(db.String(255), unique=True, nullable=False)
    password_hash = db.Column(db.String(255), nullable=False)
    display_name = db.Column(db.String(100), nullable=False)
    role = db.Column(db.String(20), default='parent')  # parent, child, admin
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))
    last_login = db.Column(db.BigInteger)
    
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
            'last_login': self.last_login
        }

class ChildRelation(db.Model):
    __tablename__ = 'child_relations'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('rel_'))
    parent_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    child_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    pairing_code = db.Column(db.String(20), unique=True)
    paired_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))
    is_active = db.Column(db.Boolean, default=True)
    
    child = db.relationship('User', foreign_keys=[child_id], backref='parent_relations')

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
    reporting_interval = db.Column(db.Integer, default=300)  # seconds
    first_seen = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))
    last_seen = db.Column(db.BigInteger)
    
    def to_dict(self):
        return {
            'id': self.id,
            'device_id': self.device_id,
            'user_id': self.user_id,
            'device_name': self.device_name,
            'manufacturer': self.manufacturer,
            'model': self.model,
            'android_version': self.android_version,
            'sdk_version': self.sdk_version,
            'is_active': self.is_active,
            'stealth_mode': self.stealth_mode,
            'reporting_interval': self.reporting_interval,
            'first_seen': self.first_seen,
            'last_seen': self.last_seen
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
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class ActivityReport(db.Model):
    __tablename__ = 'activity_reports'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('act_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    activity_type = db.Column(db.String(50), nullable=False)  # screen_on, screen_off, app_launch, app_close, call, sms, web, photo, audio, keypress
    package_name = db.Column(db.String(255))
    app_name = db.Column(db.String(255))
    data = db.Column(db.Text)  # JSON string with extra data
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))
    
    __table_args__ = (
        db.Index('idx_activity_device_ts', 'device_id', 'timestamp'),
    )

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
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class ScreenTimeReport(db.Model):
    __tablename__ = 'screen_time_reports'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('scr_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    date = db.Column(db.String(10), nullable=False)  # YYYY-MM-DD
    total_minutes = db.Column(db.Integer, default=0)
    unlocks = db.Column(db.Integer, default=0)
    app_usage_json = db.Column(db.Text)  # JSON with per-app usage
    updated_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class SmsMessage(db.Model):
    __tablename__ = 'sms_messages'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('sms_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    sms_id = db.Column(db.Integer)
    address = db.Column(db.String(100))
    body = db.Column(db.Text)
    date = db.Column(db.BigInteger)
    type = db.Column(db.Integer)  # 1=inbox, 2=sent, etc.
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

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
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

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
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class MediaFile(db.Model):
    __tablename__ = 'media_files'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('med_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    media_type = db.Column(db.String(20))  # photo, screenshot, audio, video
    file_path = db.Column(db.String(500))
    file_size = db.Column(db.BigInteger)
    mime_type = db.Column(db.String(100))
    thumbnail_path = db.Column(db.String(500))
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class WebHistory(db.Model):
    __tablename__ = 'web_history'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('web_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    url = db.Column(db.String(2000))
    title = db.Column(db.String(500))
    browser = db.Column(db.String(50))
    visit_count = db.Column(db.Integer, default=1)
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class Geofence(db.Model):
    __tablename__ = 'geofences'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('geo_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    name = db.Column(db.String(200))
    latitude = db.Column(db.Float, nullable=False)
    longitude = db.Column(db.Float, nullable=False)
    radius = db.Column(db.Float, default=500)  # meters
    notify_on_entry = db.Column(db.Boolean, default=True)
    notify_on_exit = db.Column(db.Boolean, default=True)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class GeofenceEvent(db.Model):
    __tablename__ = 'geofence_events'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('gev_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    geofence_id = db.Column(db.String(32), db.ForeignKey('geofences.id'))
    event_type = db.Column(db.String(10))  # enter, exit
    latitude = db.Column(db.Float)
    longitude = db.Column(db.Float)
    timestamp = db.Column(db.BigInteger)
    received_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class RemoteCommand(db.Model):
    __tablename__ = 'remote_commands'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('cmd_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    parent_id = db.Column(db.String(32), db.ForeignKey('users.id'), nullable=False)
    command = db.Column(db.String(50), nullable=False)  # lock, unlock, wipe, alarm, screenshot, record_audio, block_apps
    params = db.Column(db.Text)  # JSON
    status = db.Column(db.String(20), default='pending')  # pending, delivered, completed, failed
    created_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))
    delivered_at = db.Column(db.BigInteger)
    completed_at = db.Column(db.BigInteger)
    result = db.Column(db.Text)

class AppRestriction(db.Model):
    __tablename__ = 'app_restrictions'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('res_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    package_name = db.Column(db.String(255))
    app_name = db.Column(db.String(255))
    is_blocked = db.Column(db.Boolean, default=False)
    max_minutes_per_day = db.Column(db.Integer, default=0)
    block_start_time = db.Column(db.String(5))  # HH:MM
    block_end_time = db.Column(db.String(5))
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))

class ScheduleRule(db.Model):
    __tablename__ = 'schedule_rules'
    
    id = db.Column(db.String(32), primary_key=True, default=lambda: generate_id('sch_'))
    device_id = db.Column(db.String(100), db.ForeignKey('devices.device_id'), nullable=False)
    name = db.Column(db.String(200))
    day_of_week = db.Column(db.Integer)  # 0=Mon, 6=Sun, -1=everyday
    start_time = db.Column(db.String(5), nullable=False)  # HH:MM
    end_time = db.Column(db.String(5), nullable=False)
    is_block_time = db.Column(db.Boolean, default=True)  # True=block, False=allow
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.BigInteger, default=lambda: int(datetime.now(timezone.utc).timestamp() * 1000))
