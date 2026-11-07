"""API leg (labelled): advanced/start + one action. adv.py <openid> <topic> <nodeId> <ACTION> '<payload json>'"""
from common import *
import time
openid, topic, node, action, payload = sys.argv[1:6]
tok, me = login(openid)
s = cl.http("POST", "/api/play/advanced/start", token=tok, json_body={"activityId": "0", "topicId": topic, "nodeId": node})
d = s.get('data') or {}
print('start', d.get('sessionId'), d.get('status'), d.get('version'))
r = cl.http("POST", "/api/play/advanced/action", token=tok, json_body={"sessionId": d.get('sessionId'), "version": d.get('version'), "idempotencyKey": "k8-%d" % time.time_ns(), "action": action, "payload": json.loads(payload)})
print('action', J(r)[:400])
