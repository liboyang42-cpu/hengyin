import sys, time, random; sys.path.insert(0, '/root/sc')
from common import *
p, act, node, ans, lat, lng = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6]
tok, _ = login(p)
ctx = cl.http("GET", "/api/play/action-context?nodeId=%s&activityId=%s" % (node, act), token=tok).get('data') or {}
rid = "route-walk-%d" % random.randint(1, 10**9)
r = cl.http("POST", "/api/play/answer", token=tok, form={"activityId": act, "nodeId": node, "answer": ans, "longitude": lng, "latitude": lat, "accuracy": 8,
     "routeActionId": rid, "expectedRouteVersion": ctx.get('expectedRouteVersion', 0), "routeSessionId": ctx.get('routeSessionId', 0)})
print(p, node, ans, J(r)[:300])
