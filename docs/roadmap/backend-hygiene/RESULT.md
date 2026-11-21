# R9 backend-hygiene — receipt

Branch `feature/r-backend-hygiene` in `hengyin`, based on `origin/roadmap/be-integ` (`5eb0ae1d7`). Backend only; the app repo was read for the completeness matrix and route contracts, not changed. No live server (TEST or production) was called.

## Findings

| ID | status | what closes it |
|---|---|---|
| BE-05 smoke failures | **closed for the backend; harness work handed over** | `SMOKE-TRIAGE.md` classifies all 387 matrix rows: 285 harness, 99 expected refusal, 3 defect rows (+1 cross-cutting). Code fixes: additive `errorKind` on every error envelope (business vs system, `code`/`msg` unchanged), CRM segment list no longer fails on one damaged row; broadcast `audience_count` was already fixed in R5. Remaining work is in the harness (ids, params, signed codes, reading `errorKind`) and is listed in the triage. |
| BE-06 wildcard 503 stubs | closed | `NotBuiltFeature` names all 7 families (6 not built, 1 closed by money policy) with zh/en copy; answers keep `code 503` + old `errorCode` for the app, add `notBuilt`, `feature`, `errorKind`, header `X-Chengyin-Not-Built`; `GET /api/meta/not-built` lists them. |
| BE-16 E2E providers in prod jar | closed | 8 `E2eTest*` classes moved to new module `chengyinhub-e2e`; admin takes it with test scope, only `-Pe2e-test-providers` puts it in the jar. Verified by unzipping both jars. |
| BE-13 referral bounty | closed | Activation now queues for review (status 4) instead of crediting; admin queue + approve/reject (`/business/referralbounty`, Vue page), approve requires the money switch (`chengyin.release.money-payments` && `payout`); app read-only `/api/referral/bounty/mine`. |
| BE-17 conversation list | closed | Keyset paging (`/api/im/conversations/page`), legacy array capped at 200, correlated subqueries now run per page only; three indexes incl. a MySQL 8 functional index used for the latest-message lookup (EXPLAIN checked). |
| BE-26 single instance | closed (code); two-node rollout is an owner step | Existing MySQL-lock leader already fences Quartz; the 5 `@Scheduled` methods now run on the leader only (ratchet test). Optional Quartz JDBC cluster store added, off by default. Runbook: `TWO-NODE.md`. |
| BE-27 metrics | closed (code); enabling is an owner step | Micrometer Prometheus registry; `/actuator/prometheus` only on a separate management port bound to 127.0.0.1, 404 on the public port; request latency SLO buckets; `cy_api_envelope_total{uri,code_class,kind}` for error rate. |
| BE-28 job alignment | closed | All 48 known `sys_job` targets get a unique start second (migration keyed by `invoke_target`, not job id); 24-hour second-by-second simulation test; new jobs without a slot fail the test. |
| base test failure | fixed | `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode` was a stale test (service correctly refuses with `ServiceException(409)`), not a product defect. |

## Changes, file by file

Build / packaging
- `pom.xml`: module `chengyinhub-e2e`, managed dependency.
- `chengyinhub-e2e/pom.xml` (new) + 8 moved classes (`E2eTestSmsCodeSender`, `E2eTestMerchantAvatarProvider`, `E2eTestSpeechToTextService`, `E2eTestImMediaStore`, `E2eTestContentSecurityService`, `E2eTestWeChatMiniSessionService`, `E2eTestWxCodeGateway`, `E2eTestWxCodeStorage`), packages unchanged.
- `chengyinhub-admin/pom.xml`: `chengyinhub-e2e` at `${chengyin.e2e.providers.scope}` (default `test`), profile `e2e-test-providers` (runtime); `micrometer-registry-prometheus` (Boot BOM version).

Error envelope (BE-05)
- `GlobalExceptionHandler`: `errorKind` = `BUSINESS` for `ServiceException`, `SYSTEM` for unexpected exceptions.
- `web/metrics/ApiEnvelopeMetricsAdvice` (new): same key for controllers that `return AjaxResult.error(...)`; counts `cy_api_envelope_total`.
- `MerchantCrmSegmentServiceImpl.list`: skip + log a damaged row.

