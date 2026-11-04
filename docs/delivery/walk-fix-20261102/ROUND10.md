# Round 10: fixes from the first check pass

First-pass reports, tested at `8b78f5ca3`, each on branch `claude/check-1104-<lane>` under `docs/delivery/check-20261103/<lane>/REPORT.md`: `j1-merchant-sale`, `j2-club-session`, `j5-posts-moderation`, `j6-break-it`, `p2-pages-merchant`, `p3-pages-club-publish`, `r1-release`. Read the finding in its report before fixing it; IDs below are the report's own. Reports are leads: confirm each at the current head, and if a finding no longer reproduces, say so.

Rules for all lanes: work on `integ/launch-test-20261103`. Never push to master, merge a PR or touch production. No key or secret in the repo. Follow root `AGENTS.md` and `chengyinhub-xcx/AGENTS.md`, including "For UI agents". Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`. Commit each item, pull before each push, push after each. Don't push docs while the last code commit's CI is running. Existing UI is the baseline: small fixes, no redesign. Input errors are red text under the field. Write endpoints check owner and scope. New backend public Services, Mappers or Controllers need architecture registration as in `ROUND9.md`. New migrations are new files only, and PR #723's `## 影响分类` ③ section stays current. Main package has almost no headroom: it must end under 3,145,728 source bytes, so anything new goes in a subpackage. Rig results are not a phone: say what only a phone proves. Fix the cause, never weaken a test or gate to pass, and if a test encodes an old decision that the owner has now changed, update it and say so.

Owner decisions, final:
- Merchant activity limit is 3 at a time, same as clubs. Ended activities don't count, for merchants and clubs.
- A merchant does not need a game to publish an activity.
- Club themes with merchant stations must work at launch.
- Guests on a club page: members and leaderboard stay behind login, one 登录后查看 row replaces the empty sections.
- Platform fee is 3%. C-22 stands: several tickets across orders, one per order.

## Lane f1-money-safety

Start after `integ/ROUND9-M4-RESULT.md` has a `STATUS:` line.

0. **Main package first.** Measured with `packOptions.ignore` as in `scripts/preflight-release.js`: 3,151,663 bytes at `d6ce47adc`, 5,935 over the 3,145,728 ratchet. m2's 3.23 MB figure is a different count; use preflight's. Move the utils used by only one subpackage into that subpackage (m2 names `deregister-flow`, `exploration-goals`, `user-invoice`, `capsule-client`, about 42 KB), checking every require. Do not use `require.async`: the owner has not approved an unproven load path on the busiest page. Report the before and after.
1. **Activity limit** (j1 F1). New migration: `user_permission` `role='merchant' AND membership_level IS NULL` gets `can_create_event=1, max_events=3`, with a runner assertion. Production currently has `can_create_event=1, max_events=0`. `QuotaServiceImpl` counts with `selectCmsActivityNumByMemberId`, which counts every undeleted activity ever: use a quota count of activities that haven't ended and aren't cancelled. Check every other caller before changing a shared query. Check the prelaunch seed contract tests.
2. **Game optional** (j1 F2). Remove the client requirement in `pages/publish/activity/index.js`, including the step-validity check. Backend null safety, for example `ApiActivityController` near `Arrays.asList(templateId)`. Prove a no-game activity end to end: publish, detail, pay, ticket, scan, settlement. Also fix the lost activity draft when a merchant does leave to build a game. Check the refusal now comes before the whole form is filled.
3. **Settlement rounding** (j1 F3). Merchant share is what remains after the platform and designer shares, so the parts always equal sales. Check every settlement path, including `CmsTopicServiceImpl` and coop paths, with a test at a half-fen value such as ¥97.50 at 3%.
4. **Staff invite** (j1 F4, F22). Let `/api/merchant/operators/invite/accept`, and whatever the invite page reads first, through `role-view-filter.js` in player view. Backend access stays as it is. The page names the shop and role.
5. **Content checks** (j5 B1, B2). Theme-bound node game text goes through the machine check: name, rules, question, options, hints, feedback, story blocks, on save and on the theme's publish path. The editor's 「将随主题一起审核」 must be true or removed. Activity publish and update also send ticket names, ticket descriptions, venue name and address to the gate.
6. **Silent guest accounts** (j6 F1). A guest's account is created only at the write-action login prompt (owner's rule, `app.js:142-144`). Fix all eight entries, chiefly `pages/merchant/index` `ensureSession`. No page may call login on its own for a guest. Guests get the existing login state.
7. **Stuck order** (j6 F2). Closing the WeChat pay sheet must leave an order that can be cancelled and that frees its ticket: fix `cancelUnpaidIfActive` and `beginPaymentAttempt` so a real concurrent payment still can't be cancelled. Think through a late WeChat callback after cancel, and use the existing query-before-close route if there is one. Report whether the expiry job closes `payment_status=1` orders. No duplicate-payment path.
8. **Checkout** (j6 F5, r1 F3): the false 「参与人信息需要包含姓名」 race in `baoming.js`; shown as red text under the field. (j6 F6): `payExpireTime` must keep its time on the wire and on screen. r1 lead: quote for a merchant who can't buy.
9. **Release** (r1 F1, F2, F4, F5, F6). Move the two `sys_job` inserts out of `migration_20261103_content_safety_recheck.sql` and `migration_20261103_sensitive_content_library.sql` into one new file that runs after the new jar is live, and update each file's comments. These two are unreleased (not on master), so editing them is allowed for this item only; say so in the report. Rehearse: old jar running while the migrations apply keeps its scheduler; old jar boots on the migrated schema; new jar comes up; post-deploy file applies; both tasks registered paused. Write the deploy order and rollback (delete the two rows before starting the old jar) into the release notes. Add assertions to `club_session_gather_point` and tighten the category assertion, using new files if those are already on master.
10. **Merchant numbers** (j1 F13, F12; p2 F-2, F-3, F-4): one source so home, 财务, 核销与退款, 核销记录, 营销 and 客户 agree after a sale; pending includes what's retained from a 50% refund. (p2 F-1): merchant coupon management blank page. (j1 F7): refund amount shown before cancelling and on the order after. (j1 F14): organiser shows the shop's name.

