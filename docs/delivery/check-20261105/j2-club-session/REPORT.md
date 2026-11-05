# j2-club-session: second pass

- **Commit under test:** `573c463b3b457b77544e63be4271a07d5d565e09` (`integ/launch-test-20261103`).
- **Branch:** `claude/check-1105-j2-club-session`.
- **First pass compared:** `claude/check-1104-j2-club-session` (tested `8b78f5ca3`). Fix reports read at this commit: `integ/ROUND10-F2-RESULT.md` and `integ/ROUND11-H4-RESULT.md`.
- **Rig:** `claude/cloud-walk-20261101-rig` with the first pass's `tools/rig-changes.diff`, which includes the club lane's harness patches. `up.sh` is pinned to the commit above. Docker uses `mirror.gcr.io`, with `LANG=C.UTF-8`.
  - Real jar, real MySQL 8 and Redis 7.
  - The real mini-program bundle runs in headless Chrome at 390×844, 2x.
  - **This is not a phone.**
- **Date:** 2026-11-05, about 10:10–11:45 Shanghai time.
  - The cloud worker restarted at about 10:30. MySQL lives on tmpfs, so the database was lost.
  - I rebuilt the rig and replayed sections A–D from my own scripts. They are the same steps on the same kind of data.
  - Shots `38`–`56` partly come from before the restart.
- **Data:** all fictional.
  - 主理人: 林小舟 (990005).
  - Members: 林一帆 990006, 周小雨 990007, 陈小满 990008, 王一舟 990009.
  - Merchants: 9002 探店日样板咖啡, 9005.
  - Club: 990004 沪上慢走社.
- I found things; I did not fix anything. 按核销付费 cash terms on club sessions are a known open decision and are not re-reported here.

## Verdict in plain words

The club journey with a merchant station now works from publish to settlement **with no fixture past any gate**. Every one of the five F-01 dead ends from the first pass is gone. The price lock, sale review, session, payments, the merchant's scan, later stations, chapter release, the hold screen, the ending, settlement to the fen, a refund after settlement and the ownership transfer all work. The 4 × ¥39 split exactly: ¥151.32 to the 主理人 and ¥4.68 to the platform. After a ¥39 refund, the session row reads ¥117.00 / ¥3.51 / ¥113.49.

The organiser can still lose the way in these places:
- **The terms invitation can't be reopened.** It shows once, right after 确认. If the lead leaves that page, nothing in the app leads back to it, and the station shows 已中标 with no co-op order (N-01).
- **The merchant has no clear cue on the day.** The station waiting for acceptance sits as an unlabelled second card off-screen in the 项目 carousel (N-02).
- **The refund roster shows no names.** Every payer is 「城瘾用户」 (N-03).
- **The 开始活动 refusal is out of sight.** The reason is printed far below the button (N-04).

The 自由探索 sale walks in the UI through publish, the chapter applications, approvals, the merchants' supplies and 确认终价. After that it needs platform back-office steps, which the rig cannot run. At that point the owner is still told 「确认终价」 and players see 「定价中 · 未开售」 (N-05).

## 0. First-pass findings: are they really gone?

