# j1-merchant-sale: pre-launch check, second pass

- Lane: j1-merchant-sale. Branch `claude/check-1105-j1-merchant-sale`, cut from the commit under test.
- Commit tested: `573c463b3b457b77544e63be4271a07d5d565e09`, on `integ/launch-test-20261103`.
- Walked 2026-11-05, 10:15–11:05 Asia/Shanghai. Fictional data only.
- Rules: `ROUND8.md`, with this round's paths. I find; I do not fix. 按核销付费 cash terms on club sessions are not re-reported.

**This is the cloud rig, not a phone.** It runs:
- the real jar on real MySQL 8.0.45 and Redis 7.4;
- the real mini-program code inside headless Chrome, at 390×844 and 2x;
- branch `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff`, and `up.sh` pinned to the commit above;
- `LANG=C.UTF-8`. Docker Hub pulls worked, so the `mirror.gcr.io` mirror was not needed.

Shots are in `shots/`: 780×1688 JPEG, all under 200 KB. I looked at every shot quoted below. Read-backs are database reads from the rig (`cy_e2e_51`).

## Summary

The sale now runs end to end with no fixture past the rig's own gaps:
- The merchant applies and is approved.
- The merchant publishes three paid activities with no game.
- A fourth is refused at entry, and a slot frees when one is deleted, cancelled or ends.
- Players pay. Tickets show in 票夹. The owner and a clerk scan them.
- Every refund tier is right to the fen, on screen and in the database: 100%, 50% (rounded down), after start (refused), redeemed (refused) and organiser cancel.
- Settlement pays exactly what the merchant was shown as pending (¥82.43), and parts sum to sales.

All four first-pass blockers (F1, F3, F4, and F2 as the second gate) are gone.

New this pass, none of them blockers:
1. **HIGH.** On an activity with no game, the ticket's main button 「开始游戏」 opens a play page that says 「活动或主题不存在」 (N1).
2. **HIGH.** A ticket price with jiao or fen can't be typed: 39.99 becomes 3999 (N2).
3. **HIGH.** A deleted activity stays on the merchant home and in 我的项目 (N3).
4. **HIGH.** The 核销与退款 rows can't be added up to the header, and money that sits in the in-app balance reads 「平台已打款」 (N4).

## 1. First-pass findings: are they gone?

