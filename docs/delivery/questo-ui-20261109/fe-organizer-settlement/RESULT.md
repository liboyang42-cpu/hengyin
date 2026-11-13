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

---

## 2026-11-13 落地（owner 已拍板，范围仅限本条）

owner 决定：商家发布的自由探索（`topic.club_id` 空，场次由承办俱乐部开），票款归发布商家（`topic.member_id`），承办俱乐部与合作店铺与商家线下结算；平台费不变（冻结 `fee_platform_rate`，3%）；承办俱乐部按核销付给店铺的核销费（event_type=95）不变；俱乐部发布的主题仍付俱乐部主理人。

### Task A 结算（后端）
- `CmsActivityServiceImpl`：新增 `isMerchantPublishedSession`（自由探索 + topic.club_id 空 + topic.member_id>0 + activity.club_id>0）；`requireSessionSettlementContract` 在此情形放行（冻结开售合同 / 四店专用合同检查不变）；新增 `activityPayee(activity, topic)`，该情形收款人 = `topic.memberId`，其余情形沿用原 `activityPayee`；`settleActivity`、`recordFreeExploreSessionLedger`、`releaseHeldRegistration` 三处改用；`selectActivitiesForSettlement()` 并入新选单；`strandedReason` 对已可结算的商家场次返回空（不再告警）。
- 新增 mapper 语句 `selectMerchantPublishedFreeExploreActivitiesForSettlement`（`CmsActivityMapper.xml` / `.java`），时点、退款闸与原选单逐字同源；未改任何既有 SQL 文本。
- 未动：`settleTopic` 的 `FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN`（非场次路径继续禁止）、`SettlementEntryServiceImpl`（收款人本就是参数）、退款冲回（按实际入账行）、幂等 `claimActivityForSettlement`、核销费。无迁移。
- 测试：新增 `MerchantPublishedFreeExploreSettlementTest`（商家发布→商家收 97.00/平台 3.00；俱乐部发布→主理人；无承办俱乐部场次仍拒；无发布者仍拒；选单合并去重）。`CoopSettlementTopicFenceTest` 原有的旧路径禁止用例保持。

### Task B 文案
- 后端 `MerchantFinanceQueryService.perkNoCashReason`：「票款由主办方收取；店铺如需结算，请联系主办方线下处理 · 主办方：名称」（俱乐部主题取俱乐部名，商家主题取发布商家名）。
- `pages/coop/withdraw`：店铺的核销记录全部是该句时，页内副文改为同一句。
- `pages/topic/merchantinfo/merchantinfo.wxml`：自由探索 PERK 邀请在「接受邀请」前加同一句。
- 未做：主办方收益页「合作店铺线下结算」一行——结算详情/各主题结算数据里没有主题类型，硬写会对非全权益主题说错；需要后端先给标记，留待后续。「联系主办方」入口沿用现有，未新增。
- 已查：全仓没有「商家主题不能结算」的前端文案（`fabu/index.js:8376`、`topic/pricing/index.js:64` 指的是自玩票，与本决定无关）。

### 已知后续
- 主理人「待结算资金」读侧 (`selectUnsettledActivityFundsByMember`) 仍按场次发起人且排除商家发布场次，发布商家暂看不到这些场次的待结算；入账到余额本身不受影响。
- 俱乐部结算页 `selectClubSessionSettlements` 按 `a.club_id` 列场次，商家发布场次的实际收款人是商家，该页对俱乐部主理人的展示口径需另行确认。

### 验证
- 本地不跑 Java（规则）；后端单测与 Funds MySQL IT 由 PR 的 CI 执行。

### CI
- PR：https://github.com/liboyang42-cpu/hengyin/pull/816 （CI 与 Funds MySQL IT 结果以该 PR 的检查为准；本地仅跑了 node 门禁：动作台账 --write/--check 6025 控件、ui-integrity 通过、相关 xcx 单测通过）。

STATUS: PARTIAL - 后端结算与三处文案已落地，Java 单测/Funds MySQL IT 待 PR CI 验证；主办方收益页「线下结算」一行因缺主题类型数据未做。

## Rebase 1113

PR #816 与 master 冲突，已把 origin/master 合并进分支（未改历史、未强推），解决后 CI 全绿。

- 冲突：仅 `CmsActivityServiceImpl.java` 暂扣补结段。保留本 PR 的冻结主题读取与 `requireSessionSettlementContract`，同时保留 master 的个人主体（`clubPrincipalPayee`）分支；未改动其它资金逻辑。
- `action-ledger.json`：取 master 版本后用 `build-action-ledger.js --write` 重生成，`--check` 通过（6034 controls）；未手改摘要。
- `route-matrix.json`：提现页新增登录接口请求，`pages/coop/withdraw/index` 的 `needsLogin` 变为 true；用 `build-route-matrix.js --json` 重生成，`--check` 通过（master 上同检查为 PASS，故由本 PR 引入）。
- 测试夹具（均为本 PR 新增查询／路径引起，非 master 既有失败）：
  - `ComplaintPeriodWithdrawalGateMybatisIntegrationTest` 的 `cms_topic` 补 `member_id` 列（新选单按发布商家过滤）。
  - `MerchantPublishedFreeExploreSettlementTest` 补 `ClubSessionMerchantFee` 的 `coopOrderMapper`、`topicSaleSnapshotMapper`，并让 `selectExploreSessionTopicId` 返回 null（Mockito 对 Long 默认 0 会误入探索场次分支）。
- 未改 `ci/architecture/*`、工作流；未运行 `xcx-check.sh` selftest；本地未跑 Java。

检查结果（head `e45d6aba`，35 项：成功 29，按设计 skipped 3，其余为同一汇总）：
- Backend tests、Funds MySQL IT (50/9/30)、Funds IT gate self-test：success
- Mini program unit shard 1–20/20、static gates、static checks、Fast gates：success
- Migration layout / safety gate、Secret scan (gitleaks)、Detect changed areas：success
- Followup 39 selected backend cases、isolated-authenticated-redis-recovery：success
- SBOM、Admin UI build / E2E gate：skipped（未触及 UI 管理端，工作流自身判定）

STATUS: DONE
