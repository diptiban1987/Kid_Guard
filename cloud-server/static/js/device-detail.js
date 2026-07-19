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

// ─── Fetch with Auth (auto-refresh) ──────────────────────────────────────

async function fetchWithAuth(url, options = {}) {
    if (!options.headers) options.headers = {};
    options.headers['Authorization'] = `Bearer ${TOKEN}`;
    options.headers['Content-Type'] = 'application/json';

    const res = await fetch(url, options);
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

// ─── Map ──────────────────────────────────────────────────────────────────

function initMap() {
    deviceMap = L.map('deviceMap').setView([20, 0], 2);
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a> &copy; <a href="https://carto.com/">CARTO</a>',
        subdomains: 'abcd',
        maxZoom: 19
    }).addTo(deviceMap);
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

// ─── Load All Data ────────────────────────────────────────────────────────

async function loadAllData() {
    try {
        // Fetch device info first
        const devicesRes = await fetchWithAuth('/api/parent/devices');
        const devices = await devicesRes.json();
        deviceInfo = devices.find(d => d.device_id === DEVICE_ID) || {};
        renderDeviceHeader(deviceInfo);

        // Parallel fetch all data
        const [locations, activity, sms, calls, apps, screentime, webhistory, media, geofences, restrictions, schedule, social] = await Promise.all([
            fetchWithAuth(`/api/parent/locations/${DEVICE_ID}?limit=200`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/activity/${DEVICE_ID}?limit=50`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/sms/${DEVICE_ID}?limit=50`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/calls/${DEVICE_ID}?limit=50`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/apps/${DEVICE_ID}`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/screentime/${DEVICE_ID}?days=7`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/webhistory/${DEVICE_ID}?limit=50`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/media/${DEVICE_ID}`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/geofences/${DEVICE_ID}`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/restrictions/${DEVICE_ID}`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/schedule/${DEVICE_ID}`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/social/${DEVICE_ID}?limit=100`).then(r => r.json()).catch(() => [])
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

        // Render everything
        renderStats();
        renderMap(locations, geofences);
        renderActivityPanel();
        renderSMSPanel();
        renderCallsPanel();
        renderAppsPanel();
        renderWebPanel();
        renderMediaPanel();
        renderSocialPanel();
        renderGeofences();
        renderRestrictions();
        renderSchedule();

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
