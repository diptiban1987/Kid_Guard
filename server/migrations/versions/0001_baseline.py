"""baseline: initial schema

Revision ID: 0001_baseline
Revises:
Create Date: 2026-07-22 00:00:00

The baseline migration reproduces the full schema as it existed in the
monolithic ``app.py`` before the multi-user conversion. New deployments stamp
this as head before applying ``0002_multiuser``; existing SQLite deployments
that already have tables should ``alembic stamp 0001_baseline`` (without
running it) then ``alembic upgrade head``.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = '0001_baseline'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # ── users ──────────────────────────────────────────────────────────
    op.create_table(
        'users',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('email', sa.String(255), nullable=False, unique=True),
        sa.Column('password_hash', sa.String(255), nullable=False),
        sa.Column('display_name', sa.String(100), nullable=False),
        sa.Column('role', sa.String(20), server_default='parent'),
        sa.Column('is_active', sa.Boolean, server_default=sa.text('1')),
        sa.Column('created_at', sa.BigInteger),
        sa.Column('last_login', sa.BigInteger),
    )

    # ── child_relations ────────────────────────────────────────────────
    op.create_table(
        'child_relations',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('parent_id', sa.String(32), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('child_id', sa.String(32), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('pairing_code', sa.String(20), unique=True),
        sa.Column('paired_at', sa.BigInteger),
        sa.Column('is_active', sa.Boolean, server_default=sa.text('1')),
    )

    # ── devices ────────────────────────────────────────────────────────
    op.create_table(
        'devices',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), nullable=False, unique=True),
        sa.Column('user_id', sa.String(32), sa.ForeignKey('users.id')),
        sa.Column('device_name', sa.String(200)),
        sa.Column('manufacturer', sa.String(100)),
        sa.Column('model', sa.String(100)),
        sa.Column('android_version', sa.String(20)),
        sa.Column('sdk_version', sa.Integer),
        sa.Column('is_active', sa.Boolean, server_default=sa.text('1')),
        sa.Column('stealth_mode', sa.Boolean, server_default=sa.text('0')),
        sa.Column('reporting_interval', sa.Integer, server_default='300'),
        sa.Column('first_seen', sa.BigInteger),
        sa.Column('last_seen', sa.BigInteger),
    )

    # ── report tables (device-scoped) ─────────────────────────────────
    op.create_table(
        'location_reports',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('latitude', sa.Float, nullable=False),
        sa.Column('longitude', sa.Float, nullable=False),
        sa.Column('accuracy', sa.Float, server_default='0'),
        sa.Column('altitude', sa.Float),
        sa.Column('speed', sa.Float),
        sa.Column('bearing', sa.Float),
        sa.Column('provider', sa.String(20)),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'activity_reports',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('activity_type', sa.String(50), nullable=False),
        sa.Column('package_name', sa.String(255)),
        sa.Column('app_name', sa.String(255)),
        sa.Column('data', sa.Text),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )
    op.create_index('idx_activity_device_ts', 'activity_reports', ['device_id', 'timestamp'])

    op.create_table(
        'battery_reports',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('level', sa.Integer, server_default='-1'),
        sa.Column('is_charging', sa.Boolean, server_default=sa.text('0')),
        sa.Column('temperature', sa.Float, server_default='-1'),
        sa.Column('voltage', sa.Float),
        sa.Column('plugged', sa.String(20)),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'screen_time_reports',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('date', sa.String(10), nullable=False),
        sa.Column('total_minutes', sa.Integer, server_default='0'),
        sa.Column('unlocks', sa.Integer, server_default='0'),
        sa.Column('app_usage_json', sa.Text),
        sa.Column('updated_at', sa.BigInteger),
    )

    op.create_table(
        'sms_messages',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('sms_id', sa.Integer),
        sa.Column('address', sa.String(100)),
        sa.Column('body', sa.Text),
        sa.Column('date', sa.BigInteger),
        sa.Column('type', sa.Integer),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'call_logs',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('call_id', sa.Integer),
        sa.Column('number', sa.String(50)),
        sa.Column('name', sa.String(200)),
        sa.Column('duration', sa.Integer),
        sa.Column('date', sa.BigInteger),
        sa.Column('type', sa.Integer),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'call_state_events',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('state', sa.Integer),
        sa.Column('phone_number', sa.String(50)),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'installed_apps',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('package_name', sa.String(255)),
        sa.Column('app_name', sa.String(255)),
        sa.Column('version_name', sa.String(50)),
        sa.Column('version_code', sa.BigInteger),
        sa.Column('first_install_time', sa.BigInteger),
        sa.Column('last_update_time', sa.BigInteger),
        sa.Column('is_system_app', sa.Boolean, server_default=sa.text('0')),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'media_files',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('media_type', sa.String(20)),
        sa.Column('file_path', sa.String(500)),
        sa.Column('file_size', sa.BigInteger),
        sa.Column('mime_type', sa.String(100)),
        sa.Column('thumbnail_path', sa.String(500)),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'web_history',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('url', sa.String(2000)),
        sa.Column('title', sa.String(500)),
        sa.Column('browser', sa.String(50)),
        sa.Column('visit_count', sa.Integer, server_default='1'),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'geofences',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('name', sa.String(200)),
        sa.Column('latitude', sa.Float, nullable=False),
        sa.Column('longitude', sa.Float, nullable=False),
        sa.Column('radius', sa.Float, server_default='500'),
        sa.Column('notify_on_entry', sa.Boolean, server_default=sa.text('1')),
        sa.Column('notify_on_exit', sa.Boolean, server_default=sa.text('1')),
        sa.Column('is_active', sa.Boolean, server_default=sa.text('1')),
        sa.Column('created_at', sa.BigInteger),
    )

    op.create_table(
        'geofence_events',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('geofence_id', sa.String(32), sa.ForeignKey('geofences.id')),
        sa.Column('event_type', sa.String(10)),
        sa.Column('latitude', sa.Float),
        sa.Column('longitude', sa.Float),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )

    op.create_table(
        'remote_commands',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('parent_id', sa.String(32), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('command', sa.String(50), nullable=False),
        sa.Column('params', sa.Text),
        sa.Column('status', sa.String(20), server_default='pending'),
        sa.Column('created_at', sa.BigInteger),
        sa.Column('delivered_at', sa.BigInteger),
        sa.Column('completed_at', sa.BigInteger),
        sa.Column('result', sa.Text),
    )

    op.create_table(
        'app_restrictions',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('package_name', sa.String(255)),
        sa.Column('app_name', sa.String(255)),
        sa.Column('is_blocked', sa.Boolean, server_default=sa.text('0')),
        sa.Column('max_minutes_per_day', sa.Integer, server_default='0'),
        sa.Column('block_start_time', sa.String(5)),
        sa.Column('block_end_time', sa.String(5)),
        sa.Column('is_active', sa.Boolean, server_default=sa.text('1')),
        sa.Column('created_at', sa.BigInteger),
    )

    op.create_table(
        'schedule_rules',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('name', sa.String(200)),
        sa.Column('day_of_week', sa.Integer),
        sa.Column('start_time', sa.String(5), nullable=False),
        sa.Column('end_time', sa.String(5), nullable=False),
        sa.Column('is_block_time', sa.Boolean, server_default=sa.text('1')),
        sa.Column('is_active', sa.Boolean, server_default=sa.text('1')),
        sa.Column('created_at', sa.BigInteger),
    )

    op.create_table(
        'password_reset_tokens',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('user_id', sa.String(32), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('token', sa.String(64), nullable=False, unique=True),
        sa.Column('used', sa.Boolean, server_default=sa.text('0')),
        sa.Column('expires_at', sa.BigInteger, nullable=False),
        sa.Column('created_at', sa.BigInteger),
    )

    op.create_table(
        'social_notifications',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('device_id', sa.String(100), sa.ForeignKey('devices.device_id'), nullable=False),
        sa.Column('package_name', sa.String(200)),
        sa.Column('app_name', sa.String(100)),
        sa.Column('sender', sa.String(200)),
        sa.Column('content', sa.Text),
        sa.Column('message_type', sa.String(50), server_default='notification'),
        sa.Column('timestamp', sa.BigInteger),
        sa.Column('received_at', sa.BigInteger),
    )


def downgrade() -> None:
    for table in [
        'social_notifications', 'password_reset_tokens', 'schedule_rules',
        'app_restrictions', 'remote_commands', 'geofence_events', 'geofences',
        'web_history', 'media_files', 'installed_apps', 'call_state_events',
        'call_logs', 'sms_messages', 'screen_time_reports', 'battery_reports',
        'activity_reports', 'location_reports', 'devices', 'child_relations',
        'users',
    ]:
        op.drop_table(table)
    op.drop_index('idx_activity_device_ts', table_name='activity_reports')
