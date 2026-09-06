"""One-shot database migration: copy all rows from a source DB into a target DB.

Typical use — move the KidGuard data into a free Neon Postgres:

    # From a local SQLite file (e.g. pulled off a Render instance):
    python migrate_db.py --source sqlite:///../tracking.db \\
                         --target postgresql://user:pass@ep-xxx-pooler...neon.tech/neondb?sslmode=require

    # Or from one Postgres to another:
    python migrate_db.py --source postgresql://...old... \\
                         --target postgresql://...neon...

How it works:
  * Reflects the full schema from the SOURCE database.
  * Creates any missing tables in the TARGET (idempotent — existing tables
    are left alone, so this is safe to run against the live server DB).
  * Copies tables in dependency (FK) order, skipping tables that already
    have rows in the target.
  * After copying, resets Postgres identity sequences so new inserts don't
    collide with migrated primary keys.

Only stdlib + SQLAlchemy (already in requirements.txt) are used.
"""
import argparse
import sys

from sqlalchemy import MetaData, create_engine, inspect, text


def copy_database(source_url: str, target_url: str, batch_size: int = 500) -> None:
    src = create_engine(source_url)
    dst = create_engine(target_url)

    src_meta = MetaData()
    src_meta.reflect(bind=src)
    tables = src_meta.sorted_tables
    print(f"Source schema: {len(tables)} tables")

    # Create missing tables in the target (safe: existing tables untouched).
    src_meta.create_all(bind=dst, checkfirst=True)

    dst_inspect = inspect(dst)
    copied, skipped, empty = 0, 0, 0

    for table in tables:
        if not dst_inspect.has_table(table.name):
            print(f"  ! {table.name}: missing in target even after create_all, skipping")
            continue

        # Row counts
        with src.connect() as c:
            src_rows = c.execute(text(f'SELECT COUNT(*) FROM "{table.name}"')).scalar() or 0
        with dst.connect() as c:
            dst_rows = c.execute(text(f'SELECT COUNT(*) FROM "{table.name}"')).scalar() or 0

        if src_rows == 0:
            empty += 1
            print(f"  - {table.name}: source empty")
            continue
        if dst_rows > 0:
            skipped += 1
            print(f"  - {table.name}: target already has {dst_rows} rows, skipped")
            continue

        total = 0
        with src.connect() as rconn, dst.begin() as tconn:
            result = rconn.execution_options(stream_results=True).execute(
                text('SELECT * FROM "%s"' % table.name)
            )
            cols = list(result.keys())
            col_list = ", ".join('"%s"' % c for c in cols)
            placeholders = ", ".join(":%s" % c for c in cols)
            insert_sql = text(
                'INSERT INTO "%s" (%s) VALUES (%s)' % (table.name, col_list, placeholders)
            )
            while True:
                rows = result.fetchmany(batch_size)
                if not rows:
                    break
                payload = [dict(zip(cols, r)) for r in rows]
                tconn.execute(insert_sql, payload)
                total += len(payload)
        copied += 1
        print(f"  + {table.name}: copied {total} rows")

        # Reset identity/serial sequences so future PKs don't collide
        # (Postgres only; errors are swallowed for other dialects).
        for c in table.columns:
            if c.primary_key and c.autoincrement and 'INT' in c.type.__class__.__name__.upper():
                try:
                    with dst.begin() as tconn:
                        tconn.execute(text(
                            f'SELECT setval(pg_get_serial_sequence(\'"{table.name}\", \'{c.name}\'), '
                            f'COALESCE((SELECT MAX("{c.name}") FROM "{table.name}"), 1))'
                        ))
                except Exception as seq_err:
                    print(f"    (sequence reset skipped for {table.name}: {seq_err})")
                break

    print(f"\nDone. copied={copied} skipped={skipped} empty={empty}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source', required=True, help='Source SQLAlchemy URL (sqlite:///... or postgresql://...)')
    ap.add_argument('--target', required=True, help='Target SQLAlchemy URL (the new Neon Postgres)')
    ap.add_argument('--batch-size', type=int, default=500)
    args = ap.parse_args()
    copy_database(args.source, args.target, args.batch_size)
    return 0


if __name__ == '__main__':
    sys.exit(main())
