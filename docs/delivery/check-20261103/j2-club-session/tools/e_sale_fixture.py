"""FIXTURE (same as integ/proof/selfplay_quote.py): the platform's sale review refused twice through the real admin
endpoint (J2-02: 「开放承接时 recruitDeadline 必填」, then 「effectiveSaleEndAt 必须早于或等于 ticket.startTime」),
so the theme is put on sale here the way that proof did. Throwaway DB only."""
from common import *
cl.ex("UPDATE cms_topic SET lifecycle=3, selling_started_at=DATE_ADD(NOW(), INTERVAL 7 HOUR), status=1, user_status=1, check_status=1 WHERE id=990014")
print(cl.q("SELECT id,lifecycle,selling_started_at,status,user_status,fee_platform_rate FROM cms_topic WHERE id=990014"))
