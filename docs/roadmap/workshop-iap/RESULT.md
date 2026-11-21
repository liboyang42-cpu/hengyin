# R6c workshop-iap — backend receipt (hengyin)

Branch `feature/r-workshop-iap`, from `origin/feature/r-money-pass` (the pass agent's branch on top of `roadmap/be-integ`).
Merged `origin/feature/r-money-pass` again at cad00371c (its receipt); its `PlatformPassAppleTest` (18) passes alongside these tests. App half: chengyin-app `cloud/r-workshop-iap` (one StoreKit fix).

Nothing here opens a switch. With the shipped defaults every `/api/mod-commerce/**` route still answers today's named
refusal. No Apple, WeChat or payment server is called by code or tests.

## Findings

| ID | State | Notes |
|---|---|---|
| FLOW-10 / MON-05, template half (P1) | **Closed in TEST** | StoreKit's `/api/mod-commerce/*` calls are served end to end when `chengyin.mod-commerce.enabled=true` and the money switch's `pass` area is open. Closed, they get the same `MOD_COMMERCE` refusal as before. The pass half belongs to money-pass. |
| (not in scope) install reservations, release record | Open | `POST install-reservations`, `licenses/{id}/install-reservations/{rid}/release` and `GET licenses/{id}/release-record` are not mapped, so they keep the named refusal. The app calls them only behind separate approval references that are still nil. |

## Money switch area: `pass`

The owner decision (2026-11-15) put "in-app purchase for the pass and templates" on one rail. The money switch's `pass`
area is the App Store in-app purchase rail, so template sales open and close with it. No new area means no app change to
`MoneyArea` and one switch for the owner to reason about. Gate (`ModCommerceService.open()`): `chengyin.mod-commerce.enabled`
(default false) AND `pass` area open (a read failure counts as closed). Refunds and revocations from Apple are applied
even while it is closed: taking back a refunded license must not wait for "money on".

**Mismatch to know about in TEST:** the app enables the StoreKit launcher only when its `moneyPayments` label is open, and
that label reads the **`tickets`** area (`App/WorkshopCommerceComposition.swift`, pinned by
`Tests/ContractChecks/test_workshop_paid_lifecycle_closure.py`). In TEST, open both `pass` (server) and `tickets` (app).
Moving the app's workshop composition to `.pass` is a one-line change to a contract-pinned line. I left it for the
coordinator and did not edit the contract.

## Design

- **Offers** (`mod_commerce_offer`): an operator maps an **approved** template release (`workshop_version`) to one App
  Store non-consumable product id, a market (CN → CNY, US → USD) and a price in minor units, from the admin console.
  New offers start off sale. An offer can only be switched on or off after that; a new price needs a new App Store
  product, so it gets a new offer. The app sees enabled offers of approved releases in the server's market.
- **Orders are server-owned.** `POST orders` freezes the offer view (JSON) and issues the `appAccountToken` (UUID).
  It also freezes the bundle id and environment from the pass agent's App Store configuration and the product id from
  the offer. Payment and license live in **`workshop_order`** (provider `APPLE_IAP`, the existing table with Apple
  columns). Creator earnings, the existing license reads and the install flow therefore keep reading one table. The
  app's StoreKit progress (`CREATED → NATIVE_READY → SUBMITTING`, `CANCELLED`) and the frozen offer live in
  `mod_commerce_order`.
- **Once per buyer and release:** a new request for a release the buyer already holds or is buying returns the earlier
  order. The app adopts it and cancels its own request. A request id that never reached the server can be cancelled.
  The server then records it as a cancelled order, so that id can never buy later.
- **Single-use dispatch:** `native-dispatch` moves `NATIVE_READY → SUBMITTING` once, by conditional update. Every other
  call answers `dispatchAllowed:false`.
- **Verification** reuses `PlatformPassAppleVerifier` as is. It checks the signature chain to the operator's Apple
  roots, the bundle id and the environment, through Apple's SDK. `ModCommerceService` then checks the product type
  (non-consumable, purchased, quantity 1), the product id, the `appAccountToken`, the price (milliunits = minor × 10)
  and the currency against the order. It re-checks bundle and environment against the order too. One original
  transaction id binds one order: it is unique in `workshop_order`, and the service checks it before granting.
- **Grant exactly once**: a conditional update (`state in (PENDING, CANCELLED)`, no transaction bound yet). In the same
  transaction it calls `CreatorEarningsService.sale(...)`. A replay finds the order paid and returns it; the ledger's
  unique `SALE:<order>` key also makes a second row impossible.
- **Restore** (`native-transactions/restore`): the signed transaction locates the original order by the server-issued
  token, or else by the bound original transaction id. The order must belong to the caller (403 otherwise). A pending
  order with a valid transaction is granted, which recovers a crash between purchase and submission.
- **Refund / revocation**: App Store Server Notifications V2 `REFUND` and `REVOKE` for a product listed in
  `mod_commerce_offer` set the order `REFUNDED` and the license `REVOKED`. They also append `SALE_REVERSAL`. If the
  refund arrives before the grant, the sale is recorded first so the ledger nets to zero, and the bound transaction
  blocks a later grant. Each notification UUID is recorded once (`mod_commerce_apple_notification`), and a replay
  answers `APPLIED`. An unmatched refund answers non-2xx so Apple retries. `ONE_TIME_CHARGE` grants only while the
  switch is open; otherwise it is `IGNORED`, and the app submits the transaction later.
- **One notification URL:** `ApiAppleNotificationController` now tries the pass handler, then
  `ModCommerceService.notificationIfTemplate` (dispatch by product id), then the legacy workshop handler. The change is
  one injected provider plus three lines; the old two-argument constructor is kept for the pass agent's tests.
- **Reversal status** (`mod-buyer-reversal-status-v1`) reports one `REFUND / SUCCEEDED` item for a refunded order.

### Overlap to know about (not changed)

- `/api/workshop/v2/apple-iap/*` (`WorkshopAppleIapService`, `AppleSignedData`, `workshop.apple-iap.*`) and the
  `workshop_apple_product` table are an older, separately configured workshop Apple flow. They still exist and still get
  the notifications that neither the pass nor the template handler claims. Coordinator: decide whether to retire that
  flow once this one is proven in TEST.
- The pass agent's branch also has a synthetic chain helper (`ApplePassTestChain`). I wrote mine
  (`SyntheticAppleChain`) before theirs landed. They are equivalent, and one can go during integration.

## Files

| File | Change |
|---|---|
| `chengyinhub-admin/sql/migration_20261115_workshop_iap.sql` | New: `mod_commerce_offer`, `mod_commerce_order`, `mod_commerce_apple_notification`, menu 「工坊内购商品」 under 资金与售后 (`business:modcommerce:query` / `:edit`, granted to no role). Idempotent, rollback note at top. |
| `ci/migration-dependencies.json` | `20261115_workshop_iap` after `z_workshop_purchase_20261109`, `z_workshop_paid_review_20261109`, `20261115_money_switch`, `20261115_creator_earnings`. |
| `chengyinhub-system/.../modcommerce/ModCommerceService.java` | New: catalogue, orders, dispatch, verification, grant, restore, cancel, licenses, reversal status, notifications, admin. |
| `chengyinhub-system/.../modcommerce/ModCommerceProperties.java` | New: `chengyin.mod-commerce.enabled` (false), `market` (CN), `refund-policy-reference`. |
| `chengyinhub-system/.../modcommerce/mapper/ModCommerceMapper.java` | New annotation mapper (selects bypass the session cache like the other workshop mappers). |
| `chengyinhub-system/.../creatorearnings/CreatorEarningsService.java` | `sale(...)` and `reversal(...)` made `public` (two words). |
| `chengyinhub-admin/.../api/modcommerce/ApiModCommerceController.java` | New: the 18 app routes; 401 then named refusal while closed; refusals map to HTTP status + `errorCode`. |
| `chengyinhub-admin/.../api/ApiAppleNotificationController.java` | Dispatch to the template handler by product id (see above). |
| `chengyinhub-admin/.../api/appgaps/ApiAppGapsWorkshopController.java` | `notBuilt(...)` made `public` so the new controller answers the identical refusal. |
| `chengyinhub-admin/.../business/ModCommerceOfferAdminController.java` | New: list, create, on/off. |
| `chengyinhub-admin/src/main/resources/application.yml` | `chengyin.mod-commerce.enabled: ${CHENGYIN_MOD_COMMERCE_ENABLED:false}`, `market: ${CHENGYIN_MOD_COMMERCE_MARKET:CN}`. |
| `chengyinhub-ui/src/api/business/modcommerce.js`, `src/views/business/modcommerce/offers.vue` | New admin page (existing `cy-page-header`, `cy-panel`, Element components). |
| Tests | `business/platformpass/SyntheticAppleChain.java` (test chain), `business/modcommerce/ModCommerceServiceTest.java` (12), `web/controller/api/modcommerce/ApiModCommerceControllerTest.java` (5). |

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests install                    # OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='ModCommerceServiceTest,ApiModCommerceControllerTest,ApiAppGapsWorkshopControllerTest,CreatorEarnings*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
# ModCommerceServiceTest 12/12, ApiModCommerceControllerTest 5/5, ApiAppGapsWorkshopControllerTest 3/3,
# CreatorEarningsAccrualMyBatisTest 3/3, CreatorEarningsPolicyTest 6/6
bash ci/migration-safety-check.sh                                             # 迁移安全门禁:通过
```

Full admin suite (`mvn -B -pl chengyinhub-admin -am test`): **11 817 tests, 0 failures, 34 errors, 529 skipped**.
All 34 errors are in 14 `business.it.*MyBatisTest` classes that need a live MySQL ("Communications link failure"; no
database in this environment). None touches workshop, Apple, pass or mod-commerce code.

What the tests prove. They run the real mapper SQL on H2 (MySQL mode), the pass agent's real verifier (Apple's SDK)
trusting only a synthetic root, and the real creator-earnings service writing the ledger:

- **Offers:** exact app field set; only enabled offers of approved releases; withdrawn releases disappear.
- **Admin:** refuses duplicate products, bad prices, bad markets and unapproved releases.
- **Orders:** the same request gives the same order; a second request returns the earlier order; buying your own
  release is refused; changed terms are refused; another buyer's order is invisible.
- **Payment and dispatch:** payment issues the exact StoreKit parameters (`SANDBOX`, bundle, product, token, `CHN`);
  dispatch is allowed once.
- **Grant:** a verified transaction pays once and writes one SALE (gross 600, creator 420, `HELD_MONEY_OFF`). A replay
  is idempotent, and the same original transaction on another order is refused.
- **Verification failures:** wrong bundle, wrong environment, wrong product, wrong token, wrong price, an untrusted
  chain and an empty JWS are each refused, and none of them grants.
- **Restore:** the owner gets the order back and a pending order is granted; another member gets 403; an unknown token
  gets 404.
- **Refunds:** a refund notification revokes once and reverses the sale even with the switch closed; a replayed
  delivery is a no-op; a refund before the grant blocks the grant and keeps the ledger balanced.
- **Notification routing:** other products fall through to the next handler. A charge is `IGNORED` while the switch is
  closed and `APPLIED` once it opens. An unmatched refund throws, so Apple retries.
- **Cancellation:** before dispatch the order is cancelled; after dispatch only the request is recorded; an unseen
  request becomes a cancelled order.
- **Closed switch:** all 18 routes answer a body equal to today's catch-all, with the `MOD_COMMERCE` header, and do not
  touch the service. Signed out, they answer 401 first.
- **Notification endpoint:** dispatches pass, then template, then legacy, and returns 503 on an unapplied template
  refund.

## Not verified

- Real MySQL: the migration was not run (no database here). H2 does not prove the `CHECK` constraints, ascii collations,
  `insert … select … where not exists` under concurrency, or `for update` semantics. Run the migration through
  `chengyin-db-migrate` on TEST first.
- Real App Store data: no Sandbox transaction or notification from Apple was used, only Apple's SDK against a synthetic
  chain. Apple's real chain is the pass agent's configuration and is covered by the TEST script below.
- The Vue page was not built (no `node_modules`).
- A device purchase: see the app receipt. The app's `workshopCommerceApproval` is nil in every build (nothing sets it),
  so a device E2E needs a TEST-only approval injection that is not part of this package.

## App Store Connect products (owner action)

Create one **non-consumable** in-app purchase per template release you want to sell. App Store Connect: App → In-App
Purchases → +. For each:

| Field | Value |
|---|---|
| Type | Non-Consumable |
| Product ID | reverse-DNS under the app's bundle, e.g. `<bundle id>.template.<short-name>`; never reused |
| Price | a price tier; enter the **same** amount (in fen / cents) in the admin page. The server refuses a transaction whose price or currency differs. |
| Availability | China mainland storefront for `market=CN` (CNY); United States for `market=US` (USD) |
| Localisation | zh-Hans and en display name and description, matching the template title |
| Review | screenshot of the template detail page; "digital template for use inside the app" |

Also in App Store Connect:

1. Set the **App Store Server Notifications V2** URL to `https://<host>/api/apple-iap/notifications`, the one URL the
   pass already uses: Sandbox URL = TEST host, Production URL = production host (it stays closed there).
2. Create a Sandbox tester account for TEST.

Suggested first set for TEST: two existing approved paid releases at tiers ¥6 and ¥12 (`priceMinor` 600 and 1200, CN).

## End-to-end TEST script (integrator)

Server (TEST only; never production):

```
# 1. configuration (environment variables of the TEST deployment)
CHENGYIN_MONEY_SWITCH_SERVER_CEILING=true
CHENGYIN_PASS_APPLE_IAP_ENABLED=true            # the shared App Store verifier (pass agent's)
CHENGYIN_APPLE_BUNDLE_ID=<app bundle id>
CHENGYIN_APPLE_IAP_ENVIRONMENT=Sandbox
CHENGYIN_APPLE_ROOT_CERTIFICATE_PATHS=/etc/chengyin/apple/AppleRootCA-G3.cer   # downloaded from apple.com/certificateauthority
CHENGYIN_MOD_COMMERCE_ENABLED=true
CHENGYIN_MOD_COMMERCE_MARKET=CN
# 2. migration through the ledger runner (chengyin-db-migrate), then restart
# 3. admin console (TEST): grant business:moneyswitch:edit and business:modcommerce:* to one named tester;
#    资金开关 → open "pass" (and "tickets" for the app's StoreKit launcher), with a reason
#    工坊内购商品 → create offer (WKV_ id of an APPROVED release, CN, product id, 600) → 上架
```

API, as a signed-in TEST member (`T` = app session token, `H` = TEST host):

```
curl -s -H "Authorization: $T" https://$H/api/mod-commerce/offers                     # code 200, the offer
OFFER=MCO_…; TERMS=<offer.terms.hash>; REQ=$(uuidgen | tr A-Z a-z)
curl -s -H "Authorization: $T" -H 'Content-Type: application/json' -X POST https://$H/api/mod-commerce/orders \
     -d "{\"requestId\":\"$REQ\",\"offerId\":\"$OFFER\",\"termsHash\":\"$TERMS\"}"   # state CREATED
KEY=WKO_…
curl -s -H "Authorization: $T" -X POST https://$H/api/mod-commerce/orders/$KEY/payment       # NATIVE_READY + paymentParameters
curl -s -H "Authorization: $T" https://$H/api/mod-commerce/orders/$KEY/native-context        # same parameters
curl -s -H "Authorization: $T" -X POST https://$H/api/mod-commerce/orders/$KEY/native-dispatch  # dispatchAllowed true, then false
```

Device (TEST build with a reviewed `WorkshopCommerceApproval`, `storeKitEnvironment: "SANDBOX"`, signed in with the
Sandbox tester):

1. Buy the template. The app submits the JWS to `orders/{key}/native-transaction`. Expect `PAID`, license `ACTIVE`,
   and `GET /business/creator-earnings/ledger` showing one `SALE` row for the order (gross 600, creator share 420,
   `HELD_MONEY_OFF`).
2. Kill the app between purchase and submission, reopen: recovery or restore returns the same order, still one SALE.
3. Reinstall, then "restore purchases": `native-transactions/restore` returns the original order. With another member
   signed in, the same restore answers 403.
4. Refund: on the device call `Transaction.beginRefundRequest` (Sandbox) and approve. Apple posts `REFUND`. Expect
   `mod_commerce_apple_notification.state=APPLIED`, license `REVOKED`, one `SALE_REVERSAL`, and
   `orders/{key}/reversal-status` with `refundedBuyerMinor=600`.
5. In App Store Connect press "Request a Test Notification". The pass handler acknowledges `TEST`.
6. Close "pass" in 资金开关: every `/api/mod-commerce/*` call answers `code 503, errorCode WORKSHOP_COMMERCE_DISABLED,
   feature MOD_COMMERCE` again, and the app shows the template as not available.

## Owner question: templates with free (user-set) prices

Templates made by users can carry any price, but App Store products are fixed, pre-created, reviewed price points. They
do not map one to one. Options:

| Option | How it works | App Review | China (mainland) |
|---|---|---|---|
| **A. Curated offers only** (what is built) | Operators create one product per template they choose to sell, at a tier that matches the creator's price (or the creator picks from tiers). | Cleanest: each product is a real, described digital item. Every new product needs App Store review before sale, which is slow for a large catalogue. | No virtual currency. Each product is an ordinary digital good. Content still needs the usual review and filing for in-app content. |
| **B. Price-tier products** | A fixed set of generic non-consumables or consumables, e.g. "Template ¥6 / ¥12 / ¥30". The server decides which template the purchase unlocks (via `appAccountToken`). Creators choose a tier, not a free price. | Allowed if the purchased item is clear at purchase time. Generic or ambiguous product names are a known rejection risk (guideline 3.1.1: the item must be the one delivered). Non-consumables cannot be bought twice, so tiers would have to be **consumables** that unlock one template each. Restore then relies on our server, not on Apple. | Not virtual currency as long as each purchase unlocks one named item immediately and no balance is kept. |
| **C. Credits (coins)** | Users buy a coin pack through IAP and spend coins on any template at any price. | Allowed (consumable IAP), but coins must not expire unfairly, cannot be cashed out or transferred, and the price list must be in-app. Apple takes its commission on the pack. | Counts as **virtual currency**: it brings the rules on online-game and online-platform virtual currency (issuance records, no cash-out or transfer, refund handling, minors' limits). Expect more legal and filing work. |

Recommendation: keep **A** for launch, and let creators pick from a short list of tiers (a form of B on the authoring
side) so operators can pre-create products. Revisit **C** only with legal advice. Credits are **not built** here.

## Other owner actions

- Grant `business:modcommerce:query` and `:edit` to named people only. The migration grants them to no role.
- Confirm the creator share for template sales (creator-earnings default 70 %).
- Coordinator: point the app's workshop StoreKit gate at the `pass` area (contract-pinned line, see above), or confirm
  that TEST opens both `pass` and `tickets`.
- No hard-coded secrets were found in the files touched. The App Store key and roots stay file paths from the pass
  agent's configuration.
