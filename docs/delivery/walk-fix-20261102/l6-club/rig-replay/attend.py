import sys, time
from rig import *
S = load(); act = S['act']
tok, _ = login('e2e-openid-9008')
for p in sys.argv[1:]:
    _, me = login(p)
    ro = cl.http("POST", "/api/club/event-ops/roster", token=tok, json_body={"clubId": 7002, "activityId": act}).get('data') or {}
    row = [r for r in (ro.get('registered') or []) if r.get('memberId') == me['id']]
    ver = row[0].get('correctionVersion', 0) if row else 0
    r = cl.http("POST", "/api/club/event-ops/attendance/correct", token=tok, json_body={"clubId": 7002, "activityId": act, "memberId": me['id'], "arrived": True, "expectedVersion": ver, "reason": "现场到达", "requestId": "l6-att-%s-%d" % (p, int(time.time()*1000))})
    print(p, J(r)[:120])
