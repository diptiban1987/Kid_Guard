import requests, sqlite3, datetime

r = requests.post('http://192.168.1.5:5000/api/auth/login', json={'email': 'parent@local.com', 'password': 'parent123'})
token = r.json()['token']
headers = {'Authorization': 'Bearer ' + token}

print('=== Activity (latest 3) ===')
r = requests.get('http://192.168.1.5:5000/api/parent/activity/dev_4oc41in7987g?limit=3', headers=headers)
for a in r.json():
    ts = datetime.datetime.fromtimestamp(a['timestamp']/1000)
    atype = a['activity_type']
    aname = a.get('app_name','')
    print(f'  {ts}: {atype} - {aname}')

print('=== Web History ===')
r = requests.get('http://192.168.1.5:5000/api/parent/webhistory/dev_4oc41in7987g?limit=10', headers=headers)
for w in r.json():
    ts = datetime.datetime.fromtimestamp(w['timestamp']/1000)
    print(f'  {ts}: {w["url"]} ({w["title"]})')

print('=== Media ===')
r = requests.get('http://192.168.1.5:5000/api/parent/media/dev_4oc41in7987g', headers=headers)
print(f'  {len(r.json())} files')

print('=== SMS count ===')
r = requests.get('http://192.168.1.5:5000/api/parent/sms/dev_4oc41in7987g?limit=1', headers=headers)
print(f'  {len(r.json())} messages')

print('=== Calls count ===')
r = requests.get('http://192.168.1.5:5000/api/parent/calls/dev_4oc41in7987g?limit=1', headers=headers)
print(f'  {len(r.json())} calls')
