"""F04 Register and pay (TEST money): consent -> address -> quote -> create (free) -> info -> list; then the paid path on
the run's live QA activity (fixtures.py): paid quote -> paid create (pending payment) -> pay/app.
Bodies follow the native client: RegistrationQuoteRequest {ownerType, ownerId, ticketId, isUsePoint} and
RegistrationCreateIntent {+ realName, phone, quoteSign, payChannel APP, requestId} (Core/RegistrationContracts.swift).
TEST has no pay sandbox, so pay/app is TEST-SKIP with the fixture's reason (E2E_PAY_SKIP)."""
from harness import *

NAME = "玩家小城"


def flow(f):
    m = PLAYER
    f.step("consent activity_signup", "POST", "/api/compliance/consents", m,
           js={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": rid("c")})
    f.step("user/address/action participant", "POST", "/api/user/address/action", m,
           form={"fullName": NAME, "mobilePhone": PHONE[m], "isDefault": 1, "requestId": rid("a")})
    sel = {"ownerType": 2, "ownerId": ACTIVITY, "ticketId": FREE_TICKET, "isUsePoint": 0}
    q = f.step("registration/quote (native shape)", "POST", "/api/registration/quote", m, js=sel,
               check=lambda j: None if float((data_of(j) or {}).get("payAmount", -1)) == 0
               and (data_of(j) or {}).get("quoteSign") else "payAmount != 0 or no quoteSign")
    req = os.environ.get("E2E_REG_REQUEST_ID") or rid("r")
    create = dict(sel, realName=NAME, phone=PHONE[m], quoteSign=(data_of(q) or {}).get("quoteSign"), payChannel="APP", requestId=req)
    c = f.step("registration/create free", "POST", "/api/registration/create", m, js=create,
               expect_code=(200,), check=lambda j: need(j, "registrationId"))
    reg = (data_of(c) or {}).get("registrationId")
    os.environ["E2E_F04_FREE_REG"] = str(reg or "")
    f.step("registration/create replay is idempotent", "POST", "/api/registration/create", m, js=create,
           check=lambda j: None if (data_of(j) or {}).get("registrationId") == reg else "replay created a new order")
    f.step("registration/info paid (free = paid)", "POST", "/api/registration/info", m, form={"id": reg},
           check=lambda j: None if (data_of(j) or {}).get("paymentStatus") == 2 else "paymentStatus != 2")
    f.step("registration/list all orders", "POST", "/api/registration/list", m, form={"owner_type": 3},
           check=lambda j: None if str(reg) in str(data_of(j)) else "order not listed")
    # Paid path (TEST money) on the run's live activity.
    pay_skip = os.environ.get("E2E_PAY_SKIP")
    act, pt = os.environ.get("E2E_LIVE_ACTIVITY"), os.environ.get("E2E_LIVE_PAID_TICKET")
    if not (act and pt):
        raise Blocked("no paid ticket fixture (run fixtures.py first)")
    if pay_skip:
        f.step("payment-readiness reports TEST pay unavailable", "POST", "/api/registration/payment-readiness", m,
               check=lambda j: None if (data_of(j) or {}).get("ready") is False and (data_of(j) or {}).get("message")
               else "readiness not a clear not-ready answer")
    else:
        f.step("payment-readiness ready", "POST", "/api/registration/payment-readiness", m,
               check=lambda j: None if (data_of(j) or {}).get("ready") else "not ready")
    psel = {"ownerType": 2, "ownerId": int(act), "ticketId": int(pt), "isUsePoint": 0}
    pq = f.step("paid quote", "POST", "/api/registration/quote", m, js=psel,
                check=lambda j: None if float((data_of(j) or {}).get("payAmount", 0)) > 0 else "payAmount not > 0")
    pbody = dict(psel, realName=NAME, phone=PHONE[m], quoteSign=(data_of(pq) or {}).get("quoteSign"), payChannel="APP",
                 requestId=rid("p"))
    if pay_skip:
        # Without a pay channel the backend fails closed before it writes an order (RegistrationCheckoutServiceImpl).
        f.step("paid create refused cleanly while TEST pay is unavailable", "POST", "/api/registration/create", m,
               js=pbody, expect_code=(503,), check=lambda j: None if "支付服务暂不可用" in (j.get("msg") or "") else "msg=%s" % j.get("msg"))
        f.test_skip("paid create -> pending order -> pay/app payParams", "POST", "/api/registration/pay/app", pay_skip)
        return
    pc = f.step("paid create (pending payment)", "POST", "/api/registration/create", m, js=pbody,
                check=lambda j: need(j, "registrationId"))
    preg = (data_of(pc) or {}).get("registrationId")
    f.step("paid order is pending payment", "POST", "/api/registration/info", m, form={"id": preg},
           check=lambda j: None if (data_of(j) or {}).get("paymentStatus") in (0, 1) else
           "paymentStatus=%s" % (data_of(j) or {}).get("paymentStatus"))
    f.step("pay/app returns payParams", "POST", "/api/registration/pay/app", m, form={"id": preg},
           check=lambda j: need(j, "payParams"))
    f.step("cancel the unpaid order (cleanup)", "POST", "/api/registration/cancel", m, form={"id": preg})


flow.FLOW = ("F04", "Register and pay")
if __name__ == "__main__":
    main(flow)
