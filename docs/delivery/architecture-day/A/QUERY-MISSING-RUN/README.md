# H-AE-01：共享 RUN 缺失不能回退个人局

基于冻结候选 `062c6cb71ffa0ab6b005ee622e8a2045e4c496cd` 的独立微增量。覆盖原交付中“共享 RUN 缺失返回 null”的约定：null 只表示没有适用共享局；已确认共享且完成 topic/活动/当前有效报名与队伍校验后，缺 RUN 抛既有 `GameSessionException(GAME_CONFIG_INVALID)`，不给 E queryState 回退旧个人 ACTIVE 局的机会。无新增结果类型、无第二次 exists 查询、无补建/计时/写账；HTTP 错误映射沿现有契约，未改控制器。

对应已有 query 测试改为明确失败，全部原 actor/scope 和普通读次数/无额外交互断言保留；用例仍 9 项。实际仅运行 Python 字节级分支/原断言保留核对和 `git diff --check`。Java/JUnit/MySQL/CI 未运行，也不冒充 E 组合验收。此前 062c 静态回执只描述其原候选，此微增量不重复旧验收。
