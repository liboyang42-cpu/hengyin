"""qx-c1 real-HTTP proof against the rig (candidate = this branch). Fixture rows are inserted by SQL only where no
product write path exists for them in a fresh DB; every asserted behaviour is a real HTTP call, read back from MySQL.
Output: docs/delivery/questo-ui-20261109/qx-c1/proof/rig-http.json
"""
import json, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import rigkit as r

OUT = Path(__file__).resolve().parents[1] / "proof" / "rig-http.json"
TOPIC, OWNER = 990013, 9003
T4, T5 = r.app_token("e2e-openid-9004"), r.app_token("e2e-openid-9005")
T3 = r.app_token("e2e-openid-9003")
ADMIN = r.cl.admin_token()
results = {}

def check(item, ok, detail):
    results[item] = {"ok": bool(ok), "detail": detail}
    print(("PASS " if ok else "FAIL ") + item, json.dumps(detail, ensure_ascii=False, default=str)[:400])

# ---------------- fixtures (SQL; no product write path in an empty rig DB) ----------------
r.x("delete from cms_topic_content_revision where topic_id=%s", TOPIC)
r.x("delete from cms_member_template where id in (7701,7702)")
r.x("insert into cms_member_template(id,member_id,title,status,draft_status,publish_status,del_flag) values(7701,%s,'qx-c1 节点模板',1,0,1,0)", OWNER)
r.x("delete from cms_topic_node where id in (880001)")
r.x("insert into cms_topic_node(id,topic_id,name,template_id,longitude,latitude,address) values(880001,%s,'钟楼',7701,'121.47','31.23','上海市黄浦区')", TOPIC)
r.x("update cms_topic set is_template=1 where id=%s", TOPIC)

# ---------------- #1 + #8: /use through the Druid wall, requestId idempotency ----------------
before = r.q("select count(*) n from cms_topic where member_id=9004")[0]["n"]
first = r.call("POST", "/api/template/topic-template/use", T4, form={"id": TOPIC, "requestId": "qxc1-use-0001"}, label="#1/#8 use first")
replay = r.call("POST", "/api/template/topic-template/use", T4, form={"id": TOPIC, "requestId": "qxc1-use-0001"}, label="#8 use replay")
changed = r.call("POST", "/api/template/topic-template/use", T4, form={"id": TOPIC, "requestId": "qxc1-use-0001", "bindingIntent": "NEW_CITY", "targetCityCode": "320100"}, label="#8 use same id other payload")
after = r.q("select count(*) n from cms_topic where member_id=9004")[0]["n"]
new_topic = (first.get("data") or {}).get("topicId")
copied = r.q("select n.id,n.template_id,t.member_id from cms_topic_node n left join cms_member_template t on t.id=n.template_id where n.topic_id=%s", new_topic) if new_topic else []
receipt = r.q("select actor_member_id,operation,request_id,left(result_json,80) result from app_author_write_receipt where operation='TOPIC_TEMPLATE_USE'")
r.note("#1/#8 read-back", {"topicsBefore": before, "topicsAfter": after, "copiedNodes": copied, "receipt": receipt})
check("#8 requestId replay/mismatch", first.get("code") == 200 and replay.get("code") == 200 and replay["data"]["topicId"] == new_topic
      and changed.get("code") == 409 and after - before == 1 and len(receipt) == 1,
      {"first": first.get("code"), "replayTopic": (replay.get("data") or {}).get("topicId"), "changed": [changed.get("code"), changed.get("msg")], "newTopics": after - before})

# #1: template publish runs WorkshopLicenseGuard.requirePublication -> WorkshopMapper.lockLicenseForDraft through the
# app's Druid wall. Draft 7702 carries an installed but unpaid license: the locked order row must be read and refused
# as a business rule (403 WORKSHOP_LICENSE_DISABLED), not a wall "sql injection violation" (500).
for sql in ("delete from workshop_install where draft_id in (7701,7702)", "delete from workshop_order where order_no='QXC1LIC0001'",
            "delete from workshop_version where version_id='qxc1-ver'", "delete from cms_member_template where id in (7702,7703)"):
    r.x(sql)