| First pass | Now | Evidence |
|---|---|---|
| F-01a: merchant can't apply (「报名时间信息不完整」) | **Gone.** Sign-up dates are filled at create (10:17:50 to 23:59:59) | DB `cms_topic` 990014. Shots `30`, `31` |
| F-01b: application 500 (date format) | **Gone.** Submitted through the real date and time pickers. `cms_registration_merchant` 14 | `34`–`37` |
| F-01c: pricing locks 招商 out | **Gone.** Order is 立项 → 招募 → deadline job → 定价, and the invitation is accepted before the lock. coop_order 1 | `56`, `60` |
| F-01d: start blocked with no action for the lead | **Gone.** 「还不能开始：人民广场音乐喷泉（待商家接受）」 with 联系商家. The merchant accepted and readied the station | `99`, `101`–`104` |
| F-01e: merchant scan refused, players stranded | **Gone.** 「已记录接待该团」, `coop_group_verify` 1. All four finished stations 1–3 | `121`, `124`, `134` |
| F-02: sale review refuses (recruitDeadline / effectiveSaleEndAt) | **Gone.** Real admin review passed with no fixture, lifecycle 3 | read-back |
| F-03: rig lacks `cms_setting` 1 and the H8 points migration | **Still true of the rig.** Same environment fixtures applied (`tools/fixtures_env.sh`). r1 must confirm production | — |
| F-04: 「暂不能在线收款」 on organiser screens | **Gone.** No such text on the club theme page, 开一场 or the director page | `64`, `65`, `87` |
| F-05: no club screen leads to pricing | **Gone for city orienteering.** For 自由探索, 确认终价 is offered but goes stale; see N-05 | `61`, `189` |
| F-06: member steered to self-play and can't find the session | **Gone.** The card opens `via=session`, and after paying the main button goes to 票夹. The order page has 开始游戏 | `70`, `76`, `78` |
| F-07: 开始活动 fails silently | **Partly.** The reason now shows, but only at the bottom of the page and without a time (N-04) | `105b`, `105c` |
| F-08: everyone is 「城瘾用户」 where the organiser chooses | **Gone in the places listed for F-08**: role picker, broadcast, leaderboard, transfer. **Still there in 报名名册**, where the organiser picks whom to refund (N-03) | `109`, `151`, `153`, `145b` |
| F-09: 招商 counts contradict the lists | **Gone for city orienteering:** 0/3·0·3 → 0/3·1·3 → 1/3·0·2. **For 自由探索, wrong again** (N-07) | `25`, `38`, `42`, `180` |
| F-10: next step lost after 确认 | **Gone the first time:** 确认 lands on the invitation. **But it can't be reopened** (N-01) | `40` |
| F-11: raw request id after joining a team | **Gone** | `86` |
| F-12: false conflict after saving the club profile | Not re-walked (cover set at create). f2 shows it fixed | — |
| F-13: definite refusal shown as 待确认 | **Gone.** Merchant sees 「未核销 · 这不是城瘾的核销码」 and 「重复核销 · 已记录过」. Player sees the reason in red | `115`, `118`, `120`, `122` |
| F-14: wrong next step right after publishing | **Gone.** 「主题审核中」; the project card says 「还没有场次」 | `23` |
| F-15: 放行 row under the footer | **Gone.** The row is above the leaderboard and clear of the footer | `129` |
| F-16: 俱乐部结算 doesn't say where money came from | **Gone.** 各场次结算 lists sales, 3% fee, net, and 已入你的账户 / 已入原主理人账户 | `143`, `158` |
| F-17: merchant station page raw 「NODE_71」 | **Gone** | `101`, `104` |
| F-18: x/5 with 4 payers; recap 到场 vs check-ins | **Play page and recap agree: /4.** 到场 still counts station progress (owner decision C-10 pending). The roster's 「还没有带参与日期的报名」 under four dated sign-ups remains | `112`, `139`, `108` |
| F-19: team join mode contradicts itself | **Still there.** Order says 「仅邀请可加入」; team and join pages say 公开招募 | `82`, `85` |
| F-20: pink error blocks | **确认终价: gone** (red text under the field). **交接原因: still a pink icon block** | `192`, `154` |
| F-21: club home polish | Partly. 发布主题 appears twice on an empty 管理 tab, not five times | `15` |
| F-22: 票夹 after the end | **Partly.** Now 「已通关 · 查看记录」, but it still says 「生成中… · 今天过期」 | `141` |
| F-23: old 主理人 gets 「欢迎加入」 after transfer | **Still there.** No confirmation of the transfer either; the page just lands on club home | `155`, `159` |
| F-24: captions | Many cut (成为主理人 intro, 建俱乐部). Many remain (section 2, N-14) | — |
| f2 L1–L7, h4 items | L2: the card exists but is hard to find (N-02). L3, L4, L5, L7: confirmed in the UI. L1: no 开放 confirm was walked | — |
| ROUND8 lead: theme-page taps don't reach handlers | **Explained: rig, not product.** `.topic` binds `touchstart/move/end`, and the rig's runtime calls `setPointerCapture` on it (`mpweb/src/runtime.js:581`), so the click lands on `.topic`. `elementFromPoint` hits `.dock-cta`; a wrapped `onHeroCta` was never called. A phone should still confirm with a tap | `tools/g0probe.mjs`, `g0probe2.mjs` |
| ROUND8 lead: `merchantinfo` blank as a merchant | **Explained: by design.** The first screen is the full-bleed cover with no text (comment `merchantinfo.wxml:136`). A plain cover looks blank. See N-10 | `29`, `29b` |
| ROUND8 lead: `club/edit` 9003 `?id=7001` | **Form no longer renders under the denial.** But 9003 *is* 7001's lead on club home (管理 tab shown), while `access/me` gives 403. The denial sheet has no back or close (N-12) | `163`, `200` |
| ROUND8 lead: 「¥ -2.00」 vs 「−¥」 | No negative row arose (引流 terms). The session row uses 「¥ 151.32」 (space, monospace) next to 「¥151.32」 (N-13) | `143` |

