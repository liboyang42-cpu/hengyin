"""F12 Publish a topic as club leader 9003: identity status -> topic/create draft -> publish-draft -> play/nodes.
Needs an APPROVED app_media_asset cover on TEST (scripts/e2e/chain_platform.py seed_approved_cover): E2E_COVER_ASSET_ID."""
import uuid
from harness import *


def flow(f):
    m = CLUB_LEADER
    f.step("publisher/identity/status", "POST", "/api/publisher/identity/status", m, js={},
           check=lambda j: None if (data_of(j) or {}).get("registered") else "9003 publisher identity not registered")
    cover = os.environ.get("E2E_COVER_ASSET_ID")
    if not cover:
        f.block("no APPROVED cover media asset on TEST (set E2E_COVER_ASSET_ID) - data seeding needed")
    body = {"name": "QA 验收主题 %s" % RUN_ID, "description": "acceptance r3", "publishMode": "simple", "saveDraft": 1,
            "requestId": ("qa%s%s" % (RUN_ID, uuid.uuid4().hex))[:40], "clubId": CLUB, "productType": 1,
            "imgUrl": "media-asset:%s" % cover,
            "chapters": [{"name": "第一章", "description": "QA",
                          "nodes": [{"name": "QA 点位", "address": "广州老城", "longitude": "113.264385",
                                     "latitude": "23.129112", "nodeTime": 15}]}]}
    r = f.step("topic/create (save draft)", "POST", "/api/topic/create", m, js=body,
               check=lambda j: None if isinstance(data_of(j), int) else "data is not a topic id")
    tid = data_of(r)
    pr = "qa-%s-pub" % RUN_ID
    f.step("topic/publish-draft", "POST", "/api/topic/publish-draft", m, form={"id": tid, "publishRequestId": pr},
           check=lambda j: None if j.get("msg") in ("已发布", "已提交审核") else "msg=%s" % j.get("msg"))
    f.step("topic/publish-draft replay is idempotent", "POST", "/api/topic/publish-draft", m,
           form={"id": tid, "publishRequestId": pr})
    f.step("play/nodes on new topic (no '路线尚未发布')", "GET", "/api/play/nodes", m, q={"topicId": tid},
           check=lambda j: None if "尚未发布" not in (j.get("msg") or "") else "route not published")


flow.FLOW = ("F12", "Publish a topic")
if __name__ == "__main__":
    main(flow)