r.x("insert into cms_member_template(id,member_id,title,status,draft_status,publish_status,del_flag) values(7702,9004,'qx-c1 已安装草稿',0,0,0,0),(7703,9004,'qx-c1 无授权草稿',0,0,0,0)")
r.x("insert into workshop_version(version_id,seller_id,request_id,request_hash,module_id,source_canonical_id,content_hash,content_json,terms_hash,terms_json,price_fen) values('qxc1-ver',9003,'qxc1-v',%s,7701,7701,%s,'{}',%s,'{}',100)", "a"*64, "b"*64, "c"*64)
r.x("insert into workshop_order(order_no,buyer_id,request_id,request_hash,version_id,amount_fen,provider,state,license_status) values('QXC1LIC0001',9004,'qxc1-lic',%s,'qxc1-ver',100,'WECHAT_APP','PENDING','UNPAID')", "d"*64)
r.x("insert into workshop_install(buyer_id,request_id,order_no,command_hash,command_json,draft_id) values(9004,'qxc1-install','QXC1LIC0001',%s,'{}',7702)", "e"*64)
lic = r.call("POST", "/api/template/publish", T4, body={"id": 7702, "title": "qx-c1 已安装草稿", "validationMethod": 0}, label="#1 publish licensed draft (lock row read through wall)")
plain = r.call("POST", "/api/template/publish", T4, body={"id": 7703, "title": "qx-c1 无授权草稿", "validationMethod": 0}, label="#1 publish draft without license (lock returns no row)")
order = r.q("select order_no,state,license_status from workshop_order where order_no='QXC1LIC0001'")
r.note("#1 read-back", {"order": order})
text = json.dumps([lic, plain], ensure_ascii=False).lower()
check("#1 license lock passes the Druid wall", lic.get("code") == 403 and "license" in text and "sql" not in text
      and plain.get("msg") != "系统繁忙，请稍后重试" and order and order[0]["state"] == "PENDING",
      {"licensed": [lic.get("code"), lic.get("msg")], "unlicensed": [plain.get("code"), plain.get("msg")], "order": order})

# ---------------- #5(a): placeCountryCode on the adopted draft (owner 9004) ----------------
pc_bad = r.call("POST", "/api/template/topic-template/place-country", T4, form={"topicId": new_topic, "placeCountryCode": "jp"}, label="#5a lowercase rejected")
pc_other = r.call("POST", "/api/template/topic-template/place-country", T5, form={"topicId": new_topic, "placeCountryCode": "JP"}, label="#5a stranger")
pc = r.call("POST", "/api/template/topic-template/place-country", T4, form={"topicId": new_topic, "placeCountryCode": "JP"}, label="#5a owner sets JP")
stored = r.q("select json_unquote(json_extract(content_contract_json,'$.placeCountryCode')) c from cms_topic where id=%s", new_topic)
r.note("#5a read-back", stored)
check("#5a placeCountryCode", pc.get("code") == 200 and stored[0]["c"] == "JP" and pc_bad.get("code") == 400 and pc_other.get("code") in (403, 404),
      {"owner": pc.get("code"), "bad": [pc_bad.get("code"), pc_bad.get("msg")], "stranger": pc_other.get("code"), "db": stored})

# ---------------- #9: preset missing fields ----------------
pr = r.call("POST", "/api/preset/gameplay/draft", T4, body={"presetKey": "T08-multi-clue-investigation", "fields": {}}, label="#9 preset with nothing filled")
rf = (pr.get("data") or {}).get("requiredFields") or []
check("#9 structured requiredFields", pr.get("msg") != "系统繁忙，请稍后重试" and len(rf) > 0 and (pr.get("data") or {}).get("code") == "PRESET_FIELDS_REQUIRED",
      {"msg": pr.get("msg"), "count": len(rf), "first": rf[:2]})