## 1. Steps

Legend:
- **UI** = real screens in the rig.
- **API** = the endpoint the app calls, with the reason given.
- **FIX** = a fixture write to the throwaway database, with the reason given.

Read-backs are in `tools/readbacks.txt` and in each script's output.

### A–B. 主理人, club, members

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| A1 | New player, 成为主理人, 4 steps | PASS | `01`–`06` | 「已成为主理人」 |
| A2 | 创建我的俱乐部 with cover upload (real `uploadOSS`, OSS stand-in) | PASS | `07`–`11` | club 990004, cover `media-asset:1` |
| A3 | Club header shows the default art instead of the uploaded cover | HARNESS | `14` | `app.getImgUrl` drops untrusted hosts; the stand-in host isn't trusted |
| B1 | Four players tap 加入 | PASS | `12`, `13` | `club/join` 200 ×4 |

### C. Theme

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| C1 | 管理 › 发布主题 › 城市定向; 3 stations picked; editor 主题详情 | PASS | `15`–`22` | — |
| C2 | Fill and publish | API, editor body (`tools/c_create.py`). Editor pop-ups are post-launch; native wheels | — | theme 990014, lifecycle 7, `recruit_deadline` today 23:59:59, `sale_end_at` 2027-01-04, sign-up dates set |
| C3 | Templates published, then approved by the platform (real admin endpoint) | API | — | 6005–6007 |
| C4 | 管理 right after publish | PASS | `23` | 「主题审核中」; card 「还没有场次」. Button labelled the same as the heading (N-15) |
| C5 | Project review (admin) | PASS | — | lifecycle 1 |

### D. 招商

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| D1 | 管理 › 去招商 › 招商 layer | PASS | `24`–`26` | 0/3 · 0 · 3 |
| D2 | Merchant sets the shop address | PASS | `27` | `decor/save` 200 |
| D3 | Merchant: 合作中心 › theme › 申请承接 › 报名 › form (location, note, photo, date range, time) › 提交报名 | PASS | `28`–`37` | `cms_registration_merchant` 14 |
| D4 | Lead: layer shows the application › 确认 › confirm › lands on the terms invitation | PASS | `38`–`40` | `candidates/confirm` 200 |
| D5 | Lead leaves the invitation, then looks for it again | **FAIL** (N-01) | `42`–`50` | Layer: 已中标, 1/3, no action. 我发出的邀约: 「还没有发出邀约」. 协作邀请 card: no button. Merchant profile › 发起合作 is the chapter channel |
| D6 | Invitation reopened by URL (the page 确认 opens), 据点 picked, 引流合作, 发送邀约 | PASS (URL) | `51`–`56` | `coop_invite` 990024, shareMode 0, station 14 |
| D7 | Merchant: 合作中心 bell › 收件箱 › 接受合作 › confirm | PASS | `57`–`60` | `coop_order` 1. Inbox says 「来自 沪上慢走社」 (L4 fixed) |

### E. Pricing and sale

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| E1 | Deadline job (`recruit_deadline` moved back: TIME SHIFT) | PASS | — | lifecycle 2, fee 0.0300. The job also submitted `TOPIC_SALE_REVIEW` 6 itself (the price came from the editor) |
| E2 | 管理 shows 「开售审核中」 | PASS | `61` | — |
| E3 | Real admin sale review | PASS, **no fixture** | — | lifecycle 3 |

