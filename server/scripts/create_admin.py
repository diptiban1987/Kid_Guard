"""Bootstrap or update an admin account from environment variables.

Usage:
    # Create or update the admin account (reads ADMIN_EMAIL, ADMIN_PASSWORD):
    python scripts/create_admin.py

    # Override via CLI args:
    python scripts/create_admin.py --email admin@example.com --password 'S3cret!'

    # Promote an existing user to admin:
    python scripts/create_admin.py --promote user@example.com

The password is hashed with scrypt (werkzeug). If the admin already exists, the
password and display_name are updated to match the env/args.
"""
import argparse
import os
import sys

_here = os.path.dirname(os.path.abspath(__file__))
_server = os.path.dirname(_here)
_parent = os.path.dirname(_server)
for p in (_parent, _server):
    if p not in sys.path:
        sys.path.insert(0, p)


def main():
    parser = argparse.ArgumentParser(description='Create or update an admin account')
    parser.add_argument('--email', default=os.environ.get('ADMIN_EMAIL', ''),
                        help='Admin email (default: ADMIN_EMAIL env var)')
    parser.add_argument('--password', default=os.environ.get('ADMIN_PASSWORD', ''),
                        help='Admin password (default: ADMIN_PASSWORD env var)')
    parser.add_argument('--name', default='Administrator',
                        help='Display name for the admin')
    parser.add_argument('--promote', metavar='EMAIL',
                        help='Promote an existing user to admin (no password change)')
    args = parser.parse_args()

    from server import create_app
    from server.extensions import db
    from server.models import User
    from server.security import hash_password, audit_log

    app = create_app()
    with app.app_context():
        # Ensure the schema exists (dev: create_all; prod should run alembic
        # upgrade head first, but this is a safe no-op if tables exist).
        db.create_all()
        if args.promote:
            user = User.query.filter_by(email=args.promote).first()
            if not user:
                print(f'[!] User not found: {args.promote}')
                sys.exit(1)
            old_role = user.role
            user.role = 'admin'
            db.session.commit()
            audit_log(user.id, 'admin_promote', target_type='user', target_id=user.id,
                      metadata={'old_role': old_role})
            print(f'[OK] Promoted {user.email} to admin (was {old_role}).')
            return

        if not args.email or not args.password:
            print('[!] --email and --password are required (or set ADMIN_EMAIL/ADMIN_PASSWORD env vars).')
            sys.exit(1)
        if len(args.password) < 8:
            print('[!] Admin password must be at least 8 characters.')
            sys.exit(1)

        existing = User.query.filter_by(email=args.email).first()
        if existing:
            existing.password_hash = hash_password(args.password)
            existing.display_name = args.name
            existing.role = 'admin'
            existing.is_active = True
            db.session.commit()
            audit_log(existing.id, 'admin_update', target_type='user', target_id=existing.id)
            print(f'[OK] Updated existing admin: {existing.email}')
        else:
            admin = User(
                email=args.email,
                password_hash=hash_password(args.password),
                display_name=args.name,
                role='admin',
                is_active=True,
            )
            db.session.add(admin)
            db.session.commit()
            audit_log(admin.id, 'admin_create', target_type='user', target_id=admin.id)
            print(f'[OK] Created admin: {admin.email} (id={admin.id})')


if __name__ == '__main__':
    main()