# ---------------- #6: open campaigns ----------------
r.x("delete from video_bounty_campaign where campaign_key like 'qxc1-%%'")
for key, status in (("qxc1-open", 1), ("qxc1-paused", 2), ("qxc1-draft", 0)):
    r.x("insert into video_bounty_campaign(campaign_key,title,description,status,owner_member_id,currency,rate_per_thousand,per_video_cap,window_days,rule_version,create_time,update_time) values(%s,%s,'拍一条玩过的主题',%s,9003,'CNY',2.0000,500.00,14,'v1',now(),now())", key, "活动 " + key, status)
cl = r.call("GET", "/api/video-bounty/campaign/list", T4, label="#6 campaign list")
anon = r.call("GET", "/api/video-bounty/campaign/list", None, label="#6 campaign list anonymous")
keys = [row["campaignKey"] for row in cl.get("data") or []]
check("#6 only active campaigns, no money rules", keys == ["qxc1-open"] and set((cl["data"][0]).keys()) == {"campaignKey", "title", "description", "windowDays"} and anon.get("code") == 401,
      {"keys": keys, "row": (cl.get("data") or [None])[0], "anon": anon.get("code")})

# ---------------- #7: post-game feedback ----------------
r.x("delete from play_run_feedback where request_id like 'qxc1-%%'")
r.x("delete from topic_route_session where id=770001")
r.x("insert into topic_route_session(id,activity_id,topic_id,member_id,play_instance_id,config_version,config_snapshot_json,state_json,status,seed,version,create_time,update_time) values(770001,0,%s,9004,'qxc1-pi-1',1,'{\"nodeConfigs\":{}}','{}',2,1,0,now(3),now(3))", TOPIC)
fb = {"runId": 770001, "nodeId": 880001, "category": "PLACE", "content": "钟楼门口的二维码被撕掉了", "requestId": "qxc1-fb-0001"}
f1 = r.call("POST", "/api/play/feedback/submit", T4, body=fb, label="#7 first")
f2 = r.call("POST", "/api/play/feedback/submit", T4, body=fb, label="#7 replay")
f3 = r.call("POST", "/api/play/feedback/submit", T4, body=dict(fb, content="改了内容"), label="#7 same id other content")
f4 = r.call("POST", "/api/play/feedback/submit", T5, body=dict(fb, requestId="qxc1-fb-0002"), label="#7 someone else's run")
rows = r.q("select id,member_id,route_session_id,topic_id,config_version,content_revision_id,node_id,category,content,request_id from play_run_feedback where request_id like 'qxc1-%%'")
r.note("#7 read-back", rows)
check("#7 feedback owner/idempotent", f1.get("code") == 200 and f2.get("code") == 200 and f2["data"]["feedbackId"] == f1["data"]["feedbackId"]
      and f3.get("code") == 409 and f4.get("code") == 404 and len(rows) == 1 and rows[0]["node_id"] == 880001,
      {"codes": [f1.get("code"), f2.get("code"), f3.get("code"), f4.get("code")], "rows": len(rows)})

# ---------------- #4: UG-12 admin read-only projection ----------------
snap = json.dumps({"hintFreeUsesPerRun": 1, "nodeConfigs": {"880001": {"hintLadder": [
    {"level": 1, "type": "TEXT", "text": "提示正文不该出现", "cost": {"points": 0}},
    {"level": 2, "type": "TEXT", "text": "第二层正文", "cost": {"points": 5}}]}},
    "nodeAnswers": {"880001": {"answer": "答案不该出现", "aliases": ["别名不该出现"]}}}, ensure_ascii=False)
