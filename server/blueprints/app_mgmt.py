"""App management blueprint — APK update check, download, upload.

The upload route requires admin (preserved from original). The check-update
and download routes require any authenticated user. Behaviour is 1:1 with the
original monolith.
"""
import os
import json

from flask import Blueprint, request, jsonify, send_file, current_app
from flask_jwt_extended import jwt_required, get_jwt_identity

from ..security import admin_required

bp = Blueprint('app_mgmt', __name__)


def _apk_dir():
    return os.path.join(current_app.config['UPLOAD_FOLDER'], 'apk')


def _apk_metadata_file():
    return os.path.join(_apk_dir(), 'version.json')


def load_apk_metadata():
    if os.path.exists(_apk_metadata_file()):
        with open(_apk_metadata_file()) as f:
            return json.load(f)
    return {'latest_version': 0, 'changelog': '', 'apk_filename': ''}


def save_apk_metadata(meta):
    os.makedirs(_apk_dir(), exist_ok=True)
    with open(_apk_metadata_file(), 'w') as f:
        json.dump(meta, f, indent=2)


@bp.route('/app/check-update', methods=['POST'])
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
            'download_url': f'/api/v1/app/download/{latest_version}',
            'changelog': meta.get('changelog', ''),
        })
    return jsonify({'has_update': False})


@bp.route('/app/download/<int:version_code>')
@jwt_required()
def download_app_update(version_code):
    meta = load_apk_metadata()
    if version_code != meta.get('latest_version', 0):
        return jsonify({'error': 'Version not found'}), 404
    apk_path = os.path.join(_apk_dir(), meta.get('apk_filename', ''))
    if not os.path.exists(apk_path):
        return jsonify({'error': 'APK file not found'}), 404
    return send_file(apk_path, mimetype='application/vnd.android.package-archive',
                     as_attachment=True, download_name=meta['apk_filename'])


@bp.route('/app/upload', methods=['POST'])
@admin_required
def upload_app_update():
    if 'apk' not in request.files:
        return jsonify({'error': 'No APK file provided'}), 400
    file = request.files['apk']
    version = request.form.get('version_code', '1')
    changelog = request.form.get('changelog', '')
    filename = f'kidguard_v{version}.apk'
    os.makedirs(_apk_dir(), exist_ok=True)
    file.save(os.path.join(_apk_dir(), filename))
    save_apk_metadata({
        'latest_version': int(version),
        'changelog': changelog,
        'apk_filename': filename,
    })
    return jsonify({'success': True, 'version_code': int(version)})
