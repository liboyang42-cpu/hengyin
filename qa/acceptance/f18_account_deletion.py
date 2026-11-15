"""F18 Account deletion: fresh TEST phone member -> status -> precheck -> consent -> sms -> apply -> result ->
re-login -> cancel -> status NORMAL. ALWAYS cancels in the same run (7-day cooling-off, then irreversible).
Uses a throwaway TEST phone 139000009NN (E2E_DEREG_PHONE), never a shared fixture. Negative: 9003 (club) is BLOCKED."""
from harness import *


def flow(f):
    ph = os.environ.get("E2E_DEREG_PHONE", "139000009%02d" % (sum(map(ord, RUN_ID)) % 100))
    f.step("status NORMAL", "GET", "/api/user/deregister/status", ph,
           check=lambda j: None if (data_of(j) or {}).get("status") == "NORMAL" else "status=%s" % (data_of(j) or {}).get("status"))
    f.step("precheck ELIGIBLE", "POST", "/api/user/deregister/precheck", ph,
           check=lambda j: None if (data_of(j) or {}).get("status") == "ELIGIBLE" else "blockers=%s" % (data_of(j) or {}).get("blockers"))
    f.step("precheck club leader BLOCKED", "POST", "/api/user/deregister/precheck", CLUB_LEADER,
           check=lambda j: None if (data_of(j) or {}).get("status") == "BLOCKED" else "9003 not blocked")
    f.step("consent account_cancel", "POST", "/api/compliance/consents", ph,
           js={"docType": "account_cancellation_notice", "scene": "account_cancel", "eventType": "AGREE", "requestId": rid("d")})
    import harness
    f.step("sms/send for apply", "POST", "/api/sms/send", form={"phone": ph}, expect_code=(200, 500))
    code = harness._sms_code(ph)
    if not code:
        f.block("no SMS code source for apply")
    a = f.step("deregister/apply", "POST", "/api/user/deregister/apply", ph, form={"smscode": code, "requestId": rid("ap")},
               check=lambda j: None if (data_of(j) or {}).get("status") == "PENDING" else "status=%s" % (data_of(j) or {}).get("status"))
    rt = (data_of(a) or {}).get("resultToken")
    old = harness.token(ph)
    forget(ph)
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
