"""Fixture for the old-client 自由探索 case: one real merchant scan (of four) on 9004's paid ticket reg 76,
using the candidate's own chain_lib (real /api/verify/dyncode/issue + /api/registration/scan_qr_code)."""
import json, os, sys, io, contextlib
sys.path.insert(0, os.path.expanduser("~/walk-new/candidate/scripts/e2e"))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"): os.environ.pop(k, None)
from chain_lib import http, q, app_token
with contextlib.redirect_stdout(io.StringIO()):
    import chain_player as cp
ptok = open(os.path.expanduser("~/reh/t9004")).read().strip()
reg = 76
nid, ch, code, merchant = q("SELECT id,chapter_id,checkin_code,merchant_member_id FROM cms_topic_node WHERE topic_id=990025 AND merchant_member_id IS NOT NULL ORDER BY chapter_id")[0]
mtok, _ = app_token(cp.MERCHANT_PHONE_BY_MEMBER[merchant])
dyn = http("POST", "/api/verify/dyncode/issue", token=ptok, form={"registrationId": reg, "type": "activity"})
print("scan ch%s by merchant %s:" % (ch, merchant), json.dumps(http("POST", "/api/registration/scan_qr_code", token=mtok, form={"type": "activity", "code": dyn["data"]["code"]}), ensure_ascii=False)[:200])
print("readback reg", q("SELECT id,verification_status,verification_time FROM cms_registration WHERE id=%s", (reg,)))
print("readback entitlements", q("SELECT chapter_id,status FROM registration_entitlement WHERE registration_id=%s ORDER BY chapter_id", (reg,)))
