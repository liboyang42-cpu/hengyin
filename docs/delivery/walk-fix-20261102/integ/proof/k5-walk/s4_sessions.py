"""Step 4. The lead opens two sessions (开一场 ONCE) of the theme today, 3 h and 4 h from now (Shanghai)."""
from common import *
tid = state()['topic']; lead, _ = login(state()['lead_openid'])
sess = state().get('sessions', [])
now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
for k, hours in enumerate((3, 4)):
    if len(sess) > k: continue
    t = now + datetime.timedelta(hours=hours)
    r = http('开一场 series/create S%d %s' % (k + 1, t.strftime('%H:00')), 'POST', '/api/club/event-ops/series/create', token=lead, json_body={
        "clubId": state()['club'], "topicId": tid, "recurrenceType": "ONCE", "startDate": t.strftime('%Y-%m-%d'), "startTime": t.strftime('%H:00'),
        "occurrenceCount": 1, "customDates": None, "capacity": 20, "defaultLeadMemberId": state()['lead'], "waitlistEnabled": False,
        "offerMinutes": 30, "requestId": "k5-series-%d-%d" % (k, int(time.time()))})
    aid = cl.q("SELECT id FROM cms_activity WHERE topic_id=%s ORDER BY id DESC LIMIT 1", (tid,))[0][0]
    sess.append(aid); save(sessions=sess)
q('sessions', "SELECT id,topic_id,club_id,start_date,end_date,status,publish_status,member_id,latitude,longitude FROM cms_activity WHERE id IN (%s)" % ','.join(map(str, sess)))
q('session tickets', "SELECT id,owner_id,name,price,remaining_inventory FROM oms_ticket WHERE owner_type=2 AND owner_id IN (%s)" % ','.join(map(str, sess)))
