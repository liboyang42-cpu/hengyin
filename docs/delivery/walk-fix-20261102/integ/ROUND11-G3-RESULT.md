# Round 11 lane g3-player-pages: result

Branch `integ/launch-test-20261103`. All my code is in `e5405048` (my last code push) and in every later commit on the branch. Rig shots are from the browser rig at `e5405048` (real jar, MySQL 8, Redis 7, real mini-program code in headless Chrome at 390x844). They are not a phone. Shots are in `integ/fix-shots/g3/`; I looked at every one.

## Per item

### 1. Orders and participation tell the truth (p1 F-1, F-2; j3 F9, F12) — fixed

- **Not 进行中 before the session starts.** Cause: a 自由探索 ticket with `exploreProgress.active` was always 「进行中」. Now it is 「未开始」 until the session start (the redemption count still shows). `utils/order-status.js`.
- **Solo pass.** Status is now whether you can play: 未开始, 进行中, or 已结束 (after `expiresAt`). 「不可退款」 is no longer the status, so it appears once, in the refund card. The timeline no longer has the 到店核销 or 到集合点开场 step a pass never reaches. 票价 uses the original price instead of 「—」. 我的 cards say 「进行中」, not 「待核销」.
- **One validity date.** The purchase sheet promised 「可玩至 <theme end>」. The server stored 90 days from purchase, and its play and scan gates enforce that. Now the server stores the earlier of the two, so the purchase sheet, order detail and gates agree (`CmsRegistrationServiceImpl.selfPlayPassExpiry`). Passes already sold keep their stored date. **Owner check:** this shortens validity to theme end for new passes. It matches what the sheet already promised.
- **Date-only values never show 00:00.** `verificationTime` was sent date-only (`@JsonFormat yyyy-MM-dd`), so 核销 read as 「00:00」 and sorted before 付款 23:10. It is now sent with its time; the column is already datetime and redemption writes `now()`. The admin form now uses a datetime picker. Any date-only value now formats without a time: order detail, 我的 card, theme dates. A date-only end date counts as the whole day.
- **One status (F-2).** The status shows once, in the hero pill. The card no longer repeats it in its title and corner tag. It keeps only what the pill doesn't say: the redemption count, the refund progress, or 免费报名成功. 「完成后更新」 is gone. Lane f3 separately removed the 「票夹已生成…」 hints.
- Files: `utils/order-status.js`, `scene-member-order-detail/index.{js,wxml}`, `cy/profile/order-preview.js`, `CmsRegistration.java`, `CmsRegistrationServiceImpl.java`, `chengyinhub-ui/.../registration/index.vue`.
- Proof:
  - `tests/unit/r11-g3-order-truth.test.js` (6 tests; all fail on the old code).
  - `R11G3OrderTruthTest` (pass expiry, verification time on the wire).
  - `r10-pay-expire-display` updated: it had pinned date-only → 00:00 as expected.
- Unverified: I didn't walk an order detail in the rig, because the rig's purchase chain stopped first (see "Found while walking").
- Not changed: backend refusal reasons use ASCII commas (「已开始,不可退款」, also 「场次已开始,不可退款」). That is the style across all refund messages, and tests pin the wording. Left for a wording sweep.

### 2. One level and one rank (p1 F-3, F-4, F-5; j3 F10) — fixed

- **Level.** 我的 read `play.level`, a check-in-count ladder. 成长中心 reads `member_growth.level_no`. 我的 now reads `level_no` too. Other people's profiles showed `ums_member.level_id`, which nothing writes, defaulting to Lv.1. They now show no level rather than a false one. Showing other people's growth level would need a new public field; tell me if you want it.
- **Rank.** Both pages now use the same query: player accounts only (`role='player'`), the same tie-break, and no rank at 0 points. `/api/user/points/statistics` was rewritten on the leaderboard's rule. At 0 points, 成长中心 shows 「总积分榜」 with no 「第 — 名」, and 我的 shows the existing 「0」 no-rank marker (owner rule P-22). The full leaderboard has no merchant, club or platform accounts and no 0-point rows.
- **Distance.** The finish only added chapter mileage, which the editor never fills, so 「累计距离」 stayed 0. It now falls back to the theme's `total_mileage` (km, computed by the editor) when chapters have none. This only affects completions after deploy; old 0s stay.
- **F-5.** 我的队伍 no longer shows a row of four 0s, and 月度回顾 no longer shows a big 0 when everything is 0. The empty states already say it. 月度回顾's 「次路线完成」 counts city route sessions; a 自由探索 finish isn't a route, so it isn't counted. I kept the label as it is.
- Proof:
  - Real MySQL 8: `GrowthMissionProofMybatisIntegrationTest` 26/26 and `UmsMemberRankMySqlIT` pass. I added a merchant-and-0-point case.
  - `ApiGrowthControllerTest` (new 0-point case; the old 「TOP100%」 expectation was changed per F-4), `PlayerProgressionServiceImplTest` (mileage fallback).
  - Rig, with a fixture: 9009 at Lv.13, 1200 EXP, 300 points, plus merchant 9002 at 900 points.
    - `g3-member-lv13` and `g3-growth-lv13` both say Lv.13, with 「总积分榜 · 第 1 名」.
    - `g3-leaderboard-pts`: only 9009 is on the board; the merchant is not.
    - With 0 points (`g3-member`, `g3-growth`): Lv.1 on both and no rank.

