"""F03 Activity detail: activity/info (with tickets) -> topic/info-to-user -> payment-readiness -> play/nodes."""
from harness import *


def check_info(j):
    d = data_of(j) or {}
    if d.get("topicId") != TOPIC:
        return "topicId=%s != %s" % (d.get("topicId"), TOPIC)
    t = [x for x in d.get("omsTicketList") or [] if x.get("id") == FREE_TICKET]
    if not t:
        return "free ticket 992041 not in omsTicketList"
    if float(t[0].get("price") or 0) != 0:
        return "ticket 992041 price != 0"


def check_gate(j):
    d = data_of(j) or {}
    if d.get("gate") is not True:
        return "anonymous detail is not the club gate (gate=%s)" % d.get("gate")
    if d.get("clubId") not in (CLUB, str(CLUB)):
        return "gate clubId=%s != %s" % (d.get("clubId"), CLUB)
    if not d.get("message"):
        return "gate has no message"


def flow(f):
    m = PLAYER
    # Product decision (not changed here): a club-run activity shows anonymous visitors only the club gate.
    f.step("activity/info 992031 (anonymous) -> club gate", "POST", "/api/activity/info", None, form={"id": ACTIVITY},
           check=check_gate, owner="product")
    f.step("activity/info 992031 (signed in)", "POST", "/api/activity/info", m, form={"id": ACTIVITY}, check=check_info)
    f.step("topic/info-to-user 992001", "POST", "/api/topic/info-to-user", m, form={"id": TOPIC},
           check=lambda j: None if (data_of(j) or {}).get("lifecycle") == 3
           and str(ACTIVITY) in str((data_of(j) or {}).get("activityList")) else "lifecycle!=3 or 992031 not linked")
    f.step("registration/payment-readiness", "POST", "/api/registration/payment-readiness", m, check=ok_data)
    f.step("play/nodes preview", "GET", "/api/play/nodes", m, q={"activityId": ACTIVITY, "topicId": TOPIC},
           check=lambda j: None if (data_of(j) or {}).get("total") == 6 else "total != 6")
    f.step("activity/info unknown id", "POST", "/api/activity/info", m, form={"id": 1}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 or data_of(j) is None else "unknown activity returned data")


flow.FLOW = ("F03", "Activity detail")
if __name__ == "__main__":
    main(flow)
