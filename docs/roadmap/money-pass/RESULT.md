# R6a money-pass: result (hengyin)

Branch `feature/r-money-pass` from `origin/roadmap/be-integ`. App half: chengyin-app `cloud/r-money-pass`
(receipt `docs/roadmap/money-pass/RESULT.md` there).

Real money stays off in production. Every new path is refused unless **all** of these are on: `platform-pass.enabled`,
`chengyin.pass.apple-iap.enabled` (+ bundle id, environment, root certificates, product ids), and the money switch's
`pass` area (server ceiling AND stored row). Production defaults keep all of them off; no production value was changed.

This run resumed the earlier run's commit `79a305fa8` (it stopped on a usage limit before tests and the receipt) and
finished the package on top of it.

## Findings

| ID | Status | Backend part |
|---|---|---|
| FLOW-10 (P1) | closed (with the app branch) | The pass is sold on iOS through StoreKit 2: server-issued order + `appAccountToken`, App Store transaction verified on the server, idempotent grant. The quote / order / gift-claim routes the app now calls already existed (`ApiMarketingProductController`); first-use activation (admission) now also takes the app's JSON body. |
| MON-05 (P1) | closed (with the app branch) | The app no longer posts the pass to `/api/mod-commerce/*` (503). New routes `/api/platform-pass/apple/*` and the App Store Server Notifications V2 endpoint `/api/apple-iap/notifications`. |
| Step 4 (pass part) | closed | Every pass write is refused while the money switch is closed (tests below) and an end-to-end TEST script is at the bottom. |

Steps 2 and 3 of the brief (withdrawal, payout receiver binding) belong to `money-payout` and were not touched.

## How the App Store pass works

1. `GET /api/platform-pass/apple/offer`: available or not (with a reason code), environment, and for each plan on sale
   the App Store product id **from server configuration** (`chengyin.pass.apple-iap.product-ids.<PLAN>`).
2. `POST /api/platform-pass/apple/orders {planCode, requestId}`: freezes plan, price, days and product id in a
   `platform_pass_order` row (`channel = APPLE`) and a `platform_pass_apple_order` row with a fresh `appAccountToken`.
   Same `requestId` → same order; a different plan under the same id → `REQUEST_CONFLICT`.
3. The app buys with StoreKit 2, passing the token; Apple signs it into the transaction.
4. `POST /api/platform-pass/apple/transactions {signedTransactionInfo}`: Apple's official
   `app-store-server-library` verifies the JWS signature and the x5c chain to the **configured** Apple root
   certificates (OIDs for the WWDR intermediate and the signing leaf), the bundle id and the environment
   (`Sandbox`/`Production`; Xcode/LocalTesting are refused). The service then checks again (defence in depth):
   non-renewing subscription, purchased (not family shared), quantity 1, product id in configuration, token
   matches an order of **this** member, product / bundle / environment match the frozen order. One transaction
   binds to one order (unique `transaction_id`); one order binds to one transaction; the grant runs once
   (`markOrderPaid` only from pending/cancelled, `markGranted` only from `PENDING`). A replay returns the same order.
5. `POST /api/platform-pass/apple/transactions/lookup {transactionId}` (optional, off by default): asks the App Store
   Server API for the signed transaction (key file path, key id and issuer id from configuration) and runs the same
   verification.
6. `POST /api/apple-iap/notifications` (App Store Server Notifications V2, the single URL App Store Connect allows):
   verified with the same SDK. Pass products are handled here; anything else (or anything not verifiable as a pass,
   or with the pass channel not configured) is handed unchanged to `WorkshopAppleIapService.notification`, so the
   MOD workshop handler keeps working and the follow-up `workshop-iap` package can plug in there.
   * `REFUND` / `REVOKE`: takes back that segment through the existing refund logic (`finalizeAppleRefund` →
     `finalizeRefund`), **also while the money switch is closed**; a revoke that arrives before the grant pins the
     order so it is never granted.
   * `ONE_TIME_CHARGE`: grants only while the switch is open; otherwise acknowledged and the app's own confirm grants later.
   * `REFUND_REVERSED`: marks the order `REVIEW` for a person; it is not re-granted automatically.
   * Each notification is stored once by `notificationUUID` (inbox table); a redelivery is acknowledged without
     repeating anything; anything not applied returns 503 so Apple redelivers.
