# Doc 2 新功能 · 交互原型（2026-10-23）

先把 Doc 2 的全部新功能放进一个 HTML 原型里一起看，确认流程和待决问题后再写页面代码。**本目录只有原型文件，没有改动任何小程序、后台或后端代码。**

## 文件

| 文件 | 说明 |
|---|---|
| `index.html` | 原型本体。单文件、无外部依赖，大约 200 KB。直接双击打开即可 |
| `README.md` | 本说明：页面清单、每页对应的接口、待 owner 决定的问题 |

## 怎么看

- **左栏是目录**：总览，加上每个功能的页面列表。点任意页面会直接跳过去，并补齐它的返回栈。
- **中间是手机**：按 390×844 比例显示，带微信导航栏（状态栏 + 右侧胶囊）。屏幕小时整体缩放；在手机浏览器（宽度 ≤ 820px）里打开时全屏显示，左右下角的「目录 / 面板」按钮可以拉出侧栏。
  - 交互都能点：页面压栈 / 返回、底部弹层（带拖拽条）、确认框、toast。
  - 演示数据各功能之间是联动的。例如在后台通过一条 F1 成绩申诉，手机端的流水里会多出一条调整；后台开具发票后，玩家端的发票详情也会变成已开票。
- **右栏是面板**：
  - **页面状态**：可以切到加载中、空、出错、无权限、开关关闭、已恢复。「出错」页点重试后会变成「已恢复」。
  - **演示开关**：标的是后端真实的开关名。关掉后，页面显示后端真实返回的拒绝码。
  - **本页接口 / 本功能全部接口**：橙色虚线框表示后端还没有这个接口，原型里画的是提议。
  - **待 owner 决定**：本功能的待决问题。
- 可以用 `#页面id` 直接打开某一页，例如 `index.html#f1-result`、`index.html#adm-invoice`。

## 视觉约定（按 owner 常设规则）

- **玩家端一律深色，商家端一律浅色。**后台单独做成桌面样式（RuoYi-Vue 风格），保持简化。
- 组件沿用漫游原型的视觉语言：
  - 色值 token：`--bg #000`、`--s1 #1C1C1E`、`--s2 #2C2C2E`；
  - 分组圆角卡片 `.group/.row`，白色主胶囊 `.pill`，灰色次胶囊 `.spill`，`.chip`，带拖拽条的 `.sheet`，`.page` 推入转场，六边形奖章 `.hex`，toast；
  - 参考 Revolut Business iOS（左对齐粗体大标题、分组卡片、胶囊按钮）和 Nike Run Club（斜体超大数字、统计格、六边形奖章）。
- 新页面照搬现有小程序页面的结构：
  - 结算页照 `pages/activity/baoming`（参与人 → 活动信息 → 费用明细 → 总计大数字 → 底部同意 + 付款）；
  - 券和入场码照 `components/cy/scene-qr-ticket`（白色票面、黑色 Code 条）；
  - 列表与详情照 `scene-member-order-history` / `order-detail`；
  - 商家页照 `pages/merchant/index` 工作台和 `merchant-finance.wxss`（浅灰底白卡）；
  - 玩法页任务卡与节点弹层照 `pages/play/index`（`.gp2__card`、`.sheet`）；
  - 大标题照 `cy-page-title`，导航栏照 `cy-nav-bar`。
- 图片一律用 CSS 渐变占位。原型里没有真实域名、没有密钥、没有真人信息，演示数据都是虚构的。
- ⚠️ 任务里引用的 `docs/design/漫游卡片落地页_20261013/index.html` 不在本仓库任何分支或历史提交里。上面的视觉 token 和组件是按任务描述的规格重建的，现有小程序页面的结构取自对应的 wxml / wxss。

## 开关说明

线上这些开关**全部默认关闭**（见 `integration-batch4.md` §4、`integration-batch5.md` §5）。为了让流程能走通，原型里的「演示开关」默认是打开的，只有以下几个默认关闭：

- 守卫 shadow 模式
- `require_accuracy`
- `minorOrienteeringGate`（没有调用方）
- 两个「强制掷骰结果」演示项

F3 的 `reservation-enabled` 在原型里默认开，只是为了展示预约流程。按 K3 的结论，K3 定下来之前线上必须保持关闭。


## 页面清单与接口

