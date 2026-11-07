# k4 / fe: club-owned 自由探索 sale all the way: walk log (2026-11-05)

Rig: candidate `0aafbdf6` (walk-rig/candidate), backend 127.0.0.1:8099, rig web 8097, DB cy_e2e_51 (3316). Shots: this folder,
`fe-NN-*.jpg` (780x1688 JPEG, all < 200 KB). Scripts: `/tmp/claude-0/-home-user-hengyin/29eb85d1-8404-5db4-8c29-72e4a4fe2172/scratchpad/k4fe/`.
Fictional data only. Another lane (`k4cs-*`, club 990004 静安夜行社) used the same rig and DB at the same time. I used my own
identities (`k4fe-lead`, `k4fe-p1`, `k4fe-p2`) and the seeded merchants 9002/9005, and touched only my own rows. The settlement job
(145) and the recruit-deadline job (149) are global, so they also ran over the other lane's rows. Neither job picked up any of
their rows (read back: no new rows for them).

Clock note (HARNESS): the rig MySQL `NOW()` is UTC, while the app and stored times are Shanghai wall time (+8 h). SQL-side windows such as
`end_date <= NOW() - 14 DAY` therefore run 8 h late in the rig. `registration_entitlement.redeemed_at` is written in UTC
(06:23:31) while `redemption.create_time` is written in +8 (14:23:32) for the same event.

## Ids

| What | Id |
|---|---|
| Owner (主理人) `k4fe-lead` | member 990009 |
| Club 外滩慢逛社 | 990005 |
| Theme 外滩 · 两店慢逛 (product_type 2) | cms_topic 990016; chapters 44 (咖啡), 45 (书店) |
| Merchants | 9002 外滩样板咖啡 (node 74, offer 1, perk 到店赠美式一杯); 9005 福州路样板书店 (node 75, offer 2, perk 到店赠精选书签一枚) |
| Theme ticket / session ticket | oms_ticket 76 「门票」 owner_type 1 → 78 「门票」 owner_type 2, source 76 |
| Session (场次) | cms_activity 10 |
| Players | `k4fe-p1` 990010 陈小游 (reg 77), `k4fe-p2` 990011 林小行 (reg 78) |
| Audit tasks | 6 TOPIC_PROJECT_REVIEW, 7 TOPIC, 9 TOPIC_SALE_REVIEW |

## Steps

