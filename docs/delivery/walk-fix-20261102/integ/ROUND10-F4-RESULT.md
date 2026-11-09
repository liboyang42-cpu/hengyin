# Round 10, lane f4-review-rules: result

Branch `integ/launch-test-20261103`. Started at `15a95fdd8`, when the owner released f4 to run alongside f2 and f3. My last code commit is `1d9591a1c`. f2 and f3 were pushing during the same time.

Rig results come from the cloud rig, not a phone. That means the real jar on MySQL 8.0.45 and Redis 7.4 (`cy_e2e_51`, seeded by `chains.py player`), and the real mini-program in headless Chrome at 390×844. The rig candidate was `6bec105dd`, which contains all my code commits. Screenshots and read-backs are in `fix-shots/f4-*`, and I looked at every shot.

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Review wording (随机卡 / 预测 / 选) | fixed |
| 2 | Inviter per-signup points (id 6) off | fixed |
| 3 | Paid merchant extras closed at launch | already closed; guard test added |
| 3a | 自由探索 (old name 探店日) buyers on 客户; wording | fixed; 2 points need owner |
| 4 | Main package under 3,145,728 | fixed: 3,105,090 at `b1826d0c2` |
| 5 | f1 leads (refund rounding, chain_postdeploy, order label, countdown, tab bar) | fixed |

## 1. Wording: fixed

Commit `2e92e752d`. Code identifiers such as `betCount` stay. Mirrored messages were changed on both sides, the editor validator and `AdvancedGameConfigValidator`.

- 「随机盲盒」 is now 「随机卡」 and 「已抽 N / M」 is now 「已翻开 N / M」 (`pages/play/components/advanced-game`). The draw button follows suit: 「抽一次」 is now 「翻开一张」 and 「抽取中…」 is now 「翻开中…」.
- Every 「竞猜」 a user sees is now 「预测」. That covers the merchant predict page (预测待答, 登录后查看预测, 当前岗位不能公布预测答案, 没有等你给答案的预测), the workbench todo, the IM entry, the editor catalog (label 预测, sub 「今天选一个，到期商家给答案」), preview, editor and backend validation messages, and runtime errors.
- 「押」 is now 「选」: 「N 个人选了这一轮」, 「这一轮还没有人选」, 「选对的人会按你配的规则拿到奖励」. On the player wheel: 选 {item}, the seal reads 选, 「转到想选的那张，点它」, 选定这一个, 已经选了 / 就选这个, and 「你选的…」 in the reveal. Backend: 「这一轮你已经选过了」, 「…不能再选了」.
- 盲盒 in editor and backend messages is now 随机卡 / 卡片, for example 「卡片 ID 不能重复」 and 「卡片已经全部翻开」. The growth reason 「竞猜猜中」 is now 「预测选对」.
- **No stake.** I read `AdvancedGameRuntimeServiceImpl.submitPredict` and `AdvancedActionJudgement.draw`. A player only picks an option or turns a card. No points or money are taken. Rewards are XP plus the merchant's coupon.
- **Sweep of `盲盒|竞猜|押|下注|赔率|赌|抽奖`.** Every user-visible hit was changed except these:
  - 押金 means deposit, a different word.
  - 赌博 is a moderation category label in the admin sensitive-word page and the normalizer table.
  - The editor catalog label 「抽卡」 is not in the list. I left it; the owner can rename it.
  - Comments, Swagger `@Tag`/`@Operation`, log lines, the `category-icon.js` matcher regex, and tests and mock fixture names (the screenshot fixtures were updated anyway).
- **Stored or seeded text, not changed (for the owner):**
  - `sys_job` 「竞猜轮次超时作废」 and its remark (`migration_predict_void_task_register_20260915`, `_expired_job_20260906`).
  - Seeded templates 「三个盲盒」, 「盲盒一/二/三:…」, 「玩法包·盲盒之…」, 「盲盒衣柜」 (`migration_seed_playpack_templates_20260820`, `..._batch2_figma_20260821`).
  - The cover title 「三个盲盒」 (`migration_20260822_template_cover_images`).
