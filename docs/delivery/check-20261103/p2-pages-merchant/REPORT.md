# Round 8 check: p2-pages-merchant (merchant / coop pages): first pass

- Commit under test: `8b78f5ca3c06f486631e827827e973cbb47e3094`. Branch `claude/check-1104-p2-pages-merchant`, cut from that commit.
- This ran in the cloud rig, not on a phone. The rig is `claude/cloud-walk-20261101-rig` plus the club lane's `tools/harness-patches.diff`. It ran the real jar, MySQL 8 and Redis 7 in Docker (via `mirror.gcr.io`), and the real mini-program code in the browser runtime at 390×844. Screenshots are 2×.
- Rig-only changes, none of them in product code:
  1. `up.sh` pinned to this SHA.
  2. `walk.mjs` takes screenshots at deviceScaleFactor 2.
  3. `app.py` passes dummy `CHENGYIN_OSS_*` values so image-URL signing works offline. See H-1.
- Data:
  - Fixture seed, then the candidate's own `ci/chain_acceptance` chains: platform → merchant_club → sale_close → player → finance, all PASS on topic 990014.
  - That run produced one real redemption at merchant 8001 (member 9002 「探店日样板咖啡」) by player 「E2E 玩家二号」.
  - All data is fictional.
- Identities used:
  - Merchant owner: 9002.
  - Player: 9004.
  - Guest: no login.
- Lane p1 is not running in this pass, so I built the page list myself.

## 0. Page list and rule

**Rule:**
- A page is **p2** if its route is under `pages/merchant/` or `pages/coop/`.
- Two more pages count as p2: `pages/topic/merchantinfo/merchantinfo` and `pages/topic/merchantapply/index`. The merchant is their user (the merchant's theme/project workbench and the merchant's station application), even though they sit in the topic subpackage.
- These are **not** p2:
  - `pages/play/merchant/index`: the player's view of a merchant at a play node, so p1.
  - `subpackageA/pages/assetcenter/*` (earnings, income detail, analysis): shared by players and merchants, so p1 under "if unsure, p1". I opened them only as entries from merchant pages.
  - `pages/topic/pricing/*`: the organiser's pricing pages, so p3.

That gives **37 pages**: 27 under `pages/merchant`, 8 under `pages/coop`, and 2 in `pages/topic`. app.json registers 157 pages in total.