Write `integ/ROUND10-F1-RESULT.md`.

## Lane f2-club-merchant

Start after `integ/ROUND10-F1-RESULT.md` has a `STATUS:` line.

Make a paid club city-orienteering session with merchant stations work from publish to settlement with no fixtures past the gates (j2 F-01, F-02). Read `j2-club-session/REPORT.md` first.

1. 招商 comes before pricing: the club theme's next step leads to 招商, and pricing explains nothing because the order makes it clear. Whether to allow late invitations after a price lock is a money rule: keep the lock unless you find it wrong, and tell me.
2. `merchant_sign_up_start_date/end_date` get set automatically from the theme's own dates where nothing sets them today. Production themes already have values: leave them alone.
3. `RegistrationMerchantDTO` date format, so the application stops returning 500. Check every DTO on this path.
4. `recruitDeadline`: the editor must send it when 商家承接 is on, or the validator must not require it for this product. Reviewers see plain Chinese, not field names. Find out whether the `effectiveSaleEndAt` refusal is real using the editor's own body.
5. The merchant can always reach the accept page, even after the sign-up window closes. The lead sees who's holding up the start and can act.
6. The merchant's scan after acceptance has a live co-op order, so players continue past station 1. If a station has no merchant, the session must not strand paid players: start stays blocked with a clear reason before sale or start.
7. Then j2 F-04, F-05, F-06, F-07, F-08 (real names everywhere an organiser chooses someone, first in the owner-transfer confirm), F-09, F-10, F-11, F-12, F-13, F-14, F-15, F-16.

Walk the whole journey with real tokens through to settlement, to the fen, with a merchant, a lead and four players. Write `integ/ROUND10-F2-RESULT.md`.

## Lane f3-pages

Start after `integ/ROUND10-F2-RESULT.md` has a `STATUS:` line.

