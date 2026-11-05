# Round 12, lane k5-merchant-pay: result

Branch `integ/launch-test-20261103`, private local branch, `pull --rebase` before every push, no force-push.
Code commits: `943b7f67` (fee at session settlement, ledger, theme path, screens, tests, gate registration) and
`58641850` (merchant 财务 lists the refund debit; found in the rig walk).

**Not a phone.** Proof is unit tests, real MySQL 8.0.45 ITs, and a rig walk: the real jar at `58641850`, MySQL 8.0.45 and
Redis 7.4 in the cloud rig, driven over real HTTP with real tokens, plus the real mini-program in the rig's headless
browser at 390×844 for the screens. Payment uses the rig pay seam: a verified payment fact plus the real callback.
`payment_status` is never written directly.

## The rule as built

The owner's rule (2026-11-05): a merchant on 按核销付费 terms on a club session gets the agreed fee for each player who
took part, at settlement. The fee comes out of the 主理人's share. The platform keeps 3% of retained sales. A scan moves
no money. Money becomes withdrawable 14 days after the session ends (`FundsHoldPolicy`).

| Question | Answer in code |
|---|---|
| Which sessions | Club sessions (`cms_activity.club_id > 0`). Each effective merchant co-op order on the session's theme that has a `coop_group_verify` row for this session. One row per merchant per session (unique key). |
| Fee | The order's frozen `fixed_fee`. It must equal `terms_snapshot.fixedFee`, and `shareMode` must agree with `compensationMode`. Otherwise the session is refused. |
| Who counts | Registrations of the session that are paid (`payment_status=2, registration_status=2`), with no approved refund of any amount, and that took part. **Took part = the roster's own 到场 fact (C-10).** The latest attendance correction wins. Without one, any of these counts: lead check-in, ticket verified, or node progress. This is the same predicate as 名册 and the recap's 到场. |
| Not counted | Anyone who got any refund. This includes the 50% refund within 24 h: that player paid ¥39 and got ¥19.50 back, so they don't count even if they checked in. Also not counted: anyone not at the session, and anyone whose arrival was corrected to "not arrived". |
| Heads per registration | Verified seats when the registration has seat facts. Otherwise `order_num`, which is 1 for every app order today. |
| Held for a complaint | Not counted at settlement. When the complaint closes, the fee for that one registration is paid at release (`releaseHeldRegistration`). |
| 引流 (`shareMode 0`) | Pays nothing. |
| Merchant is the 主理人 | Nothing extra is paid, because it would be the same person paying themselves. |

**Money.** The platform's 3% is unchanged. The fee total is deducted from the 主理人's share before payment. The 主理人
receives `share − fees` as event 3. Each merchant receives its fees as **event 95** (合作商家核销费, `event_id` = session).
Existing debt is offset first for both payees.

**Ledger, for clawback.** For each counted registration, the 主理人's payment-time `settlement_detail` line
(`receiver_type=merchant`) is reduced by the fee. A new line is added with `receiver_type=coop_merchant`, the merchant as
receiver, and its own `settlement_claim` (route `COOP_ORDER`, source = the order). The total stays the same. Both lines
are marked settled with the rest of the session.

A refund after settlement uses the existing clawback (`allocateClawback`). It takes back the merchant's share in the
same proportion, to the fen. `debitReceiverBalance` now also debits `coop_merchant`. Balances that are negative or too
small follow the existing rule: debit what is there and record the rest as debt.

**Refusals.** Each refusal raises an alarm, rolls back the session's claim, and refuses again on every run. Codes start
`CLUB_SESSION_MERCHANT_FEE_REFUSED:`.
- `CLUB_SESSION_PERCENT_TERMS_UNSUPPORTED`: a merchant on percentage terms scanned the session.
- `MERCHANT_FEE_EXCEEDS_PRINCIPAL_SHARE`: total fees are more than the 主理人's share.
- `MERCHANT_FEE_EXCEEDS_PRINCIPAL_LINE`: the fee for one registration is more than that registration's 主理人 line.
- `PRINCIPAL_LEDGER_LINE_UNPROVEN`: the 主理人's line is missing or ambiguous.
- `FROZEN_TERMS_UNPROVEN` / `FROZEN_FIXED_FEE_UNPROVEN`: the frozen terms don't match the order.
- `MERCHANT_IS_LEDGER_PAYEE`: the merchant is the payment-time line receiver.

**Percentage terms: can production reach them?** Yes, through the API. The club invite page doesn't offer percentage
terms. But `POST /api/coop/invite` accepts `shareMode 1` (0–90%) for any theme, including a club theme, and does not
check whether its sales are session tickets. Such an order would now refuse each session the merchant scans, with the
alarm above. No money moves until someone rules on it. I didn't add an invite-side block; that is your call.

