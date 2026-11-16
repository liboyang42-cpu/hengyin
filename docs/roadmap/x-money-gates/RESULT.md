# W7 x-money-gates: server money gates (MERCHANT-1, MERCHANT-3)

Branch `feature/r-x-money-gates` from `origin/roadmap/be-integ` @ `cbbf7e3d0`. hengyin only (backend and mini-program
view-model). The app was not changed (see "Not done").

## Issues

| ID | State | Notes |
|---|---|---|
| MERCHANT-1 (P0) | Fixed | Club dues can no longer reach the platform merchant account in any mode. New prepay needs Q5 `tickets` open AND the collection model on AND a SPLIT decision (club's own sub-merchant). Refused with `CLUB.DUES_COLLECTION_UNAVAILABLE` (422), `data.reason=MONEY_SWITCH_CLOSED`, `nextStep=WAIT_FOR_PLATFORM`, before any invoice row or WeChat call. |
| MERCHANT-3 (P1) | Fixed (with an owner decision left open) | One `MoneyGate`; misc payments, misc payouts, reward cash, creator sale money state now need legacy property AND Q5 area. S2 has its own flag. Inventory test over every WeChat collect / pay-out call site. Live pre-Q5 mini-program money paths are listed, not gated (see owner questions). |

The cited lines had moved little since the review; all re-checked before editing.

## Changes, file by file

Backend (`chengyinhub-system`)
- `moneyswitch/MoneyGate.java` (new): `isOpen(area)` over `MoneySwitchService.snapshot()`, fail closed (no service, read error, not exactly true); `requireOpen(area)` throws `COMMON.FEATURE_OFF` (403 in this codebase, not 503 as the review guessed).
- `begapmisc/MiscMoneySwitch.java`: `paymentsOpen() = money-payments AND tickets`; `payoutOpen() = money-payments AND payout AND payout area`. Covers `/api/pay/create`, `/api/asset/withdraw` and external-game orders (`ExternalGameMonetizationService` reads this switch). The old 2-arg constructor remains for hand-built tests and reads closed.
- `service/rewardpledge/CashPayoutSwitch.java`: `open() = money-payments AND payout AND payout area`. Covers reward-pledge cash, referral bounty cash and the payout health indicator.
- `creatorearnings/CreatorEarningsService.java`: money state is `PENDING_PAYOUT` only when `money-payments` AND Q5 `payout`; otherwise `HELD_MONEY_OFF`.
- `collection/CollectionModelService.java`:
  - `active()` reads `chengyin.collection-model.enabled` (default false), no longer the app money ceiling.
  - new `duesMoneyOpen()` / `requireDuesMoneyOpen(clubId)`.
  - `decideClubDues` never returns PLATFORM (model off → REFUSE / `SPLIT_RAILS_OFF`, no reads).
  - `requireClubDuesCollectible` always checks the money gate first and never returns PLATFORM.
  - `requirePlatformMayCollectDues` refuses every `CLM…` order in every mode (platform JSAPI and APP paths in `WechatPaymentSdkGateway`).
  - `clubDuesStatus` no longer reports `AVAILABLE`; off or closed → `WAIT_FOR_PLATFORM`, `collectible=false`.
- `collection/CollectionDecision.java`: `MONEY_SWITCH_CLOSED`.
- `service/impl/ClubMembershipServiceImpl.java`: `renew` calls the money gate right after `requireMember`; `resumeInvoice` next to `featureGate.requireEnabled()` (after query, close and reconcile, so existing invoices still settle); `prepay` only goes through `ClubDuesSplitService`, the `createGenericOrder` platform order for dues is gone; without the collection model bean dues are refused and status is `WAIT_FOR_PLATFORM`.

Configuration
- `chengyinhub-admin/src/main/resources/application.yml`: `chengyin.collection-model.enabled: ${CHENGYIN_COLLECTION_MODEL_ENABLED:false}` and comments.

Mini-program
- `chengyinhub-xcx/pages/club/membership/view-model.js`: only `SPLIT` is payable; `AVAILABLE` and a missing state read 「在线缴费即将开放」 (existing copy, no new strings); owner settings `ready` only for `SPLIT`.

Tests (`chengyinhub-admin/src/test`)
- New `moneyswitch/MoneyGateTest` (gate fail-closed; area closed → closed with legacy on; switching the row off closes the next request), `moneyswitch/MoneyWritePathInventoryTest` (scans system and admin main code for `createGenericOrder`, `createGenericAppOrder`, `createAppOrder`, `createDuesOrder`, `transferToBalance`, payment-service `createOrder` / `withdraw`; every site must be in a reviewed map with its gate), `moneyswitch/MoneyGateTestSupport`.
- `CollectionModelServiceTest`: `ceilingOffIsANoOpThatReadsNothing` → `modelFlagOffIsANoOpThatReadsNothing`; new `theModelFlagIsIndependentOfTheAppMoneyCeiling`, `duesAreRefusedWhileTheModelIsOffAndNoSellerIsRead`, `duesAreRefusedWhileTheTicketsAreaIsClosedEvenWithABoundClub`, `platformGatewayRefusesClmOrdersInEveryMode`, `duesStatusNeverReportsThePlatformPathAsPayable`; shipped yml asserts the new flag defaults false.
- `ClubMembershipServiceImplTest`: `modelOffKeepsTodaysPlatformOrderExactly` → `modelOffRefusesNewDuesPrepay`; new `withoutTheCollectionModelDuesAreRefusedAndNeverOnThePlatformAccount`, `aPlatformDecisionNeverReachesThePlatformAccount`. The invoice-lifecycle tests now run on a SPLIT decision; their single WeChat stub is reached through the split service.
- `ClubDuesSplitTest`: `ceilingOffChangesNothingAndReadsNothing` → `ceilingOffRefusesDuesAndReadsNoSeller`.
- `CollectionModelOrderGateTest`, `ExternalGameSecurityR3Test`, `ApiMiscGapControllerTest`, `RewardPledgeFlowTest`, `MerchantReferralBountyReviewTest`, `MerchantReferralBountyTest`, `CreatorEarningsAccrualMyBatisTest`: wired an open gate or the model flag where they test the open path.
- `chengyinhub-xcx/tests/unit/k1-club-dues-membership.test.js`: `AVAILABLE` is not payable.

Docs
- `docs/roadmap/money-switch/SERVER-GATES.md`: the money path table, which paths 资金开关 stops, rollback.

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile`: passes.
- Targeted: `mvn -pl chengyinhub-admin -am test -Dtest='MoneyGateTest,MoneyWritePathInventoryTest,ClubDuesSplitTest,ClubMembershipServiceImplTest,CollectionModelServiceTest,CollectionModelOrderGateTest,MoneySwitchServiceTest,ExternalGameSecurityR3Test,ApiMiscGapControllerTest,RewardPledgeFlowTest,MerchantReferralBountyReviewTest,MerchantReferralBountyTest,CreatorEarningsAccrualMyBatisTest,CreatorEarningsPolicyTest,ModCommerceServiceTest,…'`: 225 + 30 tests, 0 failures.
- Full `chengyinhub-admin` suite: 10,621 tests. The only failures left are 34 errors in 14 `business.it.*MyBatisTest` classes that need a MySQL server ("Communications link failure"; none in this container). This covers the ratchets `PlayerMessagePunctuationTest`, `TwoNodeSchedulingTest`, `SysJobStaggerPlanTest`, `MemberPrivacyErasureServiceTest`, `E2eTestProvidersProfileTest`.
- Mini-program: `node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`: base 17,880 pass / 30 fail, branch 17,881 pass / 30 fail (one new test, no new failure).
- `node tests/lint/ui-integrity.js --warn-as-error`: exits 1 on base and branch with the same findings (`pages/club/membership/index.js:199`, `utils/money-payout.js`), none from this change.
- No migration, no sys_job, no new table.

## Not verified / needs a device or TEST

- The TEST run from the review: ceiling false and club flag true, then `POST /api/club/membership/renew` → 422 `CLUB.DUES_COLLECTION_UNAVAILABLE`, no `club_membership_invoice` row, no WeChat log line. Unit tests cover it; no TEST server here.
- On TEST the S2 model now needs `CHENGYIN_COLLECTION_MODEL_ENABLED=true` (it used to follow the ceiling). Set it in the TEST deploy to keep rehearsing split orders and dues.

## Owner actions

1. **Production read-only check (P0, before deploying):**
   `SELECT config_value FROM sys_config WHERE config_key='feature.flag.clubMembership';` and
   `SELECT status, COUNT(*) FROM club_membership_invoice GROUP BY status;`.
   If the flag was ever on and dues were paid to the platform account, counsel must decide how to settle them.
2. Confirm production does not set `wx.pay.app-appid` (APP-channel orders would then be live).

## Open questions

- Dues on the `tickets` area (both clients assume it) or an area of their own? Kept on `tickets`.
- External-game payments on `tickets`? Kept, as the app maps them.
- When to turn S2 on for the mini-program in production (counsel), and what to tell sellers without a sub-merchant.
- Live mini-program money paths that 资金开关 does not stop: ticket registration, merchant commerce, marketing-product cash, co-op deposit, and the wallet and club-settlement withdrawals (`/api/wallet/withdraw`, `/api/club/settlement/withdraw`, WeChat balance transfer). Putting them under a Q5 area would stop production money today because the server ceiling is false. Conservative choice: listed in the inventory test and `SERVER-GATES.md`, not gated. Owner to decide.

## Not done (outside this package's repos)

- App defence in depth from the review (MERCHANT-1 step 5): `ClubDuesCopy.payKey` still maps `.available` to `clubDues.pay.miniProgram`. The server no longer sends `AVAILABLE`, and the app already maps `WAIT_FOR_PLATFORM` to `clubDues.pay.soon`, so members see 「在线缴费即将开放」. The mapping change and a contract check that `ClubDuesClient.admitted` never holds `renew` or `invoice/resume` belong to an app package. `ClubDuesClient.admitted` holds neither today.
- `chengyin-app: docs/roadmap/money-switch/RUNBOOK.md` should link `SERVER-GATES.md` and say the admin page stops only the Q5 rows.

## Overlap with the UI agent

None. No file on the UI agent's lists (stop answer, club chat, merchant dashboard, registration list) was touched. The mini-program membership view-model is not on its list.

## Related problems seen (not fixed)

- `moneypayout/PayoutMoneyGate` and `merchantplans/MerchantPlansMoneyGate` duplicate `MoneyGate`; they could be folded into it later.
- Comments in `CollectionSplitSettlementService`, `ClubDuesSplitService` and `CollectionAdminService` may still describe the model as following the money ceiling.
