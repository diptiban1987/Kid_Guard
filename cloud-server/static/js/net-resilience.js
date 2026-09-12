// ─── KidGuard Network Resilience ──────────────────────────────────────────
// The dashboard is hosted on Render's free tier, whose edge proxy serves an
// anti-bot "Security Check" interstitial (plus 429/5xx HTML pages) when a
// client's request pattern looks automated — exactly what a dashboard that
// fires several API calls per refresh looks like. Those challenge responses
// arrive as HTML instead of JSON, so every panel silently fell back to
// empty data until a later auto-refresh happened to succeed.
//
// This module wraps window.fetch with challenge detection + retries, and
// surfaces a small on-page banner so the parent knows data is being retried
// rather than genuinely empty. It deliberately:
//   - does NOT retry 401/403 responses carrying JSON (real token expiry —
//     handled by fetchWithAuth's refresh flow),
//   - DOES retry 401/403 responses carrying an HTML body (proxy challenge,
//     not our API),
//   - treats HTTP 429 (rate limit) as a FIRST-CLASS case: long waits that
//     honor Retry-After, far fewer retries — hammering a rate limiter with
//     quick retries only extends the limit,
//   - runs a GLOBAL circuit breaker: after several consecutive 429s across
//     any requests, ALL outbound requests pause for a cool-down window.
//     One open dashboard panel that keeps polling can otherwise keep the
//     whole browser pinned inside the rate limit indefinitely,
//   - de-duplicates concurrent identical GETs (single-flight) so overlapping
//     panels (poll + pairing modal) share one request instead of two.
(function () {
    'use strict';

    const RETRYABLE_5XX = new Set([408, 500, 502, 503, 504]);
    const GET_5XX_RETRIES = 4;      // ≈ 0.8 + 1.6 + 3.2 + 6.4s ≈ 12s worst case
    const OTHER_RETRIES = 1;
    const BASE_DELAY_MS = 800;
    const MAX_DELAY_MS = 8000;

    // Rate-limit (429) track: FEW retries with LONG waits. Retrying a rate
    // limiter every second is how a 30-second limit becomes a 10-minute one.
    const RATE_LIMIT_RETRIES = 2;
    const RATE_LIMIT_BASE_MS = 4000;    // 4s → 12s (honor Retry-After when sent)
    const RATE_LIMIT_MAX_MS = 30000;

    // Global circuit breaker: N consecutive 429s (any endpoint) → cool-down.
    const BREAKER_THRESHOLD = 3;
    const BREAKER_COOLDOWN_MS = 60000;

    let bannerEl = null;
    let bannerHideTimer = null;
    let inFlightRetries = 0;

    // Circuit breaker state (shared by every request in the page)
    let consecutiveRateLimits = 0;
    let breakerOpenUntil = 0;

    // Single-flight: in-flight identical GETs share one promise
    const inFlightGets = new Map();   // method|url → Promise<Response>

    function isJsonResponse(res) {
        const ct = (res && res.headers && res.headers.get('content-type')) || '';
        return ct.toLowerCase().includes('application/json');
    }

    function isChallenge(res, url) {
        // Our Flask API always answers JSON. Anything else on an /api/ route
        // is the edge proxy talking (challenge page, 5xx HTML, empty body).
        if (!res || isJsonResponse(res)) return false;
        try {
            const path = new URL(url, window.location.href).pathname;
            return path.indexOf('/api/') === 0;
        } catch (_) {
            return true;
        }
    }

    function retryAfterMs(res, attempt) {
        // Honor the server's Retry-After header when present (seconds or HTTP-date).
        try {
            const ra = res && res.headers && res.headers.get('retry-after');
            if (ra) {
                const asSec = parseInt(ra, 10);
                if (!isNaN(asSec)) return Math.min(asSec * 1000, RATE_LIMIT_MAX_MS);
                const asDate = Date.parse(ra);
                if (!isNaN(asDate)) return Math.min(Math.max(asDate - Date.now(), 1000), RATE_LIMIT_MAX_MS);
            }
        } catch (_) { /* header unreadable — fall through */ }
        return Math.min(RATE_LIMIT_BASE_MS * Math.pow(3, attempt), RATE_LIMIT_MAX_MS)
            + Math.floor(Math.random() * 800);
    }

    function showBanner(text) {
        if (!bannerEl) {
            bannerEl = document.createElement('div');
            bannerEl.id = 'kg-net-banner';
            bannerEl.setAttribute('style', [
                'position:fixed', 'top:0', 'left:0', 'right:0', 'z-index:99999',
                'display:none', 'padding:8px 16px',
                'background:#b45309', 'color:#ffffff',
                'font:600 13px/1.4 system-ui,sans-serif', 'text-align:center',
                'box-shadow:0 2px 8px rgba(0,0,0,0.35)'
            ].join(';'));
            (document.body || document.documentElement).appendChild(bannerEl);
        }
        if (bannerHideTimer) { clearTimeout(bannerHideTimer); bannerHideTimer = null; }
        bannerEl.textContent = text ||
            '\uD83D\uDEE1\uFE0F Server security check in progress \u2014 retrying automatically\u2026';
        bannerEl.style.display = 'block';
    }

    function hideBannerSoon() {
        if (!bannerEl || bannerEl.style.display === 'none') return;
        if (bannerHideTimer) clearTimeout(bannerHideTimer);
        bannerHideTimer = setTimeout(() => {
            bannerEl.style.display = 'none';
            bannerHideTimer = null;
        }, 2500);
    }

    function delay(ms) { return new Promise(r => setTimeout(r, ms)); }

    async function rawFetch(url, opts) {
        let res = null;
        let netErr = null;
        try {
            res = await fetch(url, opts);
        } catch (e) {
            netErr = e;
        }
        return { res, netErr };
    }

    async function resilientFetch(url, options) {
        const opts = options || {};
        const method = (opts.method || 'GET').toUpperCase();

        // Single-flight: overlapping identical GETs share one request. The
        // pairing modal and the poll loop both fetch /api/parent/devices;
        // without dedup a single refresh costs double under load.
        if (method === 'GET') {
            const key = method + '|' + url;
            const existing = inFlightGets.get(key);
            if (existing) return existing;
            const p = (async () => {
                try {
                    return await resilientFetchInner(url, opts, method);
                } finally {
                    inFlightGets.delete(key);
                }
            })();
            inFlightGets.set(key, p);
            return p;
        }
        return resilientFetchInner(url, opts, method);
    }

    async function resilientFetchInner(url, opts, method) {
        // Circuit breaker cool-down: after repeated rate limits across the
        // page, wait quietly instead of feeding the limiter more requests.
        if (breakerOpenUntil > Date.now()) {
            const waitMs = breakerOpenUntil - Date.now();
            showBanner('\u23F8\uFE0F Rate limited \u2014 pausing requests ' +
                Math.ceil(waitMs / 1000) + 's to avoid extending the limit\u2026');
            await delay(waitMs);
            hideBannerSoon();
        }

        const isGet = method === 'GET';
        const max5xx = isGet ? GET_5XX_RETRIES : OTHER_RETRIES;

        for (let attempt = 0; ; attempt++) {
            const { res, netErr } = await rawFetch(url, opts);

            // ── Rate limit (429): own track + global breaker ──────────────
            if (netErr === null && res.status === 429) {
                consecutiveRateLimits++;
                if (consecutiveRateLimits >= BREAKER_THRESHOLD) {
                    breakerOpenUntil = Date.now() + BREAKER_COOLDOWN_MS;
                    consecutiveRateLimits = 0;
                    console.warn('[net] circuit breaker OPEN \u2014 pausing all requests for ' +
                        (BREAKER_COOLDOWN_MS / 1000) + 's');
                }
                // 2 tries max for rate limits, with long Retry-After-aware waits
                if (attempt >= RATE_LIMIT_RETRIES) {
                    if (inFlightRetries === 0) hideBannerSoon();
                    return res;
                }
                inFlightRetries++;
                try { showBanner(); } catch (_) {}
                const wait = retryAfterMs(res, attempt);
                console.warn('[net] ' + method + ' ' + url + ' \u2192 HTTP 429 (rate limit); ' +
                    'retry ' + (attempt + 1) + '/' + RATE_LIMIT_RETRIES + ' in ' + wait + 'ms');
                try {
                    await delay(wait);
                } finally {
                    inFlightRetries--;
                }
                continue;
            }

            // A successful (non-429) response resets the breaker streak
            if (netErr === null && res.status < 400) consecutiveRateLimits = 0;

            // ── 5xx / network error / proxy challenge: original track ─────
            const retryable = netErr !== null
                || RETRYABLE_5XX.has(res.status)
                || isChallenge(res, url);

            if (!retryable || attempt >= max5xx) {
                if (netErr) throw netErr;
                if (inFlightRetries === 0) hideBannerSoon();
                return res;
            }

            inFlightRetries++;
            try { showBanner(); } catch (_) {}
            const wait = Math.min(BASE_DELAY_MS * Math.pow(2, attempt), MAX_DELAY_MS)
                + Math.floor(Math.random() * 400);
            console.warn('[net] ' + method + ' ' + url + ' \u2192 ' +
                (netErr ? 'network error' : 'HTTP ' + res.status) +
                ' (challenge/5xx); retry ' + (attempt + 1) + '/' + max5xx + ' in ' + wait + 'ms');
            try {
                await delay(wait);
            } finally {
                inFlightRetries--;
            }
        }
    }

    window.KidGuardNet = { fetch: resilientFetch };
})();
