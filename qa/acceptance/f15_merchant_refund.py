"""F15 Merchant refund: player cancel-refund on a free registration; merchant orders + aftercare list/detail/respond.
Merchants cannot approve refunds (platform review); respond only adds an opinion while refund_application.status=0.
Needs E2E_REFUND_REG (a cancellable registration of 9004) and E2E_PENDING_REFUND_ID (status=0 refund of a 9002 activity)."""
from harness import *


def flow(f):
    mer, p = MERCHANT, PLAYER
    f.step("merchant/orders", "POST", "/api/merchant/orders", mer, js={}, check=ok_data)
    lst = f.step("merchant/aftercare/list PENDING", "POST", "/api/merchant/aftercare/list", mer,
                 q={"bucket": "PENDING", "pageNum": 1, "pageSize": 20}, check=ok_data)
    f.step("player cannot read merchant aftercare", "POST", "/api/merchant/aftercare/list", p,
           q={"bucket": "ALL", "pageNum": 1, "pageSize": 20}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 else "player read merchant aftercare")
    reg = os.environ.get("E2E_REFUND_REG")
    if reg:
        f.step("player registration/cancel-refund", "POST", "/api/registration/cancel-refund", p, form={"id": reg})
        f.step("registration/info cancelled", "POST", "/api/registration/info", p, form={"id": reg},
               check=lambda j: None if (data_of(j) or {}).get("registrationStatus") == 4 else "not cancelled")
    rf = os.environ.get("E2E_PENDING_REFUND_ID")
    if not rf:
        f.block("no pending (status=0) refund_application for a 9002 activity on TEST - data seeding needed")
    f.step("merchant/aftercare/detail", "POST", "/api/merchant/aftercare/detail", mer, q={"refundId": rf}, check=ok_data)
    f.step("merchant/aftercare/respond AGREE", "POST", "/api/merchant/aftercare/respond", mer, q={"refundId": rf},
           js={"decision": "AGREE", "content": "QA 同意退款", "requestId": rid("rf")})


flow.FLOW = ("F15", "Merchant refund")
if __name__ == "__main__":
    main(flow)
