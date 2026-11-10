import os, sys, json, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token
tid = int(sys.argv[1])
tok, _ = app_token("13900000004")
def j(x): return json.dumps(x, ensure_ascii=False)[:600]
print("nodes", j(http("GET", "/api/play/nodes?topicId=%d" % tid, token=tok)))
print("choices", j(http("GET", "/api/play/player-journey/choices?topicId=%d" % tid, token=tok)))
print("report", j(http("GET", "/api/play/player-journey/report?topicId=%d" % tid, token=tok)))
r = http("POST", "/api/marketing/referral/token", token=tok, json_body={"targetType": 1, "targetId": tid})
print("issue", j(r))
