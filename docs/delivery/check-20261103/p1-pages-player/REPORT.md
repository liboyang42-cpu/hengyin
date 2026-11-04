# Round 8 check: p1-pages-player (player and shared pages), first pass

- **Commit tested:** `ecef2f078ae3cb53fa41621c71d5fea3cf9124e4` (on `integ/launch-test-20261103`). Branch `claude/check-1104-p1-pages-player`, cut from that commit. Only files under `docs/delivery/check-20261103/` were added. No product code was changed.
- **This is the browser rig, not a phone.** Rig: `claude/cloud-walk-20261101-rig` + the club lane's `tools/harness-patches.diff` + j6's dummy OSS settings in `app.py`, with `up.sh` pinned to the commit above, `LANG=C.UTF-8`, and dockerd using `mirror.gcr.io`. Real jar, MySQL 8 and Redis 7 in Docker, real mini-program code in headless Chrome at 390×844. Shots are 2× JPEG under 200 KB in `shots/`.
- **Data:** fixture seed, then the candidate's own `ci/chain_acceptance` chains (platform → merchant_club → sale_close → player, all PASS on topic 990014 「E2E 探店日一期」). Then, through the real screens, player 9009 added a participant 「测试乙」 and bought a second session (activity 9, 11-14) and paid it through the rig pay seam (order 75). Walk player `walk-player-1` (member 990005) was made by real login and renamed through 个人资料. All data is fictional.
- **Identities:** player 9009 (has one used and one unused ticket), player 9004 (no club), new player 990005. Guest pages are j6's (F3) and are not repeated here.
- **Tools:** `tools/sweep.mjs` and `tools/lib.mjs` are p3's, copied unchanged, so both lanes sweep the same way: open the page from home with `navigateTo`, shoot it, then tap every visible tap-bound control one at a time, recording route change, modal, sheet, new text or "no visible effect", and reopen the page between taps. Write buttons (pay, submit, delete, logout, deregister, save, send, start) were skipped in the sweep and walked by hand where it mattered. `tools/drive.mjs` and `tools/launchpeek.mjs` are the hand walks. `logs/<id>.txt|json` hold every tap.
- **"探店日"** in fixture names is the old name for 自由探索 and is being renamed; it is not reported.

## 0. Page list and my rule

The merged list of all 157 pages is `../pages-list.md` (p1 88 + p2 37 + p3 32 = 157).

**Rule (p1):** every page in `app.json` that is **not** under `pages/merchant/`, `pages/coop/`, `pages/club/`, `pages/publish/`, `pages/templatedetail/`, and is not `pages/template/index`, `pages/topic/pricing/**`, `pages/topic/merchantinfo/merchantinfo`, `pages/topic/merchantapply/index` or `subpackageMember/mytemplate/mytemplate`. That matches p2's and p3's own lists exactly; nothing is counted twice and nothing is missing. app.json has no separate "shop" route.

Two pages in my group are really merchant pages that live in `subpackageMember`: `subpackageMember/coupon/coupon` and `subpackageMember/couponInfo/couponInfo` (light theme; only merchant screens link to them). p2 already reported the blank coupon page (p2 F-1); I opened both as a player only.

## 1. Step table

### 1a. Every p1 page

Each page was opened as player 9009 (sweep), with hand walks where noted. "Controls" = tap-bound controls actually tapped / of those, how many had no visible effect (most are the tab already selected, or a swiper slide off screen).

