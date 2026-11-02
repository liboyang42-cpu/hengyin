import sys; sys.path.insert(0, '/root/sc')
from common import *
act = int(sys.argv[1]); groups = [g.split(',') for g in sys.argv[2:]]
for g in groups:
    tok, me = login(g[0])
    r = cl.http("POST", "/api/team/create", token=tok, json_body={"ownerType": 2, "ownerId": act, "maxMembers": 4, "title": "%s队" % g[0][-1]})
    team = (r.get('data') or {}).get('team') or {}
    print(g[0], 'create', (r.get('msg') or '')[:30], team.get('id'), team.get('inviteCode'))
    for p in g[1:]:
        t2, _ = login(p)
        print('  ', p, (cl.http("POST", "/api/team/join", token=t2, json_body={"inviteCode": team.get('inviteCode')}).get('msg') or '')[:40])
