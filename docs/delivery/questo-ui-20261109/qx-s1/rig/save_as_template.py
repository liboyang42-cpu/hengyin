"""Rig-only (qx-s1): publisher 9003 saves the fixture topic as a topic template (real /save-as), then the player reads /info."""
import json, os, sys, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token, admin_token, q, readback
tid = int(sys.argv[1])
ltok, _ = app_token("13900000003"); ptok, _ = app_token("13900000004")
r = http("POST", "/api/template/topic-template/save-as", token=ltok, form={"id": tid})
print("save-as", json.dumps(r, ensure_ascii=False)[:300])
tpl = (r.get("data") or {}).get("templateId")
if tpl:
    readback("template topic", "SELECT id,is_template,status,member_id FROM cms_topic WHERE id=%s", (tpl,))
    readback("pending audit", "SELECT id,biz_type,status FROM audit_task WHERE biz_id=%s", (tpl,))
    info = http("POST", "/api/template/topic-template/info", token=ptok, form={"id": tpl})
    print("info.contentDelivery", json.dumps((info.get("data") or {}).get("contentDelivery"), ensure_ascii=False)[:900])
    print("info.msg", info.get("msg"))
print("TEMPLATE_ID=%s" % tpl)
