"""F07 Play a game: nodes -> route-state -> action-context -> help -> help/use -> answer -> receipt -> ending.
Player 9001 on the run's live QA activity (fixtures.py), so the run and its frozen answer config are fresh every run.
Node 992021 is a qa TYPE node with a 2-level hint ladder (1 free use) and answer 1921 (seed qa.answerText).
Shapes follow the native client: GET reads with query scope; answer as a form with the action-context fields
(PlayRouteAdvance.fields); help/use as JSON; ending by GET (PlayExperienceService)."""
import uuid
from harness import *


def flow(f):
    m = int(os.environ.get("E2E_PLAY_MEMBER", "9001"))
    act = os.environ.get("E2E_LIVE_ACTIVITY")
    if not act:
        raise Blocked("no live QA activity (run fixtures.py first)")
    scope = {"activityId": act, "topicId": TOPIC}
    f.step("play/nodes (registered player)", "GET", "/api/play/nodes", m, q=scope,
           check=lambda j: None if (data_of(j) or {}).get("registered") and (data_of(j) or {}).get("playable")
           and (data_of(j) or {}).get("total") == 6 else "expected registered/playable/total=6")
    f.step("play/nodes (unregistered 9009 is not playable)", "GET", "/api/play/nodes", PLAYER2, q=scope,
           check=lambda j: None if (data_of(j) or {}).get("registered") is False else "9009 should be registered=False")
    f.step("play/route-state ACTIVE", "GET", "/api/play/route-state", m, q=scope,
           check=lambda j: None if (data_of(j) or {}).get("status") == "ACTIVE" else "status=%s" % (data_of(j) or {}).get("status"))
    ctx = f.step("play/action-context node 992021", "GET", "/api/play/action-context", m,
                 q=dict(scope, nodeId=NODES[0]), check=lambda j: need(j, "routeSessionId", "expectedRouteVersion"))
    d = data_of(ctx) or {}
    run_id = d.get("routeSessionId")
    f.step("play/help ladder", "GET", "/api/play/help", m, q={"runId": run_id, "nodeId": NODES[0]},
           check=lambda j: None if (data_of(j) or {}).get("enabled") else "help not enabled for seeded ladder node")
    use = {"runId": run_id, "nodeId": NODES[0], "level": 1, "action": "HELP", "requestId": "qa-%s-%s" % (RUN_ID, uuid.uuid4().hex[:8])}
    f.step("play/help/use level 1", "POST", "/api/play/help/use", m, js=use, check=lambda j: need(j, "content"))
    f.step("play/help/use replay is idempotent (no charge)", "POST", "/api/play/help/use", m, js=use,
           check=lambda j: None if not (data_of(j) or {}).get("chargedPoints") else "replay charged points")
    advanced_qa(f, m, scope, NODES[0], "1921", "advanced qa")
    ctx = f.step("play/action-context after the advanced qa", "GET", "/api/play/action-context", m,
                 q=dict(scope, nodeId=NODES[0]), check=lambda j: need(j, "routeSessionId", "expectedRouteVersion"))
    d = data_of(ctx) or d
    ans = dict(scope, nodeId=NODES[0], answer="1921", routeActionId="qa-%s-a1" % RUN_ID,
               expectedRouteVersion=d.get("expectedRouteVersion"), routeSessionId=run_id,
               longitude="113.264385", latitude="23.129112", accuracy="10")
    if d.get("runtimeInstance") is not None and d.get("authorityEpoch") is not None:
        ans.update(runtimeInstance=d["runtimeInstance"], authorityEpoch=d["authorityEpoch"])
    f.step("play/answer node 992021 (native shape, with action context)", "POST", "/api/play/answer", m, form=ans,
           check=lambda j: None if (data_of(j) or {}).get("answerWrong") is not True else "answer judged wrong")
    f.step("play/answer replay (same routeActionId) returns the original outcome", "POST", "/api/play/answer", m, form=ans,
           check=lambda j: None if (data_of(j) or {}).get("answerWrong") is not True else "replay judged wrong")
    f.step("play/action-receipt", "GET", "/api/play/action-receipt", m,
           q=dict(scope, nodeId=NODES[0], routeActionId=ans["routeActionId"]), check=ok_data)
    f.step("play/help/receipt", "GET", "/api/play/help/receipt", m, q={"runId": run_id, "nodeId": NODES[0]})
    f.step("play/route-state after the answer", "GET", "/api/play/route-state", m, q=scope,
           check=lambda j: None if str(NODES[0]) in str((data_of(j) or {}).get("completedNodeIds") or (data_of(j) or {}).get("nodeStates") or data_of(j))
           else "node 992021 not reported")
    f.step("play/ending GET (as the native client reads it)", "GET", "/api/play/ending", m, q=scope, check=ok_data)


flow.FLOW = ("F07", "Play game: answer, hint, finish")
if __name__ == "__main__":
    main(flow)
