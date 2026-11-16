"""F14 Merchant scan-to-verify (coupon). Merchant 9005 (store 8002) publishes and stops a coupon as the native merchant
console does (CouponManagementContract: JSON publish with scope MERCHANT; multipart stop). Merchant 9002 (store 8001)
verifies the coupon fixtures.py granted to player 9004 on 9002's store coupon (scope_type 1: merchant redemption
requires a store coupon, SmsCouponHistoryServiceImpl.redeemCoupon). Native redemption sends only {code}
(MerchantRedemptionContext). The player has no in-app 'receive' path, so the grant is a fixture."""
import time
from harness import *


def flow(f):
    pub, mer, p = 9005, MERCHANT, PLAYER
    now = time.time()
    fmt = lambda t: time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t))
    c = f.step("coupon/publish (merchant 9005, native JSON)", "POST", "/api/coupon/publish", pub,
               js={"scope": "MERCHANT", "name": "QA 验收券 %s" % RUN_ID, "description": "acceptance", "startTime": fmt(now),
                   "endTime": fmt(now + 7 * 86400), "publishCount": 1, "couponType": 1}, expect_code=(200,))
    cid = data_of(c) if isinstance(data_of(c), int) else (data_of(c) or {}).get("id") if isinstance(data_of(c), dict) else None
    hid = os.environ.get("E2E_COUPON_HISTORY_ID")
    f.step("coupon/myrecvlist (player)", "POST", "/api/coupon/myrecvlist", p, check=ok_data)
    if not hid:
        raise Blocked("no granted coupon (run fixtures.py first)")
    t = f.step("coupon/qr-token (player)", "POST", "/api/coupon/qr-token", p, form={"couponHistoryId": hid},
               check=lambda j: need(j, "token"))
    tok = (data_of(t) or {}).get("token")
    f.step("coupon/verification (merchant 9002)", "POST", "/api/coupon/verification", mer, form={"code": tok},
           check=lambda j: None if j.get("msg") == "核销成功" else "msg=%s" % j.get("msg"))
    f.step("coupon/verification replay", "POST", "/api/coupon/verification", mer, form={"code": tok},
           check=lambda j: None if (data_of(j) or {}).get("alreadyUsed") else "replay not flagged alreadyUsed")
    f.step("coupon/verification by another merchant refused", "POST", "/api/coupon/verification", pub,
           form={"code": tok}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 or (data_of(j) or {}).get("alreadyUsed") else "foreign merchant verified")
    f.step("coupon/status used", "POST", "/api/coupon/status", p, form={"couponHistoryId": hid},
           check=lambda j: None if (data_of(j) or {}).get("useStatus") == 1 else "useStatus != 1")
    f.step("merchant/verification-records", "POST", "/api/merchant/verification-records", mer, check=ok_data)
    if cid:
        f.step("coupon/stop the published coupon (cleanup, native multipart)", "POST", "/api/coupon/stop", pub,
               form={"couponId": cid, "scope": "MERCHANT"})


flow.FLOW = ("F14", "Merchant scan-to-verify")
if __name__ == "__main__":
    main(flow)
