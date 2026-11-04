# j1-merchant-sale: pre-launch check, first pass

- Lane: j1-merchant-sale. Branch `claude/check-1104-j1-merchant-sale`, cut from the commit under test.
- Commit tested: `8b78f5ca3c06f486631e827827e973cbb47e3094`, which is on `integ/launch-test-20261103`.
- Walked 2026-11-04, 12:20–12:55 Asia/Shanghai. Fictional data only.

**This is the cloud rig, not a phone.** It runs:
- the real jar on real MySQL 8.0.45 and Redis 7.4;
- the real mini-program code inside headless Chrome, at 390×844 and 2x;
- branch `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff`, and `up.sh` pinned to the commit above;
- the Docker mirror `mirror.gcr.io` and `LANG=C.UTF-8`.

Shots are in `shots/`: 780×1688 JPEG, all under 200 KB. I looked at every shot quoted below.

## Summary

The sale cannot start as shipped. Neither the merchant side nor the clerk side can finish without a rig fixture.

1. **BLOCKER: a merchant cannot publish any activity.** After all three steps of 营销 → 发活动, 「发布」 answers 「当前身份不可创建活动」.
2. **BLOCKER: settlement pays out 1 fen more than was collected.** After the sale I patched the permission row as a rig fixture and ran the rest of the journey. Settlement then paid ¥94.58 to the merchant and ¥2.93 to the platform from ¥97.50 of sales.
3. **BLOCKER: a new clerk cannot accept a staff invite in the app.** The client blocks the request before it is sent and shows 「网络异常，请重试」.

Steps that worked:
- payment through the real callback;
- the ticket and its QR in 票夹;
- scanning at the counter;
- all three refund tiers in the database (100% → ¥69.90, 50% → ¥19.50, after start → refused);
- the 3% split on each order;
- clerk scope on the backend, and removing a clerk.

Where the screens fall short:
- The merchant's numbers disagree across screens. 核销与退款 and 核销记录 never show the sale.
- The player never sees the refund amount.
- The organiser is shown as a masked nickname (「城*」/「城瘾用户」), never as the shop.

## 1. Steps

Read-backs are database reads from the rig (`cy_e2e_51`).

