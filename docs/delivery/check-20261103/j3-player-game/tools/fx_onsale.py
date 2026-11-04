"""FIXTURE (same as j2 e_sale_fixture.py / integ selfplay_quote.py): sale review refused with
「effectiveSaleEndAt 必须早于或等于 ticket.startTime」 (j2 J2-02), so the theme is put on sale directly. Throwaway DB only."""
from common import *
tid=int(sys.argv[1])
cl.ex("UPDATE cms_topic SET lifecycle=3, selling_started_at=DATE_ADD(NOW(), INTERVAL 7 HOUR), status=1, user_status=1, check_status=1 WHERE id=%s", (tid,))
print(cl.q("SELECT id,lifecycle,selling_started_at,status,user_status,fee_platform_rate FROM cms_topic WHERE id=%s", (tid,)))
