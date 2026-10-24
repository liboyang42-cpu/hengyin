# Spring装配两项定点修补

Parent `d19276f56b3eb2a34ed8fab06e41ea8ea654a0d0`；独立分支 `codex/architecture-day-f-managed-cycle-fixture-d192`。只改SupportedStackServiceCyclesTest，不改生产、policy、workflow、注册表或依赖。

| 根提供的ca0e失败 | d192源码合同及本片断言 |
|---|---|
| realCycleStarts... [1] managed identity不等 | registration.topicCompletionAwardService仍为@Lazy，目标TopicCompletionAwardServiceImpl有@Transactional；将“SDK直连字段”与“eager装配可以启动”判据分离。该项沿原lazy TargetSource.getTarget() == context managed bean，再保留TransactionInterceptor断言，不拿toString或raw target相等冒充同一事务代理。 |
| eagerEdges... [7] 预期失败但真实启动成功 | topic/application/node内App→Node→Topic；当前Topic没有原Application反调，故保留该场景改验eager成功、真实managed Node→Topic→SDK、事务代理、禁止循环引用和无外呼。chapter/application/node场景保留原失败断言，其他真实循环负控不变。 |

保留原13组×2及另7项=33例、所有来源/恢复/通知/真实fact装配断言、真实反向循环负控和unexpectedCalls零调用断言；未以mock替代新环、未启动模型/SDK/数据库。仅对原两个失败对应合同改判，并加强eager正控的non-mock和事务代理绑定。

实际只做源码/差分及提交前轻量静态检查；未运行Java/DB/编译/全套回归/CI，结果NOT_RUN。根给定run40122522919 Backend job120957052429；尝试连接器读取该job日志返回Transport closed，未独立取得原XML，不声称已复跑或全启动验收。