**Theme path.**
- A theme's cash basis now leaves out registrations of **live club sessions** (`del_flag=0` and `club_id>0`), because
  those are settled per session.
- The approved-refund fence before closing originals also skips refunds on live club sessions.
- Deleted shells and non-club activities still enter the basis, so their paid cash is still refused
  (`ACTIVITY_CASH_BASIS_UNSUPPORTED`), as before.
- Candidate selection for theme-level co-op plans drops live club session registrations.
- A theme with live club sessions closes in either of two cases: it has no theme-level cash, or an order has no
  theme-level fulfilment (`SETTLED_PER_SESSION`). It does not write a claim, a zero terminal state or a `coop_settlement`
  row, and it does not change the order's status. So a session settled later is still paid, exactly once, in either
  order.

**Screens.**
- 俱乐部结算: each session row has a 商家核销费 line (`merchantFeeText`; hidden when 0). Sales = 到账 + 平台服务费 +
  商家核销费. The `coop_merchant` refund clawback is subtracted.
- Merchant 核销与退款: one row per session, showing the fee net of any refund, 已入个人账户, `unitFee` and `headCount`. The
  row opens 核销详情 (`session_fee`), which shows 计提规则 「¥8.00/人 × 3」 and 退款 when there was one.
- Merchant 财务 收入明细: event 95 shows as 合作分润. A refund debit (event 6, 支出) shows as 调整, so 已入账 equals the balance.
- Balance page / 待结算 estimate (`MemberFundsStageService`): the 主理人's estimate is reduced by the current fee estimate,
  and the merchant's is increased. It is available on end + 14 days.

## Proof

### Rig walk, real HTTP and MySQL, jar `58641850`

Read-backs: `fix-shots/k5-walk-readbacks.txt` (sessions settled before the theme) and
`fix-shots/k5-walk-readbacks-theme-first.txt` (the theme settled first, while the sessions were still inside the 14 days).
Scripts: `proof/k5-walk/`. Fixtures and time shifts are labelled `NOTE:` in the log.

**Setup, all through real entries.**
- A fresh lead 990005 becomes 主理人 and creates club 990004.
- The lead authors theme 990014 with 3 approved templates and a ¥39 领队场. The platform approves it.
- Merchants apply for stations: 9002 on 外滩钟楼, 9005 on 十六铺码头.
- The lead picks each candidate and invites them on their own stations: 9002 at **¥8/人**, 9005 as **引流**. Both accept,
  which freezes coop orders 1 and 2.
- The real recruit job runs, then 确认终价 ¥39, then sale review.
- The lead opens two sessions: S1 (activity 9) and S2 (activity 10).

**Steps.**
1. Six players join, consent, quote, buy and pay: 4 on S1, 2 on S2.
2. 程一苇 cancels within 24 h of the start: the real 50% refund of ¥19.50.
3. The sessions start (time shift). Five players check in at the gather point.
4. The lead shows each session's 团码, and both merchants scan both. Balances are still 0: **the scan moves no money.**

| Step (¥) | S1 sales | S2 sales | Platform 9001 | 主理人 990005 | Merchant 9002 (¥8) | Merchant 9005 (引流) | Check |
|---|---|---|---|---|---|---|---|
| Paid, 50% refund, check-ins, scans | 156.00 − 19.50 | 78.00 | 0.00 | 0.00 | 0.00 | 0.00 | lines per ticket 37.83 + 1.17 |
| 13 days after the end, real job | – | – | 0.00 | 0.00 | 0.00 | 0.00 | not due; balance page shows 168.06 / 40.00 「11-06 可提现」 |
| 15 days, real job | 136.50 | 78.00 | **6.44** (4.10 + 2.34) | **168.06** (108.40 + 59.66) | **40.00** (3 × 8 + 2 × 8) | **0.00** | 6.44 + 168.06 + 40.00 = 214.50 = 234.00 − 19.50 |
| Job re-run | | | 6.44 | 168.06 | 40.00 | 0.00 | nothing paid twice |
| Refund of reg 76 (S1, ¥39) after settlement (`cancel-by-owner`) | 97.50 | 78.00 | 6.44 (ledger −1.17) | **138.23** (−29.83) | **32.00** (−8.00) | 0.00 | clawback −1.17 − 29.83 − 8.00 = −39.00 |
| Job re-run | | | 6.44 | 138.23 | 32.00 | 0.00 | |
| Theme settlement (real job) and re-run | | | 6.44 | 138.23 | 32.00 | 0.00 | topic `is_settlement=1`, `coop_settlement` empty, orders still status 0, job status 0 |

Each S1/S2 registration after settlement: 主理人 29.83 + coop_merchant 8.00 + platform 1.17 = 39.00.

The player's own refund after the start is refused (「场次已开始,不可退款」), as policy says. The organiser path is used.

