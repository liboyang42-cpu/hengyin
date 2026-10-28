# E selectForUpdate 一级缓存最小补丁

结论：生产改动仅一行，给已有 selectForUpdate 增加 useCache=false、flushCache=true。新增一个真实 MySQL regression；保留原 10/16/4/5/3 测试逐字不变。Java/MySQL 未运行，属性线索不等于完整 RR 正确性已验证。

唯一 parent 为公开 E91：`91e78c448f69b2e3e3407acc111ff460c4a37ebb`；parent tree `392dac695de1aa84ec2fa7c6185505a395cb1dc1`。独立工作树 `/workspace/hengyin-e-photo-lock`，分支 `codex/architecture-day-e-photo-lock-read`。source-only tree `3af133770de4b78a744746d151125d395bdd05b5`。未移动旧 ref，未改 C、root Gateway/Vision、任何服务/DTO/Mapper Java/协议/schema/共享 CI。

原 SQL 本来已 FOR UPDATE，但 SqlSession 的 SESSION 级一级缓存可能直接返回前一次 null，根本不再发 SQL。单独 useCache=false 只能绕过二级缓存；flushCache=true 才会清理本地缓存。两项属性与已有 selectByKeyForUpdate、selectOwned 保持一致，不删除 member/hash/state/receipt 保护，不改变 SQL predicates。

现有 FreshReadMySqlIT 的 3 个用例调用的是 selectOwned 或 by-key API，不会对同一个 selectForUpdate 重复查空，因此不能声称它们精确覆盖本语句。新增 PhotoCheckIntentLockReadMySqlIT（1 个）：

1. 复用冻结的真实 MySQL/Spring fixture connect/cleanup，不运行其 10 个业务方法；RC reader 是实际事务，JDBC autoCommit=false，SqlSession 的 LocalCacheScope=SESSION。
2. 同一 mapped statement/id/hash 第一次锁读无行；MyBatis 缓存 null。
3. 挂起 reader，通过 REQUIRES_NEW 独立 JDBC 写入一条合法 fixture 原意图并提交。writer 使用不同连接，不用任何 reader Mapper，避免意外清掉 reader 的缓存。
4. 用第三个独立 JDBC 连接先证明原行已提交、原 nonce/reservation 正确。
5. 恢复后确认仍为原 actual transaction、同一个 reader Connection、同一个 SqlSessionHolder；不手工清缓存，重复同一 selectForUpdate，必须返回原行/nonce/reservation。

RC 专门用来取消 absent-row gap lock，允许独立插入提交。若先锁真实存在的行再要求另一事务修改它，会阻塞 writer，无法单独暴露此缓存缺口。本例是实际同事务 read→独立 commit→再 lock-read，不把 RC 冒充 RR，也不把缓存属性验证当成所有 RR/receipt/mixed-read 场景已证明。已有 markApplied receipt 普通一致性读问题、C 最终编排和跨 scope admission 仍属于独立待验事项。

测试的直接 INSERT 仅创建独占随机 scratch schema 中的合法原行，固定 ID/nonce 是为了精确重复同一缓存 key；没有新增业务协议、provider/quota 调用或生产恢复路径。沿用已有 CHENGYIN_AI_MYSQL_IT_PORT/USER/PASSWORD；没有 skip gate、H2、默认连接或清理他人资源。

实际运行：Python 验证 production diff 只有两个属性；3 个明确 FOR UPDATE SELECT 均具双 flag；冻结 10/16/4/5/3 的完整字节及计数不变；新增 @Test=1；secret-file gate、git diff --check 和有限敏感模式扫描 PASS。没有执行 Java/MySQL/DDL，未运行 F155 官方门禁或完整 gitleaks，待根在联合候选执行。

根可注册 PhotoCheckIntentLockReadMySqlIT：tests=1、storage=[mysql]、gates={}；使用既有 AI MySQL env，必须 actual 1、failures/errors/skipped=0。本增量不能被旧 43 项 CI 的 snapshot 覆盖，也不替代原 Photo10、Fresh3、key recovery4/5、unit16 或完整 C 玩法验收。无迁移；保留全部 intent/result/nonce/receipt/audit，不因回读失败重发外部动作。