### F. Session and payment

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| F1 | 管理 › 开一场: form shows ¥39 and the meeting point; time set to 12:00 through the page's handler (wheel: HARNESS) › 创建系列场次 | PASS | `64`–`68` | activity 9, lat/lng copied, ticket 76 ¥39 |
| F2 | Member: club 活动 tab › session card › theme page (`via=session`) | PASS | `69`, `70` | — |
| F3 | 立即报名 › 领队场 › 去结算 | PASS with HARNESS tap (see section 0) | `72`, `m2-*` | `registration/quote` 200 |
| F4 | Participant name and phone, consent, 付款, 「取消只退 50%」, 继续下单 (rig pay seam, real callback) | PASS | `73`–`77` | — |
| F5 | Four members pay | PASS | — | regs 74–77, ¥39, `payment_status` 2 |
| F6 | Theme page after paying: main button opens 票夹 | PASS | `78` | — |

### G. Teams and the day

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| G1 | Order › 叫上队友 › 2 人队伍; second member joins by link | PASS | `82`–`86` | team 1: 990006, 990007. 陈小满 and 王一舟 stay unteamed |
| G2 | Director page before the session | PASS | `87`–`89` | — |
| G3 | Check-in more than 60 minutes early | PASS (refusal toast) | `early-94a` | 「集合前60分钟才可签到」 |
| G4 | Session moved to now+20 minutes (TIME SHIFT); three members check in, 王一舟 does not | PASS | `90`–`94a` | `lead/arrive` 「已到集合点」 ×3. Gather shows /4 |
| G5 | 开始准备 › confirm | PASS | `95`–`99` | 2/3 ready; 「还不能开始：人民广场音乐喷泉（待商家接受）」 + 联系商家. Unteamed 陈小满 and 王一舟 listed (L3 fixed) |
| G6 | Merchant: home › session station card (second in carousel) › 接受邀请 › capacity, date and time (date/time wheels: page handlers, HARNESS) › 确认准备完成 | PASS; **hard to find** (N-02) | `100`–`104` | `STATION_ACCEPT`, `STATION_READY` 200 |
| G7 | 开始活动 before the window | PASS server side; reason out of view (N-04) | `105`–`105c` | 「没能开始：当前不在活动服务时段」 |
| G8 | Session moved to now−1 minute, plus station windows frozen at PREPARE moved back 20 minutes (both TIME SHIFT; caused by my own earlier shift) › 开始活动 | PASS | `106*`, `107*` | `game_session` RUNNING |
| G9 | 报名参与者 › 定向广播 › untick 林一帆 and 王一舟 › write › 发送给 2 人 | PASS | `108`–`111` | Deliveries 990007 and 990008, REACHED. These are exactly the two still ticked. The sheet opens with everyone ticked |

### H. Play

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| H1 | Player at station 1: 同场路线 sheet, then 开始任务; wrong answer, then right | PASS | `112`–`116` | `play/answer` 「答案不正确」, then 「答对了！」. 到场 3/4 |
| H2 | Station 2 before the merchant's scan | PASS (refused, reason in red) | `117`, `118` | 「请先由商家扫描带队主理人的团码完成上一站接待」 |
| H3 | Lead's 团码; merchant 扫码: wrong code, real 团码, same again | PASS | `119`–`122` | 未核销 / 核销成功 「已记录接待该团」 / 重复核销. `coop_group_verify` 1 → coop_order 1 |
| H4 | Station 2 after the scan, all four | PASS | `123`, `124` | 「答对了！」 ×4 |
| H5 | Chapter 2 not yet released: 「等待放行」 › 返回地图 › 回到故事 | PASS | `125`–`128` | The map invites 「导航过去」 to the held station (N-11) |
| H6 | Lead: 放行「第二章 大世界的灯」 · 1 队在等 › reason › 放行 | PASS | `129`–`131` | `UNLOCK_CHAPTER` chapter 43 |
| H7 | Station 3 and the ending, all four | PASS | `132`–`136` | 「已通关」, 3 坐标, 300 解谜分. `/api/play/journey/ending` 500 「本主题未配置剧情」 does nothing visible (j3 area) |

