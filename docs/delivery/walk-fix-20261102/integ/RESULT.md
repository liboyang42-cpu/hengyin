# Round 6 integration result, 2026-11-03

Branch `integ/launch-test-20261103`, from master `c6eac3b8c`. Draft PR #723 `[TEST ONLY, do not merge] launch integration 2026-11-03`. Nothing was pushed to master, no PR was merged, nothing in production changed.

## Merge commit per lane

All are real `--no-ff` merges of the exact commits from ROUND6, made in its order and pushed after each one.

| # | Lane | Commit merged | Merge commit | Conflicts |
|---|---|---|---|---|
| 1 | pay-gate #718 | `8712fa452` | `7e6ed8ef4` | none |
| 2 | l1-play #713 | `14312f809` | `9661502fc` | none |
| 3 | l2-explore #714 | `6fbde34a1` | `7b91e7a53` | yes, see below |
| 4 | l3-sell #715 | `6621c8672` | `1aa88d12a` (+ `3e070a5e5` ledger count) | yes |
| 5 | l4-counter #717 | `48bc8a920` | `9803298f7` | generated files only |
| 6 | l5-onboard #712 | `d0383fb43` | `151350bb1` | yes |
| 7 | l6-club #716 | `ef03e7a3b` | `0f7551cc9` | yes |
| 8 | l7-launch #720 | `8ecb80e56` | `edaf10fdd` | yes |
| 9 | l8-safety #721 | `bc86e52a6` | `c6e38832d` | `ci/migration-dependencies.json` only |
| 10 | club broadcast #710 | `beec06ee8` | `c0fd49145` | ledger only |
| 11 | page polish #719 | `a7c3624cf` | `65a3269d3` | one wxss block |

After the merges:
- `441bd89e7` and `aa21e8ebe`: the self-play quote fix (below).
- `2da689994`: two backend test fixtures aligned to the merged behaviour (below).

Nothing was left out. #710 and #719 merged without needing a decision.

## Each real conflict and what was kept

- **`ApiClubLeadController.java`** (l1 × l2): both injected fields are kept (`advancedGameRuntimeService` and `playNodeAccessService`).
- **`pages/play/index.wxml`** (l1 × l2):
  - l2 moved the whole story-stream block (`chfull__stage`, P-28: tools layer lifted to page level). l1 had edited two lines inside that block: the `kit` / `journeyCheck` top padding with `--chfull-fade-end` and `--pk-es-*`. Both edits were carried into the moved block; I checked that nothing else differs from l1's version.
  - Gather screen: kept l1's three-dot wait (P-13 led sessions) and l2's `!lead.freeEntry` condition (P-13 free exploration).
- **`pages/play/index.js`** (l1 × l6): the lead payload keeps l1's `skipRequests` (P-23) and l6's `directorRun` / `ended`.
- **`CmsRegistrationServiceImpl.java`** and the C-22 files (l2 × l3, with pay-gate already in):
  - **C-22, ticket limit.** l3 built the LANES.md default: limit per session. l2 built the later ROUND3 owner instruction: remove every 「限 1 张」 rule, including the four-shop day, on the server too.
    - The later owner instruction wins. Kept l2's `ExploreSaleSnapshotGate.assertSnapshotFrozen`.
    - Not carried in from l3: its two per-session limit queries (`selectLiveExploreTicketIdsForUpdate`, `...InActivityForUpdate`) and the limit-code messages.
    - Kept from l3: `checkoutRefusalMessage` with the `SALE_SNAPSHOT_MISSING` sentence, used on both quote and create.
    - l3's gate test was rewritten to prove the merged rule: no per-account count, and only the topic lock is taken. l3's consent-gate test now checks the `SALE_SNAPSHOT_MISSING` sentence instead of the removed limit message.
    - **The owner should confirm this reading of C-22.**
  - **Quote refund clock.** pay-gate made the free-exploration ticket refund tiered, clocked from the ticket start time. l3 (P-07) made the quote read the same frozen point that create stores. Both are kept: the quote now calls the same `RefundPolicy.snapshotOnPurchase(kind, start, end)` that create calls.
    - Tiered snapshot: the tiers are clocked from the ticket start.
    - Old-style snapshot (ticket has no start time): the quote states the frozen validity end, 100% before it and 0% after.
    - `item6-nonrefundable-confirm.test.js` keeps every pay-gate assertion, and l3's assertions were rewritten onto the merged code.
