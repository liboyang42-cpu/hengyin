#!/usr/bin/env python3
"""P-01 proof over real HTTP in the walkthrough rig: a PAID city-orienteering theme (product_type=1),
content slot PLATFORM, organised by club 7002, goes from publish to SELLING and takes a paid registration
through the real /api/registration/callback. Then the same path on a CLUB-owned slot must still be refused
by assertCollectible. Every step prints the response and a DB read-back.

Round 2 adds "self": club 7001's own paid city theme with NO partner club (no invite, no recruiting window).
The publishing club counts as organiser, so the sale review succeeds; 7001 opens its own session and the quote
must still return the assertCollectible payment refusal.

Run with the rig venv:  $RIG_HOME/venv/bin/python p01_city_sale.py [platform|club|self]
Only fixture identities and the rig's throwaway DB are used. payment_status is never written:
the rig's pay.py writes the raw payment fact and calls the real callback, as chain_player.py does."""
import os, sys, json, datetime, time, subprocess, urllib.request, urllib.parse
RIG = os.environ.get("RIG_HOME", os.path.expanduser("~/walk-rig"))
sys.path.insert(0, RIG + "/candidate/scripts/e2e")
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
os.environ["PATH"] = RIG + "/work/bin:" + os.environ["PATH"]
from chain_lib import http, q, ex, admin_token, readback  # noqa: E402
from seed_fixtures import RECRUIT_JOB_TARGET  # noqa: E402

MODE = sys.argv[1] if len(sys.argv) > 1 else "platform"
J = lambda x: json.dumps(x, ensure_ascii=False)[:400]


def login(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=data, method="POST",
                                 headers={"content-type": "application/x-www-form-urlencoded"})
    r = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20).read().decode())
    return r.get("token")


def seed_cover():
    """Fixture: one APPROVED image asset for the publisher (same shape as the Java IT MediaFixture)."""
    key = "upload/p01-cover-%d.jpg" % int(time.time() * 1000)
    ex("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,create_time,update_time) "
       "VALUES(%s,%s,'IMAGE','APPROVED',0,NOW(),NOW())", (PUBLISHER, key))
    return q("SELECT id FROM app_media_asset WHERE object_key=%s", (key,))[0][0]


def step(t):
    print("\n== " + t)


now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
fmt = lambda d: d.strftime("%Y-%m-%d %H:%M:%S")
atok = admin_token()
# Both cases are club 7001's own city theme published by its leader 9003 (city themes from a club leader must
# bind the club). "platform": the admin sets the content slot to PLATFORM (as the walkers did) and club 7002 is
# invited as organiser during recruiting. "club": the content slot stays CLUB 7001.
PUBLISHER = 9003
ptok, etok, player = login("e2e-openid-%d" % PUBLISHER), login("e2e-openid-9008"), login("e2e-openid-9009")
SELF = MODE == "self"
SESSION_CLUB, stok = (7001, ptok) if SELF else (7002, etok)

step("publisher identity (paid publish needs it)")
st = http("POST", "/api/publisher/identity/status", token=ptok, json_body={})
if not (st.get("data") or {}).get("registered"):
    print(J(http("POST", "/api/publisher/identity", token=ptok, json_body={
        "realName": "测试主理人", "idCard": "110101199001010015", "consent": True, "source": "topic_publish"})))

step("one text-question game (template) for the stations, approved by admin")
tr = http("POST", "/api/template/publish", token=ptok, json_body={
    "title": "门楣上的年份 %s" % now.strftime("%H%M%S"), "description": "正门上方刻着哪一年？", "validationMethod": 1,
    "questionName": "正门上方刻着哪一年？（四位数字）", "questionAnswer": "1873", "hint1": "抬头看。",
    "hint2": "18 开头。", "answerReveal": "1873。", "feedbackText": "对了。", "duration": 8,
    "difficulty": "普通", "players": "2-4人", "usageLocation": "户外"})
