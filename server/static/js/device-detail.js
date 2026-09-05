// ─── KidGuard Device Detail ───────────────────────────────────────────────
// Companion script for device.html

const TOKEN_KEY = 'kidguard_token';
const REFRESH_KEY = 'kidguard_refresh';

let TOKEN = localStorage.getItem(TOKEN_KEY);
let deviceMap;
let mapMarkers = [];
let mapPolyline = null;
let geoCircles = [];
let refreshTimer = null;

// Cached data
let cachedLocations = [];
let cachedActivity = [];
let cachedSMS = [];
let cachedCalls = [];
let cachedApps = [];
let cachedWebHistory = [];
let cachedMedia = [];
let cachedGeofences = [];
let cachedRestrictions = [];
let cachedSchedule = [];
let cachedScreenTime = [];
let cachedSocial = [];
let cachedChats = [];
let deviceInfo = {};

// Active date-range preset for the time-series sections. One of 'today' | '7d'
// | '30d' | 'all'. Default 'all' so the parent sees the entire history on
// first load (matches the request "add filters … all time from installed to
// the till now"). Persisted in localStorage so the choice survives reloads.
const RANGE_KEY = 'kidguard_device_range';
let activeRange = localStorage.getItem(RANGE_KEY) || 'all';
const RANGE_PRESETS = ['today', '7d', '30d', 'all'];

// ─── Init ─────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    if (!TOKEN) {
        window.location.href = '/';
        return;
    }
    loadUser();
    initMap();
    loadAllData();
    setupTabs();
    setupRangeChips();
    setupStorage();
    startAutoRefresh();
});

// ─── Auth ─────────────────────────────────────────────────────────────────

async function loadUser() {
    try {
        const res = await fetchWithAuth('/api/auth/me');
        if (!res.ok) throw new Error('Unauthorized');
        const data = await res.json();
        const user = data.user;
        document.getElementById('navUser').textContent = user.display_name;
    } catch (e) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(REFRESH_KEY);
        window.location.href = '/';
    }
}

function logout() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    window.location.href = '/';
}

// ─── Fetch with Auth (auto-refresh) ──────────────────────────────────────

