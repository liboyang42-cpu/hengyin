"""Platform admin approves pending media audit tasks through the real admin endpoint /business/audit/review."""
import os, sys
sys.path.insert(0, os.path.expanduser('~/k10/scripts/e2e'))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items(): os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"): os.environ.pop(k, None)
import chain_lib as cl
atok = cl.admin_token()
for tid_, bt, bid, ver in cl.q("SELECT id,biz_type,biz_id,version FROM audit_task WHERE status=0 ORDER BY id"):
    if len(sys.argv) > 1 and bt not in sys.argv[1:]: print('skip', tid_, bt, bid); continue
    r = cl.http("POST", "/business/audit/review", token=atok, json_body={"taskId": tid_, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})
    print(tid_, bt, bid, (r.get('msg') or '')[:80])