### I–K. End, money, refund, transfer

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| I1 | 结束活动 › confirm; recap | PASS | `137`–`140` | FINISHED. 持票 4 / 到场 4 / 有完成进度 4; each station 到达 4 · 完成 4 |
| I2 | Player 票夹 afterwards | PASS with LOW | `141` | 「已通关 · 查看记录」, still 「生成中… · 今天过期」 |
| J1 | Real settlement jobs 145 and 146 (activity moved 9 days back: TIME SHIFT) | **PASS, to the fen** | — | 990005 +151.32 (event 3), 9001 +4.68 (event 7). `settlement_detail` 4 × (37.83 + 1.17) = 156.00. `coop_settlement` empty (引流) |
| J2 | 管理 › 结算; director 查看结算报告 | PASS | `143`–`143c` | 可提现 ¥151.32; row ¥156.00 · 3% ¥4.68 · ¥151.32 · 已入你的账户 |
| J3 | Refund after settlement: 设置 › 报名名册 › group › 待核销 chip of row 4 › 清退并退款 › 退款并清退 | PASS money; **can't tell rows apart** (N-03) | `144`–`148` | `cancel-by-owner` 200; reg 77 (王一舟) `payment_status` 4. `refund_application` ¥39. Clawbacks −37.83 (lead, event 6) and −1.17 (platform line). Lead balance 113.49 |
| J4 | 结算 after the refund | **PASS (L7 fixed)** | `149` | Row ¥117.00 · 3% ¥3.51 · ¥113.49; 可提现 ¥113.49 |
| J5 | Platform balance after the refund | Known open item (h4 "needs owner" 4) | — | 9001 still 4.68; only a −1.17 line. Not re-reported |
| K1 | 设置 › 成员治理 › 选择成员 (real names) › 林一帆 › 转让给所选成员 › 「转让主理人给 林一帆」 › empty reason › reason › 确认转让 | PASS | `150`–`155` | `club.member_id` 990005 → 990006 |
| K2 | New owner: 管理, 结算 | PASS | `156`–`158` | 可提现 ¥0.00; row 「已入原主理人账户」 |
| K3 | Former owner: club home; 我的 › 我的资产 | PASS money; LOW UI | `159`, `160` | Balance ¥113.49. 「欢迎加入」 sheet again (F-23) |

### M. 自由探索 sale, as far as the rig allows

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| M0 | Seeded 自由探索 990013 (club 7001) | Not usable: no dates, no chapters. 管理 shows 「暂时无法确认当前阶段 · 重试管理进度」 | `163`–`166` | Seed data, not walked further |
| M1 | New owner publishes a club 自由探索 theme | API, editor body (`tools/m_create.py`): 2 PERK chapters, `recruitDeadline` today | — | 990015, chapters 44 and 45 |
| M2 | Merchants 9002 and 9005: 合作中心 › theme › 申请承接 › chapter + template › 填报名 › 地图选点 (location explainer) › 提交申请与节点 | PASS | `167`–`172` | `merchant_chapter_application` 1, 2 |
| M3 | Owner: console › 商家 › 章节承接申请 › 确认 › 确认通过 | PASS server side; **list doesn't refresh**; counts wrong (N-07) | `173`–`180` | Both status 1. Second tap on an approved row: 「该申请已处理」 |
| M4 | Merchants: 承接进度 › 填写实际供给 › 常备权益 › 核销额度 › 确认实际供给 | PASS | `181`–`186` | `coop/offer/enroll` 「已入驻」 ×2 (PERK, perks 3001 and 3002) |
| M5 | Deadline job (TIME SHIFT) | PASS | — | lifecycle 2, fee 0.0300 |
| M6 | Owner: 管理 › 确认终价 › empty submit (red text under the field) › 60 / 4 › 重新计算地板 › price 29 (slider: page handler, HARNESS) › 确认终价并提交开售审核 | PASS | `189`–`196` | 「终价已确认，请等待开售审核」. Ticket 77 有人带 ¥29. **No `TOPIC_SALE_REVIEW` task.** By design for 自由探索 (`ApiPricingController.java:100`): ops save a canonical15 draft first |
| M7 | Owner 管理 and player theme page after pricing | **FAIL** (N-05) | `197`–`199` | Owner: 「现在要做 · 确认终价」 again. Player: 「定价中 · 未开售」 |
| M8 | Platform back office (club payee, work packages, compensation hours, cost, organizer actor, canonical15, sale submit), then the player buys | **NOT VERIFIED** | — | Admin web is not in the rig. The repo's own `chains.py` covers it over the API |