1. **Dead ends.** p3 F1 (swallowed 下一步 in `club/create`), p3 F2, p3 F3 (a business refusal shows its reason and one back action; the existing rule that a network failure stays blank is unchanged, and if you think the two can't be separated, tell me), j5 H1, H2 (author sees the held state), j6 F3 (票夹 error state with retry), the ~30 guest blank pages (j6, use the existing 登录后查看 state), j6 F4 (join gate refreshes on return), j6 F7 (the owner's club page decision).
2. **Report, don't open:** can club posts and themes on the guest club page be read safely by guests? List the endpoints and fields for owner review.
3. **Wrong words.** j5 H3, H4, H5, j6 F9, F12, p3 F4, F6 (error style and timing; the unlabeled price), F9, r1 leads.
4. **Polish.** p2 F-5 to F-13, p3 F5, F8, F10, j1 LOW, j2 F-17 to F-23, j5 LOW, j6 LOW.
5. **Captions** (p2 F-10, p3 F7, j1, j2 F-24, j6 F11), using each report's layout suggestion. Keep privacy statements and consequence warnings on danger actions, and list them for the owner.

Write `integ/ROUND10-F3-RESULT.md`.

## Lane f4-review-rules

Start after `integ/ROUND10-F3-RESULT.md` has a `STATUS:` line. The owner delegated these WeChat-review choices on 2026-11-04.

1. **Wording, final.** Everywhere a user sees it (mini-program, admin, backend messages, editor, system notices), changed together so mirrored messages stay in step; code identifiers such as `betCount` stay.
   - 「随机盲盒」 becomes 「随机卡」, and 「已抽 N / M」 becomes 「已翻开 N / M」.
   - 「竞猜」 becomes 「预测」: 「竞猜待答」 is 「预测待答」, 「登录后竞猜待答」 is 「登录后查看预测」, 「当前岗位不能结算竞猜」 is 「当前岗位不能公布预测答案」, 「没有等你给答案的竞猜」 is 「没有等你给答案的预测」.
   - 「押」 becomes 「选」: 「N 个人押了这一轮」 is 「N 个人选了这一轮」, 「这一轮还没有人押」 is 「这一轮还没有人选」, 「押中的人」 is 「选对的人」.
   Sweep `盲盒|竞猜|押|下注|赔率|赌|抽奖` across the repo's user-visible text and list every hit as changed or why not. Confirm no points or money can be staked in these games: if they can, stop and report. List any such words in seeded or stored content for me; don't change stored data.
2. **Inviter points.** New migration turns off only `cms_points_setting` id 6 (「被邀请用户报名任意活动」, 100 points per signup, active on production), with a runner assertion; id 5 (500 points for a first purchase) stays. Prove signup, payment and settlement still work with id 6 off, including any check that a rule must exist. Remove or correct any screen copy promising the per-signup reward. No team or tiered rewards.
3. **Paid merchant extras stay closed at launch** (`pages/merchant/decor`: 高级模板, 推广曝光位, 品牌主页, 活动定制). Find how they're gated today. If a merchant could start a payment under production config, close them with the existing switch; if there's no switch, hide the purchase entries and refuse on the backend. No new captions. The ¥30 merchant referral stays as it is.
4. **Main package.** It must end under 3,145,728 bytes by preflight's count; f1 ended 7,449 over, and later lanes may have added more. Don't use my item 0 route: `de56cfa56` moved those four utils back to the main package on purpose because of the danger-action gate. Don't sink them or weaken that gate or the ceiling. Look first at `images` (343 KB in the main package): images used only by a subpackage move there, unused ones go, and oversized ones get losslessly recompressed. Then at dead code and long comments in main-package files, and at `club-direct-ledger`. No `require.async`. Report before and after, and what you moved.
5. **f1 leads.**
   - `RefundServiceImpl.createSettlementAdjustment` rounds each receiver's clawback separately, so a refund after settlement can be about 1 fen off per receiver. Make the parts equal the refunded amount, with a half-fen test.
   - Extend `ci/chain_postdeploy.py` to the new post-deploy file.
   - The order list labels a plain activity 「城市定向」.
   - Participation detail says 「距离路线开始还剩1天」 for an activity 12 hours away.
   - The tab bar partly covers the home project card.
   Skip any that f3 already fixed.

Write `integ/ROUND10-F4-RESULT.md`.

## Verify and report

Every lane: full mini unit tests, `ui-integrity.js`, `build-action-ledger.js --check`, backend tests for what changed, all CI on the final commit, migration rehearsal for new migrations. Rig walks at 390x844 with screenshots viewed by you, under `integ/fix-shots/`. Plain words. Per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
