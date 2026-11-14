# Backend App R2 Production Readiness Review (Observability & Safety)

**Review branch**: `obs/prod-readiness` (base: `origin/integration/backend-app-r2` @ `794264eac`)  
**Scope**: All modules added or modified on `backend-app-r2` (`origin/integration/backend-app-20261112..origin/integration/backend-app-r2`, 323 `src/main` files):
- `be-gap-im` (channel platform, governance, audit, inbound & outbound webhooks)
- `club` (`ApiClubController`, `ApiClubGapController`, `ClubProxyEnrollService`, `ClubWaitlistTickets`, `ClubEventOps`)
- `rewards / workshop` (`RewardPledgeService`, `ApiRewardPledgeController`, `ApiNonCashRewardController`, `WorkshopProfessionalService`, `ApiWorkshopProfessionalController`)
- `external-game` (`ApiExternalGameController`, `ExternalGamePackageValidator`, `ExternalGameReviewService`, `ExternalGameSigner`, `ExternalGameReviewTimeoutJob`)
- `misc` incl. App Attest / US Apple (`ApiUsAppleAuthController`, `UsAppleAuthService`, `NativeAppAttestService`, `ApiNativeDeviceController`, `ApiNativeHomeController`, `ApiMiscGapController`, `ApiNativeBlacklistController`, `ApiAccountDeregistrationController`)
- `merchant` (`ApiMerchantGapController`, `ApiMerchantAuthoringController`, `ApiMerchantLegacyAliasController`, `ApiCityGameController`, `ApiCityGameR491Controller`, `ApiCouponController`, `HttpMerchantAvatarProvider`, `PayoutSwitches`, `WechatEcommercePayGateway`)
- `admin review queues` (`ExternalGameReviewController`, `ImWebhookDeliveryAdminController`, `MerchantOfferWithdrawalAdminController`, `WorkshopProfessionalReviewController`)

---

## Executive Summary

- **Total findings identified**: **21** across the 5 review dimensions (all **21** remediated and covered by unit tests).
  - **(1) Log Hygiene**: 5 findings fixed
  - **(2) Metrics & Actuator Health**: 4 missing dependency health indicators implemented
  - **(3) Rate Limits & Authorization**: 5 anonymous/public route groups (9 endpoints) hardened with IP rate limiters
  - **(4) Input Validation**: 2 findings fixed (checked types + size bounds)
  - **(5) SQL Safety**: 5 findings fixed (1 `${field}` MyBatis interpolation + 4 Druid `WallFilter`-incompatible `1 = 1` predicates)
- All admin review queues (`ExternalGameReviewController`, `ImWebhookDeliveryAdminController`, `MerchantOfferWithdrawalAdminController`, `WorkshopProfessionalReviewController`) enforce `@PreAuthorize("@ss.hasPermi('business:audit:list' | 'business:audit:review')")`.
- Webhook SSRF guards (`ChannelWebhookService.validateUrl`, `ChannelWebhookDispatcher.publicHost`), package size/MIME/archive guards (`ExternalGamePackageValidator`), and App Attest daily/key limits (`NativeAppAttestService`) were verified intact.

---

## Findings & Remediations by Dimension

### 1. Log Hygiene (5 Findings — Fixed)

| ID | Module / Location | Finding | Remediation |
|---|---|---|---|
| `F-LOG-1` | `ChannelWebhookDispatcher.tick()` (`:118`) | Logged full exception (`log.error(..., e)`), risking webhook URL/token/header leakage via stack/message. | Sanitized to log `e.getClass().getSimpleName()` only. |
| `F-LOG-2` | `ChannelWebhookDispatcher.dispatch()` (`:149`) | Logged `e.toString()`, risking outbound URL or secret exposure on transport/runtime failures. | Sanitized to `e.getClass().getSimpleName()`. |
| `F-LOG-3` | `ChannelWebhookInboundService` (`checkText:182`, `cleanupNonces:263`) | Logged raw `e.toString()` on moderation and nonce cleanup errors. | Sanitized both to `e.getClass().getSimpleName()`. |
| `F-LOG-4` | `ApiChannelGapController.inbound()` (`:235`) | Logged full exception (`logger.error(..., e)`) on inbound webhook `OUTCOME_UNKNOWN`. | Sanitized to `logger.error("入站 Webhook 结果待确认 type={}", e.getClass().getSimpleName())`. |
| `F-LOG-5` | `ApiCouponController.publish(CouponPublisDTO)` (`:193`) | Logged full `request` DTO via `toString()` (`接收到请求参数: {}`). | Replaced with non-sensitive metadata fields (`scope`, `couponType`, `publishCount`). |

