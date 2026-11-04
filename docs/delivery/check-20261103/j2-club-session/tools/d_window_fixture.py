"""WORKAROUND (fixture write, throwaway DB; MySQL NOW() is UTC, the app reads Asia/Shanghai, hence +8h offsets) for finding J2-03: no app screen and no admin endpoint can set the merchant
sign-up window / recruit deadline of a club CO theme. Written here only so the rest of the journey can be walked."""
from common import *
cl.ex("UPDATE cms_topic SET merchant_sign_up_start_date=NOW() + INTERVAL 7 HOUR, merchant_sign_up_end_date=NOW() + INTERVAL 11 HOUR, "
      "recruit_deadline=NOW() + INTERVAL 11 HOUR WHERE id=990014")
print(cl.q("select merchant_status,merchant_sign_up_start_date,merchant_sign_up_end_date,recruit_deadline from cms_topic where id=990014"))
