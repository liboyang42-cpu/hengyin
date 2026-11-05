"""Platform side (played by me as the admin, real admin endpoint): approve pending audit tasks of the given biz types."""
from common import *
atok = cl.admin_token()
types = sys.argv[1:]
for tid_, bt, bid, ver in cl.q("SELECT id,biz_type,biz_id,version FROM audit_task WHERE status=0 ORDER BY id"):
    if types and bt not in types and str(bid) not in types: print('skip', tid_, bt, bid); continue
    r = cl.http("POST", "/business/audit/review", token=atok, json_body={"taskId": tid_, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})
    print(tid_, bt, bid, (r.get('msg') or '')[:80])
