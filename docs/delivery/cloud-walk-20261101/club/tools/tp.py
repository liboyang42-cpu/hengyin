import sys; sys.path.insert(0, '/root/sc')
from common import *
tok, _ = login(sys.argv[1])
print(json.dumps(cl.http("GET", "/api/club/lead/team-progress?activityId=%s" % sys.argv[2], token=tok), ensure_ascii=False)[:2500])
