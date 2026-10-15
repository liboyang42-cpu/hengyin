# B03 源码修复与验证交接

2026-10-15；共享分支 `codex/mini-bug-arch-integration-20261015`。本轮审阅 HEAD `f72ddb14ab883e12e65fe2910f44d184725a2fd2` 加工作区 B03 diff；静态脚本对照修复前公共基线 `d551c6babd018d41aec90dad2c0acaf1c534644d`。未提交、未部署，不能标业务 READY。

## 确认缺口与最小修复

1. 积分余额更新已有原子 UPDATE，但流水 current/after 原先来自普通会员 SELECT。在外层 REPEATABLE READ 事务先建立快照、另一业务扣分提交后，奖励可能按旧余额记录流水；作者结算即使先锁会员，再普通读仍可能遇旧快照/缓存。`PointsAwardServiceImpl` 两条奖励路径改用 `UmsMemberMapper.selectPointsForUpdate` 当前锁读；XML 关闭二级缓存并清一级缓存。会员不存在或 point=NULL 拒绝写入，不再凭空按零记账。金额规则与原事务、唯一键保持。
2. 商家服务原本已禁止冻结或非正数批次确认、登记付款、开蓝票；检查与 UPDATE 间若发生追扣/冻结，旧 SQL 条件不足。`MerchantPayableBatchMapper.xml` 三条更新均补 `amount_total > 0`，开票补原有冻结条件。只将既有政策放入原子 WHERE；登记付款仍只记事实、不实际转账。

批准追加的独占文件：`UmsMemberMapper.java`、`UmsMemberMapper.xml`、`MerchantPayableBatchMapper.xml`。其余 lane 文件未改动。未修改 SQL migration、workflow、pom、鉴权、资金政策或配置。

## 历史线索反证与保留

- 非空 eventId 已有 `uk_member_event_once(member_id,event_type,event_id)`（base schema）及事务；不能把先查后写直接判为重复入账。本次保留唯一冲突抛出及回滚。NULL eventId 不受该唯一键去重；现有 probe 不带 eventId，会查同用户/类型既有流水。本次不重新定义其业务身份，也不宣称 NULL 事件并发已得到新保证。
- 券服务已有核销/撤销 CAS、来源发放回执锁和库存原子占坑；`PmsSkuMapper.xml` 已有 stock>=qty 条件扣减。这些是源码反证，未重复改造，也不等于已验证现场券/库存守恒。
- 不扩展 cart、新领券流程、会费或资金政策；未把旧迁移注释当真实数据库状态。

## 测试与实际证据

已执行：`python3 docs/verification/mini-bug-arch-20261015/B03/check-source.py`，实际输出保存于 `static-check-output.txt`：修复前 5/5 缺少对应源码条件，候选 5/5 通过。另运行 B03 文件 `git diff --check` 通过。静态检查只证明 XML/源码形态，不证明事务、编译或数据库语义。

已编写但未执行：

- `B03PointsAwardServiceTest`：真实奖励服务调用，当前余额读取顺序、流水 before/after、空余额拒绝、既有事件 no-op、唯一冲突不吞。
- `B03AssetMybatisIntegrationTest`：真实 Spring 事务代理和 MyBatis XML；覆盖外层 RR/缓存、不同事件并发连续流水、同事件唯一冲突回滚、空会员/余额、追扣后非正金额阻止三类推进、冻结阻止开票及正常正数路径。独立 JDBC 读回余额/流水/批次事实。该测试仅规则值使用 stub，商家竞态使用受控交错。
- `PublisherSettlementPointsAwardServiceTest` 与 `RedemptionRewardClawbackMyBatisTest` 已注入新增 Mapper，保留业务断言。全仓搜索直接构造/类型引用，旧测试构造入口只有这两套，另有两套 B03 新测试。

Cloud 按任务约束未执行 Java 编译/单测、MySQL/Redis、任何 IT 或自建后端。相关旧套件 `MerchantPayableServiceTest`、`MerchantClawbackServiceTest` 也未运行。交 CI/VPS 串行执行后才能确认。新 MySQL 测试要求 `CHENGYIN_FUNDS_MYSQL_IT=1`、显式授权测试 host 和非空隔离 suffix；会创建/删除隔离 fixture 数据库，只允许专用测试目标，不能指向生产。未修改 CI 以自动启用它。

待验证层：编译、Java 单测、专用 MySQL 并发及独立业务读回、真实报名/核销/退款贯通、设备、托管 CI、合并与部署全部未完成。出现异常应仅回退上述 B03 代码，保留账本和其他 lane 改动。
