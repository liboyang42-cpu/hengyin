# Photo prepare 交错窗口：规则控制与有界拒绝定位 SOURCE

父提交：`21cbd7772ba17640c3684766abc3fc3107081039`。代码交付为 test-only；没有修改生产服务、事务管理器策略、池上限、传输或 root 的共享诊断。两份跨 owner review.patch 未应用。旧运行具体 SQL/连接池/锁/资格首因仍 UNKNOWN，不能拿本次静态风险替代原缺失异常。

## 实际调用与已证事实

1. 原组合在 baseline prepare/execute、poisonEntered 之后，四次循环分别通过原 TransactionTemplate 执行 `PhotoCheckIntentService.prepare`，立即把同一原 intent/member/fingerprint 交给 caller executor 执行 `executeOnce`。本片仅把循环内原 TransactionTemplate 包进 fixture 的 preparePhoto 以区分 TX_BEGIN 与 PHOTO_PREPARE，原 assertNotNull、original/commands/pending 列表与提交顺序保留。没有先把所有执行串行完成、减少四个物理 provider handler、或以重试吞掉失败。
2. 原 prepare（生产源 :44–77）MANDATORY，校验可写事务/命令，原 key 查询，同意/媒体校验，冻结 canonical/context/nonce/原日配额身份，upsert 后当前锁读；没有在这里执行 provider。Mapper 的 selectByKeyForUpdate 已有 flushCache/useCache，不重做旧缓存修复。
3. 原 executeOnce（:80–121）NEVER，显式拒绝实际事务。首次 owned/selectOwned 是 Mapper FOR UPDATE 读取，claim/snapshot/result 使用现有 REQUIRES_NEW；quota 的 prepare/reserved/stage/outcome 也用真实独立事务；provider 处仍不能有实际业务事务。这一合同本片完全保留。
4. CrossDomainStorageFixture :140 的 DataSourceTransactionManager 使用默认同步策略，:550 附近的 proxy 安装真实 TransactionInterceptor；原组合只将同一 Druid 池从 standalone 的 12 限为 3。Spring 7.0.9 默认 SYNCHRONIZATION_ALWAYS，NEVER 仍会建立无实际事务的同步。MyBatis-Spring 4.0.0 会把读取使用的 SqlSession 和 JDBC 连接绑定到该同步；它的 suspend 只解绑 SqlSession，不关闭它。仍被 SqlSession 持有的连接也不会在 Spring ConnectionSynchronization.suspend 中释放。
5. 因此源码上存在可达资源依赖：一个 NEVER 调用可能持有读连接，再借独立短事务连接；多个调用可能占满三连接池，使 prepare 或独立提交无法借连接。这不是已经证明的本次实际调度/错误码，也不等于持有真实 SQL transaction/行锁。不能以旧 all-schema transaction oracle 为 0 推断池连接没有占用。
6. 现有 PhotoCheckIntentFreshReadMySqlIT 明确在空同步里 prime 同一 SqlSession，验证独立提交后的当前回读。不能通过关闭同步、跳过代理、改假 mapper、增池/等待或清缓存，把该真实合同绕开。

## 本片可直接集成的源码

- CrossDomainStorageFixture 新 preparePhoto：仅原 tx.execute/service.prepare 的调用投影。捕获 RuntimeException 后存固定诊断，原异常对象原样重抛；不替换 cause、改错误码、补偿额度或继续执行。TX_BEGIN 包括原连接取得/事务开始失败，PHOTO_PREPARE 表示已经进入原 callback；后者仍可能包含 Mapper/校验/配额计划/提交错误，不能仅凭阶段认定 SQL 已执行。
- photoPrepareFailure：仅固定两阶段/UNKNOWN、最多八层 cause 的公开类型白名单、每层最多四个 SQLException nextException；只准固定 SQLState/vendor-code 集合，未审值写 UNKNOWN，未知类型写 UNKNOWN_TYPE。不读取异常 message/SQL/配置/身份；不遍历任意 suppressed/反射对象或写 raw log。
- 原组合 outer catch 仍重抛首错误，额外将该固定记录写入原有有界 observation（仍 FAIL_OR_UNKNOWN）。无记录为 NOT_OBSERVED，不冒充已完成 prepare 或 provider。
- CombinedContextConfigurationTest 原七方法正文逐字保留，新增两个普通无参控制。profile 仍同 20 源输入，没有新顶层/嵌套 helper 类或新的最低 class 要求。

新增方法：

- `photoNeverReadRetainsConnectionAcrossIndependentTransaction`：真实 DataSourceTransactionManager、Photo Mapper XML、SqlSessionTemplate，有限 JDBC doubles；在无实际事务的 NEVER 同步里读取后，证明原连接与 REQUIRES_NEW 第二连接同时存在，直到调用结束才释放原连接。核独立连接身份、最终关闭和同步清除；不是 MySQL/HTTP 执行证据，也不是实际失败重现。
- `photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause`：分别在 TX_BEGIN 与 PHOTO_PREPARE 失败，原异常/原 SQL cause 保持同一实例；未进入服务时零调用；不输出私有 message，未知阶段/类型/SQLState/code 不得伪装为已核值，原 SQLException nextException 可按有限字典读回。

## 仅交 root 的精确接缝

### http-failure-projection.review.patch

基于父提交的 ci/test_http_e2e_entry.py，只建议增加固定失败 observation 投影和一个直接 Python 控制 SOURCE。必须匹配原唯一 suite/case、有 error/failure、唯一 <=128KiB marker、同 source/run/attempt、FAIL_OR_UNKNOWN/两完整性标志 false，再逐键/类型/长度/有限字典核验 photoPreparationFailure。公开只返回该小对象，不回显整个 observation/error/XML。缺证/混源/重复/未知值不出字段，原失败仍失败。该 patch 不改 XML/count/receipt/cleanup 或失败准入。root 唯一实际应用/控制计数 owner。

### photo-owned-read-short-transaction.review.patch（NEEDS_ROOT_DECISION，未应用）

这是生产生命周期候选，不是确认过的本次修复。仅建议 executeOnce 内三个 owned/read 调用使用既有 independent REQUIRES_NEW：首次 owned，claim 失败的回读，保存结果后的回读。这样不会让本方法的读取在外层空同步上建立长期 SqlSession/连接；current locking read 在短事务结束释放，provider 仍在事务外，原 key/hash/owner/状态检查和原 nonce/UNKNOWN/结果合同不变。保留 NEVER 注解和显式实际事务拒绝，不改变 public read 的调用者锁序，不把所有事务管理器改成 ON_ACTUAL，不影响 prepare/markApplied。

外层调用者若在进入本服务前自行绑定过其他读连接，本候选不会凭空释放它；不能由三行 patch 宣称全域连接生命周期已验收。root 决定是否交生产 owner 应用并补相应真实服务/真库验收；本 C 分支没有应用生产改动，不越过当前 ownership。未作裁决前，本片只承诺源码诊断与规则控制已交付，不能承诺下一次业务运行必过。

## 验证/回退

所有新增测试、parse/import、Java/Maven/DB/Redis/Docker/HTTP/CI 均 NOT_RUN。仅源码读取/编辑、官方源码读取、Git 差分检查/摘要/限定秘密模式扫描。没有运行统计或原私有 runtime 材料入库。root 需要将配置方法登记 7 -> 9；原业务 case 仍 1、HTTP 240 与所有原安全/资金/UNKNOWN/物理隔离/清理断言保持。本片回退仅撤三份测试源的新增 helper/诊断接线和两个控制，不撤既有 digest/seed/内存/进程修补。
