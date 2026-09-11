// ─── KidGuard Network Resilience ──────────────────────────────────────────
// The dashboard is hosted on Render's free tier, whose edge proxy serves an
// anti-bot "Security Check" interstitial (plus 429/5xx HTML pages) when a
// client's request pattern looks automated — exactly what a dashboard that
// fires 14 API calls per refresh looks like. Those challenge responses
// arrive as HTML instead of JSON, so every panel silently fell back to
// empty data ("0 SMS / 0 Calls / 0 Apps") until a later 30s auto-refresh
// happened to succeed.
//
// This module wraps window.fetch with challenge detection + exponential
// backoff retries, and surfaces a small on-page banner so the parent knows
// data is being retried rather than genuinely empty. It deliberately:
//   - does NOT retry 401/403 responses carrying JSON (real token expiry —
//     handled by fetchWithAuth's refresh flow),
//   - DOES retry 401/403 responses carrying an HTML body (proxy challenge,
//     not our API),
//   - limits non-GET (side-effecting) requests to a single retry.
(function () {
    'use strict';

    const RETRYABLE_STATUS = new Set([408, 429, 500, 502, 503, 504]);
    const GET_RETRIES = 4;          // ≈ 0.8 + 1.6 + 3.2 + 6.4s ≈ 12s worst case
    const OTHER_RETRIES = 1;
    const BASE_DELAY_MS = 800;
    const MAX_DELAY_MS = 8000;

    let bannerEl = null;
    let bannerHideTimer = null;
    let inFlightRetries = 0;

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

    function showBanner() {
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
        bannerEl.textContent = '\uD83D\uDEE1\uFE0F Server security check in progress \u2014 retrying automatically\u2026';
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

    async function resilientFetch(url, options) {
        const opts = options || {};
        const method = (opts.method || 'GET').toUpperCase();
        const maxRetries = method === 'GET' ? GET_RETRIES : OTHER_RETRIES;

        for (let attempt = 0; ; attempt++) {
            let res = null;
            let netErr = null;
            try {
                res = await fetch(url, opts);
            } catch (e) {
                netErr = e;
            }

            const retryable = netErr !== null
                || RETRYABLE_STATUS.has(res.status)
                || isChallenge(res, url);

            if (!retryable || attempt >= maxRetries) {
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
                ' (challenge/5xx); retry ' + (attempt + 1) + '/' + maxRetries + ' in ' + wait + 'ms');
            try {
                await delay(wait);
            } finally {
                inFlightRetries--;
            }
        }
    }

    window.KidGuardNet = { fetch: resilientFetch };
})();