print(J(tr))
td = tr.get("data")
tpl = td if isinstance(td, int) else (td or {}).get("id")
for task_id, ver in q("SELECT id,version FROM audit_task WHERE biz_id=%s AND status=0 AND biz_type LIKE '%%TEMPLATE%%'", (tpl,)):
    print("approve template", J(http("POST", "/business/audit/review", token=atok, json_body={
        "taskId": task_id, "status": 1, "reasonCode": "E2E", "reasonText": "走查放行", "expectedVersion": ver})))
readback("template", "SELECT id,status,draft_status,check_status FROM cms_member_template WHERE id=%s", (tpl,))
cover = "media-asset:%d" % seed_cover()
step("executing club leader 9008 identity (open-session needs it)")
st = http("POST", "/api/publisher/identity/status", token=etok, json_body={})
if not (st.get("data") or {}).get("registered"):
    print(J(http("POST", "/api/publisher/identity", token=etok, json_body={
        "realName": "执行主理人", "idCard": "110101199001010023", "consent": True, "source": "topic_publish"})))

step("publish a paid city theme (V2 create, product_type=1)")
start = now + datetime.timedelta(days=3)
ticket = {"name": "领队场", "price": 69, "totalStock": 20, "mode": 1, "teamSize": 4,
          "meetingPoint": "外滩源壹号 正门台阶", "meetingPointAddress": "上海市黄浦区中山东一路33号",
          "gatherLng": 121.48841, "gatherLat": 31.24268,
          "startTime": fmt(start.replace(hour=14, minute=0, second=0, microsecond=0)),
          "endTime": fmt(start.replace(hour=17, minute=0, second=0, microsecond=0)),
          "signupDeadline": fmt(start.replace(hour=12, minute=0, second=0, microsecond=0)),
          "description": "领队带队。", "refundSupported": True}
nodes = [{"name": "外滩源壹号", "address": "上海市黄浦区中山东一路33号", "longitude": "121.488410",
          "latitude": "31.242680", "nodeTime": 15, "templateId": tpl},
         # P-16: the real 人民广场 point. On pre-fix code set P16_NUDGE=1 to move it 100 m (as the walker had to).
         {"name": "人民广场", "address": "上海市黄浦区人民大道",
          "longitude": "121.4747" if os.environ.get("P16_NUDGE") else "121.4737",
          "latitude": "31.2304", "nodeTime": 15, "templateId": tpl}]
dto = {"name": "P01 外滩电报（%s %s）" % (MODE, now.strftime("%H%M%S")), "subtitle": "城市定向开售验证",
       "description": "两站城市定向，用于验证付费城市定向能开售。", "startDate": fmt(now),
       "endDate": fmt(now + datetime.timedelta(days=60)), "saleEndAt": fmt(now + datetime.timedelta(days=55)),
       "categoryIds": "1", "productType": 1, "publishMode": "normal",
       "imgUrl": cover, "teamMode": 1, "teamMaxMembers": 4,
       "chapters": [{"name": "第一章", "description": "从外滩源出发。", "schemaVersion": 1, "required": 1,
                     "blocks": [{"type": "text", "content": "从外滩源出发。"},
                                {"type": "node", "nodeIndex": 0}, {"type": "node", "nodeIndex": 1}],
                     "nodes": nodes}],
       "tickets": [ticket]}
# A recruit deadline lets the publisher invite club 7002 as organiser before pricing (the sale gate needs
# exactly one ORGANIZER for guided tickets).
dto.update({"clubId": 7001} if SELF else {"clubId": 7001, "recruitDeadline": fmt(now + datetime.timedelta(days=1))})
r = http("POST", "/api/topic/v2/create", token=ptok, json_body=dto)
print(J(r))
d = r.get("data")
tid = d if isinstance(d, int) else (d or {}).get("topicId")
if not tid:
    sys.exit("create failed")
readback("topic", "SELECT id,product_type,lifecycle,status,user_status,open_club_pool FROM cms_topic WHERE id=%s", (tid,))