## 2. Findings

Severity: BLOCKER = money wrong, data leak, dead end, crash or explicit content. HIGH = user can't finish or is misled. LOW = polish.

### N-01. HIGH: the terms invitation can be sent only once, from the page 确认 opens

- **Repro:**
  1. Lead: 招商 layer › 确认 › confirm. This lands on `pages/coop/invite`.
  2. Go back without sending.
  3. Look for the invitation.
- **What happens:**
  - Layer: the shop is under 已确认 as 「已中标」, counted 1/3 已招站点, and tapping the row does nothing.
  - 管理 › 我发出的邀约: 「还没有发出邀约」.
  - 可对接商家 › 我的合作 › 收到的: card 「已中标」 with no button.
  - The merchant's profile › 发起合作 opens the chapter-level invite, not this one.
  - The 「去发邀约」 modal in `pages/coop/list/index.js:836` appears only right after 确认 on that page.
- **Effect:** there is no co-op order. At the deadline the theme is held back (「…的商家还没有生效的合作单，不能开售」), and the lead can't see why or fix it.
- **Shots:** `42`–`50`.
- **Fix direction:** the 已中标 row (and the 协作邀请 card) carries 「发邀约」 until an invitation exists.

### N-02. HIGH: on the day, the merchant can't tell they must accept the station

- **What happens:**
  - The station waiting for acceptance is a second card in the horizontal 项目 carousel, mostly off-screen.
  - The card is labelled 「活动」 with a grey placeholder, the name and a raw time 「2026-11-05 11:14:37」 (seconds included). No 「待接受」.
  - 「全部 1」 counts one project while there are two cards.
  - 我的消息 says nothing about it.
  - Meanwhile the lead's start is blocked by 「待商家接受」.
- **Shots:** `100b`, `100c`, `101`.
- **Fix direction:** put a state line (「待接受邀请」) on the card, put the waiting card first, and send a message to 我的消息.

### N-03. HIGH: the refund roster shows every payer as 「城瘾用户」

- **Where:** 设置 › 报名名册 › group.
- **What happens:**
  - All four rows read 「城瘾用户 · 待核销」. The organiser picks whom to 清退并退款 (a money action) blind.
  - The detail page then shows 「王一舟」, so the name exists.
  - The name opens the profile; only the small 「待核销」 chip opens the refund page.
- **Also wrong on the same page:**
  - 「募集中」 and 「可退款 4 人」 for a finished, settled session.
  - Everyone is 「待核销」 after finishing all stations.
  - A template-ticket block 「领队场 0 / 20 · 还没有人报名」 sits above the real session block.
- **Two lists, two behaviours:** the director's 报名参与者 list has the names, but its rows open nothing.
- **Shots:** `145b`, `146`, `144-roster-after-settlement`.

### N-04. HIGH: 开始活动 refusal is printed far below the button and has no time

- **What happens:**
  - After 开始活动 › confirm, the visible screen doesn't change.
  - The reason 「没能开始：当前不在活动服务时段」 sits under 队伍进度, near the footer.
  - It doesn't say when the session can start (it starts at 11:14).
  - The second refusal 「开局条件未满足：上海博物馆（站点服务时段：2026-11-05 11:14:37 至 …）」 shows raw timestamps with seconds.
- **Shots:** `105b`, `105c`, `106c`.

### N-05. HIGH: 自由探索 after 确认终价: the owner is told to price again; players see 「定价中」

- **Repro:** theme 990015, 确认终价 at ¥29 succeeds with 「终价已确认，请等待开售审核」.
- **What happens:**
  - Owner's 管理 still shows 「现在要做 · 确认终价」 with the same button. f2's `d4faffd4a` fixed this for city orienteering only.
  - The player's theme page bar reads 「定价中 · 未开售」 with a ¥29 ticket listed above it.