async function fetchWithAuth(url, options = {}) {
    if (!options.headers) options.headers = {};
    options.headers['Authorization'] = `Bearer ${TOKEN}`;
    options.headers['Content-Type'] = 'application/json';
    // Identify the dashboard's traffic so an upstream WAF (e.g. Cloudflare)
    // can allowlist the dashboard from generic-bot rate limits. The device
    // uses User-Agent; the browser cannot be UA-spoofed, so a custom header
    // is the next best signal.
    options.headers['X-KidGuard-Client'] = 'dashboard/1.0';

    let res = await fetch(url, options);
    // If the proxy is challenging the request (HTTP 401/403/429 with a
    // non-JSON body), wait briefly and retry once. This recovers from the
    // Cloudflare "Just a moment" challenge without forcing a full page
    // reload.
    if ((res.status === 429 || res.status === 401 || res.status === 403) &&
        !isJsonResponse(res)) {
        await sleep(2500);
        res = await fetch(url, options);
    }
    if (res.status === 401 || res.status === 403) {
        const refresh = localStorage.getItem(REFRESH_KEY);
        if (refresh) {
            const refreshRes = await fetch('/api/auth/refresh', {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${refresh}` }
            });
            if (refreshRes.ok) {
                const data = await refreshRes.json();
                TOKEN = data.token;
                localStorage.setItem(TOKEN_KEY, TOKEN);
                options.headers['Authorization'] = `Bearer ${TOKEN}`;
                const retryRes = await fetch(url, options);
                if (retryRes.ok) return retryRes;
            }
        }
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(REFRESH_KEY);
        window.location.href = '/';
        return res;
    }
    return res;
}

function isJsonResponse(res) {
    const ct = res.headers.get('content-type') || '';
    return ct.includes('application/json');
}

function sleep(ms) {
    return new Promise(r => setTimeout(r, ms));
}

// ─── Map ──────────────────────────────────────────────────────────────────

// OSM is the default. Carto's `dark_all` / `light_all` are now key-gated
// (we used to flash "API KEY REQUIRED" on every tile), so they are
// intentionally not offered. `dark_nolabels` / `light_nolabels` were the
// key-free Carto fallback but the parent has asked to drop Carto entirely.
const TILE_PROVIDERS = {
    osm: {
        url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a> contributors',
        subdomains: 'abc',
        maxZoom: 19,
        labels: 'OpenStreetMap'
    },
    esri: {
        url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        attribution: 'Tiles &copy; Esri',
        maxZoom: 19,
        labels: 'Esri Satellite'
    },
    terrain: {
        url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
        attribution: 'Map data: &copy; OSM, SRTM | OpenTopoMap',
        subdomains: 'abc',
        maxZoom: 17,
        labels: 'OpenTopoMap'
    }
};
const DEFAULT_TILE_PROVIDER = 'osm';
let currentTileProvider = DEFAULT_TILE_PROVIDER;
let currentTileLayer = null;
let tileErrorCount = 0;

function updateTileStatus(level, text) {
    const el = document.getElementById('mapTileStatus');
    if (!el) return;
    el.classList.remove('warn', 'error');
    if (level === 'warn') el.classList.add('warn');
    if (level === 'error') el.classList.add('error');
    el.title = text || '';
}

function setTileProvider(key) {
    if (!TILE_PROVIDERS[key]) key = DEFAULT_TILE_PROVIDER;
    const cfg = TILE_PROVIDERS[key];
    if (currentTileLayer) {
        deviceMap.removeLayer(currentTileLayer);
    }
    const opts = { attribution: cfg.attribution, maxZoom: cfg.maxZoom || 19 };
    if (cfg.subdomains) opts.subdomains = cfg.subdomains;
    currentTileLayer = L.tileLayer(cfg.url, opts).addTo(deviceMap);
    currentTileLayer.on('tileerror', () => {
        tileErrorCount += 1;
        if (tileErrorCount > 30) updateTileStatus('error', 'Many tile errors - try another style');
        else if (tileErrorCount > 5) updateTileStatus('warn', `${tileErrorCount} tile errors`);
    });
    currentTileLayer.on('tileload', () => {
        if (tileErrorCount > 0) {
            tileErrorCount = Math.max(0, tileErrorCount - 1);
            if (tileErrorCount === 0) updateTileStatus('ok', 'Tiles loading');
        }
    });
    currentTileProvider = key;
    // Update dropdown label + selection state if the dropdown exists.
    const label = document.getElementById('mapStyleLabel');
    if (label) label.textContent = cfg.labels;
    document.querySelectorAll('#mapStyleMenu .map-dropdown-item').forEach(li => {
        li.classList.toggle('selected', li.dataset.value === key);
    });
}

let pickMode = false;
let pickMarker = null;
// Once the user pans/zooms the map themselves we stop auto-fitting on
// every data refresh, otherwise every 60 s the view would snap back and
// the user's chosen zoom/center is lost. The Fit-to-points button and
// the first auto-fit on the very first data load still work.
let userHasPanned = false;

function fitBoundsToPoints() {
    if (!deviceMap) {
        console.warn('[map] fitBoundsToPoints: deviceMap not ready');
        return;
    }
    const layers = [];
    mapMarkers.forEach(m => layers.push(m));
    if (mapPolyline) layers.push(mapPolyline);
    geoCircles.forEach(c => layers.push(c));
    if (layers.length === 0) {
        console.warn('[map] fitBoundsToPoints: no points to fit');
        return;
    }
    const group = L.featureGroup(layers);
    try {
        const b = group.getBounds();
        if (!b.isValid()) {
            console.warn('[map] fitBoundsToPoints: invalid bounds');
            return;
        }
        deviceMap.fitBounds(b.pad(0.1));
        userHasPanned = false; // next refresh will re-fit only if no further interaction
        console.log('[map] fitBoundsToPoints: fit', layers.length, 'layers');
    } catch (e) {
        console.warn('[map] fitBoundsToPoints failed', e);
    }
}

function togglePickMode() {
    pickMode = !pickMode;
    const btn = document.getElementById('pickOnMapBtn');
    const cross = document.getElementById('mapCrosshair');
    if (btn) btn.classList.toggle('active', pickMode);
    if (cross) cross.style.display = pickMode ? 'block' : 'none';
    if (deviceMap && deviceMap.getContainer) {
        deviceMap.getContainer().style.cursor = pickMode ? 'crosshair' : '';
    }
}

function setupMapStyleDropdown() {
    const btn = document.getElementById('mapStyleBtn');
    const menu = document.getElementById('mapStyleMenu');
    if (!btn || !menu) return;
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const open = !menu.hidden;
        menu.hidden = open;
        btn.setAttribute('aria-expanded', String(!open));
    });
    menu.querySelectorAll('.map-dropdown-item').forEach(li => {
        li.addEventListener('click', (e) => {
            e.stopPropagation();
            const value = li.dataset.value;
            setTileProvider(value);
            menu.hidden = true;
            btn.setAttribute('aria-expanded', 'false');
        });
    });
    // Click anywhere else closes the menu.
    document.addEventListener('click', () => {
        if (!menu.hidden) {
            menu.hidden = true;
            btn.setAttribute('aria-expanded', 'false');
        }
    });
    // Esc closes the menu.
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !menu.hidden) {
            menu.hidden = true;
            btn.setAttribute('aria-expanded', 'false');
        }
    });
}

function initMap() {
    deviceMap = L.map('deviceMap', { worldCopyJump: true }).setView([20, 0], 2);
    setTileProvider(DEFAULT_TILE_PROVIDER);
    L.control.scale({ imperial: true, metric: true, position: 'bottomleft' }).addTo(deviceMap);
    if (L.control && L.control.mousePosition) {
        L.control.mousePosition({
            position: 'bottomright',
            prefix: 'lat,lng:',
            separator: ' , ',
            numDigits: 5
        }).addTo(deviceMap);
    }
    setTimeout(() => deviceMap.invalidateSize(), 50);
    // Re-fit if a tab was hidden when the map first loaded.
    setTimeout(() => deviceMap.invalidateSize(), 600);

    setupMapStyleDropdown();
    const fitBtn = document.getElementById('fitBoundsBtn');
    if (fitBtn) fitBtn.addEventListener('click', fitBoundsToPoints);
    const pickBtn = document.getElementById('pickOnMapBtn');
    if (pickBtn) pickBtn.addEventListener('click', togglePickMode);
    deviceMap.on('click', (e) => {
        if (!pickMode) return;
        if (pickMarker) deviceMap.removeLayer(pickMarker);
        pickMarker = L.marker(e.latlng).addTo(deviceMap)
            .bindPopup(`Picked: ${e.latlng.lat.toFixed(5)}, ${e.latlng.lng.toFixed(5)}`).openPopup();
        togglePickMode();
    });
    // Track user pan/zoom so the next renderMap() doesn't override their view.
    deviceMap.on('dragstart zoomstart movestart', () => {
        userHasPanned = true;
    });
    updateTileStatus('ok', 'Tiles loading');
}

function renderMap(locations, geofences) {
    // Clear previous layers
    mapMarkers.forEach(m => deviceMap.removeLayer(m));
    mapMarkers = [];
    if (mapPolyline) { deviceMap.removeLayer(mapPolyline); mapPolyline = null; }
    geoCircles.forEach(c => deviceMap.removeLayer(c));
    geoCircles = [];

    if (locations && locations.length > 0) {
        const points = locations.map(l => [l.latitude, l.longitude]);

        // Polyline trail
        mapPolyline = L.polyline(points, {
            color: '#667eea',
            weight: 3,
            opacity: 0.7,
            smoothFactor: 1
        }).addTo(deviceMap);

        // Latest position marker (special, larger)
        const latest = locations[0];
        const latestMarker = L.circleMarker([latest.latitude, latest.longitude], {
            radius: 9,
            fillColor: '#667eea',
            fillOpacity: 1,
            color: '#fff',
            weight: 3
        }).addTo(deviceMap)
            .bindPopup(`<b>Latest</b><br>${latest.latitude.toFixed(5)}, ${latest.longitude.toFixed(5)}<br>${formatTime(latest.timestamp)}`);
        mapMarkers.push(latestMarker);

        // Trail-point markers (cap at ~80 so very large datasets stay light).
        // The polyline below still draws the full trail visually.
        const maxMarkers = 80;
        const step = locations.length > maxMarkers
            ? Math.ceil(locations.length / maxMarkers)
            : 1;
        for (let i = 1; i < locations.length; i += step) {
            const loc = locations[i];
            const marker = L.circleMarker([loc.latitude, loc.longitude], {
                radius: 4,
                fillColor: '#764ba2',
                fillOpacity: 0.7,
                color: 'transparent',
                weight: 0
            }).addTo(deviceMap)
                .bindPopup(`${loc.latitude.toFixed(5)}, ${loc.longitude.toFixed(5)}<br>${formatTime(loc.timestamp)}`);
            mapMarkers.push(marker);
        }

        // Only auto-fit the very first time we get data. After the user
        // pans/zooms, leave the view alone. They can hit "Fit to points"
        // to re-fit on demand. (The polyline.getBounds() already spans
        // every point on the trail, so it covers all 180+ locations
        // without us adding a marker for each one.)
        if (!userHasPanned) {
            deviceMap.fitBounds(mapPolyline.getBounds(), { padding: [40, 40] });
        }
    }

    // Draw geofence circles
    (geofences || []).forEach(g => {
        const circle = L.circle([g.latitude, g.longitude], {
            color: '#4caf50',
            fillColor: '#4caf50',
            fillOpacity: 0.08,
            weight: 2,
            dashArray: '6, 4',
            radius: g.radius
        }).addTo(deviceMap)
            .bindPopup(`<b>${g.name}</b><br>Radius: ${g.radius}m`);
        geoCircles.push(circle);
    });
}

// ─── Load All Data ────────────────────────────────────────────────────────

// safeJson: short-circuit to a typed fallback when an upstream proxy (Cloudflare
// Turnstile on Render cold-starts) returns an HTML challenge page or a non-JSON
// error. A single bad response must never tear the whole device page down.
async function safeJson(res, fallback) {
    try {
        const ct = (res.headers.get('content-type') || '').toLowerCase();
        if (!ct.includes('application/json')) {
            console.warn('[device-detail] non-JSON response', res.status, ct);
            return fallback;
        }
        return await res.json();
    } catch (e) {
        console.warn('[device-detail] JSON parse failed', res.status, e.message);
        return fallback;
    }
}

// Polyfill CanvasRenderingContext2D.roundRect for older WebViews (pre-Chrome 99)
// that ship a stripped-down 2D context. The screen-time bar chart uses it.
if (typeof CanvasRenderingContext2D !== 'undefined' && !CanvasRenderingContext2D.prototype.roundRect) {
    CanvasRenderingContext2D.prototype.roundRect = function(x, y, w, h, r) {
        if (typeof r === 'number') r = [r, r, r, r];
        else if (r.length === 1) r = [r[0], r[0], r[0], r[0]];
        else if (r.length === 2) r = [r[0], r[1], r[0], r[1]];
        this.moveTo(x + r[0], y);
        this.lineTo(x + w - r[1], y);
        this.quadraticCurveTo(x + w, y, x + w, y + r[1]);
        this.lineTo(x + w, y + h - r[2]);
        this.quadraticCurveTo(x + w, y + h, x + w - r[2], y + h);
        this.lineTo(x + r[3], y + h);
        this.quadraticCurveTo(x, y + h, x, y + h - r[3]);
        this.lineTo(x, y + r[0]);
        this.quadraticCurveTo(x, y, x + r[0], y);
        this.closePath();
        return this;
    };
}

async function loadAllData() {
    try {
        // Fetch device info first
        const devicesRes = await fetchWithAuth('/api/parent/devices');
        const devices = await safeJson(devicesRes, []);
        const found = (Array.isArray(devices) ? devices : []).find(d => d.device_id === DEVICE_ID);
        if (found) {
            deviceInfo = found;
            renderDeviceHeader(deviceInfo);
        }
        // If the response was empty/blank (e.g. Cloudflare 429 challenge), keep
        // the previously rendered header so the badge doesn't flicker between
        // ONLINE and OFFLINE on every poll while a challenge is in progress.

        // Parallel fetch all data. Time-series endpoints get the active
        // date-range filter; static ones (apps/geofences/restrictions/
        // schedule/chats) are unaffected.
        const range = activeRange;
        const [locations, activity, sms, calls, apps, screentime, webhistory, media, geofences, restrictions, schedule, social, chats] = await Promise.all([
            fetchWithAuth(`/api/parent/locations/${DEVICE_ID}?range=${range}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/activity/${DEVICE_ID}?range=${range}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/sms/${DEVICE_ID}?range=${range}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/calls/${DEVICE_ID}?range=${range}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/apps/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/screentime/${DEVICE_ID}?days=7`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/webhistory/${DEVICE_ID}?range=${range}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/media/${DEVICE_ID}?range=${range}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/geofences/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/restrictions/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/schedule/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/social/${DEVICE_ID}?range=${range}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/device/${DEVICE_ID}/chats?limit=500`).then(r => safeJson(r, [])).catch(() => [])
        ]);

        // Cache
        cachedLocations = locations;
        cachedActivity = activity;
        cachedSMS = sms;
        cachedCalls = calls;
        cachedApps = apps;
        cachedScreenTime = screentime;
        cachedWebHistory = webhistory;
        cachedMedia = media;
        cachedGeofences = geofences;
        cachedRestrictions = restrictions;
        cachedSchedule = schedule;
        cachedSocial = social;
        cachedChats = Array.isArray(chats) ? chats : (chats.messages || []);

        // Run every renderer inside its own try/catch so a single panel failure
        // (e.g. ctx.roundRect on older WebViews) cannot kill the rest of the page.
        // The first error is surfaced as a debug toast instead of the generic
        // "Failed to load device data" toast.
        const renderers = [
            ['stats',    () => renderStats()],
            ['map',      () => renderMap(locations, geofences)],
            ['activity', () => renderActivityPanel()],
            ['sms',      () => renderSMSPanel()],
            ['calls',    () => renderCallsPanel()],
            ['apps',     () => renderAppsPanel()],
            ['web',      () => renderWebPanel()],
            ['media',    () => renderMediaPanel()],
            ['social',   () => renderSocialPanel()],
            ['geofence', () => renderGeofences()],
            ['restrict', () => renderRestrictions()],
            ['schedule', () => renderSchedule()],
            ['screenti', () => renderScreenTimeCard(screentime)],
            ['battery',  () => renderBatteryCard(deviceInfo)],
        ];
        let firstError = null;
        for (const [name, fn] of renderers) {
            try { fn(); }
            catch (e) {
                console.error(`[device-detail] renderer ${name} failed:`, e);
                if (!firstError) firstError = { name, error: e };
            }
        }
        if (firstError) {
            showToast('Render warning', `${firstError.name}: ${firstError.error.message}`);
        }

        // Update tab badges + hide empty tabs
        updateTabBadges();

    } catch (err) {
        console.error('Load error:', err);
        showToast('Error', 'Failed to load device data');
    }
}

