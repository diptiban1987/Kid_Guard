# Migrations Guide

This guide covers Alembic migrations, the SQLite→Postgres data migration, and
how to add future migrations for the AnonChat / KidGuard server.

---

## Prerequisites

```bash
cd server
pip install -r requirements.txt   # includes alembic + psycopg2-binary
```

## Migration files

| File | Revision ID | Description |
|------|-----------|-------------|
| `migrations/versions/0001_baseline.py` | `0001_baseline` | Reproduces the full schema as it existed in the monolithic `app.py` — all 20 tables. |
| `migrations/versions/0002_multiuser.py` | `0002_multiuser` | Adds `firebase_uid`, `last_password_change` on `users`; creates `token_blocklist` + `audit_log` tables; adds the partial unique index enforcing strict-one-parent-per-child. |

---

## Scenario 1: Fresh deployment (new Postgres database)

```bash
# 1. Set the database URL
export DATABASE_URL="postgresql://user:password@localhost:5432/kidguard"

# 2. Run migrations
python -m alembic upgrade head

# 3. Bootstrap an admin account
python scripts/create_admin.py --email admin@example.com --password 'StrongPass!'

# 4. Start the server
python run.py
```

## Scenario 2: Existing SQLite deployment → Postgres

This is the path for the current production data (552 KB in `tracking.db`).

```bash
# 1. Create the Postgres database (e.g. on PythonAnywhere or your host)
createdb kidguard

# 2. Set the Postgres URL
export DATABASE_URL="postgresql://user:password@localhost:5432/kidguard"

# 3. Run migrations to create the schema in Postgres
python -m alembic upgrade head

# 4. Dry-run the data migration (compares row counts, writes nothing)
python scripts/migrate_sqlite_to_postgres.py --dry-run

# 5. Perform the actual copy
python scripts/migrate_sqlite_to_postgres.py

# 6. Verify row counts match (dry-run shows both sides)
python scripts/migrate_sqlite_to_postgres.py --dry-run

# 7. Point the server at Postgres and restart
export DATABASE_URL="postgresql://user:password@localhost:5432/kidguard"
python run.py
```

The original `tracking.db` is **never modified** — the script reads from it
and writes to Postgres. It's idempotent (skips rows that already exist by PK),
so re-running after a partial failure picks up where it left off.

## Scenario 3: Existing SQLite deployment, staying on SQLite

If you want to stay on SQLite but apply the multi-user schema changes:

```bash
# 1. Back up the existing database
cp tracking.db tracking.db.bak

# 2. Stamp the baseline (tells Alembic the current tables are at 0001_baseline
#    WITHOUT running the baseline migration, which would try to CREATE TABLE)
python -m alembic stamp 0001_baseline

# 3. Apply the multi-user changes
python -m alembic upgrade head
```

---

## How Alembic is configured

- **`alembic.ini`** — standard Alembic config. The `sqlalchemy.url` is a fallback
  only; `env.py` overrides it with the Flask app's `SQLALCHEMY_DATABASE_URI`
  (which reads the `DATABASE_URL` env var).
- **`migrations/env.py`** — calls `create_app()` to get the Flask config + the
  SQLAlchemy metadata, so autogenerate sees all models. Uses
  `render_as_batch=True` for SQLite compatibility (batch mode).
- **`migrations/script.py.mako`** — template for new migration files.

## Adding a new migration

```bash
# 1. Make a model change in models.py (e.g. add a column)

# 2. Autogenerate a migration
python -m alembic revision --autogenerate -m "add_household_table"

# 3. Review the generated file in migrations/versions/
#    (autogenerate is a starting point — always review!)

# 4. Apply it
python -m alembic upgrade head
```

### Manual migration (when autogenerate isn't enough)

```bash
python -m alembic revision -m "add_household_table"
# Then edit the generated file in migrations/versions/
```

## Common Alembic commands

```bash
python -m alembic current          # Show current revision
python -m alembic history          # Show migration history
python -m alembic upgrade head     # Apply all pending migrations
python -m alembic downgrade -1     # Roll back one migration
python -m alembic stamp head       # Mark DB as up-to-date without running migrations
python -m alembic stamp 0001_baseline  # Mark DB as at baseline (for existing SQLite)
```

---

## The partial unique index (strict one-parent-per-child)

Migration `0002_multiuser` creates:

```sql
CREATE UNIQUE INDEX uq_child_relations_active_child
  ON child_relations (child_id)
  WHERE is_active = 1;
```

This enforces that a child can have **at most one active parent** at the DB
level. Transferring a child to a new parent requires deactivating the old
relation first (the `approve_pairing` route in `blueprints/pairing.py` does
this automatically). Historical (inactive) relations are preserved for audit.

## Legacy password hash migration (V11)

No migration is needed for passwords. The old unsalted SHA-256 hashes are
detected at login time via `security.is_legacy_hash()` and transparently
upgraded to scrypt via `security.maybe_rehash()`. This is a no-downtime,
lazy migration — each user is upgraded on their next successful login.
