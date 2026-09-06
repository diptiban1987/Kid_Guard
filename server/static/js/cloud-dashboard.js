// KidGuard Cloud Dashboard — Enhanced
let map, childMap;
let markers = [];
let currentChildId = null;
let currentDeviceId = null;
let TOKEN = localStorage.getItem('kidguard_token');
let USER = null;
let pollInterval = null;
let refreshIntervalMs = 30000;

// Cached data for child detail tabs
let cachedActivities = null;
let cachedSms = null;
let cachedCalls = null;
let cachedApps = null;
let cachedWebHistory = null;
let cachedMedia = null;

// ─── Initialize ──────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    if (!TOKEN) {
        window.location.href = '/';
        return;
    }
    loadUser();
    initMap();
    loadDashboard();
    startPolling();
    loadSettings();
});

// ─── Auth ─────────────────────────────────────────────────────────────────

async function loadUser() {
    try {
        const res = await fetch('/api/auth/me', {
            headers: { 'Authorization': `Bearer ${TOKEN}` }
        });
        if (!res.ok) throw new Error('Unauthorized');
        USER = (await res.json()).user;
        document.getElementById('navUser').textContent = USER.display_name;
        // Settings
        document.getElementById('settingsEmail').textContent = USER.email;
        document.getElementById('settingsRole').textContent = USER.role;
        document.getElementById('settingsServerUrl').textContent = window.location.origin;
    } catch (e) {
        localStorage.removeItem('kidguard_token');
        localStorage.removeItem('kidguard_refresh');
        window.location.href = '/';
    }
}

function logout() {
    localStorage.removeItem('kidguard_token');
    localStorage.removeItem('kidguard_refresh');
    window.location.href = '/';
}

// ─── Settings Panel ──────────────────────────────────────────────────────

function toggleSettings() {
    const panel = document.getElementById('settingsPanel');
    const overlay = document.getElementById('settingsOverlay');
    const isOpen = panel.classList.contains('open');
    panel.classList.toggle('open');
    overlay.classList.toggle('open');
    if (isOpen) {
        document.body.style.overflow = '';
    } else {
        document.body.style.overflow = 'hidden';
    }
}

function loadSettings() {
    const saved = localStorage.getItem('kidguard_settings');
    if (saved) {
        const s = JSON.parse(saved);
        if (s.refreshInterval) {
            refreshIntervalMs = s.refreshInterval * 1000;
            document.getElementById('settingRefreshInterval').value = s.refreshInterval;
        }
        if (s.autoRefresh === false) {
            document.getElementById('settingAutoRefresh').checked = false;
        }
        if (s.soundNotifications) {
            document.getElementById('settingSound').checked = true;
        }
    }
}

function saveSettings() {
    const s = {
        refreshInterval: parseInt(document.getElementById('settingRefreshInterval').value),
        autoRefresh: document.getElementById('settingAutoRefresh').checked,
        soundNotifications: document.getElementById('settingSound').checked
    };
    localStorage.setItem('kidguard_settings', JSON.stringify(s));
}

function toggleAutoRefresh(enabled) {
    if (enabled) {
        startPolling();
    } else {
        if (pollInterval) clearInterval(pollInterval);
        pollInterval = null;
        document.getElementById('liveIndicator').classList.remove('connected');
        document.querySelector('.live-text').textContent = 'PAUSED';
    }
    saveSettings();
}

function updateRefreshInterval(seconds) {
    refreshIntervalMs = parseInt(seconds) * 1000;
    if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = setInterval(pollDashboard, refreshIntervalMs);
    }
    document.querySelector('.live-text').textContent = seconds + 's';
    document.getElementById('settingsConnection').textContent = `Polling (${seconds}s)`;
    saveSettings();
}

// ─── Mobile Sidebar ──────────────────────────────────────────────────────

function toggleMobileSidebar() {
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebarOverlay');
    sidebar.classList.toggle('mobile-open');
    overlay.classList.toggle('open');
}

// ─── Polling ─────────────────────────────────────────────────────────────

function startPolling() {
    if (pollInterval) clearInterval(pollInterval);
    document.getElementById('liveIndicator').classList.add('connected');
    const sec = refreshIntervalMs / 1000;
    document.querySelector('.live-text').textContent = sec + 's';
    pollInterval = setInterval(pollDashboard, refreshIntervalMs);
}

function pollDashboard() {
    const overview = document.getElementById('overviewSection');
    if (overview && !overview.classList.contains('hidden')) {
        loadDashboard();
    }
    checkForUpdates();
    checkPendingPairings();
}

let lastUpdateTime = 0;

async function checkForUpdates() {
    try {
        const res = await fetchWithAuth('/api/parent/updates?since=' + lastUpdateTime);
        const data = await res.json();
        lastUpdateTime = data.server_time || Date.now();
        if (data.notifications) {
            data.notifications.forEach(n => showNotification(n.title, n.message));
        }
    } catch (e) { /* Silent */ }
}

function showNotification(title, message) {
    const container = document.getElementById('mainContent');
    const notif = document.createElement('div');
    notif.className = 'toast-notification';
    notif.innerHTML = `<strong>${escapeHtml(title)}</strong><br>${escapeHtml(message)}`;
    container.appendChild(notif);
    setTimeout(() => notif.remove(), 5000);
}

// ─── Map ──────────────────────────────────────────────────────────────────

function initMap() {
    map = L.map('map').setView([20, 0], 2);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
    }).addTo(map);

    childMap = L.map('childMap').setView([20, 0], 2);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(childMap);
}

// ─── Dashboard ────────────────────────────────────────────────────────────

// safeJson: short-circuit to a typed fallback when an upstream proxy (Cloudflare
// Turnstile on Render cold-starts) returns an HTML challenge page or a non-JSON
// error. Without this, a single bad response tears the whole dashboard down.
async function safeJson(res, fallback) {
    try {
        const ct = (res.headers.get('content-type') || '').toLowerCase();
        if (!ct.includes('application/json')) {
            console.warn('[cloud-dashboard] non-JSON response', res.status, ct);
            return fallback;
        }
        return await res.json();
    } catch (e) {
        console.warn('[cloud-dashboard] JSON parse failed', res.status, e.message);
        return fallback;
    }
}

