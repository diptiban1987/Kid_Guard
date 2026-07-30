"""Blueprint package for the AnonChat / KidGuard server.

Each blueprint owns a concern and is registered under ``/api/v1`` by the app
factory in ``server/__init__.py``. The legacy ``/api/*`` paths are preserved
via a 308 redirect so the existing Android client keeps working without a
rebuild.
"""