Not-built stubs (BE-06)
- `appgaps/NotBuiltFeature` (new), `appgaps/ApiAppGapsWorkshopController` (named families, listing route).

Referral bounty (BE-13)
- `MerchantReferralBounty` (statuses 4/5, review fields), `MerchantReferralBountyMapper(.java/.xml)` (queue, by-inviter, three CAS updates), `MerchantReferralBountyService` (review gate, `approve`, `reject`, `queue`, `mine`, `moneyOpen`).
- `controller/business/ReferralBountyAdminController` (new), `controller/api/ApiReferralBountyController` (new).
- `chengyinhub-ui/src/api/business/referralbounty.js`, `chengyinhub-ui/src/views/business/referralbounty/index.vue` (new; existing Element components only).
- `sql/migration_20261115_referral_bounty_review.sql`.

IM (BE-17)
- `ImConversationMemberMapper(.java/.xml)`: `selectMyConversationSummaryPage`, legacy method now a capped default method; `ImServiceImpl.listConversationPage`; `IImService`; `ApiImController` `POST /api/im/conversations/page`.
- `sql/migration_20261115_im_conversation_page.sql`.

Scheduling (BE-26, BE-28)
- `common/core/scheduling/SchedulingLeadership` (new), `MySqlSchedulerCoordinator` implements it; leader check in `ExternalGameReviewTimeoutJob`, `ChannelWebhookInboundService`, `ChannelWebhookDispatcher`, `UserInvoiceRefundReconciliationJob`, `SelfPlayXpayService`.
- `quartz/config/QuartzClusterConfig` (new) replaces the commented-out `ScheduleConfig` (deleted).
- `sql/migration_20261115_stagger_all_sys_jobs.sql`; generator `docs/roadmap/backend-hygiene/gen_stagger_plan.py`.

Metrics (BE-27)
- `web/metrics/MetricsEndpointGuard` (new), `application.yml` `management.*`.

Tests (new): `GlobalExceptionHandlerErrorKindTest`, `ApiAppGapsWorkshopControllerTest` (rewritten), `MerchantReferralBountyReviewTest`, `ImConversationPageTest`, `SysJobStaggerPlanTest`, `TwoNodeSchedulingTest`, `MetricsEndpointGuardTest`, `MetricsEndpointGuardWebTest` (real Tomcat, two ports), `MerchantCrmSegmentListResilienceTest`; `E2eTestProvidersProfileTest` gains a production-module scan. Adjusted: `ChapterFlowWriteServiceTest`, `MerchantReferralBountyTest`, `MerchantReferralBountyDeadlockRetryTest`, `MerchantReferralBountyDeadlockRetryMySqlIT`, `OWN_C04_21Test` (opt into direct pay), `ImClubConversationBatchMySqlIT` (200 cap + new keyset paging case).

Docs: `docs/roadmap/backend-hygiene/SMOKE-TRIAGE.md`, `TWO-NODE.md`, this file.

## Verification

All Maven runs with `LANG=C.UTF-8 LC_ALL=C.UTF-8`. A local MySQL 8.0.46 (`apt install mysql-server-8.0`, disposable, loopback only) was used for the MySQL ITs and the migrations.