// ─── Device Header ────────────────────────────────────────────────────────

function renderDeviceHeader(dev) {
    const name = dev.device_name || dev.device_id || DEVICE_ID;
    document.getElementById('deviceName').textContent = name;
    document.title = `KidGuard - ${name}`;

    const online = isOnline(dev.last_seen);
    const badge = document.getElementById('onlineBadge');
    badge.className = `online-badge ${online ? 'online' : 'offline'}`;
    document.getElementById('onlineBadgeText').textContent = online ? 'ONLINE' : 'OFFLINE';

    document.getElementById('metaModel').textContent = dev.model || '—';
    document.getElementById('metaManufacturer').textContent = dev.manufacturer || '—';
    document.getElementById('metaAndroid').textContent = dev.android_version ? `Android ${dev.android_version}` : '—';
    document.getElementById('metaLastSeen').textContent = `Last seen: ${formatTime(dev.last_seen)}`;
}

// ─── Stats ────────────────────────────────────────────────────────────────

function renderStats() {
    const battery = deviceInfo.battery_level;
    document.getElementById('statBattery').textContent = battery != null ? `${battery}%` : '—';
    document.getElementById('statLocations').textContent = cachedLocations.length.toLocaleString();
    document.getElementById('statSMS').textContent = cachedSMS.length.toLocaleString();
    document.getElementById('statCalls').textContent = cachedCalls.length.toLocaleString();
    document.getElementById('statApps').textContent = cachedApps.length.toLocaleString();

    const todayMins = cachedScreenTime.length > 0 ? cachedScreenTime[0].total_minutes || 0 : 0;
    document.getElementById('statScreenTime').textContent = todayMins.toLocaleString();

    document.getElementById('locationCount').textContent = `${cachedLocations.length} points`;
}

// ─── Screen Time Card ─────────────────────────────────────────────────────

function renderScreenTimeCard(screentime) {
    const today = screentime.length > 0 ? screentime[0].total_minutes || 0 : 0;
    document.getElementById('screenTimeValue').textContent = today.toLocaleString();
    document.getElementById('screenTimeBadge').textContent = `${today} min`;

    const canvas = document.getElementById('screenTimeChart');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const w = canvas.offsetWidth || 300;
    canvas.width = w;
    canvas.height = 90;

    const days = 7;
    const data = [];
    const labels = [];
    const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

    for (let i = days - 1; i >= 0; i--) {
        const d = new Date();
        d.setDate(d.getDate() - i);
        const dateStr = d.toISOString().slice(0, 10);
        const entry = screentime.find(s => s.date === dateStr);
        data.push(entry ? (entry.total_minutes || 0) : 0);
        labels.push(dayNames[d.getDay()]);
    }

    const maxVal = Math.max(...data, 1);
    const barW = Math.floor(w / days) - 6;
    const padB = 4;
    const chartH = canvas.height - padB;

    ctx.clearRect(0, 0, w, canvas.height);

    data.forEach((val, i) => {
        const barH = Math.max(4, Math.round((val / maxVal) * chartH));
        const x = i * (barW + 6) + 3;
        const y = chartH - barH;

        // Bar gradient
        const grad = ctx.createLinearGradient(0, y, 0, chartH);
        grad.addColorStop(0, i === days - 1 ? '#a78bfa' : '#667eea');
        grad.addColorStop(1, i === days - 1 ? '#7c3aed44' : '#764ba244');
        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.roundRect(x, y, barW, barH, 3);
        ctx.fill();
    });

    // Labels
    const labelsEl = document.getElementById('screenTimeLabels');
    if (labelsEl) labelsEl.innerHTML = labels.map(l => `<span>${l}</span>`).join('');
}

// ─── Battery & Status Card ────────────────────────────────────────────────

