"""Round 3 phone rule over HTTP: 活动核销员 roster before / after the 「可见客户手机号」 switch, DB read-back.
No full numbers are logged: phones in the output are reduced to their last 4 digits."""
import sys, time
from rig import *
S = load(); act = S['act']; out = sys.argv[1]; CLUB = 7002; STAFF = 9009
lines = []
def log(*a): lines.append(' '.join(str(x) for x in a)); print(*a)
def tail(p): return ('…' + str(p)[-4:]) if p else p
lead, _ = login('e2e-openid-9008'); staff, _ = login('e2e-openid-9009')
def roster(tok, who):
    r = cl.http('POST', '/api/club/event-ops/roster', token=tok, json_body={'clubId': CLUB, 'activityId': act})
    d = r.get('data') or {}
    rows = (d.get('registered') or []) + (d.get('arrived') or []) + (d.get('noShow') or [])
    log(who, 'roster code', r.get('code'), r.get('msg') if r.get('code') != 200 else '', 'phoneIncluded', d.get('phoneIncluded'),
        'rows', len(rows), 'phones', [tail(x.get('phone')) for x in rows])
def assign(flag, rid):
    body = {'clubId': CLUB, 'targetMemberId': STAFF, 'roleCode': 'EVENT_CHECKIN', 'activityId': act, 'requestId': rid}
    if flag: body['customerPhoneVisible'] = True
    r = cl.http('POST', '/api/club/roles/assign', token=lead, json_body=body)
    log('assign EVENT_CHECKIN customerPhoneVisible=%s ->' % flag, r.get('code'), r.get('msg'), J(r.get('data'))[:200])
    return (r.get('data') or {})
def readback():
    log('club_role_assignment read-back', cl.q('select id,role_code,scope_type,scope_id,status,customer_phone_visible from club_role_assignment where club_id=%s and target_member_id=%s order by id', (CLUB, STAFF)))
roster(lead, '主理人(9008)')
a = assign(False, 'l6-r3-checkin-off-%d' % int(time.time() * 1000)); readback(); roster(staff, '核销员(9009) 开关=否')
r = cl.http('POST', '/api/club/roles/revoke', token=lead, json_body={'clubId': CLUB, 'activityId': act, 'assignmentId': a.get('id'), 'version': a.get('version'), 'reason': 'rig', 'requestId': 'l6-r3-rv-%d' % int(time.time() * 1000)})
log('revoke ->', r.get('code'), r.get('msg'))
assign(True, 'l6-r3-checkin-on-%d' % int(time.time() * 1000)); readback(); roster(staff, '核销员(9009) 开关=是')
r = cl.http('POST', '/api/club/roles/assign', token=lead, json_body={'clubId': CLUB, 'targetMemberId': STAFF, 'roleCode': 'CLUB_OPERATOR', 'customerPhoneVisible': True, 'requestId': 'l6-r3-op-%d' % int(time.time() * 1000)})
log('assign CLUB_OPERATOR with customerPhoneVisible=true ->', r.get('code'), r.get('msg'))
open(out, 'w').write('\n'.join(lines) + '\n')