r.x("update topic_route_session set config_snapshot_json=%s where id=770001", snap)
r.x("delete from cms_topic_content_revision where topic_id=%s", TOPIC)
r.x("insert into cms_topic_content_revision(topic_id,revision_no,schema_version,content_sha256,snapshot_json,public_manifest_json,origin,create_time) values(%s,1,1,%s,%s,'{}','PUBLISH',now())", TOPIC, "c"*64, snap)
a1 = r.call("GET", "/business/playhelp/topic/%d/config" % TOPIC, ADMIN, label="#4 topic config (admin)")
a2 = r.call("GET", "/business/playhelp/run/770001", ADMIN, label="#4 run lines (admin)")
a3 = r.call("GET", "/business/playhelp/run/770001", T4, label="#4 run lines (app token)")
dumped = json.dumps([a1, a2], ensure_ascii=False)
grants = r.q("select count(*) n from sys_role_menu rm join sys_menu m on m.menu_id=rm.menu_id where m.perms='business:playhelp:query'")
r.note("#4 read-back grants", grants)
check("#4 admin read-only projection", a1.get("code") == 200 and a1["data"]["nodes"][0]["layerCount"] == 2 and a1["data"]["freeUsesPerRun"] == 1
      and a2.get("code") == 200 and "不该出现" not in dumped and "第二层正文" not in dumped and a3.get("code") != 200 and grants[0]["n"] == 0,
      {"topic": a1.get("data"), "run": (a2.get("data") or {}).get("nodes"), "appToken": a3.get("code"), "grants": grants})

# ---------------- #10: audit domain_type via the real verify-group write ----------------
camp = r.q("select id from video_bounty_campaign where campaign_key='qxc1-open'")[0]["id"]
r.x("delete from video_bounty_submission where canonical_video_key='qxc1-video-1'")
sid = r.x("insert into video_bounty_submission(campaign_id,campaign_key,member_id,topic_id,platform,source_url,canonical_video_key,origin_group_key,status,submitted_at,create_time,update_time) values(%s,'qxc1-open',9004,%s,'tiktok','https://www.tiktok.com/@a/video/7300000000000000099','qxc1-video-1','qxc1-g1',2,now(),now(),now())", camp, TOPIC)
v = r.call("POST", "/business/videobounty/submission/%d/verify-group" % sid, ADMIN, body={"verifiedGroupKey": "qxc1-group", "sourceRef": "manual:qxc1", "reason": "同一原视频"}, label="#10 verify group")
audit = r.q("select domain_type,domain_id,action,target_type from business_audit_event where domain_id=%s and action='VIDEO_BOUNTY_GROUP_VERIFY'", sid)
r.note("#10 read-back", audit)
check("#10 audit row written with 16-char domain", v.get("code") == 200 and audit and audit[0]["domain_type"] == "VIDEO_BOUNTY",
      {"code": v.get("code"), "msg": v.get("msg"), "audit": audit})

# ---------------- #2: collation (real HTTP before/after on the rig DB) ----------------
import subprocess
MIG = r.CAND / "chengyinhub-admin" / "sql" / "migration_zz_questo_collation_align_20261109.sql"
ok_before = r.call("POST", "/api/play/journey/opening/complete", T4, body={"topicId": TOPIC}, label="#2 gift join with aligned collation")
r.x("alter table marketing_product_order modify column order_no varchar(40) character set utf8mb4 collate utf8mb4_0900_ai_ci not null")
broken = r.call("POST", "/api/play/journey/opening/complete", T4, body={"topicId": TOPIC}, label="#2 same call with order_no reverted to 0900_ai_ci (pre-migration state)")
mig = subprocess.run(["docker", "exec", "-i", "cy-chain-local-1-mysql", "mysql", "-uroot", "--default-character-set=utf8mb4", "-N", "cy_e2e_51"],
                     input=MIG.read_bytes(), capture_output=True)
r.note("#2 migration re-applied", {"rc": mig.returncode, "out": mig.stdout.decode()[-200:], "err": mig.stderr.decode()[-300:]})
fixed = r.call("POST", "/api/play/journey/opening/complete", T4, body={"topicId": TOPIC}, label="#2 same call after migration")
coll = r.q("select collation_name as collation_name from information_schema.columns where table_schema=database() and table_name='marketing_product_order' and column_name='order_no'")
r.note("#2 read-back", coll)
check("#2 gift entitlement join no longer fails on collation", broken.get("msg") == "系统繁忙，请稍后重试" and mig.returncode == 0
      and fixed.get("msg") != "系统繁忙，请稍后重试" and fixed.get("msg") == ok_before.get("msg") and coll[0]["collation_name"] == "utf8mb4_general_ci",
      {"before": broken.get("msg"), "after": fixed.get("msg"), "collation": coll})

r.note("summary", results)
r.dump(OUT)
print("wrote", OUT)
