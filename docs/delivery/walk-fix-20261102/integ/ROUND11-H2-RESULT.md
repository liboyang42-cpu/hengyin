# Round 11, lane h2-owner-answers: result

Branch `integ/launch-test-20261103`. My three code commits are `bab98ad56` (item 1), `9eb752d8d` (item 2) and `5480dd572` (item 3). Other lanes pushed at the same time.

For local proof I used a throwaway MySQL 8.0.46. I started it from the Ubuntu package files in the session scratchpad and deleted it afterwards; nothing was installed system-wide. CI uses Oracle MySQL 8.0.45. Nothing touched production.

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Revoked per-shop consent hides the row on 客户 | fixed |
| 2 | Rename stored platform-seeded text at release | fixed: new migration, rehearsed twice, PR #723 ③ updated |
| 3 | Editor label 「抽卡」 becomes 「翻卡」 | fixed |
| 4 | Points 停用 switch | not done, as instructed |

## 1. Revoked consent hides the row: fixed

**What changed.** `MerchantCrmAppMapper.xml` `OwnedRegistrationScope` drops a registration in one case. The shop must be a participating shop on that 自由探索 ticket that isn't the host (the existing `FreeExploreShopRegistration` rule). And the player's latest `merchant_onsite_data_sharing` / `merchant_redeem` / `MERCHANT` consent for that shop must be `REVOKE`.

- "Latest" uses the same order as `ConsentRecordMapper.selectLatestScoped`: `occurred_at desc, id desc`.
- The onsite roster (`ShopDayMerchantRosterServiceImpl`) already skips REVOKED rows, so the two now match.
- Every query built on the roster uses that one fragment, so all of them follow:
  - customer list, count and segment counts;
  - contact reveal (now 404 for that player);
  - campaign recipients and broadcast candidates;
  - customer profile upsert and stale marking;
  - summary and interactions.

**What did not change.**
- The host's view: the fragment never applies to the host.
- Plain themes.
- Phone rules.
- Marketing opt-in.

**One case to know.** The rule works per ticket. If the same player also bought something directly from that shop, such as the shop's own activity, they still appear for that purchase. They are still that shop's customer.

**Files.**
- `MerchantCrmAppMapper.xml`
- `MerchantCrmFreeExploreRosterMySqlIT.java`, extended
- `MerchantCrmProfileNamespaceMyBatisTest.java`: the H2 fixture gains the real `consent_record` table, which the roster SQL now reads.

**Proof.** `MerchantCrmFreeExploreRosterMySqlIT` on real MySQL: 2 tests pass, including the Druid wall check on the changed statements. The new steps:
- **Revoke:** shop 66 no longer lists the buyer. The total drops from 2 to 1, `countOwnedRegistrationCustomers` agrees, and reveal returns 404.
- **Other shops:** the chapter-approved shop 68 still sees the buyer. The host still sees the masked phone, even with a host-scoped revoke in place.
- **Re-grant:** a new grant at the same instant with a higher id brings the row back, still without a phone. The count returns to 2.
- **Revoke again:** the row disappears again. The rule looks at the latest record, not at any past revoke.

Negative control: with the old XML the IT fails on 「撤回单店同意后参与商家不该再看到这位买家」.

Targeted backend suites (CRM, ShopDay roster, project player read, outcome rules, journey story, advanced game): 692 pass.

**For the owner.**
- The player-side list of shops they can opt into marketing from (`MerchantCrmMarketingConsentMapper.EligibleMerchantScope`) still lists a shop the player has revoked. I left it because the brief scoped this item to 客户 and what's built on it. Opting in there has no effect while the row is hidden, because broadcasts read the roster. **Recommended default:** apply the same rule there too, so a player isn't offered a choice that does nothing.
- The onsite roster also hides a row when the consent service is unavailable. The 客户 SQL hides only on an explicit revoke.

**Unverified:** a walk in the rig or on a phone. The IT covers the read-back.

## 2. Rename stored text: fixed

**What changed.** There is one new file, `chengyinhub-admin/sql/migration_20261104_review_wording_seeded_text.sql`, and no old file was edited.