- Proof: editor and play unit tests, 323 pass. Backend `AdvancedGameConfigValidatorTest`, `AdvancedGameRuntimeServiceImplTest`, `Predict*` and `PlayKitRuntime*`: 335 pass. Rig shot `f4-1-01-merchant-predict.jpg` shows 「预测待答 / 没有等你给答案的预测」.
- Unverified on a phone: the wheel and seal at real font sizes.

## 2. Inviter points: fixed

Commit `9fbc174f0`.

- New migration `migration_20261104_invitee_signup_points_off.sql` changes id 6 only: `status=0, value=0`. It has a runner read-back assertion and can be re-run. It is registered in `ci/migration-dependencies.json`, and PR #723 `## 影响分类` ③ now lists it as item 10. id 5 (500 points for a first purchase) is untouched.
- **Why both columns.** The payment callback (`CmsRegistrationServiceImpl.completePaymentSuccess` → `pointsSettingValue`) reads `value` by id and **ignores `status`**. Turning off only the status would keep paying 100 points per signup. With `value=0`, `awardPointsOnce` skips the award. There is no check anywhere that a rule must exist: a missing row reads as 0.
- Screen copy: the 「如何获取积分」 sheet already lists only `status=1` rules (`points-rules.js`), and no screen hard-codes the per-signup reward. So no screen change was needed.
- Proof:
  - `RegistrationFundsMybatisIntegrationTest` on real MySQL gains two tests. After the migration, payment returns SUCCESS, the order is paid, the inviter gets 500 (event 5), and there is no event 6 row. Before it, the same callback gives 600.
  - Migration rehearsal: on a throwaway DB built from `sql/base_schema_20260818.sql` plus rows 5 and 6, run twice. Result: id 6 at 0/0, id 5 unchanged, assertion 1.
  - The repo runner `ci/run_migration.py` refuses any server other than Oracle 8.0.45, and the local server is 8.0.46, so I applied the file with the mysql client. CI runs 8.0.45.
  - Settlement reads only the H8 rule id 15, so it is unaffected.
- Needs owner: the payment path ignoring `status` also applies to ids 2, 3 and 5. The admin 停用 switch does not stop those payouts. I did not change it because it touches money; I recommend making the payout honour `status` once production's status values for 2, 3 and 5 are confirmed.

## 3. Paid merchant extras: already closed, guard test added

Commit `7ceb2cf3f`.

- How they are gated today: `merchant.commerce.self-checkout-enabled` = `${CHENGYIN_MERCHANT_SELF_CHECKOUT_ENABLED:false}`. Nothing in the repo turns it on.
- When the switch is off, `MerchantCommerceService.createOrder` refuses with 「自助开通暂未开放，请联系官方人工开通」 before writing anything. 品牌主页 never self-checks-out. The decor page only offers 「联系运营开通」. This is the only commerce order path; I checked every `setBizType`.
- New `MerchantPaidExtrasLaunchClosedTest` checks that the shipped default stays false and that all four extras are refused with zero mapper calls. No captions were added. The ¥30 referral is untouched.
- Unverified: I can't read the production environment. The owner should confirm that `CHENGYIN_MERCHANT_SELF_CHECKOUT_ENABLED` is not set there.

## 3a. 自由探索 customers and naming: fixed, with owner points

Commit `1a230c185`.

- **Roster.** `OwnedRegistrationScope` no longer excludes `product_type = 2`.
  - 自由探索 tickets sit on the activity shell (`owner_type = 2`, `purchase_kind = 3`), not on the topic. The activity branch now also admits the theme owner and approved participating shops.
  - "Approved" means either `cms_registration_merchant.audit_status = 1, del_flag = 0` or an approved chapter application (`merchant_chapter_application.status = 1, del_flag = 0`). **Needs owner:** the brief named only `audit_status = 1`. In the rig every participating shop of the 自由探索 theme came in through chapter applications and none had an `audit_status` row, so the brief's rule alone would have shown them nothing. Default kept: both count.
  - Shops not on the theme, and pending shops, see nothing.
  - The `hostFreeExploreTopicId` exception is removed (DTO field, mapper query, broadcast service).
  - The player's marketing opt-in list (`MerchantCrmMarketingConsentMapper.EligibleMerchantScope`) uses the same scope. Broadcasts and campaigns still need `merchant_crm_marketing_consent` opt-in, and delivery limits are unchanged.
