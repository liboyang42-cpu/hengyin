"""Round 13 m6: a club-owned 自由探索 sale walked end to end on the rig through real HTTP and the real jobs.

Usage (env M6_TAG names the run, M6_PRICE the ticket price): python3 fe_walk.py <phase>
Phases, in order: setup create merchants pricing session buy refund_before scan settle refund_after
Everything is fictional and lives in the throwaway rig DB cy_e2e_51. Fixture writes and TIME SHIFTs are printed as NOTE
and touch only this run's own rows (its theme, its session, its registrations)."""
from common import *
sys.path.insert(0, os.path.expanduser('~/rig/docs/delivery/cloud-walk-20261101/rig'))
import pay as rigpay  # noqa: E402

PRICE = int(os.environ.get('M6_PRICE', '29'))
LEAD_OID = 'm6-lead-' + TAG
MERCHANTS = [{"openid": "e2e-openid-9002", "member": 9002, "template": 6001, "perk": 3001, "store": "样板咖啡"},
             {"openid": "e2e-openid-9005", "member": 9005, "template": 6002, "perk": 3002, "store": "样板书店"}]
PLAYERS = [("m6-%s-p1" % TAG, "钱小满"), ("m6-%s-p2" % TAG, "孙一禾"), ("m6-%s-p3" % TAG, "白若川")]
SQL = RIG + '/candidate/chengyinhub-admin/sql'


def review_all(atok, biz_id=None, biz_type=None):
    sql = "SELECT id,biz_type,biz_id,version FROM audit_task WHERE status=0"
    args = []
    if biz_id is not None: sql += " AND biz_id=%s"; args.append(biz_id)
    if biz_type is not None: sql += " AND biz_type=%s"; args.append(biz_type)
    for task_id, bt, bid, ver in cl.q(sql + " ORDER BY id", tuple(args)):
        http('[%s] admin review task %s %s biz=%s' % (TAG, task_id, bt, bid), 'POST', '/business/audit/review', token=atok,
             json_body={"taskId": task_id, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})


def apply_migration(path):
    from sql_splitter import split_statements, executable_statement
    import pymysql
    c = pymysql.connect(host='127.0.0.1', port=3316, user='root', password='', database='cy_e2e_51', charset='utf8mb4', autocommit=True)
    cur = c.cursor(); out = []
    for st in split_statements(open(path, encoding='utf-8').read()):
        if not executable_statement(st): continue
        cur.execute(st)
        if cur.description: out.append(cur.fetchall())
    note('env fixture: applied %s, selects -> %s' % (os.path.basename(path), out))


def setup():
    sys.path.insert(0, RIG + '/candidate/ci')
    if not cl.q("SELECT 1 FROM cms_setting WHERE id=1"):
        note("env fixture (as k5 s1): settlement_rule generic rows at production's pre-migration 0.10, cms_setting id 1 platform 9001, then the real 3% migration")
        if not cl.q("SELECT 1 FROM settlement_rule WHERE merchant_id=0 AND biz_type='activity'"):
            ex("INSERT INTO settlement_rule(merchant_id,biz_type,rule_name,platform_commission_rate,merchant_rate,creator_rate,status,del_flag) VALUES (0,'activity','平台默认-活动',0.1000,0.9000,0,1,0)")
        if not cl.q("SELECT 1 FROM settlement_rule WHERE merchant_id=0 AND biz_type='topic'"):
            ex("INSERT INTO settlement_rule(merchant_id,biz_type,rule_name,platform_commission_rate,merchant_rate,creator_rate,status,del_flag) VALUES (0,'topic','平台默认-主题',0.1000,0.9000,0,1,0)")
        ex("INSERT INTO cms_setting(id,platform_member_id,create_time) VALUES (1,9001,NOW())")
        apply_migration(SQL + '/migration_20261103_settlement_rule_three_percent.sql')
        apply_migration(SQL + '/migration_20260815_tandianri_h8_publisher_points_funds.sql')
    q('env settlement_rule', "SELECT id,merchant_id,biz_type,platform_commission_rate,merchant_rate,creator_rate,status FROM settlement_rule")
    tok, me = login(LEAD_OID); mid = me.get('id'); save(lead=mid)
    http('[%s] become-leader' % TAG, 'POST', '/api/club/become-leader', token=tok, json_body={
        "leaderName": "蒋知远", "phone": "1380000%04d" % (mid % 10000), "identity": "个人", "coFounders": "", "hasExperience": 1,
        "experience": "办过两次探店日"})
    st = http('[%s] publisher identity status' % TAG, 'POST', '/api/publisher/identity/status', token=tok, json_body={})
    if not (st.get('data') or {}).get('registered'):
        http('[%s] publisher identity' % TAG, 'POST', '/api/publisher/identity', token=tok, json_body={
            "realName": "蒋知远", "idCard": idcard("11010119900307%03d" % (mid % 1000)), "consent": True, "source": "topic_publish"})
    key = "upload/m6-club-cover-%d.jpg" % mid
    ex("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,create_time,update_time) SELECT %s,%s,'IMAGE','APPROVED',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_media_asset WHERE member_id=%s AND object_key=%s)", (mid, key, mid, key))
    cover = "media-asset:%d" % cl.q("SELECT id FROM app_media_asset WHERE member_id=%s AND object_key=%s", (mid, key))[0][0]
    note('fixture: approved cover media asset for the lead (as chain_platform.seed_approved_cover) -> ' + cover)
    save(cover=cover)
    if not state().get('club'):
        r = http('[%s] club create' % TAG, 'POST', '/api/club/create', token=tok, json_body={
            "name": "外滩慢走社" + TAG, "description": "周末去外滩的店里坐坐。", "cover": cover, "logo": cover, "joinPolicy": 0})
        d = r.get('data')
        save(club=d.get('clubId') if isinstance(d, dict) else d)
    q('club', "SELECT id,member_id,name,status FROM club WHERE member_id=%s", (mid,))