### F1 · 城市定向（深色 · 玩家）

开关：`feature.flag.orientSession`（会话生命周期 F1a）；`orient.scoring.enabled`（计分 F1b）；`play.arrive.location_guard.mode = enforce（关=off）`（到达位置守卫 F1c）；`play.arrive.location_guard.mode = shadow`（守卫只记不拦）；`play.arrive.require_accuracy`（必须上报定位精度）；`orient.alt_task.enabled`（AR 替代任务 F1d）；`orient.team.board.enabled`（队伍看板 F1d）；`orient.safety.enabled`（开局安全检查 F1d）

| # | 页面 id | 页面 | 本页接口 |
|---|---|---|---|
| 1 | `f1-home` | 活动入口 · 开局前 | `POST /api/orient/safety/check`<br>`POST /api/play/orient/session/prepare` |
| 2 | `f1-run` | 对局中（HUD） | `GET /api/play/orient/session`<br>`GET /api/orient/scoring/session/{sessionId}` |
| 3 | `f1-ended` | 已放弃（终态） | `POST /api/play/orient/session/abandon` |
| 4 | `f1-gappeal` | 到达申诉 | ⚠️ 提议：`POST （audit_appeal 提交入口）` |
| 5 | `f1-task` | 站点任务 · AR 替代 | `GET /api/orient/task/state`<br>`POST /api/orient/task/alternative/submit`<br>`POST /api/play/orient/session/task/{taskId}/result` |
| 6 | `f1-team` | 队伍看板 | `GET /api/orient/team/detail`<br>`GET /api/orient/team/ranking` |
| 7 | `f1-score` | 计分明细（追加式流水） | `GET /api/orient/scoring/session/{sessionId}`<br>`POST /api/orient/scoring/session/{id}/sync` |
| 8 | `f1-result` | 完赛 · 终局成绩 | `POST /api/orient/scoring/session/{id}/settle`<br>`GET /api/orient/scoring/session/{sessionId}` |
| 9 | `f1-appeals` | 我的申诉与争议 | `GET /api/orient/scoring/receipt`<br>`POST /api/orient/scoring/appeal`<br>`POST /api/orient/scoring/dispute` |

<details><summary>本功能全部接口</summary>

- `POST /api/play/orient/session/prepare` — topicId, activityId, idempotencyKey → view + ackConfigVersion
- `POST /api/play/orient/session/start` — sessionId, ackConfigVersion, idempotencyKey → view + resumed
- `POST /api/play/orient/session/abandon` — sessionId, idempotencyKey → ABANDONED（终态，幂等）
- `POST /api/play/orient/session/task/{taskId}/result` — alternativeTaskType SCAN/PHOTO/QUIZ → receiptId, elapsedMs, phase
- `GET /api/play/orient/session` — sessionId → phase, status, configVersion, startedAt, elapsedMs, serverNow
- `GET /api/play/orient/session/receipt` — sessionId, idempotencyKey → 原回执 + replayed
- `POST /api/orient/safety/check` — topicId, activityId → allowed, blockers, night, insuranceRequired, sosAvailable
- `POST /api/play/arrive` — longitude, latitude, accuracy … → 拒绝时 errorCode, appealable, guardLogId, appealBizType
- ⚠️ **后端没有，提议**：`POST （audit_appeal 提交入口）` — bizType=play_arrive_guard，bizId=guardLogId；本批没有新增控制器，需确认走哪个现有申诉接口
- `GET /api/orient/task/state` — taskId → completed, completedVia, photoPendingReview, alternatives[]
- `POST /api/orient/task/alternative/submit` — alternativeTaskType, code/answer/picUrl, lng/lat, idempotencyKey → outcome, geo{distanceM,nodeGeoStatus}
- `GET /api/orient/team/detail` — teamId → team, threshold, tasks[], members[], summary
- `GET /api/orient/team/ranking` — ownerType, ownerId → rank, teamDoneCount, totalTasks, mine
- `GET /api/orient/scoring/session/{sessionId}` — totalCenti, byDimension, timeline[], disputeWindowDeadline, appeals[], disputes[]
- `POST /api/orient/scoring/session/{id}/sync` — → added, totalCenti
- `POST /api/orient/scoring/session/{id}/settle` — idempotencyKey → 终局投影 settlementId, totalCenti, pointsAwarded, xpAwarded…
- `POST /api/orient/scoring/appeal` — sessionId, entryId, nodeId, reason, idempotencyKey
- `POST /api/orient/scoring/dispute` — sessionId, reason, idempotencyKey
- `GET /api/orient/scoring/receipt` — kind SETTLE/APPEAL/DISPUTE → state PENDING/ACCEPTED/REJECTED

