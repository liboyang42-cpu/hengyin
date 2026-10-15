# E04 券交付回执与撤销核销真实 MySQL 补充回归

CY-HANDOFF: CODEX；任务 E04 / BK-ASSET。NO_API_CHANGE。TEST_DEPLOYED=NO。

- 仓库：`liboyang42-cpu/hengyin`；分支：`codex/extra-e04-regression-20261015`。
- 公共基线：`1e403b135206844a34219753a36e2dee39c7dd59`；控制快照：`b8541ad2407d0a360588ab77ad32fe3d1c9eb71e`；协议：`CY-COORD-20261015-v1`。
- 状态：**SOURCE_DELIVERED_UNVERIFIED**；按最新用户指令全部不验证，不运行 Java/Maven/Node/DB/DevTools/业务验证，不等待或重跑 CI。以下命令仅作未来另行获准时的交接材料。Cloud **Java/javac/Maven/MySQL/Redis 均未运行**，未启动服务、未连接 VPS 或生产。冻结候选的 231 项通过不转记为本文件通过。
- 只新增本 README 和 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03CouponLifecycleMySqlIT.java`。未修改既有测试、共享配置、业务源码、依赖、CI、base schema 或 migration；无暂存、提交、push、部署。

## 既有覆盖与本次差异

| 已有文件/方法 | 已有证据范围 | 本次处置 |
|---|---|---|
| `CouponLifecycleMybatisIntegrationTest.lastAvailableCouponConcurrentGrantCannotOverIssue` | 两个不同会员争最后库存，真实 Service/Mapper/事务与 JDBC 读回 | 不复制 |
| 同类 `unlimitedCouponKeepsGrantingAndMaintainsActualReceiveCount`、`failedHistoryInsertRollsBackReservedStockAndCanRetry` | NULL/0/负数不限量；历史插入约束失败回滚库存并重试 | 不复制 |
| 同类 `concurrentRedemptionOnStartedCouponSettlesOnce`、使用期边界/删除与撤销交错方法 | 核销 CAS、结算一次、时间与软删保护 | 不复制 |
| `CouponSourceGrantTest` 六方法 | Mockito 验同来源重放、历史无意图、归属/模板、停发、回执落库及失败抛出 | 新增真实 `selectForUpdate`/`markDelivered` 并发与落库失败全事务回滚 |
| `SmsCouponReverseTest`、`SmsCouponReverseTransactionBoundaryTest` | mock CAS/审计；H2 真事务但 Mapper SQL 为 mock JDBC，含多周期回放 | 新增 MySQL 真实 XML 的并发撤销、删除半径及审计约束失败回滚 |
| `RedemptionRewardClawbackMyBatisTest` 三方法 | 既定积分/XP 不足扣零、同发放只冲一次、再完成、已用券仅审计 | 保留现有政策，不复制、不把撤销券核销混作付款退款 |
| `SmsCouponStockTest` 三方法 | 库存纯函数 | 不当真库并发证据 |

本基线 `SmsCoupon`/通用发券入口没有获准的“每会员最多一张模板”合同。本次限领只指现有 `(source_type,source_id)` 原来源交付一次；同会员不同来源可合法获券。不以缺 requestId 判 bug，不新增按人限领、退款范围、资金主体或积分政策。

## 新增四方法与预期

1. `concurrentSameSourceReturnsOneOriginalHistoryAndConsumesOneStock`：两个事务都抵达来源行锁入口后放行；真实 `FOR UPDATE` 串行化同原来源。两次必须返回同一个非空 history ID，独立 JDBC 读回一张历史、一单位库存、原回执关联；再次重放计数不变。
2. `receiptConstraintFailureRollsBackHistoryAndStockBeforeRetry`：scratch 回执表 CHECK 拒绝非空 history_id，迫使真实 `markDelivered` 失败；已插历史与已占库存必须全部回滚，意图仍待交付；撤约束后原来源重试成功且关联原结果。
3. `concurrentReverseCasDeletesOnlyUnbatchedPendingSettlementOnce`：两事务真实普通读均取得已用态，再竞逐真实撤销 CAS；恰一次成功、一次 false，券态/计数回零，恰一条 SYS_USER 撤销审计。仅删除本券未入批次且待结算明细；已入批次、已结算、其他来源三条原行保留。
4. `auditConstraintFailureRollsBackRealReverseSqlIncludingSettlementDeletion`：scratch 审计 CHECK 拒绝撤销动作；真实券态 CAS、计数回退和待结算明细删除必须全部回滚；撤约束后重试仅一次成功提交。

目标 Surefire **tests=4 / failures=0 / errors=0 / skipped=0**；这是预期数，未声称已通过。业务 Mapper 没有 mock 返回值；Java 动态代理仅用有界 barrier 控制真实 Mapper 调用时机，异常原样抛出。每个调用经过 Spring `TransactionInterceptor` 和 `DataSourceTransactionManager`，验证查询使用新独立 JDBC 连接。

## 获准验证环境与精确命令（Cloud 不执行）

仅交本地验证协调者，在获准的隔离 MySQL 8.0.45 实例、可用 Maven 依赖缓存与 Java21/Maven3.9.11 环境串行执行。MySQL 必须支持并强制 CHECK，账户须有专用 scratch schema 的 CREATE/DROP/DDL/DML 权限；不需要 Redis 或应用服务。不适用于 budget212 离线环境；不提供或套用旧 1152MiB 驱动，容器/堆预算由验证协调者按当前批准通道设定。

所有变量必须显式提供；凭据由批准通道注入，不能写进 Git/报告。示意占位值必须替换成获准隔离实例：

```bash
export CHENGYIN_FUNDS_MYSQL_IT=1
export CHENGYIN_TEST_MYSQL_HOST='<approved-isolated-mysql-host>'
export CHENGYIN_TEST_MYSQL_PORT='<approved-isolated-mysql-port>'
export CHENGYIN_TEST_MYSQL_DATABASE_BASE=e04_scratch
export CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX=_run20261015_e04_001
export CHENGYIN_TEST_MYSQL_USER='<approved-scratch-user>'
# CHENGYIN_TEST_MYSQL_PASSWORD 由批准通道显式注入（允许显式空字符串）。
mvn -B -pl chengyinhub-admin -am \
  -Dtest=B03CouponLifecycleMySqlIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

