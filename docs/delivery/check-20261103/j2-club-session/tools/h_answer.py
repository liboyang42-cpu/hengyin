"""API leg (labelled): POST /api/play/answer exactly as the play page sends it, for stations the UI walk did not cover."""
from common import *
import urllib.request, urllib.parse
def ans(openid, node, answer, lat, lng):
    tok, me = login(openid)
    data = {"activityId": 9, "nodeId": node, "answer": answer, "longitude": lng, "latitude": lat, "accuracy": 8,
            "routeActionId": "route-j2-%s-%d-%d" % (openid, node, int(datetime.datetime.now().timestamp()*1000)), "expectedRouteVersion": 0, "routeSessionId": 0}
    req = urllib.request.Request("http://127.0.0.1:8099/api/play/answer", data=urllib.parse.urlencode(data).encode(), headers={"Authorization": "Bearer " + tok, "content-type": "application/x-www-form-urlencoded"})
    r = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req).read())
    print(openid, node, answer, r.get('code'), r.get('msg'))
for a in sys.argv[1:]:
    o, n, v, la, ln = a.split(',')
    ans(o, int(n), v, float(la), float(ln))
