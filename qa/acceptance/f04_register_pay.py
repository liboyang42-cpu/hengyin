"""F04 Register and pay (TEST money): consent -> address -> quote -> create (free) -> info -> list;
then the paid path (payment-readiness + pay/app) when a paid ticket fixture exists (E2E_PAID_ACTIVITY/E2E_PAID_TICKET)."""
from harness import *

NAME = "玩家小城"


def flow(f):
    m = PLAYER
    f.step("consent activity_signup", "POST", "/api/compliance/consents", m,
           js={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": rid("c")})
    f.step("user/address/action participant", "POST", "/api/user/address/action", m,
           form={"fullName": NAME, "mobilePhone": PHONE[m], "isDefault": 1, "requestId": rid("a")})
    body = {"ownerType": 2, "ownerId": ACTIVITY, "ticketId": FREE_TICKET,
            "tickets": [{"ticketId": FREE_TICKET, "count": 1}], "realName": NAME, "phone": PHONE[m], "isUsePoint": 0}
    q = f.step("registration/quote", "POST", "/api/registration/quote", m, js=body,
               check=lambda j: None if float((data_of(j) or {}).get("payAmount", -1)) == 0
               and (data_of(j) or {}).get("quoteSign") else "payAmount != 0 or no quoteSign")
    app_q = f.step("registration/quote as the app sends it (no tickets/realName)", "POST", "/api/registration/quote", m,
                   js={"ownerType": 2, "ownerId": ACTIVITY, "ticketId": FREE_TICKET, "isUsePoint": 0}, owner="app")
    req = os.environ.get("E2E_REG_REQUEST_ID") or rid("r")
    c = f.step("registration/create free", "POST", "/api/registration/create", m,
               js=dict(body, quoteSign=(data_of(q) or {}).get("quoteSign"), requestId=req, payChannel="APP"),
               expect_code=(200,), check=lambda j: need(j, "registrationId"))
    reg = (data_of(c) or {}).get("registrationId")
    f.ctx["registrationId"] = reg
    f.step("registration/create replay is idempotent", "POST", "/api/registration/create", m,
           js=dict(body, quoteSign=(data_of(q) or {}).get("quoteSign"), requestId=req, payChannel="APP"),
           check=lambda j: None if (data_of(j) or {}).get("registrationId") == reg else "replay created a new order")
    f.step("registration/info paid (free = paid)", "POST", "/api/registration/info", m, form={"id": reg},
           check=lambda j: None if (data_of(j) or {}).get("paymentStatus") == 2 else "paymentStatus != 2")
    f.step("registration/list all orders", "POST", "/api/registration/list", m, form={"owner_type": 3},
           check=lambda j: None if str(reg) in str(data_of(j)) else "order not listed")
    # Paid path (TEST money)
    pa, pt = os.environ.get("E2E_PAID_ACTIVITY"), os.environ.get("E2E_PAID_TICKET")
    f.step("payment-readiness (paid channel)", "POST", "/api/registration/payment-readiness", m,
           check=lambda j: None if (data_of(j) or {}).get("ready") else "payment not ready on TEST: %s" % (data_of(j) or {}).get("message"))
    if not (pa and pt):
        f.block("no paid ticket fixture on TEST (set E2E_PAID_ACTIVITY/E2E_PAID_TICKET) - data seeding needed")
    pb = dict(body, ownerId=int(pa or 0), ticketId=int(pt or 0), tickets=[{"ticketId": int(pt or 0), "count": 1}])
    pq = f.step("paid quote", "POST", "/api/registration/quote", m, js=pb,
                check=lambda j: None if float((data_of(j) or {}).get("payAmount", 0)) > 0 else "payAmount not > 0")
    pc = f.step("paid create", "POST", "/api/registration/create", m,
                js=dict(pb, quoteSign=(data_of(pq) or {}).get("quoteSign"), requestId=rid("p"), payChannel="APP"),
                check=lambda j: need(j, "registrationId"))
    f.step("pay/app returns payParams (TEST pay stub)", "POST", "/api/registration/pay/app", m,
           form={"id": (data_of(pc) or {}).get("registrationId")}, check=lambda j: need(j, "payParams"))


flow.FLOW = ("F04", "Register and pay")
if __name__ == "__main__":
    main(flow)
