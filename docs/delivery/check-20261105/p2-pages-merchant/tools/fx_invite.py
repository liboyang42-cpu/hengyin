"""FIXTURE (API leg, labelled): club lead 9003 sends a coop invite to merchant 9002 through the real /api/coop/invite."""
exec(open('/root/p2/fx_common.py').read())
tok, me = login('e2e-openid-9003')
body = {"inviteType": 0, "toType": "merchant", "toId": 9002, "topicId": int(sys.argv[1]) if len(sys.argv) > 1 else 990013,
        "message": "想请你们做第 1 站的补给点", "requestId": sys.argv[2] if len(sys.argv) > 2 else "p2-coop-invite-1", "shareMode": int(sys.argv[3]) if len(sys.argv) > 3 else None}
r = cl.http("POST", "/api/coop/invite", token=tok, json_body=body); print(json.dumps(r, ensure_ascii=False)[:500])
print(cl.q("SELECT id,from_type,from_id,to_type,to_id,topic_id,status FROM coop_invite ORDER BY id DESC LIMIT 3"))
