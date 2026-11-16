"""F06 Check-in: player 9001 issues the entry QR -> merchant 9002 (not the owner) is refused -> activity owner 9003 scans
(scan_dynamic_code) -> rescan is idempotent; then node self check-in by 9004 as the native client sends it
(PlayExperienceService.completionRequest .scan: scope + code, plus the action-context fields routeActionId,
expectedRouteVersion, routeSessionId[, runtimeInstance, authorityEpoch]; no nodeId).
Uses the run's live QA activity and fresh registrations from fixtures.py, and node 992021's check-in code."""
from harness import *


def flow(f):
    p, owner = 9001, CLUB_LEADER
    reg, act = os.environ.get("E2E_REG_9001_FREE"), os.environ.get("E2E_LIVE_ACTIVITY")
    if not (reg and act):
        raise Blocked("no fixture registration (run fixtures.py first)")
    q = f.step("player dyncode/issue", "POST", "/api/verify/dyncode/issue", p, form={"registrationId": reg},
               check=lambda j: need(j, "code"))
    code = (data_of(q) or {}).get("code")
    f.step("merchant 9002 (not owner) scan refused", "POST", "/api/registration/scan_dynamic_code", MERCHANT,
           form={"code": code}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 and j.get("msg") else "not refused")  # still code 500, see REPORT B9
    f.step("owner 9003 scan_dynamic_code", "POST", "/api/registration/scan_dynamic_code", owner,
           form={"code": code}, check=lambda j: None if "核销成功" in (j.get("msg") or "") else "msg=%s" % j.get("msg"))
    f.step("rescan reports already verified", "POST", "/api/registration/scan_dynamic_code", owner,
           form={"code": code}, expect_code=None,
           check=lambda j: None if "已核销" in (j.get("msg") or "") or (data_of(j) or {}).get("alreadyVerified") else "rescan not idempotent")
    # Self check-in (A1 fixed in the native client): bind to the server action context first.
    s = 9004
    scope = {"activityId": act, "topicId": TOPIC}
    ctx = f.step("self check-in: play/action-context node 992021", "GET", "/api/play/action-context", s,
                 q=dict(scope, nodeId=NODES[0]), check=lambda j: need(j, "routeSessionId", "expectedRouteVersion"))
    d = data_of(ctx) or {}
    # Node 992021 carries an advanced qa mechanic: it is played first (as the native client does through
    # /api/play/advanced/*), otherwise the door check-in answers "请先完成高级玩法".
    advanced_qa(f, s, scope, NODES[0], "1921", "self check-in")
    ctx = f.step("self check-in: play/action-context again", "GET", "/api/play/action-context", s,
                 q=dict(scope, nodeId=NODES[0]), check=lambda j: need(j, "routeSessionId", "expectedRouteVersion"))
    d = data_of(ctx) or {}
    form = dict(scope, code=os.environ.get("E2E_NODE_CHECKIN_CODE", "QA992021"), routeActionId="qa-%s-ck" % RUN_ID,
                expectedRouteVersion=d.get("expectedRouteVersion"), routeSessionId=d.get("routeSessionId"))
    if d.get("runtimeInstance") is not None and d.get("authorityEpoch") is not None:
        form.update(runtimeInstance=d["runtimeInstance"], authorityEpoch=d["authorityEpoch"])
    # Native PlayExperienceService.completionRequest(.scan) removes nodeId ("source checkin resolves node from code"),
    # but a context-bound command must name its node (PlayBasicIntentApplication.basicIntentScope) -> "参数有误".
    native = dict(form, routeActionId="qa-%s-ckn" % RUN_ID)
    f.step("self check-in as the native client sends it (no nodeId) -> app defect A5", "POST", "/api/play/checkin", s,
           form=native, owner="app")
    full = dict(form, nodeId=NODES[0])
    f.step("self check-in play/checkin (backend contract: action context + nodeId)", "POST", "/api/play/checkin", s, form=full,
           check=ok_data)
    f.step("self check-in replay (same routeActionId) is idempotent", "POST", "/api/play/checkin", s, form=full, check=ok_data)


flow.FLOW = ("F06", "Check-in")
if __name__ == "__main__":
    main(flow)
