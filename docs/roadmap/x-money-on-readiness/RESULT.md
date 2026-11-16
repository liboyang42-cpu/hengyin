# W7 x-money-on-readiness — backend, admin console, mini-program receipt

Branch `feature/r-x-money-on-readiness` from `origin/roadmap/be-integ` (`87aaf3dd2`). App half: chengyin-app
`cloud/r-x-money-on-readiness` (receipt `docs/roadmap/x-money-on-readiness/RESULT.md` there).
Merchant review PKG-5. **Money stays off in production**: no production switch value, ceiling or `cy_money_switch` row is
written anywhere; every money action below still needs its Q5 area open, and the server ceiling defaults to `false`.

## Issues

| ID | Status | Summary |
|---|---|---|
| MERCHANT-8 | Fixed | Number matching on the payout-binding QR: the scanner sees a two-digit number in the mini-program, the app owner must pick it from three; a wrong pick cancels the code. |
| MERCHANT-12 | Fixed | Plan orders carry payment instructions (or are not taken); confirm-paid needs reference + amount received; paid grant without an order needs `grantPaid`; club dues refund reviews can be approved (refund sent) or rejected from 商品与会费. |
| MERCHANT-15 | Fixed | Sponsored-quest writes need a reason; opening a money area needs two different admins; `admin` cannot open; runbook names the bypass (app repo); anonymous switch read cached 5 s + rate limited. |

## Changes, file by file

### MERCHANT-8 number-matched binding
- `sql/migration_20261130_payout_bind_match.sql` (new): guarded `ALTER` adds `cy_payout_bind_code.match_number TINYINT NULL`. Runs **before** the jar (the mapper selects it). The brief's `match_attempts` column is not needed: the first wrong pick cancels the code.
- `PayoutBindingMapper`: `claim(id, openid, matchNumber)`, new `cancelClaimed(id, memberId)`, `matchNumber` in the column list.
- `PayoutBindingService`:
  - `claim` stores a random 10–99 number and returns `matchNumber` to the mini-program (no openid leaves).
  - `status` returns `choices` (the number + two others, order fixed per code so polling never reshuffles) and `claimedAt`, only while CLAIMED.
  - `confirm(memberId, purpose, clubId, pickedNumber)`: no pick → `BAD_REQUEST PAYOUT_BIND_NUMBER_REQUIRED` (nothing cancelled; an old app); wrong pick or a claim without a number (made before this deploy) → code cancelled (outside the transaction, so it stays cancelled) and `CONFLICT PAYOUT_BIND_NUMBER_MISMATCH`. The app maps both codes to its own copy.
- `ApiMoneyPayoutController.confirm`: reads `matchNumber` from the body.
- Mini-program `utils/money-payout.js` (`claimBinding` hands back the number, `matchNumberOf`), `subpackageMember/payout-bind/index.{js,wxml,wxss}`: shows the number large with 「请在城瘾 App 里选择 {{n}}，再点「确认绑定」」 and a do-not-share line. One new class `.pb-match` built from existing tokens only.

### MERCHANT-12 operator dead ends
- `sql/migration_20261130_money_on_operator.sql` (new): `cy_merchant_plan_order.payment_reference`, `.amount_received_fen` (guarded ALTERs); table `cy_dues_refund_decision` (one decision per review; no member data, so no erasure change); buttons `business:merchantplans:grantPaid` and `business:commerceConsole:refund`, granted to no role (asserted). Runs **before** the jar.
- `MerchantPlanService`:
  - payment instructions from `chengyin.merchant-plans.payment.payee / .bank / .account / .deadline-days` (default 3): amount, payee, bank, account, reference = order number, deadline. Returned on checkout and on the plan page as `pendingOrder`.
  - checkout refuses `409 PLAN_PAYMENT_NOT_READY` while payee or account is not configured (no unpayable orders).
  - `confirmPaid(orderNo, paymentReference, amountReceivedFen, …)`: reference `[A-Za-z0-9_-]{4,64}`, unique across orders; amount must equal the order amount; both stored on the order and in the audit row.
  - `grant(…, paidGrantAllowed)`: a paid plan without an order needs `grantPaid`; Free never does.
- `MerchantPlansAdminController`: passes the new fields; `grantPaid` read through `PermissionService` (`@ss`).
- Dues refunds — `commerceconsole/DuesRefundDecisionService` + `mapper/DuesRefundDecisionMapper` (new, separate from `CommerceConsoleService`, whose writes still only reduce selling):
  - **reject**: note required, records REJECTED, moves no money.
  - **approve**: note required, only while Q5 `refunds` is open. Writes the decision (SUBMITTING, `CRF<reviewId>`, full invoice amount) and an audit row in one transaction; then, outside any transaction, calls the existing `IWechatPayService.submitRefund(outTradeNo, …)`, which routes split orders through `EcommercePartnerPaymentAdapter` (returns the platform share first) and platform orders through the direct refund; then records ACCEPTED / FAILED / UNKNOWN with a second audit row. UNKNOWN or SUBMITTING can be sent again with the same refund number (WeChat is idempotent on it). A restore pause is passed on.
  - `CommerceConsoleController`: `GET /dues-refunds`, `POST /dues-refunds/{reviewId}/approve|reject` (`business:commerceConsole:refund`).
  - `ClubDuesSplitMapper.selectDueOrders`: a review the operator **rejected** no longer holds profit sharing; an approved one keeps holding.
  - `ClubMembershipMapper.xml` / `ClubMembershipRefundReview`: the member's refund review list also carries `decision` and `refundState` (additive fields; the note stays internal).
