# R5 error-contract: result (backend)

Branch `feature/r-error-contract`, based on `origin/roadmap/be-integ`.

## Findings

| ID | State | Notes |
|---|---|---|
| BE-14 (P1) errors are untyped | **Closed for opted-in clients** | Typed catalogue + `X-Error-Contract: 2`. Every non-success `AjaxResult` gets a real HTTP status and an `errorCode` for the app; 1,103 literal refusals are typed explicitly, the rest are classified (see below). |
| BE-05 (P1) 139 refusals coded 500 in the TEST smoke | **Closed for opted-in clients, open for the smoke itself** | With the header, refusals are 4xx and only failures are 5xx. The TEST smoke must send `X-Error-Contract: 2` to see it (owner action below). Without the header the 500 body code stays, on purpose. |

## The contract

* **Without the header (mini-program, admin console, older app builds): nothing changes.** Same HTTP status, same JSON bytes, same keys. The typed code lives in a `transient` Java field of `AjaxResult`, not in the map, so no serializer (Jackson or fastjson2) can emit it.
* **With `X-Error-Contract: 2`:** `ErrorContractAdvice` rewrites non-success `AjaxResult` bodies on a copy:
  * refusal → HTTP 4xx (`401` sign-in, `403` not allowed, `404` not found, `409` conflict, `422` refused …) plus `errorCode`;
  * genuine failure (uncaught exception, 5xx) → HTTP 5xx plus `errorCode` (`SYSTEM.*`);
  * body `code` and `msg` stay the legacy values, so a client can read the envelope as before;
  * an existing domain `errorCode` (e.g. `CHECK_REQUIRED`, `INTENT_PENDING`) is kept, only the HTTP status is added;
  * success (`200`) and warning (`601`) are untouched.
* Classification for code that is not migrated yet (`ErrorContract.decide`): explicit `ErrorCode` → its status; body code 4xx/5xx → that status; body code `500` without a code → `COMMON.UNTYPED_REFUSAL` (422) unless the wording looks like a caught failure (稍后/繁忙/失败/异常/暂不可用/超时/网络 …) → `SYSTEM.UNTYPED_FAILURE` (500). Monitoring can count the two `UNTYPED_*` codes to see what is left to migrate.
* `ServiceException` without a code → `COMMON.REFUSED` (422) unless its wording looks like a failure. `RuntimeException`/`Exception` → `SYSTEM.FAILURE` (500); unreadable body / missing parameter → `COMMON.BAD_REQUEST` (400). The authentication entry point answers `401` + `AUTH.SESSION_EXPIRED` under v2.

## Migrated refusals (1,103 in 93 controllers under `web/controller/api`)

Mechanical, rule-based rewrite of `return error("literal")` → `refuse(ErrorCode.X, "literal")` (message kept verbatim). Exact-message rules for the main journeys (sign-in, roam, play/topics, clubs, tickets/activities/coupons, merchant), and pattern rules for `缺少…`/`请填写…` (bad request), `…不存在` (not found), `无权…` and role-based `仅/只有<role>可…` (forbidden). Ambiguous wording (state refusals such as `仅行进中的场次可放行`, `该场次不是带领场`) was left untyped on purpose and is classified at runtime.

By code: `SIGN_IN_REQUIRED` 606, `BAD_REQUEST` 233, `FORBIDDEN` 62, `NOT_FOUND` 47, `CLUB_NOT_FOUND` 19, `TOPIC_NOT_FOUND` 18, `OFFICIAL_PUBLISH_FORBIDDEN` 18, `CLUB_MEMBERS_ONLY` 12, `ACTIVITY_NOT_FOUND` 11, `LOCATION_REQUIRED` 11, `USER_NOT_FOUND` 9, `LOCATION_INVALID` 9, `TEMPLATE_NOT_FOUND` 8, `ROAM_SESSION_NOT_FOUND` 6, `ROAM_ALREADY_SETTLED` 5, `ACTIVITY_FORBIDDEN` 3, `COUPON_NOT_FOUND` 3, `MERCHANT_NOT_FOUND` 3, `MERCHANT_APPLICATION_REQUIRED` 3, `MERCHANT_ONLY` 3, `TOPIC_FORBIDDEN` 3, `ACCOUNT_DISABLED` 3, `ROAM_PLACE_NOT_FOUND` 2, `ROAM_TOO_FAR` 2, `ROAM_MOVING_TOO_FAST` 2, `REGISTRATION_NOT_FOUND` 1, `ROAM_AREA_TOO_LARGE` 1.