The platform balance is not debited by the refund; only a −1.17 ledger line is written. That is the existing rule
(h4 noted it) and I didn't change it.

**Theme first, then sessions** (second run, fresh DB):
- The theme settles with no row and no payment while the sessions are inside the 14 days.
- Then the sessions settle: 6.44 / 168.06 / 40.00 / 0.00.
- The refund after settlement gives 6.44 / 138.23 / 32.00 / 0.00. These are the same numbers, with no double payment.

**Alarms.** The app log of both runs has **0** lines matching `MANUAL_REPAIR`, `结算告警` or `CLUB_SESSION_MERCHANT_FEE`.
The only alarms are 「业务通知未送达」 (WeChat messaging, which the rig cannot reach), an unset alarm webhook and an
unregistered patrol job. Before this change, h4's walk refused the theme with `REFUND_NET_BASIS_UNPROVEN` on every run.

**Screens** (`fix-shots/k5-settled-*`, `k5-refunded-*`; I looked at each one):
- 俱乐部结算 after the refund. S1: ¥78.57, sales 97.50, 平台服务费 3% 2.93, 商家核销费 16.00. S2: ¥59.66, sales 78.00, 2.34,
  16.00. 可提现 ¥138.23.
- Merchant 核销与退款: two 外滩钟声 · 领队场 rows, both +¥16.00 after the refund (+24/+16 before it). Both are 已入个人账户.
  已入账 is ¥32.00, matching the balance.
- 核销详情 (S2): ¥16.00, 计提规则 ¥8.00/人 × 2, 结算去向 个人账户.
- Merchant 财务 收入明细: 合作分润 +24.00, 合作分润 +16.00, 调整 −8.00.

The "06:00" time on the merchant rows is the rig DB's UTC clock (HARNESS).

### Tests

- **`ClubSessionMerchantFeeMySqlIT`** (new, 4 cases, real MySQL 8.0.45, registered in the funds gate: `EXPECTED`,
  `SOURCE_FILES`, `-Dtest`, and the selftest fixture). Cases:
  - Four players and the half refund, settled at 13 then 15 days. Ledger lines, claims, the 俱乐部结算 row and the
    merchant rows are checked; the re-run pays nothing; the refund split is applied to the real lines.
  - Took part by check-in, by ticket, by node progress, and with a correction to "not arrived". A held registration is
    paid at release.
  - A fee above the 主理人's share refuses and rolls everything back.
  - The theme basis and refund-fence SQL leave out live club sessions only.
  - 4/4 green.
- **Unit tests.**
  - `ClubSessionMerchantFeeSettlementTest` (11 cases): split; 引流; no scan; percentage refused with alarm; above the
    share; above one line; frozen mismatch; merchant = 主理人; held release; clawback to the fen; 财务 rows.
  - `ClubSessionThemeSettlementTest` (3): zero-cash club theme closes without a plan; a per-session plan is skipped
    while theme cash still adds up; candidates drop club sessions.
  - `ClubSessionPrepareRouteTest` (2).
  - The settlement test fails on old code: the merchant is never paid.
- **Existing tests updated, no `@Test` added or removed:**
  - `Wp04TopicSettlementEntryGuardsTest` stubs the new non-club-session query, so a non-club activity is still refused.
  - `ClubPrincipalPayeeSettlementTest` wires the fee component.
  - `TandianriBatch0FundsMybatisIntegrationTest` adds the `club_id` column, which production has, to its fixture DDL.
- **Unit sweep:** 4,497 tests across settlement, coop, refund, club, merchant and activity. The only non-DB failure is
  `ClubWaitlistClaimMembershipRaceTransactionTest`, which fails the same way without my changes (pre-existing).
- **Real MySQL locally** (all green):
  - The whole funds gate list (65 classes).
  - The four that need CI-only DDL flags, run with the flags: RegistrationFunds 114, RefundPayout 30, RefundRetry 10,
    RefundNotification 9.
  - The co-op real-storage ITs that read the SQL I changed: Wp04 OriginalRefund 25, TopicCashFence 5, CoopOrderScope 11,
    ApprovedRefundFence 5, CurrentCashBasis 4, CashEvidenceExpand 45, CoopSettlementEvidence 7, OriginalProducer 3,
    PaidHalfState 5, CoopOrderStationFulfillment 13, CoopSupplyStation 7.
  - ComplaintPeriod 7, ClubSettlementSummary 6, ClubEditionCompensation 21, SettlementGenerateBatch 3.
  - `RUNNER_ENVIRONMENT` was set to satisfy the tests' guard; this is a container, not a GitHub runner.
- **Mini-program:**
  - `k5-club-session-merchant-fee.test.js` (3), plus `club-settlement-page-contract` and `merchant-ledger-display`
    (28 pass), and k6's `k6-merchant-screens` (4).
  - `ui-integrity.js` → `UI-GATE-0:PASS`.
  - Action ledger rebuilt with `--write`: line shifts only, 5716 controls.
