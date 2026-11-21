# x-be-test: one backend line for TEST (backend-app-r3 + roadmap/be-integ)

Branch `feature/r-x-be-test` (hengyin). Opus 5.5, cloud, 2026-11-21. Everything here was verified in the cloud. Nobody has run it on the VPS yet.

## 1. What was merged

| | Commit |
|---|---|
| Base: `origin/integration/backend-app-r3` (TEST, Wave 9) | `8ef24ebb6` (unchanged since the brief) |
| Merged in: `origin/roadmap/be-integ` | `c4b011bdd` (unchanged since the brief) |
| Merge base | `de7d43c70` (r3 +31, be-integ +439) |
| Merge commit | `4f187fc59` |
| Verified code commit (pinned in `deploy-test.sh`) | see `SHA=` in the script; the head of the branch adds only this receipt |

Commits after the merge, each one pushed as soon as it was made:

| Commit | Why |
|---|---|
| `72244cc7c` | r3's ID-card refusal used a half-width comma; be-integ's test and the punctuation rule want `，` |
| `64a9bdcee` | test only: `CityGapsR3Test` routes the extra SQL be-integ's City code issues (no assertion changed) |
| `7b6756069` | `20261115_im_conversation_page` was missing its dependency on `20261012_im_media_visibility` (fresh-schema order failed with 1054) |
| `a63f435c0`, `125b64c72` | **startup blocker**: three shop-game services had two constructors and no `@Autowired` (`No default constructor found`) |
| `b9965759f` | **startup blocker**: `management.server.address` was fixed in application.yml while the management port defaults to the app port, and Spring Boot refuses to start with that. Replaced by `ManagementAddressDefault` (EnvironmentPostProcessor) and a test |
| `dd8fb1393` | test only: be-integ's Apple-bypass test expected 500 for a wrong SMS code; the merged code keeps r3's 400 |
| `881c96d02` … `21a3cfbc0` | the deploy script, the TEST copy of `fog_map`, the post-deploy check |

The three startup blockers and the dependency edge are on `roadmap/be-integ` as it stands. The be-integ jar alone would not start either. **They are fixed here, not on `roadmap/be-integ`. Its owner should take `7b6756069`, `a63f435c0`, `125b64c72` and `b9965759f`.** I found them by starting the packaged jar on a local MySQL 8.0 + Redis (section 4.1). Unit tests cannot catch them.

## 2. Conflicts and how they were resolved

The rule throughout: keep both sides' behavior. r3's 4xx body codes, security fixes, identity encryption and City fixes stay as r3 has them. be-integ's routes, fields, typed error codes and W7 fixes stay as be-integ has them.

