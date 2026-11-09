import sys
from rig import *
S = load(); act = S['act']; out = sys.argv[1]
lines = []
def log(*a): lines.append(' '.join(str(x) for x in a)); print(*a)
log('cms_activity before:', cl.q("SELECT id,club_id,start_date,end_date,latitude,longitude FROM cms_activity WHERE id=%s", (act,)))
log('ticket gather point:', cl.q("SELECT id,gather_lat,gather_lng FROM oms_ticket WHERE owner_type=2 AND owner_id=%s", (act,)))
for who, lat, lng, label in (('walk-player-2', 31.24272, 121.48839, 'within ~5 m'), ('walk-player-3', 31.26070, 121.48841, '~2 km north')):
    tok, me = login(who)
    body = {"activityId": act, "latitude": lat, "longitude": lng}
    r = cl.http("POST", "/api/club/lead/arrive", token=tok, form=body)
    log('POST /api/club/lead/arrive', who, me.get('id'), label, J(body), '->', J(r))
log('club_lead_checkin rows:', cl.q("SELECT activity_id,member_id,arrived_at FROM club_lead_checkin WHERE activity_id=%s", (act,)))
open(out, 'w').write('\n'.join(lines) + '\n')
