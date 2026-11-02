import sys, subprocess; sys.path.insert(0, '/root/sc')
from common import *
act = int(sys.argv[1]); people = sys.argv[2:]
NAMES = {"walk-player-1": ("周一鸣", "13700000001"), "walk-player-2": ("吴佳怡", "13700000002"), "walk-player-3": ("郑浩然", "13700000003"),
         "walk-player-4": ("王小雨", "13700000004"), "walk-player-5": ("冯思远", "13700000005"), "walk-player-6": ("陈晓婷", "13700000006"),
         "walk-player-7": ("褚子涵", "13700000007"), "walk-player-8": ("卫一凡", "13700000008"), "walk-player-9": ("蒋雨桐", "13700000009"), "walk-player-10": ("沈嘉木", "13700000010"), "walk-player-11": ("韩若溪", "13700000011"), "walk-player-12": ("杨知远", "13700000012")}
for p in people:
    tok, me = login(p)
    cl.http("POST", "/api/club/join", token=tok, json_body={"id": 990004, "joinMessage": ""})
    cl.http("POST", "/api/compliance/consents", token=tok, json_body={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "walk-consent-%s-%d" % (p, act)})
    t = cl.q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id", (act,))[0][0]
    nm, ph = NAMES[p]
    body = {"ownerType": 2, "ownerId": act, "ticketId": t, "tickets": [{"ticketId": t, "count": 1}], "realName": nm, "phone": ph}
    qq = cl.http("POST", "/api/registration/quote", token=tok, json_body=body)
    if not qq.get('data'): print(p, 'QUOTE', J(qq)); continue
    body["quoteSign"] = qq["data"]["quoteSign"]; body["requestId"] = "walk-reg-%s-%d" % (p, act)
    c = cl.http("POST", "/api/registration/create", token=tok, json_body=body)
    if not c.get('data'): print(p, 'CREATE', J(c)); continue
    if '--nopay' in sys.argv[0:1] or p in ('walk-player-8',):
        print(p, 'created unpaid', c['data']); continue
    out = subprocess.run([RIG + '/venv/bin/python', '/home/user/hengyin/docs/delivery/cloud-walk-20261101/rig/pay.py', '--member', str(me['id'])], capture_output=True, text=True)
    print(p, me['id'], nm, 'reg', c['data'].get('registrationId'), 'refundableNow', qq['data'].get('refundableNow'), qq['data'].get('refundDeadline'), 'pay:', out.stdout.strip()[:200])