| # | Page | How reached | States seen | Shots |
|---|---|---|---|---|
| 1 | pages/merchant/index/index | Launch as merchant; tabs and quick actions | empty, full, load-failure (H-1) | e01, f01, f01b, t01, t01b, t04, n12a |
| 2 | pages/merchant/apply/index | Deep link as merchant (status) and as player (landing) | full (approved), new applicant | e37, e38 |
| 3 | pages/merchant/ledger/index | Home → 财务 entry / deep link; tabs 不结现金, 待处理 | empty, full | e02, f02, n02c |
| 4 | pages/merchant/ledger/order-detail/index | Ledger row tap; deep link without id | full, missing-param | n02b, e06 |
| 5 | pages/merchant/ledger/batch-detail/index | Deep link without id only (no batch exists) | missing-param | e07 |
| 6 | pages/merchant/aftercare/index | 更多 → 退款售后 | empty | e03, n01c |
| 7 | pages/merchant/predict/index | Deep link | empty | e04 |
| 8 | pages/merchant/aftercare/detail/index | Deep link without refundId (falls back to list) | fallback | e05 |
| 9 | pages/merchant/profile/index | Legacy deep link `?id=8001` → redirects to public merchant home | redirect OK | e08 |
| 10 | pages/merchant/marketing/index | Tab 营销 | full | e09, f06 |
| 11 | pages/merchant/reviews/index | 品牌中心 → 看公开主页 → 真实评价; deep link without id | empty, missing-param | n08c, e10 |
| 12 | pages/merchant/marketing/ai-insight/index | 营销 → 店铺参谋 | empty, full | e11, f05 |
| 13 | pages/merchant/relation/index | Tab 合作 (帖子 / 俱乐部) | empty, full | e12, f11b |
| 14 | pages/merchant/citynode/index | Deep link; 认领节点 sheet | empty | e13, n15b |
| 15 | pages/merchant/citynode/create/index | Deep link (map step skipped) | empty form | e14 |
| 16 | pages/merchant/decor/index | 更多 → 品牌资料; deep link | full | e15, n01f |
| 17 | pages/merchant/decor/ai-npc/index | 品牌中心 → AI 店铺角色; onboarding steps | onboarding | e16, n23b, n23d |
| 18 | pages/merchant/decor/coop-setting/index | 合作经营 → 承接设置; 编辑 | view, edit | e17, n22b |
| 19 | pages/merchant/decor/gallery/index | 门店相册; + → crop | empty, crop | e18, n26b |
| 20 | pages/merchant/decor/perks/index | 合作经营 → 常备权益; 添加权益 | full, add form | e19, n21b |
| 21 | pages/merchant/coop-center/index | 营销 → 进入合作中心 / deep link | full | e20, f10 |
| 22 | pages/merchant/customer/index | Home → 客户; 筛选, 管理 | empty (with data present, see F-4) | e21, f04, n24b, n25b |
| 23 | pages/merchant/game-node/index | Deep link without ids (redirects to home) | fallback | e22 |
| 24 | pages/merchant/customer/detail/index | Deep link without id | **blank** | e23 |
| 25 | pages/merchant/verify-records/index | 更多 → 核销记录 | empty, full | e24, f03, n01d |
| 26 | pages/merchant/crm-campaigns/index | Deep link; 客户 → 管理 → 触达记录 sheet entry seen | empty | e25, n25b |
| 27 | pages/merchant/team/index | Deep link; 邀请员工 sheet | empty, sheet | e26, n14b |
| 28 | pages/coop/list/index | Deep link | empty | e27, f09 |
| 29 | pages/coop/invite-detail/index | Deep link without id | **blank** | e28 |
| 30 | pages/coop/invite/index | Deep link | empty (no own theme) | e29 |
| 31 | pages/coop/nearby/index | Deep link | empty | e30 |
| 32 | pages/coop/finance/index | Deep link; first open at 300 ms and 1 s | empty | e31, s02a, s02b |
| 33 | pages/coop/settlement-detail/index | Deep link without id | missing-param | e32 |
| 34 | pages/coop/withdraw/index | Legacy deep link | static | e33 |
| 35 | pages/coop/withdraw/records/index | Legacy deep link → redirects to 提现记录 | redirect OK | e34 |
| 36 | pages/topic/merchantapply/index | Deep link (no topic) | form | e35 |
| 37 | pages/topic/merchantinfo/merchantinfo | Home project card → workbench; 合作中心 → 看详情; deep link without id | full, invalid-link | n10b, n10c, n12b, n12c, n16b–n19b, e36 |

Entries into p1 pages from merchant screens, all opened from the merchant side:
- 财务 → `subpackageA/.../earnings` (t02b, s01a/b), then 明细 (n03b), 分析 (n03c) and 提现 (n03d).
- 更多 → 管理优惠券 → `subpackageMember/coupon/coupon` (n01e, n04b): **broken**, see F-1.
- 更多 → 协作邀请 → `subpackageB/pages/im/list` (n01b).
- 项目「全部 1」→ `subpackageA/pages/myproject` (n13b): **empty**, see F-3.

Wrong-role access, as player 9004 and as guest (g01–g08):
- Merchant home, decor and team send a player or guest to the player 主页. This works.
- The ledger shows a lock. Coop list and coop finance ask a guest to log in.
- Customer page and verify-records come out blank. See F-9.

## 1. Step table

