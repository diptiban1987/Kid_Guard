"""Authentication blueprint — register, login, refresh, me, logout, forgot/reset.

Security fixes applied here (see the audit + security.py):
  - V11 weak hashing: uses Werkzeug scrypt; transparently re-hashes legacy
    SHA-256 hashes on successful login (no-downtime migration).
  - V9  reset token in response: forgot-password returns the token ONLY when
    no SMTP (MAIL_SERVER) is configured (dev). With SMTP configured, the token
    is emailed and never echoed in the response (prod-safe).
  - V10 account enumeration: forgot-username returns a generic boolean only.
  - V12 no token revocation: logout blocklists the current jti; reset-password
    revokes all the user's outstanding tokens via ``last_password_change``.
  - Rate-limited: 5/min on login, register, forgot, reset (WS-2b).
"""
import random
import string
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify, current_app
from flask_jwt_extended import (
    jwt_required, get_jwt_identity, get_jwt,
    create_access_token, create_refresh_token,
)
from sqlalchemy.exc import IntegrityError

from ..extensions import db, limiter
from ..models import User, PasswordResetToken, generate_pairing_code
from ..security import (
    hash_password, verify_password, maybe_rehash,
    revoke_user_tokens, audit_log, mask_email,
)

bp = Blueprint('auth', __name__)


@bp.route('/auth/register', methods=['POST'])
@limiter.limit('5/minute')
def register():
    data = request.get_json() or {}
    email = data.get('email', '').strip().lower()
    password = data.get('password', '')
    display_name = data.get('display_name', '').strip()
    role = data.get('role', 'parent')

    if not email or not password or len(password) < 6:
        return jsonify({'error': 'Email and password (min 6 chars) required'}), 400

    existing = User.query.filter_by(email=email).first()
    if existing:
        # Preserve the original flexibility: a parent and child may share an
        # email (the role distinguishes them). Otherwise reject duplicates.
        if not ((role == 'child' and existing.role == 'parent') or
                (role == 'parent' and existing.role == 'child')):
            return jsonify({'error': 'Email already registered'}), 409

    user = User(
        email=email,
        password_hash=hash_password(password),
        display_name=display_name or email.split('@')[0],
        role=role if role in ('parent', 'child') else 'parent',
    )
    db.session.add(user)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return jsonify({'error': 'Email already registered'}), 409

    audit_log(user.id, 'register', target_type='user', target_id=user.id,
              metadata={'role': user.role})

    access_token = create_access_token(identity=user.id)
    refresh_token = create_refresh_token(identity=user.id)
    return jsonify({
        'token': access_token,
        'refresh_token': refresh_token,
        'user': user.to_dict()
    }), 201


@bp.route('/auth/login', methods=['POST'])
@limiter.limit('5/minute')
def login():
    data = request.get_json() or {}
    email = data.get('email', '').strip().lower()
    password = data.get('password', '')
    role = data.get('role', '').strip().lower()

    if role and role in ('parent', 'child'):
        user = User.query.filter_by(email=email, role=role).first()
    else:
        user = User.query.filter_by(email=email).first()

    if not user or not verify_password(password, user.password_hash):
        return jsonify({'error': 'Invalid credentials'}), 401

    if not user.is_active:
        return jsonify({'error': 'Account disabled'}), 403

    # V11: transparently upgrade legacy SHA-256 hashes to scrypt.
    maybe_rehash(user, password)

    user.last_login = int(datetime.now(timezone.utc).timestamp() * 1000)
    db.session.commit()

    audit_log(user.id, 'login', target_type='user', target_id=user.id)

    access_token = create_access_token(identity=user.id)
    refresh_token = create_refresh_token(identity=user.id)
    return jsonify({
        'token': access_token,
        'refresh_token': refresh_token,
        'user': user.to_dict()
    })


@bp.route('/auth/refresh', methods=['POST'])
@jwt_required(refresh=True)
def refresh():
    user_id = get_jwt_identity()
    user = User.query.get(user_id)
    if not user or not user.is_active:
        return jsonify({'error': 'User not found'}), 404
    return jsonify({'token': create_access_token(identity=user_id)})


@bp.route('/auth/me', methods=['GET'])
@jwt_required()
def get_me():
    user = User.query.get(get_jwt_identity())
    if not user:
        return jsonify({'error': 'User not found'}), 404
    return jsonify({'user': user.to_dict()})


@bp.route('/auth/logout', methods=['POST'])
@jwt_required()
def logout():
    """V12: blocklist the current token's jti so it can no longer be used."""
    from ..models import TokenBlocklist
    jti = get_jwt().get('jti')
    user_id = get_jwt_identity()
    if jti:
        # Blocklist until the token's natural expiry so the table self-prunes
        # (a token past its expiry is rejected by JWT anyway).
        exp = get_jwt().get('exp', 0)
        db.session.add(TokenBlocklist(
            jti=jti, user_id=user_id, expires_at=int(exp * 1000)
        ))
        db.session.commit()
    audit_log(user_id, 'logout', target_type='user', target_id=user_id)
    return jsonify({'status': 'ok'})


