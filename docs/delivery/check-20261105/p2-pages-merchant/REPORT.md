# Round 8 check: p2-pages-merchant (merchant / coop pages): second pass

- Commit under test: `573c463b3b457b77544e63be4271a07d5d565e09` (on `integ/launch-test-20261103`). Branch `claude/check-1105-p2-pages-merchant`, cut from that commit. Only files under this folder were added.
- **This is the cloud rig, not a phone.** `claude/cloud-walk-20261101-rig` + the club lane's `harness-patches.diff`, `up.sh` pinned to the SHA above, `LANG=C.UTF-8`. Real jar, MySQL 8.0.45 and Redis 7.4 in Docker, the real mini-program code in headless Chrome at 390×844, shots at 2× (all under 200 KB). Docker Hub pulls worked, so the `mirror.gcr.io` mirror was not needed (setting it as a daemon mirror was refused by this session's sandbox).
- Rig-only changes (none in product code): `walk.mjs` at deviceScaleFactor 2; dummy `CHENGYIN_OSS_*` values so image-URL signing works offline (first-pass H-1).
- I looked at every shot I cite. Tools and per-step network logs are in `tools/` and `logs/`.

## Data and identities

All data is fictional. Fixtures go through the product's own endpoints unless marked.

| What | How | Read-back |
|---|---|---|
| Base chains | `chains.py finance`: platform → merchant_club → sale_close → player → finance, all PASS on topic 990014 (自由探索) | redemption 1: reg 74, 9009 「E2E 玩家二号」 at 8001 |
| Three more 自由探索 buyers | `tools/fx_join.py` (real `/api/club/join`) + `tools/fx_buy.py` (real quote/create, rig payment seam = `chain_player.store_trusted_payment_fact` + real callback). walk-player-1/2/3 = 测试玩家甲/乙/丙 | regs 75, 76, 77, 78, 79, all payment_status 2, ¥69 |
| Cancel | `tools/fx_cancel.py` (real `/api/registration/cancel-refund`) | reg 77 → 4; `refund_application` 1, ¥69, merchant_id **9008** (organiser) |
| Session 9 time shift | FIXTURE: `cms_activity` 9 moved to start 1 h ago, as the chain waits for its start | — |
| Player QR | `tools/fx_dyn.py` (real `/api/verify/dyncode/issue`, what 票夹 shows) | — |
| Coop invite to 9002 | `tools/fx_invite.py`: club lead 9003 → real `/api/coop/invite`. FIXTURE: `cms_topic.recruit_deadline` on 990013 (refused otherwise: 「招商写入已关闭：recruitDeadline 未配置」, same gap j2 hit) | invites 990025 (no terms) and 990026 (引流型) |
| Merchant's own paid activity | `tools/fx_activity.py`: real `/api/publisher/identity` + `/api/activity/publish` (form's map/date wheels don't run in the rig, j1 B1). Cover = approved media-asset fixture. HARNESS: rig seed has no `settlement_rule`, so I inserted the two 3 % rows the 2026-11-03 migration expects | activity 14, tickets ¥39; regs 80, 81, 82 |
| Settlement | FIXTURE time shift (activity 14 ended 8 days ago). HARNESS: rig seed has no `cms_setting` row 1 (job refused 「活动结算配置不存在」), added. Real job 145 `settlementTask` via admin `/monitor/job/run` | 「总销售额=78.00, 商户佣金=75.66, 平台佣金=2.34」; balance 9002 +75.66, 9001 +2.34 |

Identities: merchant owner 9002 (探店日样板咖啡, 8001); staff walk-player-1 (店长) and walk-player-2 (核销员); player 9004; guest.

## 0. Page list

Same rule and the same 37 pages as the first pass (27 under `pages/merchant`, 8 under `pages/coop`, plus `pages/topic/merchantinfo/merchantinfo` and `pages/topic/merchantapply/index`). app.json still registers 157 pages, and these 37 routes are unchanged at this commit.