function renderBatteryCard(dev) {
    const level = dev.battery_level != null ? dev.battery_level : null;
    const charging = dev.is_charging;

    // Badge
    const badge = document.getElementById('batteryBadge');
    if (badge) badge.textContent = level != null ? `${level}%` : '—';

    // Progress bar
    const bar = document.getElementById('batteryBar');
    if (bar && level != null) {
        bar.style.width = `${level}%`;
        if (level < 20) {
            bar.style.background = 'linear-gradient(90deg, #ef4444, #f97316)';
        } else if (level < 50) {
            bar.style.background = 'linear-gradient(90deg, #f59e0b, #fbbf24)';
        } else {
            bar.style.background = 'linear-gradient(90deg, #22c55e, #4ade80)';
        }
    }

    const pct = document.getElementById('batteryPct');
    if (pct) pct.textContent = level != null ? `${level}%` : '—';

    const statusEl = document.getElementById('batteryStatus');
    if (statusEl) statusEl.textContent = level != null ? (charging ? '⚡ Charging' : '🔋 On Battery') : '—';

    const lastSeenEl = document.getElementById('batteryLastSeen');
    if (lastSeenEl) lastSeenEl.textContent = dev.last_seen ? formatTime(dev.last_seen) : '—';

    const modelEl = document.getElementById('batteryModel');
    if (modelEl) modelEl.textContent = [dev.manufacturer, dev.model].filter(Boolean).join(' ') || '—';

    const androidEl = document.getElementById('batteryAndroid');
    if (androidEl) androidEl.textContent = dev.android_version ? `Android ${dev.android_version}` : '—';
}


// ─── Tab Switching ────────────────────────────────────────────────────────

function setupTabs() {
    document.getElementById('dataTabBar').addEventListener('click', (e) => {
        if (!e.target.classList.contains('tab')) return;
        const tab = e.target.dataset.dtab;
        if (!tab) return;

        // Update active tab
        document.querySelectorAll('#dataTabBar .tab').forEach(t => t.classList.remove('active'));
        e.target.classList.add('active');

        // Show panel
        document.querySelectorAll('.tab-content-panel').forEach(p => p.classList.remove('active'));
        const panel = document.getElementById(`panel-${tab}`);
        if (panel) panel.classList.add('active');
    });
}

// Map tab key → cached array. AnonChat uses cachedChats.
const TAB_COUNT_SOURCES = {
    activity: () => cachedActivity.length,
    sms:      () => cachedSMS.length,
    calls:    () => cachedCalls.length,
    apps:     () => cachedApps.length,
    web:      () => cachedWebHistory.length,
    social:   () => cachedSocial.length,
    anonchat: () => cachedChats.length,
    media:    () => cachedMedia.length,
};

// ─── Date-range chips ──────────────────────────────────────────────────────
//
// A row of chips (Today / 7d / 30d / All) above the tabs controls the
// time-series sections. The choice is persisted in localStorage so the
// parent's preference sticks across reloads. Picking a chip triggers a
// re-fetch of just the time-series endpoints (not apps/geofences/etc).

const RANGE_LABELS = { today: 'Today', '7d': '7d', '30d': '30d', all: 'All' };

function applyRangeChipsSelection() {
    document.querySelectorAll('.range-chip').forEach(chip => {
        const r = chip.dataset.range;
        chip.classList.toggle('active', r === activeRange);
    });
}

async function setRange(newRange) {
    if (!RANGE_PRESETS.includes(newRange) || newRange === activeRange) return;
    activeRange = newRange;
    localStorage.setItem(RANGE_KEY, newRange);
    applyRangeChipsSelection();
    await loadAllData();
}

function setupRangeChips() {
    applyRangeChipsSelection();
    document.querySelectorAll('.range-chip').forEach(chip => {
        chip.addEventListener('click', () => setRange(chip.dataset.range));
    });
}

// Show count on each tab. Tabs whose data is missing are hidden entirely
// (per the user request "If not show the menus then add numbers to the newly
// arrived as numbers"). Newly arrived tabs get a count pill.
function updateTabBadges() {
    let firstAvailableTab = null;

    Object.entries(TAB_COUNT_SOURCES).forEach(([key, getCount]) => {
        const btn = document.querySelector(`#dataTabBar .tab[data-dtab="${key}"]`);
        if (!btn) return;
        const badge = btn.querySelector('.tab-badge');
        const count = getCount();

        if (count === 0) {
            // Hide the tab entirely when there's no data.
            btn.classList.add('hidden');
            if (badge) {
                badge.textContent = '0';
                badge.classList.remove('loading');
                badge.classList.add('zero');
            }
        } else {
            btn.classList.remove('hidden');
            if (badge) {
                badge.textContent = count > 999 ? '999+' : String(count);
                badge.classList.remove('zero', 'loading');
            }
            if (!firstAvailableTab) firstAvailableTab = btn;
        }
    });

    // If the currently-active tab got hidden, fall forward to the first
    // available tab so the user isn't staring at an empty panel.
    const active = document.querySelector('#dataTabBar .tab.active');
    if (active && active.classList.contains('hidden') && firstAvailableTab) {
        document.querySelectorAll('#dataTabBar .tab').forEach(t => t.classList.remove('active'));
        firstAvailableTab.classList.add('active');
        document.querySelectorAll('.tab-content-panel').forEach(p => p.classList.remove('active'));
        const panel = document.getElementById(`panel-${firstAvailableTab.dataset.dtab}`);
        if (panel) panel.classList.add('active');
    }
}

// ─── Activity Panel ───────────────────────────────────────────────────────

function renderActivityPanel() {
    const container = document.getElementById('panel-activity');
    if (cachedActivity.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">📋</div>No activity recorded yet</div>';
        return;
    }
    container.innerHTML = cachedActivity.map(a => `
        <div class="activity-item">
            <strong>▶ ${escHtml(a.app_name || a.activity_type || 'Activity')}</strong>
            ${a.package_name ? `<span class="device-tag">${escHtml(a.package_name)}</span>` : ''}
            <span class="time">${formatTime(a.timestamp)}</span>
        </div>
    `).join('');
}

// ─── SMS Panel ────────────────────────────────────────────────────────────

function renderSMSPanel() {
    const container = document.getElementById('panel-sms');
    if (cachedSMS.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">💬</div>No SMS messages found</div>';
        return;
    }
    container.innerHTML = `
        <table class="data-table">
            <thead><tr>
                <th>Type</th><th>Number</th><th>Message</th><th>Date</th>
            </tr></thead>
            <tbody>
                ${cachedSMS.map(s => {
                    const isSent = s.type === 2;
                    return `<tr>
                        <td><span class="type-badge ${isSent ? 'sent' : 'received'}">${isSent ? '↑ Sent' : '↓ Received'}</span></td>
                        <td>${escHtml(s.address || s.number || '—')}</td>
                        <td><span class="sms-body">${escHtml((s.body || '').substring(0, 80))}</span></td>
                        <td style="white-space:nowrap;">${formatTime(s.date)}</td>
                    </tr>`;
                }).join('')}
            </tbody>
        </table>`;
}

// ─── Calls Panel ──────────────────────────────────────────────────────────

function renderCallsPanel() {
    const container = document.getElementById('panel-calls');
    if (cachedCalls.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">📞</div>No call log found</div>';
        return;
    }
    container.innerHTML = `
        <table class="data-table">
            <thead><tr>
                <th>Type</th><th>Number</th><th>Name</th><th>Duration</th><th>Date</th>
            </tr></thead>
            <tbody>
                ${cachedCalls.map(c => {
                    const typeMap = { 1: ['incoming', '↓ Incoming'], 2: ['outgoing', '↑ Outgoing'], 3: ['missed', '✕ Missed'] };
                    const [cls, label] = typeMap[c.type] || ['incoming', 'Call'];
                    return `<tr>
                        <td><span class="type-badge ${cls}">${label}</span></td>
                        <td>${escHtml(c.number || '—')}</td>
                        <td>${escHtml(c.name || '—')}</td>
                        <td>${formatDuration(c.duration)}</td>
                        <td style="white-space:nowrap;">${formatTime(c.date)}</td>
                    </tr>`;
                }).join('')}
            </tbody>
        </table>`;
}

// ─── Apps Panel ───────────────────────────────────────────────────────────

function renderAppsPanel() {
    const container = document.getElementById('panel-apps');
    if (cachedApps.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">📦</div>No apps found</div>';
        return;
    }
    container.innerHTML = `<div class="apps-grid">${cachedApps.map(a => `
        <div class="app-card">
            <div class="app-card-name">${escHtml(a.app_name || a.package_name)}</div>
            <div class="app-card-pkg">${escHtml(a.package_name || '')}</div>
        </div>
    `).join('')}</div>`;
}

