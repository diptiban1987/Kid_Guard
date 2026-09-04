"""ensure chat_messages table exists

Revision ID: 0005_chat_messages
Revises: 0004_password_reset_tokens
Create Date: 2026-09-04 22:00:00

Idempotent. Why this migration exists:

  The ``ChatMessage`` model in ``server/models.py`` maps ``chat_messages`` but
  the baseline Alembic migration (``0001_baseline``) did not include it, and
  the table was never added in a follow-up migration. The model is referenced
  by:

  - ``GET /api/parent/device/<id>/chats`` in ``blueprints/parent.py``
  - ``/api/admin/chats`` and ``/api/admin/chats/export`` in
    ``blueprints/admin.py``
  - ``/api/reports`` in ``blueprints/reports.py`` (parental device reports
    that may include ``chat_messages`` data)

  On any deployment that does not already have the table, hitting
  ``/api/parent/device/<id>/chats`` raises
  ``sqlalchemy.exc.OperationalError: no such table: chat_messages``,
  which the generic error handler turns into HTTP 500. The device-detail
  page now reports ``Failed to load resource: 500`` for that endpoint and
  the AnonChat tab is empty.

  This migration creates the table if it is missing, and is a no-op
  otherwise.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = '0005_chat_messages'
down_revision: Union[str, None] = '0004_password_reset_tokens'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    from sqlalchemy import inspect
    return name in inspect(op.get_bind()).get_table_names()


def upgrade() -> None:
    if _has_table('chat_messages'):
        return

    op.create_table(
        'chat_messages',
        sa.Column('id', sa.String(32), primary_key=True),
        sa.Column('chat_id', sa.String(100), index=True),
        sa.Column('sender_id', sa.String(100), index=True),
        sa.Column('sender_name', sa.String(100)),
        sa.Column('recipient_id', sa.String(100), index=True),
        sa.Column('recipient_name', sa.String(100)),
        sa.Column('content', sa.Text),
        sa.Column('type', sa.String(20), server_default='text'),
        sa.Column('image_url', sa.String(1000)),
        sa.Column('timestamp', sa.BigInteger, index=True),
        sa.Column('received_at', sa.BigInteger),
    )


def downgrade() -> None:
    if not _has_table('chat_messages'):
        return
    op.drop_table('chat_messages')
