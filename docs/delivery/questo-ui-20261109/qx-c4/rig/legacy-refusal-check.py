import sys,os,json
sys.path.insert(0,os.environ['CAND']+'/scripts/e2e')
from chain_lib import http, app_token, q, ex
tid=q("select topic_id from topic_route_session where id=1")[0][0]
node=q("select id from cms_topic_node where topic_id=%s order by id limit 1",(tid,))[0][0]
tok,_=app_token("13900000004")
print("before", json.dumps(http("GET","/api/play/help?runId=1&nodeId=%d"%node,token=tok),ensure_ascii=False)[:200])
ex("update topic_route_session set config_snapshot_json=json_remove(config_snapshot_json,'$.helpRunInstance') where id=1")
print("legacy", json.dumps(http("GET","/api/play/help?runId=1&nodeId=%d"%node,token=tok),ensure_ascii=False)[:200])
