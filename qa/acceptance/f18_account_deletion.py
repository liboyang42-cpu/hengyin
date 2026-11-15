"""F18 Account deletion: fresh TEST phone member -> status -> precheck -> consent -> sms -> apply -> result ->
re-login -> cancel -> status NORMAL. ALWAYS cancels in the same run (7-day cooling-off, then irreversible).
Uses fixture phone 13900000001 (player 9001; override with E2E_DEREG_PHONE, fixture range 13900000001-09 only).
SMS sends are rate-limited per phone (60 s) and per IP window, so the flow waits before each new code. Negative: 9003 (club) is BLOCKED."""
from harness import *


def flow(f):
    ph = os.environ.get("E2E_DEREG_PHONE", "13900000001")
    if not re.match(r"^1390000000[1-9]$", ph):
        raise Blocked("E2E_DEREG_PHONE must be a fixture phone 13900000001-09")
    f.step("status NORMAL", "GET", "/api/user/deregister/status", ph,
           check=lambda j: None if (data_of(j) or {}).get("status") == "NORMAL" else "status=%s" % (data_of(j) or {}).get("status"))
    f.step("precheck ELIGIBLE", "POST", "/api/user/deregister/precheck", ph,
           check=lambda j: None if (data_of(j) or {}).get("status") == "ELIGIBLE" else "blockers=%s" % (data_of(j) or {}).get("blockers"))
    f.step("precheck club leader BLOCKED", "POST", "/api/user/deregister/precheck", CLUB_LEADER,
           check=lambda j: None if (data_of(j) or {}).get("status") == "BLOCKED" else "9003 not blocked")
    f.step("consent account_cancel", "POST", "/api/compliance/consents", ph,
           js={"docType": "account_cancellation_notice", "scene": "account_cancel", "eventType": "AGREE", "requestId": rid("d")})
    import harness
    time.sleep(int(os.environ.get("E2E_SMS_COOLDOWN", "65")))  # login just used this phone's SMS slot
    f.step("sms/send for apply", "POST", "/api/sms/send", form={"phone": ph}, expect_code=(200, 500))
    code = harness._sms_code(ph)
    if not code:
        f.block("no SMS code source for apply")
    a = f.step("deregister/apply", "POST", "/api/user/deregister/apply", ph, form={"smscode": code, "requestId": rid("ap")},
               check=lambda j: None if (data_of(j) or {}).get("status") == "PENDING" else "status=%s" % (data_of(j) or {}).get("status"))
    rt = (data_of(a) or {}).get("resultToken")
    old = harness.token(ph)
    forget(ph)
    time.sleep(int(os.environ.get("E2E_SMS_COOLDOWN", "65")))  # re-login needs a fresh SMS slot
    harness._TOKENS["revoked"] = old
    harness.PHONE["revoked"] = ph
    f.step("old token revoked after apply", "GET", "/api/user/deregister/status", "revoked", expect_code=(401,), expect_http=None)
    f.step("deregister/result PENDING", "GET", "/api/user/deregister/result", ph, q={"resultToken": rt},
           check=lambda j: None if (data_of(j) or {}).get("status") == "PENDING" else "not PENDING")
    f.step("deregister/cancel", "POST", "/api/user/deregister/cancel", ph,
           check=lambda j: None if (data_of(j) or {}).get("status") == "CANCELLED" else "not CANCELLED")
    f.step("status back to NORMAL", "GET", "/api/user/deregister/status", ph,
           check=lambda j: None if (data_of(j) or {}).get("status") == "NORMAL" else "not NORMAL")


flow.FLOW = ("F18", "Account deletion")
if __name__ == "__main__":
    main(flow)