</details>

依据：integration-batch4.md §1/§4/§6 · ApiOrientSessionController · ApiOrientScoringController · ApiPlayProgressController#arrive · ApiOrientController

### F2 · RPG P0 · 角色卡与检定（深色 · 玩家）

开关：`journeyRules.natAuto`（天然 20 / 天然 1 自动成败）

| # | 页面 id | 页面 | 本页接口 |
|---|---|---|---|
| 1 | `f2-card` | 角色卡 | `GET /api/play/character` |
| 2 | `f2-check` | 属性检定（服务端掷骰） | `POST /api/play/check/roll`<br>`POST /api/play/check/reroll`<br>`POST /api/play/check/settle` |

<details><summary>本功能全部接口</summary>

- `GET /api/play/character` — topicId → created, running, attributes[]{stateKey,label,value,min,max,displayType,color,icon,persistScope,rules}, rpg?
- `POST /api/play/check/roll` — topicId, activityId, nodeId, checkId → dice[], kept, total, dc, success, nat, breakdown
- `POST /api/play/check/reroll` — 花 1 点幸运（sys.luck）重掷
- `POST /api/play/check/settle` — 结算：text、failCostLabel 只在这里出现，同时返回 hp/luck
- `POST /api/play/check/rest` — 力竭（exhausted）时休息

</details>

依据：integration-batch4.md · ApiPlayCharacterController · ApiPlayCheckController · ThemeCharacterService · GameplayCheckEngine

### F6 · RPG P1 · 状态与交易（深色 · 玩家）

开关：`journeyRules.rpg.bargain.enabled`（魔鬼交易）；`journeyRules.rpg.momentum.enabled`（动量）；`journeyRules.rpg.environment.enabled`（环境（天气 / 时段））

| # | 页面 id | 页面 | 本页接口 |
|---|---|---|---|
| 1 | `f6-hud` | 玩法页状态栏 | `GET /api/play/rpg/status` |
| 2 | `f6-check` | 检定 + 交易 + 动量 | `GET /api/play/nodes`<br>`POST /api/play/check/bargain`<br>`POST /api/play/check/momentum`<br>`POST /api/play/check/settle` |

<details><summary>本功能全部接口</summary>

- `GET /api/play/rpg/status` — topicId, activityId → running, hp, luck, attributes[]{key,…}, rpg{clocks,momentum,facets,aspects,environment}
- `GET /api/play/nodes` — 节点 advancedConfigJson 里的 check.bargain = {label, bonus}（代价不下发）
- `POST /api/play/check/bargain` — 掷骰前接受交易 → bargain{label,bonus,accepted}, hp, luck
- `POST /api/play/check/roll` — 不接受交易 = 直接掷骰（没有拒绝接口）
- `POST /api/play/check/momentum` — 掷骰后、结算前花动量补差 → momentum{spent,value,max}
- `POST /api/play/check/settle` — 结算；交易代价（SET/INC/ADD_TAG/ADD_CONDITION/TICK_CLOCK）在这里生效

</details>

依据：integration-batch5.md §5/§9 · ApiPlayRpgController · ApiPlayCheckController · JourneyRpg · TopicRouteRuntimeServiceImpl

### F3 · 自由探索套票（深色 · 玩家）

开关：`chengyin.free-explore.bundle.ledger-enabled`（套票台账）；`chengyin.free-explore.bundle.sale-enabled`（开售（= 台账 且 开售））；`chengyin.free-explore.bundle.reservation-enabled`（预约（K3 未定前保持关））

