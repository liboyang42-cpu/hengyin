"""FIXTURE (API leg): the player's own ticket QR, as 票夹 issues it (/api/verify/dyncode/issue). Prints the code."""
exec(open('/root/p2/fx_common.py').read())
openid, reg = sys.argv[1], int(sys.argv[2])
tok, me = login(openid)
if len(sys.argv) > 3:
    nodes = cl.q("SELECT id,chapter_id,checkin_code,merchant_member_id FROM cms_topic_node WHERE topic_id=990014 AND merchant_member_id=%s", (int(sys.argv[3]),))
    act = cl.q("SELECT owner_id FROM cms_registration WHERE id=%s", (reg,))[0][0]
    for _n, ch, code, m in nodes:
        print('checkin', cl.http("POST", "/api/play/checkin", token=tok, form={"activityId": act, "code": code, "topicId": 990014}).get('msg'), file=sys.stderr)
d = cl.http("POST", "/api/verify/dyncode/issue", token=tok, form={"registrationId": reg, "type": "activity"})
print(d["data"]["code"])
