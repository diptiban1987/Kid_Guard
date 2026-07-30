"""Alembic environment configuration.

Reads the database URL from the Flask app config (via ``create_app()``) so the
same ``DATABASE_URL`` env var drives both the app and the migrations. Targets
``models.metadata`` so autogenerate sees all tables.
"""
from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

# Import the Flask app to get its config + metadata.
import os
import sys

_here = os.path.dirname(os.path.abspath(__file__))
_server = os.path.dirname(_here)  # server/
_parent = os.path.dirname(_server)  # AnonChat-Deployment/
if _parent not in sys.path:
    sys.path.insert(0, _parent)

from server import create_app  # noqa: E402
from server.extensions import db  # noqa: E402

config = context.config

# Use the Flask app's SQLALCHEMY_DATABASE_URI for migrations.
app = create_app()
config.set_main_option('sqlalchemy.url', app.config['SQLALCHEMY_DATABASE_URI'])

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = db.metadata


def run_migrations_offline():
    """Run migrations in offline mode (emit SQL without a DB connection)."""
    url = config.get_main_option('sqlalchemy.url')
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={'paramstyle': 'named'},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online():
    """Run migrations in online mode (with a live DB connection)."""
    connectable = engine_from_config(
        config.get_section(config.config_ini_section),
        prefix='sqlalchemy.',
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
            # Render partial indexes (needed for the child_relations unique index).
            render_as_batch=True,  # SQLite compatibility
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
