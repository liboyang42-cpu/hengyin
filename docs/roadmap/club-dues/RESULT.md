# K1 club-dues: result (hengyin)

Branch `feature/r-club-dues` from `origin/roadmap/be-integ` (084db8d53). App half: chengyin-app `cloud/r-club-dues`.
Resumed run: the earlier launch pushed nothing; everything here is from this run.

**Gate.** Everything that moves money acts only while the money switch's server ceiling
`chengyin.money-switch.server-ceiling` (`CHENGYIN_MONEY_SWITCH_SERVER_CEILING`, default and production `false`) is on,
i.e. while the S2 collection model is active. Ceiling off: dues renewal, resume, payment callback, refunds and the
payout task behave exactly as before (`ClubDuesSplitTest.ceilingOffChangesNothingAndReadsNothing`,
`ClubMembershipServiceImplTest.modelOffKeepsTodaysPlatformOrderExactly`). The club membership feature flag
(`feature.flag.clubMembership`, sys_config) still has to be on too. No production switch value changed.

## Findings

| ID | Status | How |
|---|---|---|
| CLB-07 / MON-11 (backend) | closed behind the money switch | Dues have a split path: paid by the member to the **club's own sub-merchant** (service-provider JSAPI, `profit_sharing=true`); the platform take is one profit-sharing receiver amount after the hold; never a member-balance hold. Clients now exist (mini-program page, app page). |
| collection-model `DUES_SPLIT_NOT_BUILT` | replaced | Dues are refused only when the club cannot be paid by split (`SELLER_NOT_BOUND`, `SUB_MCHID_IS_PLATFORM`, `SPLIT_RAILS_OFF`, or policy `REFUSE`). |
| MON-14 | still open | Nothing ran against WeChat (stubs only); TEST rehearsal needed. |

## The dues path (ceiling on)

1. **Decide** (`CollectionModelService#decideClubDues`): seller is always the club (`EcommerceSellerResolver#clubDuesFacts`).
   Policy `chengyin.collection-model.policy.club-dues` = `SPLIT` (default) or `REFUSE`; anything else, `PLATFORM`
   included, is `REFUSE`. A PLATFORM outcome is impossible (forced to refusal).
2. **Refuse before any invoice row** (`requireClubDuesCollectible`, called first in `renew` and before a new prepay in
   `resumeInvoice`): typed `CLUB.DUES_COLLECTION_UNAVAILABLE` (422 in v2) with `data.reason` and `data.nextStep`:
   `BIND_COLLECTION_ACCOUNT` (club has no finished sub-merchant), `WAIT_FOR_PLATFORM` (rails off / policy refuse).
   Counted in `cy_collection_refusal` as seller type `DUES`.
3. **Order** (`ClubDuesSplitService#createOrder` → `EcommercePartnerPaymentAdapter#createDuesOrder`): writes the
   `ecom_order_seller` row (CLUB, club id, sub_mchid), then the ledger row `cy_collection_dues_order` with the **take
   rate frozen** (`TakeRateService#clubDuesRate`), then freezes the payment intent on the sub_mchid and calls
   `/v3/pay/partner/transactions/jsapi`. An attempt already started on the platform account is refused, never moved.
   Query, close, callback and refund then route through the existing partner adapter because the seller row exists;
   `handlePaidInvoice` is merchant-agnostic (it checks the immutable verified payment fact).
4. **Platform never collects dues** while the model is on: `WechatPaymentSdkGateway#createGenericOrder` and
   `createGenericAppOrder` refuse any `CLM…` out_trade_no (`requirePlatformMayCollectDues`).
5. **Share** (`ClubDuesSplitService#planDue`, run by `EcommercePayoutTask#shareAndRetry`): due when the invoice is
   PAID, the transaction bound, `paid_at` + `FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS` (14) passed and no refund
   review is pending. An open refund waits; a full refund closes the row (`REFUNDED`). One single-seller plan
   (`planSingleSeller`, source id 0): the platform take is the only receiver, the rest is RETAINED on the club's
   sub-merchant. Missing rate → manual review `SPLIT_TAKE_RATE_MISSING`, never a guessed rate.
   `selectDuePlannedShareOrders` now also submits planned dues orders.
