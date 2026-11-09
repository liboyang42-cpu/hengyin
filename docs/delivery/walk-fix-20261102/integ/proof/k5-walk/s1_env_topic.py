"""Step 1. Env fixtures (same as j2/f2-C), then the lead (k5-lead, 主理人 of the club from step 0) authors a club city-orienteering theme
through the editor's own endpoints: 3 text templates (approved by the platform), theme v2/create with 2 chapters / 3 stations,
a 领队场 ticket ¥39, 商家承接 on. Platform approves the project and content reviews."""
from common import *
SQL = RIG + '/candidate/chengyinhub-admin/sql'
sys.path.insert(0, RIG + '/candidate/ci')
from sql_splitter import split_statements, executable_statement
import pymysql
def apply_migration(path):
    c = pymysql.connect(host='127.0.0.1', port=3316, user='root', password='', database='cy_e2e_51', charset='utf8mb4', autocommit=True)
    cur = c.cursor(); out = []
    for st in split_statements(open(path, encoding='utf-8').read()):
        if not executable_statement(st): continue
        cur.execute(st)
        if cur.description: out.append(cur.fetchall())
    note('env fixture: applied %s, selects -> %s' % (os.path.basename(path), out))
if not cl.q("SELECT 1 FROM cms_setting WHERE id=1"):
    note("env fixture: settlement_rule generic activity/topic rows at production's pre-migration 0.10/0.90/0; cms_setting id 1 platform 9001")
    ex("INSERT INTO settlement_rule(merchant_id,biz_type,rule_name,platform_commission_rate,merchant_rate,creator_rate,status,del_flag) VALUES (0,'activity','平台默认-活动',0.1000,0.9000,0,1,0),(0,'topic','平台默认-主题',0.1000,0.9000,0,1,0)")
    ex("INSERT INTO cms_setting(id,platform_member_id,create_time) VALUES (1,9001,NOW())")
    apply_migration(SQL + '/migration_20261103_settlement_rule_three_percent.sql')
    apply_migration(SQL + '/migration_20260815_tandianri_h8_publisher_points_funds.sql')
q('env settlement_rule', "SELECT id,merchant_id,biz_type,platform_commission_rate,merchant_rate,creator_rate,status FROM settlement_rule")
q('env cms_setting', "SELECT id,platform_member_id FROM cms_setting")

tok, me = login(state()['lead_openid']); LEAD = me.get('id'); log('lead login', {'member': LEAD})
st = http('publisher identity status', 'POST', '/api/publisher/identity/status', token=tok, json_body={})
if not (st.get('data') or {}).get('registered'):
    sys.exit('lead identity missing')
G = [("钟楼的指针", "外滩钟楼的大钟面朝哪条马路？", "中山东一路"), ("码头的缆桩", "十六铺码头最近的缆桩上漆的是什么颜色？", "黄"), ("桥头的石狮", "外白渡桥南堍有几只石狮？（填数字）", "2")]
tids = state().get('templates')
if not tids:
    tids = []
    for title, qn, ans in G:
        r = http('template publish ' + title, 'POST', '/api/template/publish', token=tok, json_body={
            "title": title, "description": qn, "validationMethod": 1, "questionName": qn, "questionAnswer": ans,
            "hint1": "到现场看。", "hint2": "抬头。", "answerReveal": ans, "feedbackText": "答对了。", "duration": 8,
            "difficulty": "普通", "players": "2-4人", "usageLocation": "户外"})
        d = r.get('data'); tids.append(d if isinstance(d, int) else (d or {}).get('id'))
    save(templates=tids)
atok = admin()
for tid_, bt, bid, ver in cl.q("SELECT id,biz_type,biz_id,version FROM audit_task WHERE status=0 AND biz_type='TEMPLATE' ORDER BY id"):
    http('admin review task %s %s biz=%s' % (tid_, bt, bid), 'POST', '/business/audit/review', token=atok,
         json_body={"taskId": tid_, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})
q('templates', "SELECT id,title,status FROM cms_member_template WHERE member_id=%s", (LEAD,))
cover = state()['cover']
now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
day = now.strftime('%Y-%m-%d'); end = (now + datetime.timedelta(days=60)).strftime('%Y-%m-%d')
S = [("外滩钟楼", "上海市黄浦区中山东一路13号", 121.490317, 31.240018), ("十六铺码头", "上海市黄浦区中山东二路531号", 121.502, 31.229),
     ("外白渡桥", "上海市黄浦区中山东一路外白渡桥", 121.4905, 31.2449)]
CH = [("第一章 · 钟声", [0, 1]), ("第二章 · 桥头", [2])]
chapters = []
for name, idx in CH:
    nodes, blocks = [], [{"type": "text", "content": name + "：沿江往北。"}]
    for k, si in enumerate(idx):
        n, addr, lng, lat = S[si]
        nodes.append({"name": n, "address": addr, "longitude": "%.6f" % lng, "latitude": "%.6f" % lat, "nodeTime": 15,
                      "templateId": tids[si], "description": "到%s附近答题。" % n})
        blocks.append({"type": "node", "nodeIndex": k})
    chapters.append({"name": name, "description": name, "schemaVersion": 1, "required": 1, "blocks": blocks, "nodes": nodes})
dto = {"name": "外滩钟声", "subtitle": "", "description": "沿外滩走三站，钟楼、码头、外白渡桥。", "startDate": day + " 00:00:00",
       "endDate": end + " 23:59:59", "imgUrl": cover, "imgArr": "", "categoryIds": "1", "productType": 1, "publishMode": "pro",
       "clubId": state()['club'], "teamMode": 1, "teamMaxMembers": 4, "openMerchantPool": 1, "openClubPool": 0,
       "recruitDeadline": day + " 23:59:59", "selfPlay": 0, "routeMode": "LINEAR", "routeGraphJson": "", "publishToCreative": 0,
       "chapters": chapters,
       "tickets": [{"name": "领队场", "price": 39, "totalStock": 20, "mode": 1, "teamSize": 4, "meetingPoint": "外滩钟楼",
                    "meetingPointAddress": "上海市黄浦区中山东一路13号", "gatherLng": 121.490317, "gatherLat": 31.240018,
                    "meetingPointLatitude": 31.240018, "meetingPointLongitude": 121.490317, "syncWithTheme": True,
                    "startTime": day + " 00:00:00", "endTime": end + " 23:59:59", "description": "领队全程带队。", "refundSupported": True}]}
if not state().get('topic'):
    r = http('topic v2/create', 'POST', '/api/topic/v2/create', token=tok, json_body=dto)
    d = r.get('data'); tid = d if isinstance(d, int) else (d or {}).get('topicId') if isinstance(d, dict) else None
    if not tid: sys.exit(1)
    save(topic=tid)
tid = state()['topic']
for task_id, bt, ver in cl.q("SELECT id,biz_type,version FROM audit_task WHERE biz_id=%s AND status=0 ORDER BY id", (tid,)):
    http('admin review task %s %s biz=%s' % (task_id, bt, tid), 'POST', '/business/audit/review', token=atok,
         json_body={"taskId": task_id, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})
q('topic row', "SELECT id,club_id,member_id,product_type,merchant_status,lifecycle,status,user_status,team_mode,recruit_deadline FROM cms_topic WHERE id=%s", (tid,))
q('nodes', "SELECT n.id,n.name,n.template_id,n.chapter_id FROM cms_topic_node n WHERE n.topic_id=%s ORDER BY n.id", (tid,))
q('tickets', "SELECT id,owner_type,owner_id,name,price,start_time,end_time FROM oms_ticket WHERE owner_type=1 AND owner_id=%s", (tid,))
