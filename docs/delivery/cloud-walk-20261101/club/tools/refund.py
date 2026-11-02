import sys; sys.path.insert(0, '/root/sc')
from common import *
tok, me = login(sys.argv[1]); act = int(sys.argv[2])
rid = cl.q("SELECT id FROM cms_registration WHERE member_id=%s AND owner_type=2 AND owner_id=%s", (me['id'], act))[0][0]
print(J(cl.http("POST", "/api/registration/cancel-refund", token=tok, form={"id": rid})))
print(cl.q("SELECT id,payment_status,payable_amount FROM cms_registration WHERE id=%s", (rid,)))
