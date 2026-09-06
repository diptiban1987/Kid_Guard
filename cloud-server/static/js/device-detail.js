// ─── KidGuard Device Detail ───────────────────────────────────────────────
// Companion script for device.html

const TOKEN_KEY = 'kidguard_token';
const REFRESH_KEY = 'kidguard_refresh';

let TOKEN = localStorage.getItem(TOKEN_KEY);
let deviceMap;
let mapMarkers = [];
let mapPolyline = null;
let geoCircles = [];

// ─── Tile layer providers (no API key required) ──────────────────────────
// Each entry maps a value used in the <select id="mapStyleSelect"> to a
// Leaflet L.tileLayer config. Carto's `dark_nolabels` / `light_nolabels`
// are served on the public basemaps.cartocdn.com CDN without a key —
// the "API KEY REQUIRED" watermark the user previously saw was caused
// by the deprecated `dark_all` style and a Cloudflare retry that hit
// Carto's watermarked fallback. All five providers below were verified
// to return real tiles (200 image/png) without a key.
const TILE_PROVIDERS = {
    dark: {
        url: 'https://{s}.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}{r}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>',
        subdomains: 'abcd',
        maxZoom: 20,
        labels: 'Carto Dark'
    },
    light: {
        url: 'https://{s}.basemaps.cartocdn.com/light_nolabels/{z}/{x}/{y}{r}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>',
        subdomains: 'abcd',
        maxZoom: 20,
        labels: 'Carto Positron'
    },
    osm: {
        url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        subdomains: 'abc',
        maxZoom: 19,
        labels: 'OpenStreetMap'
    },
    esri: {
        // Esri World Imagery — free for low-traffic, no key required.
        url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        attribution: 'Tiles &copy; Esri &mdash; Source: Esri, i-cubed, USDA, USGS, AEX, GeoEye, Getmapping, Aerogrid, IGN, IGP, UPR-EGP, and the GIS User Community',
        maxZoom: 19,
        labels: 'Esri Satellite'
    },
    terrain: {
        // OpenTopoMap — free, no key, perfect for showing actual ground.
        url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
        attribution: 'Map data: &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors, <a href="http://viewfinderpanoramas.org">SRTM</a> | Map style: &copy; <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)',
        subdomains: 'abc',
        maxZoom: 17,
        labels: 'OpenTopoMap'
    }
};

let currentTileLayer = null;   // The active L.tileLayer
let currentProvider = 'dark';  // matches the <select> default
let lastErrorCount = 0;
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
let deviceInfo = {};

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

// ─── Fetch with Auth (auto-refresh & retry) ──────────────────────────────

async function fetchWithAuth(url, options = {}, retries = 2) {
    if (!options.headers) options.headers = {};
    if (TOKEN) options.headers['Authorization'] = `Bearer ${TOKEN}`;
    if (!options.headers['Content-Type']) options.headers['Content-Type'] = 'application/json';

    try {
        const res = await fetch(url, options);
        if (res.status === 401) {
            const refresh = localStorage.getItem(REFRESH_KEY);
            if (refresh) {
                try {
                    const refreshRes = await fetch('/api/auth/refresh', {
                        method: 'POST',
                        headers: { 'Authorization': `Bearer ${refresh}`, 'Content-Type': 'application/json' }
                    });
                    if (refreshRes.ok) {
                        const data = await refreshRes.json();
                        TOKEN = data.token;
                        localStorage.setItem(TOKEN_KEY, TOKEN);
                        options.headers['Authorization'] = `Bearer ${TOKEN}`;
                        return await fetch(url, options);
                    }
                } catch (_) {}
            }
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(REFRESH_KEY);
            window.location.href = '/';
            return res;
        }
        return res;
    } catch (err) {
        if (retries > 0) {
            await new Promise(r => setTimeout(r, 600));
            return fetchWithAuth(url, options, retries - 1);
        }
        throw err;
    }
}

// ─── Map ──────────────────────────────────────────────────────────────────

function setTileProvider(key) {
    if (!TILE_PROVIDERS[key]) key = 'dark';
    currentProvider = key;
    const cfg = TILE_PROVIDERS[key];
    if (currentTileLayer) {
        deviceMap.removeLayer(currentTileLayer);
    }
    const opts = {
        attribution: cfg.attribution,
        maxZoom: cfg.maxZoom || 19,
        // Don't block panning while tiles are loading.
        updateWhenZooming: false,
        // Use the small 256px tiles first, then upgrade.
        updateWhenIdle: true
    };
    if (cfg.subdomains) opts.subdomains = cfg.subdomains;
    currentTileLayer = L.tileLayer(cfg.url, opts);

    // Reset the error counter every time the layer changes.
    lastErrorCount = 0;
    updateTileStatus('ok', `tiles: ${cfg.labels}`);

    currentTileLayer.on('tileerror', (ev) => {
        lastErrorCount += 1;
        if (lastErrorCount <= 3) {
            // Log to the JS console for debugging.
            console.warn('Tile load error:', ev.tile.src);
        }
        if (lastErrorCount === 4) {
            updateTileStatus('warn', `tiles: ${lastErrorCount} errors — network slow?`);
        } else if (lastErrorCount > 8) {
            updateTileStatus('error', `tiles: failing (${lastErrorCount}) — try another style`);
        }
    });

    currentTileLayer.addTo(deviceMap);
}

function updateTileStatus(level, text) {
    const el = document.getElementById('mapTileStatus');
    if (!el) return;
    el.className = 'tile-status ' + level;
    el.textContent = text;
}

