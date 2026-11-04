# j2-club-session: Round 8 pre-launch check (first pass)

- Commit under test: `8b78f5ca3c06f486631e827827e973cbb47e3094` (on `integ/launch-test-20261103`). Branch `claude/check-1104-j2-club-session`.
- Rig: `claude/cloud-walk-20261101-rig` with the club lane's `tools/harness-patches.diff`, `up.sh` pinned to the commit above, Docker via `mirror.gcr.io`, `LANG=C.UTF-8`. Real jar, real MySQL 8 and Redis, the real mini-program bundle in the browser runtime.
- **This is not a phone.** Shots are 390x844 at 2x from the rig browser. Map, real WeChat responses, payment sheet, fonts and haptics are not what a phone shows.
- Date of walk: 2026-11-04, about 12:20–14:05 Shanghai time.
- All names, phones and ID numbers are fictional: 主理人 「林小舟」; members 林一帆, 周小雨, 陈小满, 王一舟.

## Verdict in plain words

A club 主理人 **cannot run a paid city-orienteering session that includes a merchant station** at this commit. The journey breaks in four places, and the breaks add up:

1. A merchant can't apply for a station: the app shows 「报名时间信息不完整」, and nothing sets the dates.
2. When the dates are forced, the application fails with 「系统繁忙」 (a date-format bug).
3. The lead is led to price the theme before 招商 is finished. After pricing, the co-op invitation is refused for good.
4. On the day: the session can't start until the merchant accepts. After it starts, players are stuck after station 1, because the merchant's scan is refused: 「没有生效的合作单」.

Everything around that works much better than in the 11-01 walk:
- Becoming 主理人, creating the club, joining and roles all work.
- Payment works on a club-owned theme.
- Self check-in, the director page, chapter release, targeted broadcast to chosen members and ending the session all work.
- Settlement pays the right person at 3%, to the fen, before and after an ownership transfer.

Two things across the whole walk mislead the organiser:
- Every member is 「城瘾用户」. That includes the confirm dialog that hands the club to someone.
- Organiser screens say 「这个主题暂不能在线收款」, while four players had already paid.

## 1. Steps

Evidence:
- **UI** is a real screen in the rig. All shots are under `shots/`.
- **API** means the step was done through the same endpoint the app calls, because the rig could not drive that screen. The reason is given each time, and scripts are under `tools/`.
- **FIX** is a fixture write to the throwaway database, with its reason. Reads use `tools/db.py`.

### A. 主理人 and club

| # | Step | Result | Evidence | Read-back |
|---|---|---|---|---|
| A1 | New player opens 成为主理人, goes through 4 steps | PASS | UI `01`–`07` | `POST /api/club/become-leader` 200 「已成为主理人」 |
| A2 | 创建我的俱乐部, 4 steps, name 「沪上慢走社」, 上海 | PASS | UI `08`–`15` | `club` 990004, `member_id` 990005 |
| A3 | Cover and logo on the create form | HARNESS (first attempt) | UI `12b` | 503 「安全检查服务异常」: the WeChat image check can't be reached from the rig |
| A4 | Cover and logo via 编辑资料, after the rig seams were added (see section 4) | PASS upload. **FAIL after save** | UI `31`–`33` | `uploadOSS` 200 ×2. `club/update-mine` 200 「已保存」. Then the page shows a false conflict (F-12) |

### B. Members and roles

| # | Step | Result | Evidence | Read-back |
|---|---|---|---|---|
| B1 | Lead looks at the club home and the 管理 tab | PASS, with polish findings | UI `16`–`19b` | — |
| B2 | Four players tap 加入. The welcome sheet appears | PASS | UI `20`, `21` | `club/join` 200 ×4 (990006–990009) |
| B3 | 设置 › 角色与权限: assign 俱乐部运营 | PASS. **Can't tell members apart** (F-08) | UI `22`–`27` | `club_role_assignment` id 1: 990006 CLUB_OPERATOR ACTIVE |

### C. Theme from templates, pricing, sale

