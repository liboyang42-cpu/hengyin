"""qx-u2 rig-only fixture: turn on the existing team-mode column of the rig theme so the
P4 choices endpoint returns two real modes. Rig DB only; no product code."""
import os, sys, json, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token, ex, q
tid = int(sys.argv[1])
ex("UPDATE cms_topic SET team_mode=1, team_max_members=4 WHERE id=%s", (tid,))
print("readback", q("SELECT id, team_mode, team_max_members FROM cms_topic WHERE id=%s", (tid,)))
tok, _ = app_token("13900000009")
print("choices(9009)", json.dumps(http("GET", "/api/play/player-journey/choices?topicId=%d" % tid, token=tok), ensure_ascii=False)[:900])
