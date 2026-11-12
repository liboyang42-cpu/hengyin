"""Rig-only (r2-create): give fixture member 9002 four more published node templates via the real
POST /api/template/publish (+ admin review), so the template picker rail has enough cards to show the peek.
Usage: CAND=<candidate> PATH=$RIG_HOME/work/bin:$PATH $RIG_HOME/venv/bin/python seed_my_templates.py"""
import datetime, json, os, pathlib, sys
for p in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token, admin_token, q, readback

tok, _ = app_token("13900000002")
atok = admin_token()
status = http("POST", "/api/publisher/identity/status", token=tok)
if not ((status.get("data") or {}).get("registered")):
    status = http("POST", "/api/publisher/identity", token=tok, json_body={
        "realName": "测试商家", "idCard": "110101199001010023", "consent": True, "source": "topic_publish"})
print("identity", json.dumps(status, ensure_ascii=False)[:160])
stamp = datetime.datetime.now().strftime("%m%d%H%M%S")
for i, title in enumerate(["街角谜题", "拍下这一刻", "今晚的暗号", "咖啡风味人格"]):
    r = http("POST", "/api/template/publish", token=tok, json_body={
        "title": title, "validationMethod": 0, "requestId": "r2c-tpl-%s-%d" % (stamp, i)})
    tid = r.get("data") if isinstance(r.get("data"), int) else (r.get("data") or {}).get("id")
    print("publish", title, json.dumps(r, ensure_ascii=False)[:160])
    if not tid:
        continue
    for row in q("SELECT id, version FROM audit_task WHERE biz_id=%s AND status=0 AND biz_type LIKE %s", (tid, "%TEMPLATE%")):
        rv = http("POST", "/business/audit/review", token=atok, json_body={
            "taskId": row[0], "status": 1, "expectedVersion": row[1], "reasonCode": "RIG", "reasonText": "rig 放行"})
        print("  review", json.dumps(rv, ensure_ascii=False)[:120])
    readback("template", "SELECT id,member_id,status,publish_status FROM cms_member_template WHERE id=%s", (tid,))
