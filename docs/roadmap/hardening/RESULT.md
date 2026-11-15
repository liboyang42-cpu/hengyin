# T5 hardening: result (hengyin)

Branch `feature/r-hardening`, started from `origin/roadmap/be-integ`. Current `origin/roadmap/be-integ` (wave 5) was merged in on 2026-11-15. The only conflict was the ledger `ci/migration-dependencies.json`, and both sides were kept. App half: chengyin-app `cloud/r-hardening` (receipt `docs/roadmap/hardening/RESULT.md` there).

This run picked up after an earlier run stopped on a usage limit. Its pushed commits `26e7f2c5b` (SEC-02), `cccb9f519` (SEC-03) and `8d7bf2d9b` (SEC-11) are unchanged and were re-tested after the merge. Steps 4 to 6 and this receipt were added on top. Nothing was force-pushed. No production switch value was changed. No live service was called.

## Findings

| ID | Status | What now exists |
|---|---|---|
| SEC-02 | **Closed** | The `/api/common/**` wildcard is gone. Public routes are listed one by one: `dict`, `banner`, `infomation_list`, `infomation_detail`, and `POST channel/callback/{providerKey}` (the method checks the signature). Any other route under `/api/common/`, including future ones, needs a login. `UpgradeAuthorizationBoundaryTest` checks both outcomes: listed routes are public and unlisted ones return 401. `CommonRoutesInventoryTest` fails when a `/api/common` route is added without being classified. |
| SEC-03 | **Closed** | jjwt 0.9.1 → **0.13.0** (`jjwt-api/impl/jackson`). `SessionJwt` keeps the 0.9.1 key derivation: the same lenient Base64 decoding, and keys shorter than 64 bytes are right-padded with zeros, which is the HMAC rule, so the MAC does not change. Tokens issued before the upgrade still verify, and new tokens are byte-identical. A short key logs a warning at startup. `SessionJwtCompatibilityTest` checks this against an independent HMAC reference. The code generator is out of the production jar: it is test scope by default, and developers build with `-Pcode-generator`. |
| SEC-11 | **Closed** | Sign-up points are given once per device on the app's sign-up paths: phone, Apple and WeChat app. These paths read `X-Device-Id` (the identifierForVendor, sent by the app branch). Only a SHA-256 of it is stored, in `cy_signup_device`, and `INSERT IGNORE` makes the first account win. A second account on the same device is created as normal but gets no sign-up points. A missing or malformed header follows today's rules. Switch `chengyin.signup-points.require-device-id` defaults to false. A database error never blocks registration. The mini-program `/api/login/code` and `/api/register` paths are unchanged. |
| MON-16 | **Closed** | New console page **商品与会费** (`/business/commerceConsole`): <ul><li>marketing products, including the city bundle kind;</li><li>their orders;</li><li>explore-bundle orders, with units used out of total;</li><li>club dues plans and invoices.</li></ul>It has two safe edits, each needing a reason and writing an audit row: stop a product's sale (on sale → stopped, revision +1) and deactivate a dues plan (ACTIVE → INACTIVE). Neither edit opens a sale, changes a price, refunds or touches a money switch. Reward pledges already had the R10 console, so they are not repeated here. |
| Merchant plan features | **Closed** | All three features now read `MerchantPlanService.feature(...)`. On Free (and when there is no plan or the plan cannot be read) each one behaves exactly as today. Tests prove this. See below. |
| Pass refund reversal | **Closed** | New console page **通行证撤回退款复核** (`/business/passReview`). It lists App Store pass orders in REVIEW, each with every App Store notification received for its transaction. An operator can **re-grant** or **close**; both need a note and write an audit row. |

## Merchant plan features: what Free gets