7. The WeChat refund routes refuse APPLE orders (`APPLE_REFUND_VIA_APPLE`): only Apple refunds an App Store purchase.

Refusals use the existing pass style (HTTP 200 + body `code` + `reasonCode`); with `X-Error-Contract: 2` the error
contract turns them into real HTTP statuses (503 for `MONEY_SWITCH_CLOSED`).

## What changed, file by file

Commit `79a305fa8` (earlier run, kept unchanged except the one hardening below):
- `chengyinhub-system/.../platformpass/PlatformPassAppleProperties.java` (new): all Apple settings, safe empty defaults.
- `.../platformpass/PlatformPassAppleVerifier.java` (new): SDK verifier, roots only from configured files; App Store Server API lookup.
- `.../platformpass/PlatformPassAppleService.java` (new): offer, order, confirm, lookup, notifications, money switch gate.
- `.../platformpass/mapper/PlatformPassAppleMapper.java` (new, annotation MyBatis, every write conditional).
- `.../platformpass/PlatformPassService.java`: `CHANNEL_APPLE`; WeChat refund/refund-apply refuse APPLE orders;
  `finalizeAppleRefund`; `coverageSnapshot` package-visible.
- `chengyinhub-admin/.../api/ApiPlatformPassAppleController.java` (new): `/api/platform-pass/apple/*`, member from the session only.
- `chengyinhub-admin/.../api/ApiAppleNotificationController.java` (new): `/api/apple-iap/notifications` router.
- `chengyinhub-admin/sql/migration_20261115_money_pass.sql` (new): `platform_pass_apple_order`,
  `platform_pass_apple_notification`, channel CHECK widened to `APPLE`; idempotent; rollback note at the top.
- `ci/migration-dependencies.json`: `20261115_money_pass` after the two platform-pass migrations.
- `chengyinhub-admin/src/main/resources/application.yml`: `chengyin.pass.apple-iap.*`, every value from an
  environment variable, defaults off/empty.

