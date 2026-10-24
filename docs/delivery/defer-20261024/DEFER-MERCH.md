# DEFER-MERCH 非阻塞项按优先级修复（2026-10-24）

范围：商家看板、经营分析、到店核销。基线：`origin/master` @ `05c91471`，分支 `chore/defer-merch-20261024`，PR #587（draft）。
来源：`docs/delivery/bugfix-20261023/BUG-MERCH.md` 中 DEFER 行。按优先级自上而下处理，一项一提交。

## 汇总

| 状态 | 数量 |
|---|---|
| FIXED | 9 |
| PARTIAL | 2 |
| ALREADY_FIXED | 0 |
| NEEDS_OWNER | 1 |


## 明细

| id | priority | status | files | test | notes |
|---|---|---|---|---|---|
| A:P0-37 | P0 | FIXED | `MerchantInsightMapper.xml/.java`、`MerchantInsightServiceImpl.java`、`MerchantMetricDictionary.java` | `MerchantInsightServiceImplTest.dashboardComposesExposureCheckinAndSupply_withoutAi`（新断言）、`MerchantInsightMapperSqlTest.exposureVisitorsIsDistinctMemberCountOverSameScopeAsPv`、`MerchantMetricDictionaryTest` | `insight.exposure` 保持 PV 不变；新增 `exposureUv`（按会员去重）与 `exposureSemantics="PV"`；指标字典新增 `insight.exposureUv`，VERSION 1→2。只加字段，无前端读方 |
| A:P0-45 | P0 | FIXED | 新增 `MerchantMetricWindow.java`；`ApiMerchantController.java`、`MerchantInsightServiceImpl.java` | `ApiMerchantLedgerControllerTest`（windows 断言：店主三块 / 店长仅 insight） | `/dashboard` 新增 `windows` 对象，只声明实际下发的块：revenue=ALL_TIME、revenue7d=CALENDAR_7D_INCL_TODAY、insight=ROLLING_30D；参谋 30 天常量改引用同一枚举 |
| A:P0-46 | P0 | FIXED | `UmsMemberBalanceDetailMapper.xml/.java`、`IUmsMemberBalanceDetailService.java`、`UmsMemberBalanceDetailServiceImpl.java`、`ApiMerchantController.java` | `UmsMemberBalanceDetailDashboardSqlTest`（新）、`ApiMerchantLedgerControllerTest.dashboardRevenueAndSevenDayTrendComeFromSqlAggregatesWithMissingDaysZeroFilled`（新）、`ApiMerchantOperatorReadScopeTest` | 累计收入改库内 SUM，近 7 日改库内按自然日 GROUP BY；口径不变（change_type=1，**不**额外过滤 del_flag，与原 Java 求和一致），两位小数 HALF_UP 不变。分桶日期依赖连接 `serverTimezone=GMT+8` 与 JVM `Asia/Shanghai` 一致（现状如此） |
| A:P0-47 | P0 | FIXED | `ApiMerchantController.java` | `ApiMerchantLedgerControllerTest.dashboardInsightFailureIsReportedAsErrorNotSilentlyMissing`（新）、`ApiMerchantOperatorReadScopeTest`（FORBIDDEN 断言） | 新增 `insightStatus` 三态 OK / FORBIDDEN / ERROR；失败仍不拖垮收入/订单面板；进程内失败计数随 warn 日志输出 |
| B:P1-008 | P1 | FIXED | `MerchantInsightSupplyFact.java`、`MerchantInsightMapper.xml`、`MerchantInsightServiceImpl.java` | `MerchantInsightServiceImplTest.supplyTopicsAreKeyedByTopicIdNotName`（新）、`MerchantInsightMapperSqlTest.supplyFactsSelectTopicIdAsAggregationKey`（新） | 供给事实带出 `o.topic_id`；`topics` 按主题 id 去重（同名不同主题各算一个，同一主题多章只出一次）。只影响喂给参谋 AI 的主题名列表 |
| B:P1-010 | P1 | NEEDS_OWNER | — | — | 缺口是把 `migration_merchant_operator_aftercare_20260823.sql` 里的 PREPARE 动态建表改成静态 `CREATE TABLE IF NOT EXISTS`，只能通过修改既有迁移做到，规则禁止。新迁移改不了旧迁移的写法；现有迁移已有存在性/形状断言，且可安全重跑 |
| B:P1-015 | P1 | PARTIAL | —（本 PR 无代码改动） | 已有：`verification-readback`/`merchant-verification`/`verification-scan` 相关单测 | 商家域的核销写入口（citynode 核销、game-node、商家首页）都已走共享 helper 和 `createVerificationWorkflow` 持久写闸；ledger/aftercare/verify-records 只读列表，不做核销写入。剩余发码入口在 club/roam/components（其他 lane），统一成 issue/redeem/checkEligibility 门面属于跨 lane 的接口设计，本 lane 未做 |
| B:P1-020 | P1 | FIXED | `CmsRegistrationMapper.xml/.java`、`ICmsRegistrationService.java`、`CmsRegistrationServiceImpl.java`、`ApiMerchantController.java` | `CmsRegistrationVerificationCountTest`（新）、`ApiMerchantAwardedTopicStatsTest.todoSummaryCountsVerificationWithOneGroupedQueryPerOwnerType`（新） | todo-summary 待核销/已核销改为每种 owner_type 一次 `group by (owner_id, verification_status)` 计数，口径同原 `selectCmsRegistrationList(...).size()`；无项目时不查询。逐主题 `selectCmsTopicById`（结束时间过滤）保持不变 |
| B:P1-027 | P1 | FIXED | `ApiClubSettlementController.java` | `ApiClubSettlementControllerTest.summaryTopicRowsCarryTopicIdSoFinanceDetailLinkResolves`（新断言） | 删除恒 null 的 `pendingAdjustment` 占位字段；前端已有合同测试锁定不读它（`club-settlement-page-contract.test.js` 通过）。该测试 :133 注释仍写「后端恒下发 null」，现已过时，未改（测试文件不在本 lane） |
| C:U-077 | P1 | FIXED | `chengyinhub-xcx/utils/analytics.js` | `tests/unit/analytics-privacy.test.js`（新用例） | 过滤规则不变（隐私方向仍 fail-closed）；开发版对每个被丢弃的敏感键 `console.warn` 一次，体验版/正式版静默。helper 放在文件尾，避免 UI 门 U5 基线行号漂移（未改基线） |
| C:U-139 | P1 | FIXED | `ImConversation.java` | —（仅注释） | `type` 字段注释补上 4=群聊（与 `TYPE_GROUP` 一致）；`ImMessage.msgType` 注释原本就含 4 投票卡片。库表列注释在既有 `base_schema_20260818.sql` 里，不改既有 SQL；只为改注释新增 ALTER 迁移不划算 |
| C:U-081 | P2 | PARTIAL | —（本 PR 无代码改动） | 已有：`NodePublicVisibilityPredicateContractTest` | 6 处「主办方节点或已过审 + 退出供给排除」谓词已由合同测试锁定一致（每处必须带同别名的退出排除，且至少 6 处）。抽成跨 mapper 的 `<sql>` 片段要改写这条门禁、改动 Game/PlayerProgress 等其他 lane 的 mapper，本 lane 未做 |

