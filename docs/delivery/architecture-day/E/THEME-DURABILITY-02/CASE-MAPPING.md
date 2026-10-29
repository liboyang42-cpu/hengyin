# v1 → v2 用例迁移矩阵（已编写，未执行）

原测试锚点：公共 `e683b5a0ebc44cc217ac75e378c271fd96c80ae0` 的 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiThemeDraftRequestServiceTest.java`，blob `cb2b5fae723d855a5919613dd4c5c98c4b7ac634`，原 11 条。原 Controller 6 条由 root 独占转换，不在 E delta 内。旧 17 条的历史回执不作为 v2 证据。

协调者明确裁决：退休“旧 submit 可以执行”的预期，立即 400/PREPARE_REQUIRED；不复制旧执行代码维持绿，不保留 Redis-only 旁路。保留全部必要业务边界，迁入新协议。下表是代码对应关系，不是通过记录。

| 原 Service case | v2 unit（AiThemeDraftIntentServiceTest） | 真 MySQL+Redis（AiThemeDraftIntentMySqlRedisIT） / legacy read |
| --- | --- | --- |
| replayReturnsFirstDraftWithoutCallingModelAgain | samePrepareKeyKeepsOriginalPlanAndFirstResult | successSurvivesCacheLossAndRequestReplay；concurrentPrepareCommitsOneOriginalIdentityWithoutEconomicEffects |
| readIsOwnerScoped | nonOwnerCannotReadClaimOrDiscoverIntent | prepareAndOwnerRefusalsHaveNoQuotaReviewOrModelEffects；legacyOwnerIsolationAndMissingRecordsAreUnknown |
| knownFailureIsTerminalAndNotRetriedUnderSameRequestId | knownFailureRemainsTerminalUnderOriginalIdentity | typedNotSentRefundsOriginalPlanOnceButKeepsDurableFailedIdentity；ordinaryInputFailureAndInvalidDraftRemainFailedAndNeverRegenerate |
| inFlightIsProcessingThenUnknownAfterLeaseNeverNotGenerated | concurrentLoserOnlyReadsAndExpiredClaimNeverReexecutes | concurrentClaimRunsRealCreatorOnceAndLoserOnlySeesInFlight；legacyProcessingExpiresToUnknownNeverResent |
| storageFailureBeforeClaimNeverCallsModel | prepareStorageFailureNeverExecutes；dbFailureAndAmbiguousClaimCommitNeverStartCreator | bodyWriteFailureKeepsOriginalPrepareAndCommittedClaimAckLossNeverExecutes；legacyRedisFailureIsNotMissingState |
| lostTerminalWriteDegradesToUnknownNotNotFound | failedTerminalCommitKeepsOriginalClaimWithoutResend；resultBodyLossKeepsKnownSuccessWithoutRegeneration | committedOutcomeLostReplyAndFailedResultCacheKeepOriginalKnownFact；successSurvivesCacheLossAndRequestReplay |
| requestIdFormatIsStrict | legacyMissingAndForgedIdsCannotExecute | prepareAndOwnerRefusalsHaveNoQuotaReviewOrModelEffects；原名 legacy UUID 格式断言保留 |
| providerUnknownKeepsOriginalRequestUnknownOnSubmitReadAndReplay | typedProviderUnknownKeepsOriginalIdentity | typedUnknownSurvivesBodyDeletionAndRestartWithoutRefundOrResend |
| genericInputUnknownKeepsOriginalFailedClassification | genericInputUnknownRemainsFailed | ordinaryInputFailureAndInvalidDraftRemainFailedAndNeverRegenerate（真实 Creator/Gateway 的输入边界失败） |
| wrappedProviderUnknownAndLookalikeMessagesDoNotExpandTheTypedRule | wrappedUnknownDoesNotExpandTypedRule | 精确 subtype 的分类负控在 unit；IT 不把 model 已 dispatch 的普通异常冒充输入阶段 generic，因为真实 Gateway 会按合同转成专用 UNKNOWN |
| invalidDraftFailureKeepsExistingFailureAndSameRequestDoesNotRegenerate | invalidDraftKeepsFailedIdentity | ordinaryInputFailureAndInvalidDraftRemainFailedAndNeverRegenerate（真实解析/无效稿路径） |

legacy 类现有 7 条均为纯读取或拒绝入口：legacySubmitRefusesOldOrMissingIdWithoutDependencyAccess、legacyKnownSuccessIsReadWithoutGenerating、legacyOwnerIsolationAndMissingRecordsAreUnknown、legacyKnownFailureReadPreservesOriginalReason、legacyProcessingExpiresToUnknownNeverResent、legacyRedisFailureIsNotMissingState、requestIdFormatIsStrict。数量减少是旧执行预期正式退休并迁移，不是 skip。

新增 v2 其余边界：default-off/read 可用、prepareKey 无回包回读、payload 的 null/空数组/顺序/空白/productType/防御性复制、context tamper 拒绝、原 day 过期、角色/配置变化、固定 24h 与 SUCCEEDED 过期、清理总 key 预算/精确 key/元数据保留、gen/player 原计划不双扣、RR cached-null 后 current locking read、schema 全局 CHECK 冲突、DDL 后验失败不被重跑隐藏、NULL CHECK 绕过拒绝。

H235 后续 owner 冷恢复最终合同（先前 pending 方案未交付，已替换）：新增 unit `readRecoveryHasFixedOwnerCursorAndNoRedisOrCreatorEffects`、`recoveryPaginationKeepsDescendingLookaheadAndOwnerIsolation`、`recoveryNonPositiveCursorAndDatabaseFailureNeverBecomeEmptySuccess`；新增真库 `recoveryFindsLostSuccessfulReplyWithDeadlineAndDescendingOwnerPages`、`recoveryMapperExplainUsesOwnerIdRangeAndFixedNewestLookahead`、`recoveryNonPositiveCursorAndRealDatabaseFailureAreNotEmptyResults`。覆盖实际成功回包/本地身份丢失后仅凭owner找回原id/resultExpiresAt，原GET取回原结果；全状态/过期事实、降序20+1分页、owner隔离、零Redis/外部/状态副作用、真实SQL EXPLAIN及Unknown database 1049错误（均待执行）。

当前精确声明数：legacy unit **7**、v2 unit **24**、真库 IT **19**，共 **50**；没有 Disabled/Assumptions/环境跳过。由原44新增6条，未撤退任何主 v2 边界。所有 50 条和 Java 编译均未在 E 环境运行；不得据静态数把初始化失败或未执行算成业务通过。root 注册并同源编译后逐 XML/casename/receipt/cleanup 核对。
