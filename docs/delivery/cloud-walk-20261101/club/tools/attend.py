import sys, time; sys.path.insert(0, '/root/sc')
from common import *
tok, _ = login('walk-club-lead'); act = int(sys.argv[1]); reason = sys.argv[2]
for p in sys.argv[3:]:
    _, me = login(p)
    ro = cl.http("POST", "/api/club/event-ops/roster", token=tok, json_body={"clubId": 990004, "activityId": act}).get('data') or {}
    row = [r for r in (ro.get('registered') or []) if r.get('memberId') == me['id']]
    ver = row[0].get('version', 0) if row else 0
    r = cl.http("POST", "/api/club/event-ops/attendance/correct", token=tok, json_body={"clubId": 990004, "activityId": act, "memberId": me['id'], "arrived": True, "expectedVersion": ver, "reason": reason, "requestId": "walk-att-%s-%d" % (p, int(time.time()*1000))})
    print(p, (r.get('msg') or '')[:60])