| First pass | Now | Evidence |
|---|---|---|
| F1 merchant can't publish | **Gone.** The rig's merchant row is 1/3 straight from `migration_20261104_merchant_activity_limit.sql`, with no fixture. Publish works. | Activities 9, 10, 12 published; B1–B8 |
| F2 game required, draft lost | **Gone.** No template is needed (activity 9 has `template_id 0`). Leaving to 创建节点玩法 and coming back keeps the draft. template-intro is light on the merchant path. | 22-step2, 104, 105 |
| F3 settlement overpays 1 fen | **Gone in what I ran.** Two real settlements sum exactly: 39.99 = 38.79 + 1.20, and 44.99 = 43.64 + 1.35. No half-fen total came up in this walk, so the ¥97.50 case was not replayed in the rig. | log lines, `ums_member_balance_detail` 39–42 |
| F4 invitee can't accept | **Gone.** walk-player-6 accepted in the app in player view. | 87, 88; `merchant_operator` 1 ACTIVE |
| F22 invite page names nothing | **Gone.** 「梧桐里书房 · 核销员」 | 87 |
| F7 refund amount not shown | **Gone.** The confirm says 「本次取消将原路退回 ¥69.95 / ¥14.99」. The order shows a 「退款」 row. | 62b, 62d, 63b, 63d |
| F12 pending leaves out the kept 50% | **Gone.** 待结算 ¥82.43 = what settlement paid. | 70b, 70c, 71b |
| F13 merchant numbers disagree | **Gone.** 财务, 核销与退款, 核销记录, 营销 and 结算 agree before and after settlement. Home 累计收入 stays ¥0.00 until settlement, which is f1's design. N4 is a new issue in the rows. | 51–54, 70, 71 |
| F14 organiser is a nickname | **Gone on screen:** 「主办方 梧桐里书房 · 商家」 on the detail and the order, and the shop in 合作者. `/api/project/my` still returns `projectType: club_activity`, but no screen shows a club tag. | 40b, 22-step3-filled, 44 |
| F6 publish step 3 | **Mostly gone.** Price is required with no prefilled 0. The summary has no seconds and no longer covers 添加票单. I did not re-measure margins. | 22-step3-filled, 22-step3-bottom |
| F9 location placeholder | **Gone.** The placeholder is no longer 「请输入活动地点」. The location hint line remains; see captions. | 22-step1-empty |
| F10 narrow 返回首页 | **Gone.** | 15 |
| F23 即将上线 card | **Partly.** 「还剩11小时 / 还剩3天」 is fixed. The three empty grey pills remain. | 40 |
| F21 mixed | **Partly.** The owner's own invite now answers in Chinese, but 「你还有一条团队邀请 · 接受」 is still offered first. 财务 says 活动分润. Refund rows use −¥. Rows still show 「城瘾用户」 instead of the sign-up name. | 100, 101, 70c |
| F18 money and labels | **Partly.** The order has 2 decimals and the 24-hour clock. Still there: 「-¥0」 for 积分抵扣, 「¥25」 on the activity detail, the glyph under the status bar, 「不可退款」 as the status pill after start, and ASCII 「;」 「,」 in refund rules. | 41b, 102b, 43i, 65b, 62b |
| F15 dark sheet, card under tab bar | **Still there.** The activity sheet from the merchant home is dark on the light page. A tap on the bottom project card opened the 模板 tab. The transparent bar is the owner's 2026-09-19 decision, but the misdirected tap is not. | 102, 102b |
| F16 scan result has no details | **Still there.** 「验票成功 / 核销成功」 only. | 50 |
| F20 clerk scan from 我的 → 核销 | **Still there.** The scan succeeded (reg 77 verified), but nothing was on screen 2.5 s later. | 91, 91b |
| F8 no approval message | **Still there.** 我的消息: 「还没有消息」 after approval. | 16 |
| F5 咖啡茶饮 saved as 4001 | **Still there.** Industry ids saved `30,4001`, and 4001 is type-1 「咖啡」. | DB `mms_merchant` 8006 `category_ids` |
| F11 editor | **Not re-walked.** A game is no longer needed, so I did not build one. | — |
| F17, F19, F24 | Owner decisions (ROUND10-F3). Not re-reported. | — |

## 2. Steps