6. **Refund**: the existing partner refund (`submitRefund`) returns the shared take first (分账回退), then refunds from
   the club sub-merchant (`ClubDuesSplitTest.aDuesRefundReturnsTheSharedTakeFirst`). Note: dues refunds today are a
   review queue only (`club_membership_refund_review`); there is still no operator action that executes one.

**Take rate.** New `TakeProduct.CLUB_DUES` (`club_dues`) in the merchant-plans take table: until someone sets it in
the console, the dues take is today's club ticket take (`settlement_rule` `topic_club`, else `topic`).

## What changed, file by file

- `chengyinhub-admin/sql/migration_20261115_club_dues.sql` (new) + `ci/migration-dependencies.json`: table
  `cy_collection_dues_order`; idempotent; rollback note; no menu, no data.
- `collection/ClubDuesSplitService.java` (new), `collection/mapper/ClubDuesSplitMapper.java` (new).
- `collection/CollectionModelService.java`: `decideClubDues`, `requireClubDuesCollectible` now returns the decision,
  `requirePlatformMayCollectDues`, `clubDuesStatus`, `duesNextStep`, policy `club-dues`.
- `payout/EcommerceSellerResolver.java`: `clubDuesFacts`.
- `merchantplans/TakeProduct.java`, `TakeRateService.java`: `CLUB_DUES`, `clubDuesRate()`.
- `service/impl/EcommercePartnerPaymentAdapter.java`: `createDuesOrder`.
- `service/impl/WechatPaymentSdkGateway.java`: dues guard in both generic order methods.
- `service/impl/ClubMembershipServiceImpl.java`: split prepay in `renew` / `resumeInvoice`; `duesCollection` on the
  overview; new `duesCollection(principal, clubId)` (club write access).
- `IClubMembershipService.java`, `ClubMembershipOverviewVO.java` (`duesCollection` field, additive).
- `ApiClubMembershipController.java`: new `POST /api/club/membership/collection` (owner). No route removed or renamed.
- `EcomPayoutMapper.xml`: planned dues share orders are due for submission.
- `web/task/EcommercePayoutTask.java`: plans due dues.
- Mini-program: `pages/club/membership/` (new page, registered in `app.json` club subpackage): members see status,
  benefits, plans, bills, join/renew (WeChat JSAPI) and continue payment; owners (`manage=1`) see the collection
  state, switch paid membership on/off and create/edit plans. Paying needs the `tickets` money area and a collectible
  state; otherwise 「在线缴费即将开放」 or the club-not-bound message. Entries 会费设置 / 付费会员 in the club settings
  sheet (`pages/club/detail`). `scripts/uiaudit/route-matrix.json` rebuilt; `ci/silent-error-allowlist.txt` entry.
- Tests: `ClubDuesSplitTest` (11, new), `ClubMembershipServiceImplTest` (+4), `CollectionModelServiceTest` (dues
  test updated to the new behaviour), `tests/unit/k1-club-dues-membership.test.js` (6, new).

## Verification (LANG=C.UTF-8 LC_ALL=C.UTF-8)