| # | Page | How reached (sweep = navigateTo from home as 9009, then every tap-bound control tapped) | States seen | Controls tapped / no visible effect | Result | Shots |
|---|---|---|---|---|---|---|
| 1 | `pages/index/index` | tab; launch; hand walk w01/w02 | full (1 theme), empty 编辑精选/附近 | 15 / 7 | PASS; captions F-12 | a01-home-* |
| 2 | `pages/talent/list/index` | tab | full (2 clubs), empty 帖文 (w60) | 8 / 1 | PASS | a02-talent-* |
| 3 | `pages/member/index/index` | tab; t01–t06 tabs 我的/帖文/成就/关于 | full | 8 / 1 | FAIL F-3 (Lv.1 vs Lv.13), F-4 | a03-member-* |
| 4 | `pages/roam/index` | tab | full | 6 / 0 | PASS (map is rig stand-in) | a04-roam-* |
| 5 | `pages/play/index` | deep link with a used registration; 票夹 → 开始游戏 (w44) | pass gate; 活动未开始 | 3 / 1 | PASS (see F-1 for the contradiction) | b01-play-* |
| 6 | `pages/play/merchant/index` | deep link `nodeId=1` (no such node) | **blank** | 0 / 0 | FAIL F-6 | b02-play-merchant-* |
| 7 | `pages/play/circle/index` | deep link; redirects to play | pass gate | 3 / 1 | PASS (redirect by design) | b03-play-circle-* |
| 8 | `pages/play/poi-challenge/index` | deep link without poi | error + 返回地图 | 2 / 1 | PASS (page error is rig nav, H-2) | b04-poi-challenge-* |
| 9 | `pages/activity/detail/index` | deep link id=13; 票夹/订单 links | full, running | 10 / 0 | PASS | c01-activity-detail-* |
| 10 | `pages/activity/baoming/baoming` | deep link activity 13 (already bought), activity 9 (new); topic → 选择场次 → 结算 walk w30–w39 | full, closed, participant missing, consent missing, paid | 6 / 2 | PASS with F-10, F-11 | c02-baoming-* |
| 11 | `pages/activity/list/index` | home banner 和朋友报名活动 | empty | 5 / 1 | PASS | c03-activity-list-* |
| 12 | `pages/activity/official-detail/index` | deep link id=1 (none exists) | **blank**, light theme | 0 / 0 | FAIL F-6, F-9 | c04-official-detail-* |
| 13 | `pages/activity/official-inbox/index` | deep link | empty | 0 / 0 | PASS | c05-official-inbox-* |
| 14 | `pages/activity/official-mine/index` | deep link; 官方活动 → 我的 | **blank** (no items) | 0 / 0 | FAIL F-6 | c06-official-mine-* |
| 15 | `pages/activity/official-organizer/index` | deep link without id | invalid-link, light theme | 1 / 0 | PASS; F-9, caption F-12 | c07-official-organizer-* |
| 16 | `subpackageA/pages/myproject/index` | deep link | empty | 3 / 1 | PASS | d01-myproject-* |
| 17 | `subpackageA/pages/infomation/infomation` | 设置 → 城瘾玩法 | full | 4 / 0 | PASS; captions F-12 | d02-infomation-* |
| 18 | `subpackageA/pages/infomationdetail/infomationdetail` | deep link id=1 (none) | missing | 1 / 0 | PASS | d03-infomationdetail-* |
| 19 | `subpackageA/pages/assetcenter/income-detail/income-detail` | 账户收益 → 明细 | empty | 4 / 1 | PASS | d04-income-detail-* |
| 20 | `subpackageA/pages/assetcenter/earnings/index` | 我的 → 账户余额 | empty ¥0.00 | 4 / 0 | PASS | d05-earnings-* |
| 21 | `subpackageA/pages/assetcenter/analysis/index` | 账户收益 → 分析 | empty ¥0.00 | 2 / 1 | PASS | d06-analysis-* |
| 22 | `subpackageP3/pages/growthcenter/index/index` | deep link; 成就 → 查看详情 | full | 2 / 0 | FAIL F-3, F-4 | e01-growth-* |
| 23 | `subpackageP3/pages/growthcenter/leaderboard/index` | 成长中心 → 查看榜单 | full (all 0) | 4 / 2 | FAIL F-4 | e02-leaderboard-* |
| 24 | `subpackageP3/pages/stamp-camera/index/index` | 收集册 → 打开集邮相机 | camera (rig image) | 1 / 0 | NOT VERIFIED: camera is a rig stand-in | e03-stamp-camera-* |
| 25 | `subpackageP3/pages/stamp-album/index/index` | 收集册 tab 邮票 | empty (light: owner P-3) | 4 / 1 | PASS | e04-stamp-album-* |
| 26 | `subpackageP3/pages/badge-wall/index/index` | 收集册 tab 勋章; 成就 → 收集册 | full (1 badge) | 5 / 3 | PASS; tagline F-12 | e05-badge-wall-* |
| 27 | `subpackageP3/pages/badge-3d/index` | 徽章 → 查看 | 3D coin | 0 / 0 | PASS (WebGL in rig) | e06-badge-3d-* |
| 28 | `subpackageP3/pages/object-cards/index/index` | 收集册 tab 藏品 | empty | 4 / 1 | PASS | e07-object-cards-* |
| 29 | `subpackageB/pages/im/list/index` | 发现 → 🔔 | full (1 system notice) | 4 / 1 | PASS | f01-im-list-* |
| 30 | `subpackageB/pages/im/chat/index` | deep link conversationId=1 (not mine) | error | 3 / 2 | FAIL F-8 | f02-im-chat-* |
| 31 | `pages/topic/index/index` | home card; search result | hero, detail, 选择场次 sheet | 15 / 3 | NOT VERIFIED tap (H-1); handler path PASS | g01-topic-* |
| 32 | `pages/square/detail/index` | deep link id=1 (no posts in rig) | **blank** + 返回上一页 | 1 / 0 | FAIL F-6; full state NOT VERIFIED | g02-square-detail-* |
| 33 | `pages/search2/index` | home search | idle | 19 / 0 | PASS | g03-search-* |
| 34 | `pages/search2/result/index` | search 「E2E」 | full (1 theme) | 9 / 3 | PASS | g04-search-result-* |
| 35 | `pages/searchmap/index` | search → 在地图上找 | empty map | 5 / 1 | PASS (rig map) | g05-searchmap-* |
| 36 | `pages/address/address` | deep link; 结算 → 参与人信息 | empty, full after w35 | 1 / 1 | PASS | h01-address-* |
| 37 | `pages/addressinfo/addressinfo` | 结算 → 参与人信息 (no saved person) | empty form, field errors, saved | 1 / 0 | PASS (red text under fields) | h02-addressinfo-* |
| 38 | `pages/shezhi/shezhi` | 我的 → ⚙ | full | 25 / 0 | PASS; F-7 (发票), caption F-12 | h03-shezhi-* |
| 39 | `pages/shezhi/about/index` | 设置 → 关于 | full | 5 / 0 | captions F-12; 个人码 NOT VERIFIED (wxacode, HARNESS) | h04-about-* |
| 40 | `pages/shezhi/marketing/index` | 设置 → 营销消息与优惠券 | empty | 0 / 0 | FAIL F-12 (caption promising what did not happen) | h05-marketing-* |
| 41 | `pages/gerenziliao/gerenziliao` | 我的 → avatar; walk w50–w54 rename | full, nickname sheet, saved | 8 / 1 | PASS (rename shows on home and 我的); caption F-12 | h06-gerenziliao-* |
| 42 | `pages/userinfo/userinfo` | deep link id=9009 (own) | same as 我的 | 7 / 1 | PASS | h07-userinfo-* |
| 43 | `pages/mylike/mylike` | 设置 → 我的收藏 | empty | 2 / 0 | PASS | h08-mylike-* |
| 44 | `pages/crop/index` | deep link without image | missing-image | 1 / 0 | PASS | h09-crop-* |
| 45 | `subpackageMember/free-explore/index` | deep link | feature-closed red block | 2 / 2 | FAIL F-7 | i01-free-explore-* |
| 46 | `subpackageMember/character/index` | 参与详情 → 我的角色卡 | template-empty | 2 / 2 | FAIL F-7; captions F-12 | i02-character-* |
| 47 | `subpackageMember/orient-score/index` | deep link without params; 参与详情 → 定向成绩 | missing-param; 「城市定向计分尚未启用」 | 1 / 1 | FAIL F-7 | i03-orient-score-* |
| 48 | `subpackageMember/invoice/index` | 设置 → 发票 | feature closed | 0 / 0 | FAIL F-7 | i04-invoice-* |
| 49 | `subpackageMember/invoice-detail/index` | deep link | feature closed | 0 / 0 | PASS (gate) | i05-invoice-detail-* |
| 50 | `subpackageMember/invoice-apply/index` | deep link | feature closed | 0 / 0 | PASS (gate) | i06-invoice-apply-* |
| 51 | `subpackageMember/invoice-titles/index` | deep link | feature closed | 0 / 0 | PASS (gate) | i07-invoice-titles-* |
| 52 | `subpackageMember/invoice-title-edit/index` | deep link | form shown although invoices are closed | 3 / 3 | FAIL F-7 | i08-invoice-title-edit-* |
| 53 | `subpackageMember/complaint/index` | navigateTo from home (w55) | form | 2 / 2 | PASS (captions already in j6 F11) | i09-complaint-* |
| 54 | `subpackageMember/coupon-wallet/index` | 关于 → 我的优惠券 | empty | 5 / 2 | PASS | i10-coupon-wallet-* |
| 55 | `subpackageMember/coupon-qr/index` | deep link without coupon | **blank** | 0 / 0 | FAIL F-6 | i11-coupon-qr-* |
| 56 | `subpackageMember/coupon/coupon` | deep link as player | blank (merchant page; p2 F-1) | 0 / 0 | see p2 F-1 | i12-coupon-* |
| 57 | `subpackageMember/couponInfo/couponInfo` | deep link as player | merchant form, light | 3 / 0 | merchant page in this package; p2 owns its use | i13-couponInfo-* |
| 58 | `subpackageMember/mycanyu/mycanyu` | deep link | full (2) | 8 / 3 | FAIL F-1 | i14-mycanyu-* |
| 59 | `subpackageMember/mycanyuinfo/mycanyuinfo` | 我的参与 → card | full (completed) | 2 / 0 | FAIL F-7 (定向成绩, 角色卡 rows) | i15-mycanyuinfo-* |
| 60 | `subpackageMember/myinvite/myinvite` | 关于 → 邀请好友 | empty | 0 / 0 | PASS | i16-myinvite-* |
| 61 | `subpackageMember/order/order` | deep link | full (2 orders) | 9 / 3 | FAIL F-1 | i17-order-* |
| 62 | `subpackageMember/orderinfo/orderinfo` | 订单 → 查看详情; after pay (w39–w41) | paid-unused, completed | 2 / 0 | FAIL F-1, F-2 | i18-orderinfo-* |
| 63 | `subpackageMember/points-history/index` | deep link (我的 → 成就 积分 links here) | empty | 0 / 0 | PASS | i19-points-* |
| 64 | `subpackageMember/report-mine/index` | 设置 → 帖子处置与申诉 | empty | 0 / 0 | PASS | i20-report-mine-* |
| 65 | `subpackageMember/report-detail/index` | deep link reportId=1 (not mine) | denied | 0 / 0 | PASS | i21-report-detail-* |
| 66 | `subpackageMember/appeal-mine/index` | 设置 → 我的申诉 | empty | 1 / 0 | PASS | i22-appeal-mine-* |
| 67 | `subpackageMember/signup/index` | 我的 → 票夹 (w10, w42) | empty (used ticket hidden by owner rule), full | 1 / 1 | PASS; QR flip NOT VERIFIED (H-1) | i23-signup-* |
| 68 | `subpackageMember/tixian/tixian` | 账户收益 → 提现 | static | 2 / 0 | captions F-12 | i24-tixian-* |
| 69 | `subpackageMember/tixianjilu/tixianjilu` | 提现 → 提现记录 | empty | 0 / 0 | PASS | i25-tixianjilu-* |
| 70 | `subpackageRoam/citynode-code/index` | deep link without poi | invalid | 1 / 0 | PASS | j01-citynode-code-* |
| 71 | `subpackageRoam/history/index` | 漫游 → 记录 | empty | 4 / 0 | PASS | j02-roam-history-* |
| 72 | `subpackageRoam/poi-detail/index` | deep link poiId=1 (none) | offline sheet | 4 / 2 | LOW F-13 | j03-poi-detail-* |
| 73 | `subpackageRoam/session/index` | deep link without id | missing | 1 / 0 | PASS | j04-roam-session-* |
| 74 | `subpackageRoam/nearby/index` | 漫游 → 打开地图 | full (5 places) | 13 / 1 | PASS (rig map) | j05-nearby-* |
| 75 | `subpackageRoam/citystamp/index` | deep link | camera sheet | 4 / 2 | NOT VERIFIED: camera stand-in | j06-citystamp-* |
| 76 | `subpackageRoam/my-teams/index` | 漫游 → 我的队伍 | empty | 4 / 2 | LOW F-5 | j07-my-teams-* |
| 77 | `subpackageRoam/official-routes/index` | deep link | empty | 0 / 0 | PASS | j08-official-routes-* |
| 78 | `subpackageRoam/official-route/index` | deep link id=1 (none) | missing | 0 / 0 | PASS | j09-official-route-* |
| 79 | `subpackageTools/capsule/index` | 漫游记录 tools | empty | 1 / 0 | PASS | k01-capsule-* |
| 80 | `subpackageTools/monthly-recap/index` | 漫游记录 → 月度回顾 | zeros | 5 / 4 | LOW F-5 | k02-monthly-recap-* |
| 81 | `subpackageTools/exploration-goals/index` | deep link | empty | 1 / 0 | PASS | k03-goals-* |
| 82 | `subpackageTools/anniversary/index` | 漫游记录 → 那年今日 | empty | 1 / 0 | PASS | k04-anniversary-* |
| 83 | `subpackageTools/exploration-export/index` | 漫游记录 → 导出 | form | 5 / 1 | PASS (导出 not run) | k05-export-* |
| 84 | `pages/privacy/index` | launch path (l01-privacy-launch-*), 设置 → 隐私与定位 | consent sheet; after agree → home | 2 / 2 | Lead explained (L-1) | l01-privacy-* |
| 85 | `pages/agreement/index` | 设置 → 用户服务协议 | full (legal) | 0 / 0 | PASS | l02-agreement-* |
| 86 | `pages/deregister/index` | 设置 → 账号注销 | blocked with reason | 4 / 4 | PASS | l03-deregister-* |
| 87 | `pages/team/detail/index` | deep link teamId=1 (none) | **blank** | 0 / 0 | FAIL F-6 | l04-team-detail-* |
| 88 | `pages/team/join/index` | deep link without code | **blank** | 0 / 0 | FAIL F-6 | l05-team-join-* |

