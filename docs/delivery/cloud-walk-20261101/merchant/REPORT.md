# Merchant walk: 梧桐里书房 on 「梧桐里·武康路探店日」

Lane: merchant · branch `claude/cloud-walk-20261101-merchant` · candidate `master` a1d30fb · rig `claude/cloud-walk-20261101-rig` (RIG-STATUS READY) · walked 2026-11-02, 12:50–13:45 Asia/Shanghai.

Persona: 林小梧 owns 梧桐里书房 (书店 · 咖啡), a small bookshop-café at 复兴西路62号. They are not technical, use the phone one-handed at the counter, and have customers waiting. They signed up from scratch, joined a free-exploration theme after it was published, and then served five players at the counter on the day.

## 1. Verdict in five lines

1. **Getting in works, but takes three detours.** Sign-up → approval → finding the theme → applying for a stop → perk supply took about 25 taps. On the way I hit three stale sheets that still said "create X first" after I had created it. One sheet lost my place entirely. A real owner probably gives up at 「请先完善商家品类」, or when publishing a game drops them on the 模板 tab.
2. **Scanning a valid code is fast and correct.** The result sheet never says *what* to hand over. The server returns 「第四站·一页手写的回信」 and the sheet shows only 「核销成功」.
3. **Every rejected code looks like a possible double redemption.** A forged code, an expired code and a code from the wrong shop all show 「核销结果待确认…记录暂未出现也不能证明未核销…无法确认时请联系平台处理」. With a queue in front of them, the owner cannot tell "not valid here" from "maybe charged". This is the biggest counter problem.
4. **On the day, the shop is told nothing.** Home says 「周四 11月12日 · 今日无待办」 while a session with 4 paid players starts at 13:23. 「核销记录」 says 「已核销 0 · 未核销 10」 after 5 real redemptions. The numbers disagree across six screens.
5. **The money is honest but never explained.** Every redemption is 「权益已履约 · 无现金结算」 and earnings stay ¥0.00, which reconciles to the fen. Nothing at join time says "you are paid nothing per redemption". Withdrawal is 「联系平台客服提现…线下处理」.

## 2. The journey, step by step

Evidence labels:
- **UI** = VERIFIED-UI: real page in the rig browser against the real backend, with a screenshot.
- **API** = VERIFIED-API: real HTTP against the real backend, with a DB read-back.

### Stage A — Getting in