- **`scene-member-order-detail/index.js`** (pay-gate × l3): both lanes fixed the same P-07 bug (after a refund the progress fell back to 「等待支付」). Kept pay-gate's code, which has the owner's final words 付款成功 → 正在退款 → 退款成功. l3's test now uses those words and injects `isCityOrder` (P-20). It still asserts that no 「等待支付」 appears after a refund.
- **`pages/publish/activity/index.{js,wxml}`** (pay-gate × l3): pay-gate removed the merchant 「票价只能是 0」 block. l3 (round 4) removed the toast after `validateForm` and gave the inline error `kind="error"`. Both are kept, and the free-only block stays removed.
- **`pages/merchant/apply/index.{js,wxml}`** (l3 × l5):
  - Kept l3's round 5D behaviour: red text under each field and a scroll to the first one.
  - Added l5's required 行业类型 (M-16) as a field with its own anchor `apply-field-industryIds` and its own red text, cleared when the industry is confirmed.
  - Kept l5's M-19 scroll to `#apply-identity` for the case where only the separate consent is missing.
  - Tests changed to match the combined behaviour, with no check removed:
    - l3's r5d test loads the industry list, asserts the new red line, and the payload has `categoryIds` in place of `preference` (l5 replaced that field).
    - l5's M-19 test: with the name missing, the first red field is the 姓名 row inside the identity block, so the scroll lands there. The test checks the row is inside the block.