def create():
    """M2: zero nodes, two PERK recruiting chapters, one ticket. Must publish into 招商."""
    st = state(); tok, _ = login(LEAD_OID); atok = admin()
    now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
    day = now.strftime('%Y-%m-%d'); end = (now + datetime.timedelta(days=60)).strftime('%Y-%m-%d')
    ch = [{"name": "第%d章 %s" % (i + 1, n), "description": "进店完成一次体验", "schemaVersion": 1, "required": 1, "recruitEnabled": 1,
           "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
           "blocks": [{"type": "text", "content": "本章一店(%s)" % n}], "nodes": []} for i, n in enumerate(["咖啡", "书店"])]
    dto = {"name": "外滩两店慢逛" + TAG, "subtitle": "", "description": "两家店，一张票。", "startDate": day + " 00:00:00",
           "endDate": end + " 23:59:59", "imgUrl": st['cover'], "imgArr": "", "categoryIds": "1", "productType": 2, "publishMode": "pro",
           "clubId": st['club'], "openMerchantPool": 1, "openClubPool": 0, "recruitDeadline": day + " 23:59:59", "selfPlay": 0,
           "chapters": ch, "tickets": []}
    # 招商期不带票(k4 row 3:编辑器发布自由探索不定价;确认终价时生成「门票」)。
    if not st.get('topic'):
        r = http('[%s] M2: FE topic v2/create (zero nodes)' % TAG, 'POST', '/api/topic/v2/create', token=tok, json_body=dto)
        d = r.get('data'); tid = d if isinstance(d, int) else (d or {}).get('topicId') if isinstance(d, dict) else None
        if not tid: sys.exit('create failed')
        save(topic=tid)
    tid = state()['topic']
    review_all(atok, biz_id=tid)
    q('[%s] topic after create+review' % TAG, "SELECT id,club_id,product_type,status,user_status,lifecycle,merchant_status,published_route_graph_json IS NOT NULL FROM cms_topic WHERE id=%s", (tid,))
    q('[%s] chapters' % TAG, "SELECT id,name,recruit_enabled,terms_mode FROM cms_topic_chapter WHERE topic_id=%s ORDER BY id", (tid,))
    q('[%s] nodes (expect none)' % TAG, "SELECT COUNT(*) FROM cms_topic_node WHERE topic_id=%s", (tid,))