- Full suite, after the main changes: `mvn -B -o test -fae -DargLine='-Xmx1536m -Dspring.test.context.cache.maxSize=4'` with `CHENGYIN_MYBATIS_IT_*` pointing at the local MySQL → **Tests run: 19865, Failures: 1, Errors: 0, Skipped: 709**. The one failure (`OWN_C04_21Test`, direct-pay path now opt-in) was fixed and re-run green. The R5 base run had 2 failures + 34 errors (MySQL unreachable); with local MySQL those ITs pass, and the base `ChapterFlowWriteServiceTest` failure is fixed.
- **Final run** (all code commits in, same command and local MySQL): **Tests run: 19867, Failures: 0, Errors: 0, Skipped: 709 — BUILD SUCCESS.**
- Targeted: `-Dtest='MerchantReferralBounty*Test,ImConversationPageTest,ImConversationBatchReadTest,ImServiceImplTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,MetricsEndpointGuard*Test,ApiAppGapsWorkshopControllerTest,GlobalExceptionHandler*Test,E2eTestProvidersProfileTest,ChapterFlowWriteServiceTest,MerchantCrm*Test,OWN_C04_21Test'` → all green.
- MySQL ITs: `ImConversationBatchReadMySqlIT` 4/4, `ImClubConversationBatchMySqlIT` 6/6 (incl. new 257-row keyset walk across equal and NULL `last_msg_at`, ≤ 2 statements per page), `MerchantReferralBountyDeadlockRetryMySqlIT` 2/2.
- Migrations on a schema from `sql/base_schema_20260818.sql`: `migration_20261115_im_conversation_page.sql` and `migration_20261115_referral_bounty_review.sql` applied twice each (second run skips, asserts return 1). `migration_20261115_stagger_all_sys_jobs.sql` moved seeded default rows and preserved/listed a hand-edited row. `EXPLAIN` of the latest-message lookup: `Index lookup on im_message using idx_conv_visible_order (reverse)`, no filesort.
- Jars: `mvn -o -DskipTests package` → `BOOT-INF/lib` has no `chengyinhub-e2e`; with `-Pe2e-test-providers` it has `chengyinhub-e2e-3.9.0.jar`; `micrometer-registry-prometheus-1.17.1.jar` present.
- Negative control: making two plan rows share a second turns `SysJobStaggerPlanTest` red.

## Not verified

- Nothing was run against TEST 8120 or production; the smoke run was not repeated.
- The admin Vue page (`referralbounty/index.vue`) was not built or opened in a browser (no UI toolchain run).
- The full application was not booted; the management-port behaviour was verified with a minimal Spring Boot app on real Tomcat (`MetricsEndpointGuardWebTest`), using a stand-in for the app's security chain.
- Quartz JDBC cluster mode (`chengyin.quartz.cluster.enabled=true`) was not started against `QRTZ_` tables.

## Owner actions

1. **TEST server build must add `-Pe2e-test-providers`** (e.g. `mvn -Pe2e-test-providers -DskipTests package`), otherwise the TEST jar has no E2E providers and the smoke run regresses. Production builds stay as they are. (Deploy/CI files are out of my bounds.)
2. Run, in order, on TEST then production: `migration_20261115_referral_bounty_review.sql`, `migration_20261115_im_conversation_page.sql` (index builds on `im_message`: time it on TEST), `migration_20261115_stagger_all_sys_jobs.sql` (before the restart; check its "manual edits" listing).
3. Referral bounties: decide whether activated bounties keep going through review (default `chengyin.referral-bounty.auto-pay=false`) or pay directly (`true`, still only while the money switch is on). Grant `business:referralbounty:list` / `:review` to the operator role (menu rows are created, no role grants).
4. Metrics: to enable Prometheus set `CHENGYIN_MANAGEMENT_PORT=9091` and in the same change point the deploy health check (`ci/check_deploy_health.py`, `http://127.0.0.1:8080/actuator/health`) at the new port. Point the scraper at `127.0.0.1:9091/actuator/prometheus`.
5. Two nodes: follow `TWO-NODE.md` (shared upload storage, pool sizing, optional `sql/quartz.sql` + `chengyin.quartz.cluster.enabled=true`).
6. Smoke harness (`api-smoke`): apply the "Harness actions" in `SMOKE-TRIAGE.md`, starting with reading `errorKind` and treating `notBuilt` as its own bucket.

## Open questions / notes for integration

- In-flight backend branches (`feature/extgame-money`, `feature/game-types`, `feature/ingame-channels`, `feature/sys-weather`): any new `sys_job` must get a free second in a new stagger migration, and any new `@Scheduled` method must call `SchedulingLeadership.allows(...)`; `SysJobStaggerPlanTest` and `TwoNodeSchedulingTest` fail otherwise. Free seconds left: 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58.
- The coded-500 business refusals keep `code: 500` on purpose (app and mini-program depend on it). Moving them to 4xx needs a coordinated client change.
- `GET /api/merchant/authoring/jobs` presents a stored FAILED job (e.g. `UPSTREAM_UNAVAILABLE`) as HTTP 400 by its contract; left as is.
- No hard-coded secrets found in the files touched.
