# fe-organizer-settlement 结果（2026-11-13）

owner 决定：全 PERK 的自由探索主题，平台不分账，全部票款归主办方（发布主题的俱乐部/商家），店铺如需分成与主办方线下结算。

## Step 1 核验：钱的路径（未改任何结算代码）

### (a) 店铺的 PERK 核销没有平台打款 —— 成立
- `PerkLedgerServiceImpl.java:389-478` `redeemChapter`：只做 entitlement CAS、`consumeQuota`（`usesQuota` 仅 PERK，`:437-441`）、插 `redemption` 流水（`:458-471`）、套票消耗（`:474-476`），**不写任何应付/余额/分账行**。
- 支付阶段也不入账：`SettlementEntryServiceImpl.java:77-80` 探索票 `recordOnPayment` 返回 0；`:191-194` `recordPaidRegistration` 对探索票直接抛错（防整单入账）。
- 商家财务读侧：`MerchantFinanceQueryService.java:579-585` PERK → `NONE / NOT_APPLICABLE / 权益已履约 · 无现金结算`。
- 开售终检：`TopicSaleFinalizationServiceImpl.java:424-436` `assertShopDayTermsArePerk` 要求章节与 offer 均为 PERK。

### (b) 票款记入主办方 —— **仅对俱乐部发布成立；商家发布不成立**
- 俱乐部自有自由探索：`CmsActivityServiceImpl.java:306-320` `settleActivity` → `recordFreeExploreSessionLedger`（`:509-526`）把收款人设为 `activityPayee`（`:492-497`，俱乐部场次 = 当前主理人），分账行由 `SettlementEntryServiceImpl.java:185-209` `recordClubFreeExploreSessionSettlement` 记入；平台费 = 下单冻结的 `fee_platform_rate`（全局 activity 规则，`:203-208`），平台份额记入 `platformMemberId`（`CmsActivityServiceImpl.java:~460`）。
- 商家发布的自由探索（`topic.club_id` 为空）：`CmsActivityServiceImpl.java:528-541` `requireSessionSettlementContract` 要求 `topic.getClubId() > 0` 且等于场次俱乐部，否则抛 `FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN`；`CmsTopicServiceImpl.java:703-706` `settleTopic` 对 FREE_EXPLORE 也一律抛同一错。即商家发布的主题**目前没有任何结算出口**，票款不会记入商家主办方。
- 另：俱乐部场次若有合作单，`ClubSessionMerchantFee`（`event_type=95`，`CmsActivityServiceImpl.java:~480`）会从主办方份额内向合作商家付「核销费」；它读合作单，不读 PERK offer，需 owner 确认全 PERK 主题上不应出现合作单费用。

### 结论
(b) 不全成立，按任务要求**停止编码**：钱的逻辑属 owner 决策，未改任何代码、未改文案，避免在店铺端写出"票款由主办方收取"而商家主办方实际收不到。

## 需要 owner 决定
1. 商家发布的自由探索，票款是否也走 `settleActivity` 路径、收款人为商家本人？（需放开 `requireSessionSettlementContract` 的 `club_id` 要求，并明确是否仍必须由 1 家俱乐部承办——前端 `fabu/index.js:7382-7383` 写明"必须先邀 1 家俱乐部承办才能开售"，此时票款归商家还是承办俱乐部？）
2. 平台费是否仍按 `fee_platform_rate` 收取（现状：是）。
3. 全 PERK 主题上，承办俱乐部合作单的商家核销费是否应关闭。

解决 1 后，Step 2 的文案可直接落地：
- 后端原因串 `MerchantFinanceQueryService.java:582` 改为带主办方说明；主办方名取 topic 发布者/俱乐部名；联系入口沿用现有俱乐部/商家消息入口。
- 店铺只有 PERK 核销时 `pages/merchant` 提现入口展示线下找主办方指引。
- 主办方收益页与商家承接条款确认页各加一行"合作店铺如有结算，由主办方与店铺线下处理，不经平台"。

## Step 3：商家 vs 俱乐部发布自由探索（仅调研）

| 项 | 商家 | 俱乐部 |
|---|---|---|
| 入口 | `pages/merchant/marketing/index.js:21` → `/pages/publish/fabu/index?mode=2&scope=MERCHANT` | `pages/club/detail/index.js:1734,2851` → `/pages/publish/fabu/index?clubId=<id>&mode=<m>`；另 `pages/club/event-ops/index.js:489` |
| 同一编辑器 | `pages/publish/fabu/index.js`，`operationScope='MERCHANT'`（`:3306`） | `_lockClub`：clubId 入口锁定归属（`:3301,3308`） |
| 归属/收款 | `club_id` 为空；`topic.memberId` 为商家 | `formData.clubId`（`:505`）；收款人 = 俱乐部主理人（`CmsActivityServiceImpl.java:492-497`） |
| 承接开关 | 俱乐部承接为固定项，文案"必须先邀 1 家俱乐部承办才能开售"（`:7382-7383`）；商家承接档位仅 权益 / 引流（`:7385`） | 商家承接档位 权益 / 计酬 / 引流（`:7385`）；REVSHARE 仅 clubId>0 且 productType=2（`:4635,4759`） |
| 必填 | 招募截止（`CmsTopicServiceImpl.java:2800-2806` 自由探索必填）；每必选章至少 1 家商家承接（`TopicSaleFinalizationServiceImpl.java:~400` `assertRequiredChapterCoverage`） | 同左；`clubId` 须为发布者名下俱乐部（`TopicSaleFinalizationServiceImpl.java:~512` `publishingClubOwnsTopic`） |
| 开售 | 开售前须有冻结阵容的 ORGANIZER 合作俱乐部（`assertOrganizer` `:458-473`，自由探索至多 1 个）；场次由该俱乐部开（`ClubSessionServiceImpl.java:164-170` 须有生效合作单） | 俱乐部自有主题可直接开场次（`clubOwned`） |
| 结算 | 目前被 `FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN` 拦下（见上） | 场次结算给主理人，扣平台费 |

模板工作须区分：商家主题的"主办方"（发布者）与"承办俱乐部"（合作单 ORGANIZER）是两个主体，俱乐部主题两者合一。

## 验证
未改代码，未新增测试，未重建行动台账（无变更）。仅读源码取证（文件:行号见上）。

STATUS: PARTIAL - Step 1(b) 对商家发布不成立（结算被禁），按指令停止编码并交 owner 决策；Step 2 文案/提现指引未改，Step 3 已完成。
