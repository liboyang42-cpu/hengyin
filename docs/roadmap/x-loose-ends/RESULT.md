# x-loose-ends: backend receipt (items 3 and 4)

Branch `feature/r-x-loose-ends`, from `origin/roadmap/be-integ` (`297fdb952`).

## Issues

| Item | What | State |
|---|---|---|
| 3 | Integration tests that no workflow runs | **Fixed** for the 31 MySQL-only classes that are green on a fresh MySQL, plus the 2 W7 ITs. 16 classes stay open (listed below with reasons). |
| 4 | `ApprovedTopicReleasesTest.java` empty since `2abfd2889` | **Fixed**: 14 unit tests written from the class and the commit message. All green. |

## Changes, file by file

- `.github/workflows/backend-tests.yml`: new step **Run named MySQL integration tests**, after `Compile and run unit tests`. No job or step was renamed.
  - It runs with `if: ${{ !cancelled() }}`, so a red unit step (the known `ChapterFlowWriteServiceTest` failure) does not hide the IT results.
  - It mirrors `ci/backend-test.sh`:
    - a named `NAMED_ITS` array;
    - `mvn -B -pl chengyinhub-admin -am test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<list>`;
    - then `python3 ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports <list>`, which fails on a missing report, a skip, a failure or an error.
  - Step env:
    - the existing job service (`127.0.0.1:3306`, root/root) is reused through `CHENGYIN_TEST_MYSQL_*`;
    - `CHENGYIN_TEST_MYSQL_DATABASE_BASE=chengyin_bt` and `CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX=_<run_id>_<attempt>`;
    - these class gates are open: `CHENGYIN_MYBATIS_IT`, `CHENGYIN_FUNDS_MYSQL_IT`, `CHENGYIN_R2_MYSQL_IT`, `CHENGYIN_MERCHANT_GAP_MYSQL_IT`, `CHENGYIN_SYS_AINPC_MYSQL_IT`, `CHENGYIN_WEATHER_MYSQL_IT`;
    - GitHub sets `GITHUB_ACTIONS=true` itself. The `Channels*`, `ChainPersistence` and `FriendFoundation` ITs require it together with a `127.0.0.1` MySQL host.
  - The Test summary step reads all surefire reports, so it also counts these runs.
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/appgaps/ApprovedTopicReleasesTest.java`: was 0 bytes; now 14 Mockito tests:
  - the strict request body: size, shape, duplicate keys, missing and unknown keys, and positive integer ids;
  - the capture is pure: the same facts give the same hash, answers show only as `answerPresent`, there is no `selectedCover`, and the hash changes with the config version or the answer flag;
  - text-city block rules: no chapters, a foreign block type, a node from another chapter, non-array blocks or a missing template each give 422;
  - release chapters drop node images without touching the capture, and each chapter needs at least one node that has an answer;
  - `reviewSources` honestly returns no sources, and `sourceSelections` gives 409;
  - owner, product and task guards (403/422/404);
  - `reviewSubmit` refreshes the open task and freezes the bundle sha. Replaying the same `requestId` does not resubmit, and the same id with different content gives 409. A stale task version, changed content or another open task gives 409 before anything is submitted. A broken receipt invariant or a 403 from the submitter records no capture;
  - `prepare`/`publish` allocate revision head+1 only on an approved, unchanged snapshot. A moved head or a wrong manifest gives 409. A replay returns the same receipt, and `status` is visible only to the owner. An unapproved task, content changed after approval, a different task snapshot or no approve record each give 409.

How unrun classes were found:
- I collected every simple class name named by any workflow, or by a script or registry that a workflow runs:
  - `.github/workflows/*.yml`;
  - the non-test files under `ci/`, including `ci/backend-test.sh`, `ci/funds-mysql-it-gate.sh` and `ci/real-storage-it-registry.json`.
- I also checked whether a name appears only in a comment. `CoopServiceRealDbIT` does: `ci/backend-test.sh` mentions it in a comment but never runs it.
- 256 `*IT.java` files (255 names; `OfficialBroadcastDeliveryMySqlIT` exists in two packages). 214 are named somewhere; 41 are not.
- I also listed the env-gated `*Test` classes that nothing runs by name. They are skipped in the default `mvn test`: 7 classes.
- `ComplianceF7MyBatisTest` is not gated. It matches surefire's default includes and uses `MybatisItDatabase` with the job-level `CHENGYIN_MYBATIS_IT_*` env, so the existing `mvn -B test` step in backend-tests.yml already runs it against the job MySQL. It is green locally (2/2), and I did not add it twice.
- I took each class's needs from its gates, helpers (`MybatisItDatabase`, `MysqlIntegrationTestConfig`, `AppGapsMySql`) and env reads. Then I ran all 47 MySQL candidates against a local `mysql:8.0.45` to confirm.

## Unrun IT classes (before this change)

| Class | Needs | Result |
|---|---|---|
| MerchantReferralBountyDeadlockRetryMySqlIT (W7) | MySQL only | **Added**. Was only in `ci/backend-test.sh`, which ci.yml runs on master PRs and never on `feature/**` |
| AdvancedGameRuntimeMySqlIT (W7) | MySQL only | **Added** (same reason) |
| ComplianceF7MyBatisTest (W7) | MySQL only, ungated | Already run by the unit step of backend-tests.yml; green locally |
| AuditAppealRequestMySqlIT | MySQL only | **Added** |
| AuditItemProblemMySqlIT | MySQL only | **Added** |
| AuthorAttributeBindingMyBatisIT | MySQL only | **Added** |
| BeGapMiscMysqlIntegrationTest | MySQL, gate `CHENGYIN_R2_MYSQL_IT` | **Added** |
| CartSettlementWriteReceiptMySqlIT | MySQL, gate `CHENGYIN_FUNDS_MYSQL_IT` | **Added** |
| ChainPersistenceMySqlIT | MySQL on 127.0.0.1 + `GITHUB_ACTIONS=true` | **Added** |
| ChannelsFourthPassMySqlIT | same | **Added** |
| ChannelsGapImMySqlIT | same | **Added** |
| ChannelsPlatformMySqlIT | same | **Added** |
| ChannelsSecondPassMySqlIT | same | **Added** |
| ChannelsThirdPassMySqlIT | same | **Added** |
| ChannelsWebhookDispatchMySqlIT | same | **Added** |
| ClubProxyEnrollMysqlIntegrationTest | MySQL, gate `CHENGYIN_R2_MYSQL_IT` | **Added** |
| CompletedRouteFactMapperMySqlIT | MySQL only | **Added** |
| CoopServiceRealDbIT | MySQL only | **Added**. The `ci/backend-test.sh` comment calls it rotten, but it has since been repaired and is green |
| CreatorMaintenanceMysqlIntegrationTest | MySQL, gate `CHENGYIN_R2_MYSQL_IT` | **Added** |
| ExternalGameMonetizationMysqlIntegrationTest | MySQL, gate `CHENGYIN_R2_MYSQL_IT` | **Added** |
| FriendFoundationSchemaMySqlIT | MySQL on 127.0.0.1 + `GITHUB_ACTIONS=true` | **Added** |
| GuardWorkbenchScopeMySqlIT | MySQL only | **Added** |
| ImAdminSendIdempotencyMySqlIT | MySQL only | **Added** |
| ImClubConversationBatchMySqlIT | MySQL only | **Added** |
| ImModerationUnreadMySqlIT | MySQL only | **Added** |
| InGameChannelMySqlIT | MySQL only | **Added** |
| MerchantGapMySqlIT | MySQL, gate `CHENGYIN_MERCHANT_GAP_MYSQL_IT` (own schema `cy_merchant_gap_it`) | **Added** |
| OfficialEventAttendanceMySqlIT | MySQL, gate `CHENGYIN_FUNDS_MYSQL_IT` | **Added** |
| OrientRouteSettlementMySqlIT | MySQL only | **Added** |
| RewardsWorkshopMysqlIntegrationTest | MySQL, gate `CHENGYIN_R2_MYSQL_IT` | **Added** |
| SysAinpcMySqlIT | MySQL, gate `CHENGYIN_SYS_AINPC_MYSQL_IT` | **Added** |
| SysRandomMySqlIT | MySQL only | **Added** |
| UserInvoiceLifecycleMySqlIT | MySQL, `CHENGYIN_FUNDS_MYSQL_IT=1` asserted | **Added** |
| WeatherEventRuleMySqlIT | MySQL on localhost, gate `CHENGYIN_WEATHER_MYSQL_IT` | **Added** |
| MapReadLoadMySqlIT | MySQL **and Redis** (`CHENGYIN_REDIS_IT_PORT`) | Left out: needs Redis |
| AppOriginalEnvelopeMySqlIT | gate `APPGAPS_MYSQL_IT`; a **prepared TEST-copy database `cy_gaps_b`** (copied from `cy_e2e_beint`) with the full app schema; it only runs its migrations on top | Left out: an empty MySQL fails with `Unknown database 'cy_gaps_b'`, and creating it empty would still miss `cms_topic`, `audit_task`, ... |
| AppOriginalRequestLedgerMySqlIT | same `cy_gaps_b` copy | Left out (same) |
| ApprovedTopicReleasesMySqlIT | same | Left out (same) |
| ClubOriginalsMySqlIT | same | Left out (same) |
| ClubSeriesOriginalResultsMySqlIT | same | Left out (same) |
| CouponCommandReceiptsMySqlIT | same | Left out (same) |
| MapPublicLayersMySqlIT | same | Left out (same) |
| SupportTicketReadsMySqlIT | same | Left out (same) |
| UsageConsentRecordsMySqlIT | same | Left out (same) |
| ExternalGameReviewMysqlIntegrationTest | MySQL, gate `CHENGYIN_R2_MYSQL_IT` | Left out: **red** on MySQL 8.0.45 in UTC, 2 of 7 failing. `submitIsPendingServerSignedAndIdempotent` expected 10:00+08 but got 18:00+08 (time zone). `catalogListsOnlyTheApprovedVersionAndDownloadsAreSignedAndExpire` expected an `AppGapException` that was not thrown. Needs a test or code fix first |
| SysCityGameMysqlIntegrationTest | MySQL, gate `CHENGYIN_CITY_GAME_MYSQL_IT` | Left out: **red**, `Unknown column 'tier' in 'field list'` on `city_game_point` (schema drift between the test and the migrations on be-integ) |
| ContentSearchCityMyBatisIT | MySQL only | Left out: **red**, `Illegal mix of collations (utf8mb4_bin,NONE), (utf8mb4_0900_ai_ci,COERCIBLE)` on `LIKE` with the server's default collation |
| TeamEventCommandMySqlIT | MySQL, gate `CHENGYIN_FUNDS_MYSQL_IT` | Left out: **red** 3 of 3 runs, 1 of 10. `concurrentDuplicatesAndCancellationWaitForSameReceiptLock` gets a `Deadlock found when trying to get lock` |
| OrientScoringFlowMySqlIT | MySQL, `CHENGYIN_TEST_MYSQL_DATABASE_BASE` required | Left out: **red** 2 of 2 runs, 1 of 11. `concurrentSameRequestReplaysOneSnapshotAndWatermarkRemainsConsistent` throws `OrientScoringException: 该条流水已被冲正` |
| MapBoxReadParityMySqlIT | MySQL only | Left out: **red**. Loading `sql/base_schema_20260818.sql` fails at `qrtz_blob_triggers` with `Failed to open the referenced table 'qrtz_triggers'`. Likely cause: the dump's `/*!40014 ... FOREIGN_KEY_CHECKS=0 */` is a comment that `ScriptUtils` drops |

Not counted as unrun:
- Classes named only in `ci/real-storage-it-registry.json` are run by the manual `real-storage-it.yml`.
- Classes in `ci/backend-test.sh` are run by `ci.yml`, but only on PRs to master and selected bases, when the full backend scope is hit.

## Commands run and results

All Maven runs used `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