| Step | Result | Shot | Read-back |
|---|---|---|---|
| All 37 pages open from a cold deep link as merchant 9002 | PASS (0 page errors on every page) | e01–e37 | walk logs: network all HTTP 200 |
| Merchant home with one redemption | PASS for counts; card cover grey (H-2) | f01, f01b | `/api/merchant/chapter-application/mine` row status 1, `offerQuotaTotal` 20, used 1 |
| Ledger shows the redemption | PASS: 「本月核销 1 笔 · 权益已履约 · 无现金结算」 | f02 | matches the chain's one redemption, ¥0 cash |
| Verify-records shows it | PASS: 已核销 1 | f03 | same |
| Project → 客户 | PASS: 1 已核销, 「E2E 玩家二号 · 12:34 核销」 | n18b | same |
| 店铺参谋 | PASS: 到店 1 · 打卡 1 · 核销 1 | f05 | `/api/merchant/dashboard` checkin.total 1 |
| 营销 numbers | **FAIL**: 「近 30 天报名 0 · 核销 0」 | f06 | `/api/merchant/marketing-home` window ends at the start of today (F-2) |
| 客户 (CRM) | **FAIL**: 「0 位 · 还没有客户」 | f04 | `/api/merchant/crm/customers/list` total 0 (F-4) |
| 项目 → 全部 1 | **FAIL**: list empty 「还没有主题」 | n13b | home says 1 project (F-3) |
| 更多 → 管理优惠券 | **FAIL**: blank page, no create button | n01e, n04b | `/api/coupon/mypublishlist` → 「系统繁忙」; server log shows a Druid wall refusal (F-1) |
| 更多 → 协作邀请 | PASS, but it lands on 消息 收件箱 (IM), not on pages/coop/list | n01b | code comment says this is intended (`index.js:1206`) |
| 财务 → 账户收益 / 明细 / 分析 / 提现 | PASS (light theme, ¥0.00 agrees with ledger) | t02b, n03b–d | balance 0 |
| Ledger row → 核销详情 | PASS with polish issue (F-7) | n02b | — |
| 品牌中心 rows, 门店信息, 品牌介绍, 合作经营, 公开主页, 口碑 | PASS (copy findings F-10) | n05b–n08c | — |
| AI 店铺角色 onboarding | PASS | e16, n23b, n23d | — |
| 常备权益 → 添加权益 form | PASS (not submitted) | n21b | — |
| 承接设置 → 编辑 | PASS (not saved) | n22b | — |
| 门店相册 → + → 裁剪 | PASS (rig photo) | n26b | — |
| 经营团队 → 邀请员工 sheet | PASS | n14b | — |
| 合作中心 → 看详情 → 申请承接 | PASS: 「这条路线暂时没有开放的章节」 is the correct state for the fixture | n10b, n11b | — |
| Project workbench: 节点NPC sheet | PASS, but all 12 presets show grey 「角色」 boxes. Possibly a rig gap; NOT VERIFIED | n16b | — |
| Project workbench: 二维码 | NOT VERIFIED: 「个人码生成服务繁忙」 needs WeChat wxacode (HARNESS) | n17b | — |
| Project workbench: 编辑 | No visible response; the theme is locked (F-11) | n19b | — |
| Lead: `pages/topic/merchantinfo` blank as merchant | **Not reproduced**: renders for 990013 (合作中心) and 990014 (project) | n10b, n12b | — |
| Lead: async `scene-*` first open (earnings, coop finance) | No blank or flash at 300 ms. NOT VERIFIED for a phone: the rig bundles every subpackage in one go, so async subpackage loading is not reproduced | s01a, s02a | — |
| Lead: 确认终价 pink error block | Not covered: `pages/topic/pricing` is p3 under my rule | — | — |
| Scan at counter, staff (clerk) role, refunds/aftercare detail, batch detail, settlement detail, invite detail with data, game-node with a live session | NOT VERIFIED in this pass (see §3) | — | — |

## 2. Findings

### F-1 BLOCKER (dead end): merchant coupon management is a blank page
- **Repro:** As merchant 9002, go to 工作台 → 更多 → 管理优惠券. The same page opens from 营销 → 优惠券. Only the title 「优惠券」 shows: no list, no 「发券」 button, no error text. The merchant cannot see or create coupons.
- **Cause:**
  - `POST /api/coupon/mypublishlist` returns `{"code":500,"msg":"系统繁忙，请稍后重试"}`.
  - The server log shows `sql injection violation ... comment not allow : update sms_coupon`.
  - The statement is `updateExpiredCouponsByMemberId` in `chengyinhub-system/src/main/resources/mapper/business/SmsCouponMapper.xml:192-202`. It carries `-- ...` SQL comments on every line, and the app's Druid wall (`application-druid.yml`, wall enabled, Druid 1.2.28) refuses SQL comments.
  - `ApiCouponController.java:126` runs this update before every list call.
  - The page then goes to `loadState: 'error'`. `cy-error load-failure` draws nothing (owner rule), and `cy-footer-bar` is hidden on error (`subpackageMember/coupon/coupon.wxml:48`).
