# Round 10, lane f1-money-safety: result

Branch `integ/launch-test-20261103`. Work started at `23ca1354` after `ROUND9-M4-RESULT.md` had its STATUS line. The last code commit is `4dd041ec3`.

The work was split three ways. f1-A did the backend money items 1, 3, 7 and 9. f1-B did publishing and the app, items 2, 4, 6 and 8, and ran the rig. f1-C did content and numbers, items 5 and 10, then a rig walk of item 10.

All rig results come from the cloud rig: the real jar on MySQL 8 and Redis 7, and the real mini-program code in headless Chrome at 390×844. **They are not a phone.** Each item says what only a phone can prove.

Screenshots are in `fix-shots/`. `f1-2-*`, `f1-4-*`, `f1-6-*` and `f1-8-*` come with `f1-b-rig-readbacks.txt`. `f1-10-*` comes with `f1-10-readbacks.txt`. Every shot was looked at.

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Activity limit (j1 F1) | fixed |
| 2 | Game optional (j1 F2) | fixed |
| 3 | Settlement rounding (j1 F3) | fixed |
| 4 | Staff invite (j1 F4, F22) | fixed |
| 5 | Content checks (j5 B1, B2) | fixed |
| 6 | Silent guest accounts (j6 F1) | fixed |
| 7 | Stuck order (j6 F2) | fixed |
| 8 | Checkout (j6 F5, F6, r1 F3) | fixed |
| 9 | Release (r1 F1, F2, F4, F5, F6) | fixed |
| 10 | Merchant numbers (j1 F13, F12, F7, F14; p2 F-1, F-2, F-3) | fixed |
| 10 | p2 F-4 (客户 shows 0) | needs owner |
| — | Main package under 3,145,728 bytes | **not met**: 3,153,177. This lane's net change is −4 bytes. See below. |

## 1. Activity limit (j1 F1): fixed

Commit `664aef438`.

**What was wrong.** At the current head, the merchant permission row was 0/0 (`migration_p1_rbac_permission_defaults.sql:22`). `QuotaServiceImpl` counted every activity that was not deleted, however old.

**Migration.** New file `migration_20261104_merchant_activity_limit.sql`:
- Sets the merchant default row (`role='merchant' AND membership_level IS NULL`) to `can_create_event=1, max_events=3`.
- Runner assertions: before the change, exactly one such row must exist; after the change, the value is read back.

**Count.** New query `CmsActivityMapper.selectActivityQuotaUsedByMemberId`. It counts activities that are not deleted, not cancelled, and either have no end date or end in the future. Two places use it:
- `QuotaServiceImpl.checkCreate(TYPE_EVENT)`. This serves merchants and clubs alike, so "ended activities don't count" holds for both.
- `/api/role/info` `usage.events`, which is shown next to `maxEvents`.

**Other callers.** The other callers of the old all-time query keep it. They are profile counts: `ApiUmsMemberController:604` and `PublicMemberServiceImpl:122`.

**Prelaunch seed contract.** `ci/prelaunch-seed-provenance.json` and `DEFAULT_ROLE_PERMISSIONS` describe the frozen 20261012 insert, so they are unchanged. The new migration runs after it. `test_prelaunch_business_seeds` and `test_chain_platform` pass.

**Proof.**
- `QuotaServiceOnlineLimitTest.createEvent_countsOnlyLiveActivities_notLifetimeTotal`: 7 activities ever, 2 live, so a third is allowed.
- `PublicActivityScopeGateMyBatisTest.activityQuotaCountsOnlyLiveUncancelledRows` on real MySQL: the new count is 3 where the old one gives 6.
- Migration rehearsal:
  - Running it twice gives the same result.
  - With a second default row present, it stops at the assertion and writes nothing.
  - A row that has a membership level is not touched.
- f1-B's rig had the merchant row at 1/3 straight from this migration, with no fixture.

**Needs owner.** Club sessions (`ClubSessionServiceImpl.open`) and self-hosted topic activities are created without a quota check. They still count toward the 3 while they haven't ended, as they did before. Default: keep it that way. Say if sessions should not count.

## 2. Game optional (j1 F2): fixed

Commit `0476427dc`.

**Game is optional.**
- Step 2 and the submit check no longer require a template, and the required mark is gone.
- Unselecting a template and tapping 完成 now means "no game".

**Backend.** `/api/activity/info` no longer queries `id in (null)` when `template_id` is null or 0.