**Verified existing controls**:
- `LogAspect` suppresses raw request/response bodies and exception strings from `sys_oper_log`.
- `UsAppleAuthService`, `NativeAppAttestService`, `ExternalGameReviewTimeoutJob`, and `HttpMerchantAvatarProvider` do not log tokens, openids, phone numbers, or addresses (error logs already use `e.getClass().getSimpleName()`).
- `ChannelWebhookAdminService.adminView()` strips full webhook `url` and exposes only `urlHost`.

---

### 2. Metrics & Actuator Health Indicators (4 Indicators Implemented)

All new probes implement `org.springframework.boot.health.contributor.HealthIndicator` under `chengyinhub-admin/src/main/java/com/chengyinhub/web/health/` (matching `WechatPayHealthIndicator`) and are exposed through `/actuator/health` (`management.endpoint.health.show-details: when_authorized`):

| ID | Component Bean (`@Component`) | Class & Support Changes | Reported Details & Failure Semantics |
|---|---|---|---|
| `F-HEALTH-1` | `webhookQueue` | `ChannelWebhookQueueHealthIndicator`, `WebhookHealthSupport`, `ChannelWebhookMapper.countDeliveriesByState()` | Reports `queueDepth` (`PENDING + INFLIGHT`), `pending`, `inflight`, `failedRetryWaiting`, `dispatchEnabled`, `cryptoReady`, `warnThreshold` (`chengyin.health.webhook.queue-warn-threshold`, default `1000`), `backlogWarning`. Reports `DOWN` (`PII_CRYPTO_KEY_MISSING`) if dispatcher is enabled without `chengyin.pii.key`, or on DB error. |
| `F-HEALTH-2` | `webhookDlq` | `ChannelWebhookDlqHealthIndicator`, `WebhookHealthSupport` | Reports `dlqSize` (`DEAD`), `unknownCount`, `warnThreshold` (`chengyin.health.webhook.dlq-warn-threshold`, default `100`), `dlqAlert`. Reports `DOWN` on DB query failure. |
| `F-HEALTH-3` | `externalGameSigningKey` | `ExternalGameSigningKeyHealthIndicator` | Reports `configured` (`ExternalGameSigner.configured()`), `required` (`chengyin.external-game.require-signing-key`, default `false`), `failClosed=true`, `algorithm=Ed25519`, `keyId`. Never exposes private key bytes. Reports `DOWN` (`SIGNING_KEY_MISSING`) when `required=true` and key is absent. |
| `F-HEALTH-4` | `payoutProvider` | `PayoutProviderHealthIndicator`, `WechatEcommercePayGateway.credentialsConfigured()` | Reports `creatorEnabled`, `creatorProvider`, `creatorProviderResolved`, `availableCreatorProviders`, `ecommerceEnabled`, `sellerRoutingEnabled`, `profitSharingEnabled`, `combinedEnabled`, `ecommerceCredentialsConfigured`, `cashPayoutOpen`. Never exposes `spMchid`, `spAppid`, `apiV3Key`, `merchantSerialNumber`, or `privateKeyPath`. Reports `DOWN` if creator payout is enabled with missing/disabled provider, or if ecommerce payout is enabled with missing credentials. |

---

### 3. Rate Limits & Authorization on New Public / Anonymous Routes (5 Findings — Fixed)

Every `SecurityConfig` `permitAll()` addition and `@Anonymous` controller route introduced on `r2` was audited:

| ID | Route(s) | Controller Method(s) | Status & Fix |
|---|---|---|---|
| `F-AUTH-1` | `POST /api/im/webhook/inbound/{id}` (`@Anonymous`) | `ApiChannelGapController.inbound` | Missing controller rate limit. Added `@RateLimiter(time = 60, count = 120, limitType = LimitType.IP)`. |
| `F-AUTH-2` | `GET /api/external-game/catalog`, `/catalog/{gameId}`, `/catalog/{gameId}/package`, `/signing-key` (`@Anonymous`) | `ApiExternalGameController` (`catalog`, `catalogDetail`, `download`, `signingKey`) | Missing rate limits. Added `@RateLimiter(time = 60, count = 120, limitType = LimitType.IP)` on catalog/detail/signing-key and `count = 60` on package downloads. |
| `F-AUTH-3` | `POST /api/us/auth/apple/challenges`, `POST /api/us/auth/apple/exchange` (`SecurityConfig` permitAll) | `ApiUsAppleAuthController` (`challenges`, `exchange`) | Missing per-IP rate limits. Added `@RateLimiter(time = 60, count = 20, limitType = LimitType.IP)` on challenges and `count = 10` on exchange. |
| `F-AUTH-4` | `GET /api/account/deregistration/result` (`SecurityConfig` permitAll) | `ApiAccountDeregistrationController.result` | Missing rate limit on `resultToken` polling. Added `@RateLimiter(time = 60, count = 30, limitType = LimitType.IP)`. |
| `F-AUTH-5` | `POST /api/merchant/poi/detail` (`SecurityConfig` permitAll alias) | `ApiMerchantLegacyAliasController.poiDetail` | Missing rate limit on legacy anonymous alias. Added `@RateLimiter(time = 60, count = 60, limitType = LimitType.IP)`. |