### 1b. Hand walks (real taps, with read-back)

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| W1 | 9009 home → scroll → theme card | PASS | w01, w02 | `/api/topic/list` 1 row |
| W2 | 我的 → 票夹 with only a used ticket | PASS: 「还没有票」 (owner rule 2026-10-28: only usable tickets) | w10 | registration 74 `exploreProgress.completed=true, active=false` |
| W3 | Theme 990014 → 立即报名 (9004) | **NOT VERIFIED**: the tap reaches `touchEnd` but never `onHeroCta` in the rig (H-1). Calling the same handler opens 选择场次 | w20–w25 | — |
| W4 | 选择场次 → 周六 11.14 as 9004 (not in 执行俱乐部) | Gate shown; caption clipped (F-11). Join flow is j6 F4 | w26 | — |
| W5 | 结算 activity 9 as 9009 → 参与人信息 (none saved) → empty save → bad phone → good phone | PASS: red text under each field, then back on 结算 with 测试乙 | w30–w35 | `/api/user/address/action` 200 |
| W6 | Untick consent → button 「请先勾选信息共享同意」 → tick → 付款 → rig pay success | PASS: lands on 订单详情 ¥69 | w37–w39 | order 75: `payment_status=2, registration_status=2`, RIG-TXN-75 |
| W7 | 订单详情 of the new order | **FAIL F-1**: 「进行中 / 正在进行」 for a session on 11-14 | w40, w41 | activity 9 `start_date 2026-11-14 10:00` |
| W8 | 票夹 with the new ticket → 开始游戏 | PASS: play page 「活动未开始」, 4 nodes listed. Card flip to QR: NOT VERIFIED (H-1) | w42–w44 | — |
| W9 | 设置 → 个人资料 → 昵称 sheet → 「夜行测试员」 → 完成 → 保存 (new player 990005) | PASS: 「这个昵称可以用」, home says 「Hi, 夜行测试员!」, 我的 shows it | w50–w54 | `ums_member` 990005 `nickname=name=夜行测试员` |
| W10 | 投诉与建议 opened from home | PASS (its captions are j6 F11) | w55 | — |
| W11 | Home hero banners 成为俱乐部 / 成为商家 | PASS (sweep's "no effect" was an off-screen slide; handler opens `club/apply`) | — | `heroBannerList` routes checked |
| W12 | Static check: every `/pages/...` or `/subpackage.../...` route used in `navigateTo`/`redirectTo`/`reLaunch`/`url:` in product code is registered in app.json | PASS: none unregistered (regex check, not exhaustive) | — | — |

### 1c. Round 8 leads owned by p1

| Lead | What is true | Evidence |
|---|---|---|
| L-1 `pages/privacy/index` as a launch path jumps to home with a page error | **Explained.** Launched directly, the page shows the consent sheet (guest and logged-in alike, shots `l01-privacy-launch-noagree-*`). Agreeing calls `wx.navigateBack`; with no page below it falls back to `wx.switchTab('/pages/index/index')` (`pages/privacy/index.js:303-305`). So the jump to home is the designed fallback. The "page error" is the rig: its `navigateBack` returns a rejected promise even when a `fail` callback is given (`rig/mpweb/src/nav.js:218`), which WeChat does not do (H-2). Product question for the owner, not a bug: someone who opened the privacy page to read it lands on home after agreeing. | `l01-privacy-launch-guest*`, `l01-privacy-launch-e2e-openid-9009*` |
| L-2 async `scene-*` first open (settings, deep links, earnings) | No blank or flash on 设置, 账户收益, 订单详情, 参与详情 or 我的 in the rig. **NOT VERIFIED for a phone:** the rig bundles every subpackage at start, so subpackage download and `componentPlaceholder` are not exercised. | h03, d05, i18, i15, a03 |

## 2. Findings

Not repeated here (already reported elsewhere): guest blanks, guest captions and silent guest login (j6 F1, F3, F11, F12), 票夹 black screen on error (j6 F3), unpaid-order deadline and 「实付款」 (j6 F6, F9), first-payment participant error (j6 F5), activity page after joining (j6 F4), merchant coupon page (p2 F-1).

### F-1 HIGH: a ticket for a session ten days away says 「进行中 / 正在进行」 everywhere except the play page

- **Repro:** as 9009 on 2026-11-04, buy activity 9 (session 2026-11-14 10:00–23:59) and pay. Open 我的订单, 订单详情, and 我的参与.
- **Seen:**
  - 订单详情: badge 「进行中」, card 「正在进行 … 进行中」 (w40).
  - 我的订单: 「进行中」 on the card (i17).
  - 我的参与: 「进行中」 although that page has a 「未开始」 tab (i14).
  - 票夹 → 开始游戏: the play page says 「活动未开始」 (w44).
- The user is told the event is running when it starts in ten days.
- Read-back: `cms_activity` 9 `start_date 2026-11-14 10:00:00`; `cms_registration` 75 `registration_status=2, payment_status=2, verification_status=0`.
- Code: label map `subpackageMember/components/cy/scene-member-order-detail/index.js:24` (`in_progress: '正在进行'`); the state key comes from the order summary at `:925`. Probably the 自由探索 pass window is treated as "in progress" from purchase, while play uses the session start.

### F-2 LOW: order detail says the same status three or four times

- Unused: 「进行中」 badge, 「正在进行」 card title, 「进行中」 card badge, plus the helper 「完成后更新」 under 等待到店核销 (w40, w41).
- Used: 「已完成 / 已经完成 / 已完成」 plus 「本次订单已核销」 (i18).
- Layout fix: one status in the hero; the card shows only the redemption count (「已核销 0/4 家」), which is real data; drop 「完成后更新」 since the timeline step's open circle already says it hasn't happened.

### F-3 HIGH: the same player is Lv.1 on 我的 and Lv.13 in 成长中心

- **Repro:** 9009 → 我的: 「Lv.1 城市探索者」. 成就 tab: 「本周 +1200 EXP」. 成长中心: 「我的足迹 Lv.13 · 探索值 1,200 EXP」 (a03, a03-t04, e01).
- Two sources: `/api/play/growth` returns `level: 1`; `/api/growth/center` returns `levelNo: 13, expValue: 1200`. For the user's own profile `growthLevel` stays at its default 1 (`subpackageMember/components/cy/profile/index.js:460`; it is only set from `levelId` for other people's profiles, `:1292`).
- The user can't tell what level they are.