def merchants():
    st = state(); tid = st['topic']; lead, _ = login(LEAD_OID)
    chapters = [r[0] for r in cl.q("SELECT id FROM cms_topic_chapter WHERE topic_id=%s ORDER BY id", (tid,))]
    for m in MERCHANTS: m['token'] = login(m['openid'])[0]
    for cid, m in zip(chapters, MERCHANTS):
        http('[%s] merchant %s apply chapter %s' % (TAG, m['member'], cid), 'POST', '/api/merchant/chapter-application/apply',
             token=m['token'], json_body={"chapterId": cid, "message": "想承接这一章"})
    for (aid,) in cl.q("SELECT id FROM merchant_chapter_application WHERE topic_id=%s AND status=0 ORDER BY id", (tid,)):
        http('[%s] lead approves application %s' % (TAG, aid), 'POST', '/api/merchant/chapter-application/audit', token=lead,
             json_body={"applicationId": aid, "approved": True, "status": 1})
    for cid, m in zip(chapters, MERCHANTS):
        http('[%s] merchant %s offer enroll ch%s' % (TAG, m['member'], cid), 'POST', '/api/coop/offer/enroll', token=m['token'], json_body={
            "chapterId": cid, "termsMode": "PERK", "perkTemplateId": m["perk"], "quotaTotal": 20, "allowedValidationMethods": "QR", "maxNodeXp": 50})
    for i, (cid, m) in enumerate(zip(chapters, MERCHANTS)):
        http('[%s] merchant %s node submit ch%s' % (TAG, m['member'], cid), 'POST', '/api/merchant/chapter-node/submit', token=m['token'],
             json_body={"chapterId": cid, "name": "%s第%d站" % (m["store"], i + 1), "address": "上海市黄浦区%s%d号" % (m["store"], i + 1),
                        "longitude": "%.6f" % (121.48 + i * 0.01), "latitude": "%.6f" % (31.24 + i * 0.01), "templateId": m["template"]})
    for (nid,) in cl.q("SELECT id FROM cms_topic_node WHERE topic_id=%s AND merchant_member_id IS NOT NULL AND node_audit_status<>1 ORDER BY id", (tid,)):
        http('[%s] lead approves node %s' % (TAG, nid), 'POST', '/api/merchant/chapter-node/audit', token=lead,
             form={"nodeId": nid, "approve": "true", "reason": "可以"})
    q('[%s] applications' % TAG, "SELECT id,chapter_id,merchant_id,status FROM merchant_chapter_application WHERE topic_id=%s ORDER BY id", (tid,))
    q('[%s] offers' % TAG, "SELECT id,chapter_id,merchant_id,terms_mode,quota_total,status FROM merchant_chapter_offer WHERE topic_id=%s ORDER BY id", (tid,))
    q('[%s] nodes' % TAG, "SELECT id,chapter_id,merchant_member_id,node_audit_status,template_id FROM cms_topic_node WHERE topic_id=%s ORDER BY id", (tid,))
    q('[%s] published route stations' % TAG, "SELECT JSON_LENGTH(published_route_graph_json, '$.routeStations') FROM cms_topic WHERE id=%s", (tid,))


