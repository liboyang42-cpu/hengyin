# integration/backend-app-r2 — result (2026-11-13)

One integrated backend with everything built so far, deployed as the **TEST** backend on VPS `work` (be-int).
Production was not touched. hengyin `master` was not pushed. The only branch pushed is `integration/backend-app-r2`.

Tested SHA: **`955175611`** after the R2 follow-up (§0). The first round was tested on `01229ec46` (§1–§5). The commit that updates
this file follows `955175611` and only changes `docs/`.

## R5 (2026-11-14): `feature/be-gap-misc` + `feature/be-gap-merchant` merged, redeployed once more

| Item | Result |
|---|---|
| Merges | `origin/feature/be-gap-misc` `6de5ac855` → **`e51473e65`**, then `origin/feature/be-gap-merchant` `7121d9c32` (cut from `f89005866`) → **`67379040e`**. Both `--no-ff`, no conflicts. The ledger keeps both new entries (`20261119_be_gap_misc`, `20261119_merchant_gap`); no same-named migrations. |
| Gates | `migration_ledger.py audit` problems=[], `migration-layout-check.sh` and `migration-safety-check.sh` pass (27 migrations in this batch, ledger order). |
| Build | `mvn package -DskipTests` BUILD SUCCESS. |
| Unit tests (on `67379040e`) | **19,522 run, 0 failures**; the 34 errors are the same 14 DB-needing `*MyBatisTest` classes, **39/39** against be-int-mysql. |
| Real-MySQL ITs | **27 classes, 113/113** with the app's timezone (`-Duser.timezone=Asia/Shanghai`): the R4 list plus `BeGapMiscMysqlIntegrationTest` 3/3 and `MerchantGapMySqlIT` 3/3 (`CHENGYIN_MERCHANT_GAP_MYSQL_IT=1`). `MapPublicLayersMySqlIT` 1/1. |
| TEST 8120 redeploy | Jar from `67379040e`, same DB `cy_e2e_r2`: only `20261119_be_gap_misc` and `20261119_merchant_gap` were applied (634 → 656 tables). Overlay additions (TEST only; code defaults stay off): the W05 merchant authoring policy fixture values from `be-gap-merchant/application-merchant.properties.txt`, and every misc switch (private home, home return with placeholder policy `home-return-test-1`, US Apple realm `us-test` / client id `com.example.questify.us`, App Attest with the TEST app id `ABCDE12345.com.chengyinhub.test`, categories 2,4 and Apple's public roots in `app/attest/`). **Rollback:** `app/*.prev-aa3dcc8` (the R4 deploy); `*.prev-fc6c80e` and older are kept. |
| TEST seed | `r5/fixture-merchant-8120.sql.txt` = `be-gap-merchant/fixture-merchant.sql.txt` with its database guard pointed at `cy_e2e_r2` (store 8001/8002 city points + 4 visits, the FREE_EXPLORE copy 992901/992911/992991 for the supply exit, one W05 source row, product 992951). |
| curl: merchant (`smoke_merchant_8120.py`) | Same outcomes as on 8127 for all 29 rows (W05 drafts/facts/publish/withdraw, draft selection, city value, marketing consent result, store NPC voice/avatar/knowledge review, offer withdrawal, the three aliases). Generation answers `UPSTREAM_UNAVAILABLE` (no model on TEST), `shop/apply` answers the target's real-name refusal for the fixture. Transcript `r5/smoke-merchant.log`. |
| curl: misc (`smoke_misc_8120.py`) | **41/41 OK** (the 40 checks of `be-gap-misc/smoke_misc.py` plus an `im/unblock` cleanup so the shared IM fixtures stay usable). |
| curl: club / R2 regression | club `activity/join` 200 (waitlist entry 2) and `activity/cancel` 200 again; `smoke_r2.py` **39 OK** on loopback and on the public URL (the same two known upload failures). On TEST the loopback SMS windows (`sms_send_ip_window_127.0.0.1`, `sms_verify_ip_window_127.0.0.1`) fill up after many smoke logins; clear them in Redis db 9 before rerunning. |

Logs: VPS `/data/workspace/be-int/r5/`.

## R4 (2026-11-14): `feature/be-gap-club` + `feature/be-gap-rewards-workshop` merged, one redeploy

| Item | Result |
|---|---|
| Merges | `origin/feature/be-gap-club` `4d27a88f2` → **`7b10199e9`**, then `origin/feature/be-gap-rewards-workshop` `9a49a9749` (which carries `feature/game-import-review` `e10a8e98f`) → **`aa3dcc89d`**. Both `--no-ff`, no conflicts, no same-named migrations. Ledger entries come from the branches: `20261116_external_game_review`, `20261117_reward_pledge`, `20261117_workshop_professional`, `20261118_club_proxy_enroll`. |
| Gates | `migration_ledger.py audit` problems=[], `migration-layout-check.sh` and `migration-safety-check.sh` pass. |
| Build | `mvn package -DskipTests` BUILD SUCCESS (`flock`, `-T 1`, `-Xmx1536m`). |
| Unit tests (on `aa3dcc89d`) | `-pl framework,system,admin -am`, surefire `-Xmx1536m`: **19,410 run, 0 failures**, 34 errors = the same 14 DB-needing `*MyBatisTest` classes, which pass **39/39** against be-int-mysql. (The club-only merge `7b10199e9` was also 19,369 / 0 failures.) |
| Real-MySQL ITs | **25 classes, 107 run, 106 pass** on the first run: the R3 list plus `ClubProxyEnrollMysqlIntegrationTest`, `RewardsWorkshopMysqlIntegrationTest`, `ExternalGameReviewMysqlIntegrationTest`, `WorkshopPurchaseMyBatisIT`, `WorkshopAppleIapMyBatisIT`. The one red is `ExternalGameReviewMysqlIntegrationTest.submitIsPendingServerSignedAndIdempotent` under a UTC JVM (expected `10:00+08:00`, got `18:00+08:00`), the same JVM-timezone dependency as `MapPublicLayersMySqlIT`. With `-Duser.timezone=Asia/Shanghai` (the app's timezone) it passes **7/7**, and `MapPublicLayersMySqlIT` passes 1/1. |
| TEST 8120 redeploy | Jar from `aa3dcc89d`, same DB **`cy_e2e_r2`**: only the four new migrations were applied (616 → 634 tables). Overlay (TEST only): **`chengyin.release.money-payments=true`, `chengyin.release.payout=true`** (the cash switch; the code default stays off). `start.sh` (TEST only) now also exports a stable TEST Ed25519 seed for external-game signatures from `app/.test-external-game-key` (generated once, `chmod 600`, never printed), like the PII key. **Rollback:** `app/*.prev-fc6c80e` (the R3 deploy); `*.prev-2e6f665`, `*.prev-01229ec`, `*.prev-9ac1393` are kept. |
| TEST seed | `r4/seed_r4_waitlist.sql.txt`: a series waitlist policy for session 992031 (club 7001: `club_event_series` ACTIVE, waitlist on, no host reservation, offer 20 min, plus its `club_event_occurrence`) and one sold-out free ticket 992042 on 992031 (a waitlist only queues on a sold-out ticket; ticket 992041 used by the registration smoke is unchanged). `be-gap-rewards-workshop/fixture_8125.sql.txt` on `cy_e2e_r2`: **`w17_reward_policy` rows** `REVIEWER`(9004), `RULE`/`CLAIM` `rw-test-1`, `STORE_HOLD`, coupon 970001 of merchant 9002, a TRAFFIC chapter offer on 992001, and 9003's paid workshop license 970201 installed into draft 970301. Production has no policy rows, so rewards stay closed there until the owner opens them. |
| curl: club (`smoke_club_8120.py`) | All 16 rows as in `be-gap-club/RESULT.md`. **`activity/join` (9004) 200**: waitlist entry 1 on ticket 992042 (`WAITING`); **`activity/cancel` (9004) 200** `true`. Transcript `r4/smoke-club.log`. |
| curl: rewards + workshop (`smoke_rw_8120.py`) | Rewards **53/53 OK** (signed out 401, draft/replay/conflict, cash draft refused, ops read-back, submit/confirm, review queue, freeze, catalog/offer/claim/replay/recover, list/page/detail, store awards, preview/resolve/redeem/expire, stats, hold/withdraw, `CASH_REFUND` case opening with the switch on). The workshop block's 9003 login hit the TEST loopback verify-IP window after the earlier logins, so it was rerun alone (`smoke_rw_workshop_8120.py`, which also clears that TEST Redis key): **16/16 OK** (targets, detail, submit CREATED template 992107, replay, status, cancel, stale hash REJECTED, cancel-before-submit, late submit, history, malformed 400; events `AUTHOR:PENDING,SYSTEM:APPROVED,AUTHOR:PENDING,SYSTEM:REJECTED,AUTHOR:CANCELLED`). |
| curl: external games (`smoke_import_8120.py`) | **24/24 OK**: signing key configured, submit signed out 401, submit PENDING with the server signature, replay, `GAME_ID_TAKEN`, mine, admin queue/detail/approve (the e2e fixture admin), **catalog 1 item**, catalog detail signed download, tampered link 403, reject/escalate/cancel, reports. |
| curl: R2 regression (`smoke_r2.py`) | loopback and public URL: **39 OK**, unchanged; the only failures are the known OSS-less media upload and WeChat-less image upload. |

Logs and scripts: VPS `/data/workspace/be-int/r4/` (`gates.log`, `build.log`, `test-unit.log`, `its.log`, `extgame-rerun.log`, `mybatis-rerun.log`, `map-rerun.log`, `smoke*.log`, the `*_8120.py` copies, `club-only/` for the first unit run). Copies of the seed and smoke scripts are in `r4/` in this folder.

## R3 (2026-11-14): `feature/be-gap-im` merged and deployed

| Item | Result |
|---|---|
| Merge | `origin/feature/be-gap-im` `9964bc78b` → merge commit **`fc6c80e70`** (`--no-ff`, no conflicts: be-gap-im was cut from `2e6f6651c`). It brings `/api/channels`, `/api/im/audit-log`, the `/api/im/webhook/*` family, `chain/info` by `conversation_id`, the outbound dispatcher (signed, backoff, DLQ, redrive) and signed inbound ingestion; migrations `20261116_im_gap_audit_webhook` and `20261117_im_webhook_dispatch` with their ledger entries. See `docs/delivery/be-gap-im/RESULT.md`. |
| Gates | `migration_ledger.py audit` problems=[], `migration-layout-check.sh` and `migration-safety-check.sh` (5/5) pass. |
| Build | `mvn package -DskipTests` BUILD SUCCESS (`flock`, `-T 1`, `-Xmx1536m`). |
| Unit tests | `-pl framework,system,admin -am`, surefire `-Xmx1536m`: **19,347 run, 0 failures**, 34 errors = the same 14 DB-needing `*MyBatisTest` classes, which pass **39/39** against be-int-mysql. |
| Real-MySQL ITs | **21 classes, 87/87**: the R2 list plus be-gap-im `ChannelsGapImMySqlIT` 6/6 and `ChannelsWebhookDispatchMySqlIT` 3/3 (`ChainPersistenceMySqlIT` 9/9, City 3+3, gaps-b 8 classes on `cy_gaps_b_r2`). `MapPublicLayersMySqlIT` 1/1 with `-Duser.timezone=Asia/Shanghai`. MDB-F2 was not rerun (be-gap-im touches no marketing code). |
| TEST 8120 redeploy | Jar from `fc6c80e70`, same DB **`cy_e2e_r2`**: only the two new migrations were applied (616 tables). The overlay turns the dispatcher **on for TEST only** (`chengyin.im.webhook.dispatch.enabled=true`, `chengyin.im.webhook.inbound-base-url=https://8120-…instabox.tech`). The code default stays off. The PII key file is unchanged, so stored sign-up PII stays readable. **Rollback:** `app/*.prev-2e6f665` (the R2 follow-up deploy); `*.prev-01229ec` and `*.prev-9ac1393` are kept. |
| curl (R2 list, `smoke_r2.py`) | loopback and public URL: **39 OK**, the same as R2. The only failures are the known OSS-less media upload and WeChat-less image upload. |
| curl (be-gap-im, `smoke_r3_im.py`) | `/api/channels` 200; `audit-log verify=1` 200 `chain_ok=true` (member 403 `CHANNEL_FORBIDDEN`); `webhook/context` 200; `chain/info?conversation_id=5` body 404 `CHAIN_NOT_FOUND`. A subscription to the TEST app's own public URL: the `test` delivery and a real `/api/im/send` in channel 5 were both claimed and sent signed, and came back `DEAD a1 HTTP_401` (a non-retryable 4xx). This proves the dispatcher is live without calling a third party. Inbound endpoint create 200 (member 403), signed 200 `ACCEPTED` (message 17), replay 409 `REPLAYED`, bad signature 401. Test subscription and endpoint deleted. |

Logs: VPS `/data/workspace/be-int/r3/` (`gates.log`, `build.log`, `test-unit.log`, `its.log`, `mybatis-rerun.log`, `map-rerun.log`, `smoke.log`, `smoke-public.log`, `smoke-im-loopback.txt`).

## 0. R2 follow-up (2026-11-13, coordinator msg_3856dec207cf)

| # | Item | Commit | What changed |
|---|---|---|---|
| 1 | `/api/play/egg/collect` | `c496d370d` | The route answers **410 `ROUTE_EGG_OFFLINE`** before login and parameter checks (same convention as `HANGOUT_OFFLINE`), instead of 500. The service and mapper code that read the dropped `npc_route_egg` is removed. `PlayerGapsMysqlIntegrationTest` drops its egg case (registry 3 → 2), and app-gaps-a `MAPPING.md` and `smoke_app_gaps_a.py` are updated. |
| 2 | `ChainPersistenceMySqlIT` | `180abaf0f` | **The unique key was the wrong side.** `im_chain_entry.request_id` is only the latest-command pointer (see the L8 migration comment). Idempotent replay for the same member and payload, and rejection for a different one, are enforced by `im_chain_command_receipt.uk_chain_command (chain_id, request_id)`, which is what the test checks. `ChannelInteractionService` scopes request ids per operator, so the entry-level `uk_chain_entry_request` turned a second member reusing the same request text into a raw duplicate-key 500. New migration `20261115_chain_entry_request_nonunique` (rerunnable) replaces it with the plain index `idx_chain_entry_request`. The IT applies it twice and asserts the index shape: 9/9. |
| 3a | 2 capgraph verb mismatches | — | **False positives, no backend change.** The app's own transports send `GET /api/friend/relationship` (`NativeFriendService` / `NativeFriendRemovalService`: `body == nil` → GET with query `peer_id`) and `POST /api/topic/attribute-author/validate` (`NativeAttributeAuthorHostTransport`: `(.post, …/validate)`). The backend maps exactly these. The capgraph's static verb guess misreads both. The mini-program does not call either route. |
| 3b | `/api/mod-commerce` OFF stub | `54ed1867c` | The app (`Core/WorkshopCommerceService`) only reads `code`: 401 means re-login, anything else non-200 means unavailable. The stub now answers **401 when signed out**, otherwise **503 + `errorCode WORKSHOP_COMMERCE_DISABLED`**. Every method is still refused without reading the body or touching payment. |
| 4a | U06 turn order | `758bcf9d1` | Every member's advanced-session read (`publicMultiplayer`, used by state/act/start) gets a `turn` block: `index, position, size, round, completedTurns, requiredTurns, finished, myTurn, turnsUntilMine`. It contains no other member's role (U06-02; the app's `PlayAdvancedTeamProjection`: "other members' roles are leader-only data"). The **leader** also gets `turn.currentRoleId` and the ordered `turnOrder` list `[{position, roleId, roleLabel}]`, with the same visibility as `roleAssignments`. It is additive, and the app's decoder ignores unknown keys. |
| 4b | U08-03 creator maintenance | `a1974a9a2` | `GET /api/creator/feedback` (U08-02 `play_run_feedback` on the caller's own topics; the reporter's member id and run id are never returned). Maintenance tickets: `POST /api/creator/maintenance/tickets`, `GET …/tickets[/{id}]`, `POST …/tickets/{id}/close`, and `GET /api/creator/maintenance/requests/{requestId}` (recovery). The stage is derived from facts: REPORTED → FIX_DRAFTED (draft version > baseline) → FIX_PUBLISHED → RETESTED (the author's own completed run on a newer version) → CLOSED. Closing FIXED needs the published version past the baseline (`FIX_NOT_PUBLISHED` otherwise); NO_CHANGE needs a reason. Version CAS and idempotent request ids throughout. Author = `cms_topic.member_id`; others get 404. No money. Migration `20261115_creator_maintenance`. |
| 4c | U08-04 creator re-registration board | `955175611` | `GET /api/creator/analytics/post-game-outcome?cohortDate&windowDays&topicId` is the B8 report scoped to the caller's own topics, with the same maturity, refund, seat-dedup, watermark and basis semantics (`mature=false` is not 0%). The cohort is members whose full-history first valid registration was on those topics. `SECOND_VALID_REGISTRATION` keeps the B8 definition, and `OFFICIAL_COMPLETION` counts completed route sessions on those topics. Reward, photo, share, click, client-observed and roam revisit cannot be attributed to a topic, so they are **omitted and listed in `scope.omittedMetrics`** instead of being shown with platform numbers. A creator without topics gets 404 `CREATOR_TOPICS_NOT_FOUND`, and an unreadable source gets 503 (never 0). The platform report is unchanged. |

**Tests on `955175611`:** build OK. Unit tests (`-pl framework,system,admin -am`, surefire `-Xmx1536m`): **19,309 run, 0 failures**. The 34 errors are the same 14 DB-needing `*MyBatisTest` classes as before, and they pass **39/39** against be-int-mysql. Real-MySQL ITs: **78/79** (new `CreatorMaintenanceMysqlIntegrationTest` 3/3, `ChainPersistenceMySqlIT` 9/9, `PlayerGapsMysqlIntegrationTest` 2/2, all channels/gaps ITs green). The one red is `MapPublicLayersMySqlIT` under a UTC JVM, and it passes **1/1 with `-Duser.timezone=Asia/Shanghai`**, as before. **MDB-F2: 25/25 classes, 239/239 tests PASS, cleanup CLEAN.** Targeted unit tests: `AdvancedGameRuntimeServiceImplTest` 232/232 (U06), `PostGameOutcomeReportServiceImplTest` 37/37 (+2 scoped), `CreatorAnalyticsServiceTest` 3/3, `ApiPlayerGapsControllerEggTest` 1/1, `ApiAppGapsWorkshopControllerTest` 1/1. Migration gates (ledger audit/manifest, layout, safety 5/5) all pass. Curl smoke: 39 OK lines, identical on loopback and the public URL. The only failures are the known OSS-less media upload and the WeChat-less image upload. Logs: VPS `/data/workspace/be-int/r2/` (current run), with the earlier runs under `run1-*`, `run2-*`, `run3-*`.

**Redeploy (same `cy_e2e_r2`, then re-seeded):** the new jar started after only the two new migrations were applied, but 9009's sign-up then failed to decrypt
(`PII 解密失败`). `start.sh` generated a **random PII key on every start**, so any restart that keeps the DB loses all stored sign-up PII. This was
latent and never showed before, because every earlier deploy rebuilt the DB. Fix on TEST: `start.sh` now reads a stable TEST-only key from
`app/.test-pii-key` (generated once, `chmod 600`, never printed). Rows already encrypted under the lost key cannot be recovered, so `cy_e2e_r2` was rebuilt from the ledger
(670 migrations, 373 after the snapshot, 0 problems, 609 tables) and re-seeded with the same steps (`seed_api` 14 OK). A follow-up **restart** then
proved the fix: `play/nodes` and the sign-up replay for 9009 return 200, with 0 PII errors. TEST-only data added for the U08 smoke: one completed route session of topic 992001 for 9009
(`play_instance_id r2-smoke-run-9009`). Rollback for this deploy: `app/*.prev-01229ec` (the previous r2 jar, properties and `start.sh`).

## 1. Merged heads (in order, from `origin/integration/backend-app-20261112` = `c96d345ff`)

| # | Head | SHA | Merge commit | Conflicts |
|---|---|---|---|---|
| 1 | `fix/userinfo-role` | `9ac1393ad` | `61aa06266` | none |
| 2 | `feature/im-channels` | `bb4ee788a` | `27f1e870e` | `ci/migration-dependencies.json` |
| 3 | `feature/app-gaps-a` | `00df8a74e` | `6ea45cd05` | none |
| 4 | `feature/app-gaps-b` | `39c8ea928` | `9e6be1156` | none |
| 5 | `feature/app-gaps-a` (pass 2) | `faab653ca` | `b49c06cf5` | none |
| 6 | `feature/app-gaps-b` (pass 3) | `c8d21a396` | `1250ea5dd` | none |
| 7 | `feature/app-gaps-a` (pass 2 fixes) | `2aacc518f` | `80a018abb` | none (auto-merged onto the same constructor fix, below) |
| 8 | `feature/im-channels` (fourth pass) | `e16417013` | `01229ec46` | `ci/migration-dependencies.json` |

Rows 5–8 are the newer heads pushed while this ran ("take the newest pushed head"; coordinator messages for 7 and 8).

### Conflict resolutions

* **Merge 2 — `ci/migration-dependencies.json`.** Both sides only added keys at the same spot. Kept both: base
  `20261105_l4_invoice_command_receipt` and channels `20261105_l8_channel_lifecycle`, `20261105_l8_chain_storage`,
  `20261112_im_channels_platform`, `20261105_l9_friend_foundation`, `20261113_im_channels_second_pass`, `20261114_im_channels_third_pass`.
* **Merge 8 — `ci/migration-dependencies.json`.** Kept both: `20261114_club_notify_default` (this branch) and `20261115_im_channels_fourth_pass`.
* **Same-named migrations:** none. `ls chengyinhub-admin/sql | uniq -d` is empty at every step, so nothing needed renaming.
* The ledger keeps every entry. Execution order is the ledger's topological order (`build_manifest`), which `build_schema.py` uses.

### Integration commits (not from a feature branch)

| Commit | What | Why |
|---|---|---|
| `23350e488` | `ci/migration_ledger.py`: classify `docs/delivery/e2e-seed/seed.sql` as `TEST_FIXTURE` | the ledger manifest refused the unclassified SQL file that `fix/userinfo-role` added |
| `0211ae471` | e2e seed: `seed.sql` sets `ums_member.wx_open_id = e2e-openid-<id>` for fixture players 9001–9009 and accepts `cy_e2e_r2`; `seed_api.py` takes the DB from `E2E_DB` | `cy_e2e_beint` had these openids set by hand. On a fresh DB, `ContentSafetyGateImpl` held topic publish and square posts as `IDENTITY_REVIEW` (author-only) |
| `847b9860b` | `@Autowired` on the public constructor of `BundleExtrasService`, `DeregistrationResultService`, `LocationShareService` | gaps-a pass 2 (`faab653ca`) failed to start: `BeanInstantiationException … DeregistrationResultService.<init>()`. `2aacc518f` later made the same fix on gaps-a, and git merged the two cleanly |
| `8cb262d27` | **Dropped** `migration_20261114_gaps_a_npc_route_egg.sql` and its ledger entry | Coordinator instruction: the owner decommissioned `npc_route_egg` on 2026-09-07 (`migration_x11`), and that decision stands. Everything else from gaps-a pass 2 is kept. Consequence: `POST /api/play/egg/collect` answers `500 系统繁忙` on ledger-built schemas because the table is gone. `docs/delivery/app-gaps-a/MAPPING.md` still describes the restore and needs a gaps-a follow-up |
| `10dbe9adc` | **New:** `GET/POST /api/club/settings/notify-level` (see §5) | Coordinator instruction: `feature/im-channels` did not have it; spec is app-gaps-b MAPPING §4 |
| `b014bac24` | Ledger: declared `20261113_gaps_a_city_game: []` and `20261114_gaps_a_player: []` | `ci/migration-safety-check.sh` gate 5 rejects new migrations without a declaration. Both only create new tables (no FKs, no writes to existing rows) |

Ledger gates on `01229ec46`: `migration_ledger.py audit` problems=[], `manifest --target-commit` OK, `migration-layout-check.sh` passes,
`migration-safety-check.sh` passes all 5 gates, and `dup_create_table_gate.py` passes.

## 2. Build and tests (all on the VPS, `flock /data/workspace/.mvn.lock`, `-T 1`, `MAVEN_OPTS=-Xmx1536m`)

* **Build:** `mvn package -DskipTests` → BUILD SUCCESS.
* **Fresh TEST database `cy_e2e_r2`** in `be-int-mysql`, built by `scripts/e2e/build_schema.py`: base snapshot (227 tables) + seed,
  then **371 post-snapshot migrations in ledger order** (668 in the ledger). Problems: baseline 0, seed 0, migrations 0. Final table count: 607.
  The 5 `DATA_ASSERT` observations are data-only assertions, which are expected on an empty database (same as round 1).
* **Unit tests**, `-pl chengyinhub-framework,chengyinhub-system,chengyinhub-admin -am` with surefire `-Xmx1536m`. The default 512 MB fork
  OOMs in `CardCutoutInputTest` and aborts the rest of the admin module, as it did in round 1.
  **19,300 run, 0 failures, 34 errors, 690 skipped.** All 34 errors come from 14 `*MyBatisTest` classes that need a real MySQL (CI provides
  one on 3306; `Communications link failure` here). Rerun of exactly those 14 classes against `be-int-mysql`
  (`CHENGYIN_MYBATIS_IT_PORT=3330`, suffix `_r2`): **39/39 pass**.
* **New real-MySQL ITs** against `be-int-mysql` (3330):
  * app-gaps-a: `CityGameMysqlIntegrationTest` 3/3, `CityR491MysqlIntegrationTest` 3/3, `PlayerGapsMysqlIntegrationTest` 3/3, `AppGapsPass2MysqlIntegrationTest` 4/4 (scratch `cy_r2_*`).
  * app-gaps-b, on `cy_gaps_b_r2` = a copy of the seeded `cy_e2e_r2`: `AppOriginalEnvelopeMySqlIT` 2/2, `AppOriginalRequestLedgerMySqlIT` 4/4,
    `ClubOriginalsMySqlIT` 3/3, `ClubSeriesOriginalResultsMySqlIT` 2/2, `CouponCommandReceiptsMySqlIT` 2/2, `SupportTicketReadsMySqlIT` 1/1,
    `UsageConsentRecordsMySqlIT` 1/1, `ApprovedTopicReleasesMySqlIT` 3/3. `MapPublicLayersMySqlIT` fails 0/1 with the JVM in UTC and
    **passes 1/1 with `-Duser.timezone=Asia/Shanghai`**. The DB runs at `+08:00`, the test compares `LocalDateTime.now()` with rows written by `now()`,
    and the app itself runs with `Asia/Shanghai`, so this is a test timezone dependency, not a backend defect.
  * channels (`GITHUB_ACTIONS=true`, MyBatis IT DB on 3330, suffix `_r2`): `ChannelsPlatformMySqlIT` 4/4, `ChannelsSecondPassMySqlIT` 10/10,
    `ChannelsThirdPassMySqlIT` 4/4, `ChannelsFourthPassMySqlIT` 7/7, `FriendFoundationSchemaMySqlIT` 11/11,
    **`ChainPersistenceMySqlIT` 7/8**. `sameRequestCannotBeReboundToAnotherMemberOrPayload` throws `Duplicate entry '1-same-key' for key
    'im_chain_entry.uk_chain_entry_request'`. The migration, the test and the mapper are byte-identical to `feature/im-channels`, and the IT applies only
    `migration_20261105_l8_chain_storage.sql` to its own schema, so this failure comes from the branch itself (L8 chain storage, PR#728), not from the merge. The
    unique key is `(chain_id, request_id)`, while the test expects another member to be able to insert an entry with the same request id. **Open item for the channels owner.**
  * Total: 76 run, 74 pass, plus the map IT that passes under the app timezone. One real failure (above).
* **MDB-F2** (`marketing-db-acceptance`, `ci/real_storage_it.py`, a fresh MySQL container per class, `RUNNER_ENVIRONMENT=github-hosted`):
  **25/25 classes, 239/239 tests PASS, cleanup CLEAN** (including `MarketingRevenueAllocationMySqlIT` 13/13 and `GenericRouteRuntimeMySqlIT` 77/77).
* New unit test for §5: `ChannelNotifyClubDefaultTest` 4/4. The existing `ChannelPlatformServiceTest` 11/11, `ChannelGovernanceServiceTest` 4/4 and `ChannelOnboardingServiceTest` 4/4 still pass.

Logs are on the VPS under `/data/workspace/be-int/r2/` (`build.log`, `schema.log`, `test-unit.log`, `its.log`, `mybatis-rerun.log`, `map-rerun.log`, `mdb/`).
Earlier runs on superseded SHAs are kept in `run1-0211ae471/` and `run2-847b9860b/`. They had the same outcome, and MDB-F2 was 25/25 on `847b9860b`.

## 3. TEST deployment

* **URL:** https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech (reachable only while the VPS runs). Loopback is `127.0.0.1:8120`.
* Jar from **`955175611`** at `/data/workspace/be-int/app/app.jar`, profile `druid,e2e-test` + `chengyin.e2e.test-providers=true`. `start.sh` is unchanged except for the stable TEST PII key (§0).
  Overlay `application-beint.properties` now points at **`cy_e2e_r2`**. Redis is still `be-int-redis` db 9.
* **Rollback:** `app/*.prev-01229ec` is the previous r2 deploy on `cy_e2e_r2`, and `app/*.prev-9ac1393` is the pre-r2 TEST, `fix/userinfo-role` on
  `cy_e2e_beint`, which is untouched. The older `*.prev-c96d345` set was deleted to save disk.
* Re-seed order on `cy_e2e_r2`: `build_schema.py` → `seed_fixtures.py` → `docs/delivery/e2e-seed/seed.sql` → `scripts/e2e/seed_app_gaps_a.py`
  (board `gapsa-board-1`, region `gaps-a-test`) → start the app → `E2E_DB=cy_e2e_r2 seed_api.py` (14 OK) → `verify.sh` (all 200).
* Startup log: same expected warnings as round 1 (no WeChat Pay merchant certificate, no alarm webhook, paused monitor job).

### curl verification (loopback and public URL give identical results; script `smoke_r2.py` in this folder)

| Check | Result |
|---|---|
| login (`/api/sms/send` + `/api/login/phone`, players 9004/9009, club owner 9003) | 200, role player / player / club |
| `/api/userInfo` role | 200 `role=player nickName=玩家二号` |
| `/api/topic/info-to-user` 992001 | 200 `E2E 城市定向·老城三章`, lifecycle 3 |
| `/api/play/nodes` | 200 registered=True, 6 nodes / 3 chapters |
| `/api/registration/quote` + `/create` (free) | 200, payAmount 0.0, registrationId 74 (idempotent replay) |
| `/api/im/send` text | 200, checkStatus 1 |
| `/api/im/send` image | `msg_type 2` send 200 → `GET /api/im/message/{id}/attachment` 200 `kind=IMAGE state=PENDING source=LEGACY_SINGLE_IMAGE`; `GET /api/im/message` 200 for the sender, 404 for the recipient (correct by contract until approved). **Uploading bytes is blocked on TEST:** `/api/common/upload` → 503 (needs WeChat imgSecCheck credentials), and the new `/api/im/{file,video,voice}/upload` media pipeline → `OUTCOME_UNKNOWN` (`对象存储配置缺失: CHENGYIN_OSS_ENDPOINT`). TEST has no OSS and no WeChat. Verifying uploads needs either real TEST OSS/WeChat credentials or a TEST-only storage/moderation provider. |
| channels list / create (club 7001, guild_kind 1) | 200 directory; create 200 `state=APPLIED` |
| `/api/club/settings/notify-level` | GET (member) 200 `level=1 version=0 configured=false`; POST (owner) 200 APPLIED v1; stale version 200 `REJECTED CLUB_DEFAULT_VERSION_CONFLICT`, read back through `/api/im/notification-requests/{id}`; POST (member) 403 `CLUB_DEFAULT_FORBIDDEN` |
| `/api/friend/request` | 200 `outcome=APPLIED`; `/api/friend/requests` incoming=1 |
| `/api/club/home` | 200 joined [7001, 7002] |
| `/api/city-game/current?regionId=gaps-a-test`, `/game/snapshot?boardId=gapsa-board-1` | 200 `CITY_PLAYER_READ_V1` AVAILABLE; snapshot 200 |
| `/api/team/create` → `/api/team/pool` | 200 team; pool 200 `enabled=false TEAM_POOL_NOT_CONFIGURED` (the seeded topic has no `gameplayRuntime.teamPool`; correct per contract) |
| `/api/rank/list?scope=team` | 200 `scope=team`, 0 entries |
| `/api/play/egg/collect` (follow-up 1) | 410 `ROUTE_EGG_OFFLINE` |
| `/api/mod-commerce/orders` (follow-up 3b) | signed out 401; signed in 503 `WORKSHOP_COMMERCE_DISABLED` |
| `/api/play/feedback/submit` → `/api/creator/feedback` (U08-03) | 9009 files feedback on its run; 9003 (author) reads it with the reporter hidden; 9009 reading 992001 as an author gets 404 `TOPIC_NOT_FOUND` |
| `/api/creator/maintenance/tickets` open → close (U08-03) | open 200 `stage=REPORTED baseline=1`; close FIXED without a new version 409 `FIX_NOT_PUBLISHED`; close NO_CHANGE with a reason 200 `CLOSED` |
| `/api/creator/analytics/post-game-outcome` (U08-04) | 9003: 200, scope `[990013, 992001]`, `mature=false` for cohort 2026-11-13, metrics `OFFICIAL_COMPLETION` + `SECOND_VALID_REGISTRATION`; 9009 (no topics) 404 `CREATOR_TOPICS_NOT_FOUND` |
| U06 `turn` block | not exercisable on TEST (no multiplayer session is seeded); covered by `AdvancedGameRuntimeServiceImplTest` (232/232, including 2 turn cases) |

TEST-only data note: the smoke script sets `cms_topic.team_mode = 2` on topic 992001 in `cy_e2e_r2`, so a team can be created.

## 4. Capability graph: APP-NOT-IN-BACKEND now served

`tools/capgraph` from app branch `ui/capgraph` (`c2202dbe3`). The backend / mini-program / hengyin-docs extraction ran on the VPS against this
branch (rerun on **`955175611`** after the follow-up); the app scan and the join ran as in `run.sh`. Baseline is the 339 APP-NOT-IN-BACKEND paths in
`docs/release/capability-graph.csv` on `ui/capgraph`.

**177 of the 339 paths are mapped by a backend controller; 175 of them are functionally served** (2 deliberately closed: egg 410 and the
mod-commerce OFF stub). **162 are still missing.** The six new U08 creator routes are not among the 339, because no app client calls them yet.
The capgraph lists them as BACKEND-UNUSED (299 → 305) until the app adds clients.

* By area (served): messaging 46, social/friends 23, city game 17, club 14, topics/routes 13, creator/publishing 11, channels (native) 9, play runtime 8,
  roam 7, native-only 6 (the blacklist routes), profile 5, workshop/mods 4, activities 3, channels 3, templates 2, others 1 each.
* Status per row is in the `status` column of `capgraph-served.tsv`: `/api/play/egg/collect` is CLOSED (410 `ROUTE_EGG_OFFLINE`), and `/api/mod-commerce` is OFF
  (401 / 503 `WORKSHOP_COMMERCE_DISABLED`). The two MISMATCH rows (`/api/friend/relationship`, `/api/topic/attribute-author/validate`) are
  false positives of the tool's static verb guess (§0 3a). The app sends the same verb the backend maps, so they are SERVED.
* Still missing by area: merchant 30, messaging 23, club 21, rewards 18, workshop/mods 16, city game 9, channels (native) 6, native-only 6, play runtime 5, and others.
* Full lists: `capgraph-served.tsv` (path, verb, controller, area) and `capgraph-still-missing.tsv` in this folder.

## 5. `/api/club/settings/notify-level` (new in this branch)

* `GET ?clubId=`: club members only (otherwise 403 `GUILD_FORBIDDEN`). Returns `{club_id, level (0 all / 1 mentions), version, configured, updated_at}`.
  An unconfigured club returns `version 0, level 1, configured false, updated_at null`, matching `NativeClubNotificationDefault.validate`.
* `POST` JSON exactly `{request_id, clubId, level, version}`: guild-level `MANAGE_CHANNEL` (owner/admin), otherwise 403 `CLUB_DEFAULT_FORBIDDEN`.
  A version CAS on the new `im_club_notify_default` row (version 1 on the first save). The receipt lives in family `NOTIFY` with operation `CLUB_DEFAULT`
  and `scope_type 2`, so `/api/im/notification-requests/{id}` reads it back, and the same `request_id` with a different body returns 409 `REQUEST_ID_CONFLICT`.
  A stale version is a **stored** `REJECTED` receipt with `result.rejection_code = CLUB_DEFAULT_VERSION_CONFLICT`
  (`CLUB_DEFAULT_VERSION_EXHAUSTED` at `Long.MAX_VALUE`), as `NativeChannelNotificationReceipt.validate` expects.
* Migration `migration_20261114_club_notify_default.sql` (creates one new table, after `20261113_im_channels_second_pass`).

## 6. Open items / known issues

1. ~~`ChainPersistenceMySqlIT` red~~: fixed in the follow-up (`180abaf0f`, unique key moved to the receipt side).
2. Media upload cannot be verified on TEST (no OSS, no WeChat image check). See the image row in §3.
3. ~~`/api/play/egg/collect` 500~~: closed with 410 `ROUTE_EGG_OFFLINE` in the follow-up (`c496d370d`), and app-gaps-a MAPPING updated.
4. 14 `*MyBatisTest` classes are not gated and need MySQL on 3306. They pass against be-int-mysql. `MapPublicLayersMySqlIT` depends on the JVM timezone.
5. Pre-existing and not new here: `CommunityPostServiceImpl.runMaintenance` throws `NumberFormatException: "false"` when an erasure job row exists
   (`legal_hold` read as Boolean). It was already in the previous TEST deployment's log (`fix/userinfo-role` on `cy_e2e_beint`).
6. TEST `start.sh` used to randomize the PII key on every start (§0). It now reads the stable TEST-only key file. Any other TEST deploy script that
   keeps a DB across restarts needs the same treatment.
7. The U08-03/U08-04 routes and the U06 `turn` block have no app client yet (owner doc rows are PROPOSED). The wire shapes are documented in §0 and
   in the controller/service Javadoc for the app side to adopt.
