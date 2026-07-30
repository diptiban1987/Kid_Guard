"""Shared Flask extension instances.

Imported by the app factory (``server.__init__``) and by blueprints. Declared
here so there's a single binding point — models use ``db``, blueprints use
``jwt`` / ``limiter``, and the app factory calls ``init_app`` on each.
"""
from flask_sqlalchemy import SQLAlchemy
from flask_jwt_extended import JWTManager
from flask_cors import CORS
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address


def _rate_limit_key():
    """Key rate limits by the authenticated user's JWT identity when present,
    falling back to the client IP for anonymous endpoints (login/register).

    Why: without ProxyFix the app sits behind nginx and every client shares the
    proxy's IP, so per-IP limits made ONE chatty client 429 EVERYONE. Keying by
    user id isolates limits to the actual caller; anonymous auth endpoints still
    get per-IP protection (now the REAL client IP once ProxyFix is installed)."""
    try:
        from flask_jwt_extended import get_jwt_identity, verify_jwt_in_request
        verify_jwt_in_request(optional=True)
        identity = get_jwt_identity()
        if identity:
            return f"user:{identity}"
    except Exception:
        pass
    return get_remote_address()


db = SQLAlchemy()
jwt = JWTManager()
cors = CORS()
limiter = Limiter(key_func=_rate_limit_key, default_limits=[])

# In-memory stores for real-time call state and command-result polling.
# These are process-local (no Redis) hot caches. Command RESULTS and mic chunks
# are ALSO persisted to the DB (see store_command_result / store_mic_chunk) so
# they survive worker restarts and are visible across multiple gunicorn workers.
live_call_state = {}        # device_id -> {state, phone_number, timestamp, streaming}
live_audio_streams = {}     # device_id -> {active, last_chunk_time, sample_rate}
live_command_results = {}   # command_id -> {status, result_type, data, command, updated_at}
live_mic_chunks = {}        # command_id -> {audio_b64, sample_rate, seq, done, updated_at}


def store_command_result(command_id, status, result_type, data, command, updated_at):
    """Write a command result to the in-memory cache AND persist it on the
    RemoteCommand row so a parent polling from any worker (or after a restart)
    still sees it. Best-effort persistence: never raises into the request path."""
    live_command_results[command_id] = {
        'status': status, 'result_type': result_type, 'data': data,
        'command': command, 'updated_at': updated_at,
    }
    try:
        from .models import RemoteCommand
        cmd = RemoteCommand.query.get(command_id)
        if cmd:
            cmd.status = status
            cmd.result = data
            cmd.result_type = result_type
            cmd.updated_at = updated_at
            if status == 'completed' and not cmd.completed_at:
                cmd.completed_at = updated_at
            db.session.commit()
    except Exception:
        db.session.rollback()


def store_mic_chunk(command_id, audio_b64, sample_rate, seq, done, updated_at):
    """Write the latest mic chunk to the in-memory cache AND upsert the
    MicChunk row so audio-poll works across workers / restarts."""
    live_mic_chunks[command_id] = {
        'audio_b64': audio_b64, 'sample_rate': sample_rate,
        'seq': seq, 'done': done, 'updated_at': updated_at,
    }
    try:
        from .models import MicChunk
        row = MicChunk.query.get(command_id)
        if row is None:
            row = MicChunk(command_id=command_id)
            db.session.add(row)
        row.audio_b64 = audio_b64
        row.sample_rate = sample_rate
        row.seq = seq
        row.done = done
        row.updated_at = updated_at
        db.session.commit()
    except Exception:
        db.session.rollback()