async function loadDashboard() {
    try {
        const [statsRes, devicesRes] = await Promise.all([
            fetchWithAuth('/api/parent/stats'),
            fetchWithAuth('/api/parent/devices')
        ]);
        const stats = await safeJson(statsRes, { children: [], online_devices: 0, total_activities: 0, total_locations: 0 });
        const devices = await safeJson(devicesRes, []);

        if (!Array.isArray(devices) || statsRes.status === 403) {
            localStorage.removeItem('kidguard_token');
            localStorage.removeItem('kidguard_refresh');
            window.location.href = '/';
            return;
        }

        // Stats
        document.getElementById('statChildren').textContent = stats.children?.length || 0;
        document.getElementById('statOnline').textContent = stats.online_devices || 0;
        document.getElementById('statActivities').textContent = (stats.total_activities || 0).toLocaleString();
        document.getElementById('statLocations').textContent = (stats.total_locations || 0).toLocaleString();

        // Children overview
        const overview = document.getElementById('childOverview');
        if (!stats.children?.length) {
            overview.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">👨‍👩‍👧‍👦</div>
                    <div class="empty-state-title">No children added yet</div>
                    <div class="empty-state-text">Install KidGuard on your child's phone and use a pairing code to connect.</div>
                    <button class="btn-primary" onclick="showAddChild()">Add Your First Child</button>
                </div>`;
        } else {
            overview.innerHTML = stats.children.map(c => {
                const child = c.child;
                const devs = c.devices || [];
                const online = devs.some(d => isOnline(d.last_seen));
                // Navigate directly to the device page (best UX — device page has full detail)
                const deviceId = devs[0]?.device_id || '';
                const href = deviceId ? `/device/${deviceId}` : '#';
                return `<a class="child-card" href="${href}" style="text-decoration:none;display:flex;align-items:center;gap:12px;padding:14px 16px;border-radius:12px;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.07);margin-bottom:8px;cursor:pointer;transition:all 0.2s;" onmouseover="this.style.background='rgba(102,126,234,0.08)'" onmouseout="this.style.background='rgba(255,255,255,0.03)'">
                    <div class="child-avatar">${escapeHtml(child.display_name.charAt(0).toUpperCase())}</div>
                    <div class="child-info">
                        <div class="child-name">${escapeHtml(child.display_name)} ${online ? '<span class="online-dot"></span>' : ''}</div>
                        <div class="child-meta">${devs.length} device(s) &middot; ${escapeHtml(child.email)}</div>
                    </div>
                    <span class="chevron">&rsaquo;</span>
                </a>`;
            }).join('');
        }

        // Map markers
        map.eachLayer(layer => {
            if (layer instanceof L.Marker || layer instanceof L.Circle) map.removeLayer(layer);
        });
        
        const bounds = [];
        for (const dev of devices) {
            const locs = await fetchWithAuth(`/api/parent/locations/${dev.device_id}?limit=1`);
            const locData = await locs.json();
            if (locData.length > 0) {
                const loc = locData[0];
                L.marker([loc.latitude, loc.longitude])
                    .addTo(map)
                    .bindPopup(`<b>${escapeHtml(dev.device_name || dev.device_id)}</b><br>${loc.latitude.toFixed(4)}, ${loc.longitude.toFixed(4)}`);
                bounds.push([loc.latitude, loc.longitude]);
            }
        }
        if (bounds.length > 0) map.fitBounds(bounds, { padding: [50, 50] });

        // Sidebar
        document.getElementById('deviceCount').textContent = `${devices.filter(d => isOnline(d.last_seen)).length} online`;
        
        const deviceList = document.getElementById('deviceListSidebar');
        if (devices && devices.length > 0) {
            deviceList.innerHTML = devices.map(d => {
                const online = isOnline(d.last_seen);
                return `<a class="sidebar-item" href="/device/${d.device_id}" style="text-decoration:none;display:flex;align-items:center;gap:8px;">
                    <span class="status-dot ${online ? 'online' : 'offline'}"></span>
                    ${escapeHtml(d.device_name || d.device_id)}
                </a>`;
            }).join('');
        } else {
            deviceList.innerHTML = `<div style="padding:10px 14px;color:rgba(255,255,255,0.4);font-size:13px;">No devices paired</div>`;
        }

        const childList = document.getElementById('childList');
        if (stats.children && stats.children.length > 0) {
            childList.innerHTML = stats.children.map(c => {
                const child = c.child;
                const deviceId = c.devices?.[0]?.device_id || '';
                return `<a class="sidebar-item" href="${deviceId ? '/device/'+deviceId : '#'}" style="text-decoration:none;display:flex;align-items:center;gap:8px;">
                    <span class="status-dot ${isOnline(c.devices?.[0]?.last_seen) ? 'online' : 'offline'}"></span>
                    ${escapeHtml(child.display_name)}
                </a>`;
            }).join('');
        } else {
            childList.innerHTML = `<div style="padding:10px 14px;color:rgba(255,255,255,0.4);font-size:13px;">No child profiles</div>`;
        }

        // Recent activity
        loadRecentActivity('all');

    } catch (err) {
        console.error('Dashboard error:', err);
    }
}

// ─── Recent Activity ──────────────────────────────────────────────────────

async function loadRecentActivity(tab) {
    const container = document.getElementById('recentActivity');
    container.innerHTML = renderSkeleton(5);

    try {
        const devicesRes = await fetchWithAuth('/api/parent/devices');
        const devices = await devicesRes.json();
        
        if (!devices.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">📡</div>
                    <div class="empty-state-title">No devices reporting</div>
                    <div class="empty-state-text">Activity will appear here once a child's device starts sending data.</div>
                </div>`;
            return;
        }

        let allActivities = [];

        if (tab === 'all' || tab === 'locations') {
            for (const dev of devices.slice(0, 3)) {
                const res = await fetchWithAuth(`/api/parent/locations/${dev.device_id}?limit=5`);
                const locs = await res.json();
                locs.forEach(l => allActivities.push({
                    type: 'location', device: dev, data: l,
                    time: l.timestamp,
                    html: `<strong>📍 Location</strong> ${l.latitude.toFixed(4)}, ${l.longitude.toFixed(4)}`
                }));
            }
        }

        if (tab === 'all' || tab === 'screentime') {
            for (const dev of devices.slice(0, 3)) {
                const res = await fetchWithAuth(`/api/parent/screentime/${dev.device_id}?days=1`);
                const st = await res.json();
                if (st.length > 0) allActivities.push({
                    type: 'screentime', device: dev, data: st[0],
                    time: Date.now(),
                    html: `<strong>📱 Screen Time</strong> ${st[0].total_minutes} min, ${st[0].unlocks} unlocks`
                });
            }
        }

        if (tab === 'all') {
            for (const dev of devices.slice(0, 3)) {
                const res = await fetchWithAuth(`/api/parent/activity/${dev.device_id}?limit=5`);
                const acts = await res.json();
                acts.forEach(a => allActivities.push({
                    type: 'activity', device: dev, data: a,
                    time: a.timestamp,
                    html: `<strong>▶ ${escapeHtml(a.app_name || a.activity_type)}</strong> ${escapeHtml(a.package_name || '')}`
                }));
            }
        }

        if (tab === 'sms') {
            for (const dev of devices.slice(0, 3)) {
                const res = await fetchWithAuth(`/api/parent/sms/${dev.device_id}?limit=10`);
                const sms = await res.json();
                sms.forEach(s => allActivities.push({
                    type: 'sms', device: dev, data: s, time: s.date,
                    html: `<strong>💬 SMS ${s.type === 2 ? 'Sent' : 'Received'}</strong> ${escapeHtml(s.address)}: ${escapeHtml((s.body || '').substring(0, 60))}`
                }));
            }
        }

        if (tab === 'calls') {
            for (const dev of devices.slice(0, 3)) {
                const res = await fetchWithAuth(`/api/parent/calls/${dev.device_id}?limit=10`);
                const calls = await res.json();
                calls.forEach(c => allActivities.push({
                    type: 'call', device: dev, data: c, time: c.date,
                    html: `<strong>📞 ${['','Incoming','Outgoing','Missed'][c.type] || 'Call'}</strong> ${escapeHtml(c.number)} ${c.name ? '('+escapeHtml(c.name)+')' : ''}`
                }));
            }
        }

        if (tab === 'apps') {
            for (const dev of devices.slice(0, 3)) {
                const res = await fetchWithAuth(`/api/parent/apps/${dev.device_id}`);
                const apps = await res.json();
                apps.slice(0, 10).forEach(a => allActivities.push({
                    type: 'app', device: dev, data: a, time: Date.now(),
                    html: `<strong>📦 ${escapeHtml(a.app_name)}</strong> ${escapeHtml(a.package_name)}`
                }));
            }
        }

        allActivities.sort((a, b) => (b.time || 0) - (a.time || 0));
        allActivities = allActivities.slice(0, 30);

        container.innerHTML = allActivities.length === 0
            ? `<div class="empty-state">
                <div class="empty-state-icon">📭</div>
                <div class="empty-state-title">No activity yet</div>
                <div class="empty-state-text">Data will appear once devices start reporting.</div>
            </div>`
            : allActivities.map(a => `
                <div class="activity-item">
                    ${a.html}
                    <span class="device-tag">${escapeHtml(a.device?.device_name || a.device?.device_id || '')}</span>
                    <span class="time">${formatTime(a.time)}</span>
                </div>
            `).join('');

    } catch (err) {
        console.error('Activity error:', err);
        container.innerHTML = '<div class="loading">Error loading activity</div>';
    }
}

