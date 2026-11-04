"""WORKAROUND for finding J2-04 (merchant station application always 500): replay the exact body the app sent
(merchant-create-body.json, captured from the rig network log) with only startDate/endDate rewritten to ISO-8601.
Everything else (owner checks, station binding) runs in the real endpoint."""
from common import *
tok, me = login('e2e-openid-9002')
b = json.load(open('merchant-create-body.json'))
b['startDate'] = b['startDate'].replace(' ', 'T') + '.000+08:00'
b['endDate'] = b['endDate'].replace(' ', 'T') + '.000+08:00'
r = cl.http("POST", "/api/registration/merchant/create", token=tok, json_body=b)
print(J(r))