| # | 页面 id | 页面 | 本页接口 |
|---|---|---|---|
| 1 | `f3-catalog` | 套票目录（含未开售态） | `GET /api/free-explore/bundle/catalog` |
| 2 | `f3-checkout` | 结算（照 baoming） | `POST /api/registration（现有）`<br>`GET /api/free-explore/bundle/order/receipt` |
| 3 | `f3-wallet` | 套票钱包 | `GET /api/free-explore/bundle/order` |
| 4 | `f3-unit` | 券码 · 预约 | `POST /api/free-explore/bundle/reservation`<br>`POST /api/free-explore/bundle/reservation/{reservationId}/cancel` |
| 5 | `f3-refund` | 退款试算（只读） | `GET /api/free-explore/bundle/order/refund-quote` |
| 6 | `f3-exit` | 商家退出 · 改换或退款 | `POST /api/free-explore/bundle/exit-decision` |

<details><summary>本功能全部接口</summary>

- `GET /api/free-explore/bundle/catalog` — topicId → items[]{chapterId,name,termsMode,unitsPerHolder,stores[]{offerId,merchantName,validEnd}}, policy, saleEnabled, reservationEnabled
- `POST /api/registration（现有）` — 购票仍走现有报名下单；开售关闭时不建台账（静默返回）
- `GET /api/free-explore/bundle/order` — registrationId → bundle{order, balances[], units[], reservations[], exitCases[], policy}
- `GET /api/free-explore/bundle/order/receipt` — requestId → 下单结果未知时查回
- `GET /api/free-explore/bundle/order/refund-quote` — registrationId → 只读试算 totalValue, remainingValue, remainingRatio
- `POST /api/free-explore/bundle/reservation` — registrationId, unitId, offerId, startAt, idempotencyKey → status BOOKED
- `GET /api/free-explore/bundle/reservation/receipt` — idempotencyKey
- `POST /api/free-explore/bundle/reservation/{reservationId}/cancel` — {useWaiver} → CANCELLED / LATE_CANCELLED / WAIVED
- `POST /api/free-explore/bundle/exit-decision` — unitId, decision SWAP_CONSENTED/REFUND_UNFULFILLED, targetOfferId, idempotencyKey

</details>

依据：docs/delivery/doc2-f3-phase1-20261022/design.md · ApiFreeExploreBundleController · FreeExploreBundleServiceImpl

### F7 · 发票与未成年模式（深色 · 玩家）

开关：`feature.flag.userInvoice`（用户发票）；`feature.flag.minorMode`（未成年模式）；`feature.flag.minorPaymentGate`（未成年支付拦截）；`feature.flag.minorOrienteeringGate`（未成年定向拦截（无调用方））

| # | 页面 id | 页面 | 本页接口 |
|---|---|---|---|
| 1 | `f7-home` | 设置入口 | —（入口 / 纯展示） |
| 2 | `f7-inv` | 发票列表 | `POST /api/invoice/list` |
| 3 | `f7-inv-detail` | 发票详情 | `POST /api/invoice/detail`<br>`POST /api/invoice/cancel` |
| 4 | `f7-titles` | 发票抬头管理 | `POST /api/invoice/titles`<br>`POST /api/invoice/titles/save`<br>`POST /api/invoice/titles/delete` |
| 5 | `f7-apply` | 申请开票（合并订单） | `POST /api/invoice/orders/eligible`<br>`POST /api/invoice/apply` |
| 6 | `f7-minor` | 未成年模式 | `POST /api/minor/status`<br>`POST /api/minor/policy`<br>`POST /api/minor/guardian/list`<br>`POST /api/minor/guardian/revoke` |
| 7 | `f7-invite` | 邀请监护人 | `POST /api/minor/guardian/invite` |
| 8 | `f7-accept` | 监护人确认页 | `POST /api/minor/guardian/accept` |
| 9 | `f7-gate` | 支付 / 开局拦截示例 | `POST /api/minor/status`<br>⚠️ 提议：`（定向拦截 enforceOrienteeringIfEnabled 没有调用方）` |

<details><summary>本功能全部接口</summary>