// ─── Child Detail ─────────────────────────────────────────────────────────

let selectedChildId = null;
let selectedDeviceId = null;

async function showChild(childId) {
    selectedChildId = childId;
    document.getElementById('overviewSection').classList.add('hidden');
    document.getElementById('childSection').classList.remove('hidden');
    document.getElementById('childNameHeader').textContent = 'Loading...';
    
    // Close mobile sidebar
    document.getElementById('sidebar').classList.remove('mobile-open');
    document.getElementById('sidebarOverlay').classList.remove('open');

    const statsRes = await fetchWithAuth('/api/parent/stats');
    const stats = await statsRes.json();
    const childData = stats.children?.find(c => c.child?.id === childId);
    if (!childData) return;

    const child = childData.child;
    const devices = childData.devices || [];
    selectedDeviceId = devices[0]?.device_id;

    document.getElementById('childNameHeader').textContent = child.display_name;
    document.querySelector('.sidebar-item.active')?.classList.remove('active');
    const sidebarItems = document.querySelectorAll('.sidebar-item');
    sidebarItems.forEach(item => {
        if (item.textContent.trim().includes(child.display_name)) {
            item.classList.add('active');
        }
    });

    // Reset child tab to activity
    document.querySelectorAll('#childTabBar .tab').forEach(t => t.classList.remove('active'));
    document.querySelector('#childTabBar .tab[data-child-tab="activity"]').classList.add('active');

    await loadChildDetail(selectedDeviceId);
}

function showOverview() {
    document.getElementById('overviewSection').classList.remove('hidden');
    document.getElementById('childSection').classList.add('hidden');
    document.querySelector('.sidebar-item.active')?.classList.remove('active');
    loadDashboard();
}