### 3. Theme duration unit (j3 F7) — fixed at the source, with a migration

- The brief said "minutes stored, seconds formatted". Checking every caller showed:
  - `cms_topic.total_time` is commented 「单位秒」.
  - Seeded themes store seconds (360, 5400, 7200).
  - The mini-program, templates and admin all format it as seconds.
  - Only the editor (`calculateTotalTime`) stored a sum of minutes.
- Changing every display would have broken the seeded themes, so I fixed the writer instead. The editor now stores seconds (`totalTimeSeconds`), and no display code changed.
- New migration `migration_20261104_topic_total_time_seconds.sql`, registered in `ci/migration-dependencies.json`. It converts only rows whose `total_time` equals the sum of their nodes' minutes (all nodes, or host-only nodes), and has a runner assertion.
  - Rehearsed on a throwaway MySQL 8 with the real table DDL: editor row 270 → 16200; host-only 30 → 1800; seed 5400 untouched; empty 0 untouched. A second run changes nothing, and the assertion holds.
  - Old jar on the new schema: no schema change, and old code keeps writing minutes until the new jar is live. Run the migration after the new jar is up, or run it again then.
  - **PR #723 `## 影响分类` ③ needs this file added. I didn't edit the PR body.**
- `cms_topic_chapter.total_time` is never written, so I left it.
- Proof: `R11G3OrderTruthTest.topicTotalTimeIsStoredInSeconds`; `OWN_C03_17/18` still pass.

### 4. A real 姓名 at checkout (j3 F8) — fixed, no new column

- The checkout took 姓名 from the profile `name`, which the 昵称 editor overwrites. It now takes 姓名 from 参与人信息, the same store and the same pick rule (remembered id, else default, else first) as the activity checkout. It is not unique-checked.
- Phone still comes from the profile's WeChat-bound number; the phone rule is unchanged. With no phone, the row goes to 个人资料. With a phone but no name, it goes to 参与人信息.
- Checkout updates on return. `onShow` was calling `_fillCheckoutProfile`, which didn't exist; it now exists and refreshes the row.
- Tests: `self-play-order-idempotency` (4 tests updated to the new rule, plus a new onShow test).
- Unverified: I didn't walk the solo checkout in the rig.

### 5. Player pages

- **F-6 logged-in blank pages — fixed.** I checked f3 first. f3's 37a209aa already covered official-detail, and g2 covered the team pages (`TEAM_INFO_REFUSED`); I kept their code when merging. I added:
  - official-mine: server refusal shows the reason and 返回 (`g3-official-mine`).
  - coupon-qr: missing coupon shows 缺少券信息 · 返回 (`g3-coupon-qr`).
  - team pages: a missing-param state no longer auto-backs into nothing from a deep link (`g3-team-join`).
  - official-detail with a stale id: `g3-official-detail`.
  - Not walked: `play/merchant`, `square/detail`, `team/detail` with stale ids.
- **F-7 entries for closed features — fixed.**
  - `/api/config/features` now sends `userInvoice` and `freeExploreBundle`. They read the same switches the endpoints use.
  - 设置 hides 发票 while invoices are off (`g3-settings`).
  - The 发票抬头 form asks the server first, so it shows 「暂未开放」 when invoices are off.
  - 参与详情 hides:
    - 自由探索权益 while the bundle is off.
    - 定向成绩 unless the theme is 城市定向.
    - 我的角色卡 unless a probe of `/api/play/character` shows a created card or attributes. This adds one GET, recorded in the GET auth matrix.
  - free-explore hides its useless 刷新 button when the bundle is off.