**Rig clock:** MySQL `NOW()` runs in UTC. Rows written with SQL `NOW()` show times 8 hours behind (02:41, 02:47) beside the Shanghai times the app writes (10:39). This is a rig artefact, but see lead L1.

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| A1 | Player home → 「成为商家」 banner | PASS. The subline is gone. | 01, 02 | — |
| A2 | Apply intro | PASS. LOW: the title sits on the button (N11). | 03 | — |
| A3 | Step 1: name, industry (书店 + 咖啡茶饮, with 已选 2/5 and 主 marked), phone | PASS. 「标 * 的是必填」 is still there. | 04–07 | `category_ids 30,4001` (F5) |
| A4 | Step 2: location pre-prompt → 打开地图, hours sheet, intro | PASS. The address is the rig's 「上海市」. | 08–10 | — |
| A5 | Step 3: licence upload | NOT VERIFIED (HARNESS). The upload calls WeChat `img_sec_check`: 503 「安全检查服务异常」. The licence was set through the page's own `setData`+`validate`. | 11, 12, 13 | — |
| A6 | Step 4: real name, ID number, consent, 提交申请 | PASS | 14, 15 | `mms_merchant` 8006 status 0 |
| A7 | Platform approves | PASS through the admin API `PUT /business/merchant`. The admin web UI was not walked. | — | 8006 status 1, account_status 1. Member 990005 role `merchant`. |
| A8 | Reopen → merchant workbench | PASS. No approval message (F8). | 16 | — |
| B1 | 营销 → 发活动, step 1 | PASS, with rig workarounds. The location `<input>` swallows the tap, so I called the page's `choosePoiForNode`. Date wheels aren't drawn, so I moved them with the page's own change handlers. The activity-type list was empty (L2), so I added two `sys_category` type-2 fixture rows. | 20, 21, 22-* | — |
| B2 | Step 2: description, cover; no template chosen | PASS. No template is required. Cover upload is the same rig gap: the fixture is an approved `app_media_asset` 1. The missing-cover error is a pink block (N9). | 22-step2, 22-step3 | — |
| B3 | Step 3: tickets 单人票 39.99 ×20, 双人票 69.95 ×10 | **FAIL (N2).** Typing 39.99 gives 3999. I passed the whole string through the page's `onTicketChange` to go on. | 22-step3-filled, 22-step3-bottom | — |
| B4 | 发布 | PASS. Lands on 我的项目 on the 主题 tab, 「还没有主题 · 去发布」 (N6). | 27 | `cms_activity` 9: template 0, no club or topic; `oms_ticket` 75 39.99, 76 69.95 |
| B5 | Second and third activities (tonight 22:00 夜读票 29.99; 11-10 手作票 19.90) | PASS | 28 | activities 10, 11 |
| B6 | Fourth: tap 发活动 | PASS. Refused on entry: 「当前身份不能发布活动 / 已达到当前身份活动数量上限（3）」, then it goes back by itself (N7). | 29, 30 | precheck 403 |
| B7 | 下架 activity 11, then 删除 | PASS. 下架 still counts toward the limit; 删除 frees the slot. **FAIL (N3):** the deleted card stays in the list, on the merchant home (「全部 3」) and in 我的项目 after reload. | 31–37, 51a, 106 | precheck 403 after 下架, 200 after delete; activity 11 `del_flag 2` |
| B8 | Ended activities don't count | PASS. With three live (9, 10, 12), activity 10 was time-shifted to have ended, and the entry opens. | 99 | precheck 403 → 200; `usage.events` 3 → 2 |
| C1 | Player 2: home 附近活动 → detail | PASS. The organiser is the shop. Covers don't load (HARNESS: OSS host). | 40, 40b–d | — |
| C2 | 单人票 → 立即报名 → checkout | PASS. The refund table is shown. 「-¥0」 (F18). | 41, 41b | — |
| C3 | 参与人信息 → new participant → save | PASS | 42–42f | — |
| C4 | Info-sharing consent | Tapping the row doesn't reach `toggleHostShare` in the rig, whose `<icon>` is not drawn (HARNESS). I called `toggleHostShare`. NOT VERIFIED on a phone. | 43g | — |
| C5 | 付款 (rig pay seam → real callback) | PASS. 「支付成功 · 票已放入票夹」 sheet, then the order. | 43h, 43i | reg 74: payment_status 2, payable 39.99, fee 0.0300. Split 38.79 + 1.20 = 39.99. |
| C6 | Order → 「开始游戏」 | **FAIL (N1).** The play page says 「活动或主题不存在」. | 44, 45, 45b | `GET /api/play/nodes?activityId=9` |
| C7 | 我的 → 票夹, flip to the QR | PASS. A tap doesn't flip in the rig, so I called `onCardTap` (HARNESS). 「开始游戏」 is the only button (N1). | 46, 47 | dyncode `v1.74.activity…`, ttl 300 s |
| D1 | Owner scans at the counter (scan seam fed the real code) | PASS. The sheet says only 「验票成功 · 核销成功」 (F16). | 50 | reg 74 verification_status 1, verifier 990005 |
| D2 | Merchant numbers after one sale and one scan | PASS. 财务 待结算 ¥38.79. 核销与退款 shows 1 笔, ¥38.79. 核销记录 1. 营销 报名 1 · 核销 1. | 51–54 | pending 38.79 |
| E1 | Player 3: 双人票 69.95 (more than 24 h ahead) → cancel | PASS. Confirm and order both show ¥69.95. | 60i, 62–62e | RF75 paid 69.95, refund 69.95. Reg 75 → 4/3. Split rows 67.85/2.10 voided. |
| E2 | Player 4: 夜读票 29.99 (inside 24 h). Warning before paying, then cancel. | PASS. Warning 「取消只退 50%」. Confirm and order show ¥14.99. | 61h0, 61i, 63–63e | RF76 paid 29.99, refund 14.99 (29.99 × 0.5 = 14.995, rounded down). Rows 29.09/0.90 voided. Kept 15.00. |
| E3 | Player 5: 夜读票, then a time shift so activity 10 started 30 min ago | PASS. No cancel button; HTTP 「场次已开始,不可退款」. Redeemed reg 74: 「已核销报名不可退款」. The pill says 「不可退款」 (F18). | 65, 65b | reg 77, 74 unchanged |
| E4 | Organiser cancel: player 7 buys 手作票 ¥25 on activity 12, the merchant long-presses → 取消活动 | PASS. The confirm says 「将给 1 位已付款玩家全额退款」. The player's order shows 退款 ¥25.00. | 106, 107, 109, 110 | RF78 25.00/25.00. Rows 24.25/0.75 voided. Activity 12 cancelled. `usage.events` 0. |
| E5 | Merchant numbers after refunds | PASS on totals. 财务 and 核销与退款 待结算 ¥82.43 = 38.79 + 29.09 + 14.55 (kept from the 50% order). **FAIL (N4) on rows.** | 70a–f | — |
| F1 | Settlement: activities 9 and 10 time-shifted to end 2026-10-27; `cms_setting` 1 fixture; admin runs job 145 | PASS | — | 「activityId=9 总销售额=39.99 商户=38.79 平台=1.20」; 「activityId=10 总销售额=44.99 商户=43.64 平台=1.35」. Balance 990005 82.43; 9001 2.55. is_settlement 1. |
| F2 | Merchant screens after settlement | PASS on totals: home 累计收入 ¥82.43, 财务 可提现 ¥82.43, 待结算 ¥0.00, 核销与退款 已入账 ¥82.43, 结算 ¥82.43. N4 on rows. | 71a–f | — |
| F3 | 提现 | NOT VERIFIED. 「联系平台客服提现 … 线下处理」, so there is no in-app payout. | 72, 72b | — |
| G1 | Owner: 我的 → 经营团队 → 邀请员工 → 核销员 | PASS. The 「请在本页分享」 caption is gone. | 80–84 | invite PENDING |
| G2 | Owner opens their own invite | PASS on wording (Chinese). 「接受」 is still offered (F21). | 85, 100, 101 | — |
| G3 | walk-player-6 opens the link → 接受邀请 | PASS. N10 caption. | 87, 88 | operator ACTIVE MERCHANT_CHECKIN, invite ACCEPTED |
| G4 | Clerk's app | PASS. 我的 shows 「梧桐里书房 · 核销员 · 查看」. The workbench shows only 扫码 and 更多 → 核销记录. | 89, 90, 92, 93 | — |
| G5 | Clerk scans reg 77 from 我的 → 核销 | PASS in the DB, nothing on screen (F20). `verifier_id` is the owner (N8). | 91, 91b | reg 77 verified, verifier 990005 |
| G6 | Clerk scope over HTTP | PASS. finance/overview, operators/list, operators/invite, aftercare/list and publish_precheck → 403. update_publish_status and cancel_preview refused. finance/redemptions → rows with every amount null. | — | activity 9 unchanged |
| G7 | Owner removes the clerk | PASS. The clerk's merchant calls → 403 at once. I did not retry a scan with a valid code because no unredeemed ticket was left. | 94–97 | operator REVOKED |