- **`pages/club/create/index.js`** (l3 × l6): changing the name both voids the last name check (l3 C-14) and clears the submit-time name-taken error (l6). Written as two literal `setData` calls so the UI gate needs no new entry.
- **`pages/index/index.js`** (l3 × l7): kept l3's `recordHomeCityConsent` extraction (P-38) and l7's 「暂不定位」 memory (`saveLocationDeclined`).
- **`pages/club/event-ops/index.wxml`** (l6 × l7): kept l6's `displayName` and call button, and l7's `dl.waitlist(...)` label.
- **`baoming.wxss`** (l3 × #719): #719 deleted the `paysuc__*` styles, and no file in the mini program references them, so the deletion stands. Kept l3's 参与人 required mark and red-text styles.
- **#710**: only adds a `MEMBERS` broadcast target (new SQL `insertBroadcastMemberDeliveries`, scoped to paid members of the same session). It does not touch lane code. Results: 730 club/director/broadcast unit tests pass, `GameSessionRuntimeServiceImplTest` 135/135, `GameSessionRuntimeMapperSqlTest` 25/25.
- **#719**: on the 21 files lanes also touched it only changes polish (skeletons, 「—」 for missing data, removed captions). I read each hunk, and none reverts lane work.
- **Generated files:**
  - `action-ledger.json`: rebuilt with `build-action-ledger.js --write` after every merge. The counts in `action-ledger.test.js` were set to the rebuilt values, each with a note. Final: 5624 controls, 2455 local tap, 2732 event-contract.
  - `ui-integrity-baseline.js`: both sides' entries kept, anchors re-pointed by matching the same line text (plus the lines either side) in the merged file, duplicates removed, digests recomputed with the gate's own algorithm. No new exemption was added.
  - `ci/migration-dependencies.json`: both sides' entries kept.

## Fixes made on this branch

**Self-play quote** (`441bd89e7`, `aa21e8ebe`, commits of their own).
- The bug: `/api/registration/quote` with ownerType=1 priced the theme ticket, while create always goes through `createSelfPlayPassInternal` and charges `selfPlayPrice`.
- The fix: the quote now runs `quoteSelfPlayPass`. It uses the same price (club benefit applied), no points (create never deducts points), the same signature format as the idempotent replay, and the same charge gates as create (`assertCollectible`, `assertFeeRateValid`). The second commit adds the fee-rate check after the rig showed a quote passing where create refused.
- The refund fields come from the same pass snapshot create freezes (`RefundPolicy.snapshotSelfPlayPass`): deadline = start − 24h, and the percent and refundable flag follow the three tiers.
- Create is unchanged.
- Test: `ClubOperationBenefitTest.selfPlayQuoteReturnsWhatCreateCharges` failed on the old code (「报价 39.99 必须等于下单实收 29.99」) and passes now.
- Two existing tests had the old behaviour built into their fixtures (a topic-level quote on a topic without self-play) and were updated to real self-play topics: `TopicPurchaseGateTest` (still tests the takedown gate) and `FreeUgcActivityRegistrationGateTest.freeTopic_topicLevelQuote_doesNotRequireSelling`.
- **Rig proof over HTTP** (`proof/selfplay_quote.py`, `proof/selfplay_quote_start-1.*`, `proof/selfplay_quote_start+3.*`):
  - Club 7002's 主理人 created an ORIENTEERING theme (self-play 29.99, theme ticket 39.99) through the real endpoints.
  - Fixtures, all printed in the logs: an approved cover asset, the same way the candidate's own `chain_platform.seed_approved_cover` makes one, and the theme put on sale.
  - Player 9009: quote → create → pay through the real callback, then a database read-back.
  - Both runs PASS: quote `payAmount` 29.99 = `payable_amount` 29.99, points 0 = 0, quote `refundDeadline` = frozen start − 24h.
    - Theme already started: 0% and not refundable.
    - Theme starting in 3 days: 100% and refundable.

**Cross-lane test fixture** (`2da689994`): `ApiTopicPostReviewTest.cleanPublishLeavesReviewFlagAlone` failed on the merged tree.
- Why: since l3 P-17 every publish carries a cover, and l8 holds a topic whose media can't be sent for checking. The test's mock said media could not be sent.
- This class only tests text review, so the fixture now marks the cover as checkable.
- The product code is unchanged.

## Migrations new against master, in apply order

The order is the shared ledger's topological order (`ci/migration_ledger.build_manifest`, the same order `build_schema.py` uses), shown as position out of 592:

1. (77) `migration_20261102_merchant_industry_categories.sql`: l5, `sys_category`
2. (78) `migration_20261103_content_safety_recheck.sql`: l8, new `content_safety_recheck` + `sys_job`
3. (79) `migration_20261103_sensitive_content_library.sql`: l8, new `content_sensitive_*` tables + `sys_config` / `sys_job` / `sys_menu`
4. (107) `migration_20261102_club_session_gather_point.sql`: l6, `cms_activity`
5. (387) `migration_20261103_settlement_rule_three_percent.sql`: pay-gate, `settlement_rule`
6. (392) `migration_20261102_club_checkin_phone_visible.sql`: l6, `club_role_assignment`

- Every file is byte-identical to its lane's file.
- No two lanes touch the same table. The only shared table is `sys_job`, used by two migrations from the same lane, l8.
- On the clean rig DB: `build_schema` replayed 300 migrations after the snapshot with **0 migration-stage problems**, ending at 457 tables. I read back the new tables and the new `club_role_assignment.customer_phone_visible` column.

## Release order and rollback (Round 12, k1 item 10; supersedes both lists below)

This replaces the Round 10 table below, which left out three files (r1 F1) and had stale ledger numbers. The order is
`python3 ci/release_manifest.py diff --base c6eac3b8cebf7311e8f00f208d294c3c8212aaec --head <release commit>` at k1's
commit: 14 files ADDED against master, 0 modified, 0 deleted, out of 600. Re-run that command on the exact release
commit: other Round 12 lanes may still add files, and every new file must appear here with a phase.

| Ledger order | File (`migration_…sql`) | Phase | What it changes on production data | On rollback to the old jar |
|---|---|---|---|---|
| 77 | `20261102_merchant_industry_categories` | pre-deploy | `sys_category`: unused type-1 merchant-like rows become type 6; missing type-6 rows inserted | stays; old jar reads it |
| 78 | `20261103_content_safety_recheck` | pre-deploy | new table `content_safety_recheck` | stays, unused |
| 79 | `20261103_sensitive_content_library` | pre-deploy | 4 `content_sensitive_*` tables, `sys_config content.sensitive.rescan.remove=false`, 2 `sys_menu` rows granted to nobody | stays, unused |
| 80 | `20261104_content_safety_jobs_post_deploy` | **POST_DEPLOY** | 2 `sys_job` rows, paused | **delete both rows before starting the old jar** (see below) |
| 81 | `20261104_invitee_signup_points_off` | pre-deploy (safe either side) | `cms_points_setting` id 6 → `status 0, value 0` | stays; rule 6 is off on both jars (`value = 0`) |
| 82 | `20261104_merchant_industry_categories_readback` | pre-deploy | read-only assertion | – |
| 83 | `20261104_topic_total_time_seconds` | **POST_DEPLOY** (k1 item 10, r1 F2) | `cms_topic.total_time` of editor-made themes: minutes → seconds | stays in seconds; the old jar writes minutes again on save. Going forward again, re-run this file: it now converts only rows still in minutes and is a no-op on rows it already converted |
| 84 | `20261105_welcome_points_fifty` | pre-deploy (safe either side) | `cms_points_setting` id 1 `value` → 50 | stays; the old jar credits 50 to the balance and writes the ledger line from rule 1 (now 50), so they agree on both jars |
| 112 | `20261102_club_session_gather_point` | pre-deploy | `cms_activity` lat/lng backfill for open future club sessions | stays |
| 113 | `20261104_club_session_gather_point_readback` | pre-deploy | read-only assertion | – |
| 393 | `20261103_settlement_rule_three_percent` | pre-deploy | generic `settlement_rule` → 3% / 97% / 0 | stays; the old jar settles at 3%. Undo with the file's own 回退 line only if the owner wants 10% back |
| 398 | `20261102_club_checkin_phone_visible` | pre-deploy | new column `club_role_assignment.customer_phone_visible` | stays, unused |
| 431 | `20261104_merchant_activity_limit` | pre-deploy | `user_permission` merchant default → `1 / 3` | stays. On the old jar `QuotaServiceImpl` counts every undeleted activity ever, so a merchant with three past activities is blocked there (before the release they were blocked anyway at 0) |
| 572 | `20261104_review_wording_seeded_text` | pre-deploy (safe either side) | seeded 盲盒 / 探店日 / 竞猜 text; `sys_job` predict job name and remark only | stays |
| (74) | `20261101_author_write_receipt` | pre-deploy, **only if production lacks it** | new receipt table | stays. It is on master but not in the rollback jar `014fc0d83`; r1's read-only precheck [1] says whether production has it |

### Behaviour changes with no migration (r1 F3)

- **Points 停用 switch.** From the moment the new jar starts, a rule with `status ≠ 1` or `del_flag ≠ 0` pays nothing.
  This now covers rules 1 (注册赠送), 2, 3, 4 (评价/投票), 5, 6, 7 (推荐商户注册) and the `event_type` rules 11, 12, 13
  (k1 item 11; 2/3/5/6 were h3). Before deploying, read `status` for ids 1–7 and the event-type 11/12/13 rows
  (r1 precheck [5]): a row that is disabled today with a value above 0 **pays today and stops on the new jar**, and pays
  again after a rollback. Production has them all on, per the owner.
- **Platform rate.** A missing `settlement_rule (merchant_id=0, biz_type=topic)` row now refuses pricing and fee
  snapshots instead of silently using 10% (k1 item 7). Production has the row (3%); r1 precheck [3] confirms it.
- **14 days.** Settlement and payouts wait 14 days after the event ends (k1 item 2). Rows already written with a 7-day
  `coop_settlement.payable_time` keep that date; only settlements run by the new jar use 14 days. On rollback the
  old jar waits 7 days again.

### Deploy order

1. Owner runs r1's `production-readonly-precheck.sql` (read-only) and keeps the output. Stop if production is not at
   master, if any pre-assertion would fail, or if precheck [5] shows a points row that pays today and would stop.
2. **Old jar still running:** apply, in ledger order with `ci/run_migration.py`, every pre-deploy file above:
   77, 78, 79, 81, 82, 84, 112, 113, 393, 398, 431, 572 (+74 if precheck [1] says it is missing). Each runner assertion
   must pass.
3. **Ship the mini-program together with the jar** (r1 F5): the old bundle hides a 自由探索 ticket from 票夹 after its
   first shop scan, and there is no server-side minimum version. Submit the new bundle for review so it can be released
   the same day the jar goes live, and release it as soon as the jar is healthy.
4. Deploy and start the new jar. Wait for `Started RuoYiApplication` and `/actuator/health` UP.
5. **New jar live:** apply 80 (two jobs, paused) and then 83 (`total_time`). Both are re-runnable.

### Rollback to the old jar

1. Stop the new jar.
2. Before starting the old jar: `DELETE FROM sys_job WHERE invoke_target IN ('contentSafetyRecheckTask.run()', 'sensitiveContentRescanTask.run()');`
3. Start the old jar. Schema and data changes stay (see the last column of the table). Decide per row whether to run a
   file's own 回退 line; none of them stops the old jar.
4. Forward again: new jar, then 80 and 83 again.

## Release order and rollback (Round 10, f1 item 9; supersedes the list above for deployment)

The list above is the Round 7 snapshot. Round 10 moved the two `sys_job` inserts out of the l8 files and added
new files. Migrations new against master `c6eac3b8c` as of Round 10 f1-A, in ledger order (`ci/migration_ledger.py`):

| Ledger order | File | Phase | What it changes on production data |
|---|---|---|---|
| 77 | `migration_20261102_merchant_industry_categories.sql` | pre-deploy | `sys_category`: unused type-1 merchant-like rows become type 6; inserts missing type-6 merchant industries |
| 78 | `migration_20261103_content_safety_recheck.sql` | pre-deploy | new table `content_safety_recheck` only (no `sys_job` any more) |
| 79 | `migration_20261103_sensitive_content_library.sql` | pre-deploy | 4 new `content_sensitive_*` tables, 1 `sys_config` row, 2 `sys_menu` rows (no `sys_job` any more) |
| 80 | `migration_20261104_content_safety_jobs_post_deploy.sql` | **POST_DEPLOY** | 2 `sys_job` rows, both paused (`status='1'`) |
| 81 | `migration_20261104_merchant_industry_categories_readback.sql` | pre-deploy | read-only assertion |
| 109 | `migration_20261102_club_session_gather_point.sql` | pre-deploy | `cms_activity` lat/lng backfill for open future club sessions with no coordinates |
| 110 | `migration_20261104_club_session_gather_point_readback.sql` | pre-deploy | read-only assertion |
| 390 | `migration_20261103_settlement_rule_three_percent.sql` | pre-deploy | generic `settlement_rule` rows to 3% / 97% / 0 |
| 395 | `migration_20261102_club_checkin_phone_visible.sql` | pre-deploy | new column `club_role_assignment.customer_phone_visible` |
| 428 | `migration_20261104_merchant_activity_limit.sql` | pre-deploy | `user_permission` merchant default row: `can_create_event=1, max_events=3` |

`migration_20261101_author_write_receipt.sql` is on master already; it is part of this release only if production
does not have it yet (r1 F4: the rollback jar `014fc0d83` predates it).

### Deploy order

1. While the **old jar keeps running**, apply every pre-deploy file above in ledger order (never `ls` order), with the
   runner (`ci/run_migration.py`), each runner assertion passing. None of them touches `sys_job`, so the old jar's
   scheduler keeps running.
2. Deploy and start the new jar. Wait for it to be healthy (`Started RuoYiApplication`, `/actuator/health` UP).
3. Only then apply `migration_20261104_content_safety_jobs_post_deploy.sql`. It registers
   `contentSafetyRecheckTask.run()` and `sensitiveContentRescanTask.run()` **paused**. An operator enables them in
   the admin job page after reading them back.

Why the post-deploy step must wait: `SysJobServiceImpl.init` (startup) and `MySqlSchedulerCoordinator` (every 2 s)
validate the bean of every `sys_job` row whatever its status. A row naming a bean the running jar does not have
stops that jar's whole scheduler (r1 F2) and stops the old jar from booting (r1 F1).

### Rollback to the old jar

1. Stop the new jar.
2. **Before starting the old jar**, delete the two rows:
   `DELETE FROM sys_job WHERE invoke_target IN ('contentSafetyRecheckTask.run()', 'sensitiveContentRescanTask.run()');`
3. Start the old jar. The pre-deploy schema changes stay; the old jar boots and serves on them.
4. Going forward again later: start the new jar, then re-run the post-deploy file (idempotent; the tasks come back paused).

### Rehearsal (f1-A, real jars, scratch MySQL 8.0.45 + Redis on loopback)

Old jar built from `origin/master` `c6eac3b8c` (git archive); new jar from the candidate tree with these changes.
Baseline: master's own `build_schema.py` + `seed_fixtures.py`, plus master's three post-deploy job files (production has
those), plus fictional rows (two club sessions, generic 10% `settlement_rule` rows). Evidence in
`proof/round10-f1a-release/` (shell times in `steps.log` are UTC; app log times are +08:00).

| Step | Result | Evidence |
|---|---|---|
| Old jar running, scheduler active | PASS: jobs logged at 16:45:00 | `old-1.excerpt.log` |
| A. Pre-deploy files applied while the old jar runs | PASS: all assertions `readback=1`; 9801 filled, ended 9802 untouched; merchant 1/3; 3% rules; 0 new `sys_job` rows | `steps.log`, `readback-A.txt` |
| B2. Old jar keeps its scheduler | PASS: runs at 16:46 and 16:47, no 「调度主会话不可用」 | `joblog-B2.txt` |
| Negative control: post-deploy file applied while the old jar runs | reproduces r1 F2 within 1 s (「调度主会话不可用」); deleting the two rows brings the scheduler back (16:47:15 started, runs at 16:48) | `old-1.excerpt.log` |
| B1. Old jar boots on the migrated schema | PASS: ready in 50 s, no job-target error | `old-2.excerpt.log` |
| C. New jar comes up | PASS: ready in 40 s | `new-1.excerpt.log` |
| D. Post-deploy file applied with the new jar live | PASS: both rows `status=1`; re-run idempotent (still 2 rows); no run of either task across their 16:51:21 / 16:52:33 slots while 8 other jobs ran; no scheduler error | `readback-D.txt`, `joblog-D.txt` |
| Negative control: old jar with the two rows present | fails to boot: 「任务调用目标或参数无效」 (r1 F1) | `old-3-neg.excerpt.log` |
| E. Rollback: delete the two rows, start the old jar | PASS: ready in 50 s | `old-4-rollback.excerpt.log` |
| Forward again: new jar + re-run post-deploy | PASS: rows back, paused | `new-2.excerpt.log`, `steps.log` |
| Negative controls for the two read-back files | gather point: session 9801's coordinates cleared → `readback=(0,)`, exit 1. Categories: type-6 rows hidden and ten type-1 「花艺N」 rows added → the old l5 assertion still passes (`1`), the new one fails `readback=(0,)`. Both pass again once restored. | run log in the f1-A report |

Not covered: production's real applied state and row contents (owner's read-only precheck); a slow or failed deploy
of the new jar under real traffic; enabling the two tasks and their actual runs.