**Verified existing authorization**:
- All write/private endpoints in `be-gap-misc` (`ApiNativeDeviceController`, `ApiNativeHomeController`, `ApiMiscGapController`, `ApiNativeBlacklistController`), `be-gap-club` (`ApiClubGapController`), `be-gap-rewards-workshop` (`ApiRewardPledgeController`, `ApiNonCashRewardController`, `ApiWorkshopProfessionalController`), `be-gap-merchant` (`ApiMerchantGapController`, `ApiMerchantAuthoringController`, `ApiCityGameController`, `ApiCityGameR491Controller`), and `ApiAppGapsMapController` (`/api/map/public-layers`, `/api/compliance/usage-records*`) require `getAppUserId()` / member session auth.
- Admin review controllers (`ExternalGameReviewController`, `ImWebhookDeliveryAdminController`, `MerchantOfferWithdrawalAdminController`, `WorkshopProfessionalReviewController`) enforce `@PreAuthorize("@ss.hasPermi('business:audit:list')")` and `@PreAuthorize("@ss.hasPermi('business:audit:review')")`.

---

### 4. Input Validation (2 Findings — Fixed)

| ID | Location | Finding | Remediation |
|---|---|---|---|
| `F-VAL-1` | `WorkshopProfessionalReviewController` (`approve`, `reject`, `escalate`) | Unchecked `(String) body.get("note")` / `(String) body.get("reason")` cast caused HTTP 500 `ClassCastException` on non-string JSON values; `approve` note had no max-length check. | Added `optionalText(body, field, 500)` validating `String` type and `<= 500` chars, failing with `AppGapException.badRequest(field)` (HTTP 400). |
| `F-VAL-2` | `RewardPledgeService.validateSupply(Draft)` | Validated `draft.quantity <= 0` with no upper bound. | Added `MAX_PLEDGE_QUANTITY = 1_000_000`, throwing `PledgeRejection("INVALID_INPUT")` above the cap. |

**Verified existing bounds**:
- `ExternalGamePackageValidator`: `MAX_PACKAGE_BYTES = 10 MiB`, `MAX_FILE_BYTES = 5 MiB`, `MAX_FILES = 200`, `MAX_DOMAINS = 10`, `MAX_AUTHOR = 80`, `MAX_SUMMARY = 500`, `monetization == NONE`.
- `ExternalGameReviewService`: `MAX_LIMIT = 50`, `MAX_REASON = 500`, typed `text(...)` helpers.
- `ChannelWebhookService` & `ChannelWebhookInboundService`: `MAX_BODY_BYTES`, 1–2000 char single-field JSON body check, HTTPS-only SSRF check rejecting localhost/private/CGNAT/IPv6 literals, `MAX_ALLOWED_IPS = 20`.
- `NativeAppAttestService`: `MAX_DAILY_CHALLENGES = 20`, `MAX_ACTIVE_KEYS = 5`.

---

### 5. SQL Safety (5 Findings — Fixed)

| ID | Location | Finding | Remediation |
|---|---|---|---|
| `F-SQL-1` | `ChannelGovernanceMapper.xml` (`upsertMemberState`) | Used raw `${field}` MyBatis interpolation. | Replaced with `<choose>` branches for `pinned`, `hidden`, and `muted` (zero `${}` interpolation across all r2 XML mappers). |
| `F-SQL-2` | `ChannelWebhookAdminService.deliveries(...)` (`:51`) | Dynamic SQL began with `WHERE 1 = 1` (rejected by Druid `WallFilter`). | Replaced with conditional `List<String> clauses` joined with ` AND ` only when non-empty. |
| `F-SQL-3` | `MerchantOfferWithdrawalAdminService.list(...)` (`:37`) | Dynamic SQL began with `WHERE 1 = 1` (rejected by Druid `WallFilter`). | Replaced with conditional `List<String> clauses` joined with ` AND ` only when non-empty. |
| `F-SQL-4` | `CreatorMaintenanceService.feedback(...)` (`:70`) | Base query ended with `WHERE 1 = 1` (rejected by Druid `WallFilter`). | Moved `t.member_id = ? AND t.del_flag <> 2` from `JOIN` to `WHERE`. |
| `F-SQL-5` | `ScopedLeaderboardMapper.xml` (`scopeWhere`, `:114`) | `"world"` scope emitted `and 1 = 1`. | Replaced with XML comment inside `<when test='"world".equals(scope)'>`. |