- Admin UI: `merchantplans/index.vue` (确认已收款 dialog with 收款流水号 + 实收金额; 收款流水号 column; paid grant blocked without `grantPaid`); `commerceconsole/index.vue` + `api/business/commerceConsole.js` (会费退款 tab with 通过 / 重新发送 / 驳回 and a required reason).
- Mini-program `pages/merchant/plans/{view-model.js,index.js,index.wxml}`: 付款方式 card (应付金额, 收款方, 开户行, 收款账号, 转账备注 = order number, 截止日期); the pending order stays on the page after leaving.

### MERCHANT-15 admin safety
- `SponsoredQuestAdminService` / `SponsoredQuestCampaign.reason`: create, update and status changes refuse a blank reason; the reason goes to the audit row. UI asks for it (`sponsoredquest/index.vue`).
- `sql/migration_20261130_money_switch_two_person.sql` (new): `cy_money_switch_request`; button `business:moneyswitch:approve`, granted to no role; inserts no switch row.
- `MoneySwitchService`: `set(area, true)` now refuses with 「打开资金区域需要两个人…」; `requestOpen` (ceiling must be on, area off, one pending per area) and `approveOpen` (different person, case-insensitive; not `admin`; within 24 h; ceiling on) which writes the switch and the usual audit row naming both people; `rejectOpen`; `requests`. Switching off is unchanged (one step, also for `admin`).
- `MoneySwitchAdminController`: `GET/POST /requests`, `POST /requests/{id}/approve` (`:approve`), `/reject` (`:edit`). UI `moneyswitch/index.vue` + api: 申请打开 instead of 打开, 打开申请 table.
- `ApiMoneySwitchController`: one database read per 5 s for the anonymous route (failures never cached) and `@RateLimiter(60 s, 120, IP)`. Only this route caches; `MoneyGate` still reads directly, so switching off stops server paths at once.
- `ci/migration-dependencies.json`: three new entries.

## Verification (commands run here)

| Command | Result |
|---|---|
| `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` | OK |
| `mvn … test -Dtest=MoneyPayoutServiceTest` | 25 run, 0 fail (5 new number-matching tests) |
| `mvn … test -Dtest=MerchantPlansTest` | 24 run, 0 fail (5 new) |
| `mvn … test -Dtest=DuesRefundDecisionServiceTest` | 5 run, 0 fail (new) |
| `mvn … test -Dtest=MoneySwitchServiceTest,MoneyGateTest,SponsoredQuestServicesTest,PlayerMessagePunctuationTest,MemberPrivacyErasureServiceTest` | 17 / 4 / 13 / 2 / 4 run, 0 fail |
| `mvn … test -Dtest='*MoneyPayout*Test,MerchantPlans*Test,*CommerceConsole*Test,DuesRefund*Test,T5MapperSqlTest,MoneySwitch*Test,MoneyGate*Test,SponsoredQuest*Test,*Collection*Test,ClubDues*Test,ClubMembership*Test,PlayerMessagePunctuationTest,MemberPrivacyErasureServiceTest,TwoNodeSchedulingTest,SysJobStaggerPlanTest,E2eTestProvidersProfileTest,*Payout*Test,*Refund*Test'` | 98 classes, 902 run, 0 failures, 0 errors, 78 skipped (MySQL ITs); exit 0 |
| `bash ci/migration-safety-check.sh` | 迁移安全门禁:通过 |
| `bash ci/migration-layout-check.sh` | 迁移布局门禁:通过; no `rollback_*.sql` |
| `node --test "tests/unit/**/*.test.js"` (chengyinhub-ui) | base 31 fail (pre-existing) → branch 31 fail; 5 new contract tests pass |
| `node ci/prepare-mini-artifacts.js` then `node --test "tests/unit/**/*.test.js"` (chengyinhub-xcx) | base `roadmap/be-integ`: 17923 pass / 32 fail; branch: 17925 pass / 32 fail — the same 32 tests (pre-existing); 2 new tests pass |
| `node tests/lint/ui-integrity.js --warn-as-error` | exit 1 on base and on branch with the identical 127 findings (pre-existing, e.g. U5 silentError sites in `utils/money-payout.js`); no new finding |

No new `@Scheduled` method or `sys_job`, so the stagger and two-node ratchets are untouched.

## Not verified here (needs a database, WeChat or a device)
- The three migrations against MySQL 8 (guarded ALTER / CREATE / menu inserts; written like the existing ones).
- A real WeChat refund for a dues invoice (split and platform orders) in TEST with `refunds` open; refund notifications for dues are still not consumed (the invoice and term do not move to REFUNDED automatically — operations see ACCEPTED in the console).
- The binding flow end to end (mini-program scan → number → app pick).
- The admin pages rendered in a browser.

## Owner actions and questions
1. Configure `chengyin.merchant-plans.payment.payee`, `.account` (and `.bank`, `.deadline-days`) on TEST before a plan purchase rehearsal; without them merchants cannot place an order (honest refusal).
2. Grant `business:moneyswitch:edit` and `business:moneyswitch:approve` to **different** named people; never use `admin` for money.
3. Grant `business:commerceConsole:refund` and `business:merchantplans:grantPaid` only to named finance staff.
4. Decide whether dues refunds may be partial (today: full invoice amount only) and whether the dues refund notification should mark the term REFUNDED (follow-up; not in this package).
5. Deploy order: run the three `migration_20261130_*` files before the new jar.

## Overlap with the UI agent
None. `ui/h-acceptance-r2` and `fix/registration-list-r3` touch none of these files; the redesign branches do not touch the app files changed in the app half.
