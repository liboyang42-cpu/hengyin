"""F06 Check-in: player issues QR -> activity owner 9003 scans (scan_dynamic_code) -> rescan is idempotent;
negative: merchant 9002 (not the owner) is refused; player node self check-in with the app's field set.
NOTE: consumes 9009's registration (E2E_REG_9009). Reruns need a fresh registration (see REPORT seeding)."""
from harness import *


def flow(f):
    p, reg = PLAYER2, int(os.environ.get("E2E_REG_9009", "74"))
    q = f.step("player dyncode/issue", "POST", "/api/verify/dyncode/issue", p, form={"registrationId": reg},
               check=lambda j: need(j, "code"))
    code = (data_of(q) or {}).get("code")
    f.step("merchant 9002 (not owner) scan refused", "POST", "/api/registration/scan_dynamic_code", MERCHANT,
           form={"code": code}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 else "non-owner merchant could verify")
    f.step("owner 9003 scan_dynamic_code", "POST", "/api/registration/scan_dynamic_code", CLUB_LEADER,
           form={"code": code}, check=lambda j: None if "核销成功" in (j.get("msg") or "") else "msg=%s" % j.get("msg"))
    f.step("rescan reports already verified", "POST", "/api/registration/scan_dynamic_code", CLUB_LEADER,
           form={"code": code}, expect_code=None,
           check=lambda j: None if "已核销" in (j.get("msg") or "") or (data_of(j) or {}).get("alreadyVerified") else "rescan not idempotent")
    f.step("player self check-in as the app sends it (play_api.dart:330)", "POST", "/api/play/checkin", p,
           form={"activityId": ACTIVITY, "code": os.environ.get("E2E_NODE_CHECKIN_CODE", "QA0000"),
                 "routeActionId": "qa-%s-ck" % RUN_ID, "expectedRouteVersion": 1}, owner="app")


flow.FLOW = ("F06", "Check-in")
if __name__ == "__main__":
    main(flow)
