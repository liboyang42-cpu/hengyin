import sys
from rig import *
S = load(); act = S['act']; out = sys.argv[1]
lines = []
def log(*a): lines.append(' '.join(str(x) for x in a)); print(*a)
t, _ = login('walk-player-1')
d = cl.http('GET', '/api/club/lead/team-progress?activityId=%s' % act, token=t).get('data') or {}
log('member header /api/club/lead/team-progress arrived/total:', d.get('arrived'), '/', d.get('total'))
lt, _ = login('e2e-openid-9008')
ro = cl.http("POST", "/api/club/event-ops/roster", token=lt, json_body={"clubId": 7002, "activityId": act}).get('data') or {}
log('roster /api/club/event-ops/roster registered/arrived/noShow:', len(ro.get('registered') or []), len(ro.get('arrived') or []), len(ro.get('noShow') or []))
log('roster names:', [r.get('displayName') or r.get('nickname') for k in ('registered', 'arrived', 'noShow') for r in (ro.get(k) or [])])
v = cl.http('GET', '/api/game/session/view?activityId=%s&perspective=CLUB' % act, token=lt).get('data') or {}
log('recap funnel arrivedPlayers:', ((v.get('club') or {}).get('recap') or {}).get('funnel', {}).get('arrivedPlayers'))
c = cl.http('POST', '/api/club/event-notification/audience-counts', token=lt, json_body={"clubId": 7002, "activityId": act})
log('audience-counts:', J(c))
open(out, 'w').write('\n'.join(lines) + '\n')