在 C00 形成包含新文件的精确交付 SHA 后，于其完整 checkout 根目录运行；不能只 checkout 旧基线却计为新套件运行。`-am` 上游没有指定测试时允许无匹配，admin 的 XML 必须另验存在且恰四方法全跑，不能仅以 Maven 退出码签收：

```bash
python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree as ET
path = Path('chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.service.B03CouponLifecycleMySqlIT.xml')
root = ET.parse(path).getroot()
expected = {
 'concurrentSameSourceReturnsOneOriginalHistoryAndConsumesOneStock',
 'receiptConstraintFailureRollsBackHistoryAndStockBeforeRetry',
 'concurrentReverseCasDeletesOnlyUnbatchedPendingSettlementOnce',
 'auditConstraintFailureRollsBackRealReverseSqlIncludingSettlementDeletion',
}
assert {c.attrib['name'] for c in root.findall('testcase')} == expected
assert len(root.findall('testcase')) == 4
assert all(root.attrib.get(k) == v for k, v in {'tests':'4','failures':'0','errors':'0','skipped':'0'}.items())
assert not root.findall('.//skipped') and not root.findall('.//failure') and not root.findall('.//error')
print('E04 expected four methods ran without skips/failures/errors')
PY
```

最终 schema 为 `e04_scratch_e04_coupon_it_run20261015_e04_001`。每次执行选独占全新 suffix；suite 使用 `CREATE DATABASE`（无 IF NOT EXISTS、无预先 DROP），同名已存在即失败且不删除原库。仅成功创建自己的 schema 后 teardown 才 DROP。表夹具复用既有 CouponLifecycle 的最小 InnoDB 定义；来源回执主键与唯一 history_id 来自既有 migration，不改共享文件。

未启用 opt-in 时遵循既有套件门控；**显式启用但缺变量、连不上、版本不符、建库失败、CHECK 不生效或 barrier 超时必须失败**，没有 assumption/静默 skip 或弱化断言。需保存完整命令、交付 SHA、脱敏环境与资源配置、Surefire 原 XML、Maven 退出码、独占 schema 清理证据。编译/业务执行/应用部署/HTTP/付款退款真实身份读回与设备验收分别签收。

## 静态检查与限制

Cloud 只完成源码/SQL审计、四方法计数与 `git diff --check`。尚无 Java 编译或 MySQL 执行结果；未发现经运行确认的新增源码缺陷。若实际失败，保留原断言与证据，交 C00/原业务写者修复；不在 E04 写业务源码。NO_API_CHANGE；TEST_DEPLOYED=NO。

## 后续候选（只读源码证据，未验证）

候选：通用发券读取模板后，另一事务先提交停发/失效，旧快照仍可发券。

- 精确源码：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponHistoryServiceImpl.java:263`（`grantCouponWithReceipt`，旧 coupon status 检查后占库）；同文件 `:371`（`grantCouponById` 先普通读再委托）；`chengyinhub-system/src/main/resources/mapper/business/SmsCouponMapper.xml:206`、`:211`（两种计数 UPDATE 均无 status 条件）。
- 真实调用方：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicCompletionCouponRewardServiceImpl.java:35`、`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantCrmCouponGrantAdapter.java:31`。
- 静态触发推演：读取 status=1 → 独立事务提交 status=4 或 3 → 继续旧对象发券；现有 SQL 只判 id/库存，没有当前发放状态保护。预期新增失败断言应为不新增领取历史且 receive_count 不变。未执行复现，因此不宣称实测 bug。
- 覆盖缺口：既有 Disabled/NotIssuable 测试检查初始已失效/停发状态，现有真库 Lifecycle 未覆盖模板停发发生在读取与占库之间。`grantCouponBySource` 的模板锁读不代表其他通用入口同样受保护。
- 最小评估方向：交 C00/原源码写者审阅占库 SQL 的当前状态门控及零行返回语义，保留既有状态政策；本轮未改源码，也未授权新增该候选测试方法。