- **F-8 chat — fixed.** A network failure shows a real 重试. A server refusal shows the reason and 返回消息列表 (`g3-chat-denied`).
- **F-10 — fixed.** With 0 points, checkout has no points row. When already signed up, the page shows only the green 「你已报名 · 查看我的报名」: no 报名已截止 and no blue box.
- **F-11 — fixed.** The club gate in 选择场次 is one line with no picture; its button stays in the bottom bar.
- **F-13 — fixed.** 节点 offline says it once.
- **F-14 — fixed.** 本局计分 without a game says 「链接已失效」 with a 返回 button (`g3-orient-score`).
- **F-12 captions — fixed.** Removed:
  - home banner sub-lines (`g3-home`)
  - 城瘾玩法 hero line and card tag and description lines (`g3-infomation`)
  - the 勋章墙 sentence
  - 设置 收藏 sub-label
  - 关于: the tagline and 「活动现场出示」 (`g3-about`)
  - 提现: two explanation blocks; the 联系客服提现 button says it (`g3-tixian`)
  - the 昵称 rule line, which now shows only as red text when broken
  - the duplicate 「自由探索」 next to the theme buttons; f3's 审核中 tag stays there
  - 「请返回重新进入」 under link-expired titles, removed from the shared `cy-empty` and `cy-state-shell` defaults and the named pages.

  营销消息 now says 「还没有商家消息」.

  Kept: legal and consent text, and danger-action consequence lines (f3's keep list).
- **F-9 (官方活动 detail and 主办控制台 light for players) — not done.** The page forces the merchant theme through `merchantTheme`, so making it dark needs a nav-colour change. Default: make player views dark, the same way official-mine does.
- **F-2 — done in item 1.**
- Several older tests encoded decisions you have now changed. I updated each and said so in it:
  - `dialog-contract-r3`: coupon-qr no longer auto-backs.
  - `uiux-player-state`, `ui-empty-cta`, `pr10-review-ui-fixes`, `baoming-*`, `p05`.
  - `participation-result-entry`: the harness answers the character probe; new negative tests added.
  - `c06-state`, `NpcFeatureFlagsTest`.

### 6. L-1 privacy page — left as designed

Opened directly, `pages/privacy/index` shows the consent sheet. Agreeing calls `navigateBack`; with nothing below, it falls back to `switchTab` home (`pages/privacy/index.js` around line 303). For you: someone who opened the privacy page only to read it lands on home after agreeing. The rig's page error there is the rig (H-2), not WeChat.

### 7. Report section 3 data states — partial

- Walked in the rig at `e5405048`:
  - empty, zero, non-zero growth (fixture), leaderboard with a merchant
  - chat refused
  - official-mine refused
  - coupon missing, invite missing, official event missing, score missing-param
  - teams empty, monthly empty
  - settings, about, withdraw, 玩法, home
- Not reached:
  - paid order detail and solo checkout: the purchase chain is broken upstream; see below.
  - posts, IM threads, coupons, roam sessions, capsules, invoices on.
- Short of about 20 states.

## Found while walking (for other lanes)

- The rig's own `chain_acceptance` sale_close chain now fails at `open-session`: 「主题还没开售，开售后才能开一场」. That is lane h4's 6d221050 (L5), which forbids opening a session before sale. The chain opens one before sale, so `chains.py player` can no longer produce an order. Either the chain or h4's rule needs to change.

## Gates

- Mini unit tests, full suite at `e5405048`: 16,139 pass, 0 fail.
- `ui-integrity.js`: `UI-GATE-0:PASS`.
  - Baseline: 3 profile/topic anchors and 7 official-mine anchors re-anchored for line shifts only, each verified as an identical line through git diff mapping; digests recomputed.
  - The baoming `userInfo` zero-use debt was retired, because the page now reads `userInfo.point`; `u4Field` minus 1.
  - No new baseline entries.
- `build-action-ledger.js --check`: pass, rebuilt on the merged tree. My controls: chat ±, official-mine +1, session-picker −1.
- Backend: targeted tests above plus real-MySQL ITs.
- Main package: `preflight-release` 2.96 MB, under 3,145,728 bytes.
- **CI on `e5405048` (my last code commit):**
  - Mini followup regression: green.
  - Isolated Redis recovery: green.
  - **Funds MySQL IT: red**, with 5 errors, all in `ClubSettlementSummaryMybatisIntegrationTest`: table `settlement_detail` doesn't exist in its fixture schema. That comes from lane h4's L7 settlement query change (31645fb8), not from my files. The other 726 ITs passed, including my rank and growth ITs.
  - Main CI was cancelled by later pushes, and so was the main CI on every later commit up to `4a5e1e73`. No lane got a quiet spell.
  - **No commit containing all my work has a green main CI yet.**

STATUS: PARTIAL - F-9 not done; about 20 data states and the paid-order and solo-checkout walks not reached (the rig purchase chain stops at h4's new open-session rule); no green main CI yet on a commit with all my work (cancelled by pushes; Funds IT red from h4's settlement fixture)