### F-4 LOW: points ranking with no points

- 成长中心 shows 「0 积分」 next to 「总积分榜 · 第 9 名」; 成就 shows 「TOP100%」 (e01, a03-t04).
- 完整排行榜 puts 平台运营, 商家老板 and 俱乐部主理人 on the podium, every row 「0 积分」 (e02). Merchant and platform accounts are ranked against players.
- Layout fix: with 0 points show no rank (the 0 itself says it); hide 0-point rows; keep the board to player accounts.

### F-5 LOW: rows of zeros

- 我的队伍: 「0 已通过 · 0 审核中 · 0 带队 · 0 未通过」 above 「还没有队伍」 (j07).
- 月度回顾 November: 「0 次路线完成」 although 9009 finished theme 990014 this month (成长中心 「完成主题 1」) (k02). Either the counter misses themes or the label should say what it counts.
- Layout fix: hide the counter row while every value is 0; the empty state already says it.

### F-6 HIGH: logged-in blank pages (title only, or nothing)

The page draws nothing and gives no way forward except the back arrow. Logged in as 9009, after 3 s or more:

| Page | How | Shot |
|---|---|---|
| `pages/activity/official-mine/index` | 官方活动 → 我的, with nothing published (a real state for most players) | c06 |
| `pages/activity/official-detail/index` | stale link `?id=1` | c04 |
| `pages/play/merchant/index` | stale link `?nodeId=1` | b02 |
| `pages/square/detail/index` | stale link `?id=1`: only a 「返回上一页」 bar | g02 |
| `pages/team/detail/index` | stale link `?teamId=1` | l04 |
| `pages/team/join/index` | no code | l05 |
| `subpackageMember/coupon-qr/index` | no coupon | i11 |