## Test counts per lane, before and after

From `proof/lane-test-counts.py`: for each lane, the test files it added or changed, counted in the lane's own commit and in the merged tree.

| Lane | Test files | Lane | Merged |
|---|---|---|---|
| pay-gate | 28 | 307 | 309 |
| l1-play | 24 | 394 | 394 |
| l2-explore | 28 | 335 | 339 |
| l3-sell | 49 | 457 | 458 |
| l4-counter | 23 | 275 | 275 |
| l5-onboard | 15 | 229 | 229 |
| l6-club | 38 | 558 | 562 |
| l7-launch | 14 | 215 | 215 |
| l8-safety | 31 | 423 | 423 |
| #710 | 4 | 249 | 255 |
| #719 | 3 | 82 | 82 |

- No lane lost a test file.
- l3's `ExploreAccountTicketGateTest` / `ShopDayOneTicketGateTest` now live under l2's renamed files (`ExploreSaleSnapshotGateTest` / `ShopDayNoTicketLimitTest`), because of C-22.
- The only file with fewer tests is `ApiTopicPostReviewTest`: 8 in l3's version, 7 merged. l8 replaced two old post-review tests with one when it changed the rule, and l3 had only added a cover line. Nothing was dropped in a conflict.

## Local runs on the merged tree

