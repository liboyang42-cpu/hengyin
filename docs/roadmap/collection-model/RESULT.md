# S2 collection-model: result (hengyin)

Branch `feature/r-collection-model` from `origin/roadmap/be-integ`. Resumed run: the first commit (`013bfc4bc`, policy
engine, split ledger, migration) came from the earlier run; this run wired it in, added the admin page, tests and docs.
App: not changed (the order request needs no new field; see "App and mini-program").

**Gate.** Everything acts only while the money switch's server ceiling `chengyin.money-switch.server-ceiling`
(`CHENGYIN_MONEY_SWITCH_SERVER_CEILING`, default and production `false`) is on. Ceiling off: every new branch returns
before reading a table or calling WeChat, and order creation, payment, settlement and refunds are exactly today's
(proved by `CollectionModelServiceTest.ceilingOffIsANoOpThatReadsNothing` and
`CollectionModelOrderGateTest.ceilingOffCreatesTheOrderExactlyAsToday`).

## Findings

| ID | Status | How |
|---|---|---|
| MON-02 (P0) | closed behind the money switch | Ceiling on: only official tickets reach the platform merchant account; every third-party ticket is collected by the seller's own sub-merchant (SPLIT) or refused (REFUSE). Split sales never enter a member balance; the platform's take is a profit-sharing receiver amount. Production stays as today until legal confirmation and "money on". |
| MON-14 (P2) | open (by design) | 收付通 still has never run against WeChat: tests use stubs only. The owner's TEST rehearsal steps are in `LEGAL-QUESTIONS.md` § console steps. |

## Policy (configuration, `application.yml` → `chengyin.collection-model.*`)

| Seller | Policy |
|---|---|
| Platform (official ticket: platform content slot, or an independent activity run by the platform settlement account; passes, platform products, templates, merchant purchases, deposits) | `PLATFORM`, fixed |
| Merchant / club / individual organiser | `policy.merchant|club|member` = `SPLIT` (default) or `REFUSE`. Anything else, including `PLATFORM`, is read as `REFUSE`. |
| SPLIT without a finished sub-merchant, rails off, or sub_mchid equal to the platform's | refused with reason (`SELLER_NOT_BOUND`, `SPLIT_RAILS_OFF`, `SUB_MCHID_IS_PLATFORM`) |
| Club dues | refused while active (`DUES_SPLIT_NOT_BUILT`): no split path for dues yet |
| APP channel (native app) | split tickets refused with "这张票暂时只能在城瘾微信小程序里购买" (no partner APP order built) |

Refusals are typed (error contract): `TICKET.SELLER_NOT_BOUND`, `TICKET.SELLER_COLLECTION_UNAVAILABLE`,
`TICKET.APP_CHANNEL_UNAVAILABLE`, `CLUB.DUES_COLLECTION_UNAVAILABLE` (HTTP 422 in v2; legacy body unchanged for the
mini-program), with `data.reason` and `data.nextStep` (`CONTACT_SELLER`, `WAIT_FOR_PLATFORM`, `USE_MINI_PROGRAM`).
The path to bind: operations register and submit the sub-merchant on the existing 电商收付通 admin page; merchants see
progress on the mini-program page 收款账户 (`pages/merchant/payout-account`). Every refusal is counted per seller
(`cy_collection_refusal`) so operations know whom to onboard first.

## Take rate (join with merchant-plans)

The platform take is never chosen here. The order freezes `cms_registration.fee_platform_rate` through the existing
`CmsRegistrationServiceImpl#resolveFeeSnapshot` (theme snapshot, else `settlement_rule`); split settlement reads that
frozen rate (`cy_collection_order.take_rate`) and computes `total × rate`, half-up to the fen. If merchant-plans changes
how the rate is computed behind the same signatures, split orders follow automatically. A missing rate sends the
share order to manual review (`SPLIT_TAKE_RATE_MISSING`), never a guessed rate.

## What changed, file by file

Earlier commit `013bfc4bc` (kept unchanged):
- `chengyinhub-admin/sql/migration_20261115_collection_model.sql` + `ci/migration-dependencies.json`: tables
  `cy_collection_order` (split ledger source) and `cy_collection_refusal`; menu 分账收款 + button, granted to no role.
