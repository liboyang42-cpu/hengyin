import sys; sys.path.insert(0, '/root/sc')
from common import *
tid = int(sys.argv[1]); atok = cl.admin_token()
print(cl.q("SELECT lifecycle,status,user_status FROM cms_topic WHERE id=%s", (tid,)))
r = cl.http("POST", "/business/audit/topic/%d/sale-submit" % tid, token=atok); print('sale-submit', J(r))
