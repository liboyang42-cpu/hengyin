# F：06a后续精确技术边界

Parent `06a571b1247620a9bdcea068be0ded973671d17c`；独立 `codex/architecture-day-f-owner-followup`。只改owners、两个专属Python测试、README及本交付目录；生产gate/collect_jdeps/parser、2ec baseline、locking proposal、commands、3Mapper/2facts、CI/registry、业务Java/SQL逐字未动。原7b3的35类型/30职责/36方向及原32/27/33完整保留。新增20类型/14职责/15方向，共55/44/51，无新例外或审批JSON。

## 新命名合同（均为技术类型职责，不是业务写权）

以下B/A/D/G均在System；未写前缀的B/A/D/G类型属于 `com.chengyinhub.business`。

| policy ID | exact成员/合法输出 |
|---|---|
| B_PAYMENT_FACADE | service.IWechatPayService + service.impl.WechatPayServiceImpl → SDK/router/outbound port/A DTO |
| B_PAYMENT_OUTBOUND_PORT | service.WechatPaymentOperations → A DTO |
| B_PAYMENT_SDK | service.impl.WechatPaymentSdkGateway → outbound port/intent store/A DTO |
| B_PAYMENT_ROUTER | service.impl.WechatPaymentCallbackRouter + service.WechatPaymentCallbackOwner/WechatRefundCallbackOwner；原四域public合同调用保持合法 |
| B_PAYMENT_INTENTS | service.impl.WechatPaymentIntentStore → order-lock port |
| B_PAYMENT_ORDER_LOCK | service.WechatPaymentOrderOwner + service.impl.JdbcWechatPaymentOrderOwner |
| A_TRANSFER_INTENT_DTO | domain.dto.CashWithdrawTransferIntent；禁止反调提现/支付facade或SDK |
| D_CONTENT_SIZE_POLICY | service.support.TopicSizeLimits；原D_CONTENT_GRAPH/D_CONTENT_RULES可依赖，禁止反调主题门面/guard/publication |
| G_CASH_CLAIM | service.ICoopSettlementClaimService + service.impl.CoopSettlementClaimServiceImpl → 原G_SETTLEMENT_EVIDENCE |
| G_EDITION_FINALIZATION | service.ITopicEditionFinalizationService + service.impl.TopicEditionFinalizationServiceImpl；禁反调主题门面 |
| F_LOG_CONTEXT | Common `com.chengyinhub.common.utils.LogCorrelation`，禁反调Framework/Quartz/AlarmService |
| F_REQUEST_CONTEXT | Framework `com.chengyinhub.framework.web.trace.RequestIdFilter` → F_LOG_CONTEXT |
| F_ASYNC_CONTEXT | Framework `com.chengyinhub.framework.manager.AsyncManager` → F_LOG_CONTEXT |
| F_QUARTZ_CONTEXT | Quartz `com.chengyinhub.quartz.util.QuartzJobCorrelation` → F_LOG_CONTEXT；禁反调AbstractQuartzJob/Coordinator |

B SDK不得调入站facade/router或五项领域callback接口；store/order-lock不得反调SDK/router/facade。只对这些精确type/binary companion使用原限制，不按目录通配。Application→原公开domain服务仍可用。四域implementation/泛型Runnable历史查单callback、代理Bean循环和同职责内部循环未覆盖，不能说支付全图无环。

D4061的topic fence仍归已登记TopicContentWriteGuard，没有造新锁owner/Mapper许可。新方向只约束它/graph对原TopicSizeLimits叶子的依赖，不证明实际调用锁、当前读/缓存/计数/顺序。G两个type职责不能证明MANDATORY/先topic再head、退款分配或late SUCCESS；A DTO叶子也不能仅凭JDK record/依赖图证明字段和hash验证。F的结构约束不能替代日志泄密/线程清理测试。

## 真实源与验证

- B `0ffb9c8fee278c11fe47aa6bcfd95745c9e73850`，读完整wp03-routing-source及实际facade/SDK/router/store/ports；A最终DTO取06a（已含f676/2450），不是旧554中间class。
- D `4061bac25dbe113574632dde61d4ae416185db77`，完整d8-topic-fence说明与实际guard/graph/size类；G `2457bf1851c0573df7a69270a4532b00b9e8404c`，完整topic-cash-fence说明与实际claim/finalization。
- F `1fb99670c066496c3b19da6b3ac4b132e14ab785`，实际MDC/RequestId/Async/Quartz支持；其它已登记类型取06a。observed_source是06a共享观察锚点，不声称尚未集成的新类型存在于06a；真正编译验收仍绑定实际candidate/source/tree/run/attempt及原JAR/hash。
- 复用既有strip_java_comments只读核对所有exact源类型及新增方向，非Java解析/编译证明；没有新增scanner。原fixture曾仅建Admin/System，新fixture同步维护真实各模块original JAR、Boot打包与target class目录，原测试方法正文全部保留。
- 实际仅 **9 Python定点tests / 0.838s / OK / 0skip**：新增6+原3（全部允许方向、第三Mapper CLI0/1/2、3ports/2facts）。[完整test ID输出](targeted.txt)可用 `python3 -m unittest <ids> -v` 在ci/architecture重跑。ZIP/runner均明确NOT_COMPILED协议double，不是真实class编译负控。涵盖Common/Framework/Quartz真实位置/hash、七类反调、SDK/store双向SCC、Common输入缺失/模块错误UNKNOWN、router合法public port仍非全图、候选删SDK/加反向许可不可自批准。
- 实际静态检查：Python3.9语法、原69+89测试方法正文逐AST不变、原35/30/36及业务policy精确不变、diff与原配置秘密扫描。**Java/jdeps/JUnit/DB/CI均NOT_RUN，不启动新回归批次。** 缺新源/输入会UNKNOWN，不能拿本guard分支缺B/F类的生产树做空图验收。

root独占两处trusted ref，应一起指向审定的新verifier，不可candidate自改policy批准自己；F仅交review.patch，不改workflow。下一项为root同批完整源上的真实SDK→router、DTO→facade、Common→Framework隔离编译负控及原3Mapper正控；必须同源JAR/raw回读，不能借本Python合成绿项。业务/锁/日志语义验证仍由各owner对应测试与root集中CI执行。

执行verifier冻结 `5caf6b288c8df612d8420e4007032e4d9568e18e`（parent06a）。本交接提交只加[双ref review.patch](trusted-ref.review.patch)与说明，不再改冻结策略。补丁基于公开06a，精确两行7b3→5caf；`git apply --unidiff-zero --check` 的临时Git逐字应用模拟PASS。root仅按自己实际workflow逐hunk审定，若旧ref/上下文不同不得整旧文件覆盖；本分支没有编辑workflow。