| File | Conflict | Resolution |
|---|---|---|
| `ApiAccountDeregistrationController` | r3: wrong SMS code → body code 400. be-integ: Apple-only accounts confirm with Sign in with Apple, plus `ErrorCode` | be-integ's Apple branch, with r3's `AjaxResult.error(400, …)` on the SMS line; both imports |
| `ApiClubController` | r3: club missing → `error(404, …)`. be-integ: `refuse(CLUB_NOT_FOUND, …)` (body 500 + typed code) | `AjaxResult.refuse(404, ErrorCode.CLUB_NOT_FOUND, …)`: r3's body code and be-integ's typed code |
| `ApiClubLeadController`, `ApiUserInvoiceController` | imports only | both imports |
| `ApiCoopController` | review summary without `toId` | `refuse(400, BAD_REQUEST, …)` |
| `ApiMerchantController` | voice consent; business hours | r3's 400 for consent; `refuse(400, BAD_REQUEST, …)` for hours; be-integ's `refuse` for an empty body |
| `ApiOfficialEventController` | publish-check forbidden | `refuse(403, FORBIDDEN, …)` |
| `ApiTopicController` | topic missing | `refuse(404, TOPIC_NOT_FOUND, …)` |
| `AjaxResult` | r3 added `refusal(RuntimeException)`; be-integ added `refuse(...)`, `withErrorCode(...)` | both kept. `refusal(e)` now also keeps a `BusinessRefusal`'s legacy code, `ErrorCode` and v2 HTTP status (it would otherwise flatten a be-integ refusal caught in a controller) |
| `GlobalExceptionHandler` | r3: 405/415 with friendly text and body `errorCode`, validation → 400. be-integ: `BusinessRefusal` handler, typed codes | r3's bodies plus `.withErrorCode(...)`; be-integ's `BusinessRefusal` handler kept |
| `CityMonopolyService` (9 hunks) | r3: in-app notices (`INotifyService`), deed serials, district names. be-integ: points-governance holds, kept fog cells, APNs notices (`ICityEventNotifyService`), board projection on takeover, free places on the map, rent estimate | every piece kept. The map loop now carries both the district fields (r3) and `state`/`fogged`/`rentPerVisit` (be-integ), and the takeover keeps the serial (r3) and projects the new holder (be-integ). Both notice channels stay: r3's is an in-app message card, be-integ's is a phone push. **One behavior change:** r3's "rent settled" message now counts only rent that was actually paid. Rent held by points governance is paid (and announced) later, so the old text "已结算到账" would have been false |
| `InAppNotifyChannel`, `NotifyEvent`, `NotifyServiceImpl` | r3 `CITY_TERRITORY`; be-integ `GAME_DUEL`, `MERCHANT_REVIEW` | all three events and their senders |
| `MemberIdentityService` | be-integ re-checked the ID card inline | r3's `validateSubmission` already checks it; kept r3's |
| `ci/migration-dependencies.json` | both appended | union; valid JSON, lowercase ids. The 4 duplicate keys in the file already exist in both parents |

And one compile break without a textual conflict: r3's B4 (`ApiLoginController`) autowired `E2eTestWeChatMiniSessionService`, which be-integ moved into `chengyinhub-e2e` (admin may not depend on it, see `E2eTestProvidersProfileTest`). The controller now injects `IWeChatMiniSessionService` qualified by the stub's bean name. Outside the TEST profile that bean doesn't exist, so the field stays null, exactly as before. A new test pins the bean name.

## 3. Semantic duplicates

- **Routes:** a scan of every `@*Mapping` found 10 duplicate method+path pairs. All 10 exist in both parents and are split by `consumes` (JSON vs form), so they are not ambiguous. The packaged jar starts (4.1), which proves there is no ambiguous mapping.
- **Beans:** no duplicate injection points. The jar starts with the TEST profile and the e2e providers on.
- **Migrations:** r3 added one migration since the merge base (`20261126_city_gaps_r3`: `roam_territory.deed_serial`, `city_deed_counter`, `city_district` rows, `city_onsite_task_submission`). be-integ's `20261128_city_economy` also writes `city_district`, `city_game_point.district_id` and `roam_territory.district_id`, but adds no column or table r3 adds. A fresh merged schema and an r3 schema upgraded with this package's list have **identical tables, columns and indexes** (768 tables).
- One ordering effect between them: `city_economy` gives district-less MONOPOLY deeds district `d1` after r3's serial backfill has run, so those deeds would have no serial. The deploy runs the (idempotent) `city_gaps_r3` once more after `city_economy`.

## 4. Tests

Everything ran with `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -fae test -DargLine="-Xmx3g -Dspring.test.context.cache.maxSize=4"`, with `CHENGYIN_MYBATIS_IT_PASSWORD` pointing at a local MySQL 8.0.46. The `*MyBatisTest` classes need that server. Without it they fail identically on all three lines (34 errors, "Communications link failure"). With the default heap the merge's surefire fork ran out of memory after about 1,100 classes, so all three lines use the same larger heap.

TOTALS_PLACEHOLDER

