"""Files blueprint — media file serving.

The original endpoint already had an access check; preserved here with the
parent/child ownership logic intact. A parent can only fetch media for
devices belonging to their children; a child can only fetch their own.
"""
import os

from flask import Blueprint, jsonify, send_file, redirect
from flask_jwt_extended import jwt_required, get_jwt_identity

from ..extensions import db
from ..models import User, Device
from ..security import get_child_device_ids

bp = Blueprint('files', __name__)


@bp.route('/files/<media_id>')
@jwt_required()
def get_media(media_id):
    from ..models import MediaFile
    media = MediaFile.query.get(media_id)
    if not media:
        return jsonify({'error': 'Not found'}), 404

    user_id = get_jwt_identity()
    user = User.query.get(user_id)
    if not user:
        return jsonify({'error': 'Unauthorized'}), 401

    if user.role == 'parent':
        device_ids = get_child_device_ids(user_id)
        if media.device_id not in device_ids:
            return jsonify({'error': 'Access denied'}), 403
    elif user.role == 'child':
        device = Device.query.filter_by(user_id=user_id).first()
        if not device or media.device_id != device.device_id:
            return jsonify({'error': 'Access denied'}), 403
    elif user.role != 'admin':
        return jsonify({'error': 'Access denied'}), 403

    # Firebase-backed media: file_path holds a https download URL.
    if media.file_path and media.file_path.startswith('http'):
        return redirect(media.file_path)

    if not media.file_path or not os.path.exists(media.file_path):
        return jsonify({'error': 'File not found on disk'}), 404
    return send_file(media.file_path, mimetype=media.mime_type)