function initMap() {
    deviceMap = L.map('deviceMap', {
        zoomControl: true,
        // The map will be invalidated once the card is visible (below).
        preferCanvas: false,
        worldCopyJump: true
    }).setView([20, 0], 2);

    // Default to the dark provider; user can switch via the toolbar.
    setTileProvider(currentProvider);

    // Scale bar (bottom-left, metric + imperial)
    L.control.scale({ imperial: true, metric: true, position: 'bottomleft' }).addTo(deviceMap);

    // Live mouse coordinate readout (bottom-right)
    L.control.mousePosition({
        position: 'bottomright',
        separator: ' , ',
        prefix: '',
        numDigits: 5,
        lngFirst: false
    }).addTo(deviceMap);

    // Make sure the map recomputes its size when it first becomes visible
    // (e.g. inside a tab that's hidden until clicked). Run after the DOM
    // has had a chance to lay out the card.
    setTimeout(() => deviceMap.invalidateSize(), 50);

    // Wire up the toolbar controls
    const styleSel = document.getElementById('mapStyleSelect');
    if (styleSel) {
        styleSel.value = currentProvider;
        styleSel.addEventListener('change', () => setTileProvider(styleSel.value));
    }

    const fitBtn = document.getElementById('fitBoundsBtn');
    if (fitBtn) {
        fitBtn.addEventListener('click', () => {
            if (mapMarkers.length === 0 && !mapPolyline) {
                // Nothing to fit — gently reframe to the latest point.
                if (cachedLocations && cachedLocations.length > 0) {
                    const p = cachedLocations[0];
                    deviceMap.setView([p.latitude, p.longitude], 15);
                } else {
                    deviceMap.setView([20, 0], 2);
                }
                return;
            }
            const points = [];
            if (mapPolyline) points.push(...mapPolyline.getLatLngs());
            mapMarkers.forEach(m => { if (m.getLatLng) points.push(m.getLatLng()); });
            if (points.length > 0) {
                deviceMap.fitBounds(L.latLngBounds(points), { padding: [40, 40], maxZoom: 17 });
            }
        });
    }

    const pickBtn = document.getElementById('pickOnMapBtn');
    const crosshair = document.getElementById('mapCrosshair');
    let pickMode = false;
    if (pickBtn && crosshair) {
        pickBtn.addEventListener('click', () => {
            pickMode = !pickMode;
            crosshair.classList.toggle('on', pickMode);
            pickBtn.classList.toggle('active', pickMode);
        });
        deviceMap.on('move', () => {
            if (pickMode && typeof showGeofenceModal === 'function') {
                // Live-update the form fields so the user can see the
                // coordinates they're picking at.
                const c = deviceMap.getCenter();
                const latEl = document.getElementById('geoLat');
                const lonEl = document.getElementById('geoLon');
                if (latEl) latEl.value = c.lat.toFixed(6);
                if (lonEl) lonEl.value = c.lng.toFixed(6);
            }
        });
    }
}