| Command | Result |
|---|---|
| `docker info` | Client present, daemon not running. I started `dockerd` locally and the daemon came up (29.9.2) |
| `docker pull mysql:8.0.45` | First try: 429 Too Many Requests. Retry: pulled |
| `docker run -d --name cy-it-mysql -e MYSQL_ROOT_PASSWORD=root -p 127.0.0.1:3306:3306 mysql:8.0.45` | Handshake reads `8.0.45` |
| `mvn -B -pl chengyinhub-admin -am test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<47 candidates>` with all gates open, `GITHUB_ACTIONS=true` and the job MySQL env | 177 tests: 2 failures, 15 errors. The failing classes are the 16 left-out rows above, minus `MapReadLoadMySqlIT`, which was not run |
| Same command for `TeamEventCommandMySqlIT,OrientScoringFlowMySqlIT`, with `CHENGYIN_TEST_MYSQL_DATABASE_BASE` set, run twice | Both red both times (1 error each) |
| The new workflow step, extracted with PyYAML and run as `bash` with the job env plus the step env (`run_id=777`) | `BUILD SUCCESS`, `Tests run: 152, Failures: 0, Errors: 0, Skipped: 0`, then `✅ 真库 IT 观测通过:33 套均真跑、skipped=0、无错无失败。` |
| `mvn -B -pl chengyinhub-admin -am test ... -Dtest=ComplianceF7MyBatisTest` with only the job-level `CHENGYIN_MYBATIS_IT_*` env | 2/2 green |
| `python3 -c 'import yaml;yaml.safe_load(open(".github/workflows/backend-tests.yml"))'` | Parses. Steps: checkout, setup-java, `Compile and run unit tests`, `Run named MySQL integration tests`, `Test summary`, upload-artifact |
| `mvn -B -q -pl chengyinhub-admin -am test -Dtest=ApprovedTopicReleasesTest,PlayerMessagePunctuationTest -Dsurefire.failIfNoSpecifiedTests=false` | Exit 0. `ApprovedTopicReleasesTest` 14/14, `PlayerMessagePunctuationTest` 2/2 |