## 约束核对

- 无 wxml/wxss 改动，无 UI 视觉变化；未改 C00 保留文件；action ledger 未改。
- 无新依赖、无新迁移、未改既有 SQL；未改鉴权/RBAC/JWT/Redis 配置；owner/scope 校验未动（看板/待办仍按 `access.getOwnerMemberId()` / 权限位取数）。
- 门禁未放宽：未改基线、白名单或断言。
- 本 PR 的 API 变化全部是新增字段（`exposureUv`、`exposureSemantics`、`windows`、`insightStatus`），唯一删除的是无读方、恒为 null 的 `pendingAdjustment`。

## 验证

- 本地（LANG=C.UTF-8）：`mvn -pl chengyinhub-admin test -Dtest=MerchantInsightMapperSqlTest,MerchantInsightServiceImplTest,MerchantMetricDictionaryTest,UmsMemberBalanceDetailDashboardSqlTest,ApiMerchantLedgerControllerTest,ApiMerchantOperatorReadScopeTest,ApiMerchantAwardedTopicStatsTest,CmsRegistrationVerificationCountTest,ApiClubSettlementControllerTest` 全部通过；合并 master（`3937086e`）后在合并树上重跑 `MerchantInsight*`、`ApiMerchant*`、`NodePublicVisibilityPredicateContractTest` 等 244 例，0 失败。
- 小程序：`npm run lint` PASS；`node --test` analytics-privacy / analytics / analytics-whitelist-crossstack / club-settlement-page-contract / funds-stages-owner-only-contract 全部通过。
- CI run ID：见 PR #587（完成后补录）。
