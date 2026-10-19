# OWN-C05-25-realdb — 真实 MySQL 回归源码

状态：SOURCE_DELIVERED_UNVERIFIED。执行：NOT_RUN。新增 4 个真 DB 用例；没有执行、编译或连接数据库，不报告通过数。

## 隔离与前提

入口 `OWN_C05_25MySqlIT` 仅在 `CHENGYIN_FUNDS_MYSQL_IT=1` 开启。开启时 HOST、PORT、DATABASE_BASE、USER、PASSWORD 五项 `CHENGYIN_TEST_MYSQL_*` 配置必须显式存在；PASSWORD 允许显式空串，其余不允许空白。缺项 assert 失败，不 assume/跳过。使用已有 MysqlIntegrationTestConfig 解析和标识符校验，强制随机 UUID suffix，CREATE DATABASE 不带 IF NOT EXISTS，且只删除本实例成功创建的唯一 scratch schema。需要 MySQL >=8（拒绝 MariaDB）、Connector/J、建库/建表/触发器/删库权限及生产 mapper 资源。base+suite+随机后缀总长须符合 helper 的 64 字符限制。

真实生产 XML mapper、SqlSessionTemplate、SpringManagedTransactionFactory、DataSourceTransactionManager 与两个真实服务共享数据源。显式 TransactionTemplate 建立真实 RR 事务；未启动完整 Spring 应用、路由切面、provider 或调度器，未验证 annotation proxy wiring。RedisCache 仅为 Mockito mock；这不是 Redis 集成回归。测试仅为专用配置/审计路径建两张最小 InnoDB 表，不声称覆盖完整 migration 或提现调用链。

## 四项断言

1. A 真实锁读 48 并写 24，事务保持未提交；B 真实写调用受锁阻塞，以连接内 innodb_lock_wait_timeout=1 得到 MySQL 1205。失败 B 回滚且尚无 cache 删除。finally 放行 A 并等待提交，再以新 RR 事务重试 B，真实 before=24、写72。查询独立连接，审计恰两行 48→24、24→72，配置72，cache删除两次。A 等待的是测试释放信号；B 等待配置锁但尚未持有该锁，不形成双方持锁互等。
2. B 普通读建立 RR snapshot48，独立线程 A 提交24；B 普通读仍48，同事务真实服务当前锁读应返回24再写72。独立连接核实同样两条审计链。
3. scratch BEFORE UPDATE trigger 明确抛错；真实服务已写的审计跟配置事务一起回滚，配置仍48、审计0，无 cache callback。
4. mock cache 删除抛异常，真实 DB 事务仍提交24且审计1；真实服务的权威读取24，Redis除失败删除外没有消费。

边界：4 项均 NOT_RUN。未覆盖真 Redis、跨实例回调乱序、缺行 gap-lock 行为、生产动态数据源、完整控制器权限与部署。旧单元 fixture 与生产源码均未改动。
