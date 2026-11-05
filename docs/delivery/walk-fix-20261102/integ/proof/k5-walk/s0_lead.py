"""Step 0. A fresh lead (openid k5-lead, throwaway fictional name 沈若舟) becomes a 主理人 and creates a club through the
real entries (become-leader, publisher identity, club/create). Cover: an approved media asset fixture (no OSS in the rig)."""
from common import *
tok, me = login('k5-lead'); mid = me.get('id'); log('lead login', {'member': mid}); save(lead=mid, lead_openid='k5-lead')
http('become-leader', 'POST', '/api/club/become-leader', token=tok, json_body={
    "leaderName": "沈若舟", "phone": "13800000005", "identity": "个人", "coFounders": "", "hasExperience": 1, "experience": "带过三次城市定向"})
st = http('publisher identity status', 'POST', '/api/publisher/identity/status', token=tok, json_body={})
if not (st.get('data') or {}).get('registered'):
    http('publisher identity', 'POST', '/api/publisher/identity', token=tok, json_body={"realName": "沈若舟", "idCard": idcard("11010119900307001"), "consent": True, "source": "topic_publish"})
key = "upload/k5-club-cover-%d.jpg" % mid
ex("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,create_time,update_time) SELECT %s,%s,'IMAGE','APPROVED',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_media_asset WHERE member_id=%s AND object_key=%s)", (mid, key, mid, key))
cover = "media-asset:%d" % cl.q("SELECT id FROM app_media_asset WHERE member_id=%s AND object_key=%s", (mid, key))[0][0]
note('fixture: approved cover media asset for the lead (same as chain_platform.seed_approved_cover) -> ' + cover)
save(cover=cover)
if not state().get('club'):
    r = http('club create', 'POST', '/api/club/create', token=tok, json_body={"name": "外滩夜跑社", "description": "周末沿外滩走走跑跑。", "cover": cover, "logo": cover, "joinPolicy": 0})
    cid = (r.get('data') or {}).get('clubId') if isinstance(r.get('data'), dict) else None
    if cid: save(club=cid)
q('lead role', "SELECT id,role FROM ums_member WHERE id=%s", (mid,))
q('club', "SELECT id,member_id,name,status,del_flag FROM club WHERE member_id=%s", (mid,))
