import sys; sys.path.insert(0, '/root/sc')
from common import *
tok, _ = login(sys.argv[1])
print(J(cl.http("POST", "/api/team/quit", token=tok, json_body={"teamId": int(sys.argv[2])})))