| Feature | Where it is enforced | Free / no plan / read error | With the feature |
|---|---|---|---|
| `weeklyProofDetail` | `MerchantWeeklyProofService.weekly` (`/api/merchant/proof/weekly`) | The response is identical to today's. A test compares the whole map. | Adds a `daily` block: per day (Monday first), verified visits, redemptions, plays at the node and players. No player identities. The summary is unchanged. |
| `extraNodeGames` | Already enforced by the shop-games package (`ShopGameProfileService.allowanceOf`). Not repeated here. | 1 game | 1 + extra |
| `dealsOffersPerMonth` | New `MerchantDealQuotaService`, with `POST /api/merchant/deals/quota` and `POST /api/merchant/deals/submit {couponId}` | Quota 0: a shop cannot put a coupon into the deals layer, which is today's behaviour. Submit answers 403 `PLAN_FEATURE_NOT_INCLUDED` and writes nothing. | Up to the quota per calendar month (Asia/Shanghai), own merchant coupons only, one submission per coupon. A submission is stored **paused** (`enabled=0`, `create_by=merchant:<id>`), so an operator still turns it on in 城市集市 and the layer stays curated. The operator path is unchanged and has no quota. |

## Pass refund reversal: what re-grant and close do

- **Re-grant** depends on the order's state:
  - Refunded order: back to paid, and the revoked segment reopens for its frozen number of days, starting now or at the end of the member's last active segment. Nothing is backdated and no extra days are given.
  - The refund arrived before the grant (order pending or cancelled): the normal paid-and-activate step runs once.
  - The order is still refunding: the request is refused (409).

  Re-grant never charges and never calls a payment provider.
- **Close** keeps the refund and moves the App Store row to REVOKED.
- Both happen in one transaction with an audit row (`PASS_REVERSAL_REGRANT` / `PASS_REVERSAL_CLOSE`) in `business_audit_event`, plus RuoYi `@Log`. An order that has already been resolved returns 409.
- Pass-claimed free tickets that the refund stopped stay stopped. The member can claim again.

## Changes, file by file

### Earlier run (unchanged)

- `SecurityConfig.java`: the explicit public routes.
- `CommonRoutesInventoryTest`; `UpgradeAuthorizationBoundaryTest` (cases added); `chengyinhub-xcx/tests/unit/get-auth-matrix-contract.test.js`.
- `pom.xml`, `chengyinhub-common/pom.xml`: jjwt 0.13.
- `SessionJwt.java` (new).
- `TokenService`, `AppTokenService`, `AppleIdTokenVerifier`, `AppleIdentityReconfirmationService`, `AppleTokenRevocationService`, `UsAppleAuthService`: moved to the 0.13 API. Apple `aud` is now a set that must be exactly `{clientId}`.
- Tests: `SessionJwtCompatibilityTest`, plus test adjustments.
- `chengyinhub-admin/pom.xml`: the generator scope and the `code-generator` profile.
- `SignupDevicePoints.java` (new), `ApiLoginController.java` (the three app paths), `SignupDevicePointsTest`.
- `migration_20261115_hardening.sql`: `cy_signup_device` and a ledger entry.

### This run