- `mvn -B -q -DskipTests compile` → ok.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='ClubDuesSplitTest,CollectionModelServiceTest,ClubMembershipServiceImplTest'`
  → 11 + 11 + 82 pass.
- Wide run `-Dtest='*Registration*Test,*Refund*Test,*Settlement*Test,*Order*Test,Ecommerce*Test,*Payout*Test,ClubMembership*Test,WechatPay*Test,Erqing*Test,*Collection*Test,MoneySwitch*Test,*Checkout*Test,MerchantPlans*Test,ClubDues*Test,*TakeRate*Test,SellerOfRecord*Test'`
  → 291 classes, 2414 tests, 0 failures, 0 errors, 220 skipped (existing MySQL / opt-in ITs).
- `bash ci/migration-safety-check.sh` → 迁移安全门禁:通过; `bash ci/migration-layout-check.sh` → 通过; no `rollback_*.sql`.
- `python3 ci/role_permission_contract.py` → all endpoints guarded; `node ci/api-existence-gate.js` → exit 0.
- `node ci/silent-error-gate.js` → same 6 unregistered files as the base (none of this package).
- Mini-program: `node --test tests/unit/k1-club-dues-membership.test.js` → 6/6. Full suite: see "Mini-program suite" below.

## Mini-program suite

`node --test "tests/unit/**/*.test.js"` (17,651 tests) showed failures in 24 files. Those 24 files were re-run on this
branch and on a clean `origin/roadmap/be-integ` worktree: base 53 failing, this branch 37 failing, and every failure on
this branch also fails on the base (no new failure). Along the way this package's page was fixed for the shared
contracts it tripped (bare `¥{{…}}`, native `<switch>`, press feedback, silent-error registration). The route-count
tests (`route-matrix-freshness`, `title-system-owner-contract`, `nav-page-title-spacing`) already fail on the base
(base app.json has 181 pages vs pinned 173); this branch adds one page and rebuilt `route-matrix.json`.

## Not verified

- Nothing ran against WeChat Pay or MySQL. The annotation SQL of `ClubDuesSplitMapper` and the mapper XML change were
  not executed against a database. The mini-program page was not opened in the WeChat devtools (no screenshots).
- A real paid dues order end to end (TEST rehearsal below).

## What counsel must confirm (builds on collection-model LEGAL-QUESTIONS Q8)

1. **Dues to the club's own sub-merchant.** We built Q8's first option: dues are paid by the member directly to the
   club's 电商收付通二级商户 and the platform takes only its service fee by 分账. Confirm this is not 二清 for dues, and
   that a club (often an informal group) may hold a 二级商户 (企业 / 个体户 / 小微) for membership income.
2. **What the fee is.** The platform's share of dues is a 技术服务费 taken by 分账 at the club ticket rate unless set
   separately (`club_dues` take). Confirm the fee may be taken on membership income and how it is invoiced to the club.
3. **Hold before sharing.** Dues are held frozen on the club sub-merchant for 14 days after payment (no event end
   exists for dues), longer while a refund review is pending. Confirm the hold, and that it fits WeChat's
   unshared-funds limit; terms longer than the hold are not held for their whole length.
4. **Refunds.** A refund first returns the platform's fee, then refunds from the club's sub-merchant; dues refunds are
   decided by review (`MANUAL_REVIEW` plans) or not offered (`NON_REFUNDABLE`). Confirm a non-refundable membership
   is lawful for consumers (七日无理由 does not apply to services?), and who bears a refund once the club's balance
   has been withdrawn.
5. **Collecting outside the platform.** Clubs without a sub-merchant cannot take dues online (members are told to ask
   the owner). May a club collect dues entirely off-platform and still mark members as paid? We built nothing for it.
6. **Members paying in the app.** The native app does not take dues (no APP-channel service-provider order); members
   pay in the mini-program. Confirm this is acceptable for launch (same as collection-model Q11).

## Owner actions and open questions

- Keep `CHENGYIN_MONEY_SWITCH_SERVER_CEILING=false` in production; rehearse in TEST: club sub-merchant FINISH,
  `feature.flag.clubMembership=true`, money area `tickets` on, a 0.01-yuan plan, pay in the mini-program, shorten
  the hold in TEST, check 分账收款 ledger (dues rows are in `cy_collection_dues_order`; the console page lists ticket
  orders only), then refund one.
- Decide whether club dues should get their own money-switch area; today both clients use `tickets`.
- Decide the dues take (`club_dues` in 商家套餐与抽成 → 平台抽成); default = club ticket take.
- Build (or not) an operator action that executes an approved dues refund; today review only.
- Grace / past-due days are fixed at 0 in the plan editors (term simply ends). Say if owners should set them.
- The admin 分账收款 page does not list dues yet (`ClubDuesSplitMapper#selectLedger` exists for it).
- No hard-coded secret found in the touched files.
