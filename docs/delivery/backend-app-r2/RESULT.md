# integration/backend-app-r2 — result (2026-11-13)

One integrated backend with everything built so far, deployed as the **TEST** backend on VPS `work` (be-int).
Production was not touched. hengyin `master` was not pushed. The only branch pushed is `integration/backend-app-r2`.

Tested SHA: **`01229ec46`** (the commit that adds this file follows it and only changes `docs/`).

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
* Jar from `01229ec46` at `/data/workspace/be-int/app/app.jar`, profile `druid,e2e-test` + `chengyin.e2e.test-providers=true` (unchanged `start.sh`).
  Overlay `application-beint.properties` now points at **`cy_e2e_r2`**. Redis is still `be-int-redis` db 9.
* **Rollback** (the previous TEST deployment, `fix/userinfo-role` 9ac1393ad on `cy_e2e_beint`, which is untouched):
  `app.jar.prev-9ac1393`, `application-beint.properties.prev-9ac1393`, `start.sh.prev-9ac1393`. The older `*.prev-c96d345` files are still there.
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

TEST-only data note: the smoke script sets `cms_topic.team_mode = 2` on topic 992001 in `cy_e2e_r2`, so a team can be created.

## 4. Capability graph: APP-NOT-IN-BACKEND now served

`tools/capgraph` from app branch `ui/capgraph` (`c2202dbe3`). The backend / mini-program / hengyin-docs extraction ran on the VPS against this
branch (`01229ec46`); the app scan and the join ran as in `run.sh`. Baseline is the 339 APP-NOT-IN-BACKEND paths in `docs/release/capability-graph.csv` on `ui/capgraph`.

**177 of the 339 paths are now served by a backend controller. 162 are still missing.**

* By area (served): messaging 46, social/friends 23, city game 17, club 14, topics/routes 13, creator/publishing 11, channels (native) 9, play runtime 8,
  roam 7, native-only 6 (the blacklist routes), profile 5, workshop/mods 4, activities 3, channels 3, templates 2, others 1 each.
* Caveats: `/api/mod-commerce` is mapped only to an explicit OFF handler (closed by owner decision). Two have a verb mismatch:
  `/api/friend/relationship` (app POST, backend GET) and `/api/topic/attribute-author/validate` (app GET, backend POST).
  `/api/play/egg/collect` is mapped but fails on TEST (see `8cb262d27`).
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

1. `ChainPersistenceMySqlIT.sameRequestCannotBeReboundToAnotherMemberOrPayload` fails on `feature/im-channels` itself (unique key vs test expectation). This needs the channels owner.
2. Media upload cannot be verified on TEST (no OSS, no WeChat image check). See the image row in §3.
3. `/api/play/egg/collect` returns 500 because `npc_route_egg` was dropped by owner decision. gaps-a should close the route explicitly (for example 410/404) and update its MAPPING.
4. 14 `*MyBatisTest` classes are not gated and need MySQL on 3306. They pass against be-int-mysql. `MapPublicLayersMySqlIT` depends on the JVM timezone.
5. Pre-existing and not new here: `CommunityPostServiceImpl.runMaintenance` throws `NumberFormatException: "false"` when an erasure job row exists
   (`legal_hold` read as Boolean). It was already in the previous TEST deployment's log (`fix/userinfo-role` on `cy_e2e_beint`).
