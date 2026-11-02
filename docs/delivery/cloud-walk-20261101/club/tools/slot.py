import sys; sys.path.insert(0, '/root/sc')
from common import *
atok = cl.admin_token(); tid = int(sys.argv[1])
print(J(cl.http("PUT", "/business/topic/%d/content-slot" % tid, token=atok, json_body={"entityType": "PLATFORM"})))
print(cl.q("SELECT slot,entity_type,entity_id,status FROM product_role WHERE topic_id=%s ORDER BY id", (tid,)))