By controller: `ApiClubController` 155, `ApiCoopController` 83, `ApiTopicController` 83, `ApiMerchantController` 71, `ApiRoamController` 52, `ApiUmsMemberController` 36, `ApiOfficialEventController` 34, `ApiActivityController` 28, `ApiClubLeadController` 26, `ApiCreativeSquareController` 25, `ApiMerchantCrmController` 24, `ApiCouponController` 23, `ApiTemplateController` 23, `ApiMerchantChapterApplicationController` 20, `ApiChapterMerchantNodeController` 19, `ApiTopicTemplateController` 18, `ApiClubEventOpsController` 17, `ApiAiController` 14, `ApiRegistrationController` 14, `ApiClubMembershipController` 13, `ApiCommentController` 12, `ApiMerchantNodeController` 12, `ApiRegistrationMerchantController` 11, `ApiCityNodeController` 10, `ApiCircleThemeController` 9, `ApiCoopPoolController` 9, `ApiMerchantOperatorController` 9, `ApiTrackController` 9, `ApiClubSettlementController` 8, `ApiOrientScoringController` 8, `ApiPlayCheckController` 8, `ApiPlayJourneyActionController` 8, `ApiPlayRunSessionController` 8, `ApiRoamHangoutController` 8, `ApiBetaGameController` 7, `ApiClubCrmController` 7, `ApiClubGovernanceController` 7, `ApiMerchantReviewController` 7, `ApiPredictSettleController` 7, `ApiAccountDeregistrationController` 6, `ApiClubRoleController` 6, `ApiClubTopicSettingController` 6, `ApiPricingController` 6, `ApiProjectController` 6, `ApiTopicStateKeyController` 6, `ApiMerchantCommerceController` 5, `ApiProductController` 5, `ApiRoamStampController` 5, `ApiWalletController` 5, `ApiWithdrawalController` 5, `ApiClubCalendarController` 4, `ApiClubEventNotificationController` 4, `ApiGrowthController` 4, `ApiLoginController` 4, `ApiMerchantAftercareController` 4, `ApiPlayJourneyController` 4, `ApiPlayJourneyReadController` 4, `ApiPlayTeamController` 4, `ApiSpoilerController` 4, `ApiAgeGateController` 3, `ApiComplianceController` 3, `ApiCrowdfundingController` 3, `ApiLocationTrustController` 3, `ApiMemberAccountController` 3, `ApiObjectCardController` 3, `ApiPublishController` 3, `ApiRoamShareController` 3, `ApiStatsController` 3, `ApiClubCompensationController` 2, `ApiClubRecruitController` 2, `ApiCreatorCenterController` 2, `ApiMemberIdentityController` 2, `ApiMerchantDeskController` 2, `ApiPlayEncounterController` 2, `ApiPointsController` 2, `ApiPresetController` 2, `ApiPushDeviceController` 2, `ApiRoleController` 2, `ApiTopicPlaySetupController` 2, `PersonalExplorationExportController` 2, `ApiAgreementController` 1, `ApiBadgeController` 1, `ApiBalanceController` 1, `ApiContentSearchController` 1, `ApiInstallTelemetryController` 1, `ApiMedalController` 1, `ApiMerchantPayoutAccountController` 1, `ApiMinorGuardianController` 1, `ApiPlayHelpController` 1, `ApiRecommendationController` 1, `ApiScopedLeaderboardController` 1, `ApiUserInvoiceController` 1, `ApiVerifyController` 1.

Not migrated: 760 other `error(...)` calls in `api/` (dynamic messages such as `error(e.getMessage())`, numeric-coded errors, and literals no rule covers). They are classified by `ErrorContract.decide` under v2 and are unchanged without the header. Admin (`business/`, `system/`) controllers were not touched.

## Files

