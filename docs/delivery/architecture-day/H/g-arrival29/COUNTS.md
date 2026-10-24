# ThemeRuntime 计数补记（不替代运行证据）

本说明补充 `29c9fc90233c01fe6ba4efc93d008e33727bd44c` 的 H 审查；原报告与静态索引保留。原文 34→36→39 指普通 `@Test` 声明数，不能作为完整 JUnit 执行数。

H 另行比较 4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa、负控 acc4bf5ea10bbefabb9d5db52dd17dc800b3a955、修复 29c9 的 ThemeRuntimeMySqlIT：6 个参数化方法的 ValueSource 注解与完整方法体逐字相同，输入数分别 7/2/3/5/4/3，总计 24，未删除或改弱。

| 源码阶段 | 普通 @Test | 参数化输入 | 预期总调用数 |
| --- | --- | --- | --- |
| 原源 4cf548c3 | 34 | 24 | 58 |
| 负控 acc4bf5e | 36 | 24 | 60 |
| 修复 29c9fc90 | 39 | 24 | 63 |

6 个方法：`r5PredictActualRoundSettlementWaitsForRevealAndUsesOriginalAttempt`、`r5TypeInActualFrozenTimerOverridesLegacySeconds`、`r5ActualCompletionSaveUsesOnePendingConfirmationAndReplaysNoSecondFee`、`r5PhotoAlbumAndStepsPublishFreezeAndConfirmTheirRealSave`、`photoOriginalResultSurvivesLocalRollbackAndSameCommandAppliesOnce`、`r5FeedbackStartsOnlyAfterSettlementAndNeverResettlesTheMainResult`。

本轮新增执行仅为 Python 静态计数与方法体比较。Java/数据库/CI 未执行，58/60/63 均是源码预期而非实际 PASS。仍须由 root 按同一不可变候选原 XML 核验参数化 invocation、失败/错误/跳过与清理证据；原保护断言不得为求绿删除。无新 P1/P2，原审查对外层事务、真实竞争、切局边界的验证缺口保持。
