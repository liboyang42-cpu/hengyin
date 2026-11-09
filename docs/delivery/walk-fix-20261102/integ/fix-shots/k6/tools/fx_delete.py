"""FIXTURE (API leg): merchant 9002 takes activity 15 off sale and deletes it through the real endpoints the 我的项目 long-press menu calls."""
exec(open('/root/k6tools/fx_common.py').read())
tok, me = login('e2e-openid-9002')
print('down', json.dumps(cl.http("POST", "/api/activity/update_publish_status", token=tok, form={"id": 15, "scope": "MERCHANT"}), ensure_ascii=False)[:200])
print('delete', json.dumps(cl.http("POST", "/api/activity/delete", token=tok, form={"id": 15, "scope": "MERCHANT"}), ensure_ascii=False)[:200])
print(cl.q("SELECT id,status,del_flag FROM cms_activity WHERE id=15"))