## 3. Findings

### BLOCKER

None found in this pass.

### HIGH

**N1. A no-game activity's ticket leads to 「活动或主题不存在」.**
- Repro: buy any merchant activity published without a game. Tap 「开始游戏」 on the order page or in 票夹.
- The play page asks `GET /api/play/nodes?activityId=9` and shows 「活动或主题不存在 · 重新加载 · 返回活动详情」.
- On 票夹 it is the only button, and the QR is behind a card flip, so a player may think the ticket is broken.
- Cause: `scene-member-order-detail/index.js:182` `resolvePrimaryCta` and 票夹 offer 「开始游戏」 for any paid, playable-state ticket, with no check for a game (`template_id 0`).
- The owner made the game optional (ROUND10), so these tickets are now normal.
- Shots 43i, 44, 45, 46.

**N2. A price with jiao or fen can't be typed on 发布活动.**
- Repro: 票面价格, type 39.99. The field shows 3999 and the form holds 3999.
- Cause: `pages/publish/activity/index.wxml:174` is `type="number"`, and `onTicketChange` (`index.js:284`) writes `Number(value)` back on every keystroke. So 「39.」 becomes 39 and the dot is lost.
- Also, WeChat's `number` keyboard has no decimal point; `digit` does, as `pages/topic/pricing` and `pages/coop/invite` use.
- The rig's input is not WeChat's, so the exact keystroke behaviour is NOT VERIFIED on a phone. The code path is the same either way.
- Shot 22-step3-filled.