| # | Step | Result | Evidence | Read-back |
|---|---|---|---|---|
| C1 | 管理 › 发布主题 › 城市定向 opens the editor; 3 stations picked in the continuous picker, then cancel | PASS | UI `28`–`31` | — |
| C2 | Fill the editor | NOT VERIFIED in the UI (see below) | API `tools/c_author.py`, `c_create.py` | — |
| C3 | Club 管理 right after publish | FAIL (F-14) | UI `32`, `33` | — |
| C4 | Lead looks for how to sell | FAIL (F-05) | UI `35`, `38`–`41` | — |
| C5 | Pricing page (确认终价) | PASS with findings | UI `42`–`44` | `fee_platform_rate` frozen 0.0300 |
| C6 | Platform sale review, real admin endpoint | **FAIL** (F-02) | `tools/approve.py` | Refused twice: 「开放承接时 recruitDeadline 必填」, then 「effectiveSaleEndAt 必须早于或等于 ticket.startTime」 |
| C7 | Put the theme on sale to continue | FIX | `tools/e_sale_fixture.py` | `lifecycle` 3, same as `integ/proof/selfplay_quote.py` did |

Notes on C2:
- The remaining editor input pop-ups come after launch (owner decision), and covers can't be uploaded through the rig's create path.
- The lead published three gameplay templates through `/api/template/publish` (6005 text, 6006 choice, 6007 text), and the platform approved them.
- The theme was then created through `/api/topic/v2/create` with those templates on its stations: theme 990014, 2 chapters, 3 stations, ¥39 led ticket, self-play ¥19.9, 商家承接 on.
- The cover is an APPROVED media asset fixture, as the candidate's own `chain_platform.seed_approved_cover` does.
- **Theme-template copy (「用这个模板」) was not walked:** a fresh database has no theme template.

Notes on C3, C4 and C5:
- C3: 「现在要做」 says 开一场 with 2 identical 开一场 buttons while the theme is in review and has no price.
- C4: No club screen leads to pricing. The header says 「未开售 · 待开售审核」, while 更多 says 「定价中」. The only route is the public theme page's grey 「定价中 · 未开售」 bar (`lockedTip`). That bar did not fire in the rig, so the same handler (`onHeroCta`) was called.
- C5: A pink block with an icon, 「请填写大于 0 的成团人数」, shows on first open before any input (ROUND8 lead confirmed). The success message wrongly says players can't pay (F-04).

### D. 招商: merchant takes a station