- **Phones are not relaxed.**
  - A participating shop that is not the host is not the contact owner for a 自由探索 ticket. Players grant that shop a separate per-shop consent (`merchant_onsite_data_sharing`, shown on the theme roster).
  - On 客户 that row shows no phone and 「联系方式在主题名单按客户授权查看」. The reveal endpoint returns 403 and writes the audit row `deny=free_explore_shop`.
  - The host still gets the phone under the usual signup data-sharing consent.
- **Needs owner:** if a player revoked per-shop consent, the onsite roster hides their whole row from that shop, but 客户 still lists them (name only). That follows "see all its paid buyers". Say if a revoke should also hide the row.
- **Wording.** 探店日 is now 自由探索 in:
  - The order detail `typeLabel`.
  - Roam 「看看自由探索」.
  - Admin: the 自由探索俱乐部资金动作 dialog, the repair confirm, and both verification-review texts.
  - Backend: 「该主题不是自由探索活动」, 「该订单没有自由探索完局面」, 「自由探索不支持积分抵扣…」, 「无权查看该自由探索门店名单」, 「自由探索商家名单服务未就绪」, 「自由探索通关来源时间缺失」.
  - The 11 admin operation-log `@Log` titles.

  Comments, identifiers, `@Operation` summaries and logs stay.
- **Stored text, not changed (for the owner):**
  - `sys_menu` names 探店日实际工时确认, 探店日额外工时审批, 探店日质量奖励确认, 探店日质量奖励过错排除, 探店日期级FINAL结算, 探店日已确认工时更正 (`migration_20260816_tandianri_batch3_funds_club_compensation`, `migration_20260817_tandianri_batch3_funds_club_hours_correction`).
  - The `sys_menu` remarks 「探店日批5:…」 (`migration_20260817_tandianri_batch5_admin_menus`) and the 探店日A3/A4 permission remarks.
  - Table comments.
  - Rig seed data names such as 「E2E 探店日一期」 and 「探店日样板咖啡」.
- **Other places that treat `product_type = 2` separately (report only, money rules):**
  - `CmsTopicMapper` settle-topic query (excludes 2).
  - `CmsActivityMapper` settle-activity 自由探索 branch.
  - `ActivitySettlementHoldMapper:93`.
  - `TopicEditionFundsMapper` (自由探索 edition funds).

  These are separate settlement paths, not display. One non-merchant place: `PlayerGrowthMapper.countCompletedCityStations` excludes 2 for player growth. The `purchase_kind = 3` branches in auto-verify and entitlements are functional. On the merchant number screens I found no display that leaves 2 out for no reason.
- Proof:
  - New `MerchantCrmFreeExploreRosterMySqlIT` on real MySQL, registered in `ci/backend-test.sh`. The host sees the scanned buyer with a masked phone and gets plain text from reveal. The approved shop and the chapter-approved shop see the row without a phone, and reveal is 403 with an audit row. The pending shop and an outside shop see zero rows, and reveal is 404. Plain topics are unchanged. The changed statements pass the app's Druid wall config.
  - Negative control: with the old XML, the IT fails.
  - All CRM tests: 197 pass, plus the contact-reveal IT. Tests that pinned the old exclusion were removed or updated (`MerchantCrmHostFreeExploreSqlTest`, the broadcast `ownFreeExploreTopicOpensThatTopicsBuyers`) because the owner changed that decision.
  - **Rig:** player 9009 bought and was scanned on 自由探索 topic 990014 (registration 74, `owner_type 2`, `purchase_kind 3`). Shop 9002, approved by chapter application, sees 玩家二号 「最近已到店 · 今天」 with the hint (`f4-3a-01`). Over HTTP, reveal returned 403 and the audit row reads `deny=free_explore_shop`. Shop 9007, set to pending in this throwaway DB, sees 「0 位 / 还没有客户」 (`f4-3a-02`). Read-backs are in `f4-rig-readbacks.txt`.
  - The theme owner in the rig (9003) is a club owner, not a merchant account, so the host view could not be shown in the rig. The IT covers it.

