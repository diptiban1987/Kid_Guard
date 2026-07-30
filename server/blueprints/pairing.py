"""Pairing blueprint — generate, claim, claim-direct, pending, approve, children.

Security fixes applied here:
  - V5  pairing-approval hijack: ``approve`` verifies the pairing belongs to the
    calling parent before granting (via ``assert_pairing_ownership``).
  - V7  unauthenticated claim-direct: now requires a JWT (the device must first
    register/login). Also rate-limited.
  - Strict one-parent-per-child enforced at the DB level (partial unique index
    from WS-3/Alembic); here we also deactivate any prior active relation before
    activating a new one, so the constraint never trips.
"""
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify, current_app
from flask_jwt_extended import jwt_required, get_jwt_identity

from ..extensions import db, limiter
from ..models import (
    User, ChildRelation, Device, generate_pairing_code,
)
from ..security import (
    parent_required, assert_pairing_ownership, audit_log,
    hash_password,
)

bp = Blueprint('pairing', __name__)


@bp.route('/pairing/generate', methods=['POST'])
@parent_required
def generate_pairing():
    parent_id = get_jwt_identity()
    parent = User.query.get(parent_id)

    code = generate_pairing_code()
    pairing = ChildRelation(
        parent_id=parent_id,
        child_id='pending',
        pairing_code=code,
        is_active=False,
    )
    db.session.add(pairing)
    db.session.commit()

    audit_log(parent_id, 'pairing_generate', target_type='pairing', target_id=pairing.id,
              metadata={'code': code})
    return jsonify({
        'pairing_code': code,
        'expires_in': current_app.config['PAIRING_CODE_TTL'],
        'parent_name': parent.display_name,
    })


@bp.route('/pairing/claim', methods=['POST'])
@jwt_required()
@limiter.limit('3/minute')
def claim_pairing():
    child_id = get_jwt_identity()
    code = request.get_json().get('pairing_code', '').strip().upper()

    child = User.query.get(child_id)
    if child.role != 'child':
        return jsonify({'error': 'Only child accounts can be claimed'}), 400

    pairing = ChildRelation.query.filter_by(
        pairing_code=code, child_id='pending', is_active=False
    ).first()

    if pairing:
        pairing.child_id = child_id
        db.session.commit()
        audit_log(child_id, 'pairing_claim', target_type='pairing', target_id=pairing.id)
        return jsonify({
            'message': 'Pairing request submitted. Parent must approve.',
            'pairing_id': pairing.id,
        })
    # No matching code — create a pending request anyway (preserves original behaviour)
    pairing = ChildRelation(
        parent_id='pending',
        child_id=child_id,
        pairing_code=code,
        is_active=False,
    )
    db.session.add(pairing)
    db.session.commit()
    return jsonify({
        'message': 'Pairing request submitted. Parent must approve.',
        'pairing_id': pairing.id,
    })


@bp.route('/pairing/claim-direct', methods=['POST'])
@jwt_required()
@limiter.limit('3/minute')
def claim_pairing_direct():
    """V7: now requires authentication (the device must login first). The
    original unauthenticated variant is removed — it let anyone with a code
    auto-create a child account and get a JWT.

    The logged-in caller must be a child (or a parent re-linking a child
    device). The pairing code links them to the parent who generated it."""
    caller_id = get_jwt_identity()
    data = request.get_json() or {}
    code = data.get('pairing_code', '').strip().upper()
    device_id = data.get('device_id', '')

    if not code:
        return jsonify({'error': 'pairing_code required'}), 400

    pairing = ChildRelation.query.filter_by(
        pairing_code=code, child_id='pending', is_active=False
    ).first()
    if not pairing:
        return jsonify({'error': 'Invalid or expired pairing code'}), 404

    # Link the authenticated caller as the child.
    pairing.child_id = caller_id
    db.session.commit()

    audit_log(caller_id, 'pairing_claim_direct', target_type='pairing', target_id=pairing.id,
              metadata={'device_id': device_id})
    return jsonify({
        'pairing_id': pairing.id,
        'message': 'Pairing request submitted. Waiting for parent approval.',
    }), 201


@bp.route('/pairing/pending', methods=['GET'])
@parent_required
def get_pending_pairings():
    parent_id = get_jwt_identity()
    pendings = ChildRelation.query.filter_by(
        parent_id=parent_id, is_active=False
    ).filter(ChildRelation.child_id != 'pending').all()

    result = []
    for p in pendings:
        child = User.query.get(p.child_id)
        result.append({
            'id': p.id,
            'child_id': p.child_id,
            'child_email': child.email if child else None,
            'child_name': child.display_name if child else None,
            'pairing_code': p.pairing_code,
            'paired_at': p.paired_at,
        })
    return jsonify(result)


@bp.route('/pairing/approve/<pairing_id>', methods=['POST'])
@parent_required
def approve_pairing(pairing_id):
    """V5: verify the pairing belongs to the calling parent before granting."""
    parent_id = get_jwt_identity()
    pairing = assert_pairing_ownership(pairing_id, parent_id)
    if not pairing:
        return jsonify({'error': 'Pairing not found or access denied'}), 404

    # Strict one-parent-per-child: deactivate any prior active relation for
    # this child so the partial unique index never trips on activation.
    ChildRelation.query.filter_by(
        child_id=pairing.child_id, is_active=True
    ).update({'is_active': False})

    pairing.parent_id = parent_id
    pairing.is_active = True
    pairing.paired_at = int(datetime.now(timezone.utc).timestamp() * 1000)
    db.session.commit()

    audit_log(parent_id, 'pairing_approve', target_type='pairing', target_id=pairing.id,
              metadata={'child_id': pairing.child_id})
    return jsonify({'message': 'Child paired successfully'})


@bp.route('/pairing/children', methods=['GET'])
@parent_required
def get_children():
    parent_id = get_jwt_identity()
    relations = ChildRelation.query.filter_by(parent_id=parent_id, is_active=True).all()

    children_data = []
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    for rel in relations:
        child = User.query.get(rel.child_id)
        devices = Device.query.filter_by(user_id=rel.child_id, is_active=True).all()
        child_devices = [d.to_dict() for d in devices]
        is_recently_paired = rel.paired_at and (now_ms - rel.paired_at) < 600000
        if len(child_devices) > 0 or is_recently_paired:
            children_data.append({
                'relation_id': rel.id,
                'child': child.to_dict() if child else None,
                'devices': child_devices,
                'paired_at': rel.paired_at,
            })
    return jsonify(children_data)