step("admin approves the publish audit tasks")
for task_id, biz, ver in q("SELECT id,biz_type,version FROM audit_task WHERE biz_id=%s AND status=0 ORDER BY id", (tid,)):
    print(biz, J(http("POST", "/business/audit/review", token=atok, json_body={
        "taskId": task_id, "status": 1, "reasonCode": "E2E", "reasonText": "走查放行", "expectedVersion": ver})))
readback("lifecycle after project review", "SELECT lifecycle,status,user_status FROM cms_topic WHERE id=%s", (tid,))

if MODE == "platform":
    step("admin sets the content slot to PLATFORM (platform-owned theme)")
    print(J(http("PUT", "/business/topic/%d/content-slot" % tid, token=atok, json_body={"entityType": "PLATFORM"})))
readback("content slot", "SELECT slot,entity_type,entity_id,status FROM product_role WHERE topic_id=%s", (tid,))

step("publisher invites club 7002 to organise; its leader accepts (ORGANIZER coop_order)")
if SELF:
    print("self mode: no partner club invited")
else:
  print(J(http("POST", "/api/coop/invite", token=ptok, json_body={
    "topicId": tid, "inviteType": 1, "toType": "club", "toId": 7002, "shareMode": 0, "message": "请带队"})))
  for (iid,) in q("SELECT id FROM coop_invite WHERE topic_id=%s AND status=0 ORDER BY id", (tid,)):
    print("accept", J(http("POST", "/api/coop/handle", token=etok, json_body={"id": iid, "status": 1})))
readback("coop_order", "SELECT id,role_snapshot,status FROM coop_order WHERE topic_id=%s", (tid,))