- `.../business/collection/CollectionPolicy|CollectionDecision|CollectionPolicyEngine|CollectionModelService|CollectionSplitSettlementService.java`,
  `.../collection/mapper/CollectionModelMapper.java`.
- `ErrorCode.java`: the four codes above.
- `EcommerceProfitShareService.java`: `planSingleSeller` / `replanSingleSeller` (platform take is the only receiver, the
  seller's part is RETAINED on its own sub-merchant).
- `EcommerceSellerResolver.java`: `factsFor(ownerType, ownerId)`. `EcomPayoutMapper.xml`: split plans are due for submission.

This run:
- `CmsRegistrationServiceImpl.java`: `requireCollectionModel` after each existing collect gate (single order, multi-ticket
  order, `assertCollectible(registration)`) and in `preparePaymentRetry`; before any stock, entitlement or points.
- `EcommercePartnerPaymentAdapter.java`: while active, the model decides; SPLIT topic **and activity** tickets are
  placed on the seller sub-merchant and recorded in `cy_collection_order` before any WeChat call; combined payment is
  not used for new orders (it holds others' shares in a platform sub-order, see legal Q7).
- `WechatPaymentSdkGateway.java`: platform JSAPI and APP orders call `requirePlatformMayCollect` (only PLATFORM passes).
- `ClubMembershipServiceImpl.java`: dues renewal and invoice resume refuse while active, before any invoice/prepay.
- `CmsActivityServiceImpl.java`: activity wallet settlement subtracts split sales; releasing a held split sale credits nothing.
- `RefundServiceImpl.java`: no wallet claw-back for a split sale (the partner refund returns the shared take first).
- `MemberFundsStageService.java`: the balance-page estimate leaves split sales out.
- `EcommercePayoutTask.java`: `shareAndRetry` also plans due split orders (`planDue`), then the existing submit/sync.
- `application.yml`: `chengyin.collection-model.policy.*`, `split-plan-batch`.
- `CollectionAdminService.java`, `CollectionModelAdminController.java` (`/business/collection/*`), and
  `chengyinhub-ui/src/api/business/collection.js`, `chengyinhub-ui/src/views/business/collection/index.vue`:
  model state, per-seller collection status (bound or not, refusals), split ledger (paid, platform planned/received/
  returned, seller kept, refunded), failures queue (manual share orders → re-plan; failed returns → retry), refusals.
- Tests (all new): `CollectionPolicyEngineTest` (8), `CollectionModelServiceTest` (11),
  `CollectionSplitSettlementServiceTest` (10), `CollectionModelPartnerRoutingTest` (5), `CollectionModelOrderGateTest` (3).

Topic tickets: their wallet path (coop / club self-play settlement → T+N payout) already subtracts money collected by
a sub-merchant (`EcommerceSettlementGuard#partnerPortion`); split topic orders write the same `ecom_order_seller` row,
so they are covered without new code.

## Settlement and refunds

1. Pay: buyer → seller sub-merchant (`profit_sharing=true`, funds frozen).
2. After the existing T+N rule (event end + `FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS`; no open complaint; no
   active hold; no open refund) `planDue` writes one share order: platform take → platform receiver; rest RETAINED.
   Co-op themes are left to the co-op ledger planner (`COOP`); fully refunded orders close (`REFUNDED`).
3. `submitPlanned` sends it (unfreezing the rest); ledger rows show what each party received.
4. Refund: `EcommercePartnerPaymentAdapter#submitRefund` returns the shared take first (分账回退), refunds only after the
   return succeeds; a failed return appears in the failures queue.

## WeChat Pay API v3 endpoints and fields relied on

All through the existing `WechatEcommercePayGateway` (wechatpay-java SDK); none called in tests.

| Use | Endpoint | Fields |
|---|---|---|
| Split order | `POST /v3/pay/partner/transactions/jsapi` | `sp_appid`, `sp_mchid`, `sub_mchid`, `description`, `out_trade_no`, `notify_url`, `amount.total`, `amount.currency=CNY`, `payer.sp_openid`, `settle_info.profit_sharing=true` |
| Query / verify | `GET /v3/pay/partner/transactions/out-trade-no/{out_trade_no}?sp_mchid=&sub_mchid=` | `trade_state`, `transaction_id`, `sp_appid`, `trade_type`, `amount.total`, `success_time` |
| Close | `POST /v3/pay/partner/transactions/out-trade-no/{out_trade_no}/close` | `sp_mchid`, `sub_mchid` |
| Payment notify | `payout.ecommerce.notify-url` (AES-GCM resource, API v3 key) | `sp_mchid`, `sub_mchid`, `out_trade_no`, `transaction_id`, `trade_state` |
| Profit sharing | `POST /v3/ecommerce/profitsharing/orders`; `GET /v3/ecommerce/profitsharing/orders` | `appid`, `sub_mchid`, `transaction_id`, `out_order_no`, `receivers[].type/receiver_account/amount/description`, `finish` |
| Finish (no share) | `POST /v3/ecommerce/profitsharing/finish-order` | `sub_mchid`, `transaction_id`, `out_order_no`, `description` |
| Add receiver | `POST /v3/ecommerce/profitsharing/receivers/add` | `appid`, `type`, `account`, `relation_type` |
| Return before refund | `POST /v3/ecommerce/profitsharing/returnorders`; `GET .../returnorders` | `sub_mchid`, `out_order_no`, `out_return_no`, `return_mchid`, `amount` |
| Refund | `POST /v3/ecommerce/refunds/apply`; `GET /v3/ecommerce/refunds/out-refund-no/{no}?sub_mchid=` | `sub_mchid`, `sp_appid`, `transaction_id`, `out_refund_no`, `amount.refund/total/currency`, `notify_url` |
| Sub-merchant onboarding | `POST /v3/ecommerce/applyments/`; `GET /v3/ecommerce/applyments/out-request-no/{no}` | applyment body as WeChat documents; `applyment_state`, `sub_mchid`, `sign_url` |

Credentials (sp_mchid, sp_appid, API v3 key, private key path, serial number, notify URLs, sub-merchant ids) come only
from `payout.ecommerce.*` server configuration / environment variables, or from `ecom_sub_merchant` rows written by
onboarding. None committed. No hard-coded secret found in the touched files.

## Verification

RESULTS_PLACEHOLDER

## Not verified

- Nothing was run against WeChat Pay (no sandbox, by rule); endpoint paths and fields above are the SDK's and must be
  checked against the merchant console during the TEST rehearsal (MON-14).
- The migration was not run against MySQL; the annotation SQL in `CollectionModelMapper` (ledger, failures, due orders)
  was not executed against a database.
- The Vue page was not built or opened (no node_modules here).
- Personal-club activities (club-ops external payout route, `payout.ecommerce.personal-club-enabled`) keep their own
  path; if both that route and the model plan the same activity order, the first plan wins (same `(out_trade_no,
  -activityId)` key). Needs a TEST check if both are ever on together.
- The manual-case refund path (`RefundServiceImpl` manual cases) was not adapted for split sales; it is an offline
  refund flow and should not be used for split orders (operations note).

## App and mini-program

The order request is unchanged: a split order returns the same JSAPI pay parameters (`appId`, `timeStamp`, `nonceStr`,
`package`, `signType`, `paySign`), so the mini-program's payment call does not change, and refusals arrive as today's
error message. The app needs no new field; the APP channel shows the honest refusal message for split tickets.

## Owner actions and open questions

- Answer `LEGAL-QUESTIONS.md` with counsel; do the console steps there in TEST only.
- Keep `CHENGYIN_MONEY_SWITCH_SERVER_CEILING=false` in production.
- Grant `business:collection:query` / `business:collection:edit` to named operators only.
- Decide whether club dues should get a split path (Q8) and whether combined payment stays (Q7).
- Existing balances collected before the model are paid out by the money-payout package; this package only ensures no
  new third-party sale lands in a platform-held balance while the switch is open.
