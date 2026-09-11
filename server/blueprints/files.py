"""Files blueprint — media file serving.

The original endpoint already had an access check; preserved here with the
parent/child ownership logic intact. A parent can only fetch media for
devices belonging to their children; a child can only fetch their own.
"""
import io
import os

from flask import Blueprint, current_app, jsonify, send_file, redirect
from flask_jwt_extended import jwt_required, get_jwt_identity

from ..extensions import db
from ..models import User, Device
from ..security import get_child_device_ids

bp = Blueprint('files', __name__)


# Render free tier has an EPHEMERAL disk: media uploaded before the
# Firebase-first change (and anything else stored under /app/uploads) is
# wiped on every redeploy, while the media_files DB rows survive on
# Postgres. The dashboard references some of those dead rows directly from
# activity/social payloads (chat_screenshot thumbs, lightbox), so instead of
# a 404 + broken-image icon in the console we serve a small placeholder tile.
_UNAVAILABLE_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="320" '
    'viewBox="0 0 320 320">'
    '<rect width="320" height="320" rx="16" fill="#1c2030"/>'
    '<text x="160" y="150" font-size="64" text-anchor="middle">\U0001F5BC\uFE0F</text>'
    '<text x="160" y="210" font-size="20" font-family="sans-serif" '
    'fill="rgba(255,255,255,0.55)" text-anchor="middle">Media no longer available</text>'
    '<text x="160" y="238" font-size="13" font-family="sans-serif" '
    'fill="rgba(255,255,255,0.35)" text-anchor="middle">'
    'This file was lost in a server storage reset</text>'
    '</svg>'
)


def _media_unavailable():
    """Placeholder tile (HTTP 200) for media rows whose bytes are gone."""
    resp = send_file(
        io.BytesIO(_UNAVAILABLE_SVG.encode('utf-8')),
        mimetype='image/svg+xml',
        download_name='unavailable.svg',
    )
    resp.headers['Cache-Control'] = 'no-store'
    return resp


@bp.route('/files/<media_id>')
@jwt_required()
def get_media(media_id):
    from ..models import MediaFile
    media = MediaFile.query.get(media_id)
    if not media:
        # Row purged (storage delete) — the dashboard still references it
        # from activity payloads; placeholder instead of 404 noise.
        current_app.logger.info('media row missing id=%s', media_id)
        return _media_unavailable()

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
        # Bytes wiped by a redeploy (ephemeral disk) — placeholder tile.
        current_app.logger.info('media bytes missing id=%s path=%s', media_id, media.file_path)
        return _media_unavailable()
    return send_file(media.file_path, mimetype=media.mime_type)

