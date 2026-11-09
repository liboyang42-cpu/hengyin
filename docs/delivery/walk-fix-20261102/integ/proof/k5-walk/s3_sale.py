"""Step 3. TIME SHIFT (job timing): recruit_deadline -> yesterday so the real topicRecruitDeadlineTask moves the theme to
定价. The lead previews and confirms the final price ¥39 (pricing builds the ¥8 into the floor), platform approves the sale."""
from common import *
tid = state()['topic']
if len(cl.q("SELECT id FROM coop_order WHERE topic_id=%s AND status=0", (tid,))) != 2: sys.exit('coop orders missing')
atok = admin(); lead, _ = login(state()['lead_openid'])
lc = cl.q("SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))[0][0]
if lc == 1:
    note('TIME SHIFT (job timing): cms_topic %d recruit_deadline -> yesterday so the real deadline job picks it up now' % tid)
    ex("UPDATE cms_topic SET recruit_deadline=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id=%s", (tid,))
    run_job(atok, 'topicRecruitDeadlineTask', 5)
q('topic after deadline job', "SELECT id,lifecycle,fee_platform_rate FROM cms_topic WHERE id=%s", (tid,))
http('pricing preview', 'POST', '/api/topic/pricing/preview', token=lead, json_body={"topicId": tid})
if cl.q("SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))[0][0] == 2 and not cl.q("SELECT 1 FROM audit_task WHERE biz_id=%s AND biz_type='TOPIC_SALE_REVIEW'", (tid,)):
    http('pricing confirm (确认终价 ¥39)', 'POST', '/api/topic/pricing/confirm', token=lead,
         json_body={"topicId": tid, "guidedPrice": 39, "guidedInventory": 20, "leadCost": 0, "teamSize": 4})
for task_id, bt, ver in cl.q("SELECT id,biz_type,version FROM audit_task WHERE biz_id=%s AND status=0 ORDER BY id", (tid,)):
    http('admin review task %s %s' % (task_id, bt), 'POST', '/business/audit/review', token=atok,
         json_body={"taskId": task_id, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})
q('after sale review', "SELECT id,lifecycle,selling_started_at,fee_platform_rate,sale_end_at FROM cms_topic WHERE id=%s", (tid,))
q('tickets', "SELECT id,owner_type,owner_id,name,price,start_time,end_time,sub_type,mode FROM oms_ticket WHERE owner_type=1 AND owner_id=%s", (tid,))