async function loadChildDetail(deviceId) {
    if (!deviceId) return;
    currentDeviceId = deviceId;

    try {
        const [locations, screentime, activities, sms, calls, apps, geofences, webhistory, media, restrictions, schedule] = await Promise.all([
            fetchWithAuth(`/api/parent/locations/${deviceId}?limit=200`).then(r => r.json()),
            fetchWithAuth(`/api/parent/screentime/${deviceId}?days=7`).then(r => r.json()),
            fetchWithAuth(`/api/parent/activity/${deviceId}?limit=50`).then(r => r.json()),
            fetchWithAuth(`/api/parent/sms/${deviceId}?limit=20`).then(r => r.json()),
            fetchWithAuth(`/api/parent/calls/${deviceId}?limit=20`).then(r => r.json()),
            fetchWithAuth(`/api/parent/apps/${deviceId}`).then(r => r.json()),
            fetchWithAuth(`/api/parent/geofences/${deviceId}`).then(r => r.json()),
            fetchWithAuth(`/api/parent/webhistory/${deviceId}?limit=50`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/media/${deviceId}`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/restrictions/${deviceId}`).then(r => r.json()).catch(() => []),
            fetchWithAuth(`/api/parent/schedule/${deviceId}`).then(r => r.json()).catch(() => [])
        ]);

        // Cache data for tab switching
        cachedActivities = activities;
        cachedSms = sms;
        cachedCalls = calls;
        cachedApps = apps;
        cachedWebHistory = webhistory;
        cachedMedia = media;

        // Location map
        childMap.eachLayer(layer => {
            if (layer instanceof L.Marker || layer instanceof L.Polyline || layer instanceof L.Circle) childMap.removeLayer(layer);
        });

        if (locations.length > 0) {
            // Sort oldest → newest, drop invalid fixes, split into runs so a
            // long city-to-city jump never draws a straight line across India.
            const valid = locations.filter(isValidLoc)
                .sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
            const runs = splitLocationRuns(valid);
            runs.forEach(run => {
                L.polyline(run.map(l => [l.latitude, l.longitude]), { color: '#667eea', weight: 3, opacity: 0.7 }).addTo(childMap);
            });
            if (valid.length >= 2) {
                childMap.fitBounds(L.polyline(valid.map(v => [v.latitude, v.longitude])).getBounds(), { padding: [30, 30] });
            } else if (valid.length === 1) {
                childMap.setView([valid[0].latitude, valid[0].longitude], 14);
            }
            const last = valid.length ? valid[valid.length - 1] : locations[0];
            L.marker([last.latitude, last.longitude]).addTo(childMap)
                .bindPopup(`Latest: ${formatTime(last.timestamp)}`);
        }

        // Geofences on child map
        geofences.forEach(g => {
            L.circle([g.latitude, g.longitude], {
                color: '#4caf50', fillColor: '#4caf50', fillOpacity: 0.1, radius: g.radius
            }).addTo(childMap).bindPopup(`<b>${escapeHtml(g.name)}</b><br>Radius: ${g.radius}m`);
        });

        // Screen time
        if (screentime.length > 0) {
            document.getElementById('screenTimeValue').textContent = screentime[0].total_minutes || 0;
            drawScreenTimeChart(screentime);
        } else {
            document.getElementById('screenTimeValue').textContent = '0';
        }

        // Battery status
        updateBatteryDisplay(deviceId);

        // Activity log (default tab)
        loadChildActivityLog('activity', activities, sms, calls, apps, webhistory, media);

        // Geofences list
        renderGeofenceList(geofences);

        // App Restrictions
        renderRestrictions(restrictions);

        // Schedule Rules
        renderSchedule(schedule);

        // Populate restriction modal app list
        populateRestrictionApps(apps);

    } catch (err) {
        console.error('Child detail error:', err);
    }
}

async function updateBatteryDisplay(deviceId) {
    try {
        const devicesRes = await fetchWithAuth('/api/parent/devices');
        const devices = await devicesRes.json();
        const dev = devices.find(d => d.device_id === deviceId);
        if (dev) {
            const level = dev.battery_level || 0;
            const charging = dev.is_charging;
            const batteryBar = document.querySelector('#batteryDisplay .battery-level');
            batteryBar.style.width = level + '%';
            if (level < 20) {
                batteryBar.style.background = 'linear-gradient(90deg, #f44336, #ff5722)';
            } else if (level < 50) {
                batteryBar.style.background = 'linear-gradient(90deg, #ff9800, #ffc107)';
            } else {
                batteryBar.style.background = 'linear-gradient(90deg, #4caf50, #8bc34a)';
            }
            document.getElementById('statusInfo').innerHTML = `
                <div style="font-size:24px;font-weight:700;color:white;margin-bottom:4px">${level}%</div>
                <div>${charging ? '⚡ Charging' : '🔋 On Battery'}</div>
                <div style="margin-top:8px;font-size:12px">Last seen: ${formatTime(dev.last_seen)}</div>
            `;
        }
    } catch (e) { /* Silent */ }
}

// ─── Child Activity Tabs ─────────────────────────────────────────────────

