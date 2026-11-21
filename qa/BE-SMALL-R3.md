# Backend small fixes r3 (`fix/be-small-r3`)

Base: `origin/integration/backend-app-r3` (`8ef24ebb6`).
Context: `qa/CITY-GAPS-R3.md` and `qa/acceptance/REPORT.md` (B9 & Wave 8 on `origin/qa/acceptance-r3`).
Scope: backend code and tests only; no migrations, no deploys, no secrets.

---

## 1. City Monopoly first-claim deadlock retry (`city_deed_counter`)

**Problem.** When two concurrent first claims in a new district race on `CityMonopolyService#issueDeedSerial`, the `INSERT INTO city_deed_counter ... ON DUPLICATE KEY UPDATE last_serial = last_serial + 1` upsert can hit an InnoDB deadlock (1213) or lock-wait timeout (1205), which rolls back the surrounding transaction and surfaces as an error.

**Fix.** Reused the existing `@DeadlockRetry` / `DeadlockRetryInterceptor` boundary (which sits outside `@Transactional` at `Ordered.LOWEST_PRECEDENCE - 1`, and inside `RateLimiterAspect` at `DeadlockRetryConfig.ORDER - 1`):
- `chengyinhub-common/src/main/java/com/chengyinhub/common/annotation/DeadlockRetry.java`: added optional `int maxAttempts() default 2;` so existing 2-attempt contracts remain unchanged.
- `chengyinhub-framework/src/main/java/com/chengyinhub/framework/aspectj/DeadlockRetryInterceptor.java`: reads `maxAttempts` from `@DeadlockRetry`, retries ONLY on `PessimisticLockingFailureException` (`DeadlockLoserDataAccessException` / `CannotAcquireLockException`), skips retry when already inside an active outer transaction (`TransactionSynchronizationManager.isActualTransactionActive()`), and sleeps a 10–60 ms random jitter between attempts.
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCityMonopolyController.java`: annotated `claimCommand` with `@DeadlockRetry(maxAttempts = 3)` outside `@Transactional(rollbackFor = Exception.class)` (and inside `@RateLimiter`).
- `chengyinhub-system/src/main/java/com/chengyinhub/business/citygame/CityMonopolyService.java`: annotated `claimCommand` with `@DeadlockRetry(maxAttempts = 3)` so direct service calls without an active outer transaction also retry up to 3 attempts, while controller-routed calls pass through to the outer controller transaction boundary.

**Tests added/updated.**
- `CityMonopolyClaimDeadlockRetryTest` (5 tests):
  - `serviceClaimCommandRetriesDeadlockTwiceAndSucceedsOnThirdAttempt`: simulates 2 `DeadlockLoserDataAccessException` failures on `city_deed_counter` upsert in H2 MySQL mode and verifies success on attempt 3 with exactly 1 territory, 1 evidence row, 1 points flow, and `last_serial = 1`.
  - `serviceClaimCommandRetriesLockWaitTimeout`: simulates `CannotAcquireLockException` and verifies retry to success on attempt 2.
  - `serviceClaimCommandGivesUpAfterThreeAttemptsAndLeavesNothingCommitted`: verifies 3-attempt bound and full rollback when all 3 attempts fail.
  - `nonLockExceptionIsNotRetried`: verifies `DuplicateKeyException` is not retried (1 attempt only).
  - `controllerClaimCommandRetriesOutsideControllerTransactionAndCountsRateLimitOnce`: verifies `ApiCityMonopolyController#claimCommand` retries outside its `@Transactional` boundary (re-running location consent inside each fresh transaction) while `RateLimiterAspect` counts once.
- `DeadlockRetryInterceptorTest` (+2 tests): verifies `@DeadlockRetry(maxAttempts = 3)` retries twice and gives up after 3 attempts, while existing 2-attempt tests pass unchanged.

---

## 2. B9 business refusals returning code `500`

**Problem.** Remaining authorization, ownership, not-found, validation, and conflict ("already done") business refusals on paths listed in `qa/acceptance/REPORT.md` B9 (plus Wave 8 `merchant_registration` identity check) still returned `AjaxResult.error(...)` (`code: 500`).

**Fix.** Converted business refusals on those paths to `4xx` business codes (`400` / `403` / `404` / `409`) using `HttpStatus` constants and `AjaxResult.refusal(se)`, keeping HTTP status 200 envelope semantics, `msg`, `data`, and `500` responses for real unexpected server/DB errors unchanged:

| Endpoint | Handler | Refusal conditions & codes |
|---|---|---|
| `POST /api/verify/dyncode/issue` | `RegistrationTicketApplication#issueDynCode` | missing `registrationId` → `400`; missing order/activity → `404`; non-owner (`"无权操作该报名单"`) → `403`; unpaid/already verified/cancelled/unavailable → `409` |
| `POST /api/registration/scan_dynamic_code` (and `scanQrCode`) | `RegistrationRedemptionApplication` | non-owner / wrong shop / wrong organizer → `403` (`notRedeemed: true`); already verified (`"该订单已核销"`) / too early / unpaid / expired / superseded → `409` (`alreadyVerified` / `notRedeemed`); invalid/tampered/type-mismatch/multi-seat → `400`; missing activity → `404`; unexpected `RuntimeException` in settlement stays `500` |
| `POST /api/coupon/verification` | `ApiCouponController#couponVerification` | foreign merchant (`"无权核销该优惠券"`) / unsupported role → `403`; blank or invalid/expired token → `400`; missing coupon → `404`; `EXPIRED` / `DISABLED` / `NOT_STARTED` → `409` |
| `POST /api/activity/info` | `ApiActivityController#activityInfo` | invalid id → `400`; unknown activity (`"活动不存在"`) → `404`; cancelled own activity → `409` (retains `errorCode: ACTIVITY_CANCELLED`) |
| `POST /api/activity/publish` | `ApiActivityController#publishOnce` | player / unapproved merchant / `ServiceException(403)` via `AjaxResult.refusal` → `403`; quota full → `409`; club-binding / bad scope / content safety block → `400`; unexpected `Exception` stays `500` |
| `POST /api/login/phone` | `ApiLoginController#loginPhone` | bad phone / empty code / bad SMS code (`"验证码错误或已过期"`) → `400`; frozen or deleted account → `403`; DB registration failure stays `500` |
| `POST /api/roam/finish` | `ApiRoamController#finish` (and `presence` session guard) | missing session → `404`; already settled / CAS race (`"本次漫游已结算"`) → `409` |
| `POST /api/merchant/merchant_registration` | `ApiMerchantController#merchantRegistration` | bad ID check digit / identity validation / invalid category / text safety → `400`; missing member / missing merchant → `404`; non-owner edit → `403`; active operator / disabled or approved merchant edit → `409` (duplicate application already `409`) |

Note: `/api/sms/send` rate-limit response (`expect_code=(200, 500)` in `f01_sign_in.py`) and unauthenticated `"请先登录"` guards were left unchanged to preserve existing acceptance and unit-test contracts.

**Tests added/updated.**
- Added `B9BusinessRefusalCodeTest` (8 tests): covers every B9 endpoint above plus `MockMvc` + `GlobalExceptionHandler` HTTP 200 envelope semantics and `500` retention for unexpected server exceptions.
- Updated existing unit test assertions that previously pinned the old `500` code on these converted refusals: `ApiVerifyControllerTest`, `RegistrationTicketApplicationTest` (unchanged), `RegistrationRedemptionApplicationTest`, `RegistrationDynCodeLedgerTest`, `CounterNotRedeemedMarkerTest`, `ApiRegistrationCompletionCouponTest`, `ApiCouponControllerMerchantAccessTest`, `ActivityPublishRoleGateTest`, `ActivityPublishReviewModeTest`.

---

## 3. Verification (VPS `work`, `flock /data/workspace/.mvn.lock`, `LANG=C.UTF-8 LC_ALL=C.UTF-8`)

Command run after the final code/test edit:
```sh
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -o -pl chengyinhub-admin -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='*Activity*Test,*Coupon*Test,*Login*Test,*Roam*Test,*Registration*Test,*Verify*Test,*Merchant*Test,*City*Test,*Deadlock*Test,*Smoke*Test,!*MySql*,!*Mysql*,!*MyBatis*,!*Mybatis*,!*IT'
```
Result: **Tests run: 3298, Failures: 0, Errors: 0, Skipped: 4**, `BUILD SUCCESS` (chengyinhub-common, chengyinhub-system, chengyinhub-framework, chengyinhub-quartz, chengyinhub-generator, chengyinhub-admin).
The 4 skipped tests are pre-existing `@Disabled` / conditional skips in the matched suites. MySQL/Redis integration tests (`*MySql*`, `*MyBatis*`, `*IT`) were excluded (no MySQL in the test run; no deploys performed).