- **Funds gate selftest:** `python3 -m unittest ci/test_funds_it_gate.py` 45 OK.
- **Chain acceptance:**
  - Run in the rig at `58641850` through `chain_acceptance`'s own `run_chains` (`chains.py finance`). platform,
    merchant_club, sale_close, player and finance all **PASS** (`proof/k5-walk/chains-finance.log`).
  - The rig DB had the 3% fee-rule rows and `cms_setting` row 1, which production has.
  - I did not dispatch `chain-acceptance.yml`. As k6 found, its seed lacks those rows since k1 item 7, so it would stop at
    merchant_club for that reason alone.

### CI

On `58641850`, ci.yml and Funds MySQL IT started, then were cancelled by later pushes from other lanes; that is the
branch's concurrency setting. Results on later heads that contain both code commits are at the end of this file.

## Files

- `service/support/ClubSessionMerchantFee.java`, `mapper/ClubSessionMerchantFeeMapper.{java,xml}`, and VOs
  `ClubSessionFeeRegistration`, `ClubSessionFeeEstimate`, `MerchantSessionFeeLine`.
- `CmsActivityServiceImpl`: settle and release.
- `RefundServiceImpl`: debit `coop_merchant`.
- `CmsTopicServiceImpl`, `CoopSettlementClaimServiceImpl`, `TopicSettlementEvidenceService`, `ICoopSettlementClaimService`,
  `CoopSettlementEvidenceMapper.{java,xml}`, `CoopSettlementOriginalMapper.xml`: theme path.
- `CoopSettlementMapper.xml`, `ClubSessionSettlementRow`, `ApiClubSettlementController`, `pages/club/settlement`:
  俱乐部结算.
- `MerchantFinanceQueryService`, `MerchantRedemptionView`, `pages/merchant/ledger/order-detail`: merchant screens.
- `MemberFundsStageService`: estimates.
- `ci/funds_it_gate.py`, `ci/test_funds_it_gate.py`, `ci/funds-mysql-it-gate.sh`.

No migration: no table or column was added. `receiver_type` is `varchar(32)` and the debt `source_type` fits.

## Not verified / limits

- **Phone:** nothing. Screens are from the rig browser.
- **Estimates:** the 待结算 estimate counts heads at the moment of reading. The amount actually paid is decided at
  settlement under row locks.
- **Old orders:** a registration from before payment-time ledger lines, with no 主理人 `settlement_detail` line, makes
  its session refuse (`PRINCIPAL_LEDGER_LINE_UNPROVEN`). Current app orders always have the line.
- **50% refund bookkeeping:** the retained half of a refunded ticket still has no `settlement_detail` line (h4's
  bookkeeping finding). Balances are right. Unchanged.
- **Payment after the theme closes:** in the rig, a session ticket bought after the theme had been settled was refused
  at the payment callback. That is the existing closed-theme fence. Real sessions cannot sell after the theme ends, so I
  proved the reverse job order with sessions sold before the theme closed.
- **Merchant row:** the 核销与退款 list row doesn't mark a session as partly refunded. The amount is net, and 核销详情
  shows 退款.

## Needs owner

- **Percentage terms on club themes.** These are reachable through the API (see above). Today they refuse each scanned
  session with an alarm. Recommended default: also refuse `shareMode 1` invitations on club themes at `/api/coop/invite`
  with a plain reason, as the club page already does.

CI on a commit that contains all my work:
- **Funds MySQL IT: green** on `ca1ddd82` (contains `943b7f67` and `58641850`). The real-MySQL gate step ran for 4 min
  and includes the new `ClubSessionMerchantFeeMySqlIT`.
- **GitHub CI (ci.yml): red** on `2f9daae1` (my docs head; run 41979418953). **None of the failures are from k5's
  changes.**
  - Backend: two `P0Deregistration`/`WechatMemberRegistration` welcome-points cases (k1 `748f6519`, rule 1 fixture
    status). `ClubAttendanceTimeMappingMyBatisIT` fails with 「Unknown column 'create_time'」 in `ClubEventOpsMapper.xml`
    (k3 `b9df1ecd`).
  - Mini static: the danger-confirm selftest asks for consequences for `club.joinRequest.reject` / `club.enroll.refund`
    (k3/k4 screens).
  - Mini unit (reproduced locally, 5 of 16,211): the action-ledger count pin 5719 vs 5718, plus play, ticket-wallet,
    play-ending and club-detail cases.
  - Run alone, my tests pass: `k5-club-session-merchant-fee`, `club-settlement-page-contract`,
    `merchant-ledger-display-contract`, `k6-merchant-screens`.
  - `build-action-ledger.js --check` passes at this head.
  - I did not touch these files; they are for their lanes.

STATUS: COMPLETE