- `POST /api/invoice/titles` — → [{id,titleType,titleName,taxNoMasked,isDefault}]
- `POST /api/invoice/titles/save` — id?, titleType PERSONAL/COMPANY, titleName, taxNo, isDefault
- `POST /api/invoice/titles/delete` — id
- `POST /api/invoice/orders/eligible` — → 已支付、未退款、未开票的订单（最多 100）
- `POST /api/invoice/apply` — requestId, titleId, orderIds[]（最多 50）→ 申请 + replayed
- `POST /api/invoice/list` — → 申请列表
- `POST /api/invoice/detail` — id → applicationNo, status, taxNoMasked, totalAmount, invoiceNo, rejectReason, orders[]
- `POST /api/invoice/cancel` — id → 撤回未开具的申请，订单释放
- `POST /api/minor/policy` — → policyVersion, daytimeStart 08:00, daytimeEnd 18:00, maxValidDays, inviteTtlHours, purposes, relations
- `POST /api/minor/status` — → ageBand, guardianRequired, paymentGateOn, orienteeringGateOn
- `POST /api/minor/guardian/invite` — requestId, purpose, relation, policyVersion, validDays → inviteToken, inviteExpiresAt
- `POST /api/minor/guardian/accept` — inviteToken, policyVersion
- `POST /api/minor/guardian/revoke` — id
- `POST /api/minor/guardian/list` — → [{role, purpose, relation, status, validFrom, validUntil…}]

</details>

依据：integration-batch4.md §4/§6 · ApiUserInvoiceController · ApiMinorGuardianController · MinorProtectionServiceImpl · survey.md §7

### F4 · 商家台（浅色 · 商家）

开关：`merchant.desk.f4.enabled`（商家台 F4（关闭时全部 404））

| # | 页面 id | 页面 | 本页接口 |
|---|---|---|---|
| 1 | `f4-home` | 工作台 · 门店台入口 | `POST /api/merchant/desk/stores`<br>`POST /api/merchant/desk/arrivals/today` |
| 2 | `f4-store` | 门店 | `POST /api/merchant/desk/stores` |
| 3 | `f4-anchor` | 到店锚点 | `POST /api/merchant/desk/anchor`<br>`POST /api/merchant/desk/anchor/save`<br>`POST /api/merchant/desk/anchor/check` |
| 4 | `f4-products` | 服务商品 | `POST /api/merchant/desk/products` |
| 5 | `f4-product` | 商品 · SKU 容量 | `POST /api/merchant/desk/products/detail`<br>`POST /api/merchant/desk/products/sku/save`<br>`POST /api/merchant/desk/products/delist` |
| 6 | `f4-slots` | 时段容量（只读） | `POST /api/merchant/desk/products/sku/slots` |
| 7 | `f4-desk` | 核销台 | `POST /api/merchant/desk/arrivals/today`<br>`POST /api/merchant/desk/redeem/resolve`<br>`POST /api/merchant/desk/redeem/ocr-candidates` |
| 8 | `f4-verdicts` | 核销复核列表 | `POST /api/merchant/desk/verdicts` |
| 9 | `f4-verdict` | 复核详情 · 补充材料 | `POST /api/merchant/desk/verdicts/detail`<br>`POST /api/merchant/desk/verdicts/evidence` |
| 10 | `f4-recon` | 对账（只读） | `POST /api/merchant/desk/reconciliation`<br>`POST /api/merchant/desk/frozen` |
| 11 | `f4-claims` | 结算明细 | `POST /api/merchant/desk/reconciliation/claims` |

<details><summary>本功能全部接口</summary>