- **Scope:** every merchant and club that opens its published-coupon list. The player wallet uses `SmsCouponHistoryMapper.updateExpiredCouponsByMemberId`, which has no comments, so it is unaffected.
- **Caveat:** the rig uses the repo's own `application-druid.yml`. If production overrides the wall config privately, production may differ. Please confirm on the VPS test backend.
- **Shots:** n01e, n04b.

### F-2 HIGH: 营销 says 「核销 0」 on the day the shop redeemed
- **Repro:** Redeem one ticket today, then open the 营销 tab. It shows 「近 30 天报名 0 · 核销 0」, while 店铺参谋 one tap away shows 「到店 1 · 打卡 1 · 核销 1」 and the ledger shows 「本月核销 1 笔」.
- **Cause:** `/api/merchant/marketing-home?windowDays=30` uses `basis: COMPLETE_CALENDAR_DAYS`, with `endExclusive` at 00:00 today Asia/Shanghai, so today never counts. 店铺参谋 (`/api/merchant/dashboard`) counts today.
- **Fix needed:** one window rule for both screens.
- **Shots:** f06 vs f05, f02.

### F-3 HIGH: 「全部 1」 on home opens an empty 我的项目
- **Repro:** On merchant home, the 项目 section shows 「全部 1」 and the 「E2E 探店日一期」 card. Tap 「全部 1」. It lands on `subpackageA/pages/myproject/index` 「我的项目 · 主题 / 活动 / 模板 · 还没有主题 · 去发布」.
- **Problem:** The merchant's 承接 project is not listed anywhere on that page. The count and the list disagree, and 「去发布」 points the merchant to the wrong action.
- **Shots:** f01b, n13b.

### F-4 HIGH: 客户 shows 0 after serving a customer
- **Repro:** After one redemption at the counter, open 工作台 → 客户. It shows 「0 位 · 本月新增 0 · 还没有客户 · 去合作中心」, while project → 客户 lists the customer.
- **Cause:** `MerchantCrmAppMapper.xml:107` 「探店日不并入通用 CRM」 deliberately excludes `product_type = 2`. If that is an owner decision, the screen still misleads: the empty state tells a merchant who has customers that they have none.
- **Recommendation:** count them, or say where they are.
- **Shots:** f04 vs n18b.

### F-5 LOW: the 项目 heading is left alone above blank space when the project list fails to load
- **Repro:** When `/api/merchant/chapter-application/mine` fails, home keeps the 「项目」 heading with nothing under it. `cy-error load-failure` is blank by owner rule.
- **Trigger in the rig:** a 500 from missing OSS config (H-1).
- **Fix:** with the owner's blank-on-failure rule, also hide the heading, or keep the section height stable without a title.
- **Location:** `pages/merchant/index/index.wxml:138-190`.
- **Shot:** t01, t04.

### F-6 LOW: the bottom tab bar has no solid background, so content shows through the icons
- **Where:**
  - On home (f01), the first project card's text 「第1章 咖啡 · 今天 12:34 · 1 人已报名」 sits under the tab icons and becomes unreadable.
  - On 营销 (e09, f06), the 合作机会 photo banner shows through the icons.
- **Fix:** give the tab bar a solid page-colour background, or add bottom padding equal to the tab bar height.
- **Shots:** f01, e09.

### F-7 LOW: 核销详情 shows an empty 「我的收入」 placeholder for no-cash redemptions
- **Problem:** The card header reads 「E2E 探店日一期 · 第1章 咖啡 · 我的收入」, then a grey dash bar where an amount would be, then 「权益已履约 · 无现金结算」.
- **Fix:** for no-cash rows, drop the 收入 line and lead with the tag.
- **Shot:** n02b.

### F-8 LOW: the 「承接中」 status pill floats over the status bar when the project workbench scrolls
- **Repro:** On the project workbench (`pages/topic/merchantinfo`), scroll down. The 「承接中」 pill stays fixed at the top right and overlaps the clock and battery area.
- **Also:** home calls the same project 「报名中」 while the workbench calls it 「承接中」.
- **Shots:** n12c, f01.

