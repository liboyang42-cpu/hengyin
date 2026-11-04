"""Platform side (me as the admin): set the merchant sign-up window for theme 990014 through the admin topic edit
(PUT /business/topic, what chengyinhub-ui topic edit sends). No app screen sets these fields."""
from common import *
atok = cl.admin_token()
now = sh_now().replace(second=0, microsecond=0)
fmt = lambda d: d.strftime('%Y-%m-%d %H:%M:%S')
body = {"id": 990014, "merchantSignUpStartDate": fmt(now - datetime.timedelta(hours=1)), "merchantSignUpEndDate": fmt(now + datetime.timedelta(hours=2))}
r = cl.http("PUT", "/business/topic", token=atok, json_body=body)
print(J(r))
print(cl.q("select merchant_status,merchant_sign_up_start_date,merchant_sign_up_end_date,recruit_deadline,lifecycle,status from cms_topic where id=990014"))
