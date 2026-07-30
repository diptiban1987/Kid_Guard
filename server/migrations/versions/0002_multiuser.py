"""multiuser: firebase_uid, last_password_change, TokenBlocklist, AuditLog, unique index

Revision ID: 0002_multiuser
Revises: 0001_baseline
Create Date: 2026-07-22 00:01:00

This migration adds the multi-user platform changes:
  - ``users.firebase_uid`` — forward-compat hook for Firebase↔server auth unification.
  - ``users.last_password_change`` — supports invalidating sessions on reset.
  - ``token_blocklist`` table — enables JWT revocation (V12).
  - ``audit_log`` table — accountability for a multi-user platform.
  - Partial unique index on ``child_relations(child_id) WHERE is_active = 1``
    — enforces strict-one-parent-per-child at the DB level.

For existing deployments with legacy SHA-256 password hashes, no data change
is needed here — the hashes are transparently upgraded to scrypt on next login
(see ``security.maybe_rehash``).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = '0002_multiuser'
down_revision: Union[str, None] = '0001_baseline'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # ── New columns on users ───────────────────────────────────────────
    with op.batch_alter_table('users') as batch_op:
        batch_op.add_column(sa.Column('firebase_uid', sa.String(128), nullable=True))
        batch_op.add_column(sa.Column('last_password_change', sa.BigInteger, nullable=True))
        batch_op.create_unique_constraint('uq_users_firebase_uid', ['firebase_uid'])

    # ── token_blocklist (V12 token revocation) ─────────────────────────
    op.create_table(
        'token_blocklist',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('jti', sa.String(64), nullable=False, unique=True),
        sa.Column('user_id', sa.String(32), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('expires_at', sa.BigInteger, nullable=False),
        sa.Column('created_at', sa.BigInteger),
    )
    op.create_index('ix_token_blocklist_jti', 'token_blocklist', ['jti'])

    # ── audit_log (accountability) ─────────────────────────────────────
    op.create_table(
        'audit_log',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('actor_id', sa.String(32), sa.ForeignKey('users.id'), nullable=True),
        sa.Column('action', sa.String(50), nullable=False),
        sa.Column('target_type', sa.String(30)),
        sa.Column('target_id', sa.String(64)),
        sa.Column('ip_address', sa.String(45)),
        sa.Column('metadata_json', sa.Text),
        sa.Column('created_at', sa.BigInteger),
    )

    # ── Strict one-parent-per-child (partial unique index) ─────────────
    # On Postgres this creates a true partial index. On SQLite (render_as_batch),
    # we create the index with the WHERE clause; SQLite supports partial indexes
    # natively since 3.8.0 (2013).
    op.create_index(
        'uq_child_relations_active_child',
        'child_relations',
        ['child_id'],
        unique=True,
        postgresql_where=sa.text('is_active = 1'),
        sqlite_where=sa.text('is_active = 1'),
    )


def downgrade() -> None:
    op.drop_index('uq_child_relations_active_child', table_name='child_relations')
    op.drop_table('audit_log')
    op.drop_index('ix_token_blocklist_jti', table_name='token_blocklist')
    op.drop_table('token_blocklist')
    with op.batch_alter_table('users') as batch_op:
        batch_op.drop_constraint('uq_users_firebase_uid', type_='unique')
        batch_op.drop_column('firebase_uid')
        batch_op.drop_column('last_password_change')
