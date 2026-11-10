import sys,os,json
sys.path.insert(0,os.environ['CAND']+'/scripts/e2e')
from chain_lib import http, app_token
tok,_=app_token("13900000004")
for node in (73,74):
    r=http("GET","/api/play/help?runId=2&nodeId=%d"%node,token=tok)
    print(node, json.dumps(r,ensure_ascii=False)[:600])
other,_=app_token("13900000005")
print("other", json.dumps(http("GET","/api/play/help?runId=2&nodeId=74",token=other),ensure_ascii=False)[:200])
