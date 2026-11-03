"""Round 3D screenshot step: player cancels order <id> through /api/registration/cancel-refund; with 'done' it instead
sets that order's refund_application.payout_status=1 as a disclosed FIXTURE (the rig's payment stub refuses refund
callbacks, so 退款成功 cannot be reached for real here). payment_status is not touched."""
import sys
from rig_env import http, q, app_token, show, j, readback
import chain_lib
reg = int(sys.argv[1])
if len(sys.argv) > 2 and sys.argv[2] == "done":
    n = chain_lib.ex("UPDATE refund_application SET payout_status=1 WHERE source_type='registration' AND source_id=%s "
                     "AND payout_status IN (0,5,6)", (reg,))
    show("FIXTURE payout_status -> 1 rows", n)
else:
    ptok, _ = app_token("13900000009")
    show("cancel-refund", j(http("POST", "/api/registration/cancel-refund", token=ptok, form={"id": reg}), 400))
readback("refund_application", "SELECT refund_no,refund_amount,status,payout_status FROM refund_application "
         "WHERE source_type='registration' AND source_id=%s", (reg,))
readback("cms_registration", "SELECT payment_status,registration_status FROM cms_registration WHERE id=%s", (reg,))
