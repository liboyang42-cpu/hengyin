# x-paid-delivery (W7, merchant PKG-2): Deliver what was paid — backend receipt

Branch `feature/r-x-paid-delivery` from `origin/roadmap/be-integ` @ `cbbf7e3d0`. App half: chengyin-app
`cloud/r-x-paid-delivery`. Review: `review/opus-w7:docs/review/opus/merchant/ISSUES.md`.

## Issues

| ID | Status | Notes |
|---|---|---|
| MERCHANT-2 | Fixed (backend + app) | The money switch stops selling, never delivery or restore. |
| MERCHANT-9 | Fixed (backend part: new read; app wires the purchase) | Templates sell on the `pass` rail, as the 2026-11-15 owner decision recorded in `ModCommerceService` ("in-app purchase for the pass and templates"). Still closed in production: the pass area is compiled off in Release. |
| MERCHANT-10 | App only | No backend change. |

All cited lines were re-checked at `cbbf7e3d0`; none was already fixed.

## Changes

- `chengyinhub-system/.../platformpass/PlatformPassAppleService.java`
  - `requireOpen()` split into `requireSellOpen()` (`createOrder`; `offer()` keeps `unavailableReason()`) and
    `requireDeliverable()` (`confirm`, `lookup`), which needs only `verifier.unavailableReason() == null`
    (new public `undeliverableReason()`). `grant` still refuses unless the token matches an order of this member.
  - `ONE_TIME_CHARGE`: grants whenever the transaction's `appAccountToken` matches an order, whatever the switch;
    no order → `IGNORED`.
  - A `ONE_TIME_CHARGE` row stored `IGNORED` by the old rule is no longer treated as done, so Apple's redelivery is
    processed (grant is idempotent).
- `.../platformpass/mapper/PlatformPassAppleMapper.java`: `selectByToken` (non-locking read for the check above).
- `chengyinhub-system/.../modcommerce/ModCommerceService.java`
  - `deliverable()` = `chengyin.mod-commerce.enabled` only.
  - `ONE_TIME_CHARGE` applies when `deliverable()` and `locate(fact) != null` (was: only while `open()`); same
    `IGNORED`-redelivery fix.
  - `storeKit()` for the new read below.
- `.../modcommerce/mapper/ModCommerceMapper.java`: `productIds(market)` (every product id of the market, on sale or
  withdrawn, so bought templates stay restorable).
- `chengyinhub-admin/.../api/modcommerce/ApiModCommerceController.java`
  - Routes split. **Sell** (needs `open()`): `offers*`, `POST orders`, `orders/by-request/{id}[/lookup]`, `payment`,
    `native-context`, `native-dispatch`. **Deliver** (needs `deliverable()`): `storekit`, `GET orders/{key}`,
    `orders/{key}/offer`, `native-transaction`, `native-transactions/restore`, `reconcile`, `cancel`,
    `orders/by-request/cancel`, `reversal-status`, `licenses`, `licenses/{id}`.
  - Deviation from the review: `orders/by-request/cancel` is a deliver route (cancelling never moves money and must
    work after the area closes); the two by-request reads stay sell routes as the review lists.
  - New `GET /api/mod-commerce/storekit` (additive): `{available, unavailableReason, selling, market, bundleId,
    environment ("SANDBOX"/"PRODUCTION"), productIds}` from server configuration. The app builds its template
    StoreKit approval from it (MERCHANT-9).
- Tests
  - `PlatformPassAppleTest`: `closedSwitchStillRefusesOfferAndNewOrders`, `closedSwitchStillDeliversAVerifiedTransactionForAnExistingOrder`,
    `deliveryStillNeedsTheAppleChannelConfiguration`, `oneTimeChargeAfterSwitchOffGrantsTheExistingOrder`,
    `oneTimeChargeWithoutOrderIsIgnored`, `aChargeIgnoredByTheOldSwitchRuleIsProcessedWhenAppleRedelivers`
    (replace `moneySwitchClosedRefusesOfferOrderAndConfirmWithoutWriting` and `oneTimeChargeNotificationGrantsOnlyWhileTheSwitchIsOpen`).
  - `ApiModCommerceControllerTest`: `closedSwitchStillDeliversButRefusesEverySellRoute`; the feature-off test covers
    both sets; route list includes `GET /storekit`.
  - `ModCommerceServiceTest`: charge delivered with the switch closed (one `SALE` row, redelivery adds none, a charge
    with no order is `IGNORED`); `storeKitSetupComesFromServerConfigurationAndKeepsWithdrawnProductsRestorable`.

No migration, no sys_job, no `@Scheduled`, no new personal-data table, no player message text.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                      # OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='PlatformPassAppleTest,ApiModCommerceControllerTest,ModCommerceServiceTest,PlatformPassReversalReviewTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# PlatformPassAppleTest 22/22, ApiModCommerceControllerTest 6/6, ModCommerceServiceTest 13/13,
# PlatformPassReversalReviewTest 8/8 — 0 failures, 0 errors
```

## Needs a device or the owner

- TEST rehearsal (review step 6): buy a pass, close the `pass` area before the app confirms (kill the app), reopen
  → order `PAID`, one segment, transaction finished. Same for a template: buy, close, reopen → licence listed.
- Owner: confirm templates may be sold in the app once "money on" (the code follows the 2026-11-15 decision; Release
  stays closed until then).
- Not changed here: the review's MERCHANT-3 server gate inventory (package x-money-gates) may want to list the new
  deliver routes as "not money".

## Overlap with other branches

None. `ui/h-acceptance-r2` / `fix/registration-list-r3` do not touch these files.