// ─── Web History Panel ────────────────────────────────────────────────────

function renderWebPanel() {
    const container = document.getElementById('panel-web');
    if (cachedWebHistory.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">🌐</div>No web history found</div>';
        return;
    }
    container.innerHTML = `
        <table class="data-table">
            <thead><tr>
                <th>URL</th><th>Title</th><th>Browser</th><th>Visits</th><th>Date</th>
            </tr></thead>
            <tbody>
                ${cachedWebHistory.map(w => `
                    <tr>
                        <td><a class="url-link" href="${escAttr(w.url)}" target="_blank" rel="noopener">${escHtml(truncateUrl(w.url))}</a></td>
                        <td>${escHtml(w.title || '—')}</td>
                        <td>${escHtml(w.browser || '—')}</td>
                        <td>${w.visit_count || w.visits || '—'}</td>
                        <td style="white-space:nowrap;">${formatTime(w.timestamp || w.date || w.last_visited)}</td>
                    </tr>
                `).join('')}
            </tbody>
        </table>`;
}

// ─── Media Panel ──────────────────────────────────────────────────────────

function renderMediaPanel() {
    const container = document.getElementById('panel-media');
    if (cachedMedia.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">🖼️</div>No media files found</div>';
        return;
    }
    container.innerHTML = `<div class="media-grid">${cachedMedia.map(m => {
        const thumbUrl = `/api/files/${m.id || m.media_id}`;
        const isImage = (m.mime_type || m.type || '').startsWith('image');
        return `
            <div class="media-thumb" onclick="openLightbox('${escAttr(thumbUrl)}')">
                <img src="${escAttr(thumbUrl)}" alt="${escAttr(m.filename || 'media')}" loading="lazy"
                     onerror="this.style.display='none'">
                <span class="media-type-icon">${isImage ? '🖼️' : '📄'}</span>
            </div>`;
    }).join('')}</div>`;
}

// ─── Social Panel ─────────────────────────────────────────────────────────

function renderSocialPanel() {
    const container = document.getElementById('panel-social');
    if (!cachedSocial || cachedSocial.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">💬</div>No social media activity captured yet.<br><small>Social notifications will appear here when the child receives WhatsApp, Instagram, Telegram messages, etc.</small></div>';
        return;
    }

    const socialIcons = {
        'WhatsApp': '💬', 'WhatsApp Business': '💬',
        'Instagram': '📸', 'Facebook': '👤', 'Messenger': '💭',
        'Snapchat': '👻', 'Telegram': '✈️', 'YouTube': '▶️',
        'TikTok': '🎵', 'X (Twitter)': '🐦', 'Discord': '🎮',
        'Reddit': '🤖', 'Pinterest': '📌', 'LinkedIn': '💼',
        'Viber': '📞', 'LINE': '💚', 'Skype': '☁️'
    };

    const typeBadge = {
        'message': '<span class="social-badge msg">Message</span>',
        'dm': '<span class="social-badge dm">DM</span>',
        'like': '<span class="social-badge like">Like</span>',
        'comment': '<span class="social-badge comment">Comment</span>',
        'snap': '<span class="social-badge snap">Snap</span>',
        'video': '<span class="social-badge video">Video</span>',
        'notification': '<span class="social-badge notif">Notif</span>'
    };

    container.innerHTML = cachedSocial.map(n => {
        const icon = socialIcons[n.app_name] || '📱';
        const badge = typeBadge[n.message_type] || typeBadge['notification'];
        return `
            <div class="social-item">
                <div class="social-icon">${icon}</div>
                <div class="social-body">
                    <div class="social-header">
                        <strong>${escHtml(n.app_name)}</strong>
                        ${badge}
                        <span class="time">${formatTime(n.timestamp)}</span>
                    </div>
                    <div class="social-sender">${escHtml(n.sender || '')}</div>
                    <div class="social-content">${escHtml(n.content || '')}</div>
                </div>
            </div>`;
    }).join('');
}

// ─── AnonChat Panel ───────────────────────────────────────────────────────

function renderAnonChatPanel(filterText = '') {
    const container = document.getElementById('chatMessagesList');
    if (!container) return;

    let list = cachedChats || [];
    if (filterText) {
        const q = filterText.toLowerCase();
        list = list.filter(m =>
            (m.content && m.content.toLowerCase().includes(q)) ||
            (m.sender_name && m.sender_name.toLowerCase().includes(q)) ||
            (m.recipient_name && m.recipient_name.toLowerCase().includes(q))
        );
    }

    if (list.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">💬</div>No AnonChat messages found</div>';
        return;
    }

    container.innerHTML = `
        <table class="data-table">
            <thead><tr>
                <th>Sender</th>
                <th>Recipient</th>
                <th>Message Content</th>
                <th>Media</th>
                <th>Time (UTC)</th>
            </tr></thead>
            <tbody>
                ${list.map(m => {
                    const isImg = m.type === 'image' || !!m.image_url;
                    return `<tr>
                        <td><strong style="color:#667eea;">${escHtml(m.sender_name || 'Anonymous')}</strong></td>
                        <td><strong style="color:#a78bfa;">${escHtml(m.recipient_name || 'Anonymous')}</strong></td>
                        <td><span class="sms-body">${escHtml(m.content || (isImg ? '📷 Photo' : ''))}</span></td>
                        <td>${m.image_url ? `<a href="${escAttr(m.image_url)}" target="_blank" style="color:#38bdf8; text-decoration:underline;">View Photo</a>` : '—'}</td>
                        <td style="white-space:nowrap;">${formatTime(m.timestamp)}</td>
                    </tr>`;
                }).join('')}
            </tbody>
        </table>`;
}

function filterChatMessages() {
    const input = document.getElementById('chatSearchInput');
    const q = input ? input.value.trim() : '';
    renderAnonChatPanel(q);
}

function exportChats(format) {
    const url = `/api/admin/chats/export?format=${format}`;
    window.open(url, '_blank');
}

async function confirmPurgeChats() {
    if (!confirm('⚠️ Are you sure you want to PERMANENTLY ERASE all AnonChat history from the database? This cannot be undone.')) {
        return;
    }
    try {
        const res = await fetchWithAuth('/api/admin/chats/purge', {
            method: 'POST',
            body: JSON.stringify({})
        });
        const data = await res.json();
        if (res.ok) {
            showToast('Success', data.message || 'Chat history erased successfully');
            cachedChats = [];
            renderAnonChatPanel();
        } else {
            showToast('Error', data.error || 'Failed to erase chat history');
        }
    } catch (e) {
        showToast('Error', 'Failed to connect to server');
    }
}

// ─── Geofences ────────────────────────────────────────────────────────────

function renderGeofences() {
    const container = document.getElementById('geofenceList');
    if (cachedGeofences.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">📍</div>No geofences created</div>';
        return;
    }
    container.innerHTML = cachedGeofences.map(g => `
        <div class="geofence-item">
            <span class="geo-icon">📍</span>
            <div class="geo-info">
                <div class="geo-name">${escHtml(g.name)}</div>
                <div class="geo-coords">${g.latitude.toFixed(4)}, ${g.longitude.toFixed(4)} &middot; ${g.radius}m radius</div>
            </div>
            <button class="btn-delete" onclick="deleteGeofence('${g.id}')" title="Delete">&times;</button>
        </div>
    `).join('');
}

function showGeofenceModal() {
    document.getElementById('geofenceModal').classList.remove('hidden');
    // Pre-fill with map center
    if (deviceMap && deviceMap.getCenter) {
        const center = deviceMap.getCenter();
        document.getElementById('geoLat').value = center.lat.toFixed(6);
        document.getElementById('geoLon').value = center.lng.toFixed(6);
    }
}