function renderMap(locations, geofences) {
    // Clear previous layers
    mapMarkers.forEach(m => deviceMap.removeLayer(m));
    mapMarkers = [];
    if (mapPolyline) { deviceMap.removeLayer(mapPolyline); mapPolyline = null; }
    geoCircles.forEach(c => deviceMap.removeLayer(c));
    geoCircles = [];

    if (locations.length > 0) {
        const points = locations.map(l => [l.latitude, l.longitude]);

        // Polyline trail
        mapPolyline = L.polyline(points, {
            color: '#667eea',
            weight: 3,
            opacity: 0.7,
            smoothFactor: 1
        }).addTo(deviceMap);

        // Latest position marker (special)
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

        // Intermediate markers (show a few along the trail)
        const step = Math.max(1, Math.floor(locations.length / 12));
        for (let i = step; i < locations.length; i += step) {
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

        deviceMap.fitBounds(L.polyline(points).getBounds(), { padding: [40, 40] });
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

let currentFetchLimit = 500;
let currentSearchQuery = '';

function getFetchLimit() {
    const el = document.getElementById('dataLimitSelect');
    return el ? parseInt(el.value) || 500 : currentFetchLimit;
}

async function onDataLimitChange() {
    currentFetchLimit = getFetchLimit();
    showToast('Loading', `Fetching up to ${currentFetchLimit} records...`);
    await loadAllData();
}

function onDataSearch() {
    const el = document.getElementById('dataSearchInput');
    currentSearchQuery = (el ? el.value : '').toLowerCase().trim();
    renderAllPanels();
}

function filterList(list, stringGetters) {
    if (!list) return [];
    if (!currentSearchQuery) return list;
    return list.filter(item => {
        return stringGetters.some(fn => {
            const val = fn(item);
            return val && String(val).toLowerCase().includes(currentSearchQuery);
        });
    });
}

function renderAllPanels() {
    renderActivityPanel();
    renderSMSPanel();
    renderCallsPanel();
    renderAppsPanel();
    renderWebPanel();
    renderMediaPanel();
    renderSocialPanel();
    renderStats();
}

// ─── Load All Data ────────────────────────────────────────────────────────

async function loadAllData() {
    try {
        const limit = getFetchLimit();
        currentFetchLimit = limit;

        // Be defensive: some responses (Cloudflare Turnstile challenges
        // on Render cold-starts, 5xx HTML pages, network errors) are
        // not valid JSON. `response.json()` throws "Unexpected end of
        // JSON input" on those and previously tore down the whole
        // loadAllData() function, surfacing as the "Failed to load
        // device data" toast. safeJson() short-circuits to [] so a
        // single bad response cannot poison the page.
        const safeJson = async (res, fallback) => {
            if (!res) return fallback;
            const ctype = res.headers.get('content-type') || '';
            if (!ctype.includes('application/json')) {
                console.warn(`[device-detail] non-JSON response (${res.status}, ${ctype}) — using empty fallback`);
                return fallback;
            }
            try { return await res.json(); }
            catch (e) { console.warn('[device-detail] JSON parse failed:', e); return fallback; }
        };

        // Fetch device info first
        let devices = [];
        try {
            const devicesRes = await fetchWithAuth('/api/parent/devices');
            if (devicesRes && devicesRes.ok) {
                devices = await safeJson(devicesRes, []);
            }
        } catch (_) {}
        deviceInfo = (Array.isArray(devices) ? devices.find(d => d.device_id === DEVICE_ID) : null) || { device_id: DEVICE_ID };
        renderDeviceHeader(deviceInfo);

        // Parallel fetch all data with selected limit
        const [locations, activity, sms, calls, apps, screentime, webhistory, media, geofences, restrictions, schedule, social] = await Promise.all([
            fetchWithAuth(`/api/parent/locations/${DEVICE_ID}?limit=${Math.max(limit, 200)}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/activity/${DEVICE_ID}?limit=${limit}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/sms/${DEVICE_ID}?limit=${limit}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/calls/${DEVICE_ID}?limit=${limit}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/apps/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/screentime/${DEVICE_ID}?days=7`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/webhistory/${DEVICE_ID}?limit=${limit}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/media/${DEVICE_ID}?limit=${limit}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/geofences/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/restrictions/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/schedule/${DEVICE_ID}`).then(r => safeJson(r, [])).catch(() => []),
            fetchWithAuth(`/api/parent/social/${DEVICE_ID}?limit=${limit}`).then(r => safeJson(r, [])).catch(() => [])
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

        // Run each renderer independently so a single failure (e.g. an
        // unsupported Canvas2D method, missing DOM node, or unexpected
        // data shape) never tears down the whole page and triggers the
        // generic "Failed to load device data" toast. The first failure
        // is surfaced as a debug toast so the underlying bug stays
        // visible in the wild.
        const renderers = [
            ['renderStats',           () => renderStats()],
            ['renderMap',             () => renderMap(locations, geofences)],
            ['renderActivityPanel',   () => renderActivityPanel()],
            ['renderSMSPanel',        () => renderSMSPanel()],
            ['renderCallsPanel',      () => renderCallsPanel()],
            ['renderAppsPanel',       () => renderAppsPanel()],
            ['renderWebPanel',        () => renderWebPanel()],
            ['renderMediaPanel',      () => renderMediaPanel()],
            ['renderSocialPanel',     () => renderSocialPanel()],
            ['renderGeofences',       () => renderGeofences()],
            ['renderRestrictions',    () => renderRestrictions()],
            ['renderSchedule',        () => renderSchedule()],
            ['renderScreenTimeCard',  () => renderScreenTimeCard(screentime)],
            ['renderBatteryCard',     () => renderBatteryCard(deviceInfo)],
        ];
        let firstError = null;
        for (const [name, fn] of renderers) {
            try {
                fn();
            } catch (err) {
                console.error(`[${name}] render error:`, err);
                if (!firstError) firstError = { name, err };
            }
        }
        if (firstError) {
            showToast('Render warning', `Skipped ${firstError.name}: ${firstError.err && firstError.err.message ? firstError.err.message : 'see console'}`);
        }

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
    if (document.getElementById('statBattery')) document.getElementById('statBattery').textContent = battery != null ? `${battery}%` : '—';
    if (document.getElementById('statLocations')) document.getElementById('statLocations').textContent = cachedLocations.length.toLocaleString();
    if (document.getElementById('statSMS')) document.getElementById('statSMS').textContent = cachedSMS.length.toLocaleString();
    if (document.getElementById('statCalls')) document.getElementById('statCalls').textContent = cachedCalls.length.toLocaleString();
    if (document.getElementById('statApps')) document.getElementById('statApps').textContent = cachedApps.length.toLocaleString();

    const todayMins = cachedScreenTime.length > 0 ? cachedScreenTime[0].total_minutes || 0 : 0;
    if (document.getElementById('statScreenTime')) document.getElementById('statScreenTime').textContent = todayMins.toLocaleString();

    if (document.getElementById('locationCount')) document.getElementById('locationCount').textContent = `${cachedLocations.length} points`;

    // Filter counts
    const filteredAct = filterList(cachedActivity, [a => a.app_name, a => a.package_name, a => a.activity_type]);
    const filteredSMS = filterList(cachedSMS, [s => s.address, s => s.number, s => s.body]);
    const filteredCalls = filterList(cachedCalls, [c => c.name, c => c.number]);
    const filteredApps = filterList(cachedApps, [a => a.app_name, a => a.package_name]);
    const filteredWeb = filterList(cachedWebHistory, [w => w.title, w => w.url, w => w.browser]);
    const filteredSocial = filterList(cachedSocial, [n => n.app_name, n => n.sender, n => n.content]);
    const filteredMedia = filterList(cachedMedia, [m => m.filename, m => m.mime_type]);

    // Tab count badges
    if (document.getElementById('badgeActivity')) document.getElementById('badgeActivity').textContent = currentSearchQuery ? `${filteredAct.length}/${cachedActivity.length}` : cachedActivity.length;
    if (document.getElementById('badgeSMS')) document.getElementById('badgeSMS').textContent = currentSearchQuery ? `${filteredSMS.length}/${cachedSMS.length}` : cachedSMS.length;
    if (document.getElementById('badgeCalls')) document.getElementById('badgeCalls').textContent = currentSearchQuery ? `${filteredCalls.length}/${cachedCalls.length}` : cachedCalls.length;
    if (document.getElementById('badgeApps')) document.getElementById('badgeApps').textContent = currentSearchQuery ? `${filteredApps.length}/${cachedApps.length}` : cachedApps.length;
    if (document.getElementById('badgeWeb')) document.getElementById('badgeWeb').textContent = currentSearchQuery ? `${filteredWeb.length}/${cachedWebHistory.length}` : cachedWebHistory.length;
    if (document.getElementById('badgeSocial')) document.getElementById('badgeSocial').textContent = currentSearchQuery ? `${filteredSocial.length}/${(cachedSocial || []).length}` : (cachedSocial || []).length;
    if (document.getElementById('badgeMedia')) document.getElementById('badgeMedia').textContent = currentSearchQuery ? `${filteredMedia.length}/${cachedMedia.length}` : cachedMedia.length;
}

// ─── Screen Time Card ─────────────────────────────────────────────────────

// Polyfill CanvasRenderingContext2D.roundRect for older WebViews
// (Canvas2D roundRect was added in Chrome 99 / 2022, but some OEM
// WebViews and mini-program shells still ship a stripped-down 2D
// context that throws on the first call. Without this polyfill the
// screen-time card render throws TypeError, which previously tore
// down the whole loadAllData() and surfaced as a generic "Failed to
// load device data" toast on the device detail page.)
if (typeof CanvasRenderingContext2D !== 'undefined' && !CanvasRenderingContext2D.prototype.roundRect) {
    CanvasRenderingContext2D.prototype.roundRect = function (x, y, w, h, r) {
        if (typeof r === 'number') r = [r, r, r, r];
        else if (!Array.isArray(r)) r = [0, 0, 0, 0];
        this.beginPath();
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

// ─── Activity Panel ───────────────────────────────────────────────────────

// Map known package names/types to icons
const ACTIVITY_ICONS = {
    'com.anonchat.app':       '🔒',
    'com.android.systemui':   '📱',
    'com.android.launcher':   '🏠',
    'com.google.android.gm':  '📧',
    'com.whatsapp':           '💬',
    'com.instagram.android':  '📷',
    'com.facebook.katana':    '👥',
    'com.google.android.youtube': '▶️',
    'com.android.chrome':     '🌐',
    'com.google.android.apps.maps': '🗺️',
    'com.coloros.calculator':  '🔢',
    'app_launch':             '▶️',
    'default':                '📱',
};

function activityIcon(a) {
    if (a && a.activity_type === 'click') return isDialerPackage(a.package_name) ? '📞' : '👆';
    if (a && isDialerPackage(a.package_name)) return '📞';
    return ACTIVITY_ICONS[a.package_name] || ACTIVITY_ICONS[a.activity_type] || ACTIVITY_ICONS['default'];
}

// Tapped-element text: the accessibility layer stores the tapped button's
// content description under data.viewId (e.g. "call Megha Bestie 😊").
function tappedText(a) {
    const t = a && a.data && (a.data.viewId || a.data.text || a.data.label);
    return t ? String(t) : '';
}

function isDialerPackage(pkg) {
    return /dialer|telecom|com\.android\.contacts/.test(String(pkg || '').toLowerCase());
}

// Expanded-detail persistence. loadAllData() re-renders every panel every 30s,
// which used to collapse any open detail card. Open items are tracked by a
// stable per-item key (timestamp+type+package) so the card stays open across
// the automatic refresh. (idx cannot be used — it shifts as new events land.)
const openDetailKeys = new Set();
let activityKeys = [];   // idx -> stable key, rebuilt on every activity render

function panelItemKey(a) {
    return [a && a.timestamp, a && a.activity_type, a && a.package_name, a && a.app_name].join('|');
}

function togglePanelDetail(prefix, idx, key) {
    const row    = document.getElementById(`${prefix}-row-${idx}`);
    const detail = document.getElementById(`${prefix}-detail-${idx}`);
    const arrow  = document.getElementById(`${prefix}-arrow-${idx}`);
    if (!detail) return;
    const isOpen = detail.classList.contains('open');
    if (key) {
        if (isOpen) openDetailKeys.delete(key); else openDetailKeys.add(key);
    }
    detail.classList.toggle('open', !isOpen);
    row.classList.toggle('expanded', !isOpen);
    arrow.classList.toggle('open', !isOpen);
}

function toggleActivityDetail(idx) {
    togglePanelDetail('act', idx, activityKeys[idx]);
}

function formatFileSize(bytes) {
    if (!bytes || isNaN(bytes)) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
    return (bytes / 1073741824).toFixed(1) + ' GB';
}

function buildActivityDetail(a) {
    const fields = [];

    // Screenshot image for chat captures
    let shotHtml = '';
    if (a.activity_type === 'chat_screenshot' && a.data && (a.data.image_url || a.data.media_id)) {
        const src = a.data.image_url || `/api/files/${a.data.media_id}?token=${encodeURIComponent(localStorage.getItem('kidguard_token') || '')}`;
        shotHtml = `<div style="margin:0 0 10px;">
            <div class="activity-detail-label" style="margin-bottom:6px;">📸 Screen capture — ${escHtml(String(a.data.reason || 'chat').replace(/_/g, ' '))}</div>
            <img class="act-screenshot" src="${escAttr(src)}" onclick="openLightbox('${escAttr(src)}')" alt="chat screenshot">
        </div>`;
    }

    // Highlighted typed-text block for keystroke captures
    let typedHtml = '';
    if (a.activity_type === 'text_input' && a.data && a.data.text) {
        typedHtml = `<div style="margin:0 0 10px;padding:8px 12px;background:#f4f6fb;border-left:3px solid #5b7cfa;border-radius:4px;color:#333;font-size:13px;white-space:pre-wrap;word-break:break-word;">“${escHtml(String(a.data.text).substring(0, 500))}”</div>`;
    }

    // Highlighted tapped-element block for click events (dialer taps, etc.)
    let tapHtml = '';
    if (a.activity_type === 'click') {
        const t = tappedText(a);
        if (t) {
            tapHtml = `<div style="margin:0 0 10px;padding:8px 12px;background:rgba(245,166,35,0.08);border-left:3px solid #f5a623;border-radius:4px;color:rgba(255,255,255,0.9);font-size:13px;white-space:pre-wrap;word-break:break-word;">Tapped: “${escHtml(t)}”</div>`;
        }
    }

    // Always-present fields
    fields.push({ label: 'Activity Type', value: escHtml(a.activity_type || '—') });
    fields.push({ label: 'App Name',      value: escHtml(a.app_name || '—') });
    fields.push({ label: 'Package',       value: escHtml(a.package_name || '—') });
    fields.push({ label: 'Time',          value: escHtml(formatFullTime(a.timestamp)) });

    // Extra fields from the data blob
    const data = a.data || {};
    const knownKeys = new Set(['activity_type', 'app_name', 'package_name', 'timestamp']);
    Object.entries(data).forEach(([k, v]) => {
        if (knownKeys.has(k)) return;
        // Friendly types: the tap text / window class are already surfaced
        // above (tap block / label), so don't repeat raw keys in the grid.
        if (a.activity_type === 'click' && (k === 'viewId' || k === 'text' || k === 'label')) return;
        if (a.activity_type === 'app_switch' && k === 'className') return;
        let display = typeof v === 'object' ? JSON.stringify(v) : String(v);
        fields.push({ label: k.replace(/_/g, ' '), value: escHtml(display) });
    });

    const gridHtml = fields.map(f => `
        <div class="activity-detail-field">
            <div class="activity-detail-label">${f.label}</div>
            <div class="activity-detail-value">${f.value}</div>
        </div>`).join('');

    // Raw data dump only when there's anything meaningful beyond the friendly
    // keys (viewId/className etc. are already rendered as readable blocks).
    let rawHtml = '';
    const simpleKeys = new Set(['viewId', 'text', 'label', 'className']);
    if (Object.keys(data).some(k => !simpleKeys.has(k))) {
        rawHtml = `<div class="activity-data-raw">${escHtml(JSON.stringify(data, null, 2))}</div>`;
    }

    return `${shotHtml}${typedHtml}${tapHtml}<div class="activity-detail-grid">${gridHtml}</div>${rawHtml}`;
}

function formatFullTime(ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    return d.toLocaleString(undefined, {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
}

function renderActivityPanel() {
    const container = document.getElementById('panel-activity');
    const items = filterList(cachedActivity, [a => a.app_name, a => a.package_name, a => a.activity_type, a => a.data && a.data.text]);
    if (items.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-icon">📋</div>${currentSearchQuery ? 'No matching activity logs' : 'No activity recorded yet'}</div>`;
        return;
    }
    // Remember each row's stable key so an expanded detail card survives the
    // 30s auto-refresh re-render (see openDetailKeys).
    activityKeys = items.map(a => panelItemKey(a));
    container.innerHTML = items.map((a, idx) => {
        const open = openDetailKeys.has(activityKeys[idx]);
        return `
        <div class="activity-item">
            <div class="activity-row${open ? ' expanded' : ''}" id="act-row-${idx}" onclick="toggleActivityDetail(${idx})">
                <span class="activity-arrow${open ? ' open' : ''}" id="act-arrow-${idx}">▶</span>
                <div class="activity-app-icon">${activityIcon(a)}</div>
                <div class="activity-main">
                    <div class="activity-name">${activityLabel(a)}</div>
                    ${activityPreview(a)}
                    ${a.package_name ? `<div class="activity-pkg">${escHtml(a.package_name)}</div>` : ''}
                </div>
                <span class="activity-time">${formatTime(a.timestamp)}</span>
            </div>
            <div class="activity-detail${open ? ' open' : ''}" id="act-detail-${idx}">
                ${buildActivityDetail(a)}
            </div>
        </div>
    `;}).join('');
}

// Row label + inline preview for the activity list
function activityLabel(a) {
    if (a.activity_type === 'chat_screenshot') return '📸 Screenshot in ' + (a.app_name || a.package_name || 'Chat app');
    if (a.activity_type === 'chat_capture') return '💬 Conversation in ' + (a.app_name || a.package_name || 'Chat app');
    if (a.activity_type === 'text_input') return '⌨ Typed in ' + (a.app_name || a.package_name || 'App');
    if (a.activity_type === 'app_switch') return '📱 Opened ' + (a.app_name || a.package_name || 'App');
    if (a.activity_type === 'app_launch') return '▶️ Launched ' + (a.app_name || a.package_name || 'App');
    if (a.activity_type === 'click') {
        const t = tappedText(a);
        const call = t.match(/^call\s+(.+)$/i);   // Dialer buttons: "call <name/number>"
        if (call) return '📞 Call started — ' + call[1];
        if (t) return '👆 Tapped "' + t + '"' + (a.app_name ? ' in ' + a.app_name : '');
        return '👆 Tap in ' + (a.app_name || a.package_name || 'App');
    }
    return a.app_name || a.activity_type || 'Activity';
}

function activityPreview(a) {
    if (a.activity_type === 'chat_capture' && a.data && Array.isArray(a.data.messages) && a.data.messages.length > 0) {
        const first = String(a.data.messages[0] || '').substring(0, 90);
        const more = a.data.messages.length > 1 ? ` <span class="activity-preview-more">+${a.data.messages.length - 1} more</span>` : '';
        return first ? `<div class="activity-preview">${escHtml(first)}${more}</div>` : '';
    }
    if (a.activity_type === 'chat_screenshot' && a.data && (a.data.image_url || a.data.media_id)) {
        const src = a.data.image_url || `/api/files/${a.data.media_id}?token=${encodeURIComponent(localStorage.getItem('kidguard_token') || '')}`;
        return `<img class="activity-preview-thumb" src="${escAttr(src)}" alt="screenshot">`;
    }
    if (a.activity_type === 'text_input' && a.data && a.data.text) {
        return `<div class="activity-preview">${escHtml(String(a.data.text).substring(0, 90))}</div>`;
    }
    if (a.activity_type === 'click') {
        const t = tappedText(a);
        // Show the tapped-button text inline so the call/contact name is
        // readable without expanding the detail card.
        return t ? `<div class="activity-preview">${escHtml(t.substring(0, 90))}</div>` : '';
    }
    return '';
}


// ─── SMS Panel ────────────────────────────────────────────────────────────

function buildSmsDetail(s) {
    const isSent = s.type === 2;
    const fields = [
        { label: 'Type',             value: isSent ? 'Sent (Outgoing)' : 'Received (Incoming)' },
        { label: 'Address / Number', value: escHtml(s.address || s.number || '—') },
        { label: 'Date & Time',      value: escHtml(formatFullTime(s.date)) },
        { label: 'Message ID',       value: escHtml(s.id || '—') }
    ];
    const gridHtml = fields.map(f => `
        <div class="activity-detail-field">
            <div class="activity-detail-label">${f.label}</div>
            <div class="activity-detail-value">${f.value}</div>
        </div>`).join('');

    const bodyHtml = `
        <div class="activity-detail-field" style="grid-column: 1 / -1; margin-top: 4px;">
            <div class="activity-detail-label">Full Message Body</div>
            <div class="activity-detail-value" style="white-space: pre-wrap; font-size: 13px; line-height: 1.5; color: rgba(255,255,255,0.95); background: rgba(255,255,255,0.03); padding: 8px 10px; border-radius: 6px; margin-top: 4px;">${escHtml(s.body || '')}</div>
        </div>`;

    return `<div class="activity-detail-grid">${gridHtml}${bodyHtml}</div>`;
}

function renderSMSPanel() {
    const container = document.getElementById('panel-sms');
    const items = filterList(cachedSMS, [s => s.address, s => s.number, s => s.body]);
    if (items.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-icon">💬</div>${currentSearchQuery ? 'No matching SMS messages' : 'No SMS messages found'}</div>`;
        return;
    }
    container.innerHTML = items.map((s, idx) => {
        const isSent = s.type === 2;
        return `
            <div class="activity-item">
                <div class="activity-row" id="sms-row-${idx}" onclick="togglePanelDetail('sms', ${idx})">
                    <span class="activity-arrow" id="sms-arrow-${idx}">▶</span>
                    <div class="activity-app-icon">${isSent ? '📤' : '📥'}</div>
                    <div class="activity-main">
                        <div class="activity-name">${escHtml(s.address || s.number || 'Unknown')}</div>
                        <div class="activity-pkg">${escHtml((s.body || '').substring(0, 70))}</div>
                    </div>
                    <span class="type-badge ${isSent ? 'sent' : 'received'}" style="margin-right:8px;">${isSent ? '↑ Sent' : '↓ Received'}</span>
                    <span class="activity-time">${formatTime(s.date)}</span>
                </div>
                <div class="activity-detail" id="sms-detail-${idx}">
                    ${buildSmsDetail(s)}
                </div>
            </div>`;
    }).join('');
}

// ─── Calls Panel ──────────────────────────────────────────────────────────

function callIcon(type) {
    if (type === 1) return '📥';
    if (type === 2) return '📤';
    if (type === 3) return '❌';
    return '📞';
}

function buildCallsDetail(c) {
    const typeMap = { 1: 'Incoming Call', 2: 'Outgoing Call', 3: 'Missed Call' };
    const label = typeMap[c.type] || 'Call';
    const fields = [
        { label: 'Call Type',    value: label },
        { label: 'Phone Number', value: escHtml(c.number || '—') },
        { label: 'Contact Name', value: escHtml(c.name || '—') },
        { label: 'Duration',     value: `${formatDuration(c.duration)} (${c.duration || 0}s)` },
        { label: 'Date & Time',  value: escHtml(formatFullTime(c.date)) },
        { label: 'Call ID',      value: escHtml(c.id || '—') }
    ];
    const gridHtml = fields.map(f => `
        <div class="activity-detail-field">
            <div class="activity-detail-label">${f.label}</div>
            <div class="activity-detail-value">${f.value}</div>
        </div>`).join('');
    return `<div class="activity-detail-grid">${gridHtml}</div>`;
}

function renderCallsPanel() {
    const container = document.getElementById('panel-calls');
    const items = filterList(cachedCalls, [c => c.name, c => c.number]);
    if (items.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-icon">📞</div>${currentSearchQuery ? 'No matching call logs' : 'No call log found'}</div>`;
        return;
    }
    container.innerHTML = items.map((c, idx) => {
        const typeMap = { 1: ['incoming', '↓ Incoming'], 2: ['outgoing', '↑ Outgoing'], 3: ['missed', '✕ Missed'] };
        const [cls, label] = typeMap[c.type] || ['incoming', 'Call'];
        return `
            <div class="activity-item">
                <div class="activity-row" id="call-row-${idx}" onclick="togglePanelDetail('call', ${idx})">
                    <span class="activity-arrow" id="call-arrow-${idx}">▶</span>
                    <div class="activity-app-icon">${callIcon(c.type)}</div>
                    <div class="activity-main">
                        <div class="activity-name">${escHtml(c.name || c.number || 'Unknown')}</div>
                        <div class="activity-pkg">${c.name ? escHtml(c.number || '') + ' &middot; ' : ''}Duration: ${formatDuration(c.duration)}</div>
                    </div>
                    <span class="type-badge ${cls}" style="margin-right:8px;">${label}</span>
                    <span class="activity-time">${formatTime(c.date)}</span>
                </div>
                <div class="activity-detail" id="call-detail-${idx}">
                    ${buildCallsDetail(c)}
                </div>
            </div>`;
    }).join('');
}

// ─── Apps Panel ───────────────────────────────────────────────────────────

function buildAppsDetail(a) {
    const fields = [
        { label: 'App Name',        value: escHtml(a.app_name || '—') },
        { label: 'Package Name',    value: escHtml(a.package_name || '—') },
        { label: 'Version Name',    value: escHtml(a.version_name || '—') },
        { label: 'Version Code',    value: escHtml(a.version_code || '—') },
        { label: 'App Type',        value: a.is_system_app ? 'System App' : 'User Installed App' },
        { label: 'First Installed', value: formatFullTime(a.first_install_time) },
        { label: 'Last Updated',    value: formatFullTime(a.last_update_time) }
    ];
    const gridHtml = fields.map(f => `
        <div class="activity-detail-field">
            <div class="activity-detail-label">${f.label}</div>
            <div class="activity-detail-value">${f.value}</div>
        </div>`).join('');
    return `<div class="activity-detail-grid">${gridHtml}</div>`;
}

function renderAppsPanel() {
    const container = document.getElementById('panel-apps');
    const items = filterList(cachedApps, [a => a.app_name, a => a.package_name]);
    if (items.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-icon">📦</div>${currentSearchQuery ? 'No matching installed apps' : 'No apps found'}</div>`;
        return;
    }
    container.innerHTML = items.map((a, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="app-row-${idx}" onclick="togglePanelDetail('app', ${idx})">
                <span class="activity-arrow" id="app-arrow-${idx}">▶</span>
                <div class="activity-app-icon">📦</div>
                <div class="activity-main">
                    <div class="activity-name">${escHtml(a.app_name || a.package_name)}</div>
                    <div class="activity-pkg">${escHtml(a.package_name || '')}</div>
                </div>
                <span class="device-tag" style="margin-right:8px;">${a.is_system_app ? 'System App' : 'User App'}</span>
            </div>
            <div class="activity-detail" id="app-detail-${idx}">
                ${buildAppsDetail(a)}
            </div>
        </div>
    `).join('');
}

// ─── Web History Panel ────────────────────────────────────────────────────

function buildWebDetail(w) {
    const fields = [
        { label: 'Page Title',   value: escHtml(w.title || '—') },
        { label: 'Browser',      value: escHtml(w.browser || 'Default') },
        { label: 'Visit Count',  value: escHtml(String(w.visit_count || w.visits || 1)) },
        { label: 'Last Visited', value: formatFullTime(w.timestamp || w.date || w.last_visited) }
    ];
    const gridHtml = fields.map(f => `
        <div class="activity-detail-field">
            <div class="activity-detail-label">${f.label}</div>
            <div class="activity-detail-value">${f.value}</div>
        </div>`).join('');

    const urlHtml = `
        <div class="activity-detail-field" style="grid-column: 1 / -1; margin-top: 4px;">
            <div class="activity-detail-label">Full URL</div>
            <div class="activity-detail-value" style="margin-top: 4px;">
                <a href="${escAttr(w.url)}" target="_blank" rel="noopener" style="color:#818cf8; word-break:break-all; text-decoration: underline;">🔗 ${escHtml(w.url)}</a>
            </div>
        </div>`;

    return `<div class="activity-detail-grid">${gridHtml}${urlHtml}</div>`;
}

function renderWebPanel() {
    const container = document.getElementById('panel-web');
    const items = filterList(cachedWebHistory, [w => w.title, w => w.url, w => w.browser]);
    if (items.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-icon">🌐</div>${currentSearchQuery ? 'No matching web history' : 'No web history found'}</div>`;
        return;
    }
    container.innerHTML = items.map((w, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="web-row-${idx}" onclick="togglePanelDetail('web', ${idx})">
                <span class="activity-arrow" id="web-arrow-${idx}">▶</span>
                <div class="activity-app-icon">🌐</div>
                <div class="activity-main">
                    <div class="activity-name">${escHtml(w.title || truncateUrl(w.url))}</div>
                    <div class="activity-pkg">${escHtml(w.url || '')}</div>
                </div>
                <span class="activity-time">${formatTime(w.timestamp || w.date || w.last_visited)}</span>
            </div>
            <div class="activity-detail" id="web-detail-${idx}">
                ${buildWebDetail(w)}
            </div>
        </div>
    `).join('');
}

// ─── Media Panel ──────────────────────────────────────────────────────────

function buildMediaDetail(m, thumbUrl, isImage) {
    const fields = [
        { label: 'Filename',  value: escHtml(m.filename || m.name || '—') },
        { label: 'File Size', value: formatFileSize(m.file_size || m.size) },
        { label: 'MIME Type', value: escHtml(m.mime_type || m.type || '—') },
        { label: 'Date',      value: formatFullTime(m.created_at || m.timestamp) }
    ];
    const gridHtml = fields.map(f => `
        <div class="activity-detail-field">
            <div class="activity-detail-label">${f.label}</div>
            <div class="activity-detail-value">${f.value}</div>
        </div>`).join('');

    const previewHtml = isImage ? `
        <div style="margin-top:10px; display:flex; gap:12px; align-items:center;">
            <img src="${escAttr(thumbUrl)}" style="max-width:180px; max-height:120px; border-radius:8px; cursor:pointer; border:1px solid rgba(255,255,255,0.1);" onclick="openLightbox('${escAttr(thumbUrl)}')">
            <a href="${escAttr(thumbUrl)}" target="_blank" download class="btn-primary" style="padding:6px 12px; font-size:12px; text-decoration:none; display:inline-flex; align-items:center; gap:4px;">⬇️ Open Original</a>
        </div>` : `
        <div style="margin-top:10px;">
            <a href="${escAttr(thumbUrl)}" target="_blank" download class="btn-primary" style="padding:6px 12px; font-size:12px; text-decoration:none; display:inline-flex; align-items:center; gap:4px;">⬇️ Download Media</a>
        </div>`;

    return `<div class="activity-detail-grid">${gridHtml}</div>${previewHtml}`;
}

function renderMediaPanel() {
    const container = document.getElementById('panel-media');
    const items = filterList(cachedMedia, [m => m.filename, m => m.mime_type]);
    if (items.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-icon">🖼️</div>${currentSearchQuery ? 'No matching media files' : 'No media files found'}</div>`;
        return;
    }
    const token = localStorage.getItem('kidguard_token') || '';
    container.innerHTML = items.map((m, idx) => {
        const thumbUrl = `/api/files/${m.id || m.media_id}?token=${encodeURIComponent(token)}`;
        const isImage = (m.mime_type || m.type || '').startsWith('image');
        return `
            <div class="activity-item">
                <div class="activity-row" id="media-row-${idx}" onclick="togglePanelDetail('media', ${idx})">
                    <span class="activity-arrow" id="media-arrow-${idx}">▶</span>
                    <div class="activity-app-icon">${isImage ? '🖼️' : '🎥'}</div>
                    <div class="activity-main">
                        <div class="activity-name">${escHtml(m.filename || m.name || 'Media File')}</div>
                        <div class="activity-pkg">${formatFileSize(m.file_size || m.size)} &middot; ${escHtml(m.mime_type || m.type || 'file')}</div>
                    </div>
                    <span class="activity-time">${formatTime(m.created_at || m.timestamp)}</span>
                </div>
                <div class="activity-detail" id="media-detail-${idx}">
                    ${buildMediaDetail(m, thumbUrl, isImage)}
                </div>
            </div>`;
    }).join('');
}

// ─── Social Panel ─────────────────────────────────────────────────────────

function buildSocialDetail(n) {
    const fields = [
        { label: 'App Name',     value: escHtml(n.app_name || '—') },
        { label: 'Message Type', value: escHtml(n.message_type || 'Notification') },
        { label: 'Sender',       value: escHtml(n.sender || '—') },
        { label: 'Date & Time',  value: formatFullTime(n.timestamp) },
        { label: 'Package Name', value: escHtml(n.package_name || '—') }
    ];
    const gridHtml = fields.map(f => `
        <div class="activity-detail-field">
            <div class="activity-detail-label">${f.label}</div>
            <div class="activity-detail-value">${f.value}</div>
        </div>`).join('');

    const contentHtml = `
        <div class="activity-detail-field" style="grid-column: 1 / -1; margin-top: 4px;">
            <div class="activity-detail-label">Notification / Message Content</div>
            <div class="activity-detail-value" style="white-space: pre-wrap; font-size: 13px; line-height: 1.5; color: rgba(255,255,255,0.95); background: rgba(255,255,255,0.03); padding: 8px 10px; border-radius: 6px; margin-top: 4px;">${escHtml(n.content || '')}</div>
        </div>`;

    return `<div class="activity-detail-grid">${gridHtml}${contentHtml}</div>`;
}

function renderSocialPanel() {
    const container = document.getElementById('panel-social');
    const items = filterList(cachedSocial, [n => n.app_name, n => n.sender, n => n.content]);
    if (items.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-icon">💬</div>${currentSearchQuery ? 'No matching social activity' : 'No social media activity captured yet.'}</div>`;
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

    container.innerHTML = items.map((n, idx) => {
        const icon = socialIcons[n.app_name] || '📱';
        const badge = typeBadge[n.message_type] || typeBadge['notification'];
        return `
            <div class="activity-item">
                <div class="activity-row" id="soc-row-${idx}" onclick="togglePanelDetail('soc', ${idx})">
                    <span class="activity-arrow" id="soc-arrow-${idx}">▶</span>
                    <div class="activity-app-icon">${icon}</div>
                    <div class="activity-main">
                        <div class="activity-name">${escHtml(n.app_name)} ${n.sender ? '&middot; ' + escHtml(n.sender) : ''}</div>
                        <div class="activity-pkg">${escHtml((n.content || '').substring(0, 70))}</div>
                    </div>
                    ${badge}
                    <span class="activity-time" style="margin-left:8px;">${formatTime(n.timestamp)}</span>
                </div>
                <div class="activity-detail" id="soc-detail-${idx}">
                    ${buildSocialDetail(n)}
                </div>
            </div>`;
    }).join('');
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
    return (Date.now() - new Date(lastSeen).getTime()) < 1500000; // 25 min keep-alive window
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