**N3. A deleted activity stays on the merchant home and in 我的项目.**
- Repro: 我的项目 → long-press an unsold activity → 下架 → long-press → 删除 → 删除场次.
- DB: `del_flag 2`, and the quota frees the slot. But the card stays as 「已下架」 in 我的项目, after reload too, and on the merchant home, which counts it in 「全部 N」.
- 营销 「活动」 does leave it out, so the merchant sees 全部 2 on home and 活动 3 on 营销 (3 = 9, 10, 12).
- `/api/project/my` doesn't filter `del_flag`.
- Shots 37, 51a, 71a, 71f, 106.

**N4. 核销与退款 rows can't be added up to the header, and balance credits read 「平台已打款」.**
After settlement the header is 已入账 ¥82.43. The rows are:
- +¥29.09 夜读票
- +¥38.79 单人票
- −¥14.99 退款
- −¥69.95 退款

They sum to −¥17.06. The ¥14.55 the merchant kept from the 50% order has no row. The refund rows show the player's full refund, though neither ever came out of the merchant's money.

Two status labels are also wrong:
- Rows read 「平台已打款」 while 财务 says the money is 可提现 in the in-app balance, and 提现 is offline through customer service.
- Before settlement the rows read 待结算 and still don't add up (+38.79 −14.99 −69.95 against 待结算 ¥82.43).

Shots 70c, 71c, 71b.

### LOW

- **N5.** The location hint 「用于地图与到场导航，拒绝不影响填写」 is under 活动地点 on step 1. It explains a permission, so it may be in the owner's keep list; if not, it's a caption. Shot 22-step1-filled.
- **N6.** After 发布, the merchant lands on 我的项目 on the 主题 tab, which says 「还没有主题 · 去发布」. The new activity is one tab away. Open on the 活动 tab. Shots 27, 28.
- **N7.** At the limit, the heading reads 「当前身份不能发布活动」, which blames the identity. The page also goes back by itself after about a second, with no word on what frees a slot (ending, cancelling or deleting one). Shot 29.
- **N8.** A clerk's scan records `cms_registration.verifier_id` = the owner (990005), not the clerk (990010). I found no other row that names who scanned. This is fine if the shop-as-actor rule is intended, but then the shop can't tell which staff member scanned.
- **N9.** Errors on 发布活动 are still pink blocks with an icon, such as 「请上传活动封面」, not red text under the field. Shot 22-step3.
- **N10.** On staff join:
  - The success line 「已加入经营团队」 is in red, the error colour (f1 noted it as old styling).
  - The caption 「岗位调整或离职由店主操作，立即生效。」 sits under the role.
  - 「详情」 floats alone in the shop card.
  - Shot 88.
