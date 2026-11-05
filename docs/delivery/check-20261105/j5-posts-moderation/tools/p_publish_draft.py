"""Publish an existing theme draft through /api/topic/publish-draft as its author 9003. Usage: p_publish_draft.py <topicId> [busy]"""
import sys, json
from common import *
p3 = tok("e2e-openid-9003")
if "busy" in sys.argv[2:]: wx_mode("busy")
r = http("POST", "/api/topic/publish-draft?id=%s" % sys.argv[1], token=p3, json_body={}); wx_mode("pass")
print("publish-draft", json.dumps(r, ensure_ascii=False)[:400])
print(q("SELECT id,name,status,user_status,check_status,is_draft FROM cms_topic WHERE id=%s", (int(sys.argv[1]),)))
