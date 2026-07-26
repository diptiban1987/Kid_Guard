let deviceMap;
let polyline;

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

async function loadDeviceDetail() {
    try {
        // Load device info
        const devices = await (await fetch('/api/devices')).json();
        const device = devices.find(d => d.device_id === DEVICE_ID);

        if (device) {
            document.getElementById('deviceInfo').innerHTML = `
                <div class="info-card">
                    <div class="info-label">Device</div>
                    <div class="info-value">${device.device_name || 'Unknown'}</div>
                </div>
                <div class="info-card">
                    <div class="info-label">Manufacturer</div>
                    <div class="info-value">${device.manufacturer || 'N/A'}</div>
                </div>
                <div class="info-card">
                    <div class="info-label">Model</div>
                    <div class="info-value">${device.model || 'N/A'}</div>
                </div>
                <div class="info-card">
                    <div class="info-label">Android Version</div>
                    <div class="info-value">${device.android_version || 'N/A'}</div>
                </div>
                <div class="info-card">
                    <div class="info-label">Last Seen</div>
                    <div class="info-value">${formatTime(device.last_seen)}</div>
                </div>
            `;
        }

        // Load location history
        const locations = await (await fetch(`/api/devices/${DEVICE_ID}/locations?limit=200`)).json();

        deviceMap = L.map('deviceMap').setView([20, 0], 2);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(deviceMap);

        if (locations.length > 0) {
            const points = locations.map(l => [l.latitude, l.longitude]);
            polyline = L.polyline(points, { color: '#667eea', weight: 3 }).addTo(deviceMap);
            deviceMap.fitBounds(polyline.getBounds(), { padding: [50, 50] });

            // Markers for first and last
            const first = locations[locations.length - 1];
            const last = locations[0];
            L.marker([first.latitude, first.longitude])
                .addTo(deviceMap)
                .bindPopup(`First: ${formatTime(first.timestamp)}`);
            L.marker([last.latitude, last.longitude])
                .addTo(deviceMap)
                .bindPopup(`Latest: ${formatTime(last.timestamp)}<br>${last.latitude.toFixed(6)}, ${last.longitude.toFixed(6)}`);
        }

        // Load SMS
        const smsMessages = await (await fetch(`/api/devices/${DEVICE_ID}/sms?limit=100`)).json();
        document.getElementById('deviceSms').innerHTML = smsMessages.length === 0
            ? '<div class="loading">No SMS messages</div>'
            : `<table class="data-table">
                <thead><tr>
                    <th>Type</th><th>Number</th><th>Message</th><th>Date</th>
                </tr></thead>
                <tbody>
                ${smsMessages.map(s => `
                    <tr>
                        <td>${smsTypeName(s.type)}</td>
                        <td>${s.address}</td>
                        <td>${(s.body || '').substring(0, 100)}</td>
                        <td>${formatTime(s.date)}</td>
                    </tr>
                `).join('')}
                </tbody>
            </table>`;

        // Load Calls
        const calls = await (await fetch(`/api/devices/${DEVICE_ID}/calls?limit=100`)).json();
        document.getElementById('deviceCalls').innerHTML = calls.length === 0
            ? '<div class="loading">No call logs</div>'
            : `<table class="data-table">
                <thead><tr>
                    <th>Type</th><th>Number</th><th>Name</th><th>Duration</th><th>Date</th>
                </tr></thead>
                <tbody>
                ${calls.map(c => `
                    <tr>
                        <td>${callTypeName(c.type)}</td>
                        <td>${c.number}</td>
                        <td>${c.name || '-'}</td>
                        <td>${formatDuration(c.duration)}</td>
                        <td>${formatTime(c.date)}</td>
                    </tr>
                `).join('')}
                </tbody>
            </table>`;

        // Load Apps
        const apps = await (await fetch(`/api/devices/${DEVICE_ID}/apps`)).json();
        document.getElementById('deviceApps').innerHTML = apps.length === 0
            ? '<div class="loading">No apps data</div>'
            : `<div class="app-grid">
                ${apps.map(a => `
                    <div class="app-item">
                        <div class="app-name">${a.app_name}</div>
                        <div class="app-package">${a.package_name}</div>
                    </div>
                `).join('')}
            </div>`;

    } catch (err) {
        console.error('Failed to load device details:', err);
    }
}

loadDeviceDetail();