| # | Wanted | Did / saw | Felt | Evidence |
|---|---|---|---|---|
| A1 | Open a shop account | 我的 has no merchant entry. The gear opens 设置, which shows 「成为商家」 as a top-level row. The home carousel also has a 「成为商家」 banner. | Found it, but only because I tapped the gear. | UI `01-settings` |
| A2 | Fill the form | Four light, clean steps: 「先认识你的店」 → 经营信息 → 资质 → 「确认这份入驻信息」. On step 4, a required **经营者实名 (姓名 + 身份证号)** block sits *below the fold*. The 「提交申请」 button is grey with no reason given until you scroll. | Good pacing. A moment of "why can't I press submit?". | UI `02-apply-step1`, `03-submit-bottom` |
| A3 | Know it was sent | 「你的申请已提交 · 预计 1–3 个工作日完成审核」, with a 4-step timeline. | Reassuring, and the best screen in sign-up. | UI `04-submitted` |
| A4 | Approval (platform plays admin: `PUT /business/merchant {status:1}`) | No message arrives. On the next open, home becomes the light workbench 「Hi，梧桐里书房 · 营业中 · 累计收入 ¥0.00」 with 「开通前置检查」 (avatar / cover / intro). | The switch is nice. I'd have liked a "you're approved" notice. | UI `05-home-after-approval`; API `mms_merchant 8006 status=1, account_status=1, member_capability merchant=1` |
| A5 | Find a theme to join | 营销 tab → scroll past the floating tab bar → 「合作机会 · 2 个开放商机 · 进入合作中心」 → 广场. The theme card reads 「梧桐里·武康路探店日 · 11月12日 – 12月12日 · 权益核销 · 权益不低于 ¥30 · 5 站」. | Clear card. The entry is buried. | UI `06-coop-center` |
| A6 | Join | Theme page CTA 「立即报名」, which is player wording on a merchant page. The sheet opens with 「请先完善商家品类 · 去完善品类」, although I typed 「书店 · 咖啡」 as 经营类目 during sign-up. | "I already told you." | UI `07-topic-merchantinfo`, `08-apply-sheet` |
| A7 | Fix category | Lands on 品牌中心, not the field. 门店信息 → 编辑 shows two fields: 经营类目 (my text) and 行业类型 「选择行业」. I picked chip 「咖啡茶饮」; after saving it reads 「咖啡」. Three words for one idea: 品类 / 经营类目 / 行业类型. | Confusing. | UI `09-industry`; API `category_id 0→4001` |
| A8 | Back to the theme | Sheet **still says 「请先完善商家品类」**. Stops appear only after leaving and re-entering the page (「第一站… 还缺 1 家」 …). | Thought it hadn't saved. | UI `10-apply-sheet3` |
| A9 | Pick a game | 「还没有可用的玩法 · 先建一个玩法，回来就能选上」 → a 28-game picker. I built 选项问答 「老先生的卡片上，回信要留在书房的哪里？」 with options 二楼的留言墙 ✓ and 收银台的抽屉. 发布玩法 is grey. Tapping it shows the toast 「请至少配置一种奖励」 and scrolls to the *top*, not to the reward block. I removed 完成奖励, which is fine because the perk is the reward, but nothing says so. | The game editor is rich. The reward block is a trap. | UI `11-create-game`, `65-game-config`, `12-publish-toast`, `13-reward` |
| A10 | Return to the theme | After publishing, the app **relaunches into 我的节点玩法 (「个人审核中」)**. Back goes to the 模板 tab, not to the theme. I had to redo 营销 → 合作中心 → 广场 → theme → 立即报名. The sheet's promise 「回来就能选上」 is broken. | Lost my place. This is where a busy owner quits. | UI `14-game-published`, `15-after-back` |
| A11 | Apply for stop 4 | Picked 「第四站·一页手写的回信」 and my game → 「申请承接并配置节点」. The address is prefilled but 「地图选点」 is still required. → 「承接进度」. 我的节点 reads 「待审核 · 审核通过后可配置节点内容」, although I had just configured it. | OK. | UI `16-apply-form`, `17-progress`; API `merchant_chapter_application#1 status 0`, `cms_topic_node 76` |
| A12 | Organiser approves (club leader 9003 via `/api/merchant/chapter-application/audit`) | Home shows a project card 「待补供给 · 梧桐里·武康路探店日 · 第四站·一页手写的回信 · 周四 11月12日 · 今日无待办」. 我的消息 shows 「系统通知 · 章节申请审核结果 章节申请已通过」. | Good that it arrived. 「待补供给」 is not shop-owner language. | UI `18-home-after-accept`, `66-home-scrolled` |
| A13 | Set my perk | Project → 「填写实际供给」 → 「还没有常备权益，先创建后再回来选择 · 去创建常备权益」. The perk form is simple: 「手冲一杯 + 日期章明信片 · 礼品 · 零售价 ¥38 · 成本 ¥12 · 可接待 30 份 · 不设有效期」. Back on the sheet it **still says 「还没有常备权益」**. Close and reopen, then 「核销额度 20」 → 「实际供给已生效」. The header chip still reads 「待填写供给」. | Third stale sheet. | UI `64-perk-form`, `20-supply-stale`, `21-supply-done`; API `merchant_chapter_offer#1 quota 20 PERK` |
| A14 | Get my QR | Project → 二维码 → 「本站打卡码 · 个人码生成服务繁忙，请稍后重试」. This needs WeChat's wxacode service (HARNESS). There is no text code to fall back on. | — | UI `22-qrcode`, NOT VERIFIED as real behaviour |
| A15 | Does it reach players? | Player API `/api/play/nodes` lists my stop 「梧桐里书房 · 留言墙」 **and** a ghost 「梧桐里书房」. The ghost is the organiser's placeholder for chapter 4, which my UI join never replaced. The player play page shows 「2/6」 with chapter 4 listed twice. The catalog has `merchantName: null` for every store. | Reaches players, but doubled. | API; UI `26-player-play` |

**Theme-wide side effect of my join (S1).** My perk, created through the only merchant perk form, has no 核销规则 (the form has no such field). That alone made the organiser's sale-open fail with the opaque 「SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES」. The proof: filling one DB field (`coop_perk.redeem_rule`, fixture) made the same draft save immediately. See M-01.

### Stage B — Day of the session, before players arrive

Setup:
- Club 7002 opened a session today at 13:23 (activity 13).
- 4 players (陈一舟, 周小满, 吴桐, 许知夏) joined the club, signed up and paid ¥69.00 each through the real `/api/registration/callback`.
- A 5th player (孙望舒) paid for the **11月12日** session.

