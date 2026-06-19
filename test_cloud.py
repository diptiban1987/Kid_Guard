import json, urllib.request

api = 'http://127.0.0.1:5000/api'

# Test register
data = json.dumps({'email': 'parent@test.com', 'password': 'test123', 'display_name': 'Test Parent', 'role': 'parent'}).encode()
req = urllib.request.Request(api + '/auth/register', data=data, headers={'Content-Type': 'application/json'})
try:
    resp = urllib.request.urlopen(req)
    result = json.loads(resp.read())
    print(f'Register: {resp.status} OK')
    print(f'  User: {result["user"]["email"]} Role: {result["user"]["role"]}')
    TOKEN = result['token']
except urllib.error.HTTPError as e:
    body = e.read().decode()
    if 'Email already registered' in body:
        print('Already registered, logging in...')
        TOKEN = None
    else:
        print(f'Register error: {e.code} {body}')
        exit(1)

# Test login
data2 = json.dumps({'email': 'parent@test.com', 'password': 'test123'}).encode()
req2 = urllib.request.Request(api + '/auth/login', data=data2, headers={'Content-Type': 'application/json'})
resp2 = urllib.request.urlopen(req2)
result2 = json.loads(resp2.read())
TOKEN = result2['token']
print(f'Login: {resp2.status} OK')
print(f'  Token: {TOKEN[:30]}...')

# Test pairing code generation
req3 = urllib.request.Request(api + '/pairing/generate', data=b'{}',
                              headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN})
resp3 = urllib.request.urlopen(req3)
pair_result = json.loads(resp3.read())
print(f'Pairing: {resp3.status} OK')
print(f'  Code: {pair_result["pairing_code"]}')

# Test dashboard stats
req4 = urllib.request.Request(api + '/parent/stats',
                              headers={'Authorization': 'Bearer ' + TOKEN})
resp4 = urllib.request.urlopen(req4)
stats = json.loads(resp4.read())
print(f'Stats: {resp4.status} OK')
print(f'  Children: {stats.get("children", [])}')

# Test device registration
data3 = json.dumps({'device_id': 'test-device-001', 'device_name': 'Test Phone', 'manufacturer': 'Google', 'model': 'Pixel 8'}).encode()
req5 = urllib.request.Request(api + '/device/register', data=data3,
                              headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN})
resp5 = urllib.request.urlopen(req5)
print(f'Device Register: {resp5.status} OK')

# Test bulk report
bulk_data = json.dumps({
    'device_id': 'test-device-001',
    'location': {'latitude': 37.7749, 'longitude': -122.4194, 'accuracy': 10, 'provider': 'gps'},
    'battery': {'level': 85, 'is_charging': True, 'temperature': 30.5},
    'activities': [{'activity_type': 'screen_on', 'timestamp': 1700000000000}],
    'screentime': {'total_minutes': 120, 'unlocks': 45, 'date': '2026-05-26'}
}).encode()
req6 = urllib.request.Request(api + '/report/bulk', data=bulk_data,
                              headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN})
resp6 = urllib.request.urlopen(req6)
print(f'Bulk Report: {resp6.status} OK')

# Test retrieving devices
req7 = urllib.request.Request(api + '/parent/devices',
                              headers={'Authorization': 'Bearer ' + TOKEN})
resp7 = urllib.request.urlopen(req7)
devices = json.loads(resp7.read())
print(f'Devices: {resp7.status} OK')
print(f'  Found: {len(devices)} devices')

print('\n=== ALL CLOUD SERVER TESTS PASSED ===')