function loadChildActivityLog(tab, activities, sms, calls, apps, webhistory, media) {
    const container = document.getElementById('childActivityLog');

    // Use cached data if not provided
    activities = activities || cachedActivities || [];
    sms = sms || cachedSms || [];
    calls = calls || cachedCalls || [];
    apps = apps || cachedApps || [];
    webhistory = webhistory || cachedWebHistory || [];
    media = media || cachedMedia || [];

    let items = [];

    if (tab === 'activity' || tab === 'all') {
        activities.forEach(a => items.push({
            html: `<strong>▶ ${escapeHtml(a.app_name || a.activity_type)}</strong> ${escapeHtml(a.package_name || '')}`,
            time: a.timestamp
        }));
    }
    if (tab === 'sms') {
        sms.forEach(s => items.push({
            html: `<strong>💬 ${s.type === 2 ? 'Sent' : 'Received'}</strong> ${escapeHtml(s.address)}: ${escapeHtml((s.body || '').substring(0, 80))}`,
            time: s.date
        }));
    }
    if (tab === 'calls') {
        calls.forEach(c => items.push({
            html: `<strong>📞 ${['','Incoming','Outgoing','Missed'][c.type] || 'Call'}</strong> ${escapeHtml(c.number)} ${c.duration ? '(' + formatDuration(c.duration) + ')' : ''}`,
            time: c.date
        }));
    }
    if (tab === 'web') {
        if (webhistory.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">🌐</div>
                    <div class="empty-state-title">No web history</div>
                    <div class="empty-state-text">Browser history data will appear here once the device reports it.</div>
                </div>`;
            return;
        }
        webhistory.forEach(w => items.push({
            html: `<strong>🌐 ${escapeHtml(w.title || 'Untitled')}</strong> <a href="${escapeHtml(w.url)}" target="_blank" rel="noopener" style="color:#667eea;text-decoration:none;font-size:12px">${escapeHtml((w.url || '').substring(0, 60))}</a>`,
            time: w.timestamp
        }));
    }
    if (tab === 'apps-installed') {
        apps.forEach(a => items.push({
            html: `<strong>📦 ${escapeHtml(a.app_name)}</strong> ${escapeHtml(a.package_name)}`,
            time: 0
        }));
    }
    if (tab === 'media') {
        if (media.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">📸</div>
                    <div class="empty-state-title">No media captured</div>
                    <div class="empty-state-text">Screenshots and captured media will appear here.</div>
                </div>`;
            return;
        }
        container.innerHTML = `<div class="media-grid">${media.map(m => `
            <div class="media-item" onclick="openLightbox('/api/files/${m.id}')">
                <img src="/api/files/${m.id}" alt="${escapeHtml(m.media_type || 'media')}" loading="lazy"
                     onerror="this.parentElement.innerHTML='<div style=&quot;display:flex;align-items:center;justify-content:center;height:100%;font-size:24px&quot;>📎</div>'">
                <span class="media-type-badge">${escapeHtml(m.media_type || 'file')}</span>
                <div class="media-overlay">${formatTime(m.timestamp)}</div>
            </div>
        `).join('')}</div>`;
        return;
    }

    items.sort((a, b) => (b.time || 0) - (a.time || 0));
    items = items.slice(0, 100);

    container.innerHTML = items.length === 0
        ? `<div class="empty-state">
            <div class="empty-state-icon">📭</div>
            <div class="empty-state-title">No data</div>
            <div class="empty-state-text">Data will appear once the device reports it.</div>
        </div>`
        : items.map(i => `
            <div class="activity-item">
                ${i.html}
                ${i.time ? `<span class="time">${formatTime(i.time)}</span>` : ''}
            </div>
        `).join('');
}

// ─── Geofences ────────────────────────────────────────────────────────────

function renderGeofenceList(geofences) {
    const geoList = document.getElementById('geofenceList');
    geoList.innerHTML = geofences.length === 0
        ? `<div class="empty-state">
            <div class="empty-state-icon">📍</div>
            <div class="empty-state-title">No safe zones</div>
            <div class="empty-state-text">Create geofences to get alerts when your child enters or leaves specific areas.</div>
            <button class="btn-primary" onclick="showAddGeofence()">Create First Zone</button>
        </div>`
        : geofences.map(g => `
            <div class="geofence-item">
                <span class="geo-icon">📍</span>
                <div class="geo-info">
                    <div class="geo-name">${escapeHtml(g.name)}</div>
                    <div class="geo-coords">${g.latitude.toFixed(4)}, ${g.longitude.toFixed(4)} &middot; ${g.radius}m radius</div>
                </div>
                <button class="btn-delete" onclick="deleteGeofence('${g.id}')">&times;</button>
            </div>
        `).join('');
}

function showAddGeofence() {
    document.getElementById('geofenceModal').classList.remove('hidden');
    if (childMap.getCenter) {
        const center = childMap.getCenter();
        document.getElementById('geofenceLat').value = center.lat.toFixed(6);
        document.getElementById('geofenceLon').value = center.lng.toFixed(6);
    }
}

async function saveGeofence() {
    const data = {
        name: document.getElementById('geofenceName').value || 'Safe Zone',
        latitude: parseFloat(document.getElementById('geofenceLat').value),
        longitude: parseFloat(document.getElementById('geofenceLon').value),
        radius: parseInt(document.getElementById('geofenceRadius').value) || 500
    };

    const res = await fetchWithAuth(`/api/parent/geofences/${currentDeviceId}`, {
        method: 'POST',
        body: JSON.stringify(data)
    });

    if (res.ok) {
        closeModal();
        showNotification('✅ Geofence Created', `"${data.name}" safe zone added.`);
        loadChildDetail(currentDeviceId);
    }
}

async function deleteGeofence(id) {
    if (!confirm('Delete this geofence?')) return;
    await fetchWithAuth(`/api/parent/geofences/${id}`, { method: 'DELETE' });
    showNotification('🗑️ Geofence Deleted', 'Safe zone removed.');
    loadChildDetail(currentDeviceId);
}

// ─── App Restrictions ─────────────────────────────────────────────────────