- `POST /api/merchant/desk/stores` — → storeId, name, address, businessHours{raw,parsed[]}, locationVerified, anchorConfigured, editEndpoints
- `POST /api/merchant/desk/anchor` — → configured, anchorLat, anchorLng, radiusM, wifiCount, version, enforced=false
- `POST /api/merchant/desk/anchor/save` — requestId, anchorLat, anchorLng, radiusM 30–500, wifiBssids[], expectedVersion
- `POST /api/merchant/desk/anchor/check` — lat, lng, accuracyM, bssid → location IN_RANGE/OUT_OF_RANGE/LOCATION_UNKNOWN, wifi WIFI_MATCH/…
- `POST /api/merchant/desk/products` — → productId, productName, publishStatus（0=待审核）, price, stock
- `POST /api/merchant/desk/products/detail` — productId → 商品 + skus[] + editable
- `POST /api/merchant/desk/products/save` — requestId, productName, pic, price, stock… → 回到待审核
- `POST /api/merchant/desk/products/delist` — requestId, productId
- `POST /api/merchant/desk/products/sku/save` — requestId, skuName, bookable, slotMinutes（15 的倍数）, capacityPerSlot 0–1000, ruleVersion
- `POST /api/merchant/desk/products/sku/slots` — skuId, fromDate, days ≤14 → 只读 slotStart, capacityTotal, capacityReserved, status
- `POST /api/merchant/desk/arrivals/today` — → redeemed[], redeemedCount, openByChapter[]
- `POST /api/merchant/desk/redeem/resolve` — code, mode SCAN/MANUAL/OCR → kind, accepted, errorCode, redirectPath, form{fields[]}（不核销）
- `POST /api/merchant/desk/redeem/ocr-candidates` — text → candidates[{code,masked}]（≤5）
- `POST /api/merchant/desk/verdicts` — status 0/1/2 → reviewId, riskType, verificationRate, oneMinuteCount, evidenceCount, verdict
- `POST /api/merchant/desk/verdicts/detail` — reviewId → evidence[], canSubmitEvidence
- `POST /api/merchant/desk/verdicts/evidence` — requestId, reviewId, note ≤500, media[]（每单最多 10 次）
- `POST /api/merchant/desk/reconciliation` — → readOnly, bases[4]{route,claimCount,byStatus}, outsideFourBases[]
- `POST /api/merchant/desk/reconciliation/claims` — route, pageNum, pageSize ≤50 → rows[]
- `POST /api/merchant/desk/frozen` — → batchId, periodYm, amountTotal, holdState=FROZEN, frozenReason
- `POST /api/merchant/desk/receipt` — requestId → state APPLIED/NOT_FOUND（结果未知时查回）

</details>

依据：docs/delivery/doc2-f4-merchant-20261022/f4-backend.md · ApiMerchantDeskController · MerchantDeskServiceImpl

### 后台 · 管理后台（桌面 · 后台）

开关：`chengyin.audit.o5c.unified-trail.enabled`（统一留痕 O5c）；`chengyin.audit.item-problems.enabled`（审核逐项问题 O5a）

| # | 页面 id | 页面 | 本页接口 |
|---|---|---|---|
| 1 | `adm-appeals` | 成绩申诉 | `GET /business/orient/scoring/appeals`<br>`GET /business/orient/scoring/disputes`<br>`POST /business/orient/scoring/appeals/{id}/resolve`<br>`POST /business/orient/scoring/disputes/{id}/resolve` |
| 2 | `adm-session` | 对局流水 | `GET /business/orient/scoring/session/{id}`<br>`GET /business/orient/scoring/session/{id}/verify`<br>`POST /business/orient/scoring/entries/{id}/reverse` |
| 3 | `adm-guard` | 到达申诉 | `GET /business/audit/list`<br>`POST /business/audit/{id}/claim`<br>`GET /business/audit/{id}/detail`<br>`POST /business/audit/review` |
| 4 | `adm-verify` | 核销异常复核 | `GET /business/verificationReview/list`<br>`POST /business/verificationReview/handle`<br>⚠️ 提议：`GET （商家补充材料）` |
| 5 | `adm-invoice` | 用户发票 | ⚠️ 提议：`POST （用户发票开具 / 驳回）` |
| 6 | `adm-trail` | 统一留痕 | `GET /business/audit-trail` |

<details><summary>本功能全部接口</summary>

- `GET /business/orient/scoring/appeals` — status, limit · business:orientScoring:list
- `GET /business/orient/scoring/disputes` — status, limit
- `GET /business/orient/scoring/session/{id}` — 对局流水 breakdown
- `GET /business/orient/scoring/session/{id}/verify` — ledgerTotalCenti, projectionTotalCenti, postSettlementDeltaCenti, consistent
- `POST /business/orient/scoring/appeals/{id}/resolve` — accept, adjustCenti, note · business:orientScoring:review
- `POST /business/orient/scoring/disputes/{id}/resolve` — accept, note
- `POST /business/orient/scoring/entries/{id}/reverse` — reasonCode, idempotencyKey
- `GET /business/audit/list` — 审核工作台（到达申诉 bizType=play_arrive_guard）
- `POST /business/audit/{id}/claim` — 认领
- `GET /business/audit/{id}/detail` — appealAttachments …
- `POST /business/audit/review` — taskId, expectedVersion, status, reasonCode, problems[]
- `GET /business/verificationReview/list` — status, topicId, verifierMemberId
- `POST /business/verificationReview/handle` — id, status 1|2（只处理 status=0）
- ⚠️ **后端没有，提议**：`GET （商家补充材料）` — verification_review_evidence 后台没有读取接口；提议新增
- ⚠️ **后端没有，提议**：`POST （用户发票开具 / 驳回）` — IUserInvoiceService.markIssued / markRejected 没有控制器；提议新增
- `GET /business/audit-trail` — bizType, bizId → source, action, operatorType, reasonCode, eventTime（每源 ≤200、总 ≤500）

