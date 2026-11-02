import sys; sys.path.insert(0, '/root/sc')
from common import *
tok, _ = login(sys.argv[1]); act = sys.argv[2]; persp = sys.argv[3] if len(sys.argv) > 3 else 'CLUB'
r = cl.http("GET", "/api/game/session/view?activityId=%s&perspective=%s" % (act, persp), token=tok)
print(json.dumps(r, ensure_ascii=False))