**Refusal on entry.**
- New read-only endpoint `POST /api/activity/publish_precheck` in the existing `ApiActivityController`.
- It runs the same checks as publish: publisher, merchant approval and quota.
- The page calls it on entry for new activities, so 「当前身份不可创建活动」 shows before the form is filled. Shot `f1-2-04`.

**Draft kept.**
- When the merchant leaves to build a game, the activity page stays on the stack.
- After 发布玩法 the editor returns to the draft instead of relaunching into 我的节点玩法.
- On the merchant path, template-intro opens light.
- Shots `f1-2-06` and `f1-2-07`.

**Proof, end to end in the rig with real HTTP and DB read-backs** (`fix-shots/f1-b-rig-readbacks.txt`):
- **Publish:** with templateId 0, activity 9 is written with template 0 and no club or topic.
- **Detail:** returns 200 with no game rows.
- **Quote and pay:** quote is ¥39.00. Paying through the real `/api/registration/callback` gives payment_status 2 and a pending split of 37.83 + 1.17.
- **Ticket and scan:** dyncode issued, the merchant scanned, verification_status 1.
- **Settlement:** job 145 logged 「总销售额=39.00, 商户佣金=37.83, 平台佣金=1.17」, is_settlement 1, with balance rows for both.

**Tests.**
- `r10-activity-game-optional.test.js`: 7 cases.
- `ActivityPublishRoleGateTest`: 4 new precheck cases.
- `ApiActivityInfoLifecycleContractTest`: a no-game case.
- `publish-editor-return-flow.test.js`: now also accepts the new return-to-draft branch. The relaunch is still required for every other path.

**Unverified on a phone.**
- In the rig, the editor's 发布玩法 call itself was triggered by script. The return trip around it was walked.
- Native date wheels.

## 3. Settlement rounding (j1 F3): fixed

Commit `a197a3736`.

**The fix.** In `CmsActivityServiceImpl.calculateActivityCommissions`:
- The platform and designer shares are each rounded half-up, and the merchant gets sales − platform − designer.
- Settlement now refuses on any mismatch or a negative merchant share. Before, it only logged a warning above 0.01.
- At ¥97.50 × 3% the split is 2.93 + 94.57. Before, it was 2.93 + 94.58.
- The same method serves the settlement job, the release of complaint-held orders and `estimateMerchantShare`.

**`CmsTopicServiceImpl.calculateCommissions`.** It has no callers, but it is aligned so it can't be reused wrong.

**Already split by remainder.** These paths were checked and needed no change:
- club own-topic (principal = sales − platform);
- coop topic (largest remainder);
- `SettlementEntryServiceImpl` ticket detail.

**Proof.** `ClubPrincipalPayeeSettlementTest.halfFenSalesSplitSumsExactlyToSales` uses ¥78 + ¥19.50 retained = ¥97.50. It and `TopicSettlementSplitRemainderTest` fail on the old code ("expected 94.57 but was 94.58").

In f1-C's rig walk, the real settlement job split 58.50 into 56.74 + 1.76, which sums exactly.

**Leads, not changed.**
- When a refund comes after settlement, `RefundServiceImpl.createSettlementAdjustment` rounds each receiver's clawback separately. That can be about 1 fen off per receiver.
- `settleActivity` computes a designer share but never pays it. `creator_rate` is 0 everywhere today, so there is no effect now.

## 4. Staff invite (j1 F4, F22): fixed

Commits `9eb039fca` and `6109ec2aa`.

**Filter.** `role-view-filter.js` now lets two endpoints through in player view:
- `/api/merchant/operators/invite/accept`
- `/api/merchant/operators/invite/preview`

Every other operators endpoint is still blocked. Backend access is unchanged.

**Preview read.** New `POST .../invite/preview`, a new method on the existing `IMerchantOperatorService`:
- The invite token is its scope.
- It answers only for a pending, unexpired invite of an active shop.
- It returns only shop name, role code and role name. Any failure gives 「邀请无效或已失效」.

**Page.** The caption is replaced by 「门店名 · 岗位」. Shot `f1-4-01` shows 「探店日样板咖啡 · 核销员」.

**Proof.**
- Rig: the invitee accepted from player view and got 200. DB: operator ACTIVE MERCHANT_CHECKIN, invite ACCEPTED. Shot `f1-4-02`.
- `MerchantOperatorServiceImplTest`, `ApiMerchantOperatorControllerTest` and `merchant-operator-ui-contract.test.js`.

**SilentError allowlist.** +1 for the team page and +1 for the publish-page precheck. Both pages show the failure themselves, as red text or the denied state, so a toast on top would show it twice. This is the case the allowlist exists for.

