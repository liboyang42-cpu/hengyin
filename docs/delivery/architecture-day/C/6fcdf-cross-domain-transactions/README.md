# CrossDomain 第 110 行事务观察合同 SOURCE

父提交：`6fcdf747e17cb5094121f609ae33f7734fda2ae7`。分支：`codex/architecture-day-c-cross-domain-trx`。

实际失败证据为 run `40232916855` / job `121288370648`：原唯一 case 已越过 poison HTTP 入口，随后第 110 行 `assertEquals(0, storage.pool.getActiveCount(), ...)` 得到 1。它证明父 Druid pool 当时有一个借出连接，**不能单独证明该连接有未提交 SQL 事务**。目前没有该 run 的连接 ID/INNODB_TRX 观测，不认定实际长事务已被排除或已修复。

## 实际调用及修改边界

唯一 fixture caller 是 `CrossDomainFaultIsolationMySqlIT.sameWindowRetainsOriginalEconomicIdentitiesAndRequiresUnpoisonedNotificationProgress`。只改该类及 `CrossDomainStorageFixture`；没有修改生产事务 owner、`CrossDomainTransportStage`、Python、共享入口或 CI。

相关生产链只读核对为：`OfficialBroadcastServiceImpl.runDueScheduled → dispatch(NOT_SUPPORTED TransactionTemplate) → dispatchOutsideTransaction → freqCapped / deliverSubscribe(REQUIRES_NEW claim) → WxSubscribeMsgServiceImpl.notifyOfficialBroadcast → openidOf → UmsMemberServiceImpl.selectUmsMemberById → 真实 MyBatis Mapper → 原 HTTP post`。

`NOT_SUPPORTED` 的“没有实际事务”与“没有同步上下文/借出连接”不同。当前事务 manager 由 fixture 用真实 `DataSourceTransactionManager(pool)` 创建，Mapper/SqlSessionFactory 和原服务也使用同一 pool。外层同步上下文中的普通读可能绑定 auto-commit 连接，直至外层结束；这是一条解释现象的源码路径，不能代替本次真实连接证据。本包没有隐藏或声称已消除此类物理连接占用。

## 精确改动

1. 原真实 member service 外加只观察的现成 ProxyFactory advice：`proceed()` 返回后采样同线程真实 Spring 事务/同步状态和该 **pool** 的 `ConnectionHolder`。若绑定资源，保存实际 MySQL CONNECTION_ID、schema/UUID、JDBC isolation、JDBC 与 MySQL session auto-commit。返回值、传播属性、SQL owner 和服务调用次序不改；不设置事务模式、不关闭该连接、不 mock 成功。
2. 原 held-window **零事务**断言保留期望值 0，但把错误的“借出连接数量”代理替换为独立真实 SQL 观察：原 admin 的 **unpooled** datasource，确认同 UUID、数据库 mysql、JDBC/session auto-commit，查询 `INNODB_TRX JOIN PROCESSLIST` 中所有当前数据库等于精确 owned schema 的事务。没有按业务连接 ID、Spring flag、只读/空闲或事务状态排除，实际 suspended parent transaction 也不能被过滤。查询失败直接失败，不返回空集。
3. 同一原 case 在故障前增加校准：真实父 pool 连接开启事务、锁原 MEMBER 行，以原 CONNECTION_ID 必须观察到该事务；随后 rollback 并恢复 auto-commit，必须观察到零。元数据短暂缓存只在固定 1 秒校准截止内轮询；不增加原 150 秒总预算，不更改业务行/资金事实，不新建 case。
4. 原 observations 增加 held/released 校准和 held-window SQL 证据：observer ID、借出数量、全部事务 connection ID/state/锁行数/改行数及通知读取角色。保留借出数原值，不将其改成 0 或假设应等于 1。若真实 MySQL 事务数非零、通知继承事务或绑定连接非 auto-commit，仍明确失败。

原 cleanup 中 `assertEquals(0, pool.getActiveCount(), "parent business connections must exit before pool close")` **原样保留**，所有业务 worker 结束后仍要求所有借出连接归还。

## 原场景与限制

原唯一 @Test、@Timeout(150)、5 秒 poison 入口、同一 scheduled queue、真实 healthyWhilePoisonHeld 正向、经济原号、callback/money/access/refusal、UNKNOWN 不重发、恢复后新广播、provider 次数及 MySQL/Redis/worker 精确 cleanup 均保留。没有改 TransportStage 的 OOM/cleanup 片；该失败由 F 独立负责。

本片是修正观测量并补可证连接证据，**不是修改生产连接池策略或将真实跨外部 I/O 的 SQL 事务豁免**。是否确有生产长事务，必须看未来同源 SQL observation；若非零，结果保持失败，再按实际 connection/role 定位 owner，不能由源码假定通过。

仅 Git diff/文本/hash/有限秘密模式核对。所有控制、parse、编译、Java、MySQL/Redis、HTTP、容器、故障注入及 CI 均 **NOT_RUN**。原已通过包不重复运行。`postimages.sha256` 包含两个源码及本说明，manifest 自身由提交/tree 绑定。