async function saveGeofence() {
    const data = {
        name: document.getElementById('geoName').value || 'Safe Zone',
        latitude: parseFloat(document.getElementById('geoLat').value),
        longitude: parseFloat(document.getElementById('geoLon').value),
        radius: parseInt(document.getElementById('geoRadius').value) || 500
    };

    if (isNaN(data.latitude) || isNaN(data.longitude)) {
        showToast('⚠️ Error', 'Please enter valid coordinates');
        return;
    }

    try {
        const res = await fetchWithAuth(`/api/parent/geofences/${DEVICE_ID}`, {
            method: 'POST',
            body: JSON.stringify(data)
        });
        if (res.ok) {
            closeModal();
            showToast('✅ Added', `Geofence "${data.name}" created`);
            loadAllData();
        } else {
            showToast('⚠️ Error', 'Failed to create geofence');
        }
    } catch (e) {
        showToast('⚠️ Error', 'Network error');
    }
}

async function deleteGeofence(id) {
    if (!confirm('Delete this geofence?')) return;
    try {
        await fetchWithAuth(`/api/parent/geofences/delete/${id}`, { method: 'DELETE' });
        showToast('🗑️ Deleted', 'Geofence removed');
        loadAllData();
    } catch (e) {
        showToast('⚠️ Error', 'Failed to delete geofence');
    }
}

// ─── Remote Commands ──────────────────────────────────────────────────────

async function sendCommand(command) {
    if (command === 'wipe' && !confirm('⚠️ This will WIPE the device. Are you absolutely sure?')) return;

    try {
        const res = await fetchWithAuth(`/api/parent/commands/${DEVICE_ID}`, {
            method: 'POST',
            body: JSON.stringify({ command, params: { duration: 30 } })
        });
        if (res.ok) {
            const data = await res.json();
            const commandId = data.command_id;
            showToast('📤 Command Sent', `${command} sent to device`);
            // Open the live result modal
            if (typeof openCmdModal === 'function' && commandId) {
                openCmdModal(command, commandId);
            }
        } else {
            showToast('⚠️ Error', 'Failed to send command');
        }
    } catch (e) {
        showToast('⚠️ Error', 'Network error');
    }
}

// ─── App Restrictions ─────────────────────────────────────────────────────

function renderRestrictions() {
    const container = document.getElementById('restrictionsList');
    if (cachedRestrictions.length === 0) {
        container.innerHTML = '<div class="empty-state"><div class="empty-icon">🔒</div>No app restrictions set</div>';
        return;
    }
    container.innerHTML = cachedRestrictions.map(r => {
        const isBlocked = r.is_blocked;
        return `
            <div class="restriction-item">
                <div class="restriction-info">
                    <div class="restriction-name">${escHtml(r.app_name || r.package_name)}</div>
                    <div class="restriction-detail">
                        ${r.max_minutes_per_day ? `Limit: ${r.max_minutes_per_day} min/day` : ''}
                        ${r.block_start_time ? ` · ${r.block_start_time}–${r.block_end_time || ''}` : ''}
                    </div>
                </div>
                <span class="restriction-badge ${isBlocked ? 'blocked' : 'limited'}">${isBlocked ? 'Blocked' : 'Limited'}</span>
                <button class="btn-delete" onclick="deleteRestriction('${r.id}')" title="Remove">&times;</button>
            </div>`;
    }).join('');
}

async function addRestriction() {
    const pkg = document.getElementById('restrictPkg').value.trim();
    if (!pkg) {
        showToast('⚠️ Error', 'Please enter a package name');
        return;
    }

    const startHour = parseInt(document.getElementById('restrictStart').value) || 0;
    const endHour = parseInt(document.getElementById('restrictEnd').value) || 23;

    const data = {
        package_name: pkg,
        is_blocked: true,
        max_minutes_per_day: parseInt(document.getElementById('restrictLimit').value) || 0,
        block_start_time: `${String(startHour).padStart(2, '0')}:00`,
        block_end_time: `${String(endHour).padStart(2, '0')}:00`
    };

    try {
        const res = await fetchWithAuth(`/api/parent/restrictions/${DEVICE_ID}`, {
            method: 'POST',
            body: JSON.stringify(data)
        });
        if (res.ok) {
            document.getElementById('restrictPkg').value = '';
            document.getElementById('restrictLimit').value = '';
            showToast('✅ Added', `Restriction on ${pkg}`);
            loadAllData();
        } else {
            showToast('⚠️ Error', 'Failed to add restriction');
        }
    } catch (e) {
        showToast('⚠️ Error', 'Network error');
    }
}

async function deleteRestriction(id) {
    if (!confirm('Remove this restriction?')) return;
    try {
        await fetchWithAuth(`/api/parent/restrictions/delete/${id}`, { method: 'DELETE' });
        showToast('🗑️ Removed', 'Restriction removed');
        loadAllData();
    } catch (e) {
        showToast('⚠️ Error', 'Failed to remove restriction');
    }
}

// ─── Schedule Rules ───────────────────────────────────────────────────────

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function renderSchedule() {
    const grid = document.getElementById('scheduleGrid');

    grid.innerHTML = DAY_NAMES.map((dayName, i) => {
        const rule = cachedSchedule.find(s => s.day_of_week === i);
        if (rule) {
            return `
                <div class="schedule-day has-rule">
                    <div class="schedule-day-name">${dayName}</div>
                    <div class="schedule-day-time">${rule.start_time || '—'}–${rule.end_time || ''}</div>
                    <div class="schedule-day-rule">${rule.is_block_time ? 'Blocked' : 'Allowed'}</div>
                    <button class="btn-delete" onclick="deleteScheduleRule('${rule.id}')" title="Remove" style="margin-top:6px;">×</button>
                </div>`;
        }
        return `
            <div class="schedule-day">
                <div class="schedule-day-name">${dayName}</div>
                <div class="schedule-day-time">—</div>
                <div class="schedule-day-rule">No rule</div>
            </div>`;
    }).join('');
}

async function deleteScheduleRule(id) {
    if (!confirm('Remove this schedule rule?')) return;
    try {
        await fetchWithAuth(`/api/parent/schedule/delete/${id}`, { method: 'DELETE' });
        showToast('🗑️ Removed', 'Schedule rule removed');
        loadAllData();
    } catch (e) {
        showToast('⚠️ Error', 'Failed to remove schedule rule');
    }
}

async function addScheduleRule() {
    const startHour = parseInt(document.getElementById('schedStart').value) || 0;
    const endHour = parseInt(document.getElementById('schedEnd').value) || 23;

    const data = {
        day_of_week: parseInt(document.getElementById('schedDay').value),
        start_time: `${String(startHour).padStart(2, '0')}:00`,
        end_time: `${String(endHour).padStart(2, '0')}:00`,
        is_block_time: true
    };

    try {
        const res = await fetchWithAuth(`/api/parent/schedule/${DEVICE_ID}`, {
            method: 'POST',
            body: JSON.stringify(data)
        });
        if (res.ok) {
            showToast('✅ Added', `Schedule rule for ${DAY_NAMES[data.day_of_week]}`);
            loadAllData();
        } else {
            showToast('⚠️ Error', 'Failed to add schedule rule');
        }
    } catch (e) {
        showToast('⚠️ Error', 'Network error');
    }
}

// ─── Lightbox ─────────────────────────────────────────────────────────────

function openLightbox(src) {
    document.getElementById('lightboxImg').src = src;
    document.getElementById('lightbox').classList.remove('hidden');
}

function closeLightbox() {
    document.getElementById('lightbox').classList.add('hidden');
    document.getElementById('lightboxImg').src = '';
}

// ─── Modal ────────────────────────────────────────────────────────────────

function closeModal() {
    document.querySelectorAll('.modal').forEach(m => m.classList.add('hidden'));
}

// Close modal on Escape
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        closeModal();
        closeLightbox();
    }
});

// ─── Toast Notifications ──────────────────────────────────────────────────