def pricing():
    st = state(); tid = st['topic']; lead, _ = login(LEAD_OID); atok = admin()
    if cl.q("SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))[0][0] == 1:
        note('TIME SHIFT (job timing): cms_topic %s recruit_deadline -> yesterday so the real recruit-deadline job runs now' % tid)
        ex("UPDATE cms_topic SET recruit_deadline=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id=%s", (tid,))
        run_job(atok, 'topicRecruitDeadlineTask', 6)
    q('[%s] topic after deadline job' % TAG, "SELECT id,lifecycle,fee_platform_rate,sale_end_at FROM cms_topic WHERE id=%s", (tid,))
    http('[%s] pricing preview' % TAG, 'POST', '/api/topic/pricing/preview', token=lead, json_body={"topicId": tid})
    if not cl.q("SELECT 1 FROM audit_task WHERE biz_id=%s AND biz_type='TOPIC_SALE_REVIEW'", (tid,)):
        http('[%s] 确认终价 ¥%s' % (TAG, PRICE), 'POST', '/api/topic/pricing/confirm', token=lead,
             json_body={"topicId": tid, "guidedPrice": PRICE, "guidedInventory": 20, "leadCost": 0, "teamSize": 4})
    q('[%s] topic tickets' % TAG, "SELECT id,name,price,start_time,end_time,signup_deadline FROM oms_ticket WHERE owner_type=1 AND owner_id=%s", (tid,))
    q('[%s] audit tasks' % TAG, "SELECT id,biz_type,status FROM audit_task WHERE biz_id=%s ORDER BY id", (tid,))


def canonical_draft(atok, tid):
    """Platform's manual canonical15 facts (k4 row 13, adapted from scripts/e2e/canonical_draft.py; storeCount 2; not four-store)."""
    chapters = [r[0] for r in cl.q("SELECT id FROM cms_topic_chapter WHERE topic_id=%s ORDER BY id", (tid,))]
    ch_rows = [{"id": c, "required": 1, "termsMode": "PERK", "description": "体验%d" % i, "blocksJson": "[{}]"} for i, c in enumerate(chapters)]
    of_rows = [{"id": r[0], "chapterId": r[1], "merchantId": r[2], "perkId": r[3], "termsMode": r[4], "perHeadFee": None}
               for r in cl.q("SELECT id,chapter_id,merchant_id,perk_id,terms_mode FROM merchant_chapter_offer WHERE topic_id=%s ORDER BY id", (tid,))]
    st_rows = [{"nodeId": r[0], "chapterId": r[1], "merchantMemberId": r[2], "address": r[3], "longitude": str(r[4]), "latitude": str(r[5])}
               for r in cl.q("SELECT id,chapter_id,merchant_member_id,address,longitude,latitude FROM cms_topic_node WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY id", (tid,))]
    pk_rows = [{"id": r[0], "name": r[1], "perkType": 0, "retailValue": "30", "redeemRule": "到店核销"}
               for r in cl.q("SELECT id,name FROM coop_perk WHERE id IN (SELECT perk_id FROM merchant_chapter_offer WHERE topic_id=%s)", (tid,))]
    tk_rows = [{"id": r[0], "price": str(r[1]), "totalInventory": r[2], "startTime": 1000, "endTime": 2000, "signupDeadline": 900,
                "meetingPoint": "外滩", "gatherLng": "121.49", "gatherLat": "31.24", "refundRule": "开场前可退", "subType": r[3] or "explore"}
               for r in cl.q("SELECT id,price,total_inventory,sub_type FROM oms_ticket WHERE owner_type=1 AND owner_id=%s ORDER BY id", (tid,))]
    canonical = {
        "storeCount": 2, "salesTargetTotal": 20,
        "ticketPricingPolicy": {"pointsAllowed": False, "discountMode": "NONE", "tickets": tk_rows},
        "sessionWindows": {"startDate": 1000, "endDate": 2000, "saleEndAt": 900, "tickets": tk_rows},
        "participationUnitPolicy": {"adultCount": 1, "petMax": 1, "childrenPolicy": "WITH_GUARDIAN", "extraAdultPolicy": "NOT_ALLOWED", "companionPolicy": "SAME_TICKET"},
        "sessionVenues": {"adultCapacity": 20, "petCapacity": 20, "authorization": "asset://venue-auth", "rainPlan": "INDOOR",
                          "evacuationPlan": "asset://evacuation", "responsibleParty": "club:%s" % state()['club'], "insuranceScope": "asset://policy", "tickets": tk_rows},
        "merchantCashSettlementMode": {"merchantDepositRequired": False, "chapters": ch_rows, "offers": of_rows},
        "storeCluster": st_rows,
        "microExperienceCatalog": {"chapters": ch_rows, "offers": of_rows, "perks": pk_rows},
        "merchantWorkPackage": {"chapters": ch_rows, "workItems": [{"chapterId": c, "roleNeed": "店员", "effortStaffMinutes": 30, "effortVenue": "柜台",
                                "effortMaterials": "礼包", "effortAvailableSlots": "10:00-18:00", "effortQualifications": "在岗"} for c in chapters]},
        "clubWorkPackage": {"opening": "开场", "patrol": "巡场", "photoWrap": "摄影收口", "closing": "闭场", "community": "自愿入群",
                            "HPlan": "一期固定", "contractRef": "asset://club-contract", "organizers": []},
        "contentRightsPackage": {"assetRef": "asset://rights", "owner": "club", "licensedEntity": "chengyin", "validFrom": 1000, "validTo": 2000,
                                 "editingRight": True, "humanPortraitConsent": True, "petPortraitConsent": True, "channels": ["mini-program"]},
        "completionPolicy": {"evidenceRule": "ALL_REQUIRED_STAMPS", "requiredChapterIds": chapters},
        "relationshipPolicy": {"platformTransaction": True, "clubCommunityOptIn": True, "forcedFollow": False, "forcedGroupJoin": False, "merchantContactDisclosure": False},
        "refundReschedulePolicy": {"rescheduleDeadline": 800, "rescheduleLimit": 1, "cancellationPolicy": "REFUND_AND_REBUY", "merchantAbsencePolicy": "FULL_REFUND",
                                   "noShowPolicy": "NO_REFUND", "crossEditionTransferAllowed": False, "ticketPolicies": tk_rows},
    }
    cur = http('[%s] GET sale-canonical-draft' % TAG, 'GET', '/business/audit/topic/%d/sale-canonical-draft' % tid, token=atok, show=False)
    ver = (cur.get("data") or {}).get("parameterVersion") if isinstance(cur.get("data"), dict) else None
    http('[%s] PUT sale-canonical-draft (platform, evidenceRule ALL_REQUIRED_STAMPS)' % TAG, 'PUT', '/business/audit/topic/%d/sale-canonical-draft' % tid,
         token=atok, json_body={"schemaVersion": "CANONICAL_15_V1", "expectedParameterVersion": ver or 0, "canonical": canonical})


def session():
    st = state(); tid = st['topic']; lead, _ = login(LEAD_OID); atok = admin()
    if not st.get('session'):
        today, t = cl.q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 6 MINUTE, '%%Y-%%m-%%d'), "
                        "DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 6 MINUTE, '%%H:%%i')")[0]
        r = http('[%s] lead 开一场 %s %s' % (TAG, today, t), 'POST', '/api/club/open-session', token=lead,
                 json_body={"clubId": st['club'], "topicId": tid, "sessionDate": today, "sessionTime": t})
        aid = (r.get('data') or {}).get('activityId') if isinstance(r.get('data'), dict) else None
        if aid: save(session=aid)
    canonical_draft(atok, tid)
    review_all(atok, biz_id=tid, biz_type='TOPIC_SALE_REVIEW')
    q('[%s] topic after sale review' % TAG, "SELECT id,lifecycle,selling_started_at,sale_end_at FROM cms_topic WHERE id=%s", (tid,))
    q('[%s] sale snapshot' % TAG, "SELECT topic_id,schema_version,JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.completionPolicy.evidenceRule')) FROM topic_sale_snapshot WHERE topic_id=%s", (tid,))
    q('[%s] session' % TAG, "SELECT id,topic_id,club_id,start_date,end_date,status FROM cms_activity WHERE topic_id=%s", (tid,))
    q('[%s] session ticket' % TAG, "SELECT id,owner_id,name,price,signup_deadline FROM oms_ticket WHERE owner_type=2 AND owner_id IN (SELECT id FROM cms_activity WHERE topic_id=%s)", (tid,))


