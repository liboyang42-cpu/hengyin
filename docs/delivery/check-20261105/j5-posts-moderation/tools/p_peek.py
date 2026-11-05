import sys, json
from common import *
t = tok(sys.argv[1]); r = http("POST", sys.argv[2], token=t, json_body=json.loads(sys.argv[3]) if len(sys.argv) > 3 else {})
s = json.dumps(r, ensure_ascii=False); print(s[:int(sys.argv[4]) if len(sys.argv) > 4 else 1500])
