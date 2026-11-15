"""F14 Merchant scan-to-verify (coupon): merchant 9002 publishes coupon -> player shows coupon QR token ->
merchant verifies -> replay is idempotent -> status used -> verification-records.
The player needs a sms_coupon_history row for that coupon (no app 'receive' endpoint): E2E_COUPON_HISTORY_ID."""
import time
from harness import *


def flow(f):
    mer, p = MERCHANT, PLAYER
    now = time.time()
    fmt = lambda t: time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t))
    f.step("coupon/publish (merchant)", "POST", "/api/coupon/publish", mer,
           js={"name": "QA 验收券 %s" % RUN_ID, "description": "acceptance", "startTime": fmt(now),
               "endTime": fmt(now + 7 * 86400), "publishCount": 1, "couponType": 1}, expect_code=(200,))
    hid = os.environ.get("E2E_COUPON_HISTORY_ID")
    f.step("coupon/myrecvlist (player)", "POST", "/api/coupon/myrecvlist", p, check=ok_data)
    if not hid:
        f.block("no granted coupon for player on TEST (set E2E_COUPON_HISTORY_ID) - data seeding needed")
    t = f.step("coupon/qr-token (player)", "POST", "/api/coupon/qr-token", p, form={"couponHistoryId": hid},
               check=lambda j: need(j, "token"))
    tok = (data_of(t) or {}).get("token")
    f.step("coupon/verification (merchant)", "POST", "/api/coupon/verification", mer, form={"code": tok},
           check=lambda j: None if j.get("msg") == "核销成功" else "msg=%s" % j.get("msg"))
    f.step("coupon/verification replay", "POST", "/api/coupon/verification", mer, form={"code": tok},
           check=lambda j: None if (data_of(j) or {}).get("alreadyUsed") else "replay not flagged alreadyUsed")
    f.step("coupon/verification by another merchant refused", "POST", "/api/coupon/verification", 9005,
           form={"code": tok}, expect_code=None, check=lambda j: None if j.get("code") != 200 or (data_of(j) or {}).get("alreadyUsed") else "foreign merchant verified")
    f.step("coupon/status used", "POST", "/api/coupon/status", p, form={"couponHistoryId": hid},
           check=lambda j: None if (data_of(j) or {}).get("useStatus") == 1 else "useStatus != 1")
    f.step("merchant/verification-records", "POST", "/api/merchant/verification-records", mer, check=ok_data)


flow.FLOW = ("F14", "Merchant scan-to-verify")
if __name__ == "__main__":
    main(flow)
