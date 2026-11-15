"""F07 Play a game: nodes -> route-state -> action-context -> help -> help/use -> answer -> receipt -> ending.
Player 9009 is signed up for activity 992031 (seed). Node 992021 is qa TYPE + 2-level hint ladder (1 free use)."""
import uuid
from harness import *

TOPIC_Q = {"activityId": ACTIVITY, "topicId": TOPIC}


def flow(f):
    m = int(os.environ.get("E2E_PLAY_MEMBER", PLAYER2))  # a player with a live (not finished) run, e.g. 9001
    f.step("play/nodes (registered player)", "GET", "/api/play/nodes", m, q=TOPIC_Q,
           check=lambda j: None if (data_of(j) or {}).get("registered") and (data_of(j) or {}).get("playable")
           and (data_of(j) or {}).get("total") == 6 else "expected registered/playable/total=6")
    f.step("play/nodes (unregistered 9004 is not playable)", "GET", "/api/play/nodes", PLAYER, q=TOPIC_Q,
           check=lambda j: None if (data_of(j) or {}).get("registered") is False else "9004 should be registered=False")
    f.step("play/route-state", "GET", "/api/play/route-state", m, q=TOPIC_Q, check=ok_data)
    ctx = f.step("play/action-context node 992021", "GET", "/api/play/action-context", m,
                 q=dict(TOPIC_Q, nodeId=NODES[0]), check=lambda j: need(j, "routeSessionId", "expectedRouteVersion"))
    d = data_of(ctx) or {}
    run_id = d.get("routeSessionId")
    f.step("play/help ladder", "GET", "/api/play/help", m, q={"runId": run_id, "nodeId": NODES[0]},
           check=lambda j: None if (data_of(j) or {}).get("enabled") else "help not enabled for seeded ladder node")
    rid = "qa-%s-%s" % (RUN_ID, uuid.uuid4().hex[:8])
    use = {"runId": run_id, "nodeId": NODES[0], "level": 1, "action": "HELP", "requestId": rid}
    f.step("play/help/use level 1", "POST", "/api/play/help/use", m, js=use,
           check=lambda j: need(j, "content"))
    f.step("play/help/use replay is idempotent (no charge)", "POST", "/api/play/help/use", m, js=use,
           check=lambda j: None if not (data_of(j) or {}).get("chargedPoints") else "replay charged points")
    ans = dict(TOPIC_Q, nodeId=NODES[0], answer="1921", routeActionId="qa-%s-a1" % RUN_ID,
               expectedRouteVersion=d.get("expectedRouteVersion"), routeSessionId=run_id,
               longitude="113.264385", latitude="23.129112", accuracy="10")
    for k in ("runtimeInstance", "authorityEpoch"):
        if d.get(k) is not None:
            ans[k] = d[k]
    f.step("play/answer node 992021 (with action context)", "POST", "/api/play/answer", m, form=ans)
    # The app (play_api.dart:738) omits routeSessionId: assert the backend rejects that, flagging an app gap.
    bad = {k: v for k, v in ans.items() if k not in ("routeSessionId",)}
    bad["routeActionId"] = "qa-%s-a2" % RUN_ID
    f.step("play/answer as the app sends it (no routeSessionId) -> app gap", "POST", "/api/play/answer", m, form=bad,
           expect_code=200, owner="app")
    f.step("play/action-receipt", "GET", "/api/play/action-receipt", m,
           q=dict(TOPIC_Q, nodeId=NODES[0], routeActionId=ans["routeActionId"]))
    f.step("play/help/receipt", "GET", "/api/play/help/receipt", m, q={"runId": run_id, "nodeId": NODES[0]})
    f.step("play/ending GET", "GET", "/api/play/ending", m, q=TOPIC_Q, check=ok_data)
    f.step("play/ending POST as the app calls it -> app/backend method mismatch", "POST", "/api/play/ending", m,
           q=TOPIC_Q, owner="app")


flow.FLOW = ("F07", "Play game: answer, hint, finish")
if __name__ == "__main__":
    main(flow)
