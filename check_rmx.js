const https = require('https');

const devId = "RMX3612";
const baseUrl = `https://firestore.googleapis.com/v1/projects/anonchat-a690b/databases/(default)/documents/devices/${devId}`;

function fetchJson(url) {
    return new Promise((resolve, reject) => {
        https.get(url, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(body)); } catch (e) { resolve(null); }
            });
        }).on('error', reject);
    });
}

async function inspectDevice() {
    console.log("=== INSPECTING REALME DEVICE (RMX3612) IN FIRESTORE ===");
    
    // 1. Device Document Header
    const devDoc = await fetchJson(baseUrl);
    if (devDoc && devDoc.fields) {
        const fields = devDoc.fields;
        const lastSeen = fields.last_seen ? Number(fields.last_seen.integerValue || fields.last_seen.doubleValue) : 0;
        console.log(`Device ID: ${devId}`);
        console.log(`Model: ${fields.model?.stringValue || 'N/A'}`);
        console.log(`Manufacturer: ${fields.manufacturer?.stringValue || 'N/A'}`);
        console.log(`Android Version: ${fields.android_version?.stringValue || 'N/A'}`);
        console.log(`Battery: ${fields.battery_level?.integerValue || fields.battery_level?.doubleValue}% (${fields.is_charging?.booleanValue ? 'Charging' : 'Discharging'})`);
        console.log(`Last Seen: ${new Date(lastSeen).toLocaleString()} (${Math.round((Date.now() - lastSeen)/1000/60)} mins ago)`);
        console.log(`Screen Time Today: ${fields.screen_time_today?.integerValue || 0} mins`);
        console.log(`Unlocks Today: ${fields.unlock_count_today?.integerValue || 0}`);
    } else {
        console.log("Device header not found for ID:", devId);
    }

    // 2. Sub-collections / Consolidated Data
    const dataUrl = `${baseUrl}/data`;
    const dataCol = await fetchJson(dataUrl);
    if (dataCol && dataCol.documents) {
        console.log("\n--- CONSOLIDATED DATA DOCUMENTS ---");
        for (const doc of dataCol.documents) {
            const docId = doc.name.split('/').pop();
            const fields = doc.fields || {};
            const updatedAt = fields.updated_at ? Number(fields.updated_at.integerValue) : 0;
            const count = fields.list ? fields.list.arrayValue?.values?.length || 0 : 0;
            console.log(`Doc: ${docId} | Count: ${count} items | Updated: ${updatedAt ? new Date(updatedAt).toLocaleString() : 'N/A'}`);
        }
    }
}

inspectDevice();