**Unverified.** The real share card opened from WeChat. The success line after accepting still uses the old error colour; that is old styling outside this item.

## 5. Content checks (j5 B1, B2): fixed

**B1, commit `124c7de1d`.** Theme-bound node game text now goes through the machine check: name, rules, question, answer, feedback, options, hints, reveal, story blocks and narrative. It runs in three places:
- **Game save:** `/api/template/draft` runs the same `templateTextSecurity` as `/publish`.
- **Theme create and update:** `topicReviewTexts` collects the games bound to the theme's nodes and sends them to the existing `contentSafetyGate`. On update they are always re-checked.
- **Draft-theme publish:** `/publish-draft` re-checks the bound games. A hit refuses the publish. "Needs review" publishes the theme, then holds it and queues a recheck, the same as create.

「将随主题一起审核」 is now true, so it stays.

Tests: `ThemeBoundTemplateTextSafetyTest`, plus the existing `ApiTopic*`, `ApiTemplate*` and `JourneyNarrativeSecuritySaveTest` suites.

**B2, commit `4f5ccd36d`.**
- Activity publish and update now send these to the gate: venue name, address, and for each ticket its name, description, meeting point and refund note.
- Tests: `ActivityPublishReviewModeTest` and `ActivityUpdateReviewModeTest`.
- Rig proof over real HTTP: marked text in venue name, address, ticket name and ticket description was each refused with 「活动内容包含违规信息，请修改后重试」, and no row was written. The clean request published. An update with a marked ticket description was refused.

**Risk.** Text over 2,500 characters is not split into batches, as before.

## 6. Silent guest accounts (j6 F1): fixed

Commit `2a9bffecc`.

**Session helper.** `pages/merchant/utils/session/ensure-session.js` returns false for a guest and never calls `wx.login`. It only renews an existing account after a 401, and only from `recover*` methods.

**Entries covered.**
- The workbench, team (including invite links), relation, marketing and reviews pages now show the usual 「登录后查看… · 登录」.
- game-node, verify-records, crm-campaigns and tixian fall back to the workbench.
- The account is created only at the 登录 tap.

**Test.** `r10-guest-no-silent-login.test.js` pins three things:
- the helper never logs a guest in;
- renew is only used in recover methods;
- every login-capable call is in a reviewed allowlist that excludes the 8 j6 entries.

**Old tests updated.** These encoded the 2026-09-16 "guests log in silently" decision, which the owner's later rule (`app.js:142-144`) replaced:
- merchant-console-identity-gate
- merchant-marketing-state-contract
- merchant-operator-ui-contract
- merchant-relation-state-contract
- merchant-review-read-session
- w10a-merchant-review-filter
- r9-22

`action-ledger.test.js` counts went from 5685 to 5689 for the 4 new 登录 buttons.

**Rig proof.**
- The member count stayed at 9 after guests opened game-node, marketing and tixian.
- The only network call was `/api/config/features`.
- Shots `f1-6-01` to `f1-6-03`.

**Unverified on a phone.** That real `wx.login` creates the account at the tap.

## 7. Stuck order (j6 F2): fixed

Commit `ee2bf5e97`.

`/api/registration/cancel` now calls `IRegistrationCheckoutService.cancelUnpaid`:

| Order state | What happens |
|---|---|
| Never sent to WeChat (`payment_status=0`) | The old path. |
| Paying (`payment_status=1`) | The existing query-before-close route, the same one `OrderCloseTask.reconcileExpiredPaying` and the organiser's cancel use. See below. |

For a paying order, WeChat is queried first:
- **NOTPAY or PAYERROR:** close at WeChat; the close is confirmed only when it reads back CLOSED. Then the order is closed locally under lock, after checking that this attempt is still the current one. The ticket, points and perks go back.
- **CLOSED:** close locally.
- **USERPAYING:** refused.
- **SUCCESS:** recorded as paid through the callback path, then refused.
- **Query failure, mismatch or unconfirmed close:** refused, and the order is unchanged.

**Payment status.** The SQL of `cancelUnpaidIfActive` and `beginPaymentAttempt` is unchanged, and `payment_status` only moves through the existing transitions.

**Late callback.** If 继续支付 starts a new attempt during a cancel, the attempt check fails and the cancel is refused. After the local close, `freezePaymentIntent` won't create a new attempt. So a late WeChat success after a cancel can't happen, and there is no duplicate-payment path.