- The first one is not a broken link: any player who taps 我的 on 官方活动 without having published sees it.
- Layout fix: the empty and 「链接已失效 · 返回」 states other pages already use (e.g. `official-organizer`, `citynode-code`, `infomationdetail`).

### F-7 HIGH: entries that lead to features that are switched off

- 设置 → 发票 → 「发票功能暂未开放」 (h03, i04). The 发票抬头 edit form still opens and offers 保存 (i08).
- 参与详情 (a finished 自由探索 ticket) → 定向成绩 → 「城市定向计分尚未启用 / 城市定向新流程暂未开放 / 重新读取」 (i15-t02). A 自由探索 ticket shouldn't offer an orienteering score at all.
- 参与详情 → 我的角色卡 → 「尚未建卡 · 以下为角色模板的初始属性，尚未创建角色 · 模板暂无可展示属性 · 该主题尚未配置可见属性」 for a theme with no characters (i15-t01, i02).
- 自由探索权益 deep link: red error block 「自由探索套票暂未开放」 plus a 「刷新权益与预约结果」 button that can't change anything (i01).
- Users tap these from the main settings and from their own order, then hit a wall.
- Layout fix: don't render the row or entry while the feature is off or the theme has no such data (`pages/shezhi/shezhi.wxml:28`, `subpackageMember/components/scene-member-participation-detail/index.wxml:70`).