- **Ticket block contradicts itself:**
  - Named 「有人带」 but described 「场次内自由顺序，不带队」.
  - 「本期可售 100 张」 sits above 「余席 —」.
  - 参与品牌 lists only the club, not the two shops that took the chapters.
- **Next step:** the platform's canonical15 draft and sale submit. Nothing on the owner's screens says that this is what is being waited for.
- **Shots:** `196`–`199`.

### N-06. HIGH: the theme-page tap problem is the rig, but a phone must confirm

- **Cause:** see section 0. The `.topic` wrapper binds touch events, and the rig's `setPointerCapture` steals the click. This is HARNESS, not a product bug.
- **What a phone must confirm:** with `touchstart/move/end` on the page wrapper, 立即报名 and 查看场次 still fire on WeChat.
- **Severity:** listed HIGH only until a phone confirms it.

### N-07. LOW: 自由探索 招商 layer

- **The list doesn't refresh after 确认通过.**
  - The approved application still shows 「待审核 · 确认」.
  - A second tap gets 「该申请已处理」.
- **Counts are wrong.**
  - After both chapters were approved and supplied, the layer reads 「0/2 已招站点 · 1 待处理申请 · 2 还没人接」.
  - The header meanwhile says 「自由探索 · 2 站」.
- **Shots:** `176`, `180`.

### N-08. LOW: 「城瘾用户」 and 「成员#990005」 elsewhere after the transfer

- **New owner's club 概览:**
  - The info card reads 「城瘾用户 · 主理人」 while the member list reads 「林一帆 · 主理人」.
  - The former owner appears as the raw 「成员#990005」.
- **Former owner's club home:** every member is 「城瘾用户」, including the 主理人. (Ordinary-member nickname rule; h4 item 3 is open for the owner.)
- **Shots:** `156`, `159`.

### N-09. LOW: after 确认终价 for city orienteering, 「现在要做」 says 「管理项目 · 商家已接受，进入项目主办视图」 on an on-sale theme

On an on-sale theme, the 开一场 next to it is the real next step. Shot `64`.

### N-10. LOW: merchant theme page

- The first screen is only the cover (by design). A plain cover reads as a blank page.
- The second screen says 「封面暂不可用」 when the author simply didn't add the optional 16:9 cover; nothing failed.
- In the 选择承接节点 sheet, the 「已选：…」 line is cut off.
- **Shots:** `29`, `29b`, `30`.

### N-11. LOW: hold screen map

- While chapter 2 is held, 返回地图 shows 「下一站 · 0m 大世界 · 导航过去」, with no sign that it is locked.
- The station-2 question is clipped under the sheet header.
- **Shots:** `127`, `124`.

### N-12. LOW: `pages/club/edit` denial is a dead end

The sheet 「暂时不能查看」 has no button, and the page has no back arrow. Separately, 9003 sees 管理 on 7001's home but `access/me` answers 403 for the same club; this may be seed data. Shots `163`, `200`.

### N-13. LOW: money formats on 俱乐部结算

The summary uses 「¥151.32」, while the session row uses 「¥ 151.32」 / 「¥ 156.00」 (space, monospace), built in the backend (`"¥ " + x`). The first block shows 「商家计酬 · 俱乐部直接付款 · ¥0.00 ×3 · 还没有计酬核销记录」 on an 引流 theme where nothing can be owed. 「联系平台客服提现」 sits next to the in-app 我的资产 balance. Shot `143`.

### N-14. LOW: captions still present (owner rule)

Each line can go if the layout carries the meaning.

- **开一场 form** (`65`, `66`):
  - 「仅可修改无报名的未来场次」
  - 「最多排未来 90 天（64 期）」
  - 「退款窗口按「集合时刻前 24 小时」计算。」
  - 「自定义容量只适用于…」
  - 「当前为 1 天。」
  - 「空位为候补首位保留…」
- **Coop invite** (`40`):
  - 「对方接受并成单后锁定」
  - 「已选定，发送前不可更换」
  - 「每条邀约明确选择一个据点；商家接受后冻结，订单间不能重复使用。」
  - The send sheet's 「协作邀请 / 整主题合作邀请 / 对方会在「收到的」中确认本次协作。」. 「整主题」 is also wrong for a station invite.
  - The merchant name is printed twice in 核销据点.
