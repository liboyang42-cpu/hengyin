"""F02 Browse home: the calls feed_sections_controller.dart / feed_page.dart make on launch."""
from harness import *


def rows(j):
    d = data_of(j)
    return d.get("rows") if isinstance(d, dict) else j.get("rows") if isinstance(j, dict) else None


def flow(f):
    m = PLAYER2
    f.step("common/banner", "POST", "/api/common/banner", m, form={"showType": 1, "linkType": 0},
           check=lambda j: None if isinstance(data_of(j), list) else "data not a list")
    f.step("topic/list main feed", "POST", "/api/topic/list", m, form={"is_my": 0, "pageNum": 1, "pageSize": 10},
           check=lambda j: None if rows(j) is not None else "no rows")
    f.step("topic/list recommended", "POST", "/api/topic/list", m,
           form={"is_my": 0, "is_recommend": 1, "pageNum": 1, "pageSize": 10},
           check=lambda j: None if rows(j) is not None else "no rows")
    f.step("activity/list upcoming lists 992031", "POST", "/api/activity/list", m,
           form={"is_my": 2, "pageNum": 1, "pageSize": 6},
           check=lambda j: None if str(ACTIVITY) in str(rows(j)) else "992031 not in upcoming")
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