</details>

依据：OrientScoringAdminController · AuditWorkbenchController · VerificationReviewController · AuditTrailController · doc2-o5b-audit-truth.md · doc2-o5c.md

## 待 owner 决定的问题

### F1 · 城市定向

1. **F1a**：会话处于 PREPARED（已准备未开始）时，现有推进入口（/arrive 等）要不要拒绝？原型里「稍后再开始」会留在 PREPARED。
2. **F1a**：没有「完成本局」接口：FINISHED 由现有推进流程走到最后一站产生。最后一站完成后是否自动结算，还是让玩家点「结算成绩」？原型按自动结算画。
3. **F1a**：ORIENT_TEAM_UNSUPPORTED：会话目前不支持组队局。队伍看板（F1d）与会话（F1a）如何合并？
4. **F1b**：存量 product_type=1 主题是否随全局开关一起开始计分？
5. **F1b**：分数单位：后端以 centi（1/100 分）记账，前台显示整数分还是一位小数？原型显示整数，冲正后可能出现小数。
6. **F1b**：后台 business:orientScoring:* 权限给哪些角色？现在只有超管。
7. **F1c**：require_accuracy 要等最低小程序版本定下来才能开；版本号是多少？
8. **F1c**：到达被拦后的申诉走哪个入口（audit_appeal 已有表，但没有新控制器）？
9. **F1d**：开 safety 后没有未成年监护数据源会直接拦未成年人（与 F7 监护授权如何打通）。
10. **F1d**：照片替代任务进入人工复核（PENDING_REVIEW）期间，计时是否暂停、能否继续下一站？

### F2 · RPG P0 · 角色卡与检定

11. **F2**：属性范围的唯一真源：theme_state_key.min/max 与 journeyRules.attributes[].min/max 现在取交集，以哪个为准？
12. **C4-1**：技能绑定属性的命名空间：counter.* 还是 stat.*？属性存 theme_character（跨局）还是只存本局（目前只实现了 run）？
13. **C4-2**：情境修正 modCap 只有上限没有下限，是否改成 [-cap, cap]？
14. **C4-3**：两个掷骰入口（roll 与 rollAttempt）是否合并？
15. **F2**：结果只有成功 / 失败 + 天然 20/1，没有「部分成功」档；文案是否需要第三档？
16. **F2**：角色卡 displayType（数字 / 条 / 星）由发布者选，还是统一一种？

### F6 · RPG P1 · 状态与交易

17. **C-F6-1**：进度钟段数有两个来源（clock 配置 segments 与变量定义），以哪个为准？
18. **C-F6-2**：ENV_IN（天气 / 时段条件）用在路线边条件上会被发布闸拒绝（fail-closed）。要不要放开？
19. **C-F6-3**：交易 / 动量没有接到 attempt 路径（只在 check 路径），玩法屏里的检定要不要也支持？
20. **C-F6-4**：动量规则是简化版（失败 +1、补差最多 3）。是否需要更完整的规则？
21. **C-F6-5**：天气城市来源用的是 address_name，应在 O-14 之后改为 city_code；地址外发（N533-5）也属这一项。
22. **F6**：没有本局规则上下文的投影（模板库、商家 / 报名视角、info-to-user）一律不下发 check.bargain，预览里看不到交易，是否接受？
23. **F6**：交易代价对玩家完全隐藏（只给 label），还是给一句模糊提示？原型写「代价会在结算时揭晓」。

### F3 · 自由探索套票