This run:
- `PlatformPassAppleService.java`: an empty plan row (or a missing status) reads as "not on sale" instead of failing.
- `chengyinhub-admin/.../api/ApiMarketingProductController.java`: `POST /api/marketing/product/activate` also takes
  `{topicId}` as JSON (the app's write allow-list forbids query strings); `?topicId=` still works. Bad ids → 400.
- Tests (new): `chengyinhub-admin/src/test/java/com/chengyinhub/business/platformpass/ApplePassTestChain.java`
  (in-memory root → WWDR-style intermediate → leaf chain with Apple's OIDs, ES256 JWS via auth0 java-jwt),
  `PlatformPassAppleTest.java` (18 tests), `web/controller/api/ApiMarketingProductActivateBodyTest.java` (1 test).

Dependency: `com.apple.itunes.storekit:app-store-server-library` **5.2.0**, already pinned in
`chengyinhub-system/pom.xml` by the workshop IAP work; this package adds no new dependency.

Names kept stable for `workshop-iap`: `PlatformPassAppleVerifier` (`unavailableReason`, `transaction`,
`notification`, `lookup`, `configuredEnvironment`), `PlatformPassAppleProperties`, `ApiAppleNotificationController`
(`notification`, record `Notification`), and `PlatformPassAppleService.notificationIfPass` returning
`Optional.empty()` for every non-pass product.

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → OK.
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest='PlatformPassAppleTest,ApiMarketingProductActivateBodyTest' -Dsurefire.failIfNoSpecifiedTests=false`
  → 18 + 1 passed. What they prove (all with synthetic JWS signed by the in-memory test chain, never Apple):
  * chain to the configured root accepted; a foreign chain, a leaf without Apple's OID, another bundle, the other
    environment, a tampered payload, `alg: none` and blank input refused (`verifierAcceptsOnlyTheConfiguredChainBundleAndEnvironment`);
  * missing configuration fails closed, LocalTesting refused, Production needs the app Apple id, lookup needs every
    credential (`verifierFailsClosedWithoutConfiguration`); roots read from a configured file (`verifierReadsRootsFromConfiguredFilesOnly`);
    shipped defaults off (`shippedDefaultsKeepTheApplePassOff`);
  * **money switch closed → offer unavailable, order / confirm / lookup refused with `MONEY_SWITCH_CLOSED` (503) and
    nothing written** (`moneySwitchClosedRefusesOfferOrderAndConfirmWithoutWriting`, `controllerAnswersMoneySwitchClosedAsARejection`);
    a switch read error counts as closed; pass disabled wins first;
  * order freezes the configured product and issues a UUID token on the APPLE channel;
  * grant once, replay idempotent, a second transaction on the same order refused;
  * another member's token, unknown product, consumable, missing token, already-revoked transaction never grant;
  * REFUND takes the pass back with the switch closed, once, even on redelivery; REVOKE before grant pins the order;
    ONE_TIME_CHARGE grants only with the switch open; an unmatched refund is left PENDING for Apple to redeliver (503);
  * non-pass / unverifiable notifications are handed on; the endpoint routes them to the workshop handler, 400 on an
    empty body, 503 when nothing can take them.
- Related suites: `PlatformPassServiceTest`, `WorkshopAppleIapServiceTest`, `AppleSignedDataSdkTest`, `MoneySwitchServiceTest`,
  `*MarketingProduct*`, `*Anonymous*`, `*PermitAll*` pass. `ErrorContractCompatibilityTest` (2 failures) and
  `WorkshopAppleIapMyBatisIT` (needs a database) fail identically on `origin/roadmap/be-integ`; not touched here.
- `bash ci/migration-safety-check.sh` → `迁移安全门禁:通过`.

## Not verified

- No MySQL here: the migration and the annotation SQL were not run against a database (the MyBatis IT pattern needs
  one). The SQL follows the guarded `information_schema` pattern of the earlier platform-pass migrations.
- No real Apple transaction, sandbox purchase or real notification: everything used the synthetic chain. A real
  sandbox run is the TEST script below.
- `X-Error-Contract: 2` mapping of these routes was not exercised end to end (the advice is shared code).

## Owner actions

1. **App Store Connect products** (type **Non-Renewing Subscription**, the server refuses other types), one per plan
   on sale. Seed prices from `migration_20261110_platform_pass.sql`:

   | Plan code | Suggested product id | Server price | Days | App Store price to pick |
   |---|---|---|---|---|
   | `MONTH` | `<bundle id>.pass.month` | ¥39.00 | 30 | ¥39 (CN storefront) |
   | `YEAR` | `<bundle id>.pass.year` | ¥299.00 | 365 | ¥298 or ¥299, whichever price point App Store Connect offers |

   Apple charges its own price point; the server keeps its plan price on the order and stores Apple's price
   (`price_milliunits`, `currency`) for reconciliation. Pick the ids, then set them on the server (step 3).
2. App Store Connect → App Information → **App Store Server Notifications V2**: Production and Sandbox URL
   `https://<host>/api/apple-iap/notifications` (this one URL also serves the MOD workshop's products).
3. Server environment (TEST/Staging only until "money on"):
   `PLATFORMPASS_ENABLED=true` (property `platform-pass.enabled`; Spring drops the dash in environment names), `CHENGYIN_PASS_APPLE_IAP_ENABLED=true`,
   `CHENGYIN_APPLE_BUNDLE_ID`, `CHENGYIN_APPLE_IAP_ENVIRONMENT=Sandbox`,
   `CHENGYIN_APPLE_ROOT_CERTIFICATE_PATHS=/etc/chengyin/apple/AppleRootCA-G3.cer` (downloaded from Apple PKI by an
   operator), `CHENGYIN_PASS_APPLE_PRODUCT_MONTH`, `CHENGYIN_PASS_APPLE_PRODUCT_YEAR`. Production also needs
   `CHENGYIN_APPLE_APP_APPLE_ID`. Optional lookup: `CHENGYIN_APPLE_SERVER_API_ENABLED=true`,
   `CHENGYIN_APPLE_SERVER_API_KEY_PATH` (path to the `.p8` file, never its content), `..._KEY_ID`, `..._ISSUER_ID`.
4. Refund policy text for App Store purchases (`APPLE_REFUND_VIA_APPLE`: players request refunds from Apple).

## Open questions

- `REFUND_REVERSED` goes to manual review (`REVIEW` state); there is no admin page for it yet, only the log line and
  the row. Should a reversal re-grant automatically?
- The WeChat (XPAY / JSAPI) pass routes used by the mini-program are not behind the money switch (they keep their own
  `platform-pass.*` switches, as the money-switch receipt notes). Fold them under the `pass` area?
- First-use activation (`/api/marketing/product/activate`) is not gated on the server by the money switch (it moves no
  money; the app gates it). Confirm.

## End-to-end script for TEST (integrator)

Needs: TEST server with this branch and the migration applied; the env in owner action 3; a Staging build of the app
branch (`Questify-Staging` scheme) on a device signed into a **Sandbox** Apple account; a TEST member token `$T`;
an admin token `$A` with `business:moneyswitch:edit`; `H=https://<test-host>`.

```bash
# 0. Ceiling on for TEST only, pass area closed.
#    (server env CHENGYIN_MONEY_SWITCH_SERVER_CEILING=true; never on production)
curl -s -X PUT "$H/business/money-switch" -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -d '{"area":"pass","enabled":false,"reason":"R6a e2e: closed check"}'
# 1. Closed switch refuses every pass write (expect available=false / reasonCode MONEY_SWITCH_CLOSED, code 503).
curl -s "$H/api/platform-pass/apple/offer" -H "Authorization: $T"
curl -s -X POST "$H/api/platform-pass/apple/orders" -H "Authorization: $T" -H 'Content-Type: application/json' \
  -d '{"planCode":"MONTH","requestId":"e2e_closed_0001"}'
curl -si -X POST "$H/api/platform-pass/apple/orders" -H "Authorization: $T" -H 'X-Error-Contract: 2' \
  -H 'Content-Type: application/json' -d '{"planCode":"MONTH","requestId":"e2e_closed_0001"}' | head -1   # HTTP 503
#    App (Staging build): membership page shows the read-only price and the "not available in the app" sentence; no buy button.
# 2. Open the pass area.
curl -s -X PUT "$H/business/money-switch" -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -d '{"area":"pass","enabled":true,"reason":"R6a e2e: sandbox purchase"}'
curl -s "$H/api/platform-pass/apple/offer" -H "Authorization: $T"     # available=true, environment=Sandbox, productIds
# 3. In the app: Membership → pick MONTH → Open the pass → Sandbox sheet → buy.
#    Expect: the app shows the pass active; then check the server:
curl -s "$H/api/platform-pass" -H "Authorization: $T"                 # holding.active=true, validTo ≈ +30 days
#    SQL: select state, transaction_id from platform_pass_apple_order order by create_time desc limit 1;  -- GRANTED
# 4. Replay: kill the app before it finishes (or reinstall) and reopen Membership: the unfinished transaction is sent
#    again; the order stays PAID and platform_pass_membership has ONE new segment (no double grant).
# 5. Refund: on the device, Settings → App Store → Sandbox Account → manage, or Transaction.beginRefundRequest in a
#    debug build; approve the refund in the sandbox. Within minutes Apple posts REFUND to /api/apple-iap/notifications.
#    SQL: platform_pass_apple_order.state = REVOKED; platform_pass_order.status = 3; platform_pass_apple_notification
#    row APPLIED. GET /api/platform-pass shows the segment gone.
# 6. Points order, gift and admission (marketplace products): in the app open a pass product with a points price →
#    quote shows "N points" → buy → PAID (unlock step). Buy one "for a friend" → open the gift link
#    (subpackageMember/market/gift/index?token=…) on a second TEST account → Claim → claimed. On a first-use product
#    tap "Start using it" → activated.
# 7. Close the pass area again and repeat step 1 (all refused); App Store refunds still apply while closed (step 5 can
#    be repeated with the switch closed).
curl -s -X PUT "$H/business/money-switch" -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -d '{"area":"pass","enabled":false,"reason":"R6a e2e: done"}'
```
