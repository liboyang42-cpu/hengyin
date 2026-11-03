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
