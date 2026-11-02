import sys; sys.path.insert(0, '/root/sc')
from common import *
act, tid = 16, 990017
nodes = cl.q("SELECT id,chapter_id,checkin_code,merchant_member_id,name FROM cms_topic_node WHERE topic_id=%s ORDER BY chapter_id", (tid,))
MPH = {9002: 'e2e-openid-9002', 9005: 'e2e-openid-9005', 9006: 'e2e-openid-9006', 9007: 'e2e-openid-9007'}
plan = {'walk-player-1': 4, 'walk-player-2': 2, 'walk-player-3': 1}
for p, n in plan.items():
    tok, me = login(p)
    reg = cl.q("SELECT id FROM cms_registration WHERE member_id=%s AND owner_type=2 AND owner_id=%s", (me['id'], act))[0][0]
    for nid, ch, code, mm, name in nodes[:n]:
        mtok, _ = login(MPH[mm])
        r1 = cl.http("POST", "/api/play/checkin", token=tok, form={"activityId": act, "code": code, "topicId": tid})
        dyn = cl.http("POST", "/api/verify/dyncode/issue", token=tok, form={"registrationId": reg, "type": "activity"})
        r2 = cl.http("POST", "/api/registration/scan_qr_code", token=mtok, form={"type": "activity", "code": (dyn.get('data') or {}).get('code')})
        print(p, name, 'checkin:', (r1.get('msg') or '')[:30], '| scan:', (r2.get('msg') or '')[:60])
# perk failure: player-3 at 旧页书房, but the merchant scans the SAME code twice (second scan at counter)
tok, me = login('walk-player-3'); reg = cl.q("SELECT id FROM cms_registration WHERE member_id=%s AND owner_type=2 AND owner_id=%s", (me['id'], act))[0][0]
nid, ch, code, mm, name = nodes[1]
mtok, _ = login(MPH[mm])
print('p3 checkin', name, (cl.http("POST", "/api/play/checkin", token=tok, form={"activityId": act, "code": code, "topicId": tid}).get('msg') or '')[:40])
dyn = cl.http("POST", "/api/verify/dyncode/issue", token=tok, form={"registrationId": reg, "type": "activity"})
c = (dyn.get('data') or {}).get('code')
print('scan1', J(cl.http("POST", "/api/registration/scan_qr_code", token=mtok, form={"type": "activity", "code": c}))[:200])
print('scan2 same code', J(cl.http("POST", "/api/registration/scan_qr_code", token=mtok, form={"type": "activity", "code": c}))[:200])
# wrong-store: 小满花艺 merchant scans player-2 who has no remaining chapter there? player-2 did ch1-2; scan at 四点钟面包 by wrong merchant code reuse
tok2, me2 = login('walk-player-2'); reg2 = cl.q("SELECT id FROM cms_registration WHERE member_id=%s AND owner_type=2 AND owner_id=%s", (me2['id'], act))[0][0]
dyn2 = cl.http("POST", "/api/verify/dyncode/issue", token=tok2, form={"registrationId": reg2, "type": "activity"})
mtok4, _ = login('e2e-openid-9007')
print('player-2 at 四点钟面包 without check-in:', J(cl.http("POST", "/api/registration/scan_qr_code", token=mtok4, form={"type": "activity", "code": (dyn2.get('data') or {}).get('code')}))[:240])
