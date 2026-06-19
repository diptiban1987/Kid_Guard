import json
import sqlite3
import hashlib
import os
from datetime import datetime
from functools import wraps

from flask import Flask, jsonify, request, render_template, redirect, session, url_for
from flask_cors import CORS

app = Flask(__name__)
app.secret_key = os.urandom(24)
CORS(app)

DB_PATH = os.path.join(os.path.dirname(__file__), 'tracking.db')
API_KEY = "parental-control-key-2024"

# --- Database Setup ---

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn

def init_db():
    conn = get_db()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS devices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            device_name TEXT,
            manufacturer TEXT,
            model TEXT,
            android_version TEXT,
            sdk_version INTEGER,
            first_seen INTEGER,
            last_seen INTEGER,
            UNIQUE(device_id)
        );

        CREATE TABLE IF NOT EXISTS locations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            accuracy REAL DEFAULT 0,
            provider TEXT,
            timestamp INTEGER,
            received_at INTEGER DEFAULT (strftime('%s','now') * 1000)
        );

        CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            battery_level INTEGER DEFAULT -1,
            battery_charging INTEGER DEFAULT 0,
            battery_temperature REAL DEFAULT -1,
            timestamp INTEGER,
            received_at INTEGER DEFAULT (strftime('%s','now') * 1000)
        );

        CREATE TABLE IF NOT EXISTS sms_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            sms_id INTEGER,
            address TEXT,
            body TEXT,
            date INTEGER,
            type INTEGER,
            received_at INTEGER DEFAULT (strftime('%s','now') * 1000)
        );

        CREATE TABLE IF NOT EXISTS call_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            call_id INTEGER,
            number TEXT,
            name TEXT,
            duration INTEGER,
            date INTEGER,
            type INTEGER,
            received_at INTEGER DEFAULT (strftime('%s','now') * 1000)
        );

        CREATE TABLE IF NOT EXISTS installed_apps (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            package_name TEXT,
            app_name TEXT,
            version_name TEXT,
            version_code INTEGER,
            first_install_time INTEGER,
            last_update_time INTEGER,
            received_at INTEGER DEFAULT (strftime('%s','now') * 1000)
        );

        CREATE INDEX IF NOT EXISTS idx_locations_device ON locations(device_id);
        CREATE INDEX IF NOT EXISTS idx_locations_timestamp ON locations(timestamp);
        CREATE INDEX IF NOT EXISTS idx_reports_device ON reports(device_id);
        CREATE INDEX IF NOT EXISTS idx_sms_device ON sms_messages(device_id);
        CREATE INDEX IF NOT EXISTS idx_calls_device ON call_logs(device_id);
        CREATE INDEX IF NOT EXISTS idx_apps_device ON installed_apps(device_id);
    """)
    conn.commit()
    conn.close()

def create_default_admin():
    conn = get_db()
    existing = conn.execute("SELECT id FROM users WHERE username = ?", ("admin",)).fetchone()
    if not existing:
        pw_hash = hashlib.sha256("admin123".encode()).hexdigest()
        conn.execute(
            "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
            ("admin", pw_hash, int(datetime.now().timestamp() * 1000))
        )
        conn.commit()
    conn.close()

# --- Auth Helpers ---

def login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if 'user_id' not in session:
            if request.path.startswith('/api/'):
                return jsonify({"error": "Unauthorized"}), 401
            return redirect(url_for('login'))
        return f(*args, **kwargs)
    return decorated

def require_api_key(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        api_key = request.headers.get('X-API-Key') or request.json.get('apiKey')
        if api_key != API_KEY:
            return jsonify({"error": "Invalid API key"}), 403
        return f(*args, **kwargs)
    return decorated

# --- Web Routes ---

@app.route('/')
def index():
    if 'user_id' in session:
        return redirect(url_for('dashboard'))
    return redirect(url_for('login'))

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        username = request.form.get('username')
        password = request.form.get('password')
        pw_hash = hashlib.sha256(password.encode()).hexdigest()

        conn = get_db()
        user = conn.execute(
            "SELECT id, username FROM users WHERE username = ? AND password_hash = ?",
            (username, pw_hash)
        ).fetchone()
        conn.close()

        if user:
            session['user_id'] = user['id']
            session['username'] = user['username']
            return redirect(url_for('dashboard'))
        return render_template('login.html', error="Invalid credentials")

    return render_template('login.html')

@app.route('/logout')
def logout():
    session.clear()
    return redirect(url_for('login'))

@app.route('/dashboard')
@login_required
def dashboard():
    return render_template('dashboard.html')

@app.route('/device/<device_id>')
@login_required
def device_detail(device_id):
    return render_template('device.html', device_id=device_id)

# --- API Routes (Data Ingest) ---

@app.route('/api/report', methods=['POST'])
@require_api_key
def receive_report():
    data = request.get_json()
    if not data:
        return jsonify({"error": "No data provided"}), 400

    device_info = data.get('device', {})
    device_id = device_info.get('deviceId', 'unknown')

    conn = get_db()
    now = int(datetime.now().timestamp() * 1000)

    # Upsert device
    conn.execute("""
        INSERT INTO devices (device_id, device_name, manufacturer, model, android_version, sdk_version, first_seen, last_seen)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(device_id) DO UPDATE SET
            device_name=excluded.device_name,
            manufacturer=excluded.manufacturer,
            model=excluded.model,
            android_version=excluded.android_version,
            sdk_version=excluded.sdk_version,
            last_seen=excluded.last_seen
    """, (
        device_id, device_info.get('deviceName', ''),
        device_info.get('manufacturer', ''), device_info.get('model', ''),
        device_info.get('androidVersion', ''), device_info.get('sdkVersion', 0),
        now, now
    ))

    # Insert location
    location = data.get('location')
    if location:
        conn.execute("""
            INSERT INTO locations (device_id, latitude, longitude, accuracy, provider, timestamp, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (
            device_id, location['latitude'], location['longitude'],
            location.get('accuracy', 0), location.get('provider', 'unknown'),
            location.get('timestamp', now), now
        ))

    # Insert report (battery data)
    battery = data.get('battery', {})
    conn.execute("""
        INSERT INTO reports (device_id, battery_level, battery_charging, battery_temperature, timestamp, received_at)
        VALUES (?, ?, ?, ?, ?, ?)
    """, (
        device_id, battery.get('level', -1),
        1 if battery.get('isCharging', False) else 0,
        battery.get('temperature', -1), now, now
    ))

    # Insert SMS messages
    for sms in data.get('smsMessages', []):
        conn.execute("""
            INSERT OR IGNORE INTO sms_messages (device_id, sms_id, address, body, date, type, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (
            device_id, sms.get('id'), sms.get('address', ''),
            sms.get('body', ''), sms.get('date', 0), sms.get('type', 0), now
        ))

    # Insert call logs
    for call in data.get('callLogs', []):
        conn.execute("""
            INSERT OR IGNORE INTO call_logs (device_id, call_id, number, name, duration, date, type, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            device_id, call.get('id'), call.get('number', ''),
            call.get('name', ''), call.get('duration', 0),
            call.get('date', 0), call.get('type', 0), now
        ))

    # Insert installed apps
    for app_data in data.get('installedApps', []):
        conn.execute("""
            INSERT OR IGNORE INTO installed_apps (device_id, package_name, app_name, version_name, version_code, first_install_time, last_update_time, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            device_id, app_data.get('packageName', ''),
            app_data.get('appName', ''), app_data.get('versionName', ''),
            app_data.get('versionCode', 0), app_data.get('firstInstallTime', 0),
            app_data.get('lastUpdateTime', 0), now
        ))

    conn.commit()
    conn.close()

    return jsonify({"status": "ok", "device_id": device_id}), 200

# --- API Routes (Data Retrieval) ---

@app.route('/api/devices')
@login_required
def get_devices():
    conn = get_db()
    devices = conn.execute("""
        SELECT d.*,
            (SELECT COUNT(*) FROM locations WHERE device_id = d.device_id) as location_count,
            (SELECT COUNT(*) FROM sms_messages WHERE device_id = d.device_id) as sms_count,
            (SELECT COUNT(*) FROM call_logs WHERE device_id = d.device_id) as call_count,
            (SELECT latitude || ',' || longitude FROM locations WHERE device_id = d.device_id ORDER BY timestamp DESC LIMIT 1) as last_location,
            (SELECT battery_level FROM reports WHERE device_id = d.device_id ORDER BY timestamp DESC LIMIT 1) as last_battery
        FROM devices d
        ORDER BY d.last_seen DESC
    """).fetchall()
    conn.close()

    return jsonify([dict(d) for d in devices])

@app.route('/api/devices/<device_id>/locations')
@login_required
def get_device_locations(device_id):
    limit = request.args.get('limit', 100, type=int)
    conn = get_db()
    locations = conn.execute("""
        SELECT * FROM locations
        WHERE device_id = ?
        ORDER BY timestamp DESC
        LIMIT ?
    """, (device_id, limit)).fetchall()
    conn.close()
    return jsonify([dict(l) for l in locations])

@app.route('/api/devices/<device_id>/sms')
@login_required
def get_device_sms(device_id):
    limit = request.args.get('limit', 50, type=int)
    conn = get_db()
    messages = conn.execute("""
        SELECT * FROM sms_messages
        WHERE device_id = ?
        ORDER BY date DESC
        LIMIT ?
    """, (device_id, limit)).fetchall()
    conn.close()
    return jsonify([dict(m) for m in messages])

@app.route('/api/devices/<device_id>/calls')
@login_required
def get_device_calls(device_id):
    limit = request.args.get('limit', 50, type=int)
    conn = get_db()
    calls = conn.execute("""
        SELECT * FROM call_logs
        WHERE device_id = ?
        ORDER BY date DESC
        LIMIT ?
    """, (device_id, limit)).fetchall()
    conn.close()
    return jsonify([dict(c) for c in calls])

@app.route('/api/devices/<device_id>/apps')
@login_required
def get_device_apps(device_id):
    conn = get_db()
    apps = conn.execute("""
        SELECT DISTINCT * FROM installed_apps
        WHERE device_id = ?
        ORDER BY app_name
    """, (device_id,)).fetchall()
    conn.close()
    return jsonify([dict(a) for a in apps])

@app.route('/api/devices/<device_id>/stats')
@login_required
def get_device_stats(device_id):
    conn = get_db()
    stats = conn.execute("""
        SELECT
            (SELECT COUNT(*) FROM locations WHERE device_id = ?) as location_count,
            (SELECT COUNT(*) FROM sms_messages WHERE device_id = ?) as sms_count,
            (SELECT COUNT(*) FROM call_logs WHERE device_id = ?) as call_count,
            (SELECT COUNT(*) FROM installed_apps WHERE device_id = ?) as app_count,
            (SELECT battery_level FROM reports WHERE device_id = ? ORDER BY timestamp DESC LIMIT 1) as battery_level,
            (SELECT battery_charging FROM reports WHERE device_id = ? ORDER BY timestamp DESC LIMIT 1) as battery_charging,
            (SELECT timestamp FROM reports WHERE device_id = ? ORDER BY timestamp DESC LIMIT 1) as last_report
    """, (device_id, device_id, device_id, device_id, device_id, device_id, device_id)).fetchone()
    conn.close()
    return jsonify(dict(stats) if stats else {})

# --- Initialization ---

if __name__ == '__main__':
    init_db()
    create_default_admin()
    print("=" * 50)
    print("Parental Control Monitoring Server")
    print("=" * 50)
    print(f"Database: {DB_PATH}")
    print("Default login: admin / admin123")
    print(f"API Key: {API_KEY}")
    print("=" * 50)
    app.run(host='0.0.0.0', port=5000, debug=True)
