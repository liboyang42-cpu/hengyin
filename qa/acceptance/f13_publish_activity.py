"""F13 Publish an activity as club leader 9003: publish_precheck -> publish -> info -> delete (cleanup, frees quota of 3)."""
import time
from harness import *


def flow(f):
    m = CLUB_LEADER
    day = time.strftime("%Y-%m-%d", time.localtime(time.time() + 5 * 86400))
    f.step("activity/publish_precheck", "POST", "/api/activity/publish_precheck", m, form={"scope": ""})
    body = {"name": "QA 验收活动 %s" % RUN_ID, "description": "acceptance r3", "addressName": "钟楼广场",
            "startDate": day + " 10:00:00", "endDate": day + " 18:00:00", "categoryIds": "1", "collaborators": [],
            "requestId": rid("act"),
            "tickets": [{"name": "免费票", "price": 0, "startTime": day + " 10:00:00", "endTime": day + " 18:00:00",
                         "totalStock": 10}]}
    r = f.step("activity/publish", "POST", "/api/activity/publish", m, js=body,
               check=lambda j: None if isinstance(data_of(j), int) else "data is not an activity id")
    aid = data_of(r)
    f.step("activity/publish replay (same requestId) is idempotent", "POST", "/api/activity/publish", m, js=body,
           check=lambda j: None if data_of(j) == aid else "replay created another activity")
    f.step("activity/info new activity", "POST", "/api/activity/info", m, form={"id": aid},
           check=lambda j: None if (data_of(j) or {}).get("name") == body["name"] else "name mismatch")
    f.step("player cannot publish activity", "POST", "/api/activity/publish", PLAYER, js=dict(body, requestId=rid("p")),
           expect_code=None, check=lambda j: None if j.get("code") != 200 else "player was allowed to publish")
    f.step("activity/delete cleanup", "POST", "/api/activity/delete", m, form={"id": aid, "scope": ""})


flow.FLOW = ("F13", "Publish an activity")
if __name__ == "__main__":
    main(flow)
