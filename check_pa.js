const https = require('https');

function fetchUrl(url) {
    return new Promise((resolve, reject) => {
        https.get(url, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => resolve(body));
        }).on('error', reject);
    });
}

async function checkPythonAnywhere() {
    console.log("=== CHECKING PYTHONANYWHERE FOR DEVICE RMX3612 ===");
    try {
        const res = await fetchUrl("https://diptiban2021.pythonanywhere.com/api/devices");
        console.log("DEVICES RESPONSE:", res.substring(0, 500));
    } catch (e) {
        console.error("PA Error:", e.message);
    }
}

checkPythonAnywhere();
