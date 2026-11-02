# Club lane: organiser walkthrough (DRAFT, in progress)

Candidate `master` a1d30fb. Rig READY (branch `claude/cloud-walk-20261101-rig`). Persona: 林小舟, volunteer who runs 「沪上慢走社」, a Shanghai city-walking club.

Draft. Stages A and B are walked; C to E follow. Final sections replace this header.

## Findings so far (raw)

- A: club creation works; duplicate name caught only at step 4 of 4; 「重试」 repeats the same request; stale error survives going back and renaming.
- A: a club's own paid city-orienteering theme can never take sign-ups (sale pipeline `SALE_ORGANIZER_ONLY_FREE_EXPLORE`); organiser sees 「已确认」 and a scheduled session, member sees 「定价中 · 未开售」.
- A: paid club-led free exploration hits 「该产品暂不支持在线支付(平台正在办理支付资质,预计 2 个月内开放);如需报名请联系主办方」 until the platform switches the content slot to PLATFORM.
- A: 「开一场」 has no price and no meeting-place field; checkout shows 「集合点 · 人民广场1号口」 for a Wukang Road walk.
- A: a duplicate identical session is created without any warning.
- B: same-day session: refund deadline already passed at sign-up (`refundableNow:false`) while checkout says 「开场前24小时可退」.
- B: roster shows six identical 「城瘾用户」 rows; real names given at sign-up are not shown; CRM list masks names 「周*」.
- B: cancel works and says 「退款 2 单；人工跟进 0 单；关闭待付 0 单」; dialog buttons 「取消」 / 「确认取消」.

STATUS: PARTIAL - stages C, D, E in progress