| # | Step | Result | Shots | Evidence |
|---|---|---|---|---|
| 1 | Player `k4fe-lead` becomes 主理人 (4 steps), creates club 外滩慢逛社 with a cover (real upload) | PASS | fe-01, fe-02, fe-03 | `SELECT c.id,c.name,c.member_id FROM club c WHERE name='外滩慢逛社'` → 990005, 990009 |
| 2 | 管理 › 发布主题 › 「自由探索 · 全点开放」 opens the editor | PASS (opens) / HARNESS | fe-04, fe-05, fe-06 | The editor's continuous location picker keeps re-adding the rig's default `chooseLocation` result (44→53 「地点素材」). The editor was not driven on screen |
| 3 | Create the theme: `/api/topic/v2/create` with the editor body (2 PERK recruiting chapters, recruitDeadline today, no price), publisher 实名 via `/api/publisher/identity` | HARNESS (API leg, reason in row 2) | — | `{"topicId":990016,"auditTaskId":7,"reviewState":"PENDING"}`; lifecycle 7; chapters 44/45 recruit_enabled=1 |
| 4 | Platform approves TOPIC_PROJECT_REVIEW and TOPIC (`POST /business/audit/review`, admin token) | PASS | — | tasks 6, 7 status 1; `status,user_status,lifecycle` = 1,1,1 |
| 5 | Merchant 9002 applies: 合作中心 › theme › 申请承接 › 第1章 咖啡 › template 到店打卡·拍一张你的咖啡 › name, 地图选点 (HARNESS loc), 提交申请与节点 | PASS | fe-07-* | `merchant_chapter_application` 1 (ch 44, 9002, status 0); node 74 template 6001 |
| 6 | Merchant 9005 applies for 第2章 书店 | PASS | fe-08-* | application 2 (ch 45, 9005); node 75 template 6002 |
| 7 | Owner approves both applications: theme console › 商家 › 确认 › 确认通过 (twice) | PASS | fe-09, fe-10, fe-11, fe-12 | applications 1, 2 → 已通过; nodes 74, 75 approved. The list refreshes after each approval (N-07 fix holds). The header still says 0/2 已招站点 and 2 还没人接 until supply is filled (see notes) |
| 8 | Both merchants: 承接进度 › 填写实际供给 › 常备权益 › 核销额度 100 › 确认实际供给 | PASS | fe-13-* | `/api/coop/offer/enroll` 「已入驻」; `merchant_chapter_offer` 1 (ch 44, 9002, perk 2, quota 100), 2 (ch 45, 9005, perk 3, quota 100) |
| 9 | Recruit deadline (TIME SHIFT: recruit_deadline → NOW()-1 day, then the real job 149 `topicRecruitDeadlineTask` via `/monitor/job/run`) | PASS | — | lifecycle 1→2, fee_platform_rate frozen 0.0300; job log 70505 status 0 |
| 10 | Owner: 管理 › 现在要做 「确认终价」 › pricing page; cost 60 / 4 people › 重新计算地板 (¥16.31) › price 29 (HARNESS: `<slider>` not drawn; the page's own `onSliderChange({value:29})`) › 确认终价并提交开售审核 | PASS | fe-14..fe-20 | `/api/topic/pricing/confirm` 「终价已确认，请等待开售审核」; oms_ticket 76 name **门票**, price 29.00, inventory 100, start/end/signup_deadline **NULL** |
| 11 | **N-05 (a)** 管理 「现在要做」 after 确认终价 | **PASS**: reads 「开售审核中」 | fe-21 | — |
| 12 | **N-05 (b)** new ticket name for 自由探索 | **PASS**: 「门票」 in DB and on screen (theme 场次 card, ticket sheet, checkout, order) | fe-23, fe-25c, fe-27c | `SELECT name FROM oms_ticket WHERE id IN (76,78)` → 门票, 门票 |
| 13 | Platform: canonical15 draft `PUT /business/audit/topic/990016/sale-canonical-draft` (adapted `canonical_draft.py`, storeCount 2, evidenceRule ALL_REQUIRED_REDEEM) | **FAIL → FIX** | — | 500 `SALE_SNAPSHOT_MISSING_SESSION_WINDOWS`, nothing saved (bug B1). Admin `PUT /business/ticket` to add times → 409 「主题已进入 SELLING，售卖目录…已冻结」 while lifecycle=2 (bug B5). **FIX**: `UPDATE oms_ticket SET start_time='2026-11-05 10:00:00', end_time='2026-11-12 23:59:59', signup_deadline='2026-11-12 20:00:00', meeting_point='九江路100号门口', gather_lng/lat, refund_rule WHERE id=76`. Retry → 200, draft v1, TOPIC_SALE_REVIEW task 9 created automatically |
| 14 | Platform approves TOPIC_SALE_REVIEW | **FAIL → FIX** | — | 500 「主题没有售票截止时间」 (bug B2). Admin `PUT /business/topic {saleEndAt}` → 「非官方主题仅允许调整漫游导流开关…」. **FIX**: `UPDATE cms_topic SET sale_end_at='2026-11-12 20:00:00' WHERE id=990016`. Approve → 「开售审核通过后商业参数已变化，请重新提审」; `POST /business/audit/topic/990016/sale-submit` → 「已提交开售审核」 (task 9 v1) |
| 15 | Approve again | **FAIL → FIX** | — | 500 `SALE_TICKET_PROJECTION_MISSING` (no session yet). Owner `POST /api/club/open-session` → 「主题还没开售，开售后才能开一场」 (deadlock, bug B3). **FIX**: `UPDATE cms_topic SET lifecycle=0` only around one real `/api/club/open-session` call by the owner (session 2026-11-05 14:28), then back to `lifecycle=2`. Result: 「开团成功」 activity 10 (14:28–23:59:59), ticket 78 copied from 76 |
| 16 | Approve TOPIC_SALE_REVIEW | PASS | — | 「审核处理成功」; lifecycle 3, selling_started_at 2026-11-05 14:14:38; topic_sale_snapshot CANONICAL_15_V1 present |
| 17 | **Item 3**: player opens the theme page and taps 立即报名 with a plain `tap` (no handler call) | **PASS**: it fires and opens 「选择票种」 | fe-22, fe-23, fe-24 | `heroCta {"kind":"signup","text":"立即报名"}`; sheet: 「这一场由「外滩慢逛社」执行，加入后查看 · 去加入俱乐部」 |
| 18 | Player 1 joins the club from the sheet (去加入俱乐部 › 加入), back, 立即报名 › 门票 › 下一步 · 去结算 | PASS | fe-25a..d | `/api/club/join` 加入成功. 「下一步 · 去结算」 stays grey and inert until the only ticket row is tapped (note N3) |
| 19 | Checkout: 参与人信息 (add 陈小游 / 13700001111), tick consent, 付款 › 「取消只退 50%」 › 继续下单 › rig pay seam `success` | PASS | fe-26a, fe-27a, fe-27b, fe-27c | `/api/registration/create` reg 77, payable 29.00; `/__rig/pay` callback 204, read-back `payment_status 2, RIG-TXN-77`. Order page: ¥29.00, 门票 × 1, 已核销 0/2 家 |
| 20 | Player 2 (`k4fe-p2`, 林小行) joins and buys the same way | PASS | fe-30*, fe-31* | reg 78, 29.00, RIG-TXN-78 |
| 21 | Money after both payments (rule: money moves only at settlement) | PASS | — | `ums_member_balance_detail` 0 rows for event 10; balances 9001/9002/9005/990009 all 0.00; `settlement_detail` 0 rows for regs 77/78 (EXPLORE_PASS is not accrued at payment, `SettlementEntryServiceImpl.recordOnPayment` ~line 77) |
| 22 | Before session start (14:23, session starts 14:28): player 2 票夹 QR → bookstore 9005 扫码 (HARNESS: camera → scan seam with the player's code) | **Accepted** (bug B4) | fe-32a/b, fe-33a/b | `scan_dynamic_code` 「核销成功」 redemption 1 (reg 78, ch 45) at 14:23:32. The play page for the same session says 「活动未开始」 (fe-34b) |
| 23 | After 14:28: player 1 票夹 › flip card › QR; coffee 9002 扫码 | PASS | fe-35a/b, fe-36a/b | redemption 2 (reg 77, ch 44, 9002) 14:28:48; result sheet 「核销成功 · 第1章 咖啡 · 请提供：到店赠美式一杯」 |
| 24 | Ticket stays in 票夹 after the first stamp | **PASS** | fe-37a, fe-37b | 「已核销 1/2 家」, a new QR is issued |
| 25 | Bookstore 9005 scans player 1 (second shop) | PASS | fe-38a, fe-38b | redemption 3 (reg 77, ch 45) 14:29:31; 「第2章 书店 · 玩家通关 +1200 探索值已到账」; entitlements 77/44 and 77/45 status 1; offer quota_used 1 / 2 |
| 26 | Scan never moves money | **PASS** | fe-36b, fe-38b (累计收入 ¥0.00) | after 3 scans: balance_detail 0 rows, all balances 0.00 |
| 27 | Ending | PASS | fe-39a (票夹 「还没有票」 after 2/2), fe-40a, fe-40c, fe-41a, fe-41b | play page 「本轮探索已完成 · 看看这一天的收获」 › 探店完成, 已通关, 2/2 家, 2 枚邮票, 1 枚勋章; order 「已完成 · 本次订单已核销」 |
| 28 | Money screens before settlement | **FAIL** (bug B6) | fe-42a..d | club 结算: sessions [] / 可提现 ¥0.00; wallet `/api/wallet/stages` pendingSettlement 0, complaintPeriod [], withdrawable 0; merchants 账户收益 ¥0.00. ¥58.00 has been taken and none of it shows anywhere for the owner |
| 29 | Settlement: TIME SHIFT activity 10 −15 days (end 2026-10-21 23:59:59), topic −22 days, refund_deadline −15 days; real jobs 145 `settlementTask.run()` and 146 `coopSettlementPayoutTask.run()` via `/monitor/job/run` | **FAIL** (bug B0, blocker) | — | both jobs status 0 (SUCCESS), yet activity 10 is_settlement 0, topic is_settlement 0, 0 balance rows, 0 coop_settlement, 0 settlement_detail. The same eligibility SQL without the `product_type = 2` exclusion returns activity 10 (1 row), with it 0 rows. `POST /business/edition-final/990016` → `EDITION_TERMS_MISSING` |
| 30 | **Counterfactual only** (FIX, labelled): `product_type` 2→1 on 990016 around the real job 145 runs, to see what the session path would pay | FIX (not a product pass) | fe-43a/b, fe-44a/b | see the money table below. Restored to 2 afterwards. Side effect: the job also ran topic settlement while product_type was 1 and claimed `cms_topic.is_settlement=1` (zero topic-level cash) |
| 31 | 14-day rule (counterfactual path): TIME SHIFT end = NOW()−14 d + 1 h → job 145 does not settle; end = NOW()−14 d − 1 min → settles | PASS (counterfactual) | fe-43a/b | job logs 70571 (no settle), 70572 (settled) |

## Money (to the fen)

Real product path (product_type 2), after the time shift and both jobs: **nothing moved.**

| Row | Amount |
|---|---|
| Paid: reg 77 `wechat_payment_amount` | 29.00 |
| Paid: reg 78 | 29.00 |
| `SELECT SUM(wechat_payment_amount) FROM cms_registration WHERE owner_type=2 AND owner_id=10 AND payment_status=2` | **58.00** |
| settlement_detail (regs 77, 78) | 0 rows |
| ums_member_balance_detail (event 10) | 0 rows |
| coop_settlement (topic 990016) | 0 rows |
| club_payable / topic_edition_funds / club_edition_compensation_terms (topic 990016) | 0 / 0 / 0 rows |
| Merchants 9002, 9005 (PERK offers, no per-head fee) | 0.00 / 0.00 |
| Platform 9001 | 0.00 |
| Owner 990009 | 0.00 |

Counterfactual (FIX product_type=1 for the job run only), `SELECT id,member_id,change_balance,after_balance,event_type,event_id,change_reason FROM ums_member_balance_detail WHERE event_id=10`:

| Row | Amount | Check |
|---|---|---|
| 40 · 9001 platform · event_type 7 「活动平台分润 - 外滩 · 两店慢逛」 | 1.74 | 58.00 × 0.0300 = 1.74 ✓ (3 %) |
| 39 · 990009 owner · event_type 3 「活动分润 - 外滩 · 两店慢逛」 | 56.26 | 58.00 − 1.74 = 56.26 ✓ |
| Merchants 9002 / 9005 | 0.00 / 0.00 | PERK terms; no per-head fee (k5 merchant-fee lines not applicable) |
| Sum | 58.00 | = cash collected ✓ |
| Club 结算 screen | 「销售额 ¥58.00 · 平台服务费 3% ¥1.74 · ¥56.26 · 已入你的账户」 | matches (fe-44a) |
| Withdrawable date | wallet before settlement: 「客诉期中 · 11月5日可提现 ¥56.26」 with activity end 2026-10-22 06:33 → **end + 14 days = 11-05** ✓ (fe-43a/b); after the job: 可提现 ¥56.26 (fe-44b) | |

## Bugs (product)

- **B0 (P0, blocker): a club-owned 自由探索 never settles.** Both settlement queries exclude product_type 2:
  `CmsActivityMapper.xml:503-510` (`selectActivitiesForSettlement` … `NOT EXISTS … topic.product_type = 2`) and
  `CmsTopicMapper.xml:1295` (`AND (product_type IS NULL OR product_type <> 2)`). `CmsTopicServiceImpl.settleTopic` throws
  `FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN`. The only FREE_EXPLORE money path is the platform 探店日 FINAL
  (`/business/edition-final`), which needs `club_edition_compensation_terms`. Those terms are frozen only for
  `FOUR_STORE_REDEEM` (`TopicSaleFinalizationServiceImpl.java:177-201`), so this theme gets `EDITION_TERMS_MISSING`.
  EXPLORE_PASS is also not accrued at payment (`SettlementEntryServiceImpl.java:~77`). Repro: rows 1–29. Result: ¥58.00 is
  collected and no row ever pays the club, the platform or the merchants.
- **B1 (P1): 确认终价 creates the 自由探索 「门票」 with no start/end/signup times** (`PricingServiceImpl.java:498-518`). The
  free-exploration canonical then fails `SALE_SNAPSHOT_MISSING_SESSION_WINDOWS` (`TopicSaleCanonicalV1.java:329-331`, via
  `TopicSaleCanonicalBuilder.rebuild` → `submitSaleReviewIfReady`), and the platform cannot even save the draft. Repro: row 10 then 13.
- **B2 (P1): a club 自由探索 has no `sale_end_at`.** The editor never sends saleEndAt. `CmsTopicServiceImpl.defaultClubSaleEnd`
  (`:2535-2545`) fills it only for ORIENTEERING, so `TopicSaleTimeValidator.java:38-40` rejects with 「主题没有售票截止时间」. The admin topic PUT refuses to set it.
- **B3 (P1): deadlock between opening a session and going on sale for a club-owned 自由探索.** `finalizeForSale` requires an
  activity ticket projection (`SALE_TICKET_PROJECTION_MISSING`, `TopicSaleFinalizationServiceImpl.assertActivityTicketProjection`).
  `ClubSessionServiceImpl.requireTopicOpenForSessions` (`:124-132`, call `:165-168`) allows sessions in PRICING only when the
  club holds an effective coop order, and the owning club has none (it cannot invite itself). Repro: row 15.
- **B4 (P2): a merchant can redeem a ticket before the session starts.** `scan_dynamic_code` accepted reg 78 at 14:23:32 for a
  session that starts at 14:28. At the same moment the player's play page says 「活动未开始」. Repro: row 22.
- **B5 (P3): wrong refusal text.** Admin `PUT /business/ticket` on a PRICING (lifecycle 2) theme says 「主题已进入 SELLING…已冻结」 (OmsTicketServiceImpl freeze message).
- **B6 (P2, follows B0): the owner sees no pending money.** `ActivitySettlementHoldMapper.xml:93` also excludes product_type 2, so the
  wallet and club 结算 show ¥0 待结算 for sold 自由探索 sessions (fe-42a/b).

## Notes / UX (lower)

- N1: the 自由探索 pricing page still shows tabs 「有人带 / 无人带」 and 「带队成本 · 本团带队总成本 · 成团人数」, but a 自由探索 theme has no leader (fe-15). The ticket itself is now 门票.
- N2: after 确认终价, the owner lands on the theme page (`pages/topic/index`) instead of back on 管理 (fe-20).
- N3: in the ticket sheet with a single ticket, 「下一步 · 去结算」 is grey and does nothing until the ticket row is tapped (fe-25c).
- N4: after both stamps the ticket leaves 票夹 (「还没有票」, fe-39a). Expected, but the ending is reached only through the play page.
- N5: the play page logs `GET /api/game/session/view … 500 本场游戏尚未准备` on every open (自由探索 has no game session). This is console noise.
- N6: owner recruit layer: after both approvals the header still reads 「0/2 已招站点 · 2 还没人接」 until the merchants fill supply (fe-12). Chapter tier text is clipped 「≥ ¥30 权...」.
- N7: `cms_registration.verification_status` becomes 1 after the first of two shops (reg 78 after 1/2).
- N8: the session ticket 78 copies signup_deadline 2026-11-12 20:00 from the theme ticket, but the session ends the same day at 23:59.
- N9: the quote returns `refundDeadline 2026-11-04T14:28` (start − 24 h) while `cms_registration.refund_deadline` stores 2026-11-05 14:28 (start).
- HARNESS: theme hero cover blank, with faint 「把你带的人，变成你的…」 / 「成为主理人」 and a second capsule bleeding through (fe-20, fe-22, fe-07-apply-submitted). That text lives only in `pages/club/apply/index.wxml`, so this looks like a rig layer artefact; NOT VERIFIED on a phone. Cover and activity images are 404 from the OSS stand-in.

## Not verified

- On-screen theme editor (HARNESS loop, row 2). Camera scan (scan seam). Native slider (handler). Real WeChat pay and withdrawal.
- A real 自由探索 settlement: it cannot happen in the product (B0). The 3 % split and the end+14-day date were shown only on the counterfactual session path.
- Merchant payouts: PERK terms carry no cash, so there are no merchant lines. REVSHARE / per-head terms were not walked.
