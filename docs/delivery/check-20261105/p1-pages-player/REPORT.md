# Round 8 check: p1-pages-player (player and shared pages), second pass

- **Commit tested:** `573c463b3b457b77544e63be4271a07d5d565e09`. Branch `claude/check-1105-p1-pages-player`, cut from that commit. Only files under `docs/delivery/check-20261105/p1-pages-player/` were added. No product code was changed.
- **This is the browser rig, not a phone.** Rig: `claude/cloud-walk-20261101-rig` + the club lane's `tools/harness-patches.diff` + j6's dummy OSS settings, `up.sh` pinned to the commit above, `LANG=C.UTF-8`, dockerd on `mirror.gcr.io`. Real jar, MySQL 8, Redis 7, real mini-program code in headless Chrome at 390×844. The candidate's own chains all PASS (platform 2.6 s, merchant_club 8.3 s, sale_close 1.7 s, player 159 s, topic 990014).
- **Page list and rule:** the same 88 pages and the same rule as the first pass (`docs/delivery/check-20261103/pages-list.md` and §0 of the first-pass report on `claude/check-1104-p1-pages-player`): every page in `app.json` not under `pages/merchant/`, `pages/coop/`, `pages/club/`, `pages/publish/`, `pages/templatedetail/`, and not `pages/template/index`, `pages/topic/pricing/**`, `pages/topic/merchantinfo/merchantinfo`, `pages/topic/merchantapply/index` or `subpackageMember/mytemplate/mytemplate`.
- **Three identities, every page each:**
  - **Guest** (`G-*`): every page opened with no login (bare route), plus a share-link pass on real ids (`GS-*`: theme 990014, activity 9, official event 2, post 1, profile 9009, team 2 and its invite code, 我的队伍).
  - **New player** 990005 (`walk-player-1`, made by real login, `N-*`): every page with the real ids below.
  - **Active player** 9009 (`A-*`): every page with real ids and every tap-bound control tapped (p3's `sweep.mjs`, unchanged). Write buttons were skipped in the sweep and walked by hand where it mattered (`W*`).
- **Fixtures (all fictional, all in the throwaway rig DB).** Through real HTTP as the acting user unless marked FIXTURE WRITE. Script: `tools/fixtures.py`, helper `tools/fx.py`.
  - Posts 1, 2 (9009) and 3 (9004): guideline acknowledged and published through `/api/v1/community`. 9004 liked and commented on post 1, and 9009 replied. 9009 bookmarked and liked post 3. 9004 and 9009 follow each other.
  - Chat: conversation 8 between 9004 and 9009, 3 messages.
  - Paid order 75: 9009 bought session 11-15 (activity 9) on the real 结算 screen and paid through the rig pay seam (W01–W09). Read-back: `payment_status=2, registration_status=2, RIG-TXN-75`.
  - Team 2 「周六夜行小队」 on activity 9, made by 9009. FIXTURE WRITE: `cms_topic.team_mode=1` on 990014, because the fixture theme had team mode off.
  - Official event 2 「外滩夜走官方场」, published by platform member 9001 via `/api/official/publish`, approved through `/business/audit/review`, with 9009 signed up.
    - The whitelist `official_publish_whitelist=9001` was set through the admin config API.
    - Event 1 was a mistaken first try with no times. I took it down with `/business/officialevent/1/status`.
  - Coupon 15 「到店赠饮一杯」, published by merchant 9002 through `/api/coupon/publish`. FIXTURE WRITE: one `sms_coupon_history` row for 9009, standing in for a play-reward grant; there is no player or admin endpoint for issuing one.
  - Points history: FIXTURE WRITE of three `ums_member_points_detail` rows for 9009 (+50, +20, −10), and `ums_member.point=60` to match.
  - Report 1, filed by 9009 against post 3 through `/api/v1/community/reports`.
  - Time capsule 1 for 9009.
  - FIXTURE TIME SHIFT: activity 9 moved to start 10:52 today (`seed-walk.py shift-session 9`), so that a started play could be walked (W40–W43). This happened after the F-1 checks.
- **Guest pages opened by bare route** show the invalid-link or login state. Their share-link state is in `GS-*`.
- Shots: `shots/` (2× JPEG < 200 KB). Logs: `logs/<shot>.json|txt`. Every shot was looked at.

## 1. First-pass findings: are they really gone?

| First pass | Now | Evidence |
|---|---|---|
| **F-1** future ticket says 进行中 | **Gone.** Order 75 (session 11-15) shows 「未开始」 on 订单详情, 我的订单 and 我的参与. 票夹 → 开始游戏 says 「活动未开始」. | W08, A-i17, A-i14, W31 |
| **F-2** status said 3–4 times | **Gone for unused orders:** one 「未开始」 in the hero, then 「已核销 0/4 家」, and no 「完成后更新」. **Partly kept for used orders:** 「已完成」 plus a card that says 「本次订单已核销」, which repeats the status. The count 「已核销 4/4 家」 would carry real data there. | W08, S-i18-orderinfo-used |
| **F-3** Lv.1 vs Lv.13 | **Gone.** 9009 is Lv.13 on 我的 and 成长中心. New player 990005 is Lv.1 on both. | A-a03, A-e01, N-a03, N-e01 |
| **F-4** ranking with no points; merchants and platform on the board | **Gone.** Only players are ranked and 0-point accounts are absent. 9009 (60, fixture) is 第 1 名. New player 990005 is 第 2 名 with 50, but see N-2 for where those 50 come from. | A-e01, A-e02, N-e01 |
| **F-5** rows of zeros | **我的队伍: gone.** The counter row is hidden for a new player and shows real numbers once there is a team (0/0/1/0). **月度回顾: not gone.** It says 「这一类本月没有记录」 for 路线, although 9009 finished theme 990014 on 11-05. **New spot:** 成长中心 for a new player shows 0 EXP / 0 枚 / 0 个 / 0 km (N-7). | N-j07, A-j07, A-k02, N-e01 |
| **F-6** logged-in blank pages | **Mostly gone, two left:** <br>- Now give a reason plus 返回: 官方活动「我发布的」 (「无官方发布权限 · 返回」), missing official event, missing team, invite with no code, coupon QR with no coupon. <br>- **Still blank:** `square/detail` for a post that doesn't exist (only a 「返回上一页」 bar), and `play/merchant` opened by link (stale `nodeId=1` and real `nodeId=71` alike). <br>- **New blank:** `mycanyuinfo` for someone else's record (N-4). | S-c06, S-c04-missing, S-l04, S-l05, S-i11, S-g02-missing, S-b02, A-b02, N-i15 |
| **F-7** entries to switched-off features | **Gone.** 设置 has no 发票 row. 参与详情 for 自由探索 shows no 定向成绩 or 角色卡 row. The 自由探索权益 page has no refresh button. 发票抬头 edit is gated. **Remaining:** opened by link, 我的角色卡 still shows four stacked lines for a theme with no characters (N-8). | A-h03, A-i15, A-i01, A-i08, A-i02 |
| **F-8** chat load-failure dead end | **Gone.** A conversation that isn't yours shows 「无权查看该会话 · 返回消息列表」. **Leftover:** a footer 「不能在这个会话发消息」 that repeats it, and the full chat's header says 「对话」 instead of the other person's name (N-10). | S-f02-im-chat-notmine, A-f02 |
| **F-9** 官方活动 detail and 主办控制台 light | **Gone.** Both are dark for a player. | A-c04, A-c07 |
| **F-10** 「积分抵扣 -¥0」 with no points; bought session said four ways | **Partly gone:** <br>- With 0 points the row is hidden. <br>- A bought session now shows one green 「你已报名这场活动 · 查看我的报名」, and the blue box is gone. <br>- **New, worse:** a player who has points sees the row on a 自由探索 session and can tick it, which then blocks the purchase (N-1). | W01, S-c02-baoming-bought, A-c02-t01 |
| **F-11** 选择场次 gate text hidden under the button | **Gone.** One line 「这一场由「执行俱乐部」执行，加入后查看」, then 去加入俱乐部. The large picture is still in the 票种 slot before a date is picked. | W52, W51 |
| **F-12** taglines and helper lines | **Gone:** home banners, theme page duplicate 自由探索, 城瘾玩法 card captions, 勋章 tagline, 设置 收藏 sub-label, 关于 tagline and QR sub, 营销消息 sentence (it now lists merchants), nickname rule, 提现 captions, 「完成后更新」, and 「请返回重新进入」 on 主办控制台 / 核销码 / 本次漫游 / 本局计分 / 裁剪. **Still there:** 我的角色卡 lines (N-8). 「请返回重新进入」 under many other p1 error titles (N-6). | A-a01, A-g01, A-d02, A-e05, A-h03, A-h04, A-h05, A-i24, W08 |
| **F-13** 节点详情 said twice | **Gone.** One line 「节点未开放或已下线 · 返回地图」. | A-j03 |
| **F-14** 「缺少合法对局参数」 | **Gone.** 「链接已失效 · 返回」. | A-i03 |
| **L-1** privacy as launch path → home with page error | **Unchanged and explained the same way.** Agreeing on the launched page falls back to home, and the page error is the rig's `navigateBack` (H-2). | L1-privacy-launch-guest, L1-privacy-launch-e2e-openid-9009 |
| **L-2** async `scene-*` first open | **NOT VERIFIED for a phone.** No blank or flash on 设置, 账户收益, 订单详情, 参与详情 or 我的 in the rig, but the rig loads every subpackage at start. | A-h03, A-d05, A-i18, A-i15, A-a03 |
| **H-1** rig loses some taps | **Still there.** It hit the theme CTA, the play-page stop cards and the stop sheet's store row. I called those handlers directly, which is how W43 and W51 were reached. | — |

## 2. Hand walks (real taps, with read-back)

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| W1 | 9009: 结算 for activity 9 (11-15) → 参与人信息 → empty save → bad phone → good phone | PASS: red text under each field, then back on 结算 with 测试甲 | W01–W06 | — |
| W2 | Tick 信息共享同意 → 付款 → rig pay success | PASS: lands on 订单详情 「未开始」 ¥69. The consent tick icon is invisible in the rig (H-6). | W07, W08 | order 75 `payment_status=2, registration_status=2, verification_status=0`, RIG-TXN-75 |
| W3 | 票夹 → 开始游戏 (session not started) | PASS: foil pack with 「活动未开始」. Tapping the pack shows the 4 stops. LOW: the 「活动未开始」 banner sits on top of the theme title (N-15). | W30–W35 | — |
| W4 | 参与详情 (finished order 74) → 开始玩 | PASS: 「本轮探索已完成 · 看看这一天的收获」 | W37 | — |
| W5 | After the fixture time shift, 票夹 → 开始游戏 → stop 1 | Stop sheet opens (handler called, H-1). The store row's tap is lost, so `play/merchant` full state is NOT VERIFIED. | W40–W45 | — |
| W6 | Post 2: tap ☆ | PASS: star fills | W20, W21 | bookmark listed by `/me/bookmarks` |
| W7 | 9004: theme 990014 → 选择场次 → date 11.5 | PASS (F-11) | W50–W52 | — |
| W8 | New player 990005: invite link for team 2 → 接受邀请并加入 | Hung at 「加入中…」 with no request (rig lacks `wx.getRandomValues`, same as j4). With the API stubbed in-page: 「持有这一场的票才能加入」 and the button greys out (N-14). | W60, W61 | `/api/team/command` 200, refusal |
| W9 | Guest: 漫游 tab → 我的队伍 | **FAIL N-5:** `/api/team/my` 401 → 「队伍获取失败 · 点按重试」, and retrying loops | W70, W71 | 401 ×2 per try |

## 3. Every p1 page

States: Guest, New player and Active player columns give the first text on screen (cut to 70 characters). Controls = tap-bound controls the active-player sweep actually tapped / of those, how many showed no visible change. Most of those are the tab already selected, an off-screen swiper slide, or a change the sweep's text diff can't see (W6). Shots: `G-<id>.jpg`, `N-<id>.jpg`, `A-<id>-a-open.jpg` (+ `-a-scroll*`, `-t0*` for taps). The 88 rows match the first pass; with p2's 37 and p3's 32 they still make 157.

| # | Page | Reached as active player (9009) | Guest | New player 990005 | Active player 9009 | Controls tapped / no visible effect | Result | Shots |
|---|---|---|---|---|---|---|---|---|
| 1 | `pages/index/index` | tab; launch | 成为俱乐部 / 成为商家 / 和朋友报名活动 / Hi, 旅人! / 上海市 / 编辑精选 / 暂时没有精选主题 / 附近活动 / 你附近暂 | 成为俱乐部 / 成为商家 / 和朋友报名活动 / Hi, 城瘾用户! / 玩家 / · 上海市 / 编辑精选 / 暂时没有精选主题 / 附近 | 成为俱乐部 / 成为商家 / 和朋友报名活动 / Hi, 玩家二号! / 玩家 / · 上海市 / 编辑精选 / 暂时没有精选主题 / 附近 | 14 / 7 | PASS; role chip N-9 | G-a01-home, N-a01-home, A-a01-home-*  |
| 2 | `pages/talent/list/index` | tab | 发现 / 帖文 / 俱乐部 / 创建你自己的俱乐部 / 创建俱乐部 / 城瘾跑团 / 1 位成员 / 免费加入 / 执行俱乐部 / 2 位成 | 发现 / 帖文 / 俱乐部 / 创建你自己的俱乐部 / 创建俱乐部 / 城瘾跑团 / 1 位成员 · 1 个近期团 / 免费加入 / 执行俱 | 3 / 发现 / 帖文 / 俱乐部 / 创建你自己的俱乐部 / 创建俱乐部 / 已加入 / 执行俱乐部 / 2 位成员 · 0 个近期团 / | 8 / 1 | PASS | G-a02-talent, N-a02-talent, A-a02-talent-*  |
| 3 | `pages/member/index/index` | tab | 登录后查看我的主页 / 登录 | 主页 / 城瘾用户 / Lv.1 城市探索者 / 连续探索：0天 / 0 / 好友 / 0 / 关注 / 0 / 粉丝 / 票夹 / 我的  | 主页 / 玩家二号 / Lv.13 城市探索者 / 连续探索：0天 / 1 / 好友 / 1 / 关注 / 1 / 粉丝 / 票夹 / 我的 | 9 / 1 | PASS (F-3 gone) | G-a03-member, N-a03-member, A-a03-member-*  |
| 4 | `pages/roam/index` | tab | 漫游 / 开始漫游 / 漫游护照 / NO.01 / 开始漫游 / NO.02 / 打开地图 / 寻找组队 / 附近正在组队 / NO.03 | 漫游 / 开始漫游 / 漫游护照 / NO.01 / 开始漫游 / NO.02 / 打开地图 / 寻找组队 / NO.03 / 我的队伍 / | 漫游 / 开始漫游 / 漫游护照 / NO.01 / 开始漫游 / NO.02 / 打开地图 / 寻找组队 / NO.03 / 我的队伍 / | 5 / 0 | PASS | G-a04-roam, N-a04-roam, A-a04-roam-*  |
| 5 | `pages/play/index` | 票夹 → 开始游戏; 参与详情 → 开始玩 | 输入地址 / 链接已失效，请返回重新进入 / 返回活动详情 | 输入地址 / 请先购买自玩通行证 / 获取通行证 | 输入地址 / 请先购买自玩通行证 / 获取通行证 | 3 / 1 | PASS via 票夹 (W30–W35, W40–W43); hand-made deep link with registrationId gives 「请先购买自玩通行证」, not a user path | G-b01-play, N-b01-play, A-b01-play-*  |
| 6 | `pages/play/merchant/index` | link `?nodeId=71` and `?nodeId=1`; from play page the stop sheet opens (W43) but its store tap is lost in the rig | 登录后查看 / 登录 | 9:41 / ▮▮▮ ◔ ▭ / ··· ◉ | 9:41 / ▮▮▮ ◔ ▭ / ··· ◉ | 0 / 0 | FAIL F-6 (blank by link); full state NOT VERIFIED (H-1) | G-b02-play-merchant, N-b02-play-merchant, A-b02-play-merchant-*  |
| 7 | `pages/play/circle/index` | link | → pages/index/index · 成为俱乐部 / 成为商家 / 和朋友报名活动 / Hi, 旅人! / 上海市 / 编辑精选 /  | → pages/play/index · 输入地址 / 请先购买自玩通行证 / 获取通行证 | 输入地址 / 请先购买自玩通行证 / 获取通行证 | 3 / 1 | PASS (redirects to play) | G-b03-play-circle, N-b03-play-circle, A-b03-play-circle-*  |
| 8 | `pages/play/poi-challenge/index` | link | 挑战暂时打不开 / 没有找到这个地点 / 重试 / 返回地图 | 挑战暂时打不开 / 没有找到这个地点 / 重试 / 返回地图 | 挑战暂时打不开 / 没有找到这个地点 / 重试 / 返回地图 | 2 / 1 | PASS (no poi data) | G-b04-poi-challenge, N-b04-poi-challenge, A-b04-poi-challenge-*  |
| 9 | `pages/activity/detail/index` | link id=9 (bought, future), id=13 (running) | 活动详情 / 活动链接无效 / 请返回重新进入 / 返回活动列表 | 活动详情 / 这一场由「执行俱乐部」执行，加入后查看 / 去加入俱乐部 | 活动详情 / 未开始 / E2E 探店日一期 / 活动时间 · 11月15日 10:00 - 11月15日 23:59 / 分类 / — / | 10 / 0 | PASS | G-c01-activity-detail, N-c01-activity-detail, A-c01-activity-detail-*  |
| 10 | `pages/activity/baoming/baoming` | activity 9 bought walk W01–W09; activity 10 with 60 points | 结算 / 无法打开报名页 / 请返回重新进入 / 去发现活动 | 结算 / 这一场由「执行俱乐部」执行，加入后查看 / 入会后可报名 / 去加入俱乐部 | 结算 / 参与人信息 / * / 测试甲 / 13800000009 / 活动信息 / E2E 探店日一期 / 2026年11月16日 周一 | 5 / 0 | FAIL N-1 (points row on 自由探索) | G-c02-baoming, N-c02-baoming, A-c02-baoming-*  |
| 11 | `pages/activity/list/index` | home banner 和朋友报名活动 | 官方活动 / 进行中 / 即将 / 已结束 / 我的 / 进行中 · 共 0 个活动 / 邀约 / 暂无进行中的活动 | 官方活动 / 进行中 / 即将 / 已结束 / 我的 / 进行中 · 共 1 个活动 / 邀约 / 封面暂不可用 / 外滩夜走官方场 / 报 | 官方活动 / 进行中 / 即将 / 已结束 / 我的 / 进行中 · 共 1 个活动 / 邀约 / 封面暂不可用 / 外滩夜走官方场 / 报 | 6 / 1 | PASS | G-c03-activity-list, N-c03-activity-list, A-c03-activity-list-*  |
| 12 | `pages/activity/official-detail/index` | 官方活动 → card (event 2, signed up); id=1 (v1 event taken down); id=999 | 链接已失效 / 返回活动列表 | 官方活动 / 城瘾官方 / 发起 / 参与 / 1 / 活动周期 / 3小时 / 开放时间 / 11.20–11.20 / 活动范围 / 上 | 官方活动 / 城瘾官方 / 发起 / 参与 / 1 / 我的进度 / 0 / 已完成 / 活动周期 / 3小时 / 开放时间 / 11.20 | 2 / 1 | FAIL N-3; F-9 gone | G-c04-official-detail, N-c04-official-detail, A-c04-official-detail-*  |
| 13 | `pages/activity/official-inbox/index` | 官方活动 → 邀约 | 登录后查看 / 登录 / 官方活动邀约 | 官方活动邀约 / 暂无待处理的活动职责 | 官方活动邀约 / 暂无待处理的活动职责 | 0 / 0 | PASS (empty) | G-c05-official-inbox, N-c05-official-inbox, A-c05-official-inbox-*  |
| 14 | `pages/activity/official-mine/index` | 官方活动 → 我的 as non-publisher | 登录后查看 / 登录 / 我发布的 | 我发布的 / 无官方发布权限 / 返回 | 我发布的 / 无官方发布权限 / 返回 | 1 / 0 | PASS (F-6 gone) | G-c06-official-mine, N-c06-official-mine, A-c06-official-mine-*  |
| 15 | `pages/activity/official-organizer/index` | link without id | 主办控制台 / 链接已失效 / 返回 | 主办控制台 / 链接已失效 / 返回 | 主办控制台 / 链接已失效 / 返回 | 1 / 0 | PASS | G-c07-official-organizer, N-c07-official-organizer, A-c07-official-organizer-*  |
| 16 | `subpackageA/pages/myproject/index` | link | 我的项目 / 主题 / 活动 / 模板 / 登录后查看我的项目 / 登录 | 我的项目 / 主题 / 活动 / 模板 / 还没有主题 / 去发布 | 我的项目 / 主题 / 活动 / 模板 / 还没有主题 / 去发布 | 3 / 1 | PASS (empty) | G-d01-myproject, N-d01-myproject, A-d01-myproject-*  |
| 17 | `subpackageA/pages/infomation/infomation` | 设置 → 城瘾玩法 | 城瘾玩法 / 城市定向 / 去看看 / 自由探索 / 去看看 / 漫游 / 去看看 / 现成玩法 | 城瘾玩法 / 城市定向 / 去看看 / 自由探索 / 去看看 / 漫游 / 去看看 / 现成玩法 | 城瘾玩法 / 城市定向 / 去看看 / 自由探索 / 去看看 / 漫游 / 去看看 / 现成玩法 | 4 / 0 | PASS (F-12 gone) | G-d02-infomation, N-d02-infomation, A-d02-infomation-*  |
| 18 | `subpackageA/pages/infomationdetail/infomationdetail` | link id=1 (no articles exist) | 玩法详情 / 链接已失效 / 返回 | 玩法详情 / 这篇玩法说明不在了 / 返回玩法列表 | 玩法详情 / 这篇玩法说明不在了 / 返回玩法列表 | 1 / 0 | PASS | G-d03-infomationdetail, N-d03-infomationdetail, A-d03-infomationdetail-*  |
| 19 | `subpackageA/pages/assetcenter/income-detail/income-detail` | 账户收益 → 明细 | 收益明细 / 全部 / 创作收益 / 品牌收益 / 主办分润 / 登录后查看收益明细 / 登录 | 收益明细 / 全部 / 创作收益 / 品牌收益 / 主办分润 / 还没有收益记录 | 收益明细 / 全部 / 创作收益 / 品牌收益 / 主办分润 / 还没有收益记录 | 4 / 1 | PASS (empty) | G-d04-income-detail, N-d04-income-detail, A-d04-income-detail-*  |
| 20 | `subpackageA/pages/assetcenter/earnings/index` | 我的 → 账户余额 | 账户收益 / 登录后查看账户收益 / 登录 | 账户收益 / 账户收益（元） / ¥0.00 / 提现 / 明细 / 分析 / 更多 / 还没有收益 / 去发布路线 | 账户收益 / 账户收益（元） / ¥0.00 / 提现 / 明细 / 分析 / 更多 / 还没有收益 / 去发布路线 | 4 / 0 | PASS (empty) | G-d05-earnings, N-d05-earnings, A-d05-earnings-*  |
| 21 | `subpackageA/pages/assetcenter/analysis/index` | 账户收益 → 分析 | 分析 / 收入 / 提现 | 分析 / 收入 / 提现 / 11月收入 / ¥0.00 / 6月 / 7月 / 8月 / 9月 / 10月 / 11月 / 收入来源 /  | 分析 / 收入 / 提现 / 11月收入 / ¥0.00 / 6月 / 7月 / 8月 / 9月 / 10月 / 11月 / 收入来源 /  | 2 / 1 | PASS (empty) | G-d06-analysis, N-d06-analysis, A-d06-analysis-*  |
| 22 | `subpackageP3/pages/growthcenter/index/index` | 成就 → 查看详情 | 成长中心 / 登录后查看成长记录 / 登录 | 成长中心 / 城瘾用户 / 50 / 积分 / 总积分榜 · 第 2 名 / 查看榜单 / 我的足迹 / Lv.1 / 探索值 / 0 /  | 成长中心 / 玩家二号 / 60 / 积分 / 总积分榜 · 第 1 名 / 查看榜单 / 我的足迹 / Lv.13 / 探索值 / 1,2 | 2 / 0 | PASS (F-3, F-4 gone); LOW N-7 zero rows for new player | G-e01-growth, N-e01-growth, A-e01-growth-*  |
| 23 | `subpackageP3/pages/growthcenter/leaderboard/index` | 成长中心 → 查看榜单 | 完整排行榜 / 周榜 / 总榜 / 积分榜 / 成长榜 / 登录后查看排行榜 / 登录后可查看完整排行榜 / 登录 | 完整排行榜 / 周榜 / 总榜 / 2 / 城瘾用户 / 50 / 积分 / 1 / 玩家二号 / 60 / 积分 / 积分榜 / 成长榜  | 完整排行榜 / 周榜 / 总榜 / 2 / 城瘾用户 / 50 / 积分 / 1 / 玩家二号 / 60 / 积分 / 积分榜 / 成长榜  | 4 / 2 | PASS (F-4 gone); guest N-6 | G-e02-leaderboard, N-e02-leaderboard, A-e02-leaderboard-*  |
| 24 | `subpackageP3/pages/stamp-camera/index/index` | 收集册 → 打开集邮相机 | 双指缩放裁切 57% | 双指缩放裁切 57% | 双指缩放裁切 57% | 1 / 0 | NOT VERIFIED (camera stand-in) | G-e03-stamp-camera, N-e03-stamp-camera, A-e03-stamp-camera-*  |
| 25 | `subpackageP3/pages/stamp-album/index/index` | 收集册 邮票 | 收集册 / 勋章 / 邮票 / 藏品 / 登录后查看自己的册子 / 去登录 | 收集册 / 勋章 / 邮票 / 藏品 / 0 / 枚 / 还没有邮票 / 打开集邮相机 | 收集册 / 勋章 / 邮票 / 藏品 / 0 / 枚 / 还没有邮票 / 打开集邮相机 | 4 / 1 | PASS (empty; light by owner P-3) | G-e04-stamp-album, N-e04-stamp-album, A-e04-stamp-album-*  |
| 26 | `subpackageP3/pages/badge-wall/index/index` | 收集册 勋章 | 登录后查看我的勋章 / 登录 | 还没有点亮勋章 / 去探索 | 收集册 / 勋章 / 邮票 / 藏品 / 1 / 共 1 / 已点亮 / E2E 探店日一期 · 通关 / 2026-11-05 | 5 / 3 | PASS (F-12 gone) | G-e05-badge-wall, N-e05-badge-wall, A-e05-badge-wall-*  |
| 27 | `subpackageP3/pages/badge-3d/index` | 徽章 → 查看 | 徽章 / 徽章 | 徽章 / 徽章 | 徽章 / 徽章 | 0 / 0 | PASS; N-10 | G-e06-badge-3d, N-e06-badge-3d, A-e06-badge-3d-*  |
| 28 | `subpackageP3/pages/object-cards/index/index` | 收集册 藏品 | 收集册 / 勋章 / 邮票 / 藏品 / 全部 / 登录后查看自己的册子 / 去登录 | 收集册 / 勋章 / 邮票 / 藏品 / 全部 / 还没有收过东西 | 收集册 / 勋章 / 邮票 / 藏品 / 全部 / 还没有收过东西 | 4 / 1 | PASS (empty) | G-e07-object-cards, N-e07-object-cards, A-e07-object-cards-*  |
| 29 | `subpackageB/pages/im/list/index` | 发现 → 🔔 | 消息 / 登录后查看消息 / 登录 | 消息 / 收件箱 / 发件箱 / 还没有消息 / 去发现找人 | 消息 / 收件箱 / 2 / 发件箱 / 标已读 / 免打扰 / 删除 / 系统通知 / 02:16 / 报名成功 / 2 / 标已读 /  | 7 / 1 | PASS (full) | G-f01-im-list, N-f01-im-list, A-f01-im-list-*  |
| 30 | `subpackageB/pages/im/chat/index` | 消息 → 玩家小城 (conversation 8); `?conversationId=1` | 更多 / 对话 / 这个会话无法打开 / 请返回重新进入 / 链接已失效，请返回重新进入 | 更多 / 对话 / 无权查看该会话 / 返回消息列表 / 不能在这个会话发消息 | 更多 / 对话 / 02:13 / 你好，周六那场还有位置吗？ / 有的，我已经报名了，可以一起。 / 好，那到时见。 | 4 / 2 | PASS (full); F-8 gone; N-10 | G-f02-im-chat, N-f02-im-chat, A-f02-im-chat-*  |
| 31 | `pages/topic/index/index` | home card; search | 无法打开主题 / 请返回重新进入 / 返回发布广场 / 确认订单 / 参与人信息 / 补全姓名和手机号 / 主题信息 / 费用明细 / 票种 | 自由探索 / E2E 探店日一期 / 端到端 / 查看详情与报名 / 确认订单 / 参与人信息 / 补全姓名和手机号 / 主题信息 / E2 | 自由探索 / E2E 探店日一期 / 端到端 / 查看详情与报名 / 确认订单 / 参与人信息 / 补全姓名和手机号 / 主题信息 / E2 | 14 / 1 | PASS; CTA tap NOT VERIFIED (H-1) | G-g01-topic, N-g01-topic, A-g01-topic-*  |
| 32 | `pages/square/detail/index` | link id=1 (own, 2 comments), id=3 (other), id=999 | 没有找到这条帖文 / 请返回重新进入 / 返回上一页 | 玩家二号 / 45分前 / 关注 / 周末沿着苏州河走了一圈，傍晚的光线很好。 / 2 / 2 / 玩家二号 / 44分前 / 欢迎，周六一 | 玩家二号 / 16分前 / 周末沿着苏州河走了一圈，傍晚的光线很好。 / 1 / 2 / 玩家二号 / 16分前 / 欢迎，周六一起。 /  | 9 / 3 | PASS (full); FAIL F-6 for a missing post | G-g02-square-detail, N-g02-square-detail, A-g02-square-detail-*  |
| 33 | `pages/search2/index` | home search | 搜索 / 筛选 / 推荐搜索 / 骑行 / 城市 / 外滩 / 书店 / 美术馆 / 摄影 / 苏州河 / 菜场 / 类别 / 城市漫步 / | 搜索 / 筛选 / 推荐搜索 / 骑行 / 城市 / 外滩 / 书店 / 美术馆 / 摄影 / 苏州河 / 菜场 / 类别 / 城市漫步 / | 搜索 / 筛选 / 推荐搜索 / 骑行 / 城市 / 外滩 / 书店 / 美术馆 / 摄影 / 苏州河 / 菜场 / 类别 / 城市漫步 / | 19 / 0 | PASS | G-g03-search, N-g03-search, A-g03-search-*  |
| 34 | `pages/search2/result/index` | search 「E2E」 | 全部 / 主题 1 / 活动 0 / 商家 5 / 登录后搜索内容和俱乐部 / 登录 / 主题 / E2E 探店日一期 / 上海市黄浦区咖啡 | 全部 / 主题 1 / 活动 0 / 俱乐部 0 / 商家 0 / 主题模板 0 / 玩法模板 0 / 主题 / E2E 探店日一期 / 上 | 全部 / 主题 1 / 活动 0 / 俱乐部 0 / 商家 0 / 主题模板 0 / 玩法模板 0 / 主题 / E2E 探店日一期 / 上 | 9 / 3 | PASS | G-g04-search-result, N-g04-search-result, A-g04-search-result-*  |
| 35 | `pages/searchmap/index` | search → 在地图上找 | 定位 / 这一带还没有活动 | 定位 / 这一带还没有活动 | 定位 / 这一带还没有活动 | 5 / 1 | PASS (rig map) | G-g05-searchmap, N-g05-searchmap, A-g05-searchmap-*  |
| 36 | `pages/address/address` | 结算 → 参与人信息 | 登录后查看 / 登录 / 参与人信息 | 参与人信息 / 还没有参与人信息 / 新增参与人信息 | 参与人信息 / 测试甲 / 13800000009 / 编辑 / 新增参与人信息 | 3 / 1 | PASS (full) | G-h01-address, N-h01-address, A-h01-address-*  |
| 37 | `pages/addressinfo/addressinfo` | 结算 → 参与人信息 → 新增 | 参与人信息 / 姓名 / * / 手机号 / * / 保存参与人信息 | 参与人信息 / 姓名 / * / 手机号 / * / 保存参与人信息 | 参与人信息 / 姓名 / * / 手机号 / * / 保存参与人信息 | 1 / 0 | PASS (red text under fields W04–W05) | G-h02-addressinfo, N-h02-addressinfo, A-h02-addressinfo-*  |
| 38 | `pages/shezhi/shezhi` | 我的 → ⚙ | 设置 / 登录后查看账号信息 / 成为俱乐部主理人 / 成为商家 / 城瘾玩法 / 我的收藏 / 隐私与定位 / 营销消息与优惠券 / 帖文 | 设置 / 个人资料 / 成为俱乐部主理人 / 成为商家 / 城瘾玩法 / 我的收藏 / 隐私与定位 / 营销消息与优惠券 / 帖文处置与申诉 | 设置 / 个人资料 / 成为俱乐部主理人 / 成为商家 / 城瘾玩法 / 我的收藏 / 隐私与定位 / 营销消息与优惠券 / 帖文处置与申诉 | 23 / 0 | PASS (F-7 发票 gone) | G-h03-shezhi, N-h03-shezhi, A-h03-shezhi-*  |
| 39 | `pages/shezhi/about/index` | 设置 → 关于 | 关于 / 城瘾 / 版本 开发版本 / 我的二维码 / 玩家个人码待生成 / 用户服务协议 / 联系我们 / 素材署名 / game-ico | 关于 / 城瘾 / 版本 开发版本 / 我的二维码 / 玩家个人码待生成 / 用户服务协议 / 联系我们 / 素材署名 / game-ico | 关于 / 城瘾 / 版本 开发版本 / 我的二维码 / 玩家个人码待生成 / 用户服务协议 / 联系我们 / 素材署名 / game-ico | 5 / 0 | PASS (F-12 gone); 个人码 HARNESS | G-h04-about, N-h04-about, A-h04-about-*  |
| 40 | `pages/shezhi/marketing/index` | 设置 → 营销消息与优惠券 | 登录后查看 / 登录 / 营销消息与优惠券 | 营销消息与优惠券 / 还没有商家消息 | 营销消息与优惠券 / 主 / 主理人策划工作室 / 站内活动消息 / 商家优惠券 / 探 / 探店日样板面包 / 站内活动消息 / 商家优惠 | 0 / 0 | PASS (full, F-12 gone) | G-h05-marketing, N-h05-marketing, A-h05-marketing-*  |
| 41 | `pages/gerenziliao/gerenziliao` | 我的 → avatar | 登录后查看 / 登录 | 更换背景 / 城瘾用户 / 昵称 / 城瘾用户 / 个人介绍 / 介绍一下你自己 / 手机号 / 未绑定 / 探索作品 / 未填写 / 联系 | 更换背景 / 玩家二号 / 昵称 / 玩家二号 / 个人介绍 / 介绍一下你自己 / 手机号 / 139****0009 / 探索作品 /  | 8 / 0 | PASS; N-11 | G-h06-gerenziliao, N-h06-gerenziliao, A-h06-gerenziliao-*  |
| 42 | `pages/userinfo/userinfo` | link id=9004 | 个人主页 / 这个主页链接不完整 / 请返回重新进入 / 回到我的主页 | 主页 / 玩家小城 / 0 / 好友 / 1 / 关注 / 0 / 粉丝 / 关注 / 发消息 / 帖文 / 成就 / 关于 / 个人介绍  | 主页 / 玩家小城 / 1 / 好友 / 1 / 关注 / 1 / 粉丝 / 已关注 / 发消息 / 帖文 / 成就 / 关于 / 个人介绍 | 7 / 2 | PASS (full) | G-h07-userinfo, N-h07-userinfo, A-h07-userinfo-*  |
| 43 | `pages/mylike/mylike` | 设置 → 我的收藏 | 我的收藏 / 帖文 / 主题 / 登录后查看收藏 / 登录 | 我的收藏 / 帖文 / 主题 / 暂无收藏的帖文 | 我的收藏 / 帖文 / 主题 / 暂无收藏的主题 | 2 / 0 | PASS (full 帖文 tab) | G-h08-mylike, N-h08-mylike, A-h08-mylike-*  |
| 44 | `pages/crop/index` | link without image | 裁剪 / 没有可裁剪的图片 / 返回上一页 | 裁剪 / 没有可裁剪的图片 / 返回上一页 | 裁剪 / 没有可裁剪的图片 / 返回上一页 | 1 / 0 | PASS | G-h09-crop, N-h09-crop, A-h09-crop-*  |
| 45 | `subpackageMember/free-explore/index` | link (bundle off) | 自由探索权益 / 请登录后重新进入本人订单 / 刷新权益与预约结果 | 自由探索权益 / 自由探索套票暂未开放 | 自由探索权益 / 自由探索套票暂未开放 | 0 / 0 | PASS (F-7 gone: no refresh) | G-i01-free-explore, N-i01-free-explore, A-i01-free-explore-*  |
| 46 | `subpackageMember/character/index` | link | 我的角色卡 / 缺少有效主题 / 请从参与详情重新进入 | 我的角色卡 / 角色模板 / 尚未建卡 / 以下为角色模板的初始属性，尚未创建角色 / 模板暂无可展示属性 / 该主题尚未配置可见属性 /  | 我的角色卡 / 角色模板 / 尚未建卡 / 以下为角色模板的初始属性，尚未创建角色 / 模板暂无可展示属性 / 该主题尚未配置可见属性 /  | 2 / 2 | LOW N-8 (deep link only; entry gone, F-7) | G-i02-character, N-i02-character, A-i02-character-*  |
| 47 | `subpackageMember/orient-score/index` | link without params | 链接已失效 / 返回 / ‹ / 本局计分 | 链接已失效 / 返回 / ‹ / 本局计分 | 链接已失效 / 返回 / 本局计分 | 1 / 0 | PASS (F-14 gone) | G-i03-orient-score, N-i03-orient-score, A-i03-orient-score-*  |
| 48 | `subpackageMember/invoice/index` | link | 我的发票 / 登录后查看发票 / 微信登录 | 我的发票 / 发票功能暂未开放 | 我的发票 / 发票功能暂未开放 | 0 / 0 | PASS (gate; entry gone F-7) | G-i04-invoice, N-i04-invoice, A-i04-invoice-*  |
| 49 | `subpackageMember/invoice-detail/index` | link | 发票详情 / 登录后查看发票 / 微信登录 | 发票详情 / 发票功能暂未开放 | 发票详情 / 发票功能暂未开放 | 0 / 0 | PASS (gate) | G-i05-invoice-detail, N-i05-invoice-detail, A-i05-invoice-detail-*  |
| 50 | `subpackageMember/invoice-apply/index` | link | 申请开票 / 登录后申请开票 / 微信登录 | 申请开票 / 发票功能暂未开放 | 申请开票 / 发票功能暂未开放 | 0 / 0 | PASS (gate); N-12 | G-i06-invoice-apply, N-i06-invoice-apply, A-i06-invoice-apply-*  |
| 51 | `subpackageMember/invoice-titles/index` | link | 发票抬头 / 登录后管理发票抬头 / 微信登录 | 发票抬头 / 发票功能暂未开放 | 发票抬头 / 发票功能暂未开放 | 0 / 0 | PASS (gate) | G-i07-invoice-titles, N-i07-invoice-titles, A-i07-invoice-titles-*  |
| 52 | `subpackageMember/invoice-title-edit/index` | link | 发票抬头 / 登录后管理发票抬头 / 微信登录 | 发票抬头 / 发票功能暂未开放 | 发票抬头 / 发票功能暂未开放 | 0 / 0 | PASS (F-7 gone) | G-i08-invoice-title-edit, N-i08-invoice-title-edit, A-i08-invoice-title-edit-*  |
| 53 | `subpackageMember/complaint/index` | link | 投诉与建议 / 仅限已报名并支付的活动，平台会介入处理 / 登录后发起投诉与查看进展 / 登录后可选择活动并查看自己的处理进展 / 登录 | 投诉与建议 / 仅限已报名并支付的活动，平台会介入处理 / 暂无可投诉的活动 / 我的处理进展 / 刷新进展 | 投诉与建议 / 仅限已报名并支付的活动，平台会介入处理 / 投诉活动 / * / 请选择要投诉的活动 / 投诉类型 / 请选择投诉类型（可选 | 4 / 2 | LOW (tagline j6 F11 still there) | G-i09-complaint, N-i09-complaint, A-i09-complaint-*  |
| 54 | `subpackageMember/coupon-wallet/index` | 关于 → 我的优惠券 | 优惠券 / 全部 / 待使用 / 已使用 / 已过期 / 登录后查看我的优惠券 / 登录 | 优惠券 / 全部 / 待使用 / 已使用 / 已过期 / 这里还没有优惠券 | 优惠券 / 全部 / 待使用 / 已使用 / 已过期 / 到店赠饮一杯 / 待使用 / 到店出示即可 / 上海市黄浦区咖啡 / 有效期至 2 | 7 / 3 | PASS (full) | G-i10-coupon-wallet, N-i10-coupon-wallet, A-i10-coupon-wallet-*  |
| 55 | `subpackageMember/coupon-qr/index` | 优惠券 → 出示核销码; no id; new player id=7 | 缺少券信息 / 返回 | 优惠券 / 二维码暂时没能生成，请稍后重试 / 重试 | 到店赠饮一杯 / 二维码 52s 后自动刷新 / 出示给商家扫码核销 / 刷新二维码 | 5 / 0 | PASS (full QR); F-6 gone; LOW N-13 for another user's coupon | G-i11-coupon-qr, N-i11-coupon-qr, A-i11-coupon-qr-*  |
| 56 | `subpackageMember/coupon/coupon` | link as player | 登录后查看 / 登录 / 优惠券 | 优惠券 | 优惠券 | 0 / 0 | blank (merchant page, p2 F-1) | G-i12-coupon, N-i12-coupon, A-i12-coupon-*  |
| 57 | `subpackageMember/couponInfo/couponInfo` | link as player | 优惠券设置 / 优惠券名称 / * / 优惠券日期 / * / 开始日期 / - / 结束日期 / 投放数 / * / 优惠券类型 / *  | 优惠券设置 / 优惠券名称 / * / 优惠券日期 / * / 开始日期 / - / 结束日期 / 投放数 / * / 优惠券类型 / *  | 优惠券设置 / 优惠券名称 / * / 优惠券日期 / * / 开始日期 / - / 结束日期 / 投放数 / * / 优惠券类型 / *  | 3 / 0 | merchant page (p2) | G-i13-couponInfo, N-i13-couponInfo, A-i13-couponInfo-*  |
| 58 | `subpackageMember/mycanyu/mycanyu` | 我的 → 我的参与 | 我的参与 / 全部 / 未开始 / 进行中 / 已完成 | 我的参与 / 全部 / 未开始 / 进行中 / 已完成 / 暂无参与记录 | 我的参与 / 全部 / 未开始 / 进行中 / 已完成 / 未开始 / E2E 探店日一期 / 11.15 - 11.15 / 线下活动 · | 6 / 2 | PASS (F-1 gone) | G-i14-mycanyu, N-i14-mycanyu, A-i14-mycanyu-*  |
| 59 | `subpackageMember/mycanyuinfo/mycanyuinfo` | 我的参与 → card (75 future, 74 done); new player opening id=75 | 参与详情 / 缺少参与记录 / 请返回重新进入 / 返回参与列表 | 参与详情 | 参与详情 / E2E 探店日一期 / 2026.11.15 - 2026.11.15 / 线下活动 / 未开始 / 5-1 链路真跑 / 开 | 0 / 0 | PASS (F-7 gone); FAIL N-4 blank for another user's record | G-i15-mycanyuinfo, N-i15-mycanyuinfo, A-i15-mycanyuinfo-*  |
| 60 | `subpackageMember/myinvite/myinvite` | 关于 → 邀请好友 | 邀请记录 / 登录后查看邀请记录 / 登录 | 邀请记录 / 还没有人接受你的邀请 | 邀请记录 / 还没有人接受你的邀请 | 0 / 0 | PASS (empty) | G-i16-myinvite, N-i16-myinvite, A-i16-myinvite-*  |
| 61 | `subpackageMember/order/order` | 我的 → 我的订单 | 我的订单 / 全部 / 待支付 / 未开始 / 进行中 / 已完成 / 退款中 / 退款成功 / 不可退款 / 人工售后 / 登录后查看订单 | 我的订单 / 全部 / 待支付 / 未开始 / 进行中 / 已完成 / 退款中 / 退款成功 / 不可退款 / 人工售后 / 还没有城市路线 | 我的订单 / 全部 / 待支付 / 未开始 / 进行中 / 已完成 / 退款中 / 退款成功 / 不可退款 / 人工售后 / 订单号： /  | 8 / 2 | PASS (F-1 gone) | G-i17-order, N-i17-order, A-i17-order-*  |
| 62 | `subpackageMember/orderinfo/orderinfo` | after pay W08; 订单 → 查看详情 | → subpackageMember/order/order · 我的订单 / 全部 / 待支付 / 未开始 / 进行中 / 已完成 / 退 | → subpackageMember/order/order · 我的订单 / 全部 / 待支付 / 未开始 / 进行中 / 已完成 / 退 | ¥69.00 / E2E 探店日一期 / 11-15 10:00–23:59 / 未开始 / 已核销 0/4 家 / 订单明细 / 订单号  | 2 / 0 | PASS (F-1, F-2 gone; used state see F-2) | G-i18-orderinfo, N-i18-orderinfo, A-i18-orderinfo-*  |
| 63 | `subpackageMember/points-history/index` | 成就 → 积分 | 积分明细 / 积分明细加载失败 / 重试 | 积分明细 / 还没有积分记录 | 积分明细 / 全部 / 获得 / 使用 / 2026年11月 / 报名抵扣 / 11月5日 10:16 / -10 / 余额 60 / 发布 | 3 / 3 | PASS (full); FAIL N-2 (new player) | G-i19-points, N-i19-points, A-i19-points-*  |
| 64 | `subpackageMember/report-mine/index` | 设置 → 帖文处置与申诉 | 登录后查看 / 登录 / 我的举报 | 我的举报 / 还没有举报记录 | 我的举报 / 全部 / 处理中 / 已有结论 / 内容举报 · 垃圾广告或站外导流 / 已提交 / 编号 1 · 2026-11-05 02 | 3 / 2 | PASS (full) | G-i20-report-mine, N-i20-report-mine, A-i20-report-mine-*  |
| 65 | `subpackageMember/report-detail/index` | 我的举报 → row | 举报进度 / 链接已失效 | 举报进度 / 暂时不能查看这条举报 / 仅举报人可查看 | 举报进度 / 内容举报 · 垃圾广告或站外导流 / 已提交 / 平台已收到举报，等待受理 / 编号 1 · 提交于 2026-11-05 0 | 0 / 0 | PASS (full) | G-i21-report-detail, N-i21-report-detail, A-i21-report-detail-*  |
| 66 | `subpackageMember/appeal-mine/index` | 设置 → 我的申诉 | 我的申诉 / 登录后查看申诉 / 微信登录 | 我的申诉 / 账号信誉 / 正常 / 还没有申诉记录 | 我的申诉 / 账号信誉 / 正常 / 还没有申诉记录 | 1 / 0 | PASS (empty) | G-i22-appeal-mine, N-i22-appeal-mine, A-i22-appeal-mine-*  |
| 67 | `subpackageMember/signup/index` | 我的 → 票夹 | 票夹 / 登录后查看票夹 / 登录 | 票夹 / 还没有票 | 票夹 / E2E 探店日一期 / 11月15日 10:00 · 上海市黄浦区咖啡 · 已核销 0/4 家 / E2E 探店日一期 / 生成中 | 2 / 1 | PASS (full); N-9; QR HARNESS | G-i23-signup, N-i23-signup, A-i23-signup-*  |
| 68 | `subpackageMember/tixian/tixian` | 账户收益 → 提现 | → pages/index/index · 成为俱乐部 / 成为商家 / 和朋友报名活动 / Hi, 旅人! / 上海市 / 编辑精选 /  | 提现申请 / 提现记录 / 可提现余额 / ¥0.00 / 联系客服提现 | 提现申请 / 提现记录 / 可提现余额 / ¥0.00 / 联系客服提现 | 2 / 0 | PASS (F-12 gone) | G-i24-tixian, N-i24-tixian, A-i24-tixian-*  |
| 69 | `subpackageMember/tixianjilu/tixianjilu` | 提现 → 提现记录 | 提现记录 / 登录后查看提现记录 / 登录 | 提现记录 / 还没有发起提现 | 提现记录 / 还没有发起提现 | 0 / 0 | PASS (empty) | G-i25-tixianjilu, N-i25-tixianjilu, A-i25-tixianjilu-*  |
| 70 | `subpackageRoam/citynode-code/index` | link without poi | 这个核销码无法打开 / 返回 | 这个核销码无法打开 / 返回 | 这个核销码无法打开 / 返回 | 1 / 0 | PASS (F-12 gone) | G-j01-citynode-code, N-j01-citynode-code, A-j01-citynode-code-*  |
| 71 | `subpackageRoam/history/index` | 漫游 → 记录 | 登录后查看 / 登录 / 漫游记录 | 漫游记录 / 那年今日 / 月度回顾 / 导出 / 还没有漫游记录 / 出发第一次漫游 | 漫游记录 / 那年今日 / 月度回顾 / 导出 / 还没有漫游记录 / 出发第一次漫游 | 4 / 0 | PASS (empty) | G-j02-roam-history, N-j02-roam-history, A-j02-roam-history-*  |
| 72 | `subpackageRoam/poi-detail/index` | link poiId=1 | 节点详情 / 找不到这个节点 / 返回地图 | 节点详情 / 节点未开放或已下线 / 返回地图 | 节点详情 / 节点未开放或已下线 / 返回地图 | 4 / 2 | PASS (F-13 gone) | G-j03-poi-detail, N-j03-poi-detail, A-j03-poi-detail-*  |
| 73 | `subpackageRoam/session/index` | link without id | 本次漫游 / 找不到这次漫游 / 返回漫游历史 | 本次漫游 / 找不到这次漫游 / 返回漫游历史 | 本次漫游 / 找不到这次漫游 / 返回漫游历史 | 1 / 0 | PASS (F-12 gone) | G-j04-roam-session, N-j04-roam-session, A-j04-roam-session-*  |
| 74 | `subpackageRoam/nearby/index` | 漫游 → 打开地图 | 全部 / 主题队伍 / 地点玩法 / 商家 / 登录后查看附近信息 / 去登录 / 附近 | 全部 / 主题队伍 / 地点玩法 / 商家 / 附近 · 0 支队伍 · 5 处地点 / 热门活动 / E2E 探店日一期 / 上海市黄浦区 | 全部 / 主题队伍 / 地点玩法 / 商家 / 附近 · 0 支队伍 · 5 处地点 / 热门活动 / E2E 探店日一期 / 上海市黄浦区 | 13 / 1 | PASS (rig map) | G-j05-nearby, N-j05-nearby, A-j05-nearby-*  |
| 75 | `subpackageRoam/citystamp/index` | link | 9:41 / ▮▮▮ ◔ ▭ / ··· ◉ | 9:41 / ▮▮▮ ◔ ▭ / ··· ◉ | 9:41 / ▮▮▮ ◔ ▭ / ··· ◉ | 4 / 2 | NOT VERIFIED (camera stand-in) | G-j06-citystamp, N-j06-citystamp, A-j06-citystamp-*  |
| 76 | `subpackageRoam/my-teams/index` | 漫游 → 我的队伍 | 地图 / 我的队伍 / — / 已通过 / — / 审核中 / — / 带队 / — / 未通过 / 我的 / 附近招募 / 队伍获取失败  | 地图 / 我的队伍 / 我的 / 附近招募 / 还没有队伍 / 去地图找队伍 / 附近在招募 / 附近暂时没有在招募的队伍 | 地图 / 我的队伍 / 0 / 已通过 / 0 / 审核中 / 1 / 带队 / 0 / 未通过 / 我的 / 附近招募 / 我带的队 /  | 4 / 1 | PASS (full; F-5 zero row hidden for new player); FAIL N-5 guest | G-j07-my-teams, N-j07-my-teams, A-j07-my-teams-*  |
| 77 | `subpackageRoam/official-routes/index` | link | 官方线路 / 还没有上架的官方线路 | 官方线路 / 还没有上架的官方线路 | 官方线路 / 还没有上架的官方线路 | 0 / 0 | PASS (empty; no routes) | G-j08-official-routes, N-j08-official-routes, A-j08-official-routes-*  |
| 78 | `subpackageRoam/official-route/index` | link id=1 | 线路详情 / 这条线路不存在或已下架 | 线路详情 / 这条线路不存在或已下架 | 线路详情 / 这条线路不存在或已下架 | 0 / 0 | PASS (missing) | G-j09-official-route, N-j09-official-route, A-j09-official-route-*  |
| 79 | `subpackageTools/capsule/index` | 漫游记录 tools | 时光胶囊 / 请先登录后使用 / 重试 / 我的胶囊 / 还没有胶囊 | 时光胶囊 / 我的胶囊 / 还没有胶囊 | 时光胶囊 / 我的胶囊 / 1 枚 / 2026-11-05 写下 / 365 天 / 2027-11-05 开启 | 2 / 0 | PASS (full); guest N-5 | G-k01-capsule, N-k01-capsule, A-k01-capsule-*  |
| 80 | `subpackageTools/monthly-recap/index` | 漫游记录 → 月度回顾 | 月度回顾 / 请先登录后使用 / 重试 / 路线 / 首次地点 / 城市签 / 这一类本月没有记录 | 月度回顾 / 2026 年 11 月 / 路线 / 首次地点 / 城市签 / 这一类本月没有记录 | 月度回顾 / 2026 年 11 月 / 路线 / 首次地点 / 城市签 / 这一类本月没有记录 | 5 / 4 | LOW F-5 still (themes not counted) | G-k02-monthly-recap, N-k02-monthly-recap, A-k02-monthly-recap-*  |
| 81 | `subpackageTools/exploration-goals/index` | link | 探索目标 / 请通过现有登录流程登录后再使用 / 重试 / 我的目标 | 探索目标 / 我的目标 / 还没有探索目标 | 探索目标 / 我的目标 / 还没有探索目标 | 1 / 0 | PASS (empty); guest N-5 | G-k03-goals, N-k03-goals, A-k03-goals-*  |
| 82 | `subpackageTools/anniversary/index` | 漫游记录 → 那年今日 | 那年今日 / 换一天 / 请通过现有登录流程登录后再使用 / 重试 | 那年今日 / 11.05 / 换一天 / 这一天还没有回忆 | 那年今日 / 11.05 / 换一天 / 这一天还没有回忆 | 1 / 0 | PASS (empty); guest N-5 | G-k04-anniversary, N-k04-anniversary, A-k04-anniversary-*  |
| 83 | `subpackageTools/exploration-export/index` | 漫游记录 → 导出 | 导出探索记录 / 请通过现有登录流程登录后再使用 / 重试 / 开始 / 结束（不含） / 准备导出 | 导出探索记录 / 近 30 天 / 今年 / 近一年 / 开始 / 2026-10-07 / 结束（不含） / 2026-11-06 / 准 | 导出探索记录 / 近 30 天 / 今年 / 近一年 / 开始 / 2026-10-07 / 结束（不含） / 2026-11-06 / 准 | 5 / 1 | PASS (导出 not run); guest N-5 | G-k05-export, N-k05-export, A-k05-export-*  |
| 84 | `pages/privacy/index` | 设置 → 隐私与定位; launch | 隐私保护 / 查看《隐私保护指引》 / 首页城市自动定位 / 开启 / 关闭 / 附近的人位置分享 / 关闭 / 漫游定位 / 本机未在为漫 | 隐私保护 / 查看《隐私保护指引》 / 首页城市自动定位 / 开启 / 关闭 / 附近的人位置分享 / 关闭 / 漫游定位 / 本机未在为漫 | 成为俱乐部 / 成为商家 / 和朋友报名活动 / Hi, 玩家二号! / 玩家 / · 上海市 / 编辑精选 / 暂时没有精选主题 / 附近 | 2 / 2 | see L-1 | G-l01-privacy, N-l01-privacy, A-l01-privacy-*  |
| 85 | `pages/agreement/index` | 设置 → 用户服务协议 | 用户服务协议 / 欢迎使用城瘾。本协议由西安吾令文化传媒有限公司（以下简称“我们”）与城瘾用户共同订立。请在注册、登录或使用服务前仔细阅读并 | 用户服务协议 / 欢迎使用城瘾。本协议由西安吾令文化传媒有限公司（以下简称“我们”）与城瘾用户共同订立。请在注册、登录或使用服务前仔细阅读并 | 用户服务协议 / 欢迎使用城瘾。本协议由西安吾令文化传媒有限公司（以下简称“我们”）与城瘾用户共同订立。请在注册、登录或使用服务前仔细阅读并 | 0 / 0 | PASS (legal) | G-l02-agreement, N-l02-agreement, A-l02-agreement-*  |
| 86 | `pages/deregister/index` | 设置 → 账号注销 | 注销账号 / 登录后查看账号注销条件 / 登录 | 注销账号 / 注销后账号无法恢复；公开内容将匿名化，依法需要留存的交易记录将停止用于其他目的。 / 查看《账号注销须知》 / 我已阅读并理解 | 注销账号 / 暂时无法注销 / 请先处理以下事项： / · 存在待支付或未核销的有效报名 / 我已处理，重新检查 | 4 / 4 | PASS (blocked with reason) | G-l03-deregister, N-l03-deregister, A-l03-deregister-*  |
| 87 | `pages/team/detail/index` | 我的队伍 → 进入队伍; teamId=999 | 队伍详情 / 队伍链接不完整 / 请返回重新进入 / 返回上一页 | 队伍详情 / 无权查看该队伍 / 返回上一页 | 队伍详情 / 招募中 / 周六夜行小队 / 1 / 4 / 当前成员 / 11月15日 02:00 / 场次开始 / 邀请队友 / 公开招募 | 1 / 0 | PASS (full); F-6 gone; time HARNESS H-7 | G-l04-team-detail, N-l04-team-detail, A-l04-team-detail-*  |
| 88 | `pages/team/join/index` | link with code as owner, new player (W61), guest; no code | 加入队伍 / 邀请链接不完整 / 请从队长分享的邀请重新进入 / 返回上一页 | 加入队伍 / 周六夜行小队 / 1 / 4 / 当前成员 / 仅邀请 / 加入方式 / 接受邀请并加入 | 加入队伍 / 周六夜行小队 / 1 / 4 / 当前成员 / 仅邀请 / 加入方式 / 查看队伍 | 1 / 0 | PASS; F-6 gone; LOW N-14 | G-l05-team-join, N-l05-team-join, A-l05-team-join-*  |

## 4. Findings (new or still open at 573c463b)

Already reported elsewhere and not repeated: 收集册 light (owner P-3), merchant coupon page blank (p2 F-1), complaint-page captions (j6 F11, still there: 「仅限已报名并支付的活动，平台会介入处理」), and play-page game details (j3).

### N-1 HIGH: 结算 offers 积分抵扣 on a 自由探索 session, then refuses the purchase when it's ticked
- **Repro:** 9009 with 60 points opens 结算 for activity 10 (自由探索, ¥69) and ticks the 积分抵扣 radio.
- **Seen (A-c02-t01):**
  - The row reads 「积分抵扣（抵扣积分0） -¥0」.
  - A blue box 「暂不可报名 · 自由探索不支持积分抵扣，请关闭积分后按全价购买」 appears, plus the same text as a toast.
  - The button changes to 「暂不可报名」.
- Before ticking, the row already showed 「-¥0」.
- The page offers a control that it then refuses, and the deduction is never more than ¥0.
- Code: `pages/activity/baoming/baoming.wxml:146` shows the row whenever the player has points and the ticket price is above 0. Nothing checks whether this product can take points.
- **Layout fix:** don't render the row when the quote's point deduction is 0 or the product refuses points.

### N-2 HIGH: a new player has 50 points that their 积分明细 doesn't explain
- **Repro:** log in as a brand-new player (990005).
- **Seen:**
  - 成长中心: 「50 积分 · 总积分榜 · 第 2 名」 (N-e01).
  - 积分明细: 「还没有积分记录」 (N-i19).
- **Read-back:** `ums_member.point=50`. The only ledger row is `change_points=0, change_reason=注册赠送`.
- **Cause:** `chengyinhub-system/.../UmsMemberServiceImpl.java:284` sets the balance to a fixed 50, but the ledger row (`:303-310`) takes the amount from `cms_points_setting` id 1, which is empty in the rig.
  - On a database where that setting isn't 50, the balance and its history disagree.
  - Points pay money at checkout, so this is a money-adjacent mismatch.
- **Owner check:** read production's `cms_points_setting` id 1 before launch.

### N-3 HIGH: 官方活动 detail shows a permanent 「集合信息加载失败 · 重试」
- **Repro:** open any official event published through `/api/official/publish`, e.g. event 2, as a signed-up player, a new player or a guest.
- **Seen:** under 集合与联系 a blue 「集合信息加载失败 · 重试」 that never clears; tapping 重试 changes nothing (A-c04, N-c04, GS-c04, S-c04-offline).
- **Cause:**
  - `GET /api/official/events/2/attendance` answers `{"code":500,"msg":"仅支持官方V2赛事"}`, because events from the light publish endpoint are contract version 1.
  - `pages/activity/components/event-attendance/index.js` treats that as a load failure.
- **Also on this page (LOW):**
  - The stats row runs off the right edge (「开放时」 is cut off).
  - The 集合与联系 heading and box have no side margin.
  - 「未配置奖励」 is a placeholder label.
  - 「我的进度 0 已完成」 reads as a contradiction.
- **Layout fix:** don't render the attendance block for v1 events; drop rewards when there are none; keep the stats row inside the gutter.

### N-4 HIGH: blank pages still left (F-6 remainder)
- `pages/square/detail/index?id=999`, a post that doesn't exist or was deleted: only a 「返回上一页」 bar (S-g02-square-detail-missing). A deleted post is a normal state for a shared link.
- `pages/play/merchant/index?nodeId=…` opened by any link: nothing at all (A-b02, S-b02, N-b02).
  - The page only reads its stop from the previous page (`pages/play/merchant/index.js:79-83`).
  - Without that it sets `notFound` and shows a `cy-error` with no title.
- `subpackageMember/mycanyuinfo/mycanyuinfo?id=75` opened by a player who doesn't own it: title 「参与详情」 and nothing else (N-i15).
- **Layout fix:** the 「链接已失效 · 返回」 state the neighbouring pages use.

### N-5 HIGH: guest dead end on the 漫游 tab: 我的队伍
- **Repro:** as a guest, 漫游 tab → swipe to NO.03 我的队伍.
- **Seen:**
  - Counters 「— 已通过 — 审核中 — 带队 — 未通过」 and a red 「队伍获取失败 · 点按重试」 (W70).
  - Retrying calls `/api/team/my` again, gets 401 again and stays (W71).
  - There is no login button, and the same tab's 附近活动 block does offer 「去登录」.
- **Other guest pages that show an error or developer wording instead of 「登录后查看 · 登录」** (deep link only, LOW):
  - 积分明细: 「积分明细加载失败 · 重试」 (G-i19).
  - 时光胶囊 / 月度回顾: 「请先登录后使用 · 重试」 (G-k01, G-k02).
  - 探索目标 / 那年今日 / 导出: 「请通过现有登录流程登录后再使用 · 重试」 (G-k03–k05).
  - 自由探索权益: 「请登录后重新进入本人订单」 (G-i01).
  - 排行榜 says it twice: 「登录后查看排行榜 / 登录后可查看完整排行榜」 (G-e02).
  - 队伍详情 tells a guest 「请重新登录后再打开这支队伍」 (GS-l04).

### N-6 LOW: 「请返回重新进入」 still under error titles on many p1 pages (owner's rule)
- **Seen on:**
  - 活动详情 「活动链接无效」 (G-c01).
  - 结算 「无法打开报名页」 (G-c02).
  - 主题 「无法打开主题」 (G-g01).
  - 帖文 「没有找到这条帖文」 (G-g02).
  - 个人主页 「这个主页链接不完整」 (G-h07).
  - 对话 「这个会话无法打开」, plus a footer that says it a third time (G-f02).
  - 参与详情 「缺少参与记录」 (G-i15).
  - 队伍详情 「队伍链接不完整」 (G-l04).
  - 游玩 「链接已失效，请返回重新进入」 (G-b01).
  - 加入队伍 「请从队长分享的邀请重新进入」 (S-l05).
- It's in 27 places in product code (`grep -rn 请返回重新进入 chengyinhub-xcx`).
- **Layout fix:** title + the 返回 button, as already done on 主办控制台 / 核销码 / 本次漫游.

### N-7 LOW: rows of zeros for a new player (F-5 remainder)
- 成长中心 「我的足迹」: 0 EXP, 0 枚, 0 个, 0 km, right under 「Lv.1」 (N-e01).
- 月度回顾 says 「这一类本月没有记录」 under 路线 for 9009, who finished theme 990014 this month (A-k02). Either the counter misses themes, or the tab should be named for what it counts.
- **Layout fix:** hide the 足迹 rows while they're all 0, since the empty 徽章 card already says it.

### N-8 LOW: 我的角色卡 by link still stacks four explanation lines
- 「角色模板 · 尚未建卡 · 以下为角色模板的初始属性，尚未创建角色 · 模板暂无可展示属性 · 该主题尚未配置可见属性 · 刷新角色卡」 (A-i02, N-i02).
- The entry from 参与详情 is gone (F-7), so only a link reaches it.
- **Layout fix:** 「链接已失效 · 返回」 when the theme has no character attributes.

### N-9 LOW: small secondary labels and wraps
- Home greeting has a role chip 「✓ 玩家 · 上海市」 under 「Hi, 玩家二号!」 (A-a01). The role label isn't needed on the player home.
- 票夹: the 「我的队伍」 pill wraps as 「我的队 / 伍」 and the team name is cut to 「周六夜行…」 (A-i23).
- 个人资料: 「介绍一下你自己」 sits as a second line under 个人介绍 (A-h06). It belongs inside the input as a placeholder, not on the row.

### N-10 LOW: chat and badge headers
- The full chat's header says 「对话」, not the other person's name 「玩家小城」 (A-f02).
- 3D badge: the nav title 「徽章」 and the label under the coin 「徽章」 are the same word; the badge's own name 「E2E 探店日一期 · 通关」 isn't shown (A-e06).

### N-11 LOW: 结算 shows ¥69 three times
- 「标准场次票 ¥69」, 「订单金额 ¥69」, 「总计 ¥69」 (W01). With one ticket and no discount, 订单金额 repeats the line above it.
- **Layout fix:** show 订单金额 only when there are several lines or discounts.

### N-12 LOW: 申请开票 gate puts its title in the nav bar; its four siblings use the large title (A-i06 vs A-i05, A-i07, A-i08)

### N-13 LOW: another player's coupon QR says 「二维码暂时没能生成，请稍后重试 · 重试」
- New player opening `coupon-qr?id=7` (9009's coupon) sees this (N-i11). It isn't a temporary failure, so retry can't help. It should say 「这张券不在你的券包里 · 返回」.

### N-14 LOW: invite refused for lack of a ticket gives no way to the ticket
- 「持有这一场的票才能加入」 with a greyed 接受邀请并加入 (W61).
- **Layout fix:** the button becomes 「去买这一场的票」 → 结算 for that session.

### N-15 LOW: play page before start: the 「活动未开始」 banner covers the theme title (W35)
- Also, the stop sheet repeats the chapter: eyebrow 「本章 · 第 1 章」 above the title 「第1章 咖啡」. The store row is cut at the bottom of the sheet (W43). Hand to j3.

### N-16 LOW: 「开始玩」 on 参与详情, 「开始游戏」 on 订单详情 and 票夹, for the same action (A-i15, W08, A-i23)

### HARNESS (rig, not product)
- **H-1** The rig loses taps on the theme CTA, play-page stop cards and the stop sheet's store row (setData re-renders the node before the click lands). Handlers were called directly.
- **H-2** The rig's `navigateBack` rejects even with a `fail` callback. This causes the page errors on privacy, crop, deregister and official-mine 返回.
- **H-3** Cover images, banner GIFs and theme covers are not in the fresh DB, so those areas are empty.
- **H-4** The QR codes (票夹 「生成中…」, 关于 「玩家个人码待生成」) need WeChat's code service.
- **H-5** The camera pages (集邮相机, 城市签) use a stand-in.
- **H-6** WeChat's `<icon>` renders 0×0 in the rig, so the 结算 consent tick is invisible. Tapping the line works.
- **H-7** Headless Chrome runs in UTC. Times the app formats from timestamps show 8 hours early: 队伍详情 「11月15日 02:00」 for a 10:00 session, message times 02:13, report 02:19. Times passed as Shanghai strings are right.
- **H-8** `wx.getRandomValues` is missing (j4 found the same). Every team command hangs at 「加入中…」 until it's stubbed.
- **H-9** 关于 shows 「版本 开发版本」 because the rig's `getAccountInfoSync` has no version.

## 5. What I did not cover

- A phone: subpackage download and `componentPlaceholder` (L-2), native map, camera, QR codes, share cards, fonts, keyboard, safe areas, real tap timing (H-1).
- `play/merchant` full state: only reachable from the play page, and the rig loses that tap (W5).
- Full states with no data a player can create:
  - Official routes (`preset_route` empty; platform-made).
  - Roam sessions, stamps and object cards: no roam was walked. 开始漫游 is a write and belongs to j4.
  - Earnings and withdrawals.
  - Invites accepted.
  - Exploration goals: the create sheet rejects past start dates; not walked by hand.
  - Invoices (feature off).
- Write actions skipped on purpose: refund, deregister, logout, delete post/comment, report/block in chat, export, team quit/kick/disband, withdraw, profile 保存 (the sweep's unlabeled save-button hit ran once). Note: the sweep tapped ☆ on post 1, so 9009 also has post 1 bookmarked (seen in 我的收藏).
- Largest phone font (j4), every game type in `pages/play` (j3).

STATUS: PARTIAL - all 88 p1 pages opened as guest, new player and active player with fixture data, every control tapped as the active player; full states of play/merchant, official routes, roam sessions, stamps, object cards, earnings and invites not reached in the rig (§5), and phone-only behaviour not verified