**Rig clock:** MySQL's `NOW()` runs in UTC. Rows written with SQL `NOW()` therefore show times 8 hours behind (04:4x) beside the Shanghai times the app writes (12:4x). This is a rig artefact.

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| A1 | Player home → 「成为商家」 banner | PASS | 01, 02 | — |
| A2 | Apply step 1: shop name, 行业类型 (书店 + 咖啡茶饮), phone | PASS | 03–05 | — |
| A3 | Apply step 2: address, hours, intro | PASS. The address is the rig's fixed 「上海市」. | 06–08 | — |
| A4 | Apply step 3: business licence upload | NOT VERIFIED (HARNESS). The upload calls WeChat `img_sec_check`, which the rig cannot reach (`503`, 「安全检查服务异常，请稍后重试」). The licence was set on the page as a rig fixture. | 09–12 | — |
| A5 | Apply step 4: name, ID number and consent, then 提交申请 | PASS | 13–16 | `mms_merchant` 8006 status 0. `ums_member_identity` is encrypted, source `merchant_apply`. |
| A6 | Platform approves | PASS, through the admin API `PUT /business/merchant {id:8006,status:1}`. The admin web UI was not walked. | — | 8006: status 1, account_status 1. Member 990005 role `merchant`. |
| A7 | Reopen the app → merchant workbench | PASS. No approval message arrived. | 17 | — |
| A8 | 更多 → 品牌资料 → 门店信息 → fix the address | PASS | 18–22 | address 上海市徐汇区复兴西路62号, 31.2085/121.4426 |
| B1 | 营销 → 发活动, step 1 | PASS, with rig workarounds. The rig's disabled `<input>` swallows the tap on 活动地点 and the date wheels are not drawn, so I called the page's own handlers. The rig seed had no `sys_category` type 2, so two fixture rows were added. | 23–28 | — |
| B2 | Step 2: 选择模板 is required. The new shop has none, so 创建节点玩法 → template-intro → editor → 发布玩法. | FAIL (F2). Publishing the game relaunches into 我的节点玩法 and the activity draft is lost. The template stays 「个人审核中」 and cannot be picked until the platform approves it. I approved it through `POST /business/audit/review`. | 29–42 | `cms_member_template` 6005: status 0 → 1 |
| B3 | Activity cover upload | NOT VERIFIED (HARNESS, same upload seam). The cover is fixture `app_media_asset` 1 (APPROVED). | 43, 44 | — |
| B4 | Step 3: two paid tickets (单人票 ¥39 ×20, 双人票 ¥69.9 ×10), then 发布 | **FAIL, BLOCKER F1**: 「当前身份不可创建活动」 | 45–47 | Nothing written |
| B5 | Same, after the rig fixture `user_permission(merchant)` can_create_event=1, max_events=10 | PASS | 48, 49 | `cms_activity` 9, status 1, member 990005, no topic |
| C1 | Player finds it: home → 附近活动 card → detail | PASS. The organiser is shown as 「城* · 发起人 · 主理人」 (F14). | 50–53 | — |
| C2 | Pick 单人票 → 立即报名 → checkout and quote | PASS. The 100/50/0 refund table is shown. | 54–58 | quote `payAmount 39.00`, `refundPercentNow 100`, `refundDeadline 11-06 19:00` |
| C3 | Pay | First try FAIL (HARNESS: the rig seed has no `settlement_rule`, so create refused with 「支付费率快照缺失或非法,暂不可支付」). Added the 3% rows the 11-03 migration expects, then PASS. | 59, 60 | reg 74: payment_status 2, payable 39.00, fee 0.0300, txn RIG-TXN-74. `settlement_detail`: merchant 37.83 and platform 1.17, both pending. |
| C4 | Order detail | PASS. 「主办方：城瘾用户」 (F14). | 60–62 | — |
| C5 | 我的 → 票夹: ticket card, flip to the QR | PASS. A tap does not flip the card in the rig, so I called the card's own `onCardTap` (HARNESS). | 63–65 | `/api/verify/dyncode/issue` → `v1.74.activity…`, ttl 300 s |
| D1 | Merchant 扫码 (scan seam fed the real dynamic code) | PASS. The sheet reads 「验票成功 · 核销成功」. | 66 | reg 74: verification_status 1, verifier 990005 |
| D2 | Merchant numbers after one sale and one redemption | **FAIL (F13)**. Home 累计收入 ¥0.00. 财务: ¥0.00 and 「这个月还没有收入」, but 待结算 ¥37.83. 核销与退款: 「本月核销 0 笔 · 待结算 ¥0.00 · 还没有记录」. 核销记录: 已核销 0. | 67–79 | DB pending 37.83 |
| E1 | Refund, more than 24 h before start: player 3 buys 双人票 ¥69.9 and cancels | DB PASS. Screen FAIL (F7): no amount is shown, only 「正在退款」. | 80–82 | reg 75: 4/3. `refund_application` RF75 refund 69.90, status 1. Settlement rows for reg 75 voided. |
| E2 | Refund, within 24 h (fixture time shift of activity 9 and tickets 75/76 to start in 12 h). Player 4 buys 单人票 ¥39. | Checkout warns 「取消只退 50%」: PASS. Cancel → DB PASS. Screen FAIL (F7): the order never shows ¥19.50. | 83–90 | reg 76: refund_amount 19.50. Both settlement rows for reg 76 voided (see F12). |
| E3 | Refund after start (shifted to start 30 min ago). Player 5's ticket. | PASS. No cancel button. HTTP `cancel-refund` → 「场次已开始,不可退款」. A redeemed ticket (reg 74) → 「已核销报名不可退款」. | 91, 92 | reg 77 and reg 74 stay 2/2 |
| E4 | Merchant numbers after the refunds | Partly. 核销与退款 lists both refunds, ¥69.90 and ¥19.50, which match the DB. 财务 待结算 ¥75.66 leaves out the ¥18.92 the merchant keeps from the 50% order. | 93, 97 | `settlement_detail` pending: 74 and 77 only |
| F1 | Settlement: fixture time shift to end 8 days ago, missing `cms_setting` row 1 added, then admin `PUT /monitor/job/run` for job 147 `settlementTask.run()` | Ran. **BLOCKER F3**: 94.58 + 2.93 = 97.51 > 97.50. | 94–98 | The log line 「总销售额=97.50, 商户佣金=94.58, 平台佣金=2.93」. `ums_member_balance_detail` 39 (990005 +94.58) and 40 (9001 +2.93). Activity 9 is_settlement 1. |
| F2 | Merchant screens after settlement | 财务 ¥94.58, labelled 「其他收入」. Home 累计收入 ¥94.58. 核销与退款 still shows 「本月核销 0 笔 · 已入账 ¥0.00」. 结算 view: 「本月个人账户净入账 ¥0.00」. | 94–98 | — |
| F3 | Payout: 提现 | NOT VERIFIED. The app shows 「联系平台客服提现 … 线下处理」, so there is no in-app payout to follow. | 99 | — |
| G1 | Owner: 我的 → 经营团队 → 邀请员工 → 核销员 | PASS | 100–103 | `merchant_operator_invite` 1, PENDING |
| G2 | Owner opens their own invite link | The page offers 「接受」, then answers 「商家 Owner 无需接受员工邀请」 (F21). | 104, 105 | — |
| G3 | New account (walk-player-6) opens the invite and taps 接受邀请 | **FAIL, BLOCKER F4**: 「网络异常，请重试」. The request is never sent. | 106, 107 | — |
| G4 | Clerk accepts over HTTP (fixture), then reopens the app | PASS. 我的 shows 梧桐里书房 · 核销. The workbench shows only 扫码 and 更多 → 核销记录. | 109, 113–115 | operator ACTIVE, MERCHANT_CHECKIN |
| G5 | Clerk scans reg 77 from 我的 → 核销 | PASS in the DB. On screen there is only a toast and no result sheet (F20). | 110–112 | reg 77 verified |
| G6 | Clerk scope over HTTP | PASS | — | finance/overview, operators/list and aftercare/list → 403. activity/publish refused. |
| G7 | Owner removes the clerk; the clerk retries a scan | PASS. 403 at once. | 117–119 | — |

