# P6 measurement — backend receipt (hengyin)

Branch `feature/r-measurement`, based on `fix/r5-be-infra-stubs` (32b425c33).
App side: chengyin-app `cloud/r-measurement` (see its `docs/roadmap/measurement/RESULT.md`).

## Findings

| ID | Status | Notes |
|---|---|---|
| GRO-03 (P0) nothing measured before sign-in | **Closed on the backend** | Anonymous, rate-limited intake with a strict schema, an account-link route, and a pre-sign-in funnel in the admin console. |
| PERF-06 / IOS-05 (P1) no MetricKit / crash data | **Closed on the backend** | `POST /api/telemetry/metrickit` stores MetricKit metric and diagnostic JSON, size- and count-capped. |

## What changed

| File | Change |
|---|---|
| `chengyinhub-system/.../business/telemetry/InstallEventSchema.java` | New. Strict contract for anonymous events: 11 allow-listed events (`app_open`, `onboarding_step`, `permission_prompt`, `permission_result`, `sign_in_start`, `sign_in_success`, `sign_in_failure`, `first_walk`, `first_fog_reveal`, `first_reward`, `first_share`). Each event has its own allowed props, either closed enums or short codes `[a-z0-9_]{1,40}`. Unknown top-level, event or prop keys, nested values, free text, a bad UUID, platform or version, more than 50 events, a body over 64 KB, or a time outside −14 days/+5 min reject the **whole batch**. There is no field for coordinates, phone numbers, nicknames or account ids. |
| `chengyinhub-system/.../business/telemetry/InstallTelemetryService.java` | New. Idempotent event insert (`install_id`, `event_id` unique). Link insert happens once per (install, member) pair. MetricKit intake: the outer keys are allow-listed, `kind` is `metric` or `diagnostic`, the payload must be a JSON object of at most 256 KB, and each install id gets at most 12 payloads a day (Asia/Shanghai day). `preSignInFunnel()` returns every step in order (missing steps count as 0) plus the linked-install count. Kill switch `chengyin.telemetry.enabled` (default `true`): when off, all three routes still validate but answer `accepted: 0` and store nothing. |
| `chengyinhub-system/.../business/telemetry/mapper/InstallTelemetryMapper.java` | New annotation mapper for the three tables. |
| `chengyinhub-admin/.../controller/api/ApiInstallTelemetryController.java` | New. `POST /api/telemetry/anon/events` (anonymous, IP limit 30/min), `POST /api/telemetry/link` (signed in, member limit 10/min, body `{installId}` only), `POST /api/telemetry/metrickit` (anonymous, IP limit 6/min). Errors are fixed short messages and never echo the input. |
| `chengyinhub-framework/.../config/SecurityConfig.java` | Exactly two POST routes are opened to anonymous callers: `/api/telemetry/anon/events` and `/api/telemetry/metrickit`. `/api/telemetry/link` and every sibling route still require sign-in. |
| `chengyinhub-admin/.../controller/business/AnalyticsController.java` | New `GET /business/analytics/funnel/pre-sign-in` (permission `business:analytics:list`). The D3 window parsing was extracted into `resolveFunnelWindow`, which `/funnel` and the new route share. The `/funnel` response shape is unchanged. |
| `chengyinhub-admin/sql/migration_20261115_measurement.sql` | New tables `app_install_event`, `app_install_link`, `app_metric_payload`, all `CREATE TABLE IF NOT EXISTS`, with a rollback note at the top. |
| `chengyinhub-ui/src/api/business/analytics.js`, `views/business/analytics/index.vue` | The funnel page now shows a "登录前首会话（按安装去重）" table above the existing table (same date filter, `el-table`/`el-divider`, no restyle), plus the linked-install count and a note when collection is switched off on the server. |
| Tests | `InstallTelemetryServiceTest` (9 tests), `AnalyticsControllerPreSignInTest` (2 tests), and a new case in `PublicReadEndpointsSecurityChainTest` that checks the two anonymous POSTs are open while GETs, `/link`, `/anon` and `/anon/events/extra` return 401. |

No existing route or field was renamed or removed.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                     → OK
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='InstallTelemetryServiceTest,PublicReadEndpointsSecurityChainTest,AnalyticsController*Test,UpgradeAuthorizationBoundaryTest,SecurityConfigHeaderContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
  → InstallTelemetryServiceTest 9/9, PublicReadEndpointsSecurityChainTest 13/13,
    AnalyticsControllerTest 17/17, AnalyticsControllerPreSignInTest 2/2,
    AnalyticsControllerPostGameOutcomeTest 8/8, UpgradeAuthorizationBoundaryTest 111/111,
    SecurityConfigHeaderContractTest 2/2
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin -am test             (full suite)
  → 12575 run, 1 failure, 34 errors, 559 skipped
```

None of the full-suite failures come from this change:
- 34 errors in 14 `*MyBatisTest` integration classes: `Communications link failure`, because there is no MySQL in this container.
- 1 failure, `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode`, also fails on a clean checkout of `fix/r5-be-infra-stubs` (checked in a separate worktree with the same command).

## Not verified

- The migration was not run against MySQL, because no database is available here.
- The admin Vue page was not built or opened in a browser.
- No request was sent to a live server.

## Owner actions and open questions

1. **Deploy and migrate** `migration_20261115_measurement.sql` on TEST, then production. The app only uploads to the production host after `FirstSessionMeasurementRelease.productionUploadApproved` is set to true in the app.
2. **Privacy text.** The privacy policy has to name the anonymous usage statistics and the crash and performance reports before the production switch is turned on.
3. **Retention.** Server-side retention for `app_install_event` and `app_metric_payload` is not decided. A purge job was not built; I suggest 180 days for events and 90 days for MetricKit payloads.
4. **Deregistration.** `app_install_link` is the only table that ties an install id to a member. Should account deletion delete its rows (`DELETE ... WHERE member_id = ?`)? The deregistration chain was not changed here.
5. **MetricKit storage.** Payloads are stored as raw JSON. There is no symbolication or crash grouping view yet; that needs a decision on tooling.

No hard-coded secrets were found in the files this change touched.