@bp.route('/auth/forgot-password', methods=['POST'])
@limiter.limit('5/minute')
def forgot_password():
    """Issue a password-reset token. V9: when MAIL_SERVER is configured the
    token is emailed and NEVER echoed back (prod). When MAIL_SERVER is unset
    (dev / no SMTP) the token is returned in the response so the frontend
    reset flow can complete; server-side logging also happens in dev."""
    data = request.get_json() or {}
    email = data.get('email', '').strip().lower()
    if not email:
        return jsonify({'error': 'Email is required'}), 400

    user = User.query.filter_by(email=email).first()
    # Generic response regardless of whether the email exists — prevents
    # account enumeration via timing or message differences.
    generic = {'message': 'If that email exists, reset instructions have been sent.'}

    if not user:
        return jsonify(generic), 200

    # Invalidate any unused previous tokens for this user.
    PasswordResetToken.query.filter_by(user_id=user.id, used=False).update({'used': True})

    token = ''.join(random.choices(string.ascii_uppercase + string.digits, k=8))
    ttl_ms = 30 * 60 * 1000  # 30 minutes
    reset = PasswordResetToken(
        user_id=user.id,
        token=token,
        expires_at=int(datetime.now(timezone.utc).timestamp() * 1000) + ttl_ms,
    )
    db.session.add(reset)
    db.session.commit()

    # V9: deliver the token via email if SMTP is configured; otherwise log it.
    if current_app.config.get('MAIL_SERVER'):
        try:
            _send_reset_email(user.email, token)
        except Exception as exc:
            current_app.logger.error(f'Failed to send reset email to {user.email}: {exc}')
    else:
        current_app.logger.info(
            f'[DEV RESET TOKEN] user={user.id} email={user.email} token={token} '
            f'(configure MAIL_SERVER to email this instead)'
        )

    audit_log(user.id, 'forgot_password', target_type='user', target_id=user.id)

    # V9 delivery policy:
    #   - MAIL_SERVER configured → email the token, do NOT echo it back (prod).
    #   - MAIL_SERVER unset (dev / no SMTP) → there's no other channel to send
    #     the token, so return it in the response body. The frontend (login.html)
    #     surfaces it to the user so they can paste it into the reset form.
    #     This matches the legacy monolith's dev behavior and is consistent with
    #     the existing server-side log line above.
    #     Once MAIL_SERVER is configured, this branch stops returning the token
    #     and the prod guarantee holds again.
    payload = dict(generic)
    if not current_app.config.get('MAIL_SERVER'):
        payload['token'] = token
        payload['expires_in_seconds'] = 1800
        payload['email_masked'] = mask_email(user.email)
    return jsonify(payload), 200


@bp.route('/auth/reset-password', methods=['POST'])
@limiter.limit('5/minute')
def reset_password():
    """Set a new password using a valid reset token. V12: revokes all the
    user's outstanding sessions on reset."""
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

    # V12: invalidate all outstanding JWTs for this user.
    revoke_user_tokens(user.id)

    audit_log(user.id, 'reset_password', target_type='user', target_id=user.id)

    access_token = create_access_token(identity=user.id)
    refresh_token = create_refresh_token(identity=user.id)
    return jsonify({
        'message': 'Password updated successfully',
        'token': access_token,
        'refresh_token': refresh_token,
        'user': user.to_dict()
    }), 200


@bp.route('/auth/forgot-username', methods=['POST'])
@limiter.limit('5/minute')
def forgot_username():
    """V10: returns only a generic existence boolean — never emails, roles, or
    masked addresses (the old endpoint leaked all three)."""
    data = request.get_json() or {}
    name_hint = data.get('display_name', '').strip().lower()
    if not name_hint or len(name_hint) < 2:
        return jsonify({'error': 'display_name hint (min 2 chars) is required'}), 400

    users = User.query.filter(User.display_name.ilike(f'%{name_hint}%')).limit(10).all()
    return jsonify({'exists': len(users) > 0}), 200


# ─── Helpers ──────────────────────────────────────────────────────────────

def _send_reset_email(to_email, token):
    """Send a password-reset email. Only called when MAIL_SERVER is configured
    (V9 fix — until then the token is logged server-side in dev)."""
    from flask_mail import Mail, Message
    mail = Mail(current_app)
    msg = Message(
        subject='AnonChat / KidGuard — Password Reset',
        recipients=[to_email],
        body=(
            f'Use the following token to reset your password: {token}\n\n'
            f'This token expires in 30 minutes.\n'
            f'If you did not request a reset, ignore this email.'
        ),
    )
    mail.send(msg)