## 2. Findings

### BLOCKER

**F1. A merchant cannot publish an activity, so the paid merchant sale has no start.**
- Repro: approved merchant → 营销 → 发活动 → fill all three steps → 发布. The reply is 「活动尚未发布 · 当前身份不可创建活动」.
- Fixture shop 9002 gets the same answer over HTTP.
- Cause:
  - `QuotaServiceImpl.java:71` checks `user_permission` for role=merchant.
  - That row has `can_create_event=0, max_events=0`, set by `chengyinhub-admin/sql/migration_p1_rbac_permission_defaults.sql:22`.
  - With `can_create_event=1`, `max_events=0` would still refuse (「已达到…上限（0）」).
- The client lets the shop in through `canManageProjects` (`pages/publish/activity/index.js` `onPublisherIdentity`). The refusal only shows after the whole form is filled.
- Production's row must be read before launch. If it matches the repo migrations, every merchant hits this.
- Shot 47.

**F3. Activity settlement pays out more than was collected.**
- `CmsActivityServiceImpl.java:430-435` rounds the platform share and the merchant share separately with HALF_UP.
- Real run: sales ¥97.50 (39 + 39 + 19.50 kept from the 50% refund). Merchant ¥94.58 + platform ¥2.93 = ¥97.51.
- This happens whenever sales × 0.03 ends in half a fen. The merchant share should be sales minus the platform share.
- Read-back: `ums_member_balance_detail` 39 and 40. Shots 94 and 96.

**F4. A new staff member cannot accept an invite in the app.**
- `utils/transport/role-view-filter.js:7` lists `/^\/api\/merchant\/operators(?:\/|\?|$)/` as merchant-only. In player view the request client drops `/api/merchant/operators/invite/accept` without sending it (`request-client.js:173`).
- The page then shows 「网络异常，请重试」 and the toast 「邀请处理失败」 (`pages/merchant/team/index.js:789-791`).
- Every invitee is a player until they accept, so no clerk can ever join.
- The backend accept works: HTTP 200, operator ACTIVE.
- Shot 107.