**Expiry job (the question asked).**
- `orderCloseTask.closeExpired()` does **not** close `payment_status=1` orders.
- `orderCloseTask.reconcileExpiredPaying()` does. It runs every 10 minutes and is enabled by `migration_registration_paying_reconcile_job_20260915.sql`. It handles orders past `pay_expire_time`: it queries WeChat, closes the WeChat order, and sets the order to expired (4).

**Proof.** `RegistrationCancelPayingOrderTest`, 10 cases:
- the close order: the WeChat close comes before the local close;
- the WeChat states CLOSED, USERPAYING and SUCCESS;
- order not found, and an unconfirmed close;
- a new attempt winning the race;
- a sibling order routed to its primary;
- another member gets 403.

**Unverified.** The real WeChat pay sheet and close calls are a stub in the rig. Only a phone proves these.

## 8. Checkout (j6 F5, F6, r1 F3): fixed

Commit `260dfae34`.

**F5.** In `baoming.js`, a late `/api/user/info` reply no longer overwrites the chosen participant's name and phone. The error was already red text under the field (`cy-inline-error`), so that is unchanged. Test: `baoming-submit-inline-error`, which fails on the old code.

**F6.**
- `payExpireTime` goes over the wire as `yyyy-MM-dd HH:mm:ss` in `CmsRegistration` and `ViewRegistration`.
- The admin edit form now uses a datetime picker, so admin edits don't fail to parse.
- Rig: DB 18:18:20 → wire 「2026-11-04 18:18:20」 → screen 「请在 2026-11-04 18:18 前完成支付」. Shot `f1-8-03`.
- Tests: `r10-pay-expire-display.test.js` and `RegistrationPayExpireTimeJsonTest`.

**r1 F3.** The quote now runs the same buyer check as create, through the new `IRegistrationOrderCreateGate.validateBuyer`.
- Rig: a merchant asking for a quote on a club activity gets 403 「商家不能报名俱乐部活动」 with no price. Create refuses the same way, and no row is written.

**Unverified.** The rig can't force the real network order of the race, so the unit test is what pins it.

## 9. Release (r1 F1, F2, F4, F5, F6): fixed

Commit `9307f026e`.

**Edited migrations.** `migration_20261103_content_safety_recheck.sql` and `migration_20261103_sensitive_content_library.sql` were **edited in place**. ROUND10 allows this for this item only, and both files are confirmed absent from `origin/master`. Their `sys_job` inserts and assertions are removed, and their comments say where those went.

**New post-deploy file.** `migration_20261104_content_safety_jobs_post_deploy.sql`:
- Holds the same two inserts, both paused.
- Before: asserts the tables exist and there are no duplicate rows.
- After: asserts exactly one row per task.
- Its comment gives the rollback DELETE.
- It is listed in `POST_DEPLOY_MIGRATIONS` in `ci/migration_ledger.py`, with a ledger test, and registered in `ci/migration-dependencies.json`.

**F5 and F6.** These were done as new read-only files:
- `migration_20261104_club_session_gather_point_readback.sql`: no open session may lack coordinates when one of its tickets has them.
- `migration_20261104_merchant_industry_categories_readback.sql`: at least one type-6 row must exist, and each of the ten keywords must match a category.

Negative checks: both fail on bad data. In the second, data that passes the old category assertion fails the new one.

**Release notes.** `integ/RESULT.md` § "Release order and rollback" holds the file list with phase and production impact, the deploy order, the rollback, and the rehearsal table. That covers F4.

**Rehearsal** with real jars on throwaway MySQL and Redis. The old jar is `origin/master` `c6eac3b8c`; the new jar is the candidate. Evidence is in `proof/round10-f1a-release/`.

| Step | Result |
|---|---|
| Pre-deploy files applied while the old jar runs | The old jar keeps its scheduler (jobs at 16:46 and 16:47). |
| Old jar restarted on the migrated schema | Boots. |
| New jar | Comes up. |
| Post-deploy file applied, then run a second time | Both tasks registered paused, still 2 rows. Neither task ran in its slot while 8 other jobs ran. |
| Rollback | The old jar with the two rows present fails to boot (F1 reproduced). After deleting the rows it boots. Forward again works. |
| F2 negative check | Applying the post-deploy file under the old jar stops its scheduler within 1 s. Deleting the rows brings it back. |

**Not done.** `ci/chain_postdeploy.py`, the strict hash-pinned post-deploy check, still covers only the three older post-deploy files. Default: extend it at release prep.