- **Which rows.** It only changes platform rows whose value still equals the seed text exactly. Platform rows means `member_id = 100000` plus the seed `create_by`; for menus it is the row's `perms`; for the job it is `invoke_target`. The comparison uses `COLLATE utf8mb4_bin`, so a changed space or letter case counts as an edit.
- **What it leaves alone.** Text an admin or author changed, merchant copies (`create_by = 'system_copy'` or not platform-owned), and the platform's own copies.
- **Templates (盲盒 → 随机卡):**
  - Template 「三个盲盒」 → 「三个随机卡」. Its description 「三个「盲盒袋子」…」 → 「三个「随机卡袋子」…」.
  - Its nodes 「盲盒一:观察 / 二:拍照 / 三:开口」 → 「随机卡…」.
  - Games 「玩法包·盲盒之观察 / 拍照 / 开口」 → 「玩法包·随机卡之…」, plus the four game texts that say 盲盒: two questions, the photo prompt and the answer reveal.
  - Chapter 「盲盒衣柜」 → 「随机卡衣柜」 under 穿搭盲配.
- **Covers.** `migration_20260822_template_cover_images` writes `img_url` onto the row by name. After the rename the row keeps its `img_url`, so the cover still shows. The cover image itself has no text.
  - `docs/assets/template-covers/manifest.json` and `ci/test_template_cover_contract.py` record that historical migration's map, so I left them as they are.
- **sys_menu (探店日 → 自由探索):**
  - The 6 names from the three files you listed.
  - The 4 「探店日批5:」 remarks.
  - Under the same rule, 4 more remarks: 探店日5-5, A3 and two A4. These come from `..._roam_revisit_report_permission`, `..._player_cooldown` and `..._club_payee`. Say if you want those four left out.
- **sys_job:** 「竞猜轮次超时作废」 → 「预测轮次超时作废」, and in the remark 竞猜答案 → 预测答案. `invoke_target`, id, group, cron and status stay the same. Quartz keys jobs by id and group, and the new jar reloads them from the table on start.
- **Safe to re-run.** Each UPDATE carries the old value, so a second run matches 0 rows. Three runner `scalar-true` read-backs check that no platform seed row still holds an old value. They also pass on a database that has none of these seeds.
- **Registration.** The file is registered in `ci/migration-dependencies.json`, after all ten source files.
- **PR #723 ③.** The section now lists it as item 11. It is data only, so it can run before or after the jar, and the section gives the rollback (swap old and new values).

**Rehearsal, twice, on throwaway databases** (read-back in `integ/proof/round11-h2/rehearsal-readback.txt`):
1. **Full chain.** `scripts/e2e/build_schema.py` rebuilt the base schema plus all 306 later migrations in ledger order, including this one. Result: 0 problems at baseline, seed or migration stage. The 4 `DATA_ASSERT` lines belong to other files that need production rows; they are not this file's. Then I ran this file a second time on the same database: ok, no issues, and every touched table was byte-identical before and after.
2. **New `ci/test_review_wording_seeded_text_mysql_integration.py`.** It is now in CI's atomic-migration gate, and the floor rises from 78 to 80 tests, with a contract test that 79 fails. The test builds the base schema, runs the two real playpack seed files and the predict job registration unchanged, and inserts the 14 menus with their original text. It also adds an author-edited node, an author-edited photo prompt, an admin-written 探店日 menu, a merchant copy and a platform copy. Then it runs the migration twice.
   - The platform seed rows are renamed.
   - The second run changes nothing.
   - The edited rows, copies, `invoke_target`, id, cron and status are all unchanged.
   - A negative control sets the old job name back and confirms the assertion reads false.
   - Locally: 2 tests pass.

Gates: `migration-safety-check.sh`, `migration-layout-check.sh`, `test_migration_ledger.py`, `test_migration_gates.py` and `test_migration_runner_ci_contract.py` all pass.

**Author-owned copies: count for the owner.**
- I can't read production, so I can't give you the real count.
- `integ/proof/round11-h2/stored-text-precheck.sql` is read-only. Section ④ counts the topics, nodes, chapters and games that copy this 盲盒 text and that the migration won't touch. It also counts any 探店日 menus or 竞猜 jobs left after the migration, which would be rows someone edited by hand.
- On the rehearsal database every count is 0.
- Please run it on production before the migration and send me the four ④ numbers.

**Not changed, for the owner:**
- The sys_config remark 「探店日A3：每笔正向投诉补偿…」 (`withdrawal.player-complaint-cooldown-hours`). You named sys_menu only. Recommended: same rename in a follow-up file.
- Stored game text that uses 抽 as a verb, which is outside these words: batch2 「玩家抽卡背诵」 and b27 movement_bingo 「抽卡者」.
- `ci/verify-playpack-batch2-readback.sh` is a manual, one-time read-back of the 08-21 deploy. It still looks for 「盲盒衣柜」, so it will fail if anyone runs it after this migration. No CI job runs it.

