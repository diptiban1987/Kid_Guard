# Render-ready Dockerfile for the modern server/ Flask package.
# Builds the WHOLE project so `import server` resolves inside the container.
#
# Render settings (Web Service → Docker):
#   Root Directory  = <project root>  (i.e. the dir that contains server/)
#   Dockerfile path = /Dockerfile       (this file, at the repo root)
#
# PORT is injected by Render (default 10000). We honor it via ${PORT:-5000}
# in the CMD below.
FROM python:3.11-slim

WORKDIR /app

# Build deps for psycopg2-binary + cryptography
RUN apt-get update && apt-get install -y --no-install-recommends \
        libpq-dev gcc libffi-dev \
    && rm -rf /var/lib/apt/lists/*

# Install Python deps first (_cache layer)
COPY server/requirements.txt ./server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt

# Copy the server package only (keeps image small; ignores Android sources)
COPY server/ ./server/

# OTA artifacts: app_mgmt.py's repo fallback reads cloud-server/apk when the
# ephemeral uploads dir has no version.json (Render free tier wipes uploads
# on every deploy). Without this, check-update answered latest_version=0 and
# devices were told "you're up to date" forever — v1.2 devices never got the
# v1.3 update.
COPY cloud-server/apk/ ./cloud-server/apk/

# Upload directory (mounted as a Render Disk in prod; ephemeral on free tier)
RUN mkdir -p /app/uploads /app/uploads/apk

ENV PYTHONPATH=/app \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    FLASK_AUTO_CREATE=1

EXPOSE 5000

# Shell form so ${PORT} (set by Render) is honored at runtime.
CMD gunicorn --worker-class eventlet --workers 1 \
    --timeout 120 \
    --bind 0.0.0.0:${PORT:-5000} \
    server.wsgi:application
