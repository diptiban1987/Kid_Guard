"""ensure password_reset_tokens table exists

Revision ID: 0004_password_reset_tokens
Revises: 0003_command_persistence
Create Date: 2026-08-17 00:00:00

Idempotent. Why this migration exists:

  ``0001_baseline`` creates ``password_reset_tokens`` in its ``upgrade()``,
  but the docstring of that migration tells users with an existing DB to
  ``alembic stamp 0001_baseline`` (skipping it) then ``alembic upgrade head``.
  For deployments whose DB predates the ``PasswordResetToken`` model, that
  stamp marks the table as existing when it isn't — so the controller in
  ``blueprints/auth.forgot_password`` hits

      sqlalchemy.exc.OperationalError: no such table: password_reset_tokens

  which the generic error handler turns into HTTP 500 on
  ``POST /api/auth/forgot-password``.

  This migration re-creates the table if missing, and is a no-op otherwise.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = '0004_password_reset_tokens'
down_revision: Union[str, None] = '0003_command_persistence'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    from sqlalchemy import inspect
    return name in inspect(op.get_bind()).get_table_names()


def upgrade() -> None:
    if _has_table('password_reset_tokens'):
        return

    op.create_table(
        'password_reset_tokens',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('user_id', sa.String(32), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('token', sa.String(64), nullable=False, unique=True),
        sa.Column('used', sa.Boolean, server_default=sa.text('0')),
        sa.Column('expires_at', sa.BigInteger, nullable=False),
        sa.Column('created_at', sa.BigInteger),
    )
    op.create_index(
        'ix_password_reset_tokens_token',
        'password_reset_tokens', ['token'],
    )


def downgrade() -> None:
    if not _has_table('password_reset_tokens'):
        return
    op.drop_index('ix_password_reset_tokens_token', table_name='password_reset_tokens')
    op.drop_table('password_reset_tokens')
