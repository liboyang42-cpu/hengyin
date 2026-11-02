import sys; sys.path.insert(0, '/root/sc')
from common import *
tok, _ = login('walk-club-lead')
print(J(cl.http("POST", "/api/club/event-notification/status", token=tok, json_body={"campaignId": int(sys.argv[1])})))
