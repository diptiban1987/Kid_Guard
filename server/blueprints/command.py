"""Command blueprint — command status reporting from devices.

Security fix (V4 — command-status hijack): the device updating a command's
status must own the device the command is addressed to. Previously any
authenticated caller could update any command's result.
"""
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity

from ..extensions import db
from ..extensions import store_command_result
from ..models import RemoteCommand
from ..security import assert_command_ownership, audit_log

bp = Blueprint('command', __name__)


def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


@bp.route('/command/<command_id>/status', methods=['POST'])
@jwt_required()
def update_command_status(command_id):
    """V4: verify the command belongs to a device owned by the caller."""
    caller_id = get_jwt_identity()
    ok, _ = assert_command_ownership(command_id, caller_id)
    if not ok:
        return jsonify({'error': 'Access denied'}), 403

    data = request.get_json() or {}
    command = RemoteCommand.query.get(command_id)
    if not command:
        return jsonify({'error': 'Command not found'}), 404

    command.status = data.get('status', command.status)
    if command.status == 'completed':
        command.completed_at = _now_ms()
    if 'result' in data:
        command.result = data.get('result')
    db.session.commit()

    # Persist result to DB (and refresh the hot cache) so poll_command_result
    # works after a restart and across multiple workers.
    store_command_result(
        command_id,
        status=command.status,
        result_type=data.get('result_type', 'text'),
        data=data.get('result'),
        command=command.command,
        updated_at=_now_ms(),
    )
    return jsonify({'status': 'ok'})