| File | Change |
|---|---|
| `chengyinhub-common/.../exception/ErrorCode.java` | New. Error code catalogue: stable string codes by domain (`COMMON`, `AUTH`, `ROAM`, `PLAY`, `CLUB`, `TICKET`, `MERCHANT`, `SYSTEM`), message key, suggested HTTP status, default message. |
| `chengyinhub-common/.../exception/BusinessRefusal.java` | New. Typed refusal exception: code, message key, suggested HTTP status, legacy body code (default 500), optional data; no stack trace. |
| `chengyinhub-common/.../exception/ErrorContract.java` | New. Header name/value, `isV2`, the pure classifier `decide`, failure wording heuristic. |
| `chengyinhub-common/.../core/domain/AjaxResult.java` | `refuse(...)` factories, `withErrorCode(...)`, `errorCode()`/`suggestedHttpStatus()` (transient, not serialized), `ERROR_CODE_TAG`. Existing methods unchanged. |
| `chengyinhub-common/.../core/controller/BaseController.java` | `refuse(ErrorCode)` and `refuse(ErrorCode, String)`. |
| `chengyinhub-framework/.../web/exception/ErrorContractAdvice.java` | New `ResponseBodyAdvice`: only for `X-Error-Contract: 2`; copies the result, adds `errorCode`, sets the HTTP status. |
| `chengyinhub-framework/.../web/exception/GlobalExceptionHandler.java` | Handles `BusinessRefusal` (info log, no alarm); tags existing handlers with an `ErrorCode` (no body change). |
| `chengyinhub-framework/.../security/handle/AuthenticationEntryPointImpl.java` | v2 branch: 401 + `AUTH.SESSION_EXPIRED`; old branch unchanged. |
| 93 controllers in `chengyinhub-admin/.../web/controller/api/` | Literal refusals typed (list above). |
| `chengyinhub-admin/src/test/.../ErrorContractCompatibilityTest.java` | New, 23 tests. |

## Verification

* `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → success.
* `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest='ErrorContractCompatibilityTest,GlobalExceptionHandler*Test,UnknownRouteMvcContractTest,ApiTopicPublicationIssueContractTest,ClubPostRepostAuthorizationTest,MerchantNodeOptionMediaGateTest' -Dsurefire.failIfNoSpecifiedTests=false` → all pass (ErrorContractCompatibilityTest 23/23, GlobalExceptionHandlerErrorMappingTest 17/17).
* `ErrorContractCompatibilityTest` proves the mini-program shape:
  * migrated endpoints vs their legacy twins (`error("请先登录")` vs `refuse(SIGN_IN_REQUIRED)`, `error(403,…)` vs `refuse(403,FORBIDDEN,…)`, with data, thrown `BusinessRefusal` vs `ServiceException`): same HTTP 200, byte-identical body, same headers, no `errorCode`;
  * untyped/broken/success/domain-code/list endpoints: identical with no header and with header values `""`, `1`, `3`, `v2`, `2.0`;
  * fastjson2 and Jackson serialize `refuse(...)` exactly like `error(...)`; the 401 entry point is unchanged without the header;
  * the mini-program sources (`chengyinhub-xcx`, `.js`/`.ts`) never send `X-Error-Contract`;
  * v2: 401/403/404/409/422 refusals with the right `errorCode`, 500 for `SYSTEM.FAILURE`/`SYSTEM.UNTYPED_FAILURE`, success untouched, domain codes kept, shared static results not mutated; classifier table; catalogue codes unique and domain-scoped.
* Full admin suite: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin -am test -fae` → 12,675 run, 1 failure, 34 errors, 559 skipped. The 34 errors are MySQL-backed `business.it.*` tests (`Communications link failure`, no database in the cloud container). The 1 failure, `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode`, fails identically on the untouched base `origin/roadmap/be-integ` (verified in a separate worktree).

## Not verified

* No live server run: the advice was exercised through MockMvc standalone setup with the real `GlobalExceptionHandler`, not through the full Spring context, filters and security chain.
* `RuntimeFailureAlarmFilter` reports every HTTP ≥ 500. Under v2, `SYSTEM.UNTYPED_FAILURE` (a caught failure with failure wording) now reaches that alarm and metric; a refusal with failure-like wording (e.g. `举报失败`) can be over-counted until it is typed. Clients without the header are unaffected.

## Owner actions and open questions

1. Make the TEST smoke send `X-Error-Contract: 2` so BE-05 is measured by HTTP status (and count `COMMON.UNTYPED_REFUSAL` / `SYSTEM.UNTYPED_FAILURE` as the remaining work list).
2. Decide whether the admin console (`chengyinhub-ui`) should opt in later; it is untouched now.
3. Continue migrating dynamic `error(e.getMessage())` sites and service-layer `ServiceException`s to `BusinessRefusal` with a specific code.
4. No hard-coded secrets were found in the files touched.