**Unverified.** Production's applied state, and both tasks actually running once they are enabled.

## 10. Merchant numbers: fixed, except p2 F-4

**p2 F-1, coupon page blank. Commit `cf3340d16`.**
- **Cause:** end-of-line `--` comments in `SmsCouponMapper.updateExpiredCouponsByMemberId`. The Druid wall refuses an UPDATE that ends in a comment.
- **Fix:** the comments became XML comments. Three other mappers with the same pattern were cleaned the same way.
- **Test:** `DruidWallSqlCommentContractTest` checks the statement against the app's wall config and scans every mapper. It fails on the old code.
- **Rig:** the list returns 200 and the page shows 发布优惠券. Shot `f1-10-m10`.

**j1 F13, F12 and p2 F-2, numbers from one source. Commit `8c92dff6b`.**
- **Redemptions list:** `/api/merchant/finance/redemptions` now includes the merchant's own activity ticket scans. It uses the new `RedemptionMapper.selectActivityTicketScansByMerchant`, scoped by `a.member_id`.
- **Whole-shop summary:** 待结算 comes from the same source as 财务.
- **Settlement overview:** now counts activity payouts. 财务 labels them 「活动分润」.
- **Pending total:** 财务's 待结算 adds the retained part of a 50%-refunded order, with the same SQL and share formula settlement uses. This is read side only; settlement and refund writes are unchanged.
- **营销:** the window includes today, and its 核销 count includes ticket scans. `MerchantAggregateReadServiceTest` and four mini tests pinned the old "up to yesterday" window and were updated.

**j1 F7, refund amount. Commits `b89b77416`, `0d53d00ae`, `e00202b37` and `4dd041ec3`.**
- `/api/registration/info` and the list rows carry `cancelRefundAmount`. It is cash paid × the current refund rate, rounded down to the fen. It uses the same `RefundPolicy.applyCashRatio` the real cancel uses.
- The rig walk found that single-ticket orders carry a parent order number, so the amount was missing. Fixed with one grouped count query per page, not one per row.
- The cancel confirms on order detail, order list and participation detail read 「本次取消将原路退回 ¥19.50，…」.
- The order shows a 「退款 ¥19.50」 row after cancelling.
- Shots `f1-10-p03`, `p05`, `p07` and `p09`.

**j1 F14, shop as organiser. Commit `9dcbaca87`.**
- Activity detail shows 「主办方 · 梧桐里书房 · 商家」, and the order shows 「主办方：梧桐里书房」.
- The publish form's 合作者 row shows the shop.
- Only club activities are tagged 俱乐部活动.
- Shots `f1-10-p02` and `p11`.

**p2 F-3, home 「全部 N」 vs an empty 我的项目. Commits `cb9f23f0f`, `271e43576` and `192df5f75`.**
- In merchant scope, 我的项目 also lists 承接 projects, from the same two sources and rule the home count uses. They are shown as read-only 「我承接的」 cards.
- Test: `merchant-myproject-join-count.test.js` pins home count == list count.
- Rig: 全部 2 = 1 承接 + 1 活动. Shots `f1-10-m07` to `m09`.

**Found in the rig walk and fixed. Commit `f8c363032`.**
- Participation detail stayed on 「参与详情加载中」 forever when its property observer fired before `attached`.
- Test: `participation-detail-observer-before-attached.test.js`, which fails on the old code.

**Proof that the numbers agree.**

`MerchantNumbersAgreeMyBatisTest` runs on MySQL 8 with the real mapper XML: one ¥39 sale scanned, and one ¥39 sale cancelled at 50%.
- Before settlement, 财务 待结算 = ledger 待结算 = the settlement amount = ¥56.74. Another merchant sees nothing.
- The settlement step in this test writes the payout rows by hand; it does not run `settleActivity`.

The rig walk then ran the **real** settlement job on the same scenario:

| Screen | Before settlement | After settlement |
|---|---|---|
| Home 累计收入 | ¥0.00 | ¥56.74 |
| 财务 | 待结算 ¥56.74 | 可提现 ¥56.74, 待结算 0 |
| 核销与退款 | 1 核销, 待结算 ¥56.74 | 已入账 ¥56.74 |
| 结算 | ¥0 | ¥56.74 |
| 核销记录 | 1 | 1 |
| 营销 | 报名 2 · 核销 1 | 报名 2 · 核销 1 |

The pending amount shown before settlement equals what was paid out. The DB balance rows are 9002 +56.74 and platform +1.76. Shots `f1-10-m01*` to `m06*`.

