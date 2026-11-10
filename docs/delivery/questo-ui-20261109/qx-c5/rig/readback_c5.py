"""Rig-only read-backs for qx-c5 (activity-session first run). SQL here is SELECT only, except the step marked RIG-DB-ONLY."""
import json, os, sys, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token, q, ex
TID, ACT = int(sys.argv[1]), int(sys.argv[2])
def show(k, v): print("%-34s %s" % (k, v))
rows = q("SELECT id,member_id,activity_id,play_instance_id,status,JSON_UNQUOTE(JSON_EXTRACT(config_snapshot_json,'$.helpRunInstance')) "
         "FROM topic_route_session WHERE topic_id=%s ORDER BY id", (TID,))
show("[库回读] topic_route_session", rows)
run = [r for r in rows if r[2] == ACT][0]; run_id, identity = run[0], run[5]
tok, _ = app_token("13900000004")
show("re-entry GET /api/play/nodes", http("GET", "/api/play/nodes?topicId=%s&activityId=%s" % (TID, ACT), token=tok).get("code"))
rows2 = q("SELECT id,play_instance_id,JSON_UNQUOTE(JSON_EXTRACT(config_snapshot_json,'$.helpRunInstance')) FROM topic_route_session "
          "WHERE topic_id=%s AND activity_id=%s AND member_id=9004", (TID, ACT))
show("[库回读] after re-entry", rows2)
show("re-entry same identity, one row", len(rows2) == 1 and rows2[0][2] == identity)
show("[库回读] play_help_run_quota", q("SELECT owner_member_id,topic_id,activity_id,play_instance_id,quota_total,quota_used "
                                    "FROM play_help_run_quota WHERE topic_id=%s", (TID,)))
show("[库回读] play_attempt_log playInstance", q("SELECT member_id,activity_id,attempt_type,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.playInstance')) "
                                    "FROM play_attempt_log WHERE topic_id=%s ORDER BY id", (TID,)))
show("[库回读] player_node_progress", q("SELECT member_id,activity_id,node_id,play_instance_id,status FROM player_node_progress "
                                    "WHERE topic_id=%s ORDER BY id", (TID,)))
other, _ = app_token("13900000005")
show("other member GET help", json.dumps(http("GET", "/api/play/help?runId=%s&nodeId=74" % run_id, token=other), ensure_ascii=False)[:200])
show("other member POST use", json.dumps(http("POST", "/api/play/help/use", token=other, json_body={
    "runId": run_id, "nodeId": 74, "level": 3, "action": "HELP", "requestId": "rig-c5-other"}), ensure_ascii=False)[:200])
show("receipt (owner)", json.dumps(http("GET", "/api/play/help/receipt?runId=%s&nodeId=74" % run_id, token=tok), ensure_ascii=False)[:400])
if "--legacy" in sys.argv:
    # RIG-DB-ONLY: simulate an old in-flight activity run created before this change (no frozen identity).
    ex("UPDATE topic_route_session SET config_snapshot_json=JSON_REMOVE(config_snapshot_json,'$.helpRunInstance') WHERE id=%s", (run_id,))
    show("RIG-DB-ONLY json_remove on run", run_id)
    show("legacy activity run GET help", json.dumps(http("GET", "/api/play/help?runId=%s&nodeId=74" % run_id, token=tok), ensure_ascii=False)[:200])