def buy():
    st = state(); act = st['session']; club = st['club']; regs = st.get('regs', {})
    ticket = cl.q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (act,))[0][0]
    for openid, name in PLAYERS:
        if openid in regs: continue
        tok, me = login(openid); mid = me.get('id')
        http('[%s] %s (%s) club/join' % (TAG, name, mid), 'POST', '/api/club/join', token=tok, json_body={"id": club})
        http('[%s] %s 报名信息单独同意' % (TAG, name), 'POST', '/api/compliance/consents', token=tok, json_body={
            "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "docVersion": "v2.1", "requestId": "m6-consent-%s" % mid})
        qt = http('[%s] %s quote' % (TAG, name), 'POST', '/api/registration/quote', token=tok, json_body={"ownerType": 2, "ownerId": act, "ticketId": ticket, "isUsePoint": 0})
        sign = (qt.get('data') or {}).get('quoteSign')
        r = http('[%s] %s registration/create' % (TAG, name), 'POST', '/api/registration/create', token=tok, json_body={
            "ownerType": 2, "ownerId": act, "ticketId": ticket, "realName": name, "phone": "1370000%04d" % (mid % 10000), "email": "",
            "isUsePoint": 0, "quoteSign": sign, "requestId": "m6-buy-%s-%s" % (TAG, mid)})
        d = r.get('data') or {}
        paid = rigpay.pay(reg_no=d.get('registrationNo'))
        log('[%s] %s pay seam (verified fact + real /api/registration/callback)' % (TAG, name), paid)
        regs[openid] = {"member": mid, "reg": d.get('registrationId'), "name": name}; save(regs=regs)
    q('[%s] paid registrations' % TAG, "SELECT id,member_id,payable_amount,wechat_payment_amount,payment_status,registration_status,refund_deadline FROM cms_registration WHERE owner_type=2 AND owner_id=%s ORDER BY id", (act,))
    money(' after payment')


def money(label):
    st = state(); act = st['session']
    members = [9001, 9002, 9005, st['lead']]
    q('[%s] balances%s' % (TAG, label), "SELECT id,balance FROM ums_member WHERE id IN (%s) ORDER BY id" % ','.join(map(str, members)))
    q('[%s] balance detail for the session%s' % (TAG, label), "SELECT id,member_id,event_type,change_balance,after_balance,change_reason FROM ums_member_balance_detail WHERE event_id=%s AND event_type IN (3,7,95) ORDER BY id", (act,))
    q('[%s] refund balance detail%s' % (TAG, label), "SELECT id,member_id,event_type,event_id,change_balance,after_balance FROM ums_member_balance_detail WHERE event_type=6 AND event_id IN (SELECT id FROM refund_application WHERE source_id IN (SELECT id FROM cms_registration WHERE owner_type=2 AND owner_id=%s)) ORDER BY id", (act,))
    q('[%s] settlement_detail%s' % (TAG, label), "SELECT id,source_id,receiver_type,receiver_id,gross_amount,settlement_amount,status FROM settlement_detail WHERE source_type='registration' AND source_id IN (SELECT id FROM cms_registration WHERE owner_type=2 AND owner_id=%s) ORDER BY id", (act,))
    q('[%s] refunds%s' % (TAG, label), "SELECT id,source_id,paid_amount,refund_amount,status FROM refund_application WHERE source_id IN (SELECT id FROM cms_registration WHERE owner_type=2 AND owner_id=%s) ORDER BY id", (act,))
    q('[%s] debts%s' % (TAG, label), "SELECT member_id,source_type,source_id,amount,status FROM ums_member_settlement_debt ORDER BY id")
    q('[%s] session%s' % (TAG, label), "SELECT id,start_date,end_date,is_settlement FROM cms_activity WHERE id=%s", (act,))


def screens(label):
    st = state(); lead, _ = login(LEAD_OID)
    s = http('[%s] 俱乐部结算 summary [%s]' % (TAG, label), 'POST', '/api/club/settlement/summary', token=lead, json_body={"clubId": st['club']}, show=False)
    d = s.get('data') or {}
    log('[%s] 俱乐部结算 [%s]' % (TAG, label), d)
    w = http('[%s] wallet/stages lead [%s]' % (TAG, label), 'POST', '/api/wallet/stages', token=lead, json_body={}, show=False)
    log('[%s] wallet/stages lead [%s]' % (TAG, label), w.get('data'))
    for m in MERCHANTS:
        t = login(m['openid'])[0]
        r = http('[%s] merchant %s 核销与退款 [%s]' % (TAG, m['member'], label), 'POST', '/api/merchant/finance/redemptions', token=t,
                 json_body={"filter": "all", "pageNum": 1, "pageSize": 20}, show=False)
        dd = r.get('data') or {}
        log('[%s] merchant %s 核销与退款 [%s]' % (TAG, m['member'], label), {"summary": dd.get('summary'), "rows": [
            {k: x.get(k) for k in ('recordType', 'topicName', 'chapterName', 'settlementAmount', 'displayState', 'destination')}
            for x in (dd.get('rows') or []) if (x.get('topicName') or '').endswith(TAG)]})


def refund_before():
    st = state(); act = st['session']; p = st['regs'][PLAYERS[2][0]]
    tok = login(PLAYERS[2][0])[0]
    if cl.q("SELECT payment_status FROM cms_registration WHERE id=%s", (p['reg'],))[0][0] == 2:
        http('[%s] %s quote (refund terms now)' % (TAG, p['name']), 'POST', '/api/registration/quote', token=tok, json_body={
            "ownerType": 2, "ownerId": act, "ticketId": cl.q("SELECT ticket_id FROM cms_registration WHERE id=%s", (p['reg'],))[0][0], "isUsePoint": 0})
        http('[%s] %s cancel-refund before the start (reg %s)' % (TAG, p['name'], p['reg']), 'POST', '/api/registration/cancel-refund', token=tok, form={"id": p['reg']})
        run_job(admin(), 'refundReconcileTask', 5)
    money(' after refund before the start')
    screens('after payment and a refund before the start')


def scan():
    st = state(); act = st['session']; tid = st['topic']
    for _ in range(80):
        if cl.q("SELECT DATE_ADD(NOW(), INTERVAL 8 HOUR) >= start_date FROM cms_activity WHERE id=%s", (act,))[0][0]: break
        time.sleep(5)
    nodes = cl.q("SELECT id,chapter_id,checkin_code,merchant_member_id FROM cms_topic_node WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY chapter_id", (tid,))
    # M6_SCAN_P2=0: player 2 never goes to a shop, so a refund after settlement takes the ordinary path, not the
    # 'scanned ③ ticket goes to platform manual review' path (RefundPolicy.verifiedVerdict).
    plan = {PLAYERS[0][0]: nodes} if os.environ.get('M6_SCAN_P2') == '0' else {PLAYERS[0][0]: nodes, PLAYERS[1][0]: nodes[:1]}
    for openid, ns in plan.items():
        p = st['regs'][openid]; ptok = login(openid)[0]
        for _nid, chapter_id, code, merchant in ns:
            mtok = login('e2e-openid-%d' % merchant)[0]
            http('[%s] %s check-in ch%s' % (TAG, p['name'], chapter_id), 'POST', '/api/play/checkin', token=ptok, form={"activityId": act, "code": code, "topicId": tid})
            dyn = http('[%s] %s dyncode' % (TAG, p['name']), 'POST', '/api/verify/dyncode/issue', token=ptok, form={"registrationId": p['reg'], "type": "activity"}, show=False)
            http('[%s] merchant %s scans %s ch%s' % (TAG, merchant, p['name'], chapter_id), 'POST', '/api/registration/scan_qr_code', token=mtok,
                 form={"type": "activity", "code": (dyn.get('data') or {}).get('code')})
    q('[%s] redemptions' % TAG, "SELECT registration_id,chapter_id,merchant_id,status FROM redemption WHERE topic_id=%s ORDER BY id", (tid,))
    money(' after scans (a scan moves no money)')


def shift_end(days):
    act = state()['session']
    ex("UPDATE cms_activity SET start_date=DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL %s DAY) - INTERVAL 3 HOUR, end_date=DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL %s DAY) WHERE id=%s", (days, days, act))
    # The refund deadline frozen on each ticket at purchase is the session start; move it with the session (own rows).
    ex("UPDATE cms_registration SET refund_deadline=(SELECT start_date FROM cms_activity WHERE id=%s) WHERE owner_type=2 AND owner_id=%s AND refund_deadline IS NOT NULL", (act, act))
    note('[%s] TIME SHIFT (settlement wait, own rows): session %s now ended %d days ago (Shanghai wall time); its tickets\' frozen refund_deadline moved to the new start' % (TAG, act, days))


def settle():
    atok = admin()
    shift_end(13)
    run_job(atok, 'settlementTask', 8)
    money(' 13 days after the end, job ran')
    screens('13 days after the end')
    shift_end(15)
    run_job(atok, 'settlementTask', 8)
    money(' 15 days after the end, job ran')
    screens('after settlement')
    run_job(atok, 'settlementTask', 8)
    money(' job re-run')


def refund_after():
    st = state(); p = st['regs'][PLAYERS[1][0]]; lead, _ = login(LEAD_OID); atok = admin()
    http('[%s] %s cancel-refund after settlement (player; policy refuses after the start)' % (TAG, p['name']), 'POST', '/api/registration/cancel-refund',
         token=login(PLAYERS[1][0])[0], form={"id": p['reg']})
    http('[%s] lead cancel-by-owner reg %s after settlement' % (TAG, p['reg']), 'POST', '/api/registration/cancel-by-owner', token=lead, form={"id": p['reg']})
    run_job(atok, 'refundReconcileTask', 5)
    money(' after the refund after settlement')
    screens('after the refund after settlement')
    run_job(atok, 'settlementTask', 8)
    money(' job re-run after the refund')


if __name__ == '__main__':
    for phase in sys.argv[1:]:
        note('[%s] ===== phase %s (price %s) =====' % (TAG, phase, PRICE))
        globals()[phase]()