function renderRestrictions(restrictions) {
    const container = document.getElementById('restrictionsList');
    if (!restrictions || restrictions.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state-icon">🚫</div>
                <div class="empty-state-title">No app restrictions</div>
                <div class="empty-state-text">Block specific apps or set daily time limits.</div>
                <button class="btn-primary" onclick="showAddRestriction()">Add Restriction</button>
            </div>`;
        return;
    }
    container.innerHTML = restrictions.map(r => `
        <div class="restriction-item">
            <div class="restriction-icon">${r.is_blocked ? '🚫' : '⏱️'}</div>
            <div class="restriction-info">
                <div class="restriction-name">${escapeHtml(r.app_name || r.package_name)}</div>
                <div class="restriction-detail">${r.is_blocked ? 'Blocked' : ''}${r.max_minutes_per_day ? ` Limit: ${r.max_minutes_per_day} min/day` : ''}${r.block_start_time ? ` Blocked ${r.block_start_time}–${r.block_end_time || ''}` : ''}</div>
            </div>
            <span class="restriction-status ${r.is_blocked ? 'blocked' : 'limited'}">${r.is_blocked ? 'Blocked' : 'Limited'}</span>
        </div>
    `).join('');
}

function showAddRestriction() {
    document.getElementById('restrictionModal').classList.remove('hidden');
}

function toggleRestrictionTime(action) {
    document.getElementById('restrictionTimeFields').style.display = 
        (action === 'limit' || action === 'schedule') ? 'block' : 'none';
}

function populateRestrictionApps(apps) {
    const select = document.getElementById('restrictionApp');
    select.innerHTML = '<option value="">— Select an app —</option>' + 
        (apps || []).map(a => `<option value="${escapeHtml(a.package_name)}" data-name="${escapeHtml(a.app_name)}">${escapeHtml(a.app_name)} (${escapeHtml(a.package_name)})</option>`).join('');
}

async function saveRestriction() {
    const select = document.getElementById('restrictionApp');
    const action = document.getElementById('restrictionAction').value;
    const startHour = (action === 'schedule' || action === 'limit') ? parseInt(document.getElementById('restrictionStartHour').value) : null;
    const endHour = (action === 'schedule' || action === 'limit') ? parseInt(document.getElementById('restrictionEndHour').value) : null;
    const data = {
        package_name: select.value,
        app_name: select.selectedOptions[0]?.dataset.name || select.value,
        is_blocked: action === 'block',
        max_minutes_per_day: action === 'limit' ? parseInt(document.getElementById('restrictionLimit').value) || 0 : 0,
        block_start_time: startHour != null ? `${String(startHour).padStart(2, '0')}:00` : null,
        block_end_time: endHour != null ? `${String(endHour).padStart(2, '0')}:00` : null
    };

    if (!data.package_name) {
        showNotification('⚠️ Error', 'Please select an app.');
        return;
    }

    const res = await fetchWithAuth(`/api/parent/restrictions/${currentDeviceId}`, {
        method: 'POST',
        body: JSON.stringify(data)
    });

    if (res.ok) {
        closeModal();
        showNotification('✅ Restriction Added', `Rule set for ${data.app_name || data.package_name}.`);
        loadChildDetail(currentDeviceId);
    }
}

// ─── Schedule Rules ───────────────────────────────────────────────────────

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function renderSchedule(schedule) {
    const container = document.getElementById('scheduleList');
    if (!schedule || schedule.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state-icon">📅</div>
                <div class="empty-state-title">No schedule rules</div>
                <div class="empty-state-text">Set allowed usage hours for each day of the week.</div>
                <button class="btn-primary" onclick="showAddSchedule()">Add Schedule</button>
            </div>`;
        return;
    }

    // Build a week grid
    let gridHtml = '<div class="schedule-grid">';
    for (let d = 0; d < 7; d++) {
        const rule = schedule.find(s => s.day_of_week === d);
        const active = rule ? ' active' : '';
        gridHtml += `<div class="schedule-day${active}">
            <span class="day-name">${DAY_NAMES[d]}</span>
            <span class="day-time">${rule ? `${rule.start_time || '—'}-${rule.end_time || ''}` : 'No rule'}</span>
        </div>`;
    }
    gridHtml += '</div>';

    // List view
    gridHtml += schedule.map(s => `
        <div class="schedule-rule">
            <div style="flex:1">
                <div style="color:white;font-weight:500;font-size:14px">${DAY_NAMES[s.day_of_week] || 'Day ' + s.day_of_week}</div>
                <div style="color:rgba(255,255,255,0.4);font-size:12px">${s.is_block_time ? 'Blocked' : 'Allowed'}: ${s.start_time || '?'} – ${s.end_time || '?'}</div>
            </div>
        </div>
    `).join('');

    container.innerHTML = gridHtml;
}

function showAddSchedule() {
    document.getElementById('scheduleModal').classList.remove('hidden');
}

async function saveSchedule() {
    const startHour = parseInt(document.getElementById('scheduleStartHour').value) || 0;
    const endHour = parseInt(document.getElementById('scheduleEndHour').value) || 23;
    const data = {
        day_of_week: parseInt(document.getElementById('scheduleDay').value),
        start_time: `${String(startHour).padStart(2, '0')}:00`,
        end_time: `${String(endHour).padStart(2, '0')}:00`,
        is_block_time: true
    };

    const res = await fetchWithAuth(`/api/parent/schedule/${currentDeviceId}`, {
        method: 'POST',
        body: JSON.stringify(data)
    });

    if (res.ok) {
        closeModal();
        showNotification('✅ Schedule Added', `Rule set for ${DAY_NAMES[data.day_of_week]}.`);
        loadChildDetail(currentDeviceId);
    }
}

// ─── Pairing ──────────────────────────────────────────────────────────────

function showAddChild() {
    document.getElementById('addChildModal').classList.remove('hidden');
    generatePairingCode();
    loadPendingPairingsInModal();
}

async function generatePairingCode() {
    const res = await fetchWithAuth('/api/pairing/generate', { method: 'POST' });
    const data = await res.json();
    document.getElementById('pairingCodeDisplay').querySelector('.pairing-code').textContent = data.pairing_code;
}

function copyPairingCode() {
    const code = document.getElementById('pairingCodeDisplay').querySelector('.pairing-code').textContent;
    navigator.clipboard.writeText(code);
    showNotification('📋 Copied!', 'Pairing code copied to clipboard');
}

async function checkPendingPairings() {
    try {
        const res = await fetchWithAuth('/api/pairing/pending');
        const pairings = await res.json();
        const badge = document.getElementById('navPairingBadge');
        const count = document.getElementById('pairingBadgeCount');
        const section = document.getElementById('pendingPairingsSidebar');
        const list = document.getElementById('pendingPairingsListSidebar');

        if (pairings && pairings.length > 0) {
            badge.style.display = '';
            count.textContent = pairings.length;
            section.style.display = '';
            list.innerHTML = pairings.map(p => `
                <div class="pairing-request">
                    <div class="pr-info">
                        <div class="pr-email">${escapeHtml(p.child_email || p.child_name || 'Unknown')}</div>
                        <div class="pr-code">Code: ${escapeHtml(p.pairing_code || '—')}</div>
                    </div>
                    <div class="pr-actions">
                        <button class="btn-approve" onclick="approvePairing('${p.id}')">✓</button>
                    </div>
                </div>
            `).join('');
        } else {
            badge.style.display = 'none';
            section.style.display = 'none';
        }
    } catch (e) { /* Silent */ }
}

