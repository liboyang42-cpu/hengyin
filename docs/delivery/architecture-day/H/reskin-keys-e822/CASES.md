# 原方法与参数保留清单（源码身份，不是运行报告）

相对 parent e822，原方法与原断言均保留；owned.review.patch 没有删除/改宽任何原 assertion。新增断言并入原场景，不新增或删减运行输入。

## GameplayReskinRuntimeServiceTest：7

1. allThreeConfigurationsSavePublishAndFreezeThroughTheSameServices
2. templateFeesAndRationsUseTheBoundResourceAndBusinessReplayIsFree
3. zeroHpUsesTheSameTeamChallengeOrQuestionAlternativeAfterReskin
4. allThreeThemesFollowTheSameScriptToAnExecutedWorldEnding
5. configuredActionCeilingRejectsTheNextPaidTaskWithoutChangingResources
6. allThreePublishedThemesCanExplicitlyCloseToTheirConfiguredFallbackWithoutSpending
7. aFrozenWorldEndingDisablesAnOutstandingChallengeAndRejectsEveryNewProof

三主题循环、zeroHP 的原双分支、原金额/资源/授权/证明/回放/冻结世界结局断言均保留。

## GameplaySupportMatrixRuntimeTest：8 输入

- everyProofTypeSavesPublishesAndChargesTheBoundTeamResource：原 `scan, qaText, qaPick, sort, match, classify` 六个参数值和顺序不变。
- publishRejectsUnsupportedOrdinarySensorWithNodeTypeAndTool：仍断言 IllegalArgumentException 及节点/玩法/工具消息。
- legacyChallengeAnswerWithoutAdvancedProofCannotPublishAndFixedReferenceCanBeJudged：仍断言 IllegalArgumentException/恢复问答/zeroChallenge，并用真实判定区分错误与正确口令。

## GameplayDraftCopyTest：4

1. firstSaveRegeneratesRealQuestsWithoutChangingActOrSurvivalIdentities
2. unmatchedEmptyActStaysInDraftButCannotFreeze
3. nativeThreeThemeCopiesSaveWithRealNodeMappingsAndRequireChallengeReselectionForPublish
4. clientSceneKeysAreOnlyAcceptedForCreatedNodesAndNeverByPublishParser

第三项保留三主题真实节点映射、再次保存不漂移、未重选完成证明须 IllegalArgumentException、重选本主题挑战后实际 freeze 非空全部原断言。其余方法未变。

## 本轮执行

测试 / parse / 编译 / Java / DB / CI / 外部渠道：**NOT_RUN**。此列表由原源码及 delta 手工核对，不代表修后 19 或原失败 16 已通过。