### F-8 HIGH: chat that fails to load is a dead end

- **Repro:** open `subpackageB/pages/im/chat/index?conversationId=1` (a conversation that isn't yours or no longer exists).
- Seen: header 「对话」, empty body, and only the footer line 「消息加载失败，点上方「重试」再试」 (f02). There is no 重试 above. The `cy-error … retry="重试"` branch (`subpackageB/pages/im/chat/index.wxml:41`) did not show.
- Footer text: `subpackageB/pages/im/chat/index.js:94`.

### F-9 LOW: 官方活动 detail and 主办控制台 are light on the player side

- The 官方活动 list is dark (c03). The detail it opens is light (`pages/activity/official-detail/index.wxml:1` `theme-merchant`), and so is 主办控制台 (`official-organizer/index.wxml:3`) (c04, c07).
- Owner rule: player side dark. If light is meant for organisers only, the detail page still needs to be dark for players.
- (收集册 is light by owner decision P-3, 2026-10-30; not reported.)

### F-10 LOW: 结算 shows 「积分抵扣 -¥0」 with a radio when the player has no points

- Seen in w30 and c02: a fake zero.
- Also on the already-bought session (c02), the page says it four ways: 「你已报名这场活动」, 「报名已截止」, a blue box 「暂不可报名 · 该场次已开始，不能再报名」, and a disabled 「暂不可报名」.
- Layout fix: hide the points row at 0; one state line (the green 「你已报名 · 查看我的报名」) and no blue box.

### F-11 LOW: 选择场次 sheet text runs under the button

- 「这一场由「执行俱乐部」执行，加入后查看」 wraps and its second line 「查看」 is hidden behind 「去加入俱乐部」 (w26). The large empty-state picture pushes it down.
- Layout fix: no picture in the 票种 slot of a sheet; one line, then the button.

### F-12 LOW: taglines, helper lines and secondary labels (owner's rule)

Each item names the layout change that would make it unnecessary. j6 F11 items are not repeated.

| Where | Text | Layout change |
|---|---|---|
| Home banners (a01) | 「聚集同好，发起属于你们的城市活动」, 「把空间、服务和好内容带给更多玩家」, 「和朋友一起，发现附近正在发生的有趣体验」 | The banner title is the action; drop the line |
| Theme page (w21) | 「自由探索」 twice (chip above the title and green text next to the buttons) | Keep the chip only |
| 城瘾玩法 (d02) | 「三种玩法,即开即玩」; per card 「组队 · 按顺序」 + 「按顺序打卡，到点集合出发」, 「随时 · 自己走」 + 「节点全开，随时开玩」, 「无终点 · 散步」 + 「边走边点亮城市」 | Card title + 去看看; the detail page explains |
| 收集册 勋章 (e05) | 「一枚勋章。一枚不少。」 | The count ring is enough |
| 设置 (h03) | 「我的收藏」 sub-label 「收藏的帖文与主题」 | The page's own tabs say it |
| 关于 (h04) | 「发现城市，也发现新的自己」; 「我的二维码」 sub 「活动现场出示」 | Drop both |
| 营销消息与优惠券 (h05) | 「完成一次真实报名后，可在这里管理对应商家的消息。」 shown to 9009, who has two paid registrations (API returns `[]`) | Empty state 「还没有商家消息」; the sentence is also not true for this user |
| 个人资料 昵称 sheet (w51) | 「2–24 个字，可用文字、数字和 _ # - . · 空格」 | Show the rule only as red text when the name breaks it (the sheet already does 「这个昵称可以用」) |
| 提现 (i24) | 「提现方式 · 添加客服微信，线下处理」 and 「提现说明 · 提现已改为客服协助线下处理，请添加平台客服微信确认。」 | One button 「联系客服提现」 says it |
| 我的角色卡 (i02) | three stacked lines (see F-7) | Don't show the page for a theme without characters |
| 主办控制台 / 核销码 / 本次漫游 / 本局计分 / 裁剪 (c07, j01, j04, i03, h09) | 「请返回重新进入」 under each error title | The 返回 button is the instruction |
| 订单详情 (w41) | 「完成后更新」 | See F-2 |

Legal and consent text (「同意将姓名、手机号提供给主办方，用于报名核验」, the privacy sheet, 用户服务协议) is not counted.

### F-13 LOW: 节点详情 says the same thing with two words

- 「节点未开放或已下线」 then 「据点未开放或已下线」 (j03). Terms rule: 节点. Drop the second line.

### F-14 LOW: 「缺少合法对局参数」 is developer wording

- 本局计分 opened without a game (i03). Plain words: 「链接已失效」, like the neighbouring pages.

### HARNESS (rig, not product)

- **H-1** On the theme page and the 票夹 card the rig loses the tap: `touchstart`/`touchend` handlers call `setData` and the rig re-renders the node before the click lands, so `onHeroCta` / `onCardTap` never run (proved by wrapping the handlers: only `touchEnd` fired). The handlers themselves work when called. Check on a phone.
- **H-2** The rig's `wx.navigateBack` rejects its promise even when a `fail` callback is passed, which shows up as a page error on 隐私, 裁剪 and 地点挑战. WeChat doesn't do this.
- **H-3** Cover images and home banner GIFs are not in the fresh database (404), so cards and banners have empty areas.
- **H-4** 关于 「玩家个人码待生成」 and the ticket QR need WeChat's code service.
- **H-5** Camera pages (集邮相机, 城市签) use a generated image.
- **H-6** Fixture players have `nickname` but no `name`, so 个人资料 showed 「城瘾玩家 / 未填写」 for 9009. A real new login fills both (member 990005), so this is not reported.

## 3. What I did not cover

- A phone: subpackage download and `componentPlaceholder` (L-2), native map, camera, QR codes, share cards, real fonts, keyboard and safe areas.
- Full states that need data the rig doesn't have: posts (`square/detail`), official activities and routes, teams (`team/detail`, `team/join` with a real code), IM conversations, points history, coupons, roam sessions, capsules, goals, invoices (feature off). Each opened in its empty or invalid-link state only.
- Write actions skipped on purpose: refund, deregister, logout, delete, post, export, capsule and goal creation, team create/join (j4), withdraw.
- Guest state of every page (j6), largest phone font (j4), every game type inside `pages/play` (j3).
- Tap-through of the theme page CTA and the 票夹 card flip on a phone (H-1).

STATUS: PARTIAL - first pass: all 88 p1 pages opened and swept in the rig, but full data states for about 20 pages, phone-only behaviour and the H-1 taps are not verified