async function loadPendingPairingsInModal() {
    try {
        const res = await fetchWithAuth('/api/pairing/pending');
        const pairings = await res.json();
        const container = document.getElementById('pendingPairingsInModal');
        if (pairings && pairings.length > 0) {
            container.innerHTML = `<h3 style="font-size:13px;color:rgba(255,255,255,0.6);margin-bottom:12px;">Pending Approvals</h3>` +
                pairings.map(p => `
                    <div class="pairing-request">
                        <div class="pr-info">
                            <div class="pr-email">${escapeHtml(p.child_email || p.child_name || 'Unknown')}</div>
                            <div class="pr-code">Code: ${escapeHtml(p.pairing_code || '—')}</div>
                        </div>
                        <div class="pr-actions">
                            <button class="btn-approve" onclick="approvePairing('${p.id}')">Approve</button>
                        </div>
                    </div>
                `).join('');
        } else {
            container.innerHTML = '';
        }
    } catch (e) { /* Silent */ }
}

async function approvePairing(id) {
    const res = await fetchWithAuth(`/api/pairing/approve/${id}`, { method: 'POST' });
    if (res.ok) {
        showNotification('✅ Approved', 'Child device paired successfully.');
        checkPendingPairings();
        loadPendingPairingsInModal();
        loadDashboard();
    } else {
        showNotification('❌ Error', 'Failed to approve pairing.');
    }
}

function showPendingPairings() {
    showAddChild();
}

// ─── Media Lightbox ───────────────────────────────────────────────────────

function openLightbox(src) {
    const lb = document.getElementById('lightbox');
    const img = document.getElementById('lightboxImg');
    img.src = src;
    lb.classList.add('open');
}

function closeLightbox() {
    document.getElementById('lightbox').classList.remove('open');
}

// ─── Remote Commands ──────────────────────────────────────────────────────

async function sendRemoteCommand(command) {
    if (!currentDeviceId) return;

    const res = await fetchWithAuth(`/api/parent/commands/${currentDeviceId}`, {
        method: 'POST',
        body: JSON.stringify({ command, params: { duration: 30 } })
    });

    if (res.ok) {
        showNotification('📤 Command Sent', `${command} sent to device`);
    }
}

// ─── Charts ──────────────────────────────────────────────────────────────

function drawScreenTimeChart(data) {
    const canvas = document.getElementById('screenTimeChart');
    const tooltip = document.getElementById('chartTooltip');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    
    // Handle high-DPI displays
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * (window.devicePixelRatio || 1);
    canvas.height = rect.height * (window.devicePixelRatio || 1);
    ctx.scale(window.devicePixelRatio || 1, window.devicePixelRatio || 1);
    
    const width = rect.width;
    const height = rect.height;
    
    ctx.clearRect(0, 0, width, height);
    
    if (data.length === 0) {
        canvas._chartBars = [];
        if (tooltip) tooltip.classList.add('hidden');
        return;
    }
    
    const reversed = [...data].reverse();
    const max = Math.max(...reversed.map(d => d.total_minutes), 60);
    const padding = { top: 10, right: 20, bottom: 30, left: 40 };
    const chartWidth = width - padding.left - padding.right;
    const chartHeight = height - padding.top - padding.bottom;
    const barWidth = Math.min(36, (chartWidth - (reversed.length - 1) * 6) / reversed.length);
    const totalBarsWidth = reversed.length * barWidth + (reversed.length - 1) * 6;
    const startX = padding.left + (chartWidth - totalBarsWidth) / 2;

    // Draw horizontal grid lines
    ctx.strokeStyle = 'rgba(255,255,255,0.05)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
        const y = padding.top + (chartHeight / 4) * i;
        ctx.beginPath();
        ctx.moveTo(padding.left, y);
        ctx.lineTo(width - padding.right, y);
        ctx.stroke();
    }

    // Draw Y-axis labels
    ctx.fillStyle = 'rgba(255,255,255,0.25)';
    ctx.font = '10px Inter, sans-serif';
    ctx.textAlign = 'right';
    for (let i = 0; i <= 4; i++) {
        const y = padding.top + (chartHeight / 4) * i;
        const val = Math.round(max - (max / 4) * i);
        ctx.fillText(val + 'm', padding.left - 6, y + 3);
    }
    
    // Draw bars
    const bars = [];
    reversed.forEach((d, i) => {
        const x = startX + i * (barWidth + 6);
        const barHeight = (d.total_minutes / max) * chartHeight;
        const y = padding.top + chartHeight - barHeight;
        
        const gradient = ctx.createLinearGradient(x, y, x, padding.top + chartHeight);
        gradient.addColorStop(0, '#667eea');
        gradient.addColorStop(1, '#764ba2');
        
        ctx.fillStyle = gradient;
        ctx.beginPath();
        ctx.roundRect(x, y, barWidth, barHeight, 4);
        ctx.fill();

        // Value label on top of bar
        if (d.total_minutes > 0) {
            ctx.fillStyle = 'rgba(255,255,255,0.5)';
            ctx.font = '9px Inter, sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText(d.total_minutes, x + barWidth / 2, y - 4);
        }

        bars.push({ x, y, width: barWidth, height: barHeight, data: d });
    });
    canvas._chartBars = bars;

    // Day labels below chart
    const labelsContainer = document.getElementById('chartLabels');
    if (labelsContainer) {
        const dayLabels = reversed.map(d => {
            if (d.date) {
                const dt = new Date(d.date);
                return dt.toLocaleDateString('en', { weekday: 'short' });
            }
            return '';
        });
        labelsContainer.innerHTML = dayLabels.map(l => `<span>${l}</span>`).join('');
    }

    // Attach hover listeners once
    if (!canvas._chartEventsAdded && tooltip) {
        canvas._chartEventsAdded = true;

        canvas.addEventListener('mousemove', (e) => {
            const r = canvas.getBoundingClientRect();
            const mx = e.clientX - r.left;
            const my = e.clientY - r.top;
            const hit = (canvas._chartBars || []).find(b =>
                mx >= b.x && mx <= b.x + b.width &&
                my >= b.y && my <= b.y + b.height
            );
            if (!hit) {
                tooltip.classList.add('hidden');
                return;
            }
            const d = hit.data;
            const topApp = d.app_usage && Object.keys(d.app_usage).length > 0
                ? Object.entries(d.app_usage)
                    .sort((a, b) => b[1] - a[1])[0]
                : null;
            const topAppText = topApp
                ? `<div>${escapeHtml(topApp[0])}: ${topApp[1]}m</div>`
                : '';
            const dateStr = d.date
                ? new Date(d.date).toLocaleDateString('en', { month: 'short', day: 'numeric' })
                : '';
            tooltip.innerHTML = `
                <div class="tooltip-date">${escapeHtml(dateStr)}</div>
                <div class="tooltip-minutes">${d.total_minutes || 0} minutes</div>
                <div>${d.unlocks || 0} unlocks</div>
                ${topAppText}
            `;
            tooltip.classList.remove('hidden');
            const tipRect = tooltip.getBoundingClientRect();
            let left = e.pageX - tipRect.width / 2;
            let top = e.pageY - tipRect.height - 12;
            if (left < 8) left = 8;
            tooltip.style.left = left + 'px';
            tooltip.style.top = top + 'px';
        });

        canvas.addEventListener('mouseleave', () => {
            tooltip.classList.add('hidden');
        });
    }
}