- `node chengyinhub-xcx/tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`.
- `build-action-ledger.js --check`: PASS, 5624 controls.
- Full mini unit suite (`tests/unit/**/*.test.js`): **15803 pass, 0 fail**.
- Backend, `mvn -B test`, all modules: 14345 run.
  - The failures are 13 `*MyBatisTest` classes that need the CI MySQL service ("Communications link failure"), and two upload tests that ran out of heap in the full run (`AudioUploadBoundaryContractTest`, `WxImageSecurityCopyTest`). Both upload tests pass when run alone.
  - The two real failures found (`FreeUgc...`, `ApiTopicPostReviewTest`) are fixed above.
  - All targeted classes around the conflicts and the fix pass: refund, snapshot, gate, consent, club benefit, self-play, money-path, game session.
- **Druid wall** (`proof/IntegWallCheck.java`, `proof/mapper_wall.log`):
  - Covered: all 47 mapper statements added or changed against master across all lanes, parsed by MyBatis from the built jar and checked by Druid's MySQL wall with the app's config (multi-statement off).
  - Result: 50/50 rendered variants pass.
  - Negative controls (l8's `BINARY word = BINARY word`, `AND 1 = 0`, multi-statement) are refused. `CAST(x AS BINARY)` passes.
  - Unlike l8's proof this is a parser-level check and does not execute the statements. The rig HTTP runs above do go through the app's own wall data source.

## Rig

- Built from `claude/cloud-walk-20261101-rig` with the club lane's `tools/harness-patches.diff` applied and `up.sh` pinned to `aa21e8ebe`.
- Docker Hub rate-limited the Redis pull, so dockerd was restarted with the `mirror.gcr.io` mirror (same pinned digests), as pay-gate did.
- Migrations as above. The jar boots ("app ready in 28s").
- One login each, over HTTP (`proof/logins.log`) and in the browser runtime (`proof/shots/login-*.jpg`), all with 0 page errors. The only console errors are missing banner gifs and the favicon, which are rig harness gaps.
  - player 9004 → home
  - merchant 9002 → 商家工作台
  - club lead 9003 → club 7001 page
- No walk was done; that is the next round.

## Main package and preflight

- Main package: **3.42 MB uncompressed** (master `c6eac3b8c`: 3.40 MB).
- Compressed: **about 1.44 MB** (master about 1.43 MB). This is a per-file deflate estimate over the same file set as `mainpkg-size-report.js` (`proof/mainpkg-deflate-estimate.js`), not WeChat's own build.
- `scripts/preflight-release.js` BLOCKs on the merged tree:
  1. 「主包未压缩 3.42MB > 棘轮 3.00MB」
  2. 「Action Ledger 动态证据未完成:pending=5624,nonPassed=0」

## Architecture gate proof

- Verifier: reviewed commit `bf6282ef7`. Context: a simulated `pull_request` event (head = candidate, base = master `c6eac3b8c`). Script and outputs in `proof/arch-gate-*`.
- **Without the patch:** `BLOCKED`, exactly 7 `NEW_UNOWNED_BOUNDARY`: `ContentSafetyRecheckMapper`, `ContentSensitiveMapper`, `IContentSafetyRecheckService`, `ISensitiveContentRescanService`, `ContentSafetyRecheckServiceImpl`, `SensitiveContentLibraryImpl`, `SensitiveContentRescanServiceImpl`. These are l8's.
- **With `l8-safety/round5e/architecture-owner-registration.patch`** applied to throwaway copies of both the trusted verifier and the candidate (a local commit that was never pushed): `PASS_WITH_REVIEW_ITEMS`, `findings: []`.
- The patch is **not** on this branch and `ci/architecture/owners.json` is unchanged.
- The gate selftests pass in CI (325 OK). Locally 6 of them error only because this container's clone is shallow.

## CI state

**All CI jobs are green on `a3c9e8fbd`** (run 41628910753, finished 11:50 UTC on 2026-11-03).

- Green: Backend tests, Migration safety gate (with the ③ description), Mini program static gates and static checks, all 20 unit shards, Admin UI build and E2E contract, Migration layout, SBOM + dependency scan, gitleaks, Funds MySQL IT, Followup 39 backend cases, Redis recovery.
- Earlier backend runs on this branch did not count as final:
  - `441bd89e7` failed on the two cross-lane tests fixed in `2da689994`.
  - Later runs were cancelled by the next push.

**Fast gates is green for a reason outside this round.** At 11:15 UTC another Claude session (not this integrator) pushed three commits onto this branch:
- `3cf453808`: merges `arch-owner-l8`, i.e. the l8 owner registration in `ci/architecture/owners.json` plus a re-pin of the trusted verifier in `ci.yml`.
- `59124be29`: merges #666.
- `a3c9e8fbd`: a map font-size fix.

ROUND6 said not to put the owner registration on this branch, so the architecture gate is green here only because of that push. I did not revert those commits; the owner decides. Without them the gate behaves as shown in the proof above: blocked, 7 boundaries.

## Could not verify

- Nothing was run on a real phone, and no screenshot here is from a phone.
- MySQL `*MyBatisTest` / `*MySqlIT` classes did not run locally; CI runs them.
- The rig's anonymous `GET /api/city/nodes` probe returned 401. I did not investigate it this round.
- WeChat's own compressed main-package size: only estimated.

STATUS: COMPLETE

## 2026-11-05 optional chapter video addition

Before starting the new backend, apply `chengyinhub-admin/sql/migration_20261105_chapter_video.sql` through the existing migration flow. It adds nullable `video_url` and `video_poster_url` to `cms_topic_chapter`, is repeatable, and rewrites no existing rows. Then start the backend, then release the client. Old code ignores these columns; rollback keeps them and the authored media. No migration or deployment was performed on production by the video worker. Theme music uses existing columns.
