const https = require('https');

const url = "https://firestore.googleapis.com/v1/projects/anonchat-a690b/databases/(default)/documents/devices";

https.get(url, (res) => {
    let body = '';
    res.on('data', chunk => body += chunk);
    res.on('end', () => {
        try {
            const data = JSON.parse(body);
            console.log("FIRESTORE DEVICES DOCUMENTS:");
            if (data.documents) {
                data.documents.forEach(doc => {
                    const id = doc.name.split('/').pop();
                    const fields = doc.fields || {};
                    const model = fields.model ? fields.model.stringValue : 'N/A';
                    const lastSeen = fields.last_seen ? (fields.last_seen.integerValue || fields.last_seen.doubleValue) : 'N/A';
                    console.log(`Document ID: ${id} | Model: ${model} | LastSeen: ${lastSeen} (${new Date(Number(lastSeen)).toLocaleString()})`);
                });
            } else {
                console.log("No documents found or error:", JSON.stringify(data));
            }
        } catch (e) {
            console.error("Parse error:", e.message, body);
        }
    });
}).on('error', e => console.error("HTTP error:", e));
