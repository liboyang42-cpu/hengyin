# Round 12, lane k1-money: result

Branch `integ/launch-test-20261103`, private local branch, `pull --rebase` before every push, no force-push.
Commits: `748f6519` (items 3, 6, 7, 11), `94ce33a6` + `ab5cda5e` (item 4), `49a3b0fe` (item 2), `8bcfc46f` (item 5),
`636faf87` (item 10), `03620206` (item 9), `b77d772c` (fixture after item 11). PR #723 ③ updated.

**Not a phone, and no rig walk this round.** Proof is unit tests, real MySQL 8.0.45 ITs in a local loopback container,
migration rehearsals on that MySQL, and CI. No screenshots were taken (see "Not done / not verified").

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Merchant pay at session settlement | **not done** — design and blockers below |
| 2 | 14 days | done |
| 3 | Welcome points 50, one source | done |
| 4 | Club sessions don't count toward the 3-activity limit | done |
| 5 | Double order (j6 N1) | done (backend + checkout pages); not walked |
| 6 | 另存为模板 copies | done |
| 7 | Missing platform-rate rule refuses | done |
| 8 | Merchant money screens | **not done** |
| 9 | Prices | j1 N2 done; p1 N-1 not done |
| 10 | Migrations / release order | done |
| 11 | 停用 for rules 1, 4, 7, 11, 12, 13 | done (it was the same small change) |

## 2. 14 days — done

One constant: `FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS = 14` (`chengyinhub-system/.../support/FundsHoldPolicy.java`).
SQL reads it as `${@…FundsHoldPolicy@WITHDRAWABLE_AFTER_END_DAYS}`. Every place the wait lived:

| Place | Was | Now |
|---|---|---|
| `CmsActivityMapper.selectActivitiesForSettlement` (activity and club-session settlement) | `INTERVAL 7 DAY` | constant |
| `CmsTopicServiceImpl` club self-play topic settlement `payable_time` | end + 7 d | `FundsHoldPolicy.payableAfterEnd` |
| `CmsTopicServiceImpl.settleCoopTopic` `payable_time` (theme co-op; 商家结算 T+N 打款 pays when due) | end + 7 d | same |
| `SettlementDetailMapper.topicTicketNotYetSettleable` (merchant settlement batches) | `interval 7 day` | constant |
| `MemberFundsStageService` (余额页「X月X日可提现」) | 7 d | constant |
| `TopicSaleFinalizationServiceImpl.EVIDENCE_DUE_DAYS` (club compensation FINAL; nothing is paid before it) | 7 | constant |
| Holds (`ActivitySettlementHold`) | no day count; released when the complaint closes | unchanged |
| Screen copy | no screen states a 7-day number (checked `chengyinhub-xcx` and `chengyinhub-ui`) | – |

Left as is (not a payout wait): `AUTO_VERIFY_END_BUFFER_DAYS = 7` (auto-close of unverified tickets),
account deregistration cooling-off, recommendation retention, supply freshness.
Existing `coop_settlement` rows keep the date they were written with.

Tests that pinned 7 now use 13 / 15-day boundaries: `ComplaintPeriodWithdrawalGateMybatisIntegrationTest`,
`SettlementGenerateBatchMybatisIntegrationTest`, `TandianriBatch0FundsMybatisIntegrationTest`,
`SettlementRefundDeadlineGateSqlTest` (also asserts the constant is 14), `MemberFundsStageServiceTest`,
`TopicSaleFinalizationServiceImplTest`. All pass on MySQL 8.0.45. No `@Test` added or removed.

## 3. Welcome points — done

- Balance and ledger now read the same number: rule 1's `value` if the rule is enabled, else 0 and no ledger line.
  `UmsMemberServiceImpl.getOrCreateWechatMember` and the three `ApiLoginController` registration paths (phone, App
  WeChat, Apple) all go through `PointsRuleSwitch.activeValue(rule 1)`. No other path grants rule 1.
- New `migration_20261105_welcome_points_fifty.sql`: id 1 `value → 50`, runner assertion, registered in
  `ci/migration-dependencies.json`, listed with phase in `integ/RESULT.md` and PR #723 ③. Rehearsed twice on MySQL:
  assertion true both times, second run changes nothing.
- `WechatMemberRegistrationTest` pinned the mismatch (balance 50, ledger 73); it now asserts balance = ledger, plus a
  disabled-rule case.

## 4. Quota — done