### F-9 LOW: wrong-role and missing-parameter deep links leave blank pages
- **Blank pages:**
  - `pages/merchant/customer/detail/index` without `customerMemberId` (e23).
  - `pages/coop/invite-detail/index` without `inviteId` (e28).
  - `pages/merchant/verify-records/index` as player or guest (g06).
  - `pages/merchant/customer/index` as guest (g05).
- **Related:** as player, the customer page shows 「当前岗位没有客户查看权限」 but still shows live 筛选 / 管理 / 搜索 controls (g05-player).
- **Ledger:** as guest it says 「当前身份不能查看这份商家账本」 with no 登录 button (g02-guest).
- **Decor:** as guest it shows a login wall with no back arrow (g04-guest).
- **Fix:** use the same 「链接已失效 · 返回上一页」 pattern already used by order-detail, settlement-detail and merchantinfo, and the login prompt that coop/list uses.

### F-10 LOW: taglines, helper lines and secondary labels (owner's standing rule)
Each item below gets the layout change that would make it unnecessary.
- **品牌中心 rows** 「名称 · 地址 · 营业时间」「故事 · 特色」「形象 · 语音」「承接 · 主推 · 权益」 (e15). Drop them: the row titles say enough. Keep 「0 张照片」, which is data.
- **合作经营** (n07b):
  - Header 「让合作发生在店里」 and its icon: drop.
  - Row hints 「填写合作诉求」「选 1 个活动置顶」「查看并回复顾客评价」「添加衍生权益」「查看可开通的权益」「挂互动玩法」: drop. Show a value when one exists, otherwise just the chevron.
- **营销** 优惠券 empty state 「发一张到店券，把看过的人带进门」 (e09). Replace with a 「发券」 button.
- **Coupon page** empty state: same sentence, `coupon.wxml:43`.
- **店铺参谋** (e11):
  - 「计算方式：已核销报名数 ÷ 近30天内报名数」 and 「计算方式：核销≥2次的会员 ÷ 近30天内核销会员」: drop. Put the definition behind the metric label if it is needed at all.
  - 「近30天，城瘾为你带来」: shorten to the number plus 「位玩家到店」.
- **合作 · 帖子** empty 「帖子来自你合作过的俱乐部，谈成合作后即可看到」 (e12). Drop.
- **成为节点:**
  - Empty 「申请上漫游地图」 (e13). Drop; the button already says 投放节点.
  - Also the stray ⓘ card holding only 「在架节点 0/3」 (e13). Fold the count into the 我的节点 header.
- **报名成为节点** 「配一个自己店铺的玩法就行，商家信息用你的入驻资料」 and 「请在地图上选择店门口」 (e14). Drop; the steps already show it.
- **商家报名** (`topic/merchantapply`) 「用于地图与到场导航，拒绝不影响填写」 (e35). Drop. This is a location-permission note, not consent.
- **AI 店铺角色 onboarding** sub-lines 「出现在漫游地图和店铺主页上，替你招呼路过的玩家」「按你写的招呼语和性格说话」「温柔、可爱、毒舌，随时可改」 (e16, n23b, n23d). Keep only the headlines.
- **品牌介绍** 「选几个标签，让更多人找到你的店」 (n06b). Drop. Also the one-liner is shown twice: as the big heading 「街角咖啡补给站」 and as an empty-looking 「一句话介绍」 row. Keep one.
- **口碑** empty 「到店核销后可评价 / 到店完成核销后才能评价」 (n08c). The two lines repeat each other; keep one. The 「0.0」 rating with 0 reviews is a fake zero: show 「暂无评价」.
- **公开主页** 「核销用户评价与商家回复」 link label (n08b). Use 「查看」 or the count.
- **核销详情 / 对公结算 / 结算详情** missing-param state 「请返回重新进入」 (e06, e07, e32). The 返回上一页 button already says it.
- **审核状态** eyebrow 「商家入驻申请」 and 「商家身份已生效，可以进入商家中心使用经营功能。」 (e37). Drop both; the 已生效 tag carries it. Also: 联系电话 shows 「—」 although the account has a phone, and the lone 「返回」 button sits bottom-left.
- **商家入驻 landing** (player) 「商家入驻」 eyebrow, 「基础信息 · 经营信息 · 资质 · 确认」 and 「提交后可随时回来看进度与驳回原因」 (e38). Drop; keep the headline and 开始入驻.
- **发起协作邀请:**
  - 「先发布主题后再发起协作邀请」 under the empty state: drop; the button says 先发布主题.
  - 「对方接受并成单后锁定」 next to 合作条款: drop.
  - The 「查找原邀请请求（含其他设备）」 bar at the top reads as an engineering control (e29).