| # | Page | How reached this pass | States seen | Shots |
|---|---|---|---|---|
| 1 | merchant/index | launch as owner, 店长, 核销员, guest; 扫码; 更多; fault injection | full, staff, error (F-5) | a01, e01–e04, e10, f07, f22, i01, l1-home, l2-home |
| 2 | merchant/apply | deep link | approved | a02 |
| 3 | merchant/ledger | deep link, tabs, rows | full, staff, guest, player | a03, b01, e11, f10, f30, l1/l2-ledger, m03, m04, k-guest-05 |
| 4 | merchant/ledger/order-detail | ledger 探店日 row; ledger activity row; deep link | full (no cash), **dead end** (N-1), missing-param, guest | b02, m07, a04, k-guest-07 |
| 5 | merchant/ledger/batch-detail | deep link without id | missing-param | a05 |
| 6 | merchant/aftercare | 更多 → 退款售后; tabs | empty, 处理中 with a refund | a06, m01, m02, f15 |
| 7 | merchant/predict | deep link | empty | a07 |
| 8 | merchant/aftercare/detail | ledger refund row | full | m05, m06 |
| 9 | merchant/profile | legacy link `?id=8001` | redirect to public home | a09 |
| 10 | merchant/marketing | tab; 优惠券 card | full | a10, e13, l1/l2-marketing, n01 |
| 11 | merchant/reviews | deep link without id | missing-param | a11 |
| 12 | merchant/marketing/ai-insight | deep link | full | a12, e14 |
| 13 | merchant/relation | deep link | empty | a13 |
| 14 | merchant/citynode | deep link | empty | a14 |
| 15 | merchant/citynode/create | deep link; 去配置玩法 | form → template editor | a15, h40 |
| 16 | merchant/decor | deep link, 店长, guest, player | full | a16, f13, k-guest-06 |
| 17 | merchant/decor/ai-npc | onboarding, 编辑, name, 性格 | saved | a17, h33–h37 |
| 18 | merchant/decor/coop-setting | 编辑 合作诉求 → 保存 | saved | a18, h13–h16 |
| 19 | merchant/decor/gallery | + → 裁剪 → 选取 | upload refused (HARNESS) | a19, h30–h32 |
| 20 | merchant/decor/perks | 添加权益 → 保存 | saved | a20, h01–h04 |
| 21 | merchant/coop-center | deep link, 看详情 | full | a21, j01–j03 |
| 22 | merchant/customer | home 客户; 筛选; 管理 (批量标签, 导出) | full with 自由探索 buyers, staff, guest, player | a22, b10, b13, d01, d03, e15, l1/l2-customer, n02, n03, n06 |
| 23 | merchant/game-node | deep link without ids | redirect to home | a23 |
| 24 | merchant/customer/detail | customer row; 编辑 note | full, missing-param, player | b11, d02, n04, n05, a24, k-player-01 |
| 25 | merchant/verify-records | deep link, staff | full | a25, e12, f09, f28, l1/l2 |
| 26 | merchant/crm-campaigns | deep link | empty | a26 |
| 27 | merchant/team | invite 店长 and 核销员, accept as invitee, remove | full | a27, f01–f05, f12, f20, f21, f31–f33 |
| 28 | coop/list | 收到的, after reject and accept | full | a28, g03, g20, g26 |
| 29 | coop/invite-detail | list row; deep link; 拒绝; 接受合作; 合作操作 | pending, rejected, accepted | a29, g04–g31 |
| 30 | coop/invite | deep link | empty (no own theme) | a30 |
| 31 | coop/nearby | deep link | empty | a31 |
| 32 | coop/finance | deep link as owner, 店长, player, guest; after settlement | empty, contradictory (N-4) | a32, e16, f14, l2-coop-finance, k-* |
| 33 | coop/settlement-detail | deep link without id | missing-param | a33 |
| 34 | coop/withdraw | legacy link | static | a34 |
| 35 | coop/withdraw/records | legacy link → 提现记录 | redirect | a35 |
| 36 | topic/merchantapply | deep link; `?topicId=990013`; empty submit | form, errors | a36, h45–h50 |
| 37 | topic/merchantinfo | home card (owner, 店长); 二维码; 节点NPC; 客户; after coop accept | full | a37, b07–b09, f19, g27, j04–j06 |

Pages opened from merchant screens that belong to p1: `subpackageMember/coupon/coupon` and `couponInfo` (h20–h26, n01), `subpackageA/pages/assetcenter/earnings` (l1/l2-earnings, m09), `subpackageA/pages/myproject` (b04), `subpackageB/pages/im/list` (g02) and `im/chat` (g28).

## 1. First-pass findings: are they really gone?

| ID | Status at 573c463b | Evidence |
|---|---|---|
| F-1 coupon page blank | **Gone.** List loads; 发布优惠券 creates a coupon end to end. The tagline 「发一张到店券，把看过的人带进门」 is still there (see F-10). | b06, h22–h26; `sms_coupon` 15 |
| F-2 营销 核销 0 | **Gone.** 营销 counts today: 核销 1 → 3 → 5, same as 核销记录. | a10, e13, l2-marketing |
| F-3 全部 1 vs empty 我的项目 | **Gone.** 我的项目 lists the 承接 card 「我承接的」. | b04 |
| F-4 客户 shows 0 | **Gone (owner decision 2026-11-04).** 自由探索 buyers are now in 客户. New problems with that data: N-2, N-6. | a22, d01 |
| **F-5** orphan 「项目」 heading on load failure | **Gone, after the revert and restore.** With `/api/merchant/chapter-application/mine` forced to 500 (`--fail-path`, chain_acceptance's own fault filter), the whole 项目 block, heading included, is not drawn; 我的消息 moves up. Code at `pages/merchant/index/index.wxml:140-141` matches `6e6094641`/`3346026e2`; no later commit touches it. | i01, i02 |
| **F-7** 核销详情 「我的收入」 placeholder | **Gone, after the revert and restore.** A no-cash redemption shows title, 「权益已履约 · 无现金结算」 tag, 核销门店 and 核销时间, with no 我的收入 line and no dash. Code at `pages/merchant/ledger/order-detail/index.js:92-93` and `.wxml:18-20` matches `450637020`/`3346026e2`. | b02 |
| F-6 tab bar over content | **Still there.** Home: the project card's 「第1章 咖啡 · 今天 10:15」 is under the tab icons. 营销: the 合作机会 photo shows through. | a01, a10 |
| F-8 「承接中」 pill over the status bar; 报名中/承接中 | **Still there.** The pill floats over the clock when the workbench scrolls. Status words now differ in three places: home 「报名中」, workbench 「承接中」, 我的项目 「未开始」. | b07, b08, b04 |
| F-9 blank wrong-role / missing-param pages | **Partly gone.** Fixed: customer detail without id (「找不到这位客户」), invite detail without id, guest verify-records (→ 「登录后查看经营台」), guest customer detail / order detail / invite detail (「登录后查看 · 登录」), player customer list hides controls. **Still there:** guest 客户 shows only 筛选 / 管理 / 搜索 with nothing else and no login; guest ledger has no 登录 button; guest decor has no back arrow. | a24, a29, k-guest-01…08, k-player-04 |
| F-10 captions | **Partly gone.** 品牌中心 row captions are gone. Still there: 合作 · 帖文 empty caption; 成为节点 「申请上漫游地图」 and the lone ⓘ 「在架节点 0/3」 card; 报名成为节点 two captions; 商家报名 location note; AI 角色 onboarding sub-lines; 分润明细 「还没有已入账分润」; 「请返回重新进入」 on three missing-param pages; 审核状态 eyebrow and sentence (联系电话 still 「—」); 发起协作邀请 「先发布主题后再发起协作邀请」 and 「对方接受并成单后锁定」; 店铺参谋 「近30天，城瘾为你带来」; workbench 「主题已锁定，不可配置节点」; coupon tagline. 公开主页 still links 「核销用户评价与商家回复」. Not rechecked: 品牌介绍 duplicate one-liner, 口碑 「0.0」 (reviews page needs an id). | a02, a04, a05, a09, a12–a15, a17, a30, a32, a33, a36, b06, b07 |
| F-11 dead 编辑 on a locked theme | **Gone.** No 编辑 on the locked workbench. | b07 |
| F-12 title / theme inconsistencies | **Still there.** 核销详情 large left title vs 对公结算 centred nav title; 合作中心 title next to the back arrow; 商家报名 ~100 px low with an empty band; 本站打卡码 sheet is dark on the merchant side. | a04, a05, a21, a36, j04 |
| F-13 title clipped on 合作中心 → 看详情 | **Still there in the rig** (cover is 「封面暂不可用」, a rig image gap, so it may only happen when the cover fails). | j01 |

## 2. Steps

Read-backs are rig DB reads (`cy_e2e_51`). Rig MySQL runs in UTC: rows written with SQL `NOW()` (activity verification time, refund time) show 8 h behind the app's own times. That is a rig artefact (H-5).

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| A | 37 pages, cold deep link as owner | PASS: 0 page errors, no failed API call | a01–a37 | logs/a-sweep.txt |
| B1 | 客户 with one 自由探索 buyer who came | PASS (row shown), with N-2 | b10, b11 | `/crm/customers/9009/detail` paidAmount 69.0 |
| B2 | 客户 with 自由探索 buyers who never came | Rows shown as three identical 「城瘾用户 · 待核销」, N-6; detail says 累计实付 ¥69.00 and 到店 0, N-2 | d01, d02 | regs 75/76/77 |
| B3 | 客户 筛选, 管理 → 批量标签, 导出, note on detail | PASS. Export becomes 「导出已就绪（4 行）」 after ~12 s; note saved and shown on the list | b13, n02, n03, n05, n06 | — |
| C1 | 扫码 at the counter, 自由探索 ticket (player 甲) | PASS: green 「核销成功 · 第1章 咖啡 · 请提供：到店赠美式一杯」 | e01 | redemption 5 ACTIVE, merchant 9002 |
| C2 | Same code again | PASS: 「已核销 · 该章节已核销，请勿重复提供权益」 | e02 | no new row |
| C3 | A non-城瘾 code | PASS: 「未核销 · 这不是城瘾的核销码」 | e03 | — |
| C4 | Refunded ticket | PASS: no code can be issued | — | `/dyncode/issue` refused |
| C5 | Numbers after 3 redemptions | PASS: ledger 3 笔, 核销记录 3, 营销 核销 3, 店铺参谋 到店 3 | e10–e14 | redemption 1, 5, 6 |
| C6 | 扫码 the merchant's own activity ticket (3 days before the event) | PASS 「验票成功 · 核销成功」; see N-11 | l01 | reg 80 verified |
| D1 | Owner invites 店长, then 核销员 | PASS | f01, f02, f20 | `merchant_operator_invite` ACCEPTED |
| D2 | Invitee opens the share link and taps 接受邀请 | **PASS** (first-pass j1 F4 blocker is gone) | f03, f04, f21 | `merchant_operator` 1 MERCHANT_MANAGER, 2 MERCHANT_CHECKIN ACTIVE |
| D3 | 店长 views | PASS for scope (no 累计收入, no 财务; 客户, ledger without money, decor, coop). See N-9 for coop/finance | f07–f16, f19 | `/merchant/finance/overview` 403 |
| D4 | 核销员 views and scan | PASS: home shows only 扫码 and 更多 → 核销记录; 客户 「当前岗位没有客户查看权限」; scan sheet shown | f18, f22–f30 | redemption 7, operator 990006 |
| D5 | Owner removes the 核销员; they reopen the app | PASS: removed staff land on the player 主页 | f31–f33, f18-clerk-scan-err | operator 2 REVOKED |
| E1 | Coop invite received, list and detail | FAIL for a terms-less invite (N-12); PASS for 引流型 | g03, g04, g20, g21 | 990025, 990026 |
| E2 | 拒绝 with and without a reason | Without: nothing visible (N-8). With: confirm → 「已拒绝」 | g10, g14–g16 | 990025 status 2, handle_reason saved |
| E3 | 接受合作 | PASS | g22–g26 | 990026 status 1; `coop_order` 2 SUPPLY / TOPIC |
| E4 | 合作操作: 进入项目, 联系合作方, 申报供给, 评价, 取消合作 | All open; text problems in N-5 | g27–g31 | — |
| F1 | Write: 常备权益 add | PASS | h01–h04 | `coop_perk_template` 990031 |
| F2 | Write: 承接设置 合作诉求 | PASS | h13–h16 | `mms_merchant.demand` saved |
| F3 | Write: 发布优惠券 | PASS (N-13 on the form) | h20–h26 | `sms_coupon` 15 |
| F4 | Write: AI 店铺角色 name and 性格 | PASS, saved per field | h33–h37 | `npc_profile` 2 小咖 gentle |
| F5 | Write: 门店相册 | NOT VERIFIED (HARNESS): upload calls WeChat `img_sec_check`, 503 「安全检查服务异常」 | h30–h32 | gallery empty |
| F6 | Write: 商家报名 `topic/merchantapply` | Empty submit errors shown (N-10); full submit NOT VERIFIED: photo upload is the same HARNESS seam | h45–h50 | — |
| F7 | Write: 报名成为节点 | NOT VERIFIED: 去配置玩法 opens the template editor (p3), map step skipped per brief | h40 | — |
| G1 | Refund on the merchant's own activity (player 丙 cancels) | PASS in DB; aftercare list shows it under 处理中, detail opens from the ledger | m01, m05, m06 | `refund_application` 2, ¥39, merchant 9002 |
| G2 | Ledger activity row → 核销详情 | **FAIL, dead end (N-1)** | m07 | row recordType `activity_ticket` |
| H1 | Settlement of activity 14 (real job) | PASS: 78.00 = 75.66 + 2.34 | l2-* | balance rows |
| H2 | Merchant screens after settlement | Home 累计收入 ¥75.66, ledger 已入账 ¥75.66, 账户收益 ¥75.66: PASS. 分润明细: FAIL (N-4). Ledger refund row: N-3 | l2-home, l2-ledger, l2-earnings, l2-coop-finance | — |
| I | Wrong role: guest and player on 8 pages | Partly (F-9 above, N-9) | k-* | — |
| J | Fault: project list API 500 | PASS (F-5) | i01 | fault filter answered 500 |

## 3. New findings

### BLOCKER
None found.

### HIGH

**N-1. A paid activity redemption in 核销与退款 opens a dead end.**
- Repro: merchant scans a ticket of its own paid activity, opens 核销与退款, taps 「周六手冲咖啡体验 · 单人体验 · +¥37.83」. 核销详情 says 「缺少核销记录信息 · 请返回重新进入」.
- Cause: since `8c92dff6b` the redemptions list carries activity scans as `recordType: activity_ticket` (`/api/merchant/finance/redemptions` returns `activity_ticket:80`). `pages/merchant/ledger/index.js:577-578` passes that type on, but `pages/merchant/ledger/order-detail/index.js:17` only accepts `redemption`, so every activity row lands on the missing-param state. These are the merchant's only cash rows.
- Shot m07, l1-ledger.

**N-2. Every participating shop sees a 自由探索 buyer's whole ticket price as 「累计实付」.**
- Repro: 990014 has 4 participating shops. Player 甲 pays ¥69 once. At shop 8001, 客户 → 甲 shows 「累计实付 ¥69.00」 and 「到店次数 0」. The same ¥69 shows at each of the 4 shops, and for buyers who never came to that shop.
- The shop's own ledger says these are 「无现金结算」: the shop got ¥0. A merchant reads ¥69 as money its customer spent with it.
- Cause: `MerchantCrmAppMapper.xml:318` and `:555` sum `payable_amount` over every owned registration; the 2026-11-04 rule brought 自由探索 tickets into that scope (`:108-145`).
- Suggest: for a non-host shop, count only what the shop was paid (here none) or drop the 累计实付 hero for those rows.
- Shots b11, d02.

**N-3. 核销与退款 shows a refund as −¥39.00 next to the merchant's +¥37.83 income.**
- Repro: player cancels a ¥39 ticket of the merchant's activity. The ledger lists 「退款 · 城瘾用户 −¥39.00」 between income rows of +¥37.83, while 已入账 ¥75.66 is never reduced by it (it shouldn't be: the merchant's ¥37.83 share of that order was voided before settlement).
- The row reads as ¥39 taken from the merchant, which never happened. Show the merchant's share (−¥37.83 pending, voided) or mark it clearly as the player's refund.
- Shot l2-ledger, m03.

**N-4. 分润明细 contradicts itself after settlement.**
- After the activity settles: 「已入账分润 ¥0.00」, 「可提现 ¥75.66」, then the caption 「还没有已入账分润」 and 「还没有合作结算」, on one screen.
- 可提现 is the account balance from activity income; 已入账分润 counts only coop splits. Label what each is, or don't show 可提现 here.
- The same page shows ¥0.00 / 联系平台客服提现 to a player and to a 店长 who has no 财务 permission (fake zeros; `/api/coop/finance` returns 200 with empty topics for both).
- Shots l2-coop-finance, k-player-08, f14.

**N-5. Co-op texts promise money on a no-cash contract.**
- After accepting a 引流型 · 无分成 invite:
  - 进入项目: 「这一屏没有节点可列:你接的是整条路线的合作（到店核销与分成）,不针对某一站。」 says 分成.
  - 申报供给: 「勾选本次提供的权益，按核销人数结算」 says 结算.
- Both are also helper lines (owner rule) and mix half-width punctuation (`:` `,`) into Chinese.
- Shots g27, g29.

**N-6. 客户 can't tell customers apart.**
- Three 自由探索 buyers show as identical 「城瘾用户 · 待核销 · 有待核销报名 · 今天」 with the same avatar. The same people are 「测试玩家甲 / 乙 / 丙」 in 核销与退款, 核销记录 and the project's 客户 sheet (registration real name). The merchant cannot match a row in 客户 to anyone they served.
- Same on 经营团队: both staff are 「城瘾用户」, and the removal confirm 「移除团队成员「城瘾用户」？」 does not say which role (I first opened the wrong one).
- Shots d01, e11, j06, f31, f32.

**N-7. 退款详情 asks for the merchant's view and refuses it in the same screen.**
- 「平台审核已结束，不能再追加商家意见。」 sits above 「商家意见 · 等待商家意见」. One of them is wrong. The client is shown only as 「昵称 · 城瘾用户」.
- Shot m05.

**N-8. 拒绝 an invite without a reason looks dead.**
- From the top of 协作详情, 拒绝 with an empty reply does nothing visible: the error 「拒绝要写个理由 / 请说明拒绝理由，让对方知道如何调整。」 appears next to the reply box, which is off screen.
- Scroll to it or focus the field; also the sub-line is a helper caption.
- Shots g10/g11 (nothing visible), g14 (error after scrolling).

### LOW

**N-9. Wrong role and guest leftovers.**
- Guest 客户: title, 筛选, 管理, 搜索, nothing else, no login (k-guest-04). Guest ledger: lock and 「当前身份不能查看这份商家账本」, no 登录 (k-guest-05). Guest decor: no back arrow (k-guest-06).
- Player opening 客户详情: 「当前岗位没有客户查看权限 · 请联系店主开通 · 返回客户列表」; a player has no 岗位 and the list is also closed to them (k-player-01).
- 店长 deep link `merchantinfo?topicId=990014` (wrong param) is a blank page with 「你既不是该主题的发布者,也没有承接记录」 in the network only; the owner gets 「链接已失效」 for a bad link (f17 vs a37).

**N-10. 商家报名 errors break the owner's error rule and wording.**
- Empty submit shows a pink block with a warning icon 「请输入活动地点」 under 承接地点 (owner rule: red text under the field), and says 活动地点/输入 for a 承接地点 picker.
- Shot h46.

**N-11. A merchant can redeem an activity ticket days before the event.** The ticket for an event 3 days out scanned as 「验票成功」, which also ends the player's refund right. If that is intended, ignore; otherwise check the start window. Shot l01.

**N-12. A terms-less invite shows an error card in 协作邀请 and accept buttons in its detail.**
- `/api/coop/invite` accepts an invite without `shareMode`. The list card then shows 「邀请信息不完整 / 邀约信息不完整 / 重新加载」 (title and sub say the same; reload can't fix it), while 协作详情 offers 拒绝 / 接受合作 with 分润 「—」.
- The real club screens always send `shareMode`, so this needs a non-UI caller; the server should refuse it.
- Shots g03, g04.

**N-13. Coupon form.**
- No value field; types are fixed (礼品券, 9折券, 8折券, 体验卡) plus a selectable 「请选择」 row. A coupon named 「到店立减 5 元」 saves as 礼品券 with no amount.
- 投放数 is prefilled 「0」 (fake zero), and the default validity is today 09:00–18:00 only.
- Shots h20, h23, h26.

**N-14. 协作详情 after accept.**
- 「需要对齐事项时，请点击上方“联系合作方”进入单聊。」 points up, but the button is below it, and it is a helper line. 「对方没有留联系方式，可用下方「联系合作方」站内沟通」 repeats it.
- 评价 is offered right after accepting, before anything happened.
- 已拒绝 invite: my reply doesn't appear in the 留言 thread, and the footer says 「这条邀约已失效」, not 已拒绝.
- Sender shown as 「来自 主理人策划工作室」 (the lead's merchant name) and 「主题发布者 → 商家」, although the theme is the club's. Home 我的消息 renders it as 「主理人策划工作室 的主题发布者 → 商家邀请」.
- Shots g16, g24, g30, i01.

**N-15. AI 店铺角色 status contradicts itself.** After saving: 「审核中 · 玩家现在能看到这个形象」 (DB `audit_status` 1). Shot h36.

**N-16. 客户 tags.** A buyer who cancelled with the organiser is tagged 「异常 · 发生退款」 at every participating shop; a no-show stays 「待核销」 after the event ended. Shots e15, l2-customer.

**N-17. Small copy and duplicates.**
- 营销 「近 30 天报名 0 · 核销 3」 before the own activity existed: redemptions of other hosts' tickets with zero sign-ups reads odd (a10, e13).
- 移除成员 confirm: subtitle and first bullet say the same; half-width `,` `;` in Chinese (f32).
- Workbench has both a 扫码 icon and a 扫码核销 button after accept (g27).
- 「岗位调整或离职由店主操作，立即生效。」 on the staff's team page is a helper line (f04, f12).
- 客户 rows 「联系方式在主题名单按客户授权查看」 repeated on every 自由探索 row (b10).

### Harness notes (not product bugs)
- **H-1** dummy OSS keys set; images signed to an unreachable host, so covers and NPC presets are grey (a01, j05).
- **H-2** `img_sec_check` is unreachable: gallery and 商家报名 photo uploads 503.
- **H-3** wxacode: 本站打卡码 「个人码生成服务繁忙」 (j04).
- **H-4** the rig loads all subpackages up front, so `componentPlaceholder` first-open flashes can't be seen.
- **H-5** rig MySQL is UTC: activity verification and refund times written with SQL `NOW()` show 02:52 next to 10:31 app times (l1-ledger, m05), and the CRM API returned `lastInteractionTime 02:15+08:00`. Check production's DB time zone before reading this as a bug.
- **H-6** rig seed lacks `settlement_rule` 3 % rows and `cms_setting` row 1; both added (see Data).

## 4. Not covered
- **game-node with data:** needs a director-runtime club session with a merchant station (`game_station_run`). The standard chains create none (`/api/game/session/merchant/entries` → `[]`), and building one is the whole j2 journey with its fixtures. Without ids the page redirects home (a23).
- **coop/settlement-detail with data:** no coop settlement exists for a merchant. 按核销付费 settlement is waiting for the owner (ROUND11 I1), and 引流型 pays nothing.
- **ledger/batch-detail with data:** no 对公结算 batch path in the rig.
- **predict with data:** no 预测 at a merchant station.
- **aftercare 待回应 actions** (同意 / 不同意 / 补充凭证): the refund was auto-approved, so the merchant had nothing to answer.
- Write flows not completed: gallery save and 商家报名 submit (upload seam), 报名成为节点 (map and game publish), coop invite send from 发起协作邀请 (merchant has no own theme), AI 角色 photo and voice.
- The 确认终价 lead (p3 page).
- Real phone: fonts, safe areas, keyboard, native map and scan, share cards, subscribe messages.

STATUS: PARTIAL - all 37 pages re-walked, F-5 and F-7 confirmed gone, counter scan, staff roles, refund, settlement, invites and write submissions reached; game-node, coop settlement-detail, batch-detail and predict with data are not verified (no rig path without the j2 journey or an owner decision)