step("recruit deadline job: RECRUITING -> PRICING")
if not SELF:
  ex("UPDATE cms_topic SET recruit_deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=%s", (tid,))
  job = q("SELECT job_id,job_group FROM sys_job WHERE invoke_target=%s", (RECRUIT_JOB_TARGET,))[0]
  print(J(http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": job[0], "jobGroup": job[1]})))
  time.sleep(5)
readback("lifecycle", "SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))

step("author pricing page preview + confirm the final price")
pv = http("POST", "/api/topic/pricing/preview", token=ptok, json_body={"topicId": tid, "subType": "guided"})
print("preview", J(pv))
if os.environ.get("P01_STOP_BEFORE_CONFIRM"):
    # Screenshot staging: leave the theme in PRICING so the real pricing page can press 确认终价.
    print("STOPPED_IN_PRICING", tid)
    sys.exit(0)
# P-34 (round 3): P01_CONFIRM_PRICE confirms a price different from the author's ¥69 ticket, to prove it reaches it.
confirm_price = float(os.environ.get("P01_CONFIRM_PRICE", "69"))
r = http("POST", "/api/topic/pricing/confirm", token=ptok, json_body={"topicId": tid, "guidedPrice": confirm_price})
print("confirm", J(r))
readback("authored ticket prices after confirm (P-34)",
         "SELECT id,name,price,sub_type,total_inventory FROM oms_ticket WHERE owner_type=1 AND owner_id=%s AND del_flag=0 ORDER BY id", (tid,))
readback("sale review task", "SELECT id,biz_type,status FROM audit_task WHERE biz_id=%s AND biz_type='TOPIC_SALE_REVIEW'", (tid,))

step("admin: sale-submit (idempotent) then approve the sale review")
print("sale-submit", J(http("POST", "/business/audit/topic/%d/sale-submit" % tid, token=atok)))
for task_id, ver in q("SELECT id,version FROM audit_task WHERE biz_id=%s AND biz_type='TOPIC_SALE_REVIEW' AND status=0", (tid,)):
    print("approve", J(http("POST", "/business/audit/review", token=atok, json_body={
        "taskId": task_id, "status": 1, "reasonCode": "E2E", "reasonText": "开售放行", "expectedVersion": ver})))
readback("lifecycle/selling_started_at", "SELECT lifecycle,selling_started_at FROM cms_topic WHERE id=%s", (tid,))
print("preview after sale (P-34)", J(http("POST", "/api/topic/pricing/preview", token=ptok, json_body={"topicId": tid, "subType": "guided"})))
readback("payee facts untouched (no coop_order, no corporate payee snapshot)",
         "SELECT (SELECT COUNT(*) FROM coop_order WHERE topic_id=%s) AS coop_orders, "
         "(SELECT COUNT(*) FROM product_role WHERE topic_id=%s AND slot='CONTENT' AND entity_type='CLUB' AND entity_id=7001) AS content_slot_club7001",
         (tid, tid))

step("club %d opens a session; player 9009 quotes and signs up" % SESSION_CLUB)
day = (now + datetime.timedelta(days=3)).strftime("%Y-%m-%d")
print("open-session", J(http("POST", "/api/club/open-session", token=stok, json_body={
    "clubId": SESSION_CLUB, "topicId": tid, "sessionDate": day, "sessionTime": "14:00"})))
time.sleep(2)  # selling_started_at is stored rounded up to the next second; quote after it
acts = q("SELECT id FROM cms_activity WHERE topic_id=%s AND club_id=%s ORDER BY id DESC LIMIT 1", (tid, SESSION_CLUB))
if not acts:
    sys.exit("no session")
aid = acts[0][0]
tk = q("SELECT id,price FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id", (aid,))
print("activity", aid, "tickets", tk)
if SELF:
    # Fixture (same shape as seed_fixtures for 7002): player 9009 is also a member of club 7001.
    ex("INSERT INTO club_member(club_id,member_id,role,status,join_time,del_flag) "
       "SELECT 7001,9009,0,1,NOW(),0 WHERE NOT EXISTS "
       "(SELECT 1 FROM club_member WHERE club_id=7001 AND member_id=9009 AND del_flag=0)")
quote = http("POST", "/api/registration/quote", token=player, json_body={
    "ownerType": 2, "ownerId": aid, "ticketId": tk[0][0], "isUsePoint": 0})
print("quote", J(quote))
print("RESULT_TOPIC", tid, "ACTIVITY", aid, "TICKET", tk[0][0])
json.dump({"tid": tid, "aid": aid, "ticket": tk[0][0], "mode": MODE,
           "quoteSign": (quote.get("data") or {}).get("quoteSign")}, open(RIG + "/p01-%s.json" % MODE, "w"))

step("player 9009: consent, create the registration, pay through the rig seam -> real /api/registration/callback")
if quote.get("code") != 200:
    print("quote refused; no registration created (expected for a club-owned paid theme: assertCollectible)")
    sys.exit(0)
print(J(http("POST", "/api/compliance/consents", token=player, json_body={
    "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
    "requestId": "p01-consent-%d" % int(time.time())})))
created = http("POST", "/api/registration/create", token=player, json_body={
    "ownerType": 2, "ownerId": aid, "ticketId": tk[0][0], "isUsePoint": 0, "realName": "许知远",
    "phone": "13800000789", "quoteSign": quote["data"]["quoteSign"], "requestId": "p01-create-%d" % int(time.time())})
print("create", J(created))
rid = (created.get("data") or {}).get("registrationId")
pay = subprocess.run([sys.executable, os.path.expanduser("~/rig/docs/delivery/cloud-walk-20261101/rig/pay.py"),
                      "--member", "9009"], capture_output=True, text=True)
print("pay", pay.stdout.strip()[-400:] or pay.stderr[-400:])
readback("registration after callback", "SELECT id,owner_type,owner_id,ticket_id,payable_amount,payment_status,"
         "registration_status,purchase_kind FROM cms_registration WHERE id=%s", (rid,))
readback("verified payment fact", "SELECT business_out_trade_no,total_fen,verification_outcome FROM wx_verified_payment_fact "
         "WHERE business_out_trade_no=(SELECT registration_no FROM cms_registration WHERE id=%s)", (rid,))
