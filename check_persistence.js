const https = require('https');

// Devices to watch (Firestore document IDs).
const DEVICE_IDS = ['RMX3612', '2018', 'I2018', 'I2018_fc2e'];
const ONLINE_WINDOW_MS = 25 * 60 * 1000; // 25 minutes — matches new server threshold

function fetchJson(url) {
    return new Promise((resolve, reject) => {
        https.get(url, (res) => {
            let body = '';
            res.on('data', (c) => (body += c));
            res.on('end', () => {
                try { resolve(JSON.parse(body)); } catch (_) { resolve(null); }
            });
        }).on('error', reject);
    });
}

async function main() {
    console.log('=== ONLINE-WINDOW CHECK (Firestore heartbeats) ===');
    console.log('Now:', new Date().toLocaleString());

    const base = 'https://firestore.googleapis.com/v1/projects/anonchat-a690b/databases/(default)/documents/devices';
    for (const id of DEVICE_IDS) {
        const doc = await fetchJson(`${base}/${id}`);
        if (!doc || !doc.fields) { console.log(`${id}: NOT FOUND`); continue; }
        const f = doc.fields;
        const lastSeen = f.last_seen ? Number(f.last_seen.integerValue || f.last_seen.doubleValue || 0) : 0;
        const ageMs = Date.now() - lastSeen;
        const ageMin = Math.round(ageMs / 60000);
        const status = ageMs < ONLINE_WINDOW_MS ? 'ONLINE' : 'OFFLINE';
        console.log(
            `${id.padEnd(12)} model=${(f.model?.stringValue || '?').padEnd(12)} ` +
            `battery=${f.battery_level?.integerValue || '?'}% ` +
            `lastSeen=${lastSeen ? new Date(lastSeen).toLocaleString() : '?'} ` +
            `(${ageMin} min ago) => ${status}`
        );
    }
    console.log('Note: dashboard ONLINE uses the Render server Device.last_seen;');
    console.log('this script shows the Firestore side (second sync channel).');
}

main().catch((e) => console.error(e));