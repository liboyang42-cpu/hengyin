"""Rig-only (qx-s1): SELECT-only read-back of help identities on the integrated staging (self-play + activity first runs)."""
import os, sys, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import readback
readback("topic_route_session", "SELECT id,member_id,topic_id,activity_id,play_instance_id,JSON_UNQUOTE(JSON_EXTRACT(config_snapshot_json,'$.helpRunInstance')) FROM topic_route_session WHERE member_id=9004 ORDER BY id", ())
readback("play_help_run_quota", "SELECT * FROM play_help_run_quota ORDER BY 1", ())
readback("player_node_progress instance", "SELECT topic_id,node_id,play_instance_id FROM player_node_progress WHERE member_id=9004 ORDER BY topic_id,node_id", ())