**p2 F-4, 客户 shows 0: needs owner.**
- `MerchantCrmAppMapper` leaves 探店日 customers out on purpose (「探店日不并入通用 CRM」).
- Counting them would also widen who the shop can contact and broadcast to.
- Recommended default: keep them out, and show a 探店日 count separately in a later round.

**Seen in the rig, not fixed (f3 lane wording leads).**
- The order list labels a plain activity 「城市定向」.
- Participation detail says 「距离路线开始还剩1天」 for an activity 12 hours away.
- The tab bar partly covers the home project card.

**Unverified on a phone.** Real fonts and wrapping, native dialogs, and the refund actually reaching the player (the rig's refund payout is a stub).

## Main package

Measured with `preflight-release.js` / `mainpkg-size-report.js`:

| | Bytes |
|---|---|
| At this lane's start | 3,153,181 |
| At the end | 3,153,177 |
| Release preflight ceiling | 3,145,728 (3.00 MB) |

- Every new mini-program file from this lane is in a subpackage or under tests. The only main-package edit is `role-view-filter.js`, at −4 bytes.
- The branch is still 7,449 bytes over. Preflight blocks with 「主包未压缩 3.01MB > 棘轮 3.00MB」.
- I looked for a safe move:
  - The four large single-subpackage utils are `deregister-flow`, `user-invoice`, `capsule-client` and `exploration-goals`. Commit `de56cfa56` deliberately moved them back to the main package, because in a subpackage they fall under the danger-action gate unregistered.
  - The only other sinkable component, `club-direct-ledger`, would free about 6 KB, which is not enough.
- I did not move other lanes' code. This needs a decision in the f3 lane or by the owner, for example registering those four in the danger-action registry and sinking them.

## Migrations (for PR #723 ③)

| File | Phase | Effect on production data |
|---|---|---|
| `migration_20261104_merchant_activity_limit.sql` (new) | before jar | `UPDATE user_permission` merchant default row → `can_create_event=1, max_events=3` |
| `migration_20261103_content_safety_recheck.sql` (edited, unreleased) | before jar | creates table `content_safety_recheck` only; no `sys_job` |
| `migration_20261103_sensitive_content_library.sql` (edited, unreleased) | before jar | 4 new tables, 1 `sys_config` row, 2 `sys_menu` rows; no `sys_job` |
| `migration_20261104_content_safety_jobs_post_deploy.sql` (new) | **after the new jar is live** | inserts 2 paused `sys_job` rows. Rollback: delete them before starting the old jar. |
| `migration_20261104_club_session_gather_point_readback.sql` (new) | before jar | read-only assertion |
| `migration_20261104_merchant_industry_categories_readback.sql` (new) | before jar | read-only assertion |

## New backend classes and endpoints

**Classes.** No new Service, Mapper or Controller classes, so no architecture registration was needed. Two plain classes were added: the util `ActivityShopOrganizer` and the result type `MerchantActivityTicketScan`.

**New methods on existing interfaces.**
- `IRegistrationCheckoutService.cancelUnpaid`
- `IMerchantOperatorService.invitePreview`
- `IRegistrationOrderCreateGate.validateBuyer`
- `ICmsRegistrationService.countByParentOrderNos`
- the mapper reads named above

**New endpoints in existing controllers.**
- `POST /api/activity/publish_precheck`: read-only, same checks as publish.
- `POST /api/merchant/operators/invite/preview`: scoped by the invite token.

## Checks

- **Mini unit tests (full):** 16,026 of 16,027 pass. The one failure was a stale ledger snapshot before the rebuild; the affected tests pass after it.
- **UI gates:** `ui-integrity.js` UI-GATE-0:PASS, with no new baseline entries.
- **Action ledger:** `build-action-ledger.js --check` PASS (5,689 controls).
- **Backend:** targeted Maven runs for every changed area are green. CI's Backend tests job covers the full suite.
- **Migrations:** layout, safety and ledger gates pass. The rehearsal is above.
- **CI on the last code commit `4dd041ec3`:** all green. GitHub CI run 41819999932 (all 30 jobs: Fast gates, Backend tests, Migration safety, Mini static gates, 20 unit shards, gitleaks, SBOM), Funds MySQL IT 41819999782, Mini followup 41819999899, Redis recovery 41819999795.

STATUS: PARTIAL - all ten items fixed and pushed (p2 F-4 needs owner); the main package is still 7,449 bytes over the 3,145,728 release ceiling, which this lane did not add to and could not safely reclaim