### HIGH

**F2. A new merchant must build and get approval for a game before any activity.**
- 选择模板 is required (`pages/publish/activity/index.js:431`).
- 创建节点玩法 → 发布玩法 relaunches into 我的节点玩法. The activity draft is lost and back does not return.
- The template stays 「个人审核中」 and is hidden from the picker (`ApiTemplateController.java` `my-list` with `is_quote` → status=1) until the platform approves it.
- The 2026-11-01 merchant walk (A10) reported the same thing.
- Shots 30, 38, 40, 41.

**F13. The merchant's numbers disagree after one sale.** After one ¥39 sale and its redemption:
- Home: 累计收入 ¥0.00.
- 财务: ¥0.00 and 「这个月还没有收入」, with 待结算 ¥37.83 lower down.
- 核销与退款: 「本月核销 0 笔 · 待结算 ¥0.00 · 已入账 ¥0.00 · 还没有记录」.
- 核销记录: 「已核销 0」, for the owner and for the clerk.
- 我的消息: 「完成一笔到店核销」.

`/api/merchant/finance/redemptions` (`ApiMerchantController.java:1936`) returns `total 0` for activity ticket scans.

After settlement:
- 财务 shows ¥94.58;
- 核销与退款 still shows 「本月核销 0 笔 · 已入账 ¥0.00」;
- 结算 shows 「本月个人账户净入账 ¥0.00」.

Shots 67–69, 76, 77, 96–98, 116.

**F12. The merchant's pending amount leaves out what it keeps from a 50% refund.**
- A 50% refund voids the whole order's pending rows (merchant ¥37.83, platform ¥1.17) through `SettlementDetailMapper.xml` `voidPendingBySource`. 待结算 then counts nothing for that order, and shows ¥75.66 for the two other orders.
- Settlement then pays the retained ¥19.50 (`selectRetainedSalesAfterPartialRefundByActivityId`).
- So the merchant was shown ¥75.66 pending and received ¥94.58.
- Shots 93 and 94.

**F7. The player never sees the refund amount.**
- The cancel confirm states only the rules: 「集合前 24 小时内取消,退实付的 50%」, with no ¥19.50.
- After cancelling, the order shows 「正在退款 · 退款已批准，正在提交原路退款」 and 合计 ¥39, with no refund amount anywhere.
- The 100% order is the same (¥69.90 is not shown).
- The database and the merchant ledger both have the right amounts.
- Shots 81, 82, 88–90.

**F14. The organiser is a masked nickname, never the shop.**
- Activity detail: 「主理人 · 城* · 发起人 · 主理人」.
- Order: 「主办方：城瘾用户」.
- Publish form, 合作者: 「城瘾用户 · 主理人」.
- The merchant's own event is tagged 「俱乐部活动」 on the merchant home, and `/api/project/my` returns `projectType: club_activity`.
- A player cannot tell this is 梧桐里书房's event. 「主理人」 is the club word.
- Shots 45, 52, 62, 67.

**F22. The staff invite page does not say which shop or which role.**
- It shows only 「加入经营团队 · 接受后会获得店主分配的固定岗位…」 and 「接受邀请」.
- Shot 106.

### LOW

- **F5.** The 行业类型 sheet shows 「咖啡茶饮」. Saved and shown, it is 「咖啡」, which is `sys_category` 4001, a type-1 theme category (`migration_20261102_merchant_industry_categories.sql` skips 咖啡茶饮 when 咖啡 exists). 门店信息 also has both 经营类目 「待填写」 and 行业类型. Shots 05, 20.
- **F6.** 发布活动 step 3:
  - 票面价格 is prefilled 「0」 and has no required mark.
  - The fixed 「发布前摘要」 box takes a third of the screen and covers 「添加票单」.
  - The summary shows raw seconds: 「2026-11-07 19:00:00」.
  - Page margins are 12.5, where other merchant pages use 16.6.
  - Shots 45, 46.
