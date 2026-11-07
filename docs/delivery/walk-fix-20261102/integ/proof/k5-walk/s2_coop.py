"""Step 2. Two merchants apply for stations with the app body; the lead (主理人 from step 0) picks each candidate and sends
an invitation with terms; each merchant accepts on its own station. 9002 on 外滩钟楼 (71): 按核销付费 ¥8/人 (shareMode 2).
9005 on 十六铺码头 (72): 引流 (shareMode 0). Accept freezes a coop_order each."""
from common import *
tid = state()['topic']
lead, _ = login(state()['lead_openid'])
NODE = {n: i for i, n in cl.q("SELECT id,name FROM cms_topic_node WHERE topic_id=%s", (tid,))}
M = [(9002, NODE["外滩钟楼"], "外滩钟楼", 121.490317, 31.240018, 2, 8), (9005, NODE["十六铺码头"], "十六铺码头", 121.502, 31.229, 0, None)]
regs = state().get('stations', {})
for mid, node, name, lng, lat, mode, fee in M:
    mtok, _ = login('e2e-openid-%d' % mid)
    key = "upload/k5-station-%d.jpg" % mid
    ex("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,create_time,update_time) SELECT %s,%s,'IMAGE','APPROVED',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_media_asset WHERE member_id=%s AND object_key=%s)", (mid, key, mid, key))
    pic = "media-asset:%d" % cl.q("SELECT id FROM app_media_asset WHERE member_id=%s AND object_key=%s", (mid, key))[0][0]
    note('fixture: approved station photo media asset for merchant %d -> %s' % (mid, pic))
    if str(mid) not in regs:
        now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
        http('merchant %d apply node %d (app body)' % (mid, node), 'POST', '/api/registration/merchant/create', token=mtok, json_body={
            "topicId": tid, "mode": 1, "nodeId": node, "addressName": name, "address": "上海市黄浦区" + name, "longitude": "%.6f" % lng,
            "latitude": "%.6f" % lat, "picUrl": pic, "activityDesc": "接待领队团", "limitNum": 20,
            "startDate": now.strftime('%Y-%m-%d 00:00:00'), "endDate": (now + datetime.timedelta(days=60)).strftime('%Y-%m-%d 23:59:59'),
            "ruleInstructions": "到店出示团码"})
        rows = cl.q("SELECT id FROM cms_registration_merchant WHERE topic_id=%s AND member_id=%s AND node_id=%s ORDER BY id DESC LIMIT 1", (tid, mid, node))
        if not rows: sys.exit('station application failed')
        regs[str(mid)] = rows[0][0]; save(stations=regs)
    rid = regs[str(mid)]
    http('lead confirm candidate reg %d' % rid, 'POST', '/api/coop/candidates/confirm', token=lead, json_body={"registrationId": rid})
    if not cl.q("SELECT id FROM coop_invite WHERE topic_id=%s AND to_id=%s", (tid, mid)):
        body = {"inviteType": 0, "toType": "merchant", "toId": mid, "topicId": tid, "shareMode": mode, "supplyStationId": rid,
                "nodeId": node, "message": "一起接待周末的领队团"}
        if fee is not None: body["fixedFee"] = fee
        http('lead coop/invite -> merchant %d shareMode %s fixedFee %s' % (mid, mode, fee), 'POST', '/api/coop/invite', token=lead, json_body=body)
    inv = cl.q("SELECT id,status FROM coop_invite WHERE topic_id=%s AND to_id=%s ORDER BY id DESC LIMIT 1", (tid, mid))[0]
    if inv[1] == 0:
        http('merchant %d coop/handle accept (own station %d)' % (mid, rid), 'POST', '/api/coop/handle', token=mtok,
             json_body={"id": inv[0], "status": 1, "supplyStationId": rid})
q('cms_registration_merchant', "SELECT id,member_id,topic_id,node_id,status,audit_status FROM cms_registration_merchant WHERE topic_id=%s", (tid,))
q('coop_invite', "SELECT id,to_id,share_mode,fixed_fee,supply_station_id,status FROM coop_invite WHERE topic_id=%s", (tid,))
q('coop_order', "SELECT id,topic_id,to_type,to_id,status,share_mode,fixed_fee,LEFT(terms_snapshot,300) FROM coop_order WHERE topic_id=%s", (tid,))