function showToast(title, message) {
    const toast = document.createElement('div');
    toast.className = 'toast-notification';
    toast.innerHTML = `<strong>${escHtml(title)}</strong><br>${escHtml(message)}`;
    document.body.appendChild(toast);
    setTimeout(() => {
        toast.style.animation = 'slideIn 0.3s ease reverse';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// ─── Auto-Refresh ─────────────────────────────────────────────────────────

function startAutoRefresh() {
    refreshTimer = setInterval(() => {
        loadAllData();
    }, 30000);
}

// ─── Helpers ──────────────────────────────────────────────────────────────

function formatTime(ts) {
    if (!ts) return 'N/A';
    const d = new Date(ts);
    const now = new Date();
    const diff = now - d;
    if (diff < 60000) return 'Just now';
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDuration(seconds) {
    if (!seconds && seconds !== 0) return '—';
    seconds = parseInt(seconds);
    if (seconds < 60) return `${seconds}s`;
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    if (mins < 60) return `${mins}m ${secs}s`;
    const hrs = Math.floor(mins / 60);
    return `${hrs}h ${mins % 60}m`;
}

function isOnline(lastSeen) {
    if (!lastSeen) return false;
    return (Date.now() - new Date(lastSeen).getTime()) < 600000; // 10 min
}

function padHour(h) {
    return String(h).padStart(2, '0') + ':00';
}

function truncateUrl(url) {
    if (!url) return '—';
    try {
        const u = new URL(url);
        const path = u.pathname.length > 30 ? u.pathname.substring(0, 30) + '…' : u.pathname;
        return u.hostname + path;
    } catch {
        return url.length > 50 ? url.substring(0, 50) + '…' : url;
    }
}

function escHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = String(str);
    return div.innerHTML;
}

function escAttr(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ─── Storage capacity modal ───────────────────────────────────────────────
//
// Lets the parent see how much of the database + Firebase Storage the
// device is using, and delete records by date range or "older than 30 days"
// to free up space. Backed by two endpoints on the server:
//   GET  /api/parent/storage/<id>
//   POST /api/parent/storage/<id>/delete
//
// UX notes:
//  - One summary card with total / db / firebase bytes (human-readable).
//  - A bar per section (Activity, Locations, …) sorted biggest first.
//  - A delete form with from/to date inputs, per-section checkboxes,
//    a quick "older than 30 days" action, and a confirm sub-modal where
//    the parent has to type the device name to proceed.

const STORAGE_DELETEABLE = [
    // Only sections that support date-range delete. Order = display order.
    { key: 'activity',  label: 'Activity' },
    { key: 'locations', label: 'Locations' },
    { key: 'sms',       label: 'SMS' },
    { key: 'calls',     label: 'Calls' },
    { key: 'web',       label: 'Web history' },
    { key: 'media',     label: 'Media (Firebase)' },
    { key: 'social',    label: 'Social' },
    { key: 'chats',     label: 'AnonChat' },
];
let storageStats = null;   // last fetched GET response
let storagePendingSections = [];   // what the parent is about to delete

function humanBytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return `${n} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let i = -1;
    let v = n;
    do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
    return `${v.toFixed(v < 10 ? 2 : v < 100 ? 1 : 0)} ${units[i]}`;
}

function dateInputValue(ms) {
    if (!ms) return '';
    const d = new Date(ms);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}

function dateInputToMsStart(value) {
    // YYYY-MM-DD -> ms epoch at local midnight (start of day)
    if (!value) return null;
    const [y, m, d] = value.split('-').map(Number);
    return new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
}
function dateInputToMsEnd(value) {
    if (!value) return null;
    const [y, m, d] = value.split('-').map(Number);
    return new Date(y, m - 1, d, 23, 59, 59, 999).getTime();
}

function setupStorage() {
    const openBtn = document.getElementById('openStorageBtn');
    if (!openBtn) return;
    openBtn.addEventListener('click', openStorageModal);

    const closeBtn = document.getElementById('storageModalClose');
    if (closeBtn) closeBtn.addEventListener('click', closeStorageModal);

    const overlay = document.getElementById('storageModal');
    if (overlay) {
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) closeStorageModal();
        });
    }

    const confirmClose = document.getElementById('storageConfirmClose');
    if (confirmClose) confirmClose.addEventListener('click', closeStorageConfirm);

    const confirmCancel = document.getElementById('storageConfirmCancel');
    if (confirmCancel) confirmCancel.addEventListener('click', closeStorageConfirm);

    const confirmInput = document.getElementById('storageConfirmInput');
    if (confirmInput) {
        confirmInput.addEventListener('input', () => {
            const target = document.getElementById('storageConfirmApply');
            const expected = (document.getElementById('storageConfirmName')?.textContent || '').trim();
            target.disabled = confirmInput.value.trim() !== expected;
        });
    }

    const confirmApply = document.getElementById('storageConfirmApply');
    if (confirmApply) confirmApply.addEventListener('click', applyStorageDelete);

    const older30 = document.getElementById('storageOlder30Btn');
    if (older30) older30.addEventListener('click', applyStorageOlder30);

    const clearAll = document.getElementById('storageClearAllBtn');
    if (clearAll) clearAll.addEventListener('click', () => {
        document.querySelectorAll('#storageSectionsGrid input[type=checkbox]').forEach(cb => {
            cb.checked = false;
        });
    });

    const form = document.getElementById('storageDeleteForm');
    if (form) {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            requestStorageDelete();
        });
    }

    // Escape closes either modal.
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            const confirm = document.getElementById('storageConfirm');
            if (confirm && !confirm.hidden) closeStorageConfirm();
            else {
                const m = document.getElementById('storageModal');
                if (m && !m.hidden) closeStorageModal();
            }
        }
    });
}

async function openStorageModal() {
    const modal = document.getElementById('storageModal');
    modal.hidden = false;
    modal.style.display = '';
    // Default: the last 30 days, no lower bound, so the parent can
    // quickly "Delete everything older than 30 days" without picking dates.
    const fromEl = document.getElementById('storageFrom');
    const toEl = document.getElementById('storageTo');
    const today = new Date();
    const thirtyAgo = new Date();
    thirtyAgo.setDate(today.getDate() - 30);
    if (fromEl && !fromEl.value) fromEl.value = dateInputValue(thirtyAgo.getTime());
    if (toEl && !toEl.value) toEl.value = dateInputValue(today.getTime());
    await refreshStorageStats();
}

function closeStorageModal() {
    const modal = document.getElementById('storageModal');
    if (modal) { modal.hidden = true; modal.style.display = 'none'; }
}

async function refreshStorageStats() {
    const list = document.getElementById('storageSectionList');
    if (list) list.innerHTML = '<div class="storage-loading">Loading…</div>';

    try {
        const res = await fetchWithAuth(`/api/parent/storage/${DEVICE_ID}`);
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.error || `HTTP ${res.status}`);
        }
        storageStats = await res.json();
        renderStorageStats(storageStats);
    } catch (e) {
        console.error('storage stats failed', e);
        if (list) list.innerHTML = `<div class="storage-error">Could not load storage: ${escHtml(e.message)}</div>`;
        document.getElementById('storageTotal').textContent = '—';
        document.getElementById('storageDb').textContent = '—';
        document.getElementById('storageFirebase').textContent = '—';
    }
}

function renderStorageStats(stats) {
    document.getElementById('storageTotal').textContent = humanBytes(stats.total_bytes);
    document.getElementById('storageDb').textContent = humanBytes(stats.db_bytes);
    document.getElementById('storageFirebase').textContent = humanBytes(stats.firebase_bytes);

    const rangeRow = document.getElementById('storageRangeRow');
    if (rangeRow) rangeRow.hidden = true;

    // Section list with bars
    const list = document.getElementById('storageSectionList');
    const totalForBar = Math.max(1, stats.total_bytes);
    list.innerHTML = '';
    const visible = stats.sections.filter(s => s.count > 0);
    if (visible.length === 0) {
        list.innerHTML = '<div class="storage-empty">No records yet — nothing to show.</div>';
    } else {
        for (const s of visible) {
            const pct = Math.max(2, Math.round((s.bytes / totalForBar) * 100));
            const row = document.createElement('div');
            row.className = 'storage-section-row';
            row.innerHTML = `
                <div class="storage-section-label">
                    <span>${escHtml(s.label)}</span>
                    <span class="storage-section-count">${s.count.toLocaleString()}</span>
                </div>
                <div class="storage-bar">
                    <div class="storage-bar-fill" style="width:${pct}%;"></div>
                </div>
                <div class="storage-section-bytes">${humanBytes(s.bytes)}</div>
            `;
            list.appendChild(row);
        }
    }

    // Delete form: rebuild section checkboxes
    const grid = document.getElementById('storageSectionsGrid');
    grid.innerHTML = '';
    for (const sec of STORAGE_DELETEABLE) {
        const hasRows = (stats.sections.find(s => s.key === sec.key)?.count || 0) > 0;
        const wrap = document.createElement('label');
        wrap.className = 'storage-section-toggle';
        wrap.innerHTML = `
            <input type="checkbox" value="${escAttr(sec.key)}" ${hasRows ? '' : 'disabled'}>
            <span>${escHtml(sec.label)}</span>
        `;
        grid.appendChild(wrap);
    }
}

function getSelectedSections() {
    const checked = Array.from(document.querySelectorAll('#storageSectionsGrid input[type=checkbox]:checked'))
        .map(cb => cb.value);
    return checked;
}

async function requestStorageDelete() {
    const fromEl = document.getElementById('storageFrom');
    const toEl = document.getElementById('storageTo');
    const sections = getSelectedSections();
    if (sections.length === 0) {
        showToast('Pick a section', 'Tick at least one section to delete.');
        return;
    }
    const from_ms = dateInputToMsStart(fromEl.value);
    const to_ms = dateInputToMsEnd(toEl.value);
    if (from_ms !== null && to_ms !== null && from_ms > to_ms) {
        showToast('Bad range', '"From" must be on or before "To".');
        return;
    }
    storagePendingSections = sections;

    // First: ask the server how many rows this would affect (dry-run),
    // so the confirm modal can show a meaningful summary. Then show the
    // confirm modal where the parent must type the device name.
    try {
        const res = await fetchWithAuth(`/api/parent/storage/${DEVICE_ID}/delete`, {
            method: 'POST',
            body: JSON.stringify({
                sections, from_ms, to_ms, dry_run: true,
            }),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.error || `HTTP ${res.status}`);
        }
        const dry = await res.json();
        const totalWould = Object.values(dry.would_delete || {}).reduce((a, b) => a + b, 0);
        if (totalWould === 0) {
            showToast('Nothing to delete', 'No records match this date range + section combination.');
            return;
        }
        openStorageConfirm(dry.would_delete, totalWould);
    } catch (e) {
        console.error('storage dry-run failed', e);
        showToast('Error', e.message || 'Could not start delete');
    }
}

function openStorageConfirm(wouldDelete, totalWould) {
    const devName = (document.getElementById('deviceName')?.textContent || '').trim() || 'this device';
    const list = STORAGE_DELETEABLE
        .filter(s => (wouldDelete[s.key] || 0) > 0)
        .map(s => `${escHtml(s.label)}: <strong>${wouldDelete[s.key].toLocaleString()}</strong>`)
        .join('<br>');
    document.getElementById('storageConfirmText').innerHTML =
        `You are about to permanently delete <strong>${totalWould.toLocaleString()}</strong> record(s) from <strong>${escHtml(devName)}</strong>:<br><br>${list || '<em>None</em>'}`;
    document.getElementById('storageConfirmName').textContent = devName;
    const input = document.getElementById('storageConfirmInput');
    input.value = '';
    const apply = document.getElementById('storageConfirmApply');
    apply.disabled = true;
    document.getElementById('storageConfirm').hidden = false;
    document.getElementById('storageConfirm').style.display = '';
    setTimeout(() => input.focus(), 50);
}

function closeStorageConfirm() {
    const c = document.getElementById('storageConfirm');
    if (c) { c.hidden = true; c.style.display = 'none'; }
}

async function applyStorageDelete() {
    const apply = document.getElementById('storageConfirmApply');
    const cancel = document.getElementById('storageConfirmCancel');
    apply.disabled = true;
    cancel.disabled = true;
    apply.textContent = 'Deleting…';

    const fromEl = document.getElementById('storageFrom');
    const toEl = document.getElementById('storageTo');
    const from_ms = dateInputToMsStart(fromEl.value);
    const to_ms = dateInputToMsEnd(toEl.value);
    const sections = storagePendingSections;

    try {
        const res = await fetchWithAuth(`/api/parent/storage/${DEVICE_ID}/delete`, {
            method: 'POST',
            body: JSON.stringify({ sections, from_ms, to_ms, dry_run: false }),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.error || `HTTP ${res.status}`);
        }
        const data = await res.json();
        const total = Object.values(data.deleted || {}).reduce((a, b) => a + b, 0);
        closeStorageConfirm();
        showToast('Records deleted', `${total.toLocaleString()} record(s) removed.`);
        // Refresh the page data so every tab reflects the new counts.
        await Promise.all([
            refreshStorageStats(),
            loadAllData(),
        ]);
    } catch (e) {
        console.error('storage delete failed', e);
        showToast('Error', e.message || 'Delete failed');
    } finally {
        apply.disabled = false;
        cancel.disabled = false;
        apply.textContent = 'Delete permanently';
    }
}

async function applyStorageOlder30() {
    const today = new Date();
    const thirtyAgo = new Date();
    thirtyAgo.setDate(today.getDate() - 30);
    // Older-than-30-days: from = epoch 0, to = 30 days ago (inclusive end-of-day)
    document.getElementById('storageFrom').value = '1970-01-01';
    document.getElementById('storageTo').value = dateInputValue(thirtyAgo.getTime());
    // Tick all sections with data.
    if (storageStats) {
        const has = new Set(storageStats.sections.filter(s => s.count > 0).map(s => s.key));
        document.querySelectorAll('#storageSectionsGrid input[type=checkbox]').forEach(cb => {
            cb.checked = has.has(cb.value);
        });
    }
    showToast('Ready', 'Click "Delete selected" to confirm the older-than-30-days purge.');
}

// ─── Delete Device ────────────────────────────────────────────────────────

function showDeleteModal() {
    const devName = document.getElementById('deviceName')?.textContent || 'this device';
    const sub = document.getElementById('deleteModalDeviceName');
    if (sub) {
        sub.textContent = `"${devName}" will be removed from your dashboard. All collected data is kept — you can re-add it any time by re-installing KidGuard on the device.`;
    }
    document.getElementById('deleteModalOverlay').classList.add('open');
}

function hideDeleteModal() {
    document.getElementById('deleteModalOverlay').classList.remove('open');
}

async function confirmDeleteDevice() {
    const btn = document.getElementById('btnConfirmDelete');
    if (!btn) return;
    btn.disabled = true;
    btn.textContent = 'Removing…';

    try {
        const res = await fetchWithAuth(`/api/parent/devices/${DEVICE_ID}/delete`, {
            method: 'POST'
        });
        if (res.ok) {
            showToast('Success', 'Device removed from dashboard');
            setTimeout(() => {
                window.location.href = '/dashboard';
            }, 1200);
        } else {
            const data = await res.json().catch(() => ({}));
            showToast('Error', data.error || 'Could not remove device');
            btn.disabled = false;
            btn.innerHTML = '🗑 Remove Device';
        }
    } catch (e) {
        showToast('Error', 'Network error — please try again');
        btn.disabled = false;
        btn.innerHTML = '🗑 Remove Device';
    }
}

// Close modal when clicking the backdrop
document.addEventListener('DOMContentLoaded', () => {
    const overlay = document.getElementById('deleteModalOverlay');
    if (overlay) {
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) hideDeleteModal();
        });
    }
});
