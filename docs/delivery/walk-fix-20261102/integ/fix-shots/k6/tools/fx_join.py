"""FIXTURE: player joins club via the real /api/club/join (then approve if it needs approval)."""
import sys; sys.argv_backup = list(sys.argv)
exec(open('/root/k6tools/fx_common.py').read())
openid, club = sys.argv[1], int(sys.argv[2])
tok, me = login(openid)
r = cl.http("POST", "/api/club/join", token=tok, json_body={"id": club, "joinMessage": "想参加自由探索"}); print('join', json.dumps(r, ensure_ascii=False)[:300])
print(cl.q("SELECT id,member_id,club_id,role FROM club_member WHERE member_id=%s", (me['id'],)))