- **N11.** The apply intro title 「把你的店 放进玩家的路线里」 touches the 开始入驻 button, with an empty upper half. Shot 03.
- **N12.** On the owner's team list the clerk is 「城瘾用户」, so the owner can't tell staff apart (same root as F21's nickname rows). The remove dialog also says the same thing twice: 「移除后对方的商家权限立即失效」 and 「对方立即失去这个店铺的全部经营权限」. Shots 94, 96.
- **N13.** The 财务 rows read 「活动分润 - 梧桐里今晚夜读」 with a second label 「活动分润」 under it. Shot 71b.
- **N14.** The cancel confirm restates the whole rule after the amount, in ASCII 「;」 and 「,」. It adds 「本单如有现金退款或积分返还，进度以订单查询结果为准」. After cancelling, the toast adds 「本单未使用积分」. Shots 62b, 62c.
- Still from first pass: F5, F8, F15, F16, F18 (parts), F20, F21 (parts), F23 (pills). Details are in the table above.

### Taglines, helper lines and secondary labels still on this journey

| Where | Text | Layout change |
|---|---|---|
| Apply steps 1–3 | 「标 * 的是必填」 | The red asterisk is enough. |
| 发布活动 step 1 | 「用于地图与到场导航，拒绝不影响填写」 | Put it in the location permission pre-prompt, which already explains (N5). |
| 发布活动 step 2, empty template sheet | 「先创建节点玩法，再回来选择。」 | Empty title plus the 创建节点玩法 button. |
| template-intro | 「把你玩过的一段路 / 做成别人也能走的玩法」 | Title only. |
| 我的项目 | 「长按卡片管理」 | Put a ··· on the card. |
| 结算 view | 「授权状态待确认，可点击申请」 | A row with a status chip. |
| Staff joined | 「岗位调整或离职由店主操作，立即生效。」 | Drop it. |
| Cancel confirm | 「本单如有现金退款或积分返还，进度以订单查询结果为准」 | Drop it. The order's refund row shows progress. |
| 财务 rows | second 「活动分润」 label | The row title already says it. |

Not counted, as consent, legal or consequence text: the identity consent, 「同意将姓名、手机号提供给主办方，用于报名核验」, the 「接收「梧桐里书房」的活动消息」 opt-in, the location pre-prompt, the 50% warning, and the delete, remove and cancel consequences.

### HARNESS (rig gaps, not product bugs)

- Image upload calls WeChat `img_sec_check` → 503 (licence and cover).
- `chooseLocation` returns 「模拟位置 / 上海市」.
- The disabled location `<input>` swallows taps. Date wheels aren't drawn. The 票夹 card doesn't flip on a mouse tap. The checkout consent row doesn't toggle on a tap (its `<icon>` isn't drawn).
- With no `CHENGYIN_OSS_*`, `/api/project/my` fails with 「对象存储配置缺失」. I added dummy names through a local `app.py` change, as first pass did. Covers then load from `oss-rig.invalid` and fail.
- The rig seed lacks `settlement_rule` (production has it; `migration_20261103_settlement_rule_three_percent.sql` asserts it), `cms_setting` 1, and `sys_category` type 2 (see L2).
- MySQL `NOW()` is UTC.
- The WeChat refund gateway is a stub, so refunds stay at payout_status 6 and the player sees 「正在退款」.

### Rig fixtures (throwaway DB only)

- licence set on the page;
- `app_media_asset` 1 APPROVED as the cover;
- `sys_category` 4101 and 4102 (type 2);
- `settlement_rule` activity and topic at 3%;
- `cms_setting` 1 with platform member 9001;
- whole-string prices through `onTicketChange` (N2);
- `toggleHostShare`, `onCardTap`, `choosePoiForNode` and `onCardLong` called on the page where the rig can't tap;
- time shifts of activities 9 and 10 and their tickets;
- merchant approval and the settlement run through the admin API.

`payment_status` was never written directly.

### Leads, not run down

- **L1.** The quota (`selectActivityQuotaUsedByMemberId`), settlement eligibility and several timestamps compare against SQL `NOW()`. The JDBC `serverTimezone=GMT+8` doesn't change that. If production MySQL's `time_zone` is not +08:00, an ended activity keeps its slot for 8 hours and times show 8 hours off. Read production's `@@time_zone` before launch.
- **L2.** No repo migration seeds `sys_category` type 2, and 活动类型 is required. If production has no type-2 rows, no merchant can publish. Read production's rows before launch.
- **L3.** When `/api/project/my` failed (before the OSS names were set), 我的项目 was blank with `projectState 'error'`, and no 「项目加载失败」 appeared. I didn't reproduce it after the rig fix.

## 4. Not covered

- **Real phone:** the native pay sheet, camera scan, the WeChat share card for the invite, wheels, keyboard (N2), fonts and safe areas.
- **Admin web UI:** approval and settlement were driven through its API.
- **Payout:** offline through customer service. **Refund money reaching the player** goes through a stub gateway, and the refund reconcile task was not run.
- **Partly-redeemed group tickets, points deduction and coupons** at checkout.
- **Merchant 退款售后 (aftercare) disputes.**
- **A refund after settlement** (`createSettlementAdjustment` rounding, an f1 lead). The rules refuse refunds after start, so I found no screen path to it.
- **Staff roles 店长, 运营 and 财务.** The 运营 invite was created but not accepted.
- **The half-fen settlement case** in the rig (the f1 unit test covers it).
- **Leads from earlier rounds** outside this journey: the `scene-*` async loads on settings and earnings opened without blanks; deep links were not tested.

STATUS: COMPLETE