## Not verified

- GitHub Actions cannot run here. I ran the new step locally in the same shape, but these are untested on a GitHub-hosted runner:
  - the step on the real runner and its `services: mysql` container;
  - whether the job still fits `timeout-minutes: 30`. Locally the IT step adds about 2 to 3 minutes on top of the unit run.
- I did not run the full `mvn -B test` unit suite of backend-tests.yml. Only the named classes were run.
- The 16 left-out classes stay unrun in CI.

## Owner actions

- Decide how the 9 `appgaps` `*MySqlIT` classes should run. Either:
  - provide a CI way to build the `cy_gaps_b` schema (base schema plus migrations) inside a workflow; or
  - keep them as manual runs against the VPS TEST copy.
- Fix or retire the 6 MySQL-only classes that are red on a fresh MySQL (`ExternalGameReviewMysqlIntegrationTest`, `SysCityGameMysqlIntegrationTest`, `ContentSearchCityMyBatisIT`, `TeamEventCommandMySqlIT`, `OrientScoringFlowMySqlIT`, `MapBoxReadParityMySqlIT`), then add them to the `NAMED_ITS` list.
- `MapReadLoadMySqlIT` needs a Redis service. It could join `real-storage-it.yml` (which already starts Redis) through `ci/real-storage-it-registry.json`.
- The `CoopServiceRealDbIT` comment in `ci/backend-test.sh` ("已烂透") is stale. The class is now green.

## App side

See the app receipt on cloud/r-x-loose-ends for SOC-15 and GAME-12 (no backend change).
