import sys; sys.path.insert(0, '/root/sc')
from common import *
atok = cl.admin_token()
for tid_, bt, bid, ver in cl.q("SELECT id,biz_type,biz_id,version FROM audit_task WHERE status=0 ORDER BY id"):
    r = cl.http("POST", "/business/audit/review", token=atok, json_body={"taskId": tid_, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})
    print(tid_, bt, bid, (r.get('msg') or '')[:60])
