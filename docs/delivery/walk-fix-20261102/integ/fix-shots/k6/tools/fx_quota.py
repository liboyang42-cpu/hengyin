"""READ-BACK: publish_precheck as club lead 9008 (5 club sessions of 990014) and merchant 9002 (own activity 14)."""
exec(open('/root/k6tools/fx_common.py').read())
for oid, scope in (('e2e-openid-9008', ''), ('e2e-openid-9002', 'MERCHANT')):
    tok, me = login(oid)
    print(oid, json.dumps(cl.http("POST", "/api/activity/publish_precheck", token=tok, form={"scope": scope}), ensure_ascii=False)[:200])
print(cl.q("SELECT id,member_id,topic_id,club_id,status,del_flag FROM cms_activity WHERE member_id IN (9008,9002) ORDER BY id"))
