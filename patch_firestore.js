const https = require('https');

const data = JSON.stringify({
    fields: {
        last_seen: { integerValue: String(Date.now()) },
        is_active: { booleanValue: true },
        model: { stringValue: "vivo I2018" },
        battery_level: { integerValue: "100" }
    }
});

const req = https.request("https://firestore.googleapis.com/v1/projects/anonchat-a690b/databases/(default)/documents/devices/2018?currentDocument.exists=true", {
    method: 'PATCH',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': data.length
    }
}, (res) => {
    let body = '';
    res.on('data', chunk => body += chunk);
    res.on('end', () => console.log("PATCH RESPONSE:", res.statusCode, body));
});

req.on('error', e => console.error("Error:", e));
req.write(data);
req.end();