| # | Step | Result | Evidence | Read-back |
|---|---|---|---|---|
| D1 | 商家 opens the 招商 layer (dark) | PASS. **Counts are wrong** (F-09) | UI `45`, `46`, `59`, `61` | — |
| D2 | 招商 on a station shows 「附近暂无商家」 | FAIL (see below) | UI `47`, `48` | `/api/merchant/nearby` reads merchant leads, not shops. `invitable` needs a chapter category that no screen sets for city orienteering |
| D3 | Merchant 9002 sets the shop location in 门店信息 | PASS | UI `49`, `50` | `mms_merchant` 8001 lat/lng saved |
| D4 | Lead opens 开放商家承接申请 (招商 page › 更多) | PASS | UI `48`, `49` | `cms_topic_chapter` 42 `recruit_enabled` 1 |
| D5 | Merchant: 合作中心 › theme › 申请承接 | **BLOCKER** (F-01a) | UI `50`, `51`, `53a` | Toast 「报名时间信息不完整」. Admin `PUT /business/topic` refuses: 「非官方主题仅允许调整漫游导流开关」 |
| D6 | Force the merchant sign-up window to continue | FIX | `tools/d_window_fixture.py` | — |
| D7 | Merchant fills location, note, photo, dates and time, then 提交报名 | **BLOCKER** (F-01b) | UI `53`–`57` | 500 「系统繁忙，请稍后重试」, `HttpMessageNotReadableException` on `startDate` |
| D8 | Replay the same body with ISO dates | API | `tools/d_merchant_replay.py` | `cms_registration_merchant` 14 |
| D9 | Lead taps 确认 in the 招商 layer | PASS server side. **No next step shown** (F-10) | UI `59`–`61` | `/api/coop/candidates/confirm` 200 「请向该商家发出带条款的合作邀约」. The message is dropped |
| D10 | Lead sends the terms invitation (`coop/invite`, the page the 合作 list's 「去发邀约」 opens) | **BLOCKER** (F-01c) | UI `62`–`65` | 「主题已锁价,不能再发起或接受合作邀请」 |

### E–F. Session, sign-up, pay, teams

| # | Step | Result | Evidence | Read-back |
|---|---|---|---|---|
| E1 | 开一场: the form shows ¥39 and the meeting point (C-17 fixed). Single session today 14:30 | PASS. Time wheel driven through its handler (HARNESS) | UI `67`–`70` | `cms_activity` 9, lat/lng 31.2304/121.4737 copied (C-03 fixed) |
| F1 | Member: club 活动 tab shows 「周三 11月4日, 14:30 · 未开始」 | PASS | UI `71` | — |
| F2 | Card leads to the theme page | **FAIL** (F-06) | UI `72`, `73-*` | Main button is 「¥19.9 随时开玩」 (self-play), not the session |
| F3 | 查看场次 › 领队场 ¥39 › 下一步 · 去结算 | PASS, handler invoked (see below) | UI `74`, `75` | `/api/registration/quote` 200 |
| F4 | Participant form, consent, the 「取消只退 50%」 warning, 付款 (rig payment seam, real callback) | PASS | UI `77`, `78`, `79a`, `79` | `cms_registration` 74: ¥39, `payment_status` 2 |
| F5 | Three more members pay through the same screens | PASS | `tools/f4.mjs` | regs 75, 76, 77, all `payment_status` 2 |
| F6 | Order › 叫上队友 › 2 人队伍 | PASS | UI `80`–`82` | `play_team` 1 |
| F7 | Second member opens the invite link and taps 接受邀请并加入 | PASS after a rig fix. **Raw request id on screen** (F-11) | UI `83`, `84` | `play_team_member` (1, 990008) |
| F8 | Second team (林一帆 + 王一舟) | PASS | `tools/f8.mjs`, `f7.mjs` | team 2: 990006, 990009 |

Notes on F3: taps on `pages/topic/index` (查看详情与报名, the dock button, 查看场次, 去结算) did not reach their handlers in the rig. The bound handlers (`bmClick`, `onHeroCta`, `onBuyGo`) were called directly. The cause, rig or product, is not established; see section 3.

### G–H. Day of the session

| # | Step | Result | Evidence | Read-back |
|---|---|---|---|---|
| G1 | Lead opens 活动导演台 | PASS, with findings | UI `85`–`87` | — |
| G2 | Member finds the way into the session | PASS via 票夹 only (F-06) | UI `88`–`91` | The order page and the theme page don't lead in |
| G3 | 开始游戏 › 开启定位 › 我到了 › 定位并签到 inside 500 m | PASS (C-03 fixed) | UI `92`–`94` | `/api/club/lead/arrive` 200 「已到集合点」 ×3 |
| G4 | Check-in from 1.4 km away | PASS server side; message not proven on screen | UI `95` | 500 「请到集合点500米内签到」 |
| G5 | 开始准备 › confirm | PASS | UI `96`–`99` | `game_session` 1 READY. 「2/3 站就绪」, fountain 「已邀请」 |
| G6 | 开始活动 | **BLOCKER** (F-01d) | text log, `g6.mjs` | 「还不能开始：人民广场音乐喷泉（待商家接受）」. The reason is shown (C-09 fixed). The lead has no action on that station (UI `105`) |
| G7 | Merchant accepts and readies the station on `merchant/game-node` | PASS | UI `108`–`111` | `STATION_ACCEPT`, `STATION_READY` 200 |
| G8 | 开始活动 again | **FAIL silently** (F-07) | UI `102`, `tools/probe.mjs` | Command returns 200 with `outcome: FAILED` 「当前不在活动服务时段」, then `GAME_STATION_NOT_READY`. The page stays 准备中 with no message |
| G9 | Shift the session and station windows, then start | FIX + PASS | `seed-walk.py shift-session`, `tools/g_shift.py` | `game_session` RUNNING |
| G10 | 报名参与者 › 定向广播 › pick 2 of 4 › send | PASS. **Can't tell recipients apart** (F-08) | UI `114`–`116` | `game_broadcast` MEMBERS. Deliveries: 990006 and 990009 only, REACHED |
| H1 | Player at station 1: wrong answer, then right answer | PASS | UI `117`–`123` | `/api/play/answer`: 「答案不正确」, then 「答对了！」 |
| H2 | Lead's 团码 › merchant 扫码 at the counter | **BLOCKER** (F-01e) | UI `124`–`126` | `/api/verify/groupcode/redeem` 500 「您在本主题没有生效的合作单,无法核销」. The merchant sees 「核销结果待确认…请勿重复核销」 (F-13) |
| H3 | Player at station 2 | **BLOCKER** | UI `129`–`131` | 「请先由商家扫描带队主理人的团码完成上一站接待；提交结果待确认…暂请勿重复提交」, and the button is 「结果待确认」 |
| H4 | Lead taps 放行「第二章 大世界的灯」 | FAIL first tap (F-15), then PASS | UI `127-*`, `128` | `UNLOCK_CHAPTER` 200 (C-04 fixed) |
| H5 | Station 3 after release | BLOCKED | `tools/h_answer.py` | Same 团码 message |
| H6 | 结束活动 › confirm | PASS | UI `132`–`135` | `game_session` FINISHED. The recap tiles have numbers (C-11 fixed) |
| H7 | Player after the end | PASS, with findings | UI `136`, `137` | 「本场已结束 · 你完成了 0/3 站」. 票夹 still shows 「开始游戏 · 生成中… · 今天过期」 |

Notes on H2: the 团码 screen reads 「活动核验团码 · 本团」.

### I–K. Money, transfer, self-play

| # | Step | Result | Evidence | Read-back |
|---|---|---|---|---|
| I1 | Activity settlement (real `settlementTask`, session moved 8 days back) | PASS | `tools/i_settle.py` | Activity 9: sales ¥156.00. 主理人 990005 +¥151.32 (event 3); platform 9001 +¥4.68 (event 7), exactly 3% |
| I2 | Lead's 管理 › 结算 | Partly FAIL (F-16) | UI `138` | 「可提现 ¥151.32」 matches. 「还没有平台分润记录」, and no line for the session |
| J1 | 设置 › 成员治理 › 选择成员 › 转让给所选成员 | PASS. **Can't tell the target apart** (F-08) | UI `140`–`146` | Dialog 「转让主理人给 城瘾用户」. The 交接原因 error is a pink icon block. `owner/transfer` 200. `club.member_id` 990005 → 990006 |
| J2 | Old and new 主理人 open the club | PASS, with findings | UI `147`, `149`, `150` | Old: no 管理 tab, and the 「欢迎加入」 sheet shows. New: 管理 and 结算, 可提现 ¥0.00 |
| K1 | Member buys the ¥19.9 self-play pass | API (see below) | UI `151`, `152`; `tools/k_selfplay.py` | reg 78, ¥19.90, `payment_status` 2 |
| K2 | Theme settlement (real jobs 147 + 148, theme end moved 8 days back) | PASS | `tools/k_settle.py` | ¥19.90: PLATFORM ¥0.60 (rate 0.0300), INITIATOR_MARGIN ¥19.30 to the **current** 主理人 990006, paid out (balance event 5) |
| K3 | New 主理人's 结算 page | PASS amount | UI `154` | 可提现 ¥19.30. Still 「还没有平台分润记录」 |

Notes on K1:
- The sheet and checkout render.
- 「补全姓名和手机号」 opens the personal profile page, whose phone needs WeChat `getPhoneNumber`. That can't run in the rig.
- The quote said `refundableNow:false`, refund deadline 11-03: the pass is non-refundable from the start, because the theme window has already begun. This follows the owner-decided tier rule.

Notes on K2: 19.90 × 3% = 0.597 is rounded to 0.60 for the platform.

## 2. Findings

Severity: BLOCKER = money wrong, data leak, dead end, crash or explicit content. HIGH = user can't finish or is misled. LOW = polish.

### F-01. BLOCKER: a club theme with a merchant station can't be sold or run

The same journey dead-ends five times, at five gates. Each gate below is a dead end on its own.

- **a. Merchant can't apply.**
  - What happens: 合作中心 › theme › 申请承接 shows 「报名时间信息不完整」 (`pages/topic/merchantinfo/merchantinfo.js:1007`).
  - Cause: `merchant_sign_up_start_date/end_date` are set by no app code. The only writer in the app module clears them (`TopicContentCopyServiceImpl.java:227`). Admin `PUT /business/topic` refuses non-official themes: 「非官方主题仅允许调整漫游导流开关」.
  - Shot: `53a`.
- **b. The application always fails.**
  - What happens: 提交报名 gives 500 「系统繁忙，请稍后重试」.
  - Cause: `RegistrationMerchantDTO.startDate/endDate` are `Date` without `@JsonFormat`. The app sends `"2026-11-04 00:00:00"` (`pages/topic/merchantapply/index.js:646`). The jar has no global Jackson date format, so production fails too, unless an external config sets one. I could not see production config.
  - Repro: merchant 9002, theme 990014, station 71. Log: `HttpMessageNotReadableException … "2026-11-04 00:00:00"`.
  - Shot: `57`.
- **c. Pricing first locks 招商 out.**
  - What happens: the only path the UI offers the lead is price → 开售审核. Nothing says that pricing closes co-op invitations. After 中标 the invitation is refused: 「主题已锁价,不能再发起或接受合作邀请」 (`/api/coop/supply-stations`).
  - Shots: `44`, `64`.
- **d. On the day, start is blocked.**
  - What happens: 「还不能开始：人民广场音乐喷泉（待商家接受）」.
  - Cause: the merchant must accept on `pages/merchant/game-node`. The merchant's only entry to it is the theme's 承接 page, and the theme disappears from 合作中心 once the sign-up window closes.
  - The lead has no action for this.
- **e. After station 1 nobody can continue.**
  - What happens: the merchant's scan of the 团码 is refused: 「您在本主题没有生效的合作单,无法核销」. Players get 「请先由商家扫描带队主理人的团码完成上一站接待」 at every later station.
  - Four paid players are stranded after station 1.
  - Shots: `126`, `131`.

### F-02. BLOCKER: platform sale review can't pass for a club city-orienteering theme with 商家承接 on

- First refusal: 「开放承接时 recruitDeadline 必填」 (`TopicSaleTimeValidator.java:49`).
  - The editor sends `recruitDeadline: null` for city orienteering (`pages/publish/fabu/index.js:7639`).
  - Yet the editor offers 商家承接 for city orienteering (`fabu/index.js:6661`), and so does the club theme 更多 sheet.
- Second refusal: 「effectiveSaleEndAt 必须早于或等于 ticket.startTime」.
  - The ticket window in my create body began at publish time. The editor's own ticket times may differ: **this second refusal may come from my API body**. The first does not.
- The reviewer sees internal English field names in both.

### F-03. BLOCKER: the rig's fresh database could not settle anything until two schema gaps were filled

1. `cms_setting` row 1 (the platform account). Pay-gate used the same fixture.
2. `migration_20260815_tandianri_h8_publisher_points_funds.sql` was not applied: `cms_points_setting.rule_version` and the eventType 15 rule were missing. 「作品结算积分规则必须唯一启用」.

These are rig or release gaps, not proven product bugs. **r1-release should confirm** that production has this migration and `cms_setting` id 1. Without them, every settlement rolls back.

### F-04. HIGH: organiser screens say players can't pay, while they can and did

「这个主题暂不能在线收款，玩家无法在线报名付费场次」 appears on:
- the club theme page;
- 开一场 (`pages/club/event-ops/index.js:549`);
- the director page.

The pricing confirmation also adds 「平台正在办理支付资质…玩家还不能在线付款报名」.

Meanwhile four ¥39 orders were paid through the real callback (regs 74–77). The source is `paidSessionsBlocked` (`ClubCrmAppServiceImpl.java:305`) and the confirm message. They disagree with the payment gate after pay-gate.

Shots: `35`, `67`, `85`.

### F-05. HIGH: no club screen leads to pricing or sale

- The club theme header says 「未开售 · 待开售审核」 before any price was set. The 更多 sheet says 「定价中」 at the same moment.
- The only way to 确认终价 is the public theme page's grey disabled-looking bar 「定价中 · 未开售」.

Shots: `35`, `38`, `41`.

### F-06. HIGH: members are steered to the wrong product and can't find their session

- From the club's 14:30 session card, the theme page's main button is 「¥19.9 随时开玩」 (self-play). The session is behind a small 「查看场次」 at the very bottom.
- After paying for the session, the theme page still offers 「¥19.9 随时开玩」 and shows 「已参加 0」.
- The order page has no way into the session. Only 票夹 › 开始游戏 works.

Shots: `72`, `73-s2`, `74`, `89`.

### F-07. HIGH: 开始活动 fails silently

`/api/game/session/command` START returns HTTP 200 / code 200 with `outcome: FAILED` and a reason:
- 「当前不在活动服务时段」, or
- 「开局条件未满足：…站点服务时段…」.

The page stays 「准备中」 and says nothing. The lead taps again and again.

Repro: activity 9 before 14:30, after 开始准备. See `tools/probe.mjs`. Shot: `102`.

### F-08. HIGH: every person is 「城瘾用户」 where the organiser has to choose someone

This appears in:
- the member list;
- the role picker (`24`);
- the 定向广播 recipients (`114`, 4 × 「城瘾用户 · 角色待分配」);
- the leaderboard (`128`);
- the ownership transfer picker and its irreversible confirm 「转让主理人给 城瘾用户」 (`142`–`144`).

The lead's own row also reads 城瘾用户, while the info card says 林小舟.

The roster (报名参与者) does show real sign-up names (C-05 fixed), so the data exists.

### F-09. HIGH: the 招商 layer's numbers contradict its own lists

- 「0/2 已招站点」 with 3 stations.
- 「待处理申请 0」 while 「收到的申请」 holds one card.
- The same shop is listed under 「已确认」 tagged 待审核.
- 「还没人接 1」 above two or three unassigned rows.

Shots: `45`, `59`, `61`.

### F-10. HIGH: after 确认 in the 招商 layer the next step is lost

The server answers 「已选定该候选,请向该商家发出带条款的合作邀约」. The layer drops the message on purpose (no-toast rule) and offers no 发邀约 action. The older 合作 list does prompt 「去发邀约」 (`pages/coop/list/index.js:836`).

Shot: `61`.

### F-11. HIGH: raw request id on the team join success screen

After 接受邀请并加入 the page shows a card: 「接受邀请入队原请求已执行原请求号：a36abe3c6e4d43b7a6671421975a361d」 with 「确认结果」, above the success state.

Shot: `84`.

### F-12. HIGH: false conflict after a successful club profile save

After 保存修改 returns 「已保存」, the page shows 「保存后资料已变化，请对比后重新确认，续编草稿仍保留。 头像：草稿「已上传」／当前「已上传」」 with 重新读取. Likely cause: the server stores `media-asset:N` and the page compares it to its signed URL. **Not proven on a phone.**

Shot: `33`.

### F-13. HIGH: a definite refusal shown as 「核销结果待确认」

The group-code redeem was refused before any write (「没有生效的合作单」). The merchant still gets a modal plus a sheet saying the result is unknown, 「请勿重复核销」. The player side has the same pattern: 「提交结果待确认…暂请勿重复提交」.

Shots: `126`, `131`.

### F-14. HIGH: wrong next step right after publishing

「现在要做 · 开一场 · 选好日期和集合时间」 shows while the theme is in 立项审核 and has no price. There are two buttons, both labelled 开一场. The project card shows 「2026-11-04 · 0 人报名」, the theme start date, as if it were a session.

Shots: `32`, `33`.

### F-15. HIGH: 放行 row sits under the fixed 结束活动 bar

On the director page, the 「放行「第二章…」」 row lies under the fixed footer. Tapping it at that scroll position opened 「结束这场活动？」. The confirm dialog prevented harm.

Shot: `127-lead-release-row-under-footer`.

### F-16. HIGH: 俱乐部结算 doesn't say where the money came from

「可提现 ¥151.32」 is correct, but the page says 「还没有平台分润记录」 and lists nothing: no session, no gross, no 3%.

After the transfer:
- The former 主理人 keeps ¥151.32, which is correct, but loses the only page that shows it.
- Where they withdraw it is NOT VERIFIED.

Shots: `138`, `150`, `154`.

### F-17. LOW: the merchant station page

- Shows the raw code 「NODE_71」.
- Says 「主办方尚未配置本站任务，当前不要引导玩家开始」 although the station has its gameplay.
- After readiness the header reads 「准备完成 · 本站待准备 · 不可接待」 at once.

Shots: `108`, `111`.

### F-18. LOW: counts

- Gather and play headers say 「x/5 人」 with 4 paid players (`95`, `117`).
- The roster's trend block says 「还没有带参与日期的报名」 under four dated sign-ups (`100`).
- The recap shows 到场 4 while 3 checked in (`133`).

### F-19. LOW: team join mode contradicts itself

- Order page: 「仅邀请可加入」.
- Team page: 公开招募 switched on.
- Join page: 「邀请制」.

Shots: `80`, `82`, `83`.

### F-20. LOW: error style

Pink blocks with a warning icon instead of red text under the field:
- 确认终价, shown on first open before any input (`42`);
- 交接原因 (`145`).

### F-21. LOW: club home and roles polish

- 群主 vs 主理人 used for the same person (`16`).
- 举报俱乐部 shown to the club's own 主理人 (`17`).
- When scrolled, the tab labels vanish and only the underline stays (`17`). Could be the rig.
- The 角色与权限 form isn't reset after 分配 (`27`).
- 发布主题 appears five times on an empty 管理 tab (`18`, `19`).

### F-22. LOW: 票夹 after the session ended

Still shows 「生成中… · 今天过期 · 开始游戏」 (`136`).

### F-23. LOW: the old 主理人 gets 「欢迎加入」 after the transfer

The welcome sheet shows again (`147`). The old owner was left on the 成员治理 page with transfer and ban controls they no longer hold, and no confirmation of the transfer (`146`).

### F-24. LOW: taglines, helper text and secondary labels

Each line below can go if the layout carries the meaning.

- **成为主理人 intro** (`01`): 「填完身份立即生效，不用等审核」, 「最多可以建 2 个俱乐部」. Make the button 「成为主理人」 and drop both.
- **成为主理人 form** (`02`–`06`): 「成为主理人」 eyebrow; 「标 * 的是必填」 (the asterisks already say it); 「联系方式不对外展示」; sub-lines under each ability; 「用于后续等级评定」.
- **主理人 done screen** (`07`): the title, 「已成为主理人」, 「已生效」 and the sentence all say the same thing. Keep one line.
- **建俱乐部 intro** (`09`): three bullets and 「之后可修改」. Type sub-lines in step 1 (`10`). 「标 * 的是必填」 (`13`, `14`).
- **Welcome sheet** (`21`): each item's explanation line. Titles alone, with the tabs named, would do.
- **设置 sheet** (`22`): 「设管理员、移除成员、邀请新成员」.
- **Roles** (`25`–`27`): role descriptions; 「撤销后立即生效」.
- **管理 tab** (`18`, `19`): 「先发布一个俱乐部项目」, 「还没有可统计的项目数据」.
- **发布主题 sheet** (`28`): 「选定后会先进入连续选点，随时取消即可回到编辑器继续填写」.
- **开一场 form** (`67`, `68`):
  - 「仅可修改无报名的未来场次」
  - 「最多排未来 90 天（64 期）」
  - 「退款窗口按「集合时刻前 24 小时」计算。」
  - 「自定义容量只适用于恰有一个票种的合作主题；多票种继续沿用原票种容量。」
  - 「当前为 1 天。」
  - 「空位为候补首位保留，最晚至报名截止」
- **招商 / coop invite** (`62`): 「查找原邀请请求（含其他设备）/ 查询…的本机原请求」 reads like a debug row; 「对方接受并成单后锁定」; 「已选定，发送前不可更换」.
- **Merchant apply** (`53`–`57`):
  - 「用于地图与到场导航，拒绝不影响填写」
  - 「5:3 横图，1–9 张」
  - 「决定这一站能接多少团、什么时候接」
  - 「每日可接待时段 · 选填」, which is actually required (submit says 「请选择可配合时间段」)
- **Theme page** (`73-s3`): two near-identical 评价 lines: 「未参与也可以评价…以实际积分记录为准」 and 「评论积分须符合参与及首评条件…」.
- **Order page** (`79`): 「票夹已生成，可继续探索或现场核验」; 「组队与否不影响活动举行，也不改变订单与退款规则。」
- **Team pages** (`82`–`84`): 「陌生人可在附近队伍里申请加入」, 「退队不退款，退票请到订单」, 「邀请码不能用于签到或核销，入队不代替报名或购票」, 「入队后自动进群聊」.
- **Play intro** (`91`): 「建议戴耳机，打开声音」.
- **成员治理** (`140`): 「转让后你将失去主理人权限」. Keep it inside the confirm dialog only.

Not counted:
- The sign-up consent line 「同意将姓名、手机号提供给主办方，用于报名核验」 is consent.
- The refund-tier rows are terms.
- The 开启定位 permission text is a permission disclosure.

## 3. Leads from ROUND8.md that touch this lane

- **`pages/club/edit`, 9003 with `?id=7001`.** Reproduced: 「暂时不能查看 · 当前账号无权进入该俱乐部」, with the edit form still rendered below (`155`). `/api/club/access/me` answers 403 「俱乐部身份无效或权限不足」. The owner of my own new club (990004) opened its edit page fine (`31`). This may be seed data for fixture club 7001, but rendering the form under a denial is a product issue (LOW).
- **`pages/topic/merchantinfo` blank as a merchant.** Not reproduced: as merchant 9002 from 合作中心 it rendered (`51`). As the club lead it rendered the 招商 layer (`45`).
- **Club settlement 「¥ -2.00」 vs 「−¥」.** Not reached: no club co-op settlement or adjustment row existed in this walk, so `ApiClubSettlementController.money()` (`"¥ " + x`) never produced a negative. NOT VERIFIED.
- **确认终价 pink block.** Confirmed (F-20).
- **Rig anonymous `GET /api/city/nodes` 401.** Seen at `up.sh` time, not in this journey.
- **Taps on `pages/topic/index`.** In the rig, real clicks on several controls of this page did nothing: the dock CTA, 查看详情与报名, 查看场次, 去结算, the self-play checkout. Probing showed the click landing on the right element with no handler call. Taps worked on every other page. I could not establish whether this is the rig's event bridge or the page. **A phone must confirm that a member can tap 查看场次 and the self-play buttons.** If it is the page, it is a BLOCKER for sales.

## 4. Rig changes and fixtures (none in product code)

`tools/rig-changes.diff` is the diff on the rig worktree. `tools/rig-seams/` holds the two stand-in servers.

**Rig seams:**
- **`wx.chooseLocation` queue** (`nav.js`). The continuous picker looped forever on the old stand-in; a queue of picked points then 「cancel」 is what a person does.
- **`wx.getRandomValues`** (`nav.js`, WebCrypto). It was missing, and team commands hung on 「加入中…」.
- **Stand-in for `api.weixin.qq.com`** (`stable_token`, `img_sec_check` → ok), trusted through the JVM cacerts in the container.
- **Local OSS stand-in** on `https://oss-rig.local:9443`, with dummy credentials passed to the app (`app.py` `CHENGYIN_OSS_*`). This let uploads run through the real `/api/common/uploadOSS`.
- **Browser timezone Asia/Shanghai.** A UTC browser had shown 06:30 for a 14:30 session.
- **Picker wheels** (time, date, service range) can't scroll in the rig. The page's own change handler received the value the wheel would emit.

**Fixtures (throwaway database):**
- `settlement_rule` generic activity and topic rows at 3%, as `migration_20261103_settlement_rule_three_percent` leaves production. The fresh rig table was empty.
- An approved cover media asset.
- The merchant sign-up window (F-01a workaround).
- Theme on sale (F-02 workaround).
- Session and station time shifts.
- `cms_setting` id 1.
- `migration_20260815_tandianri_h8_publisher_points_funds.sql` applied with the repo's own `sql_splitter`.
- Platform approvals through the real admin review endpoint (`tools/approve.py`).

## 5. Not covered

- **Theme-template copy (「用这个模板」):** there is no theme template in a fresh database. NOT VERIFIED.
- **The editor's full input path:** pop-ups are after launch, and cover upload ran only through the club profile.
- **The merchant's own path to `merchant/game-node`:** reached by URL after the theme left 合作中心. Also the merchant's 承接进度 and withdrawal.
- **Players finishing the route, endings, medals and the finish-time reward:** blocked by F-01e.
- **Club co-op settlement and its 「¥ -2.00」 display:** no co-op order could exist.
- **Refunds in this journey:** none were made. The late-refund 50% warning was seen (`79a`).
- **Withdrawal for the old and new 主理人.**
- **Notifications in members' inboxes** for the broadcast: the DB shows REACHED; the inbox screen was not opened.
- **Phone-only behaviour:** `getPhoneNumber`, the real map, the payment sheet, image security on WeChat, subscribe messages, and taps on `pages/topic/index` (section 3).
- **Map, routes and map styles:** skipped as instructed. Nothing in them broke this journey.

STATUS: PARTIAL - the session could not be played to the end because of F-01 (four gates on club themes with a merchant station). Steps past the gates ran only with labelled fixtures or API replays. Theme-template copy and the theme-page taps (section 3) are unverified.