---

## Verification Evidence

All checks below ran after the final code edit on branch `obs/prod-readiness` under `/data/workspace/.mvn.lock` (`mvn -B -T 1`).

1. **Multi-module Build (`package -DskipTests`)**:
   - Command: `flock -x /data/workspace/.mvn.lock mvn -B -T 1 package -DskipTests`
   - Result: `BUILD SUCCESS` in `46.875 s` (`2026-11-14T09:12:03Z`).

2. **Full Unit Suite (`chengyinhub-framework`, `chengyinhub-system`, `chengyinhub-admin`)**:
   - Command: `flock -x /data/workspace/.mvn.lock mvn -B -T 1 test -pl chengyinhub-framework,chengyinhub-system,chengyinhub-admin -am "-DargLine=-Xmx1536m -Dspring.test.context.cache.maxSize=4"`
   - Result: `Tests run: 19556, Failures: 0, Errors: 34, Skipped: 709`.
   - Scope note: All `34` errors occurred exclusively in the `14` `*MyBatisTest` classes because `CHENGYIN_MYBATIS_IT_PORT` was unset in the non-IT unit pass; all `19,522` non-MyBatis unit tests passed (`0` failures, `0` errors; `709` IT-gated/disabled tests skipped when IT env vars are unset), including `ProdReadinessSecurityAndValidationTest` (`7/7`), `ProdReadinessHealthIndicatorsTest` (`5/5`), `PublicReadEndpointsSecurityChainTest` (`12/12`), `ChannelWebhookInboundServiceTest` (`7/7`), `ChannelWebhookAdminServiceTest` (`7/7`), `ChannelGovernanceServiceTest` (`4/4`), `MerchantOfferWithdrawalAdminServiceTest` (`4/4`), and `RewardPledgeFlowTest` (`16/16`).

3. **Real-MySQL Integration Tests (`be-int-mysql` @ `127.0.0.1:3330`, cloned `cy_gaps_b_obs` / `cy_obs_*` / `*_obs`)**:
   - **29 R2 Integration Suites** (`CHENGYIN_MERCHANT_GAP_MYSQL_IT=1`, `CHENGYIN_R2_MYSQL_IT=1`, `CHENGYIN_CITY_GAME_MYSQL_IT=1`, `CHENGYIN_GAPS_A_MYSQL_IT=1`, `APPGAPS_MYSQL_IT=1`, `APPGAPS_MYSQL_DATABASE=cy_gaps_b_obs`, `CHENGYIN_TEST_MYSQL_PORT=3330`, `CHENGYIN_TEST_MYSQL_DATABASE_BASE=cy_obs`, `CHENGYIN_MYBATIS_IT_PORT=3330`, `CHENGYIN_MYBATIS_IT_DB_SUFFIX=_obs`, `GITHUB_ACTIONS=true`, `-Duser.timezone=Asia/Shanghai`): `Tests run: 117, Failures: 0, Errors: 0, Skipped: 0` across all 29 classes (`BUILD SUCCESS`, `59.252 s`).
   - **14 `*MyBatisTest` Suites** (`CHENGYIN_MYBATIS_IT_PORT=3330`, `CHENGYIN_MYBATIS_IT_DB_SUFFIX=_obs`): `Tests run: 39, Failures: 0, Errors: 0, Skipped: 0` across all 14 classes (`BUILD SUCCESS`, `46.339 s`).
   - **`MapPublicLayersMySqlIT`** (`APPGAPS_MYSQL_IT=1`, `APPGAPS_MYSQL_DATABASE=cy_gaps_b_obs`, `-Duser.timezone=Asia/Shanghai`): `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` (`BUILD SUCCESS`, `31.627 s`).

4. **Migration, Security & SQL Static Gates**:
   - `python3 ci/migration_ledger.py audit`: exit `0`.
   - `bash ci/migration-layout-check.sh`: `Ran 146 tests` (`OK`).
   - `bash ci/migration-safety-check.sh`: `Ran 57 tests` (`OK`).
   - `python3 ci/secret-file-gate.py`, `python3 ci/forbidden_hosts_gate.py`, `python3 ci/dup_create_table_gate.py`, `bash ci/ci-config-guard.sh`: all exit `0`.
   - `PYTHONPATH=ci python3 -m unittest test_role_permission_contract test_migration_ledger test_composite_index_assertion_replacements`: `Ran 92 tests` (`OK`).
   - `GATE=ci/assert_it_ran.py PYTHONPATH=ci python3 -m unittest test_assert_it_ran`: `Ran 5 tests` (`OK`).
   - Working-tree XML/Java SQL scan across `origin/integration/backend-app-20261112..HEAD`: `0` `${}` interpolations in r2 XML mappers, `0` `1 = 1` occurrences in modified files.

