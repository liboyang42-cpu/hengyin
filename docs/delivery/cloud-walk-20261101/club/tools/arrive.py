import sys; sys.path.insert(0, '/root/sc')
from common import *
act = int(sys.argv[1])
for p in sys.argv[2:]:
    tok, me = login(p)
    print(p, J(cl.http("POST", "/api/club/lead/arrive", token=tok, form={"activityId": act, "latitude": 31.24272, "longitude": 121.48839}))[:120])