- **分润明细** 「还没有已入账分润」 caption under the withdraw button duplicates the 「还没有合作结算」 empty state (e31). Drop the caption.
- **Project workbench** 「主题已锁定，不可配置节点」 (n12b). Show it only when the merchant tries to edit (see F-11).
- **添加常备权益** 「成本价 · 仅自己可见」 (n21b). This is a privacy note for the merchant. Keep it if the owner counts it as consent-like; otherwise drop it.
- Not counted as findings: 「当前岗位 · 店主」, 「0 张照片」, 「剩余 19 / 20」, timestamps and counts. These are data.

### F-11 LOW: the project workbench 「编辑」 button does nothing visible on a locked theme
- **Repro:** On the project workbench, tap 编辑. Nothing on screen changes, while the card already says 「主题已锁定」.
- **Fix:** disable or hide 编辑 while locked, or open the locked explanation there.
- **Shot:** n19b.

### F-12 LOW: page-title and theme inconsistencies between neighbouring pages
- **Titles:**
  - 核销详情 uses the large left page title, while its sibling 对公结算 uses a centred nav-bar title (e06 vs e07).
  - 合作中心 uses a left-aligned title next to the back arrow, unlike every other merchant page (e20).
  - 商家报名 (`topic/merchantapply`) sits about 100 px lower than every other page, with an empty band under the capsule (e35).
- **Theme:** the project workbench's 「本站打卡码」 sheet is dark on the light merchant side (n17b).

### F-13 LOW: on 「E2E 探店日一期」 as a merchant (合作中心 → 看详情), the title is clipped under the nav bar
- **Seen:** the hero text sits half under the white nav bar on first open (n10b crop).
- **Caveat:** the cover is 「封面暂不可用」 here, a rig image gap. The clipping may appear only when a cover fails to load. Check on a phone.

### Harness notes (not product bugs)
- **H-1:** Without `CHENGYIN_OSS_*` set, `/api/merchant/chapter-application/mine` 500s with 「Could not write JSON: 对象存储配置缺失」 while it signs the theme image URL, and the home 项目 cards disappear. I set dummy values (rig only) and the list loads. Separately: the whole list fails if one image URL can't be signed. This is a brittleness worth a look, but it is not a production bug as long as OSS is configured.
- **H-2:** Theme covers and NPC preset images come out grey because the OSS images are not reachable from the rig.
- **H-3:** 「个人码生成服务繁忙」 on 二维码 needs WeChat wxacode.
- **H-4:** The rig loads all subpackages up front, so `componentPlaceholder` flashing can't be observed.

## 3. Not covered in this pass
- **Counter scan with a real dynamic code**, and the scan result sheets. j1 covers these; I only reached the 扫码 cancel path (t03b).
- **Clerk / staff role views**: no staff member accepted an invite in this pass.
- **Detail pages with real data:**
  - `aftercare/detail` (no refund exists).
  - `ledger/batch-detail` (no batch).
  - `coop/settlement-detail` (no settlement).
  - `coop/invite-detail` (no invite).
  - `customer/detail` (CRM empty, F-4).
  - `game-node` (no live game session for the merchant).
  - `predict` (no prediction to answer).
- **Write flows:**
  - Not submitted: perk add, coop-setting save, gallery save, AI NPC save, coop invite send, citynode create, merchant application submit and `topic/merchantapply` submit.
  - Map-based steps (店址 map pick, citynode map) are skipped per the brief.
- **Every control on every page:** I tapped the main controls on each page and every 更多 and 品牌中心 entry. Not every row on every sheet.
- **The 确认终价 lead:** that page is p3 under my rule.
- **Real phone behaviour:** fonts, safe areas, keyboard, native map, share cards and subscribe messages.

STATUS: PARTIAL - first pass. All 37 merchant/coop pages opened and shot, with the main entries tapped. The counter scan, staff role, refund/settlement/invite/game-node detail pages with data, and the write submissions are not yet verified.
