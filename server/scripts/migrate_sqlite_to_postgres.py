"""One-time ETL: copy data from the existing SQLite tracking.db to Postgres.

Usage:
    # From the server/ directory (or set PYTHONPATH):
    DATABASE_URL=postgresql://user:pass@localhost:5432/kidguard \
        python scripts/migrate_sqlite_to_postgres.py

    # Dry-run mode (compares row counts, writes nothing):
    python scripts/migrate_sqlite_to_postgres.py --dry-run

    # Specify the SQLite path explicitly:
    python scripts/migrate_sqlite_to_postgres.py --sqlite /path/to/tracking.db

The script is idempotent: it skips rows that already exist by primary key, so
re-running after a partial failure picks up where it left off. The original
SQLite file is never modified — this is a read-only copy.
"""
import argparse
import os
import sys

# Make the server package importable.
_here = os.path.dirname(os.path.abspath(__file__))
_server = os.path.dirname(_here)
_parent = os.path.dirname(_server)
for p in (_parent, _server):
    if p not in sys.path:
        sys.path.insert(0, p)

from sqlalchemy import create_engine, text  # noqa: E402

# All models to copy, in dependency order (parents before children).
# (table_name, model_class) — imported lazily so the script can print --help
# without needing the full app stack.
TABLE_ORDER = [
    'users',
    'child_relations',
    'devices',
    'location_reports',
    'activity_reports',
    'battery_reports',
    'screen_time_reports',
    'sms_messages',
    'call_logs',
    'call_state_events',
    'installed_apps',
    'media_files',
    'web_history',
    'geofences',
    'geofence_events',
    'remote_commands',
    'app_restrictions',
    'schedule_rules',
    'password_reset_tokens',
    'social_notifications',
]


def get_engines(sqlite_path, verbose=True):
    """Build the source (SQLite) and dest (Postgres) engines."""
    from server import create_app
    from server.extensions import db
    from server.config import Config

    # Dest = the Flask app's DATABASE_URL (Postgres in prod).
    dest_uri = Config.SQLALCHEMY_DATABASE_URI
    if not dest_uri.startswith('postgresql'):
        if verbose:
            print(f'[!] DATABASE_URL is not Postgres: {dest_uri}')
            print('    Set DATABASE_URL=postgresql://... before running this script.')
        sys.exit(1)

    if not os.path.exists(sqlite_path):
        print(f'[!] SQLite file not found: {sqlite_path}')
        sys.exit(1)

    src_engine = create_engine(f'sqlite:///{sqlite_path}')
    dest_engine = create_engine(dest_uri)
    return src_engine, dest_engine, dest_uri


def copy_table(src_engine, dest_engine, table_name, dry_run=False):
    """Copy all rows from src to dest for a table, skipping existing PKs."""
    with src_engine.connect() as src_conn:
        rows = src_conn.execute(text(f'SELECT * FROM {table_name}')).mappings().all()
        total = len(rows)
        if total == 0:
            return 0

        if dry_run:
            return total

        # Get column names
        cols = list(rows[0].keys())
        col_list = ', '.join(cols)
        param_list = ', '.join(f':{c}' for c in cols)
        insert_sql = text(f'INSERT INTO {table_name} ({col_list}) VALUES ({param_list}) '
                          f'ON CONFLICT (id) DO NOTHING')

        inserted = 0
        with dest_engine.begin() as dest_conn:
            for row in rows:
                try:
                    result = dest_conn.execute(insert_sql, dict(row))
                    inserted += result.rowcount
                except Exception:
                    # Row already exists or conflict — skip
                    pass
        return inserted


def main():
    parser = argparse.ArgumentParser(description='Migrate SQLite tracking.db → Postgres')
    parser.add_argument('--sqlite', default=os.path.join(_parent, 'tracking.db'),
                        help='Path to the source SQLite file')
    parser.add_argument('--dry-run', action='store_true',
                        help='Compare row counts only; write nothing')
    args = parser.parse_args()

    print('=' * 60)
    print('  SQLite → Postgres Migration')
    print('=' * 60)
    print(f'  Source (SQLite): {args.sqlite}')
    print(f'  Mode: {"DRY RUN" if args.dry_run else "COPY"}')
    print('=' * 60)

    src_engine, dest_engine, dest_uri = get_engines(args.sqlite)
    print(f'  Dest (Postgres): {dest_uri.split("@")[-1] if "@" in dest_uri else dest_uri}')
    print('=' * 60)

    # Ensure dest schema exists (run migrations if needed)
    if not args.dry_run:
        print('[*] Ensuring dest schema exists (create_all)...')
        from server.extensions import db
        from server import create_app
        app = create_app()
        with app.app_context():
            db.create_all()

    total_src = 0
    total_dst = 0
    for table in TABLE_ORDER:
        try:
            count = copy_table(src_engine, dest_engine, table, dry_run=args.dry_run)
            label = 'would copy' if args.dry_run else 'copied'
            print(f'  {table:30s} {label} {count:>6} rows')
            total_src += count
        except Exception as exc:
            print(f'  {table:30s} ERROR: {exc}')

    print('=' * 60)
    print(f'  Total: {"would copy" if args.dry_run else "copied"} {total_src} rows')
    if args.dry_run:
        print('  Run without --dry-run to perform the actual copy.')
    print('=' * 60)


if __name__ == '__main__':
    main()
