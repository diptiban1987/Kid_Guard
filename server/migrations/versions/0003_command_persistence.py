"""persist command results + mic chunks

Revision ID: 0003_command_persistence
Revises: 0002_multiuser
Create Date: 2026-07-30 00:00:00

Moves the parent-facing command/mic "live" state out of process-local dicts
into the database so it survives worker restarts and is visible across
multiple gunicorn workers:

  - ``remote_commands.result_type`` + ``remote_commands.updated_at`` — persist
    the result payload metadata that previously lived only in memory.
  - ``mic_chunks`` table — the latest mic-audio chunk per command (a streaming
    buffer, one row per command), replacing the process-local live_mic_chunks
    dict as the source of truth for audio-poll.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = '0003_command_persistence'
down_revision: Union[str, None] = '0002_multiuser'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    with op.batch_alter_table('remote_commands') as batch_op:
        batch_op.add_column(sa.Column('result_type', sa.String(20), nullable=True,
                                      server_default='text'))
        batch_op.add_column(sa.Column('updated_at', sa.BigInteger, nullable=True))

    op.create_table(
        'mic_chunks',
        sa.Column('command_id', sa.String(32),
                  sa.ForeignKey('remote_commands.id'), primary_key=True),
        sa.Column('audio_b64', sa.Text, nullable=False),
        sa.Column('sample_rate', sa.Integer, nullable=True),
        sa.Column('seq', sa.Integer, nullable=True),
        sa.Column('done', sa.Boolean, nullable=True),
        sa.Column('updated_at', sa.BigInteger, nullable=True),
    )


def downgrade() -> None:
    op.drop_table('mic_chunks')
    with op.batch_alter_table('remote_commands') as batch_op:
        batch_op.drop_column('result_type')
        batch_op.drop_column('updated_at')
