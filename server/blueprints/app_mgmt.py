"""App management blueprint — APK update check, download, upload.

Per-flavor OTA: the repo builds two disguises with different applicationIds
(com.anonchat.app = Calculator, com.anonchat.app.gpt = ChatGPT). A single APK
cannot update both, so version.json carries a per-package "flavors" block and
check-update/download resolve the right APK by the device package name (sent
by v4+ clients; v3 clients are resolved via their installed-apps report).
Upload requires admin. download_url is ABSOLUTE (UpdateManager feeds it
straight into java.net.URL, which rejects relative paths).
"""
import os
import json

from flask import Blueprint, request, jsonify, send_file, current_app
from flask_jwt_extended import jwt_required

from ..security import admin_required

bp = Blueprint('app_mgmt', __name__)

# Repo-bundled fallback so APKs committed to git keep serving OTA on
# ephemeral deploys (Render free tier has no persistent disk).
_REPO_APK_DIR = os.path.abspath(
    os.path.join(os.path.dirname(__file__), '..', '..', 'cloud-server', 'apk'))

CALC_PKG = 'com.anonchat.app'
GPT_PKG = 'com.anonchat.app.gpt'


def _apk_dir():
    return os.path.join(current_app.config['UPLOAD_FOLDER'], 'apk')


def _apk_metadata_file():
    return os.path.join(_apk_dir(), 'version.json')


def load_apk_metadata():
    for meta_path in [_apk_metadata_file(), os.path.join(_REPO_APK_DIR, 'version.json')]:
        if os.path.exists(meta_path):
            try:
                # utf-8-sig: PowerShell writes BOMs, and a BOM'd version.json
                # made json.load raise on EVERY check-update call (500s) once
                # the repo fallback path existed in the container.
                with open(meta_path, encoding='utf-8-sig') as f:
                    return json.load(f)
            except (json.JSONDecodeError, OSError, UnicodeDecodeError):
                current_app.logger.warning(
                    'unreadable APK metadata: %s — trying next source', meta_path,
                    exc_info=True)
                return json.load(f)
    return {'latest_version': 0, 'changelog': '', 'apk_filename': '', 'flavors': {}}


def save_apk_metadata(meta):
    os.makedirs(_apk_dir(), exist_ok=True)
    with open(_apk_metadata_file(), 'w') as f:
        json.dump(meta, f, indent=2)


def _flavor_entry(pkg):
    return (load_apk_metadata().get('flavors') or {}).get(pkg)


def _resolve_flavor_package(data):
    """package_name from the request wins; older clients (no package_name)
    are resolved by detecting the disguised app in their installed-apps list."""
    pkg = (data.get('package_name') or '').strip()
    if pkg:
        return pkg
    device_id = (data.get('device_id') or '').strip()
    if not device_id:
        return None
    try:
        from ..models import InstalledApp
        pkgs = {r.package_name for r in
                InstalledApp.query.filter_by(device_id=device_id).all()}
        if GPT_PKG in pkgs:
            return GPT_PKG
        if CALC_PKG in pkgs:
            return CALC_PKG
    except Exception:
        current_app.logger.warning('flavor resolve failed', exc_info=True)
    return None


@bp.route('/app/check-update', methods=['POST'])
@jwt_required()
def check_app_update():
    data = request.get_json() or {}
    current_version = data.get('version_code', 0)
    meta = load_apk_metadata()

    pkg = _resolve_flavor_package(data)
    entry = _flavor_entry(pkg) if pkg else None
    if entry:
        v = entry.get('version_code', 0)
        if v > current_version:
            root = request.url_root.rstrip('/')
            return jsonify({
                'has_update': True,
                'version_code': v,
                'download_url': f'{root}/api/v1/app/download/{v}?pkg={pkg}',
                'changelog': entry.get('changelog', '') or meta.get('changelog', ''),
            })
        return jsonify({'has_update': False})

    # Legacy single-APK path (version.json without a flavors block)
    latest_version = meta.get('latest_version', 0)
    if latest_version > current_version:
        root = request.url_root.rstrip('/')
        return jsonify({
            'has_update': True,
            'version_code': latest_version,
            'download_url': f'{root}/api/v1/app/download/{latest_version}',
            'changelog': meta.get('changelog', ''),
        })
    return jsonify({'has_update': False})


@bp.route('/app/download/<int:version_code>')
@jwt_required()
def download_app_update(version_code):
    meta = load_apk_metadata()
    pkg = (request.args.get('pkg') or '').strip()
    entry = _flavor_entry(pkg) if pkg else None
    if entry and entry.get('version_code', 0) != version_code:
        return jsonify({'error': 'Version not found for this flavor'}), 404
    filename = (entry.get('apk_filename') if entry else None) or meta.get('apk_filename', '')
    if not filename:
        return jsonify({'error': 'APK file not found'}), 404
    for d in [_apk_dir(), _REPO_APK_DIR]:
        apk_path = os.path.join(d, filename)
        if os.path.exists(apk_path):
            return send_file(apk_path, mimetype='application/vnd.android.package-archive',
                             as_attachment=True, download_name=filename)
    return jsonify({'error': 'APK file not found'}), 404


@bp.route('/app/upload', methods=['POST'])
@admin_required
def upload_app_update():
    if 'apk' not in request.files:
        return jsonify({'error': 'No APK file provided'}), 400
    file = request.files['apk']
    version = request.form.get('version_code', '1')
    changelog = request.form.get('changelog', '')
    pkg = (request.form.get('package_name') or '').strip()
    meta = load_apk_metadata()
    meta.setdefault('flavors', {})
    os.makedirs(_apk_dir(), exist_ok=True)
    if pkg:
        filename = f'kidguard_v{version}_{pkg}.apk'
        file.save(os.path.join(_apk_dir(), filename))
        meta['flavors'][pkg] = {
            'version_code': int(version),
            'apk_filename': filename,
            'changelog': changelog,
        }
        meta['latest_version'] = max(meta.get('latest_version', 0), int(version))
    else:
        filename = f'kidguard_v{version}.apk'
        file.save(os.path.join(_apk_dir(), filename))
        meta.update({
            'latest_version': int(version),
            'changelog': changelog,
            'apk_filename': filename,
        })
    save_apk_metadata(meta)
    return jsonify({'success': True, 'version_code': int(version),
                    'package_name': pkg or 'legacy'})