## 3. 「抽卡」 becomes 「翻卡」: fixed

- **Editor picker.** `node-game-catalog.js`: the game label and the 随机结果 form label are now 翻卡. The sub-caption 「一副卡池抽一张…」 is unchanged.
- **「之后」 hint.** `outcome-after.js` now says 「多张翻卡请使用通过/没通过之后」. Its backend mirror in `GameplayOutcomeRules` now says 「多张翻卡请用顶层通过/没通过之后」.
- **Story validator.** The message in `JourneyStoryValidator` now reads 「…掷骰子 / 翻卡会写」.
- **Editor preview sample.** The bingo cell in `advanced-game-preview.js` is updated, and so is its screenshot fixture in `scripts/fixtures.json`.
- **Unchanged:** comments, identifiers, `gameKey: 'random'`, test names, and the stored seed text listed above.
- **The admin UI** (`chengyinhub-ui`) has no 抽卡.
- **Proof.**
  - New `tests/unit/r11-h2-flip-card-label.test.js`, 3 tests: the picker labels, the JS and Java mirror messages, and a sweep that finds no 抽卡 string literal in `pages/publish`. All 3 fail on the old code.
  - `GameplayOutcomeRulesTest` now expects 多张翻卡, because the owner changed the word.
- **Unverified:** a screenshot. The change is two words in an existing picker. I didn't walk it in the rig.

## 4. Points 停用 switch: not done

Waiting for the owner, as instructed. Nothing touched.

## Verify

- **Mini unit:** 16,123 / 16,124 pass. The one failure is `r11-g3-order-truth.test.js` 「订单详情:自玩通行证…」. It fails the same way with my changes stashed; it is g3's area.
- **Other mini gates:** `ui-integrity.js` prints `UI-GATE-0:PASS`. `build-action-ledger.js --check` passes (5,705 controls; I changed no WXML).
- **Backend:** the targeted suites above, 692 pass. `MerchantCrmFreeExploreRosterMySqlIT` passes against real MySQL.
- **CI:** see below.

## CI

Two `GitHub CI` runs finished on heads that contain all three of my code commits: `559d41330` (run 41905112053) and `bc40b85e0` (run 41909784071). Other lanes kept pushing, so earlier runs were cancelled.

**Green on both runs:**
- Secret scan.
- Migration safety gate and migration layout gate, which cover the new migration, its registration and the PR declaration.
- Fast gates, admin UI build and E2E contract, SBOM.
- All 20 mini unit shards (they include `r11-h2-flip-card-label.test.js`).

**Backend tests: red, not in h2 code.**
- 16,404 tests ran with 0 failures and 1 error.
- The error is `CoopSettlementMapper.selectClubSessionSettlements` on H2: `Table "settlement_detail" not found`. That query is round 11 h4's L7 club settlement change (`31645fb81`).
- The h2 suites in that job passed: the CRM tests, `MerchantCrmProfileNamespaceMyBatisTest`, `GameplayOutcomeRulesTest`, and `MerchantCrmFreeExploreRosterMySqlIT`, which `ci/backend-test.sh` names.
- Because Maven stopped there, the later step "Run localhost atomic migration MySQL IT by exact class" was skipped. **My new `ci/test_review_wording_seeded_text_mysql_integration.py` has therefore not run in CI yet.** It passed locally (2/2), and the CI contract test for the gate (79 tests fail, 80 pass) passes. It will run once h4 fixes that fixture.

**Mini static gates: red, not in h2 code.** U6 flags `RAW_ERROR_TEXT` at `pages/topic/components/cy/session-picker/index.wxml:26 ticketsError` and `pages/topic/pricing/index.wxml:33 teamSizeError`, plus the isolated-unit and dead-data-field gates. None of these are h2 files; I changed no WXML. f3's `bc40b85e0` names the remaining owners as g3 and f2.

**Funds MySQL IT (run 41905111942 on `559d41330`): red, not in h2 code.** This workflow runs none of my tests (its class list is in `ci/funds-mysql-it-gate.sh`). It fails in the Maven step on the club settlement area, the same as before.

A newer run, on h4's `20150c6b1`, was in progress when I wrote this. The h4 error above was still unfixed then.

STATUS: PARTIAL - h2 code is done and its suites pass locally and in CI where they ran; whole-branch CI is red on other lanes (h4 CoopSettlementMapper H2 fixture, g3/f2 U6 WXML), which skipped the atomic-migration step, so the new migration rehearsal test has not yet run in CI