// ─── Modals ──────────────────────────────────────────────────────────────

function closeModal() {
    document.querySelectorAll('.modal').forEach(m => m.classList.add('hidden'));
}

// ─── Helpers ──────────────────────────────────────────────────────────────

async function fetchWithAuth(url, options = {}) {
    if (!options.headers) options.headers = {};
    options.headers['Authorization'] = `Bearer ${TOKEN}`;
    options.headers['Content-Type'] = 'application/json';
    
    const res = await fetch(url, options);
    if (res.status === 401 || res.status === 403) {
        const refresh = localStorage.getItem('kidguard_refresh');
        if (refresh) {
            const refreshRes = await fetch('/api/auth/refresh', {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${refresh}` }
            });
            if (refreshRes.ok) {
                const data = await refreshRes.json();
                TOKEN = data.token;
                localStorage.setItem('kidguard_token', TOKEN);
                options.headers['Authorization'] = `Bearer ${TOKEN}`;
                const retryRes = await fetch(url, options);
                if (retryRes.ok) return retryRes;
            }
        }
        localStorage.removeItem('kidguard_token');
        localStorage.removeItem('kidguard_refresh');
        window.location.href = '/';
        return res;
    }
    return res;
}

// ─── Geo trail helpers ──────────────────────────────────────────────────
// Never draw a straight line between two far-apart fixes (e.g. a device seen
// in both Mumbai and Odisha). Drop invalid points, sort oldest→newest, and
// split the trail into runs separated by >25 km or >2 days.

function isValidLoc(l) {
    const la = Number(l.latitude), lo = Number(l.longitude);
    return Number.isFinite(la) && Number.isFinite(lo)
        && la >= -90 && la <= 90 && lo >= -180 && lo <= 180
        && !(Math.abs(la) < 0.0001 && Math.abs(lo) < 0.0001);
}

function haversineKm(a, b) {
    const R = 6371;
    const toRad = d => d * Math.PI / 180;
    const dLat = toRad(b[0] - a[0]);
    const dLon = toRad(b[1] - a[1]);
    const h = Math.sin(dLat / 2) ** 2
        + Math.cos(toRad(a[0])) * Math.cos(toRad(b[0])) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
}

function splitLocationRuns(locs) {
    const RUN_GAP_KM = 25;
    const RUN_GAP_DAYS = 2;
    const runs = [];
    let cur = [];
    for (const l of locs) {
        if (cur.length) {
            const prev = cur[cur.length - 1];
            const gapKm = haversineKm([prev.latitude, prev.longitude], [l.latitude, l.longitude]);
            const gapDays = ((l.timestamp || 0) - (prev.timestamp || 0)) / 86400000;
            if (gapKm > RUN_GAP_KM || gapDays > RUN_GAP_DAYS) {
                if (cur.length >= 2) runs.push(cur);
                cur = [];
            }
        }
        cur.push(l);
    }
    if (cur.length >= 2) runs.push(cur);
    return runs;
}

function formatTime(ts) {
    if (!ts) return 'N/A';
    const d = new Date(ts);
    return d.toLocaleString(undefined, {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: true
    });
}

function formatDuration(seconds) {
    if (!seconds) return '0s';
    if (seconds < 60) return seconds + 's';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    if (m < 60) return `${m}m ${s}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
}

function isOnline(lastSeen) {
    if (!lastSeen) return false;
    return (Date.now() - lastSeen) < 1500000; // 25 min keep-alive window
}

function escapeHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function renderSkeleton(rows) {
    let html = '';
    for (let i = 0; i < rows; i++) {
        const w = ['long', 'medium', 'long', 'short', 'medium'][i % 5];
        html += `<div class="skeleton-row"><div style="flex:1"><div class="skeleton skeleton-text ${w}"></div></div></div>`;
    }
    return html;
}

// ─── Tab switching (FIXED) ───────────────────────────────────────────────

document.addEventListener('click', function(e) {
    const tab = e.target.closest('.tab');
    if (!tab) return;

    // Child detail tabs
    if (tab.dataset.childTab) {
        document.querySelectorAll('#childTabBar .tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        loadChildActivityLog(tab.dataset.childTab);
        return;
    }

    // Overview tabs
    if (tab.dataset.tab) {
        document.querySelectorAll('#overviewTabBar .tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        loadRecentActivity(tab.dataset.tab);
    }
});

// Check pending pairings on load
setTimeout(checkPendingPairings, 2000);