| Wanted | Saw | Evidence |
|---|---|---|
| Is anything on today? | Home: 「承接中 · 第四站·一页手写的回信 · 周四 11月12日 · 今日无待办」. Nothing about 13:23 today. `/api/merchant/upcoming-runs` returned `[]`, and `/api/merchant/todo-summary` returned `pendingScanConfirm: 4`, which is never shown. | UI `23-home-before`; API |
| How many people to expect? | Project page: 「11月12日 – 12月12日」 and 「主题已锁定，不可配置节点」. No date, no headcount. 客户: 「0 · 本店现场可见 · 本店暂没有现场客户记录」, including under 「还没到店」. | UI `24-project-today`, `25-customers` |
| What do I / my staff do? | No checklist, no "today" card, and no story or answer to read. (Owner expects a 剧情与玩法 entry: PR #704.) The club leader's page, by contrast, shows 「接下来 · 11月2日 13:23」. | UI; `62-club-topic` |

### Stage C — Session running (the core)

All scans used the real merchant home 「扫码」 with the scan seam injecting the player's real dynamic code from `/api/verify/dyncode/issue`.

| Case | What the owner sees | Server truth | Instant to understand? | Evidence |
|---|---|---|---|---|
| Correct code (周小满, 13:20, 3 min before start) | Sheet 「操作结果 · 验票成功 · 核销成功 · 知道了」 | `核销成功 {chapterName: 第四站·一页手写的回信}`. Entitlement ch45 0→1, quota_used 1. | Yes, but it doesn't say *what* to give, *who* this is, or which stop. Heading 「验票」 and body 「核销」 use two different words. | UI `27-scan-early`; API |
| Same code again | Amber 「已核销 · 该章节已核销，请勿重复提供权益」 | `replayed` | Mostly. 「已核销」 reads like the success word; the amber colour carries it. | UI `28-scan-again` |
| Not a 城瘾 code (a URL) | 2-second toast 「二维码格式错误」 | — | Yes, but it vanishes. | UI `29-scan-garbage` |
| Tampered code | 「验票结果待确认 · 核销结果待确认，请勿重复核销；请先查看核销记录」, then a modal 「核销结果待确认 · 请打开核销记录确认结果；记录暂未出现也不能证明未核销，请勿退出后重新提交。无法确认时请联系平台处理。」 | `{"msg":"签名不匹配","code":500}`. Nothing happened. | **No.** It frightens the owner and gives them nothing to tell the customer. | UI `30-scan-tampered`, `31-tampered-modal` |
| Wrong shop (隔壁甜品铺, a real second merchant not in the theme) | Same 「待确认」 sheet and modal | `您在本路线没有可核销权益或有效核销回执` | No | UI `33-wrong-store`; API |
| Expired code (staff phone, 5 min 12 s after issue) | Straight to the 「核销结果待确认…联系平台处理」 modal | `{"msg":"已过期","code":500}` | No | UI `49-expired-staff` |
| Wrong session (孙望舒, ticket for 11月12日 10:00) | 「验票成功 · 核销成功」 | Redeemed today. Entitlement ch45 = 1. | The owner cannot tell. Only the project 客户 list later shows 「2026-11-12 10:00–… · 13:24 核销」. | UI `34-wrong-session`, `54-project-customers-final`; API |
| One code at many shops (吴桐: my stop, then 梧桐咖啡) | Both 「核销成功」 | ch45 and ch42 both redeemed | **OWNER DECISION**, as designed. The cost: the owner can't see which stop the code is "for". | UI `33`/API |
| Several players at once | Five scans in about 6 minutes. Each opens a modal sheet that must be closed with 「知道了」 before the next scan. Taps on 扫码 while a sheet is open close the sheet instead of scanning. | — | Workable. Two taps per customer. | UI |
| Player asks "what do I do here?" | Nothing on the project page. Tapping 「梧桐里书房 · 留言墙」 does nothing. The question and answer live only in 我的节点玩法 under the 模板 tab. | — | No (PR #704) | UI `24-project-today` |
| Stock running out | Quota is checked at **purchase**: a new buyer gets 「该路线权益名额不足,暂时无法购买」, so a counter can't be over-promised. Good design. | API (fixture: `quota_total` set to 5 in DB, then restored) | — | API |
| Refund / dispute at the counter (吴桐 asks for a refund) | Player gets 「本单已转平台人工评估，尚未退款」. The shop's 退款售后 shows 「没有待回应售后」 / 「没有处理中的售后」, and 核销与退款 · 待处理 shows 「还没有记录」. | `cancellationStatus: MANUAL_REVIEW` | The shop never learns of the dispute. | UI `55-aftercare`; API |
| Shop closed (「已打烊」) | Redemption still succeeds (梧桐咖啡 closed → 「核销成功」). Players' stop list carries no open/closed state. | API | — | API |

### Stage D — During the session: numbers, broadcast, edits, staff

Truth: 5 redemptions at my counter (regs 74–78), quota_used 5, ¥0 cash.

| Screen | Says | Matches? |
|---|---|---|
| 核销与退款 (via 财务 / 查看记录) | 「本月核销 5 笔 · 待结算 ¥0.00 · 已入账 ¥0.00」, 5 rows 「权益已履约 · 无现金结算」 | ✅ (`52-ledger-redemptions`) |
| Project → 客户 | 「5 · 本店现场可见 · 5 已核销」 with full names and session times | ✅ count. Names conflict with 「顾客信息受保护」 on the line above it. (`54`) |
| 店铺参谋 | 「近30天，城瘾为你带来 5 位玩家到店 · 到店 5 · 打卡 5 · 核销 5 · 午上到店最多」 | ✅ (`57-advisor`). Typo 「午上」. |
| 更多 → 核销记录 | 「已核销 0 · 未核销 10」, rows 「未命名节点 · 13:24 · 未核销 · 报名 #78」 | ❌ (`38`, `39`, `53`) |
| Home 客户 (CRM) | 「0 位 · 本月新增 0 · 还没有客户 · 去合作中心」 | ❌ (`40-crm`) |
| 营销 | 「近 30 天报名 0 · 核销 0」 | ❌ (`56-marketing-after`) |
| 我的 → 我的项目 | 「梧桐里·武康路探店日 · 2026.11.12 - 2026.12.12 · 未开始」 | ❌ (`44-me-merchant`) |
| Home | 「累计收入 ¥0.00 · 今日无待办」. No redemption count anywhere. | ¥ ✅, today ❌ (`37-home-live`) |

- **Broadcast.** As a stop-holder (承接方) I found no 广播 entry on my project page. The API preview `/api/merchant/crm/broadcast/preview` returns `deliverableCount: 0` both with and without `topicId`. I could not misfire to everyone, but I also couldn't tell my 5 players anything. The host-only broadcast is PR #703. (API)
- **Editing / pausing the perk mid-session.** 常备权益 shows only 「删除」, with no edit or pause. The delete confirmation is excellent: 「已经发出去的权益不受影响…玩家已经领到的同名权益仍然有效…此操作不可撤销」 (`43-perk-delete`). The perk card says 「可接待 30 份」 while the theme quota is 20 with 5 used. Remaining stock is shown nowhere. (`42-perks-live`)
- **Closing early.** One tap on the 「营业中」 chip flips the shop to 「已打烊」, with **no confirmation** (`41-business-status`). It does not stop redemptions or inform players (above).
- **Staff.** 我的 → 经营团队 → 邀请员工 offers 「店长 / 核销员 / 运营 / 财务」, each with one plain line of scope. Very clear (`45-invite-staff`). 「核销员邀请已创建 · 2026-11-03 13:28 前有效，请在本页分享」 (`46`). The share card is a WeChat share (NOT VERIFIED); acceptance was done via API.
  - The staff member's **home is the dark player home** with 「成为商家」 (`47-staff-home`). Only 我的 shows 「梧桐里书房 · 核销」 and 「经营团队 · 核销员 · 查看」 (`48-staff-me`). A new 核销员 will look for the shop on home and not find it.
- **Messages.** Inbox holds one 「系统通知 · 章节申请审核结果 章节申请已通过」. There was no notice for merchant approval, the theme going on sale, today's session, a player refund, or the game review (`58-im`).

### Stage E — After

- **Earnings.** 财务 → 账户收益: 「¥0.00 · 这个月还没有收入 · 可提现 ¥0.00」 (`50-earnings`). This reconciles with 5 × 「无现金结算」 = ¥0.00 to the fen. The owner gave away 5 coffees (cost ¥12 each, which they typed in), and nothing on the money screens shows what the theme cost or brought them. Only 店铺参谋 tells the "5 people came" story.
- **Settlement expectations.** At join time the theme card said 「权益核销 · 权益不低于 ¥30」, and nothing said "no cash per redemption". The first time the owner learns it is the line 「权益已履约 · 无现金结算」 *after* serving.
- **Withdrawal.** 提现 opens 「联系平台客服提现 · 客服微信号：… 添加客服微信，确认金额后线下处理」 (`51-withdraw`). It's honest, but it's manual and the in-app 提现 button suggests otherwise.
- **Customer data.** Project 客户 shows full real names. 核销与退款 shows 「顾客信息受保护」. The CRM shows 0 customers (above).
- **Store advisor.** 店铺参谋 is the most useful post-session screen: friendly 「下午好，梧桐里书房」 and a clear 30-day dot calendar (`57`).
- **Session history.** There is no per-session view anywhere (date, players, redemptions). 我的项目 still says 「未开始」.
- **Joining another theme.** 合作中心 → 广场 lists the fixture theme 「E2E 探店日一期」, counted in 「2 个开放商机」. Its sheet says 「这条路线暂时没有开放的章节」 (`59-second-theme`). The orienteering theme I authored (below) is not listed.

### City orienteering (城市定向)

I authored 「外滩来信 · 城市定向」 (topic 990020): 3 chapters, 8 Bund–People's Square stations, story text, real coordinates. It was published through `/api/topic/v2/create`.
- To do that, the organiser's games had to be cloned from my shop's template row in the DB (fixture), because `template/publish` bodies were cut by the rig's 2,000-char net log.
- As the merchant, it **does not appear** in 合作中心 → 广场 or 官方活动 (`60-coop-center-orient`).
- Opening its merchant page directly (as from a shared link) shows 「立即报名」. Tapping it flashes 「报名时间信息不完整」 (`61-orient-apply`). My fixture did not set a merchant sign-up window, so this is partly fixture.
- The code path agrees: chapter applications are refused for orienteering (「…不是自由定向，暂不开放商家承接」), and stations go through the legacy `/api/registration/merchant/create` plus organiser candidate confirmation.
- **A merchant station in a city-orienteering theme: NOT VERIFIED end to end.** I could not reach it through any merchant-discoverable path in this run.

## 3. Issues, ranked

| ID | Sev | User-facing problem | Reproduction | Expected | Shot | Evidence | Likely code | Smallest change |
|---|---|---|---|---|---|---|---|---|
| M-01 | S1 | One shop's perk silently blocks the whole theme from going on sale | Merchant creates a 常备权益 through 店铺装修 → 常备权益 → 添加权益 (no 核销规则 field), uses it for 实际供给. Organiser saves sale parameters → 「SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES」. Setting `coop_perk.redeem_rule` alone makes it save. | Perk form either asks for 核销规则 or the snapshot accepts a default; at minimum the error names the shop and perk | `64-perk-form` | API (draft PUT failed / succeeded with only that field changed) | `pages/merchant/decor/perks/index.js` (save payload); `TopicSaleCanonicalV1.validateFreeExploration` requires `redeemRule` on perks | Default `redeemRule` (e.g. 「到店出示核销码」) when the perk is enrolled, or add one optional field to the perk form |
| M-02 | S1 | Every rejected code tells the owner the result is unknown and to call the platform | Scan a forged / expired / wrong-shop code | 「未核销：签名不匹配 / 已过期 / 本店没有这张票的权益」, with nothing to undo | `30`, `31`, `33`, `49` | UI + API (server returned plain business errors) | `utils/merchant-verification.js:57-63` (all business 500s → unknown, by design) | Have the scan endpoints mark pre-transaction rejections (e.g. `data.notRedeemed: true` or an `errorCode`) and show those as a clear red 「未核销」 with the server's message. Keep 「待确认」 for real ambiguity. |
| M-03 | S1 | 核销记录 says nothing was redeemed after 5 real redemptions | 更多 → 核销记录 after redeeming | 「已核销 5」 matching 核销与退款 | `38`, `39`, `53` | UI + API (`redemption` 5 rows ACTIVE) | `/api/merchant/verification-records` (`ApiMerchantController` ~1739) reads verification rows that stay `status 0` for explore-pass redemptions | Count from `redemption` (as `finance/redemptions` does), or hide 「未核销」 rows once the entitlement is redeemed. Show stop name, not 「未命名节点 / 报名 #77」. |
| M-04 | S2 | On session day, home says nothing is on | Free-explore stop-holder; club opens a session today; players pay | Home: 「今天 13:23 · 4 人已报名 · 已核销 0」 | `23`, `37` | UI + API (`upcoming-runs` = `[]`, `todo-summary.pendingScanConfirm` = 4) | `MerchantFulfillmentReadServiceImpl.listUpcomingRuns` only reads `cms_registration_merchant` (orienteering stations); project card uses the topic's first ticket date | Include chapter-offer topics (`merchant_chapter_offer` active) in `listUpcomingRuns`, and show today's count on the card |
| M-05 | S2 | Success sheet doesn't say what to hand over | Scan a valid code | 「核销成功 · 第四站·一页手写的回信 · 手冲一杯 + 日期章明信片」 | `27` | UI + API (`data.chapterName` present) | `utils/merchant-verification.js` `resolveVerificationResult` (~87-120) | Append `data.chapterName` (and perk name if returned) to the success message |
| M-06 | S2 | A ticket for another day is redeemed today with no warning | Player with 11月12日 ticket scanned on 11月2日 | Warn 「这张票是 11月12日 场」, or refuse before the session | `34` | UI + API | Explore-chapter branch in `RegistrationRedemptionApplication` (only `ExploreTicketUsePolicy` "ended" check) | Return the session date in the receipt and show it, or apply the 60-minute window that club tickets already use |
| M-07 | S2 | Joining via the real page leaves a ghost duplicate stop for players | Merchant joins a stop via 申请承接并配置节点 | Player sees one stop per chapter | `26`, `60` (「6 站」), `62` (「5 章 · 6 站」) | API (`cms_topic_node` 74 unbound + 76) + UI | `/api/merchant/chapter-node/submit` creates a new node instead of binding the chapter's placeholder | On submit/approval, bind or retire the chapter's unbound placeholder node |
| M-08 | S2 | Sheets keep saying "create X first" after X was created | Category / game / perk created from the sheet's link, then back | Sheet refreshes on return | `08`, `10`, `20` | UI | `pages/topic/merchantinfo/merchantinfo.js` (no reload of merchant category / perk list / templates in `onShow`) | Re-fetch these three lists in `onShow` when the sheet is open |
| M-09 | S2 | Publishing a game throws the owner out of the join flow | 去创建玩法 → 发布玩法 | Return to the stop sheet with the new game selected | `14`, `15` | UI | `pages/publish/temp/index.js` `publishGame` success path (relaunch to `mytemplate`) | When opened from merchantinfo, `navigateBack` instead of relaunching |
| M-10 | S2 | Numbers disagree across screens | Compare after 5 redemptions | One count everywhere | `37`, `40`, `44`, `56` vs `52`, `54`, `57` | UI | marketing-home (`近 30 天报名` counts own content only), CRM list, profile project status from topic dates | Point 营销 / 我的项目 status at the same source as 店铺参谋 / 核销与退款 |
| M-11 | S2 | Refund requests never reach the shop | Player 「取消并退款」 after redemption | 退款售后 shows 「玩家申请退款 · 平台评估中」 | `55` | UI + API | aftercare list excludes platform `MANUAL_REVIEW` cancellations | List them read-only under 处理中 |
| M-12 | S2 | 客户 says 0 / 「还没到店」 empty while paid players are on their way | Project → 客户 before arrival | 「还没到店 4」 | `25`, `36` | UI + API | `/api/project/players` | Include paid registrations whose entitlement for this stop is unredeemed |
| M-13 | S2 | One tap closes the shop | Tap 「营业中」 chip | Confirm 「暂停营业？玩家仍可核销」 | `41` | UI | `pages/merchant/index/index.js:178` | Add a confirm modal |
| M-14 | S2 | Closing the shop doesn't tell players or stop redemption | 已打烊 shop scans a code | Players see 「今日已打烊」 on the stop, or redemption warns | — | API | `play/nodes` has no business status | Show 「已打烊」 on the player's stop card |
| M-15 | S2 | New 核销员's home is the player home | Staff accepts invite, opens app | Light workbench with 扫码 | `47`, `48` | UI | home role routing uses member role, not operator role | Route active operators to the merchant workbench (or a 扫码-only card) |
| M-16 | S2 | Category asked three ways, and sign-up text doesn't count | Sign-up 经营类目 → join says 「请先完善商家品类」 → field is 行业类型 | Ask once at sign-up with the same chips | `08`, `09` | UI | `pages/merchant/apply/index.js` (free-text `preference`) vs `decor` `INDUSTRY_OPTIONS` | Use the 行业类型 chips at sign-up; say 「行业类型」 in the join sheet |
| M-17 | S2 | Money model is never stated before serving | Join a PERK stop | 「本站为权益核销：玩家到店你提供权益，平台不按次结算现金」 on the join card | `06`, `52` | UI | coop-center card / supply sheet | One line under 「权益不低于 ¥30」 |
| M-18 | S3 | 「立即报名」 on the merchant theme page | Open theme from 合作中心 | 「申请承接」 | `07` | UI | `merchantinfo.wxml:302` | Change the label in merchant scope |
| M-19 | S3 | Hidden required 实名 block on 「确认这份入驻信息」 | Step 4 of sign-up | Required block above the fold, or the grey button says why | `03` | UI | `pages/merchant/apply` | Scroll to and highlight the missing block on tap |
| M-20 | S3 | Disabled 发布玩法 scrolls to top instead of the error | Tap grey 发布玩法 with no reward | Scroll to 完成奖励 | `12`, `13` | UI | `publish/temp` `onPreviewDisabledTap` | Scroll to the first error block |
| M-21 | S3 | Stale header chips | After supply: chip 「待填写供给」 + 「实际供给已生效」 | One state | `21` | UI | merchantinfo header | Refresh header after enroll |
| M-22 | S3 | 「待补供给」, 「承接」, 「供给」 are platform words | Home / project | 「还差一步：设置到店权益」 | `18` | UI | home project card | Rename the chip |
| M-23 | S3 | Counter flow costs two taps per customer | Scan, then 「知道了」, then scan | Success sheet auto-closes, or offers 「继续扫码」 | `27` | UI | merchant home result sheet | Add 「继续扫码」 as the primary button |
| M-24 | S3 | No remaining-stock number | Any perk view | 「剩余 15 / 20」 | `42` | UI | perks page / project supply card | Show `quota_total − quota_used` on the supply card |
| M-25 | S3 | 「二维码格式错误」 is a 2-second toast | Scan a non-城瘾 code | A sheet: 「这不是城瘾的核销码」 | `29` | UI | `goScanQR` invalid branch | Use the same result sheet |
| M-26 | S3 | Typo 「午上到店最多」 | 店铺参谋 | 「中午到店最多」 | `57` | UI | ai-insight / marketing | Fix the string |
| M-27 | S3 | Privacy wording conflicts | 核销与退款 「顾客信息受保护」 vs 客户 full names | One rule | `52`, `54` | UI | — | Mask names in 客户 or drop the 「受保护」 label |
| M-28 | S3 | 合作中心 counts a theme with no open stops as a 商机 | 营销 「2 个开放商机」 | Count only themes with open stops | `59` | UI | marketing-home `opportunity` count | Filter by open chapters |
| M-29 | S3 | 「审核通过后可配置节点内容」 shown right after configuring the node | 承接进度 | Show the node as submitted | `17` | UI | merchantinfo progress block | Drop the line when a node exists |

### Known, fix pending (not counted as new)

- **#702 / #703 R01**: 「开通前置检查」 still on the merchant home, and the small project card (`05`, `18`). The walk agrees the fix is right: the card was where I looked for "today", and it showed only 「周四 11月12日 · 今日无待办」. #702's bigger card does not by itself add today's session or headcount, so M-04 still stands.
- **#704**: 剧情与玩法 (with answer) missing from the merchant project page, plus 「去核销直接扫码」. This was confirmed at the counter (Stage C, "what do I do here?"). #704 covers host and 承接 merchants, which is sufficient for the "answer" need. It does not address M-02 or M-05.
- **#703**: Targeted broadcast. For a 承接 merchant on a free-explore theme the preview is 0 and there is no entry (Stage D). #703 opens broadcast only to the theme's own publisher, so a stop-holder still cannot message their players. Note as a gap, not a bug.
- **#701**: Club notice delivery. Not touched by this lane.
- **`fix/merchant-home-role-resync-20261101`**: Not found on origin (`git ls-remote` shows no such ref). The pending role re-sync could not be compared; the staff-home issue M-15 may be related.

## 4. Gameplay feel (what the merchant touches)

| Thing | Rating | What I saw | Smallest change |
|---|---|---|---|
| Authoring my stop's game (选项问答) | works but awkward | The picker of 28 games by 问答 / 推理 / 互动 / 挑战 is inviting, and the defaults (option 1 correct, single choice, 内嵌故事流) are sensible. Then: a required 玩法类别, a default 完成奖励 that blocks publishing, a toast that scrolls the wrong way, and being dumped on 模板 afterwards. I knew what to do only by trial. | Default 完成奖励 off for merchant stops (the perk is the reward); scroll to the error; return to the sheet |
| Fit to story and place | works and feels good | The question 「回信要留在书房的哪里？」 → 「二楼的留言墙」 fits the chapter story 「回信请留在梧桐里书房」 and my real shop. The editor let me write feedback per answer (「对了。上楼，找到写着你名字首字母的那一格。」 / 「再想想，卡片上写的是“墙”。」). | — |
| Counter moment (scan → result) | works but flat | Correct scans are instant. The result is a plain title with no stop, perk or player, so the moment of "you've reached chapter 4, here's your postcard" is lost. Wrong codes feel alarming, not informative. Timing here is the rig's browser, not a phone. | Show chapter name + perk on success; red 「未核销」 with reason on rejection |
| Player asks "what do I do here?" | works but awkward | The owner has to dig into 模板 → 我的节点玩法 to remember their own question. No answer is shown at the counter. | #704's 剧情与玩法 entry on the project card |
| Players' play at my stop | NOT VERIFIED | Players were held at 「队伍集合中 0/5 · 等队长开始行程…」 (club-led gate, OWNER DECISION) while I had already redeemed them. I did not see my question played. | — |

## 5. Cross-role seams

| What one role did | What the other saw | Lag / contradiction |
|---|---|---|
| Admin approved my shop | No notice to me; home switched on next open | No notification |
| Organiser approved my application | 「系统通知 · 章节申请已通过」 on my home and inbox | ✅ arrives. The time shows 05:10 for 13:10 local (HARNESS-suspect, below). |
| I joined stop 4 via the real page | Players see stop 4 twice; club sees 「5 章 · 6 站」; my theme card says 「6 站」 | Contradiction (M-07) |
| My UI-created perk | Organiser's sale-open blocked with a code naming no one | Contradiction (M-01) |
| Club opened today's session; 4 players paid | Club page: 「接下来 · 11月2日 13:23」. My home: 「周四 11月12日 · 今日无待办」. 客户: 0. | Merchant not told (M-04, M-12) |
| I redeemed 5 players | Player play page still 「队伍集合中 0/5」 (club-gated). Player's list shows chapter 4 done but counts 「2/6」. Club leader's 商家 sheet: 「该主题不属于本俱乐部」 (executing club). | The organiser can't see shop redemptions from that page |
| Player asked for a refund | Platform manual review; shop sees nothing | No notification (M-11) |
| I closed the shop | Players see no change; redemption still works | Contradiction (M-14) |
| I invited a 核销员 | Staff home stays a player home | M-15 |

## 6. Not verified, harness notes, fixtures

**Injected capabilities (NOT VERIFIED as real behaviour)**
- WeChat login: rig seam `code=rig-<openid>`.
- Camera scan: `scan` seam fed the real dynamic codes.
- `chooseLocation`: I overrode the rig's stub in-page to return 「上海市徐汇区复兴西路62号 / 31.21012,121.44295」, because the rig stub returns 「上海市」.
- Image uploads: `wx.uploadFile` was stubbed in-page to return `/profile/upload/walk/rig-stub-N.jpg`. The real `/api/common/uploadOSS` returns 503 「安全检查服务异常，请稍后重试」 because image security needs a WeChat access token (`AppUploadService.verifyContentSecurity`). `/common/upload` returns 「图片发布地址未配置或无效」. Business licence and brand images are therefore fake URLs (HARNESS).
- WeChat share card for staff invite: acceptance via `/api/merchant/operators/invite/accept`.
- wxacode poster QR: 「个人码生成服务繁忙」 (HARNESS).
- Payment: real `/api/registration/callback` with a VERIFIED raw payment fact written by `chain_player.store_trusted_payment_fact`. `payment_status` was never written directly.

**Fixtures and data edits (test DB only)**
- Theme authored via copies of the candidate's own chains. Patched copies live in the scratchpad, not committed:
  - `chain_platform.py`: believable title, story and coordinates; 5 chapters instead of 4.
  - `chain_merchant_club.py`: chapter 4 left for my UI join; placeholder binding loosened; node audit skips approved nodes.
  - `chain_sale_close.py` continuation split.
  - `canonical_draft.py`: `storeCount = len(stores)` and evidence rule `ALL_REQUIRED_CHAPTERS` instead of `FOUR_STORE_REDEEM`, which hard-requires exactly 4 stores.
- `sys_category` type 4 rows (互动体验 / 知识问答 / 拍照打卡) added via `/system/category`, because the fresh DB has no 玩法类别 and publishing a game was impossible (HARNESS).
- `UPDATE coop_perk SET redeem_rule=… WHERE id=2`: used to prove M-01's cause and to let the sale open.
- `UPDATE merchant_chapter_offer SET quota_total=5` then back to 20: used to test stock-out.
- Orienteering topic: organiser templates 7101–7103 cloned from my template row 6005 by SQL, set to private-draft state.
- Second merchant 隔壁甜品铺 (8007) created via real API and approved by admin, for the wrong-store test.
- Players joined club 7002 via `/api/club/join` (open club).

**Product calls the rig could not complete**
- Player `/api/play/checkin` returned 「必须提供动作标识、期望版本和原运行时身份」 with the chain's parameters. Player check-in at my stop was therefore not done. The player lane owns this.
- Every `/business/audit/review` on 「TOPIC」 / 「TEMPLATE」 tasks returned 409 「请刷新审核内容后重试」 (`AUDIT_SNAPSHOT_STALE`). As a result:
  - My game stays 「个人审核中」 forever.
  - Theme audit task 2 stays open, although the theme was already live.
  - Suspected HARNESS (timezone split below), not confirmed.

**Suspected artefacts**
- Times show UTC on some screens (「05:10」 for a 13:10 notice), and `redeemed_at` is UTC while `redemption.create_time` is Shanghai time. The rig's MySQL runs `--default-time-zone=+00:00` while the app runs Asia/Shanghai. HARNESS-suspect.
- Docker Hub rate-limited the pinned MySQL/Redis pulls (429). I pulled the identical digests from `mirror.gcr.io` and tagged them locally; nothing else changed.
- Theme covers show 「封面暂不可用」 because no cover was uploaded (uploads blocked, above).

**Not covered**
- City-orienteering merchant station end to end (not discoverable; sign-up window fixture).
- Real counter timing on a phone.
- Players actually playing my 选项问答.
- Merchant-hosted (non-club) session.
- Broadcast send.

**Security scan**: gitleaks download was blocked by the session's permission policy. The diff was grep-scanned for keys/tokens/passwords before each push. All secrets were session-generated and live outside the repository.

STATUS: PARTIAL - city-orienteering merchant station not verified end to end; player-side play of my stop not observed; gitleaks not run (grep scan only)