- **Merge** of `origin/roadmap/be-integ`. Ledger conflict resolved by keeping both sides.
- **Pass review**
  - `platformpass/PlatformPassReversalReviewService.java` (new).
  - `PlatformPassService.reinstateAfterAppleReversal` (package-private, in the caller's transaction).
  - `PlatformPassMapper.java/.xml`: `reinstateRefundedOrder`, `reopenMembership`.
  - `PlatformPassAppleMapper.java`: `selectByState`, `selectNotificationsByTransaction`, `selectByOrderNoForUpdate`, `resolveReview`. These read no `appAccountToken`.
  - `web/controller/business/PlatformPassReviewAdminController.java` (new), permissions `business:passReview:list|resolve`.
- **MON-16**
  - `commerceconsole/mapper/CommerceConsoleMapper.java` (new; annotation SQL that never reads snapshots, quote signatures, payment facts or benefits text).
  - `commerceconsole/CommerceConsoleService.java` (new).
  - `web/controller/business/CommerceConsoleController.java` (new), permissions `business:commerceConsole:list|edit`.
- **Plan features**
  - `MerchantWeeklyProof.daily(...)`.
  - `MerchantWeeklyProofService`: optional `MerchantPlanService` and `detailIncluded`.
  - `marketplace/MerchantDealQuotaService.java` (new).
  - `web/controller/api/ApiMerchantDealController.java` (new).
- **Migration** `migration_20261115_hardening.sql`: two pages and two buttons under 资金与售后 (`funds-aftercare`). Each is guarded by `NOT EXISTS(perms)`, the migration fails closed when the directory is missing or not unique, and it ends with a read-back assertion. **No role is granted.** The rollback note was updated; there is no new audit table.
- **Admin UI**, existing Cy components only, no new styles (tokens only):
  - `src/api/business/{passReview,commerceConsole}.js`;
  - `src/views/business/{passreview,commerceconsole}/index.vue`.
- **Tests (new)**
  - `PlatformPassReversalReviewTest` (8 tests);
  - `WeeklyProofPlanDetailTest` (3);
  - `MerchantDealQuotaTest` (5);
  - `CommerceConsoleServiceTest` (4);
  - `T5MapperSqlTest`: every new statement passes the production Druid wall.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                                    # rc=0
mvn -B -q -pl chengyinhub-admin -am test -Dtest='PlatformPassReversalReviewTest,WeeklyProofPlanDetailTest,
  MerchantDealQuotaTest,CommerceConsoleServiceTest,MerchantWeeklyProofTest,MerchantPlansTest,PlatformPassAppleTest,
  PlatformPassServiceTest,ShopGame*Test'                                                       # 140 run, 0 fail
mvn ... -Dtest='CommonRoutesInventoryTest,UpgradeAuthorizationBoundaryTest,SessionJwtCompatibilityTest,
  StoredSessionCompatibilityTest,SignupDevicePointsTest,AppleTokenRevocationServiceTest'       # 149 run, 0 fail (after merge)
mvn ... -Dtest='*Authorization*Test,*Inventory*Test,*Route*Test,*Security*Test,*MapperXml*Test,*Druid*Test,*Wall*Test'
                                                                                               # 107 classes, 0 fail
mvn ... -Dtest=T5MapperSqlTest                                                                 # pass
mvn -B -q -DskipTests package -pl chengyinhub-admin -am; unzip -l chengyinhub-admin.jar        # no chengyinhub-generator; jjwt-*-0.13.0
bash ci/migration-safety-check.sh                                                              # 迁移安全门禁:通过
node ci/api-existence-gate.js                                                                  # rc=0, A=0 C=0
python3 ci/role_permission_contract.py                                                         # all ✓ (779 endpoints covered)
python3 -m pytest -q ci/test_role_permission_contract.py ci/test_migration_gates.py ci/test_migration_ledger.py   # 227 passed
cd chengyinhub-ui && node --test tests/unit/*.test.js     # 25 failures, the identical set with and without the two new pages (pre-existing)
node --test tests/unit/admin-sfc-template-compile-contract.test.js tests/unit/admin-plain-language-contract.test.js  # pass
```

## Not verified

- The migration was not run against MySQL. It follows the R10 menu pattern, and the ledger and safety gate pass.
- The console pages were not opened in a browser; only the SFC compile contract was run.
- No end-to-end run with real App Store notifications.

## Owner actions and open questions

1. **Grant the new permissions** to the operator roles that should have them: `business:commerceConsole:list|edit` and `business:passReview:list|resolve`.
2. **Deals quota semantics.** Free = 0 means "no self-submission", which is today's behaviour. Paid quotas (Basic 2, Pro 8, still placeholders from merchant-plans) let a shop submit coupons that an operator then switches on. If paid submissions should go live without an operator, that is a later switch; it is not built.
3. **Re-grant window.** A re-granted pass runs its full frozen days from now (or from the end of the last active segment). It does not restore the original dates, because the shift on refund may already have moved later segments. Confirm this policy.
4. **SEC-11 strict mode.** `chengyin.signup-points.require-device-id=true` would deny sign-up points to app sign-ups without a device id. It is left off until the app build that sends the header is live.
5. **SEC-03 key length.** If the configured session secret is shorter than 64 bytes, startup logs a warning. Rotating to a longer secret would invalidate existing sessions, so it is an owner decision.
6. The dues-plan deactivation is a platform lever over a club owner's own plan. Club owners are not notified automatically.