Not run here: env-gated MySQL ITs (`*MySqlIT`, `CityGameMysqlIntegrationTest` and others: skipped without their `CHENGYIN_*_MYSQL_IT=1` switches, the same as the integrator's full-suite runs) and the CI-only HTTP E2E (`GenericGameplayHttpE2eIT`). The script can run the City MySQL ITs on the VPS with `RUN_MYSQL_IT=1`.

Ratchets (merge): `PlayerMessagePunctuationTest` 2/2, `SysJobStaggerPlanTest` 3/3, `TwoNodeSchedulingTest` 4/4, `MemberPrivacyErasureServiceTest` 4/4, `E2eTestProvidersProfileTest` 4/4, also `GlobalExceptionHandlerErrorMappingTest` 22/22, `ErrorContractCompatibilityTest` 23/23, `CityGapsR3Test` 15/15, `ApiLoginE2eSessionStubTest` 4/4, `ManagementAddressDefaultTest` 6/6, `MetricsEndpointGuardWebTest` 2/2. `bash ci/migration-safety-check.sh` → 迁移安全门禁:通过; `bash ci/migration-layout-check.sh` → 迁移布局门禁:通过.

### 4.1 The jar, started for real

On this container (MySQL 8.0.46 from apt, local Redis, dummy secrets):

1. `scripts/e2e/build_schema.py` built a database at r3's state (693 tables, the count TEST reported after Wave 9). Then r3's post-deploy files and two menu files that build_schema leaves out were applied with the mysql client, as TEST did.
2. The script's migration lists were run against it with the mysql client: **67 files, 0 failures, 693 → 768 tables; a second run changed nothing.**
3. The merged jar built with the script's command: `mvn -B -q -o -DskipTests -Pe2e-test-providers package -pl chengyinhub-admin -am`. Offline it was missing `maven-jar-plugin:3.4.1` from this container's cache (no package had been built here yet), which exercised the script's online-retry path. The jar is 127,093,085 bytes and contains `BOOT-INF/lib/chengyinhub-e2e-3.9.0.jar`, but not the code generator.
4. Started with `--spring.profiles.active=druid,e2e-test --chengyin.e2e.test-providers=true --server.port=8120`: **`Started RuoYiApplication in 23.0 seconds`, `GET /` → 200**. The only `Caused by` is the missing WeChat Pay `apiclient_key.pem`, the same as on TEST.
5. The r3 jar (`8ef24ebb6`, 123,502,019 bytes, the exact size of the Wave 9 jar) on the same kind of database:
   - after the **pre-deploy** migrations it starts (24.2 s, 200), so the script's automatic rollback works;
   - after the **post-deploy** files it does **not** start (`任务调用目标或参数无效`: sys_job rows name beans it lacks);
   - after deleting those 7 rows it starts again. The script prints that recipe.
6. The post-deploy check table was run against both jars (section 7).

## 5. What TEST needs

### Migrations (in the merged tree, not in r3's), in ledger order

Order from `ci/migration_ledger.py` (`_ordered_migrations` over `ci/migration-dependencies.json`, then name). **All 66 are idempotent:**

- `CREATE TABLE IF NOT EXISTS`;
- `ALTER`/`INDEX` behind `information_schema` checks and `PREPARE`;
- `INSERT IGNORE`, `INSERT … WHERE NOT EXISTS`, `ON DUPLICATE KEY UPDATE`, `sys_menu` inserts guarded by `FROM dual WHERE @existing = 0`;
- re-runnable `UPDATE`s;
- per-session `TEMPORARY` tables.

They were applied twice in the dry run. "r3 tables" means a table r3's Wave 5–9 migration also touches (`roam_territory`, `city_district`, `city_game_point`, `city_deed_counter`, `city_onsite_task_submission`).

Pre-deploy (before the jar swap):

| # | File | r3 tables |
|---|---|---|
| 1 | migration_20261115_admin_consoles.sql | |
| 2 | migration_20261115_game_loop.sql | |
| 3 | migration_20261115_guest_walk.sql | |
| 4 | migration_20261115_location_trust.sql | |
| 5 | migration_20261115_marketplace.sql | |
| 6 | migration_20261115_measurement.sql | |
| 7 | migration_20261115_merchant_reach.sql | |
| 8 | migration_20261115_money_pass.sql | |
| 9 | migration_20261115_points_governance.sql | |
| 10 | migration_20261115_sponsored_quests.sql | |
| 11 | migration_20261115_stagger_all_sys_jobs.sql | |
| 12 | migration_20261115_store_account.sql | |
| 13 | migration_20261115_store_safety.sql | |
| 14 | migration_20261115_external_games_ops.sql | |
| 15 | migration_20261116_merchant_review_sla.sql | |
| 16 | migration_20261115_im_conversation_page.sql | |
| 17 | migration_20261115_referral_bounty_review.sql | |
| 18 | migration_20261115_creator_earnings.sql | |
| 19 | migration_20261115_discovery.sql | |
| 20 | migration_20261115_daily_loop.sql | |
| 21 | migration_20261116_x_come_back_place_stamp.sql | |
| 22 | migration_20261116_journey_presence.sql | |
| 23 | migration_20261115_game_mechanics.sql | |
| 24 | migration_20261114_missing_topic_pricing_rule.sql | |
| 25 | migration_20261115_club_ops.sql | |
| 26 | migration_20261115_links_push.sql | |
| 27 | **TEST copy** `docs/roadmap/x-be-test/sql/fog_map_20261115.test.sql.txt` for migration_20261115_fog_map.sql | |
| 28 | migration_20261115_privacy_erasure.sql | |
| 29 | migration_20261115_seed_tools.sql | |
| 30 | migration_20261115_maps_backend.sql | |
| 31 | migration_20261114_roam_poi_tags_json.sql | |
| 32 | migration_20261116_template_games.sql | |
| 33 | migration_20261116_life_games.sql | |
| 34 | migration_20261115_money_switch.sql | |
| 35 | migration_20261115_collection_model.sql | |
| 36 | migration_20261115_club_dues.sql | |
| 37 | migration_20261115_merchant_plans.sql | |
| 38 | migration_20261115_hardening.sql | |
| 39 | migration_20261115_money_payout.sql | |
| 40 | migration_20261115_shop_games.sql | |
| 41 | migration_20261116_channel_events.sql | |
| 42 | migration_20261116_external_game_result_key.sql | |
| 43 | migration_20261116_shop_games_judged.sql | |
| 44 | migration_20261116_x_disclosures_data_copy.sql | |
| 45 | migration_20261117_shop_game_runner.sql | |
| 46 | migration_20261126_reward_drop_perm_union.sql | |
| 47 | migration_20261130_money_on_operator.sql | |
| 48 | migration_20261130_money_switch_two_person.sql | |
| 49 | migration_20261130_payout_bind_match.sql | |
| 50 | migration_20261115_external_games_ops_menus.sql | |
| 51 | migration_20261127_city_monopoly_claim_flow.sql | |
| 52 | migration_20261128_city_economy.sql | **yes**: `city_district` (adds `d1` where a board has none), `city_game_point.district_id`, `roam_territory.district_id` |
| 53 | migration_20261126_city_gaps_r3.sql (**re-run**, already on TEST) | yes, r3's own file: serials for the deeds that row 52 gave a district |
| 54 | migration_20261116_channel_notify_default.sql | |
| 55 | migration_20261116_channels_messaging.sql | |
| 56 | migration_20261129_channel_create_event_bit.sql | |
| 57 | migration_20261129_club_invite.sql | |
| 58 | migration_20261115_workshop_iap.sql | |

- **Row 27:** the original's backfill fails with error 1055 under MySQL 8's default `ONLY_FULL_GROUP_BY`. It groups by `LOWER(geohash7)` and selects `LEFT(LOWER(geohash7), 5)`. The TEST copy also groups by that prefix and writes the same rows. The original is not edited, because the ledger pins its checksum, and the copy is `.sql.txt` so no gate or runner treats it as a migration. **This bug is on `roadmap/be-integ` and would hit production the same way. Its owner should fix the original before it is released.**
- **Row 54:** ledger order puts it after `20261115_club_reach`, which is post-deploy. It only adds and fills `im_channel.notify_default`, which the new jar reads, while `club_reach` only inserts a paused `sys_job` row. So it runs before the swap.

**Post-deploy** (`POST_DEPLOY_MIGRATIONS` in `ci/migration_ledger.py`: `sys_job` rows naming beans only the new jar has). The script applies these only after the new jar is up:

| # | File | sys_job row |
|---|---|---|
| 59 | migration_20261115_club_reach.sql | `clubChannelDefaultsTask.backfill(200)` (paused) |
| 60 | migration_20261128_channel_purge_job.sql | `channelPurgeTask.purge(50)` (paused) |
| 61 | migration_20261128_stagger_channel_purge_job.sql | its second |
| 62 | migration_20261115_growth_loops.sql | `growthWinbackTask.run()` (active) + 5 growth-loop tables |
| 63 | migration_20261115_stagger_club_growth_jobs.sql | seconds |
| 64 | migration_20261127_city_monopoly_jobs.sql | `cityMonopolyTask.settle()/nightly()/seasonSettle()` (active) |
| 65 | migration_20261127_stagger_city_monopoly_jobs.sql | seconds |
| 66 | migration_20261116_channel_membership_backfill.sql | `channelMembershipBackfillTask.backfill(200)` (paused) |
| 67 | migration_20261116_stagger_channel_membership_job.sql | its second |

### Config

- **No new required key.** Every `@Value` added by be-integ has a default, no new `System.getenv` lookup, and the two new `@ConfigurationProperties` classes (`chengyin.mod-commerce`, `chengyin.pass.apple-iap`) have no required fields.
- The env placeholders without a default are the same six on both lines:
  - `CHENGYIN_DB_PASSWORD`
  - `CHENGYIN_DRUID_PASSWORD`
  - `CHENGYIN_JWT_SECRET`
  - `CHENGYIN_WITHDRAWAL_PII_KEY`
  - `CHENGYIN_WXPAY_APIV3_KEY`
  - `CHENGYIN_WXMINI_SECRET`

  TEST's `start.sh` already sets them; the script checks `CHENGYIN_WITHDRAWAL_PII_KEY` for presence only.
- New optional switches, all defaulting to the safe value: `CHENGYIN_MONEY_SWITCH_SERVER_CEILING` (false), `CHENGYIN_COLLECTION_MODEL_ENABLED` (false), `CHENGYIN_PASS_APPLE_IAP_ENABLED` (false), `CHENGYIN_MOD_COMMERCE_ENABLED` (false), `CHENGYIN_READ_CACHE_ENABLED` (false), `CHENGYIN_DISCOVERY_SCORING_ENABLED` (false), `CHENGYIN_ROAM_FOG_RETAIN_REVEALED_CELLS` (true, the owner decision), `CHENGYIN_ROAM_GUEST_WALK_ENABLED` (true), `chengyin.roam.place-stamp.enabled` (false), `APNS_*` (empty = push off), `CHENGYIN_MANAGEMENT_PORT` (= app port).
- **The TEST build must use `-Pe2e-test-providers`.** On this line the e2e providers (the `e2e-code-*` login, SMS, pay, weather stubs) live in `chengyinhub-e2e`, which only that Maven profile packages. Without it the jar starts, but the test accounts can't sign in. The script uses the profile and checks the jar contains the module.

### Maven dependencies the merged poms need that r3's did not

| Dependency | Version |
|---|---|
| `io.micrometer:micrometer-registry-prometheus` | 1.17.1 (Spring Boot BOM), with `io.prometheus:prometheus-metrics-*` 1.7.0 |
| `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.13.0 (replaces `jjwt` 0.9.1) |
| Tomcat | 11.0.27 (was 11.0.26) |
| `com.chengyinhub:chengyinhub-e2e` | 3.9.0 (built in the reactor) |

The VPS builds offline, so expect the online retry. Because `/data` is nearly full, the script retries on a copy of `~/.m2/repository` under `/var/tmp/x-be-test/m2repo` and deletes it afterwards.

## 6. The deploy script: `docs/roadmap/x-be-test/deploy-test.sh`

Run it with `ssh work 'bash -s' < deploy-test.sh`. The whole script is one function, so `bash -s` reads all of it before anything runs.

**Setup and build:**
- `set -euo pipefail`. The log goes to `/data/workspace/be-int/x-be-test/deploy-<ts>.log` via `tee`, and a run lock stops a second copy.
- Clone, build, Maven fallback repo and dump all go under `/var/tmp/x-be-test` (`/data` has about 700 MB free). The script stops with a clear message below 1 GB free there, or below 300 MB in `$B/app`.
- A fresh blob-less clone of `feature/r-x-be-test`, pinned to the verified commit (`SHA=` in the script).
- The build runs under `/usr/bin/flock /data/workspace/.mvn.lock` with `-Xmx1536m`, offline first, then one online retry only when artifacts are missing. It stops if the jar is under 100 MB or lacks `chengyinhub-e2e`.

**Before anything changes:**
- Optional `RUN_MYSQL_IT=1` runs the City MySQL ITs.
- A `mysqldump` of `cy_e2e_r2`, checked with `gzip -t` and the `Dump completed` trailer.

**Under `/usr/bin/flock /data/workspace/.deploy.lock`:**
1. Table count, then the pre-deploy list; the first error stops the run with "jar NOT swapped" and the dump path.
2. Table count again.
3. Rollback copy `app.jar.prev-<running sha>`. The running sha comes from an install record, `RUNNING_SHA`, or the integrator's checkout (read-only). An existing copy is never overwritten.
4. The new jar is copied next to `app.jar` before java stops, so the swap is a rename.
5. Stop java and wait up to 30 s for port 8120 to be free (SIGKILL after that).
6. Start through `start.sh`, then wait up to 120 s for `Started RuoYiApplication` and `GET /` 200.

**If it doesn't start:** the script prints the failure block and the `Caused by` lines, puts the old jar back, restarts it, checks it, and prints `RESULT: ROLLED BACK` (exit 1).

**On success:** the post-deploy list runs, then the script prints the distinct `Caused by` lines, `RESULT: DEPLOYED <sha>`, the rollback jar and the manual-rollback recipe, and deletes the clone's `target/`.

**Never:** it prints no secrets, doesn't touch `$B/hengyin`, other containers or other databases, and runs no prune or `git clean`.

**Checks run in the cloud:** `bash -n` and `shellcheck` are clean. The build command was run (offline miss, then the online retry path). The migration lists were run twice against an r3-state MySQL. Both jars were started (section 4.1). The VPS itself (docker, ssh, flock, the real `start.sh`) was not reachable from here.

## 7. Post-deploy check

`docs/roadmap/x-be-test/post-deploy-check.md` has 14 merged-only routes plus 2 controls and 4 r3-survival checks.

Rehearsed against both jars:
- **r3:** all 14 answer `404 请求路径不存在`.
- **Merged:** 11 answer `200`; 3 answer a business 404 for the made-up channel and club ids. In the r3 checks:
  - `e2e-code-9004` signs in;
  - a refusal keeps `code 400`, and with `X-Error-Contract: 2` adds `errorCode COMMON.BAD_REQUEST`;
  - multipart gives `415 UNSUPPORTED_MEDIA_TYPE`.

## 8. Note for the backend integrator

After this deploy, **TEST runs `feature/r-x-be-test`**, not `integration/backend-app-r3`. Your next TEST wave should merge on top of `feature/r-x-be-test`, or merge `roadmap/be-integ` into your line. Otherwise the W7 routes vanish from TEST again.

Also:
- a manual rollback to an r3 jar needs the 7 post-deploy `sys_job` rows removed first (the script prints the exact `DELETE`);
- the database gains 75 tables (693 → 768).

## 9. Open items and owner actions

- **For the `roadmap/be-integ` owner:**
  - take the four fixes in section 1;
  - fix `migration_20261115_fog_map.sql` (error 1055) before it reaches production.
- **For the coordinator:**
  - run the script while watching it;
  - then run the post-deploy check;
  - keep the dump (`/var/tmp/x-be-test/cy_e2e_r2-<ts>.sql.gz`) until TEST is accepted.
- Not verified: anything that needs the VPS (docker names, `start.sh`, real secrets, the TEST data in `cy_e2e_r2`).
- **City notices:** a takeover or defence now produces both r3's in-app card and be-integ's phone push (different channels, so both are kept). If that is too much, it needs an owner decision.
