# 原核心 HTTP 入口边界修补（SOURCE）

Parent: `9f544d51949c3a9af8128ded5ac99381be99e7a8`。保留该基线已组合的
`10f4ef6d` UTF-8 链与 `fdb3fd40` 原 Future/fault 合同，不重交、不改生产。

源码可证的两个接缝：原 CombinedCoreHttpWorkload.run 首个 curve 前执行第一波 24 组
chapter/receipt/fact/full-state SQL 和 heldWitness；调用者却从 submit 起立即计 HTTP entry
2 秒。另 core.fact/allGame 用 plain ObjectMapper 序列化完整 JDBC 行；原迁移
`migration_club_lead.sql:14` 定义 create_time DATETIME，原 seedCombination 插入 NOW()，
合法 JDBC 时间值不应绕开已有 FACT_JSON/完整值算法。旧运行具体 cause 未取得，仍 UNKNOWN；
不宣称已还原 SQL 慢、池耗尽、时间异常或任何原始异常。

唯一实际 caller 改为 prepareFirstWave(stillHeld) → submit(run) → 原 await(2,SECONDS)。
prepare 在四 AI Future 已确认 UNKNOWN 且所有原 handler 仍 held 时执行原第一波全部读取和
原见证；不写 SQL、不派 HTTP，准备完成前不发布快照。第一波 run 消费同一份原快照，
后两波原捕获/见证顺序保留。Executor 提交前写的快照由原提交关系发布给 worker；
没有新增并发/锁/事务。原 entered.countDown 仍仅在 http 的 active.add 后，未提前到准备。
HTTP entry 的 2 秒、各 curve 45 秒和整个 case 150 秒都未增，准备工作仍计整体预算。
主线程此前亦只能等待准备/首 HTTP，付款在 entered 后才提交；此次没有减少原业务并发。

SQL 守恒：每 key 原 chapter 1、receipt 2、state-list 1、state-map 1（首波 24×5）逐条保留；
原 SQL 文本/参数、SELECT *、原 ORDER BY、72 命令 ID/actor/expected/target 全部保留。
所有原 assertApplied、完整 state equality、fact before/after、allGame read/replay/foreign
零效果断言及 240 HTTP 保留。第一波准备一次消费，不重新读/生成/替换原输入来重试。

core 两处快照共用原 CrossDomainCombinedLoadMySqlIT 的完整行算法：仅机械提取
completeFactRows，原 digest 的行顺序/所有列/null/SQL Timestamp nanos/Date-Time millis/
FACT_JSON/SHA/cause 规则不变。completeFactGroups 保留 core 原 state/receipt/table 分组，
列及组键确定排序；不丢事实、不用字符串常量代值。core 内部 hash 随新 canonical 编码变化，
只比较同源当前执行前后；不是外部 receipt hash 迁移，未改任何持久原身份或 command hash。

若原 entry 等待失败，固定 entryFailure 只读 isDone Future 的原 cause，不延时、取消、重试
或输出新诊断 schema；尚未完成仍 UNKNOWN/失败。原 case 与配置 11 方法身份不变，配置
原语句完整保留；只在既有方法追加有限 SQL doubles 验证 24×5 读/零提前 HTTP、时间/小数/
长整/null/二进制全字段变化、SQL 行顺序、真实序列化拒绝和原 Future cause 保留。
这些 SOURCE 控制不等于数据库/HTTP/容量实证。

仅三既有测试 Java 与本说明/摘要。无新 type/dependency、无共享/生产/资源/CI 改动。
四路 16 MiB held、2/5/6/60 秒、原池/堆/容器/时间预算与全部精确清理不变。
解析/import/测试/编译/Java/Maven/DB/Redis/Docker/HTTP/CI 均 NOT_RUN；只做 Git 差分、
限定敏感词扫描及摘要。不得以本片声称实际 HTTP240 已通过。