- **Merchant apply** (`31`–`36`):
  - 「用于地图与到场导航，拒绝不影响填写」
  - 「决定这一站能接多少团、什么时候接」
  - 「每日可接待时段 · 选填」
- **Merchant station page** (`104`): 「仅显示本站安全聚合，不公开玩家内容。」
- **Director** (`129`): 「玩家进度与发放状态保持不变」.
- **Order and team** (`82`, `84`): 「组队与否不影响活动举行，也不改变订单与退款规则。」, 「一起出发的玩家队伍」.
- **Theme page** (`80`): 「评论积分须符合参与及首评条件，以实际积分记录为准」.
- **Not counted:**
  - 「联系方式不对外展示」 and the 开启定位 / 集合签到定位 texts are privacy and permission disclosures.
  - The 50% refund dialog and the 清退 dialog lines are consequence warnings.

### N-15. LOW: smaller items

- **管理 right after publish:** a white primary-styled button labelled 「主题审核中」 under the heading 「主题审核中」 (`23`).
- **报名参与者 counts:** header 「4 待核销 · 0 我已核销」 sits next to roster 「待核销 0 · 已核销 0」 (`108`).
- **Release row:** 「1 队在等」 when four players (one team and two unteamed) had all finished chapter 1 (`129`).
- **Team name:** a team shows as just 「队伍」 (`97`).
- **Gather overlay:** the play page's 「继续探索」 button shows through behind 「等队长开始行程…」 (`94`).
- **Club pricing page theme:** the club owner's 确认终价 page is light, while every other club organiser screen is dark (`190`).
- **Merchant inbox line:** 「沪上慢走社 的主题发布者 → 商家邀请」 reads as internal roles (`57b`).
- **Team detail call:** `GET /api/orient/team/detail` answers 500 「该功能暂未开放」 on the team page. Nothing visible.

## 3. Rig changes and fixtures (none in product code)

- **Rig changes** (`tools/rig-changes.diff`):
  - The first pass's seams.
  - Chrome launched with `--no-proxy-server --ignore-certificate-errors` for the OSS stand-in.
  - `walk.mjs` `tap` accepts a negative `nth`.
- **Stand-ins** (`tools/rig-seams/`):
  - Local OSS on `oss-rig.local:9443`.
  - `api.weixin.qq.com` for `stable_token` and `img_sec_check`.
  - Both are trusted only by the rig's JVM and Chrome. The certificate and key are not committed.
- **Environment fixtures:** `tools/fixtures_env.sh`.
  - Production's pre-migration settlement rows, then the repo's 3% migration.
  - `cms_setting` 1.
  - The H8 points migration.
- **Time shifts, labelled in `tools/readbacks.txt`:**
  - Recruit deadlines moved back so the real job runs.
  - Session moved to now+20, then to now−1.
  - Station windows frozen at PREPARE moved back 20 minutes; needed only because of my own shift.
  - Activity moved 9 days back for the settlement job.
- **HARNESS handlers** (wheels and the price slider aren't drawn in the rig): the session time, the merchant's service date and time, the price slider, and 立即报名 / 去结算 on the theme page.

## 4. Not covered

- **The editor's full input path:** pop-ups are post-launch, and both themes were created with the editor's body.
- **The rest of the 自由探索 sale:** platform back office and a player purchase (M8).
- **按核销付费 terms:** a known open decision.
- **Self-play at 3%:** not in this pass's list; the first pass showed it settling at 3%.
- **The club profile edit save (F-12):** f2 shows it fixed.
- **Withdrawal itself:** a real WeChat refund outcome; `refund_application` payout is 「原路退款结果未知」 in the rig.
- **The members' inbox screens** for the broadcast.
- **The opt-in of 开放商家承接 confirm (L1).**
- **Phone-only behaviour:**
  - taps on the theme page (N-06);
  - native wheels and slider;
  - GPS;
  - camera scan;
  - payment sheet;
  - `getPhoneNumber`;
  - map tiles;
  - OSS image hosts.

STATUS: PARTIAL - the club session journey ran end to end with no fixture past any gate and settled to the fen, but the 自由探索 sale stops at the platform back-office steps (admin web is not in the rig), and theme-page taps and native pickers still need a phone.
