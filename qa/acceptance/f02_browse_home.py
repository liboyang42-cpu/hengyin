"""F02 Browse home: the calls feed_sections_controller.dart / feed_page.dart make on launch."""
from harness import *


def rows(j):
    d = data_of(j)
    return d.get("rows") if isinstance(d, dict) else j.get("rows") if isinstance(j, dict) else None


def upcoming_pages(f, m, size=6, limit=20):
    """Page through activity/list is_my=2 until 992031 shows up or the list ends (the app pages the same list)."""
    seen = 0
    for page in range(1, limit + 1):
        j = f.step("activity/list upcoming page %d" % page, "POST", "/api/activity/list", m,
                   form={"is_my": 2, "pageNum": page, "pageSize": size}, check=lambda j: None if rows(j) is not None else "no rows")
        r = rows(j) or []
        seen += len(r)
        if any(str((x or {}).get("id")) == str(ACTIVITY) for x in r):
            return page
        total = (data_of(j) or {}).get("total") if isinstance(data_of(j), dict) else (j or {}).get("total")
        if not r or (isinstance(total, int) and seen >= total):
            return None
    return None


def flow(f):
    m = PLAYER2
    f.step("common/banner", "POST", "/api/common/banner", m, form={"showType": 1, "linkType": 0},
           check=lambda j: None if isinstance(data_of(j), list) else "data not a list")
    f.step("topic/list main feed", "POST", "/api/topic/list", m, form={"is_my": 0, "pageNum": 1, "pageSize": 10},
           check=lambda j: None if rows(j) is not None else "no rows")
    f.step("topic/list recommended", "POST", "/api/topic/list", m,
           form={"is_my": 0, "is_recommend": 1, "pageNum": 1, "pageSize": 10},
           check=lambda j: None if rows(j) is not None else "no rows")
    # Product rule (ApiActivityController + PublicActivityScope.PUBLIC_SHELF): public lists never show community
    # (club-run) sessions. 992031 is run by club 7001, so it must be absent from every page; members find it
    # through registration/my-joined and the club page instead. Listed in REPORT next to the F03 club gate.
    found = upcoming_pages(f, m)
    f.steps.append(dict(label="activity/list upcoming (all pages): club-run 992031 stays off the public shelf",
                        method="POST", path="/api/activity/list", result="FAIL" if found else "PASS",
                        note=("club-run 992031 listed on page %s" % found) if found else "checked every page", owner="product" if found else None))
    f.step("activity/list nearby", "POST", "/api/activity/list", m,
           form={"is_my": 0, "sort_type": 2, "pageNum": 1, "pageSize": 6},
           check=lambda j: None if rows(j) is not None else "no rows")
    f.step("activity/list stream", "POST", "/api/activity/list", m, form={"is_my": 0, "pageNum": 1, "pageSize": 10},
           check=lambda j: None if rows(j) is not None else "no rows")
    f.step("play/run-session/list", "GET", "/api/play/run-session/list", m)
    f.step("registration/my-joined holds 992031", "POST", "/api/registration/my-joined", m,
           check=lambda j: None if str(ACTIVITY) in str(data_of(j)) else "992031 not in my-joined")
    f.step("anonymous browse: activity/list", "POST", "/api/activity/list", None,
           form={"is_my": 0, "pageNum": 1, "pageSize": 10})


flow.FLOW = ("F02", "Browse home")
if __name__ == "__main__":
    main(flow)