24. **K1**：每章只给 1 张券（UNITS_PER_CHAPTER=1）。要不要一章多张？需要改核销唯一键。
25. **K2**：部分退款退到「平台余额」还没有资金路由，所以现在只能试算、不能提交。
26. **K3**：最关键：罚没（FORFEITED）不会置位 verification_status，整单原路全退可以绕过罚没。K3 解决前不要开 reservation-enabled。
27. **K4**：单券价值怎么算：现在是实付平均分、尾差给最后一张；还是按章节加权？
28. **K5**：有效期来源：现在取活动 end_date（没有就拒绝冻结）；还是单独配置？
29. **K6**：商家退出后由持券人发起改换 / 退款；还是在商家退出流程里自动开单？
30. **K7**：settlement_claim 的按券维度推迟到 Phase 2，需要第二次资金审批。
31. **K8**：过期不会自动改状态，只在读的时候算 expired；要不要加过期任务？
32. **F3**：开售关闭时购票不报错、只是不建台账（没有 BUNDLE_SALE_OFF 码）。前台是否需要明确的「暂未开售」拦截？原型按目录页 saleEnabled=false 直接置灰。

### F7 · 发票与未成年模式

33. **F7**：开 minorMode 之前必须改用户协议第十条（现在写「暂不面向未成年人…未满十八周岁请勿注册」），release-legal-gate.js 也要同步改。
34. **C7-1**：未成年策略：A 维持「不面向未成年」+ 注册时 18 岁确认；B 按本原型开放未成年模式。
35. **C7-2**：是否接电子发票服务？现在 provider=MANUAL：后台手工开、只回填发票号，没有可下载的文件。
36. **F7**：后台没有处理用户发票的接口（markIssued / markRejected 存在但没有控制器调用），后台页面是提议。
37. **F7**：年龄段来自出生日期：个人资料里还没有出生日期填写入口（MINOR_AGE_UNKNOWN 会让用户去填）。入口放哪？
38. **F7**：定向拦截 enforceOrienteeringIfEnabled 没有调用方；F1d 的 safety/check 是否改为调用它？
39. **F7**：监护关系只用「双方账号互相确认」（MUTUAL_ACCOUNT_CONFIRM）作证据，是否足够？

### F4 · 商家台

40. **C-F4-1**：一个商家多门店？现在只返回 1 个门店；方案 B 是新建 mms_store 表。
41. **C-F4-2**：服务商品复用积分商城表 pms_product，还是另建服务商品表？
42. **C-F4-3**：到店统计只到章节粒度，等预约模型（C5-1）定了再细化。
43. **C-F4-4**：CLUB_DIRECT（俱乐部直收）要不要成为第五种结算基础？
44. **C-F4-5**：没有 claim 的应付行怎么展示 / 处理？
45. **C-F4-6**：到店锚点现在只是配置（enforced=false）。要不要接到核销拦截（先 shadow 再 enforce）？
46. **F4**：还没做：预约占用 / 释放、时段自动生成、打款与应付计提、发票上传、CSV 导出。时段页只读、可能为空。
47. **F4**：核销台只「识别」不「核销」：识别后跳到现有核销页面（redirectPath）。是否要在同一页完成？

### 后台 · 管理后台

48. **后台**：F1 成绩申诉、用户发票、统一留痕都没有现成的 Vue 页面；本原型按 RuoYi 列表 + 弹窗画，是否放在「业务管理」菜单下？
49. **F1b**：business:orientScoring:list / review 分给哪些角色？
50. **F4**：后台看不到商家补充的复核材料（只有商家端读写 verification_review_evidence），是否新增后台读取接口？
51. **F7**：用户发票后台处理接口需要新增（markIssued / markRejected 已有服务方法）。
52. **O5c**：「完整留痕」做成审核详情里的抽屉（原型做法），还是单独页面？
53. **O5b**：审核状态的唯一真源用哪张表；审核报表读 A 还是 B；P2 开关粒度；D-7 下架原因码取值。
54. **O5a**：beta_flag 由谁设置：A 首次发布即进 Beta / B 首次人工过审进 Beta / C 退役 Beta。

## 验证

- 用 Playwright（预装 Chromium）逐一打开 45 个页面：控制台没有报错，也没有出现「渲染出错」。
- 走通的点击流程：
  - F1：开局 → 确认规则 → 5 站到达，其中一站被速度守卫拦截后申诉 → 答题替代 → 完赛结算 → 成绩争议 → 后台通过申诉并追加调整；
  - F6：接受交易 → 掷骰失败 → 花动量补差 → 结算；
  - F3：购买 → 预约；
  - F7：合并开票、税号校验；
  - F4：核销台识别。
- 在 390×844 窄屏下检查过全屏显示和侧栏抽屉。