`selectActivityQuotaUsedByMemberId` no longer counts a session a club opens: an activity with a theme (`topic_id`)
whose own `club_id` is set, or whose theme belongs to a club (public self-hosted club sessions don't carry `club_id`).
Published activities (no theme, including club activities with `club_id`) and merchant-theme sessions still count.
`PublicActivityScopeGateMyBatisTest.clubSessionsDoNotTakeActivityQuota`: real MySQL, old SQL gives 6, new gives 3.

## 5. Double order (j6 N1) — done, not walked

Read C-22 first: the owner removed every per-person ticket limit, so one person may buy several tickets for one
session across orders. That still works: only an order that can still be charged blocks a new one.

- **Create.** `CmsRegistrationServiceImpl.create` locks the member row (after the theme fence, the callback's lock
  order) and refuses a new order while the same member has, on the same owner (session or theme), an order with
  `payment_status = 1` (WeChat attempt issued, even if locally expired and not yet reconciled: WeChat may still take
  it) or an unexpired `payment_status = 0`. Same idempotency key is excluded, so a concurrent replay still returns the
  winner. Refusal: code 409, `errorCode PENDING_ORDER_EXISTS`, `data.registrationId` of that order, msg
  「这一场你还有一笔订单没有完成支付」.
- **Pay.** `beginPaymentAttempt` refuses pay parameters (`ORDER_SUPERSEDED`) for an order when a later order of the
  same member and owner is already paid. `retryPayment` then tries the existing safe cancel (`cancelUnpaid`: query
  WeChat, close only on NOTPAY/PAYERROR/CLOSED), which releases the ticket; if WeChat can't be reached it is left to
  the expiry reconcile, same path. A late WeChat callback can't land on a locally closed order because the local close
  only happens after WeChat confirms the attempt closed.
- **Screens.** 报名页 and 主题页 route `PENDING_ORDER_EXISTS` / `ORDER_SUPERSEDED` to 订单详情 of that order (the
  409 used to be read as 「价格已更新」). After the pay sheet is closed, if the cancel didn't go through, the page
  now opens that order instead of silently going back. No new captions.
- **Tests.** `RegistrationFundsMybatisIntegrationTest.secondOrderForSameSessionIsRefusedWhileFirstIsStillChargeable`
  (real MySQL: unpaid and paying both block, no row and no stock taken; superseded order refused; after close a new
  order is allowed with a paid sibling present). Count 113 → 114 in `ci/funds_it_gate.py` and its fixture.
  Two existing cases that created a second order for the same session while the first was pending now close the first
  (`legacyNullFingerprint…`), and the guard ignores the same request id (`concurrentCreateOrReplay…` passes unchanged).
  `RegistrationCreationTopicFenceTest` +1 and asserts the lock order. `tests/unit/k1-n1-pending-order.test.js` (3).
- **Not verified:** the rig walk (pay sheet closed → second buy → sent to the order) and a real phone.

## 6. 另存为模板 — done (blocker)

`saveAsTemplate` now copies the theme, chapters and nodes (`copyType 2`: no tickets, sessions, roster or
collaborators) into a new row and marks only the copy as an approved, listed template (`markTopicTemplateCopy`,
`where is_template = 0 and member_id = …`). The live theme isn't touched. The API returns `templateId`.
`TopicSaveAsTemplateCopyTest` (3); `GenericGameplayHttpE2eIT` now asserts the original keeps
`status/user_status/is_template/del_flag` and its sessions, and `/use` works on the copy and not on the original.
The new status writer is registered in `doc2-o5b-audit-truth.md` §2 and `AuditStatusTruthConsistencyTest`.
**Owner note:** themes already taken off sale by the old behaviour still have `is_template = 1`; I did not change them.

## 7. Platform rate — done

`PricingServiceImpl` has no fallback constants any more. Missing rule row, a failed read, or any null among the four
fee fields → `IllegalStateException` (pricing, preview, guarantee rate and the fee snapshot). Production has these
columns `NOT NULL DEFAULT`, so only a missing row triggers it. Other money code was already fail-closed
(`CmsRegistrationServiceImpl.resolveFeeSnapshot`, activity and topic settlement). Pure-calculation tests that relied on
the hidden 10% now inject the same values explicitly (`PricingTestRules`); `FeeSnapshotTest` and
`PricingServiceImplTest` assert refusal; `ProductSourceWiringTest` asserts no `FALLBACK_` remains.

## 9. Prices — j1 N2 done

- 发布活动 price: `type="digit"` (the number keyboard has no dot), the typed string is kept (`utils/price-input.js`:
  digits, one dot, two decimals), validated to two places, converted on submit.
- Every money input in the app checked: 主题编辑 ticket (`onEditTicketChange`) and self-play price already kept strings;
  定价 page keeps strings; 合作邀约 fee had the same bug (Number() while typing) — fixed the same way. Game xp and
  cost fields in `publish/temp` are points, not money.
- `tests/unit/k1-price-input.test.js` (3); one existing test now expects the string `'0'`.
- **p1 N-1 (积分抵扣 row) not done.**

## 10. Migrations — done

- `migration_20261104_topic_total_time_seconds.sql` (unreleased, new against master, fixed in place as this item asks):
  rows already equal to 60 × a node-minute sum are skipped, so a second run is a no-op and the edge row from r1 F2
  converts once; the assertion uses the same rule. Added to `POST_DEPLOY_MIGRATIONS`. Rehearsed twice on MySQL 8.0.45
  with an editor row (270 → 16200), a seed row (5400 unchanged), the F2 edge row (1 → 60, then unchanged), a host-only
  row (20 → 1200) and a no-node row: assertion true both passes.
  Rows that are both "all minutes" and "host minutes × 60" are ambiguous and left alone; r1's precheck [3] counts them.
- `integ/RESULT.md` § "Release order and rollback (Round 12, k1 item 10)": all 14 files with ledger order and phase
  (incl. `author_write_receipt` when production lacks it), what each change means on a rollback (r1 F4), behaviour
  changes with no migration (points switch, rate refusal, 14 days; r1 F3), and **ship the mini-program with the jar**
  (r1 F5). PR #723 ③ updated the same way.
- `python3 -m unittest ci/test_migration_ledger.py` 56 OK; `ci/migration-safety-check.sh` and layout check pass.

## 11. 停用 for all rules that read `value` — done

`PointsRuleSwitch` (status = 1 and del_flag null/0) is now the one test for rules 1 (registration), 2/3/5/6 (h3's,
refactored onto it), 4 (`CommentPublicationService`, `ApiBetaGameController`), 7 (`MmsMerchantServiceImpl`) and
`PointsAwardServiceImpl.award` for event types 11/12/13 (first **enabled** rule; was first row). Disabled rules write
no zero ledger lines. Production has them all on (owner), so nothing stops at deploy. Fixtures updated:
`B03PointsAwardServiceTest`, `MerchantIdentityMybatisIntegrationTest` (CI red at `636faf87` came from this; fixed in
`b77d772c`, 21/21 on MySQL).

## 1. Merchant pay at session settlement — not done

I read `settleActivity`, the refund clawback (`RefundServiceImpl.reviewRefund`, `allocateClawback`,
`actualDebitMember`), `SettlementEntryServiceImpl` (per-registration `settlement_detail` lines with `settlement_claim`),
the group scan (`GroupCodeServiceImpl` → `coop_group_verify`, per-player `coop_group_member_verify` written on ticket
verification) and the theme co-op path (`CoopSettlementClaimServiceImpl.prepare`, `TopicSettlementCashBasis`).
Doing it to the fen needs changes in four fenced places at once, and a half-done version moves real money wrongly,
so I stopped rather than rush it. Proposed design for the next lane:

1. **Heads.** Per session A and effective co-op order O (to a merchant) with a `coop_group_verify` row for (A, O):
   registrations `owner_type=2, owner_id=A`, `payment_status=2, registration_status=2` (any refund, including 50%,
   isn't counted: default as instructed), `verification_status=1` (took part), not held for a complaint.
   Fee = O's frozen `fixed_fee`. **Percentage terms (share_mode 1): same gap.** Recommended: refuse the session's
   settlement with an alarm until the owner sets a rule; today the lead silently gets the merchant's share.
2. **Split.** merchant = fee × heads, from the 主理人's share; platform stays 3% of retained sales; if merchant >
   主理人 share, or fee > that player's 主理人 line, refuse that session with an alarm.
3. **Ledger for clawback.** For each counted registration, reduce the 主理人's `settlement_detail` line by the fee and
   add a line for the merchant with its own `receiver_type` (not `merchant`: `actualDebitMember` would redirect it
   to the 主理人) and a `settlement_claim`. The existing clawback then takes the merchant's part back pro rata.
   Held registrations pay the fee at `releaseHeldRegistration`.
4. **Theme path.** `TopicSettlementCashBasis` throws `ACTIVITY_CASH_BASIS_UNSUPPORTED` on any session row, and
   `selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate` counts session refunds
   (`REFUND_NET_BASIS_UNPROVEN`). Live sessions settle in `settleActivity`, so both should look only at deleted
   activity shells. A club theme with no theme-level cash then hits `validateZeroCashCoopContracts`
   (`NO_PROVEN_FULFILLMENT`) — it needs a "settled per session" close for those orders, which is the part that touches
   the claim service.
5. Screens: 俱乐部结算 (`selectClubSessionSettlements` already subtracts merchant/platform clawbacks; add the new
   receiver type), merchant 财务 and 核销与退款.

## 8. Merchant money screens — not done

j1 N4, p2 N-1…N-5, j1 N3 untouched. No time left after items 1–7, 9–11.

## Verification

- Java: full `chengyinhub-admin` suite at `49a3b0fe` (without DB credentials): 13,077 run; the only non-DB failures were mine
  (fixed). Targeted sets after each change all green. Real MySQL 8.0.45 (loopback container): RegistrationFunds 114/114,
  MerchantIdentity 21/21, ComplaintPeriod, SettlementGenerateBatch, TandianriBatch0, PublicActivityScopeGate,
  ClubEditionCompensation and the other 7-day classes green. A second full run with all funds ITs is in the section
  below if it finished.
- Mini-program: `ui-integrity.js` → `UI-GATE-0:PASS`; `build-action-ledger.js --write` rebuilt twice (line shifts and
  the new order-detail route only); unit files touched: 106 + 195 pass.
- CI: see below.

## Not done / not verified

- Item 1 and item 8 (above); p1 N-1.
- No rig walk and no screenshots at 390×844 this round. Item 5's and item 9's screen behaviour is unit-tested only.
- Real phone: nothing.

STATUS: PARTIAL - items 1 (merchant pay at session settlement) and 8 (merchant money screens) not done, p1 N-1 not done, no rig walk or screenshots; items 2-7, 9 (j1 N2), 10, 11 done and tested.