- **F8.** No message tells the merchant they were approved, and no next step is offered. The only later system message is for the game template. Shots 17, 75.
- **F9.** 活动地点 has the placeholder 「请输入活动地点」, but it is a map picker. Its aria-label reads 「为该节点选点」. Shot 24.
- **F10.** On the apply success page, 「返回首页」 is a narrow button pinned to the left. Shot 16.
- **F11.** The 发布玩法 editor:
  - still requires 完成奖励 (「请至少配置一种奖励」);
  - shows errors as pink blocks with an icon, not red text under the field;
  - names the template after its game type (「选项问答」), and shows 「时长 0分钟」 and 「— · 0 分钟」 on the activity page;
  - opens template-intro in dark on the merchant path.
  - Shots 31, 36–38, 52.
- **F15.**
  - The activity detail opened from the merchant home is a dark sheet on the light page.
  - The floating tab bar covers the bottom of the project card, and a tap on the card title opened the 模板 tab.
  - Shots 79, 67.
- **F16.** The scan result says only 「验票成功 / 核销成功」: no ticket type, no buyer, no event. Shot 66.
- **F17.** 票夹 shows 「3 天内过期」 for a ticket whose event is in 3 days. It reads as an expiry. Shot 65.
- **F18.** Money and labels on the player side:
  - Amounts lack 2 decimals: 「¥39」, 「¥69.9」, 「-¥0」 积分抵扣.
  - The 1 a.m. start reads 「上午12:46」.
  - The ticket glyph in the order header runs under the status bar.
  - 「未开始」 appears twice on the order, and after start 「不可退款」 appears three times.
  - The refund rule text uses ASCII 「;」 and 「,」.
  - Shots 53, 55, 60, 62, 84, 91.
- **F19.** With no fee rate configured, the quote passes but create refuses with the raw 「支付费率快照缺失或非法,暂不可支付」. The quote should run the same fee gate as create. This is config-dependent. Shot 59.
- **F20.** A clerk scanning from 我的 → 核销 gets only a passing toast (「该订单已核销」), not the result sheet the workbench uses. Shot 112.
- **F21.**
  - The owner opening their own invite sees 「接受」, then 「商家 Owner 无需接受员工邀请」, with English in the message.
  - 财务 labels activity income 「其他收入」.
  - Ledger refund rows read 「退款 · 退款处理中」, in red with no minus sign, and show the nickname instead of the sign-up name (ROUND4 M-27).
  - Shots 105, 94, 97.
- **F23.** The 即将上线 card on the player home has three empty grey pills and 「还剩3d」, with an English unit. Shot 51.
- **F24.** The 行业类型 chips in the sheet are cut off at the right edge (亲子社交, 宠物友好, 户外出行). Shot 04.

### Taglines, helper lines and secondary labels

Owner's rule: each of these is a finding. Next to each is a layout change that would make it unnecessary.

| Where | Text | Layout change |
|---|---|---|
| Player home banners | 「把空间、服务和好内容带给更多玩家」 and the two others | The banner title is enough. Drop the subline. |
| 商家入驻 intro | eyebrow 「商家入驻」, bullets 「基础信息 · 经营信息 · 资质 · 确认」 and 「提交后可随时回来看进度与驳回原因」 | Title plus 开始入驻. The 1/4 progress bar on the next screen says the rest. |
| Apply steps 1–3 | the eyebrow repeats the progress label; 「标 * 的是必填」 | The red asterisk is enough. |
| Industry sheet | 「可多选，最多 5 个，第一个作为主行业」 | Show the 已选 n/5 counter, and mark the first chip as 主. |
| Apply step 3 | 「最多 6 张」 | Hide the + tile at 6. |
| Apply step 4 | 「用于收款实名核验」 (the consent line above it is legal text, keep it) | Drop it. The consent line already says why. |
| 品牌中心 rows | 「名称 · 地址 · 营业时间」, 「故事 · 特色」, 「形象 · 语音」, 「承接 · 主推 · 权益」 (「0 张照片」 is data) | Row titles alone, or the real values. |
| 营销 empty coupons | 「发一张到店券，把看过的人带进门」 | Empty title plus a create button. |
| 发布活动 | 「用于地图与到场导航，拒绝不影响填写」, 「合作者可编辑内容，不参与结算」, amber 「默认公开：发布后会在活动列表展示…」 | Drop them. Put visibility in the 发布 confirm if it matters. |
| template-intro | 「把你玩过的一段路 / 做成别人也能走的玩法」 | Title only. |
| 玩法 editor | 「2-4 个选项里选一个」, 「九宫格 在主题里配置」 | Drop them. |
| 我的项目 | 「长按卡片管理」 | Put a ··· on the card. |
| Activity detail | 「首次真实评价的积分以平台当前规则为准」 | Drop it. |
| Order | 「票夹已生成，可继续探索或现场核验」, 「退款按本单实际支付原路处理，有疑问可联系客服。」 | Drop them. 联系客服 is already a button. |
| 经营团队 | 「请在本页分享」 after creating an invite | The 立即分享 button is enough. |
| Invite landing | 「接受后会获得店主分配的固定岗位；每个账号同一时间只能加入一个商家团队。」 | Replace it with the shop name and role (F22). |
| 结算 view | 「授权状态待确认，可点击申请」 | Make it a row with a status chip. |

