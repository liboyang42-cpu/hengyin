# F：声明类型raw source行完整性

Parent `5caf6b288c8df612d8420e4007032e4d9568e18e`；独立 `codex/architecture-day-f-source-row-verifier`。只改producer协议及专属测试/说明，原55/44/51、3Mapper/2facts、2ec基线、commands/gate/locking proposal、业务源码/CI/workflow均未改。

已实现：复用原parse_output识别器记录source_rows，保留原五返回值/模块边格式。既有完整回读通过原source/JAR/hash/index/tool/11项证据校验后，任意声明exact member没有认可source行，追加OWNER_SOURCE_ROWS_MISSING→UNKNOWN；其它确定违规仍优先BLOCKED。模块汇总或同模块其它class行不能替代。供后置工具复用的owner_source_reviews只比较已识别集合，不新增scanner或语义解析。

Leaf不要求项目外向边：java.lang.Object/java.base行有效，故无业务外向依赖的普通class/interface不因项目边为空而红。若工具确实没有给出该类型任何行，不能仅凭class-index存在认定零依赖；保持UNKNOWN，不加leaf白名单。

已核对官方OpenJDK21源码：TypeEnter默认父型Object→ClassWriter写super_class→Dependencies显式读取super_class→-filter:none关闭同包/同归档过滤且不排java.base→RawOutputFormatter输出source/target。来源/内容hash见jdk-source-check.json。它支持上述Object行判据，但**不是本轮Temurin21.0.12真实raw执行证明**。历史artifact API本轮Forbidden，未取得真实空leaf raw；新控制工具会提供root获准CI的真实空class/interface正控。VM未运行Java。

实际Python完整自测186/186、0skip、12.951s：原test_collect_jdeps74+test_gate90＝164个测试方法正文逐AST保留，加3个新协议控制；原19项locking控制亦保留。新增覆盖：声明类型全行缺失UNKNOWN；class/interface仅JDK行合法且无project edge；模块summary不能替代leaf行。现有Java原始source/index/哈希错误、确定越界、原9方向控制继续通过。[原始输出](python-tests.txt)。这些是协议double，不是真实JDK class图；尾部UNKNOWN是原负控输出，不是假称实际jdeps运行。

Python3.9语法、窄diff、原配置secret-file/gitleaks提交前检查。新verifier必须由root独立审定、唯一双ref repin，candidate不能自改producer批准自身。下一提交仅为控制工具明确绑定该新完整SHA并补实际JDK leaf入口；原5caf/170冻结。Java/jdeps/CI/DB均NOT_RUN，实际leaf与三项负控由root集中执行，不新增job/依赖/平台。