## 4. Main package: fixed

Commit `3f8b43884`. Measured with preflight's count (`packOptions.ignore`):

| | Bytes |
|---|---|
| Start of this item (`1a230c185`) | 3,153,113 (7,385 over) |
| After | 3,105,184 |
| At `b1826d0c2` (after f2/f3 pushes) | 3,105,090 (40,638 under) |

- 34 PNGs under `images/` were recompressed losslessly with zopflipng. A pixel-by-pixel comparison of all 34 found 0 differences. Saved 46,327 bytes; the largest saving is `cards/default-cover.png`, 37,989 → 24,022.
- Four images with no reference were deleted: `images/roam/intro-roam.svg`, `intro-team.svg`, `intro-map.svg` (only `intro-ticket-*` are used), and `images/icon_jia.png` (post detail uses the subpackage's own copy).
- No image is used by only one subpackage. Every image used outside the main package is shared by several subpackages, so it has to stay in the main package.
- Not touched: the four utils from `de56cfa56`, the danger-action gate, the ceiling, `club-direct-ledger` and comments. `require.async` was not used.
- `preflight-release.js` now reports the main package at 2.96 MB. Its only BLOCK is Action Ledger drift from whichever lane pushed WXML last (see below).

## 5. f1 leads: fixed

- **Refund rounding** (`c9c2f113c`). New `RefundServiceImpl.allocateClawback`. Each column's total is rounded once; every non-merchant receiver is rounded on its own; the merchant takes the remainder, which is the same rule settlement uses. The ratio is unchanged: refund / paid, falling back to the settled total, capped at 1. Half-fen test: ¥97.50 at 3% (platform 2.93, merchant 94.57), refund ¥48.75. Before: 1.47 + 47.29 = 48.76. Now: 1.47 + 47.28 = 48.75, and the merchant balance is debited 47.28. Refund tests: 545 pass. `RefundRetryAndRemainderMySqlIT` and `ClubEventTransferCancellationMySqlIT` pass on real MySQL. Edge case: a refund approval replayed across the deploy is compared against the old row's amounts and stops loudly rather than double-debiting.
- **`ci/chain_postdeploy.py`** (`f3674b68a`).
  - Both content-safety beans are added to `BEANS`.
  - New `register_content_safety_jobs`. It checks the fingerprint, then runs the post-deploy file as written, statement by statement. Both runner assertions read the real cursor. The new rows must be paused with the file's schedule, existing rows must stay byte-for-byte equal, and a replay inserts nothing.
  - The restart-effect scenario still covers the original six jobs. `ALL_JOBS` leaves these two out, and at the end of the restart they must equal the post-deploy read-back exactly.
  - Wired into `chain_acceptance.py` (run plus summary).
  - `test_chain_acceptance.py`: 92 pass, including three new tests and a "paused row changed during restart" negative control. Not run: the real chain-acceptance workflow with a JVM; CI's `chain-acceptance` job covers that.
- **Order list label** (`adc120288`). A plain activity, whose `productType` is empty because no theme is bound, now shows 「活动」. Only 1 shows 城市定向, and 2 still shows 自由探索. New test `r10-f4-order-list-activity-label.test.js` fails on the old WXML. Rig: in this container the order list API fails with `对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME`, so no shot.
- **Countdown** (`3e007433c`). Under 24 hours it says 「距离活动开始还剩12小时」. Activities say 活动 and themes say 路线. From 24 hours up it still counts China calendar days. New `r10-f4-participation-countdown.test.js`.
- **Tab bar** (`1d9591a1c`). The in-page tab bar is really about 16 + 88 + 16rpx plus the safe area, and the merchant home's bottom pad was exactly 120rpx plus the safe area. Scrolled to the bottom, the project card's lower edge sat inside the bar's fade. The pad is now `var(--cy-tabbar-h) + var(--cy-space-6)`, the same as the player home. New `r10-f4-merchant-home-tabbar-clearance.test.js`. Rig: merchant 9002 has no project card in this data, so `f4-5e-01-merchant-home-bottom-no-project.jpg` only shows the message card clearing the bar. The project-card case is unverified in the rig and on a phone.

None of these five had been fixed by f3 when I started.

## Verify

- Mini unit tests: 16,057 / 16,059 pass at `3f8b43884`. The 2 failures were pinned Action Ledger counts after f3's 「创建俱乐部」 control; f3 rebuilt them in `d9217d502`. `action-ledger.test.js` passes after my rebuild `6bec105dd`.
- `ui-integrity.js`: `UI-GATE-0:PASS`.
- `build-action-ledger.js --check`: passed at `6bec105dd`. f2 and f3 keep pushing WXML, so the shared ledger can drift between pushes. The last lane to push should rebuild it.
- Admin UI unit tests: 15 failures are the same with and without my changes (`Cannot find module 'vue-router'` etc.; `node_modules` is not installed in this container).
- Backend: the targeted suites listed above, run locally with Maven (JDK 21) against a local MySQL 8.0.46 for the ITs. `RegistrationFundsMybatisIntegrationTest`'s own "must be Oracle 8.0.45" check fails locally for that reason only.
- `migration-safety-check.sh` and `migration-layout-check.sh` pass. `ci/test_migration_ledger.py` and `test_migration_gates.py` pass.
- `ci/test_release_migration_rehearsal.py` fails with or without my changes (`release-migration-pack.json` is stale against HEAD). That workflow only triggers on its own files.
- **CI.** All my work is in `1b78a5362`. The last CI run read was on `5539681f9`, one f2 commit on top of it.
  - My own red, fixed: Funds MySQL IT pins test counts. My two new `RegistrationFundsMybatisIntegrationTest` tests raised the count from 108 to 110, and `e222f8377` updates the gate. The gate self-test is now green, and in the Funds MySQL IT job both new tests ran and passed (722 tests, 1 error, not mine).
  - Fixed for other lanes in `1b78a5362`:
    - The action ledger, which kept drifting while f2 and f3 pushed WXML.
    - The `std-g2-wizard-shell` negative control, broken by f3's `da26161be`: the second full-width button meant the test only ever narrowed the first one. It now narrows them all; the contract itself is unchanged.
  - Still red at `5539681f9`, none in f4 code:
    - Funds MySQL IT: `ClubSettlementSummaryMybatisIntegrationTest` setup. f2's `10a8ce6e2` cuts `cms_activity` from the base schema at the first `;`, which falls inside the `club_id` column comment.
    - Mini static gates:
      - U6 `pages/topic/pricing/index.wxml:33 RAW_ERROR_TEXT teamSizeError`, from f3's `cd9cc546c`.
      - Dead setData fields: `pages/publish/simple/index.js:82 mode` and `merchantinfo.js:459 hostMerchants`, from f2's `d963148d0` and f3's `5c10e5528`.
    - Mini unit: `wt2-player-scene-fixes-contract` 「帖文空态按主页归属切换口吻」, from f3's `26bcfb13c`. Locally this is the only failing mini unit test at `5539681f9` (16,082 pass).
  - Backend tests never finished on any head that contains `1b78a5362`. Every push from f2, f3 or round 11 restarted the run before the job ended. The last heads checked were `002f49a5d` and `764cb4cd0`. The backend suites for what f4 changed were run locally (see the items above).
  - Later reds at `002f49a5d` and `764cb4cd0`, none in f4 code:
    - Funds MySQL IT at `002f49a5d` failed on a Maven Central 502 (infrastructure).
    - The funds gate self-test at `764cb4cd0` failed because round 11 g3's `4751ec464` added a `GrowthMissionProofMybatisIntegrationTest` case without updating its expected count (25 → 26).

STATUS: PARTIAL - f4 work is all in 1b78a5362 and its own CI items are green; whole-branch CI is not green because of f2/f3 reds listed under CI, and the backend job was still running; rig could not show the order list (OSS config) or a merchant project card