Not counted: consent and legal lines (identity consent, 「同意将姓名、手机号提供给主办方，用于报名核验」, the location-use dialog) and the cancel and 50% confirms.

### HARNESS (rig gaps, not product bugs)

- Image upload calls WeChat `img_sec_check` and fails with 503 (licence and cover).
- `chooseLocation` returns 「上海市」.
- Native date wheels are not drawn. A disabled `<input>` swallows taps. The 票夹 card does not flip on a mouse tap.
- `<icon>` (the consent circles) is not drawn.
- OSS signing needed dummy `CHENGYIN_OSS_*` names, passed through a local `app.py` patch. Covers load from `oss-rig.invalid` and fail.
- The rig seed lacks:
  - `sys_category` types 2 and 4;
  - `settlement_rule`;
  - `cms_setting` row 1.
- MySQL `NOW()` is UTC, so times like 04:46 appear in the ledger and order progress.
- The WeChat refund gateway is a stub, so refunds stay at `payout_status 6` (「结果未知,等待按确定性退款号查单」) and the player sees 「正在退款」, never 「退款成功」.

### Rig fixtures (throwaway DB only)

- licence set on the page;
- `user_permission(merchant)` changed from 0/0 to 1/10 (see F1);
- `app_media_asset` 1 APPROVED cover;
- `sys_category` 4002 and 4003 (type 2) and two type-4 rows;
- `settlement_rule` activity and topic at 3%;
- `cms_setting` 1 with platform member 9001;
- clerk accept over HTTP (see F4);
- time shifts of activity 9 and tickets 75/76 with `seed-walk.py shift-session` and SQL;
- platform approvals (merchant, template) and the settlement run through the admin API.

`payment_status` was never written directly.

## 3. Not covered

- **Real phone:** native pay sheet, camera scan, share card for the staff invite, wheels, keyboard, fonts, safe areas.
- **Admin web UI:** approvals and settlement were driven through its API.
- **Payout:** the app routes withdrawal to customer service offline, so the payout itself is NOT VERIFIED.
- **Refund money:** money reaching the player (gateway stub) and the refund reconcile task are NOT VERIFIED.
- **Merchant 退款售后 (aftercare):** I read the lists, which were empty. I made no manual refund dispute.
- **Other roles:** 店长, 运营 and 财务 were not walked. Only 核销员 and the owner were.
- **Coupons, CRM broadcast and merchant marketing numbers** were not followed past the screens shown.
- **Leads from earlier rounds:** none of them sits on this journey. 确认终价 belongs to theme pricing, not merchant activities, so I did not re-test the pink-block lead. The 13 async `scene-*` components (settings, earnings) all loaded without blank screens in the screens I opened (earnings, deep links not tested).

STATUS: PARTIAL - first pass. Three blockers (F1, F3, F4) stop the journey as shipped. Everything after them was walked on rig fixtures. Payout, the admin UI and real-phone behaviour are not verified.
