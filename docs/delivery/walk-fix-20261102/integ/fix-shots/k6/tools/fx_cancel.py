"""FIXTURE (API leg, labelled): the buyer cancels through the real /api/registration/cancel-refund."""
exec(open('/root/k6tools/fx_common.py').read())
openid, reg = sys.argv[1], int(sys.argv[2])
tok, me = login(openid)
r = cl.http("POST", "/api/registration/cancel-refund", token=tok, json_body={"registrationId": reg, "reason": "临时有事"}); print('cancel', json.dumps(r, ensure_ascii=False)[:400])
if r.get('code') != 200:
    r = cl.http("POST", "/api/registration/cancel-refund", token=tok, form={"id": reg}); print('cancel(form)', json.dumps(r, ensure_ascii=False)[:400])
print(cl.q("SELECT id,payment_status,verification_status FROM cms_registration WHERE id=%s", (reg,)))
print(cl.q("SELECT id,source_type,source_id,refund_amount,status,merchant_id FROM refund_application WHERE source_id=%s", (reg,)))
