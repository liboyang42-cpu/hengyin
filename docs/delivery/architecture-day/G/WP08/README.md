# G / WP08：R7、R1 精确调度准入候选

结论：已补两个精确 target 和默认暂停的缺失补登记候选，编写准入→既有 fence→真实任务→原消费者的行为测试及 MySQL 回读夹具。只执行静态检查；Java、MySQL、真实权限/调度/运营启用未验收。未 dispatch CI，未运行后端、数据库、迁移或生产任务。

## 基线与隔离

- 仅公共仓库 `https://github.com/liboyang42-cpu/hengyin.git`。
- 实际起点 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`，独立分支 `codex/architecture-day-g-wp08-task-admission`，独立工作树 `/workspace/hengyin-g-wp08`。
- WP04 的 `65780b036d9e44e3ab8705bb8c244558a09213af` 在原 G 分支保持冻结、工作树干净。本分支直接从公共基线出发，不携带 WP04 资金补丁；应单独 review/cherry-pick。
- root 对 WP04 MySQL harness 的显式连接项及自身 UUID schema 清理修复应保留。本批没有 WP04 测试文件，不能整文件回覆盖 root 的组装树。
- 五个源文件的精确内容 SHA-256、20 个未改共享文件指纹和本批 source identity 见 `source-hashes.json`。完整提交 SHA 在 Git/协调消息中提供。

## 文件与精确方法范围

| 文件 | 范围 |
| --- | --- |
| `chengyinhub-quartz/.../util/ScheduleUtils.java` | 仅 `EXISTING_BUSINESS_TASK_TARGETS` 新增两行；`whiteList/createScheduleJob` 方法体逐字不改 |
| `chengyinhub-admin/.../quartz/util/ScheduleUtilsTest.java` | `businessTargets` 增加两组参数，复用三类准入/拒绝测试 |
| `chengyinhub-admin/sql/migration_20261023_g_wp08_delivery_jobs_paused.sql`（新增） | 预检 + 两条缺失插入 + 回读；无 DDL/UPDATE/DELETE，不执行 |
| `chengyinhub-admin/.../quartz/service/Wp08DeliveryJobInvocationTest.java`（新增） | 7 条真实任务/Quartz/coordinator 单测，JDBC 和消费边界使用替身 |
| `chengyinhub-admin/.../business/it/Wp08DeliveryJobsMySqlIT.java`（新增） | 7 条真实 MySQL seed/fence/消费/重放回读；Quartz 计时与日志存储使用替身 |

完整路径见 manifest。其他交付仅在本目录。未改 B 的 IM/掉落/徽章消费者、D 的 `CmsRegistrationServiceImpl` producer、coordinator、Quartz 抽象入口、SysJobService、controller、权限、包白名单、根 pom、CI 或旧 migration。

## 失败机制及最小修复

现有 R7 seed 是 `imNotificationDeliveryTask.deliverPending()`，每分钟一次；R1 seed 是 `roamRewardDeliveryTask.run()`，每十分钟一次。两者原 seed 均 `status='1'`、`concurrent='1'`、`misfire_policy='3'`。但 ScheduleUtils 精确表缺这两个 business task，business 包又不在旧包白名单，因此管理员正常新增/编辑会被拒绝。不能靠扩大整个 business 包来修。

本批只新增：

- `imNotificationDeliveryTask.deliverPending()` → `com.chengyinhub.business.task.ImNotificationDeliveryTask`
- `roamRewardDeliveryTask.run()` → `com.chengyinhub.business.task.RoamRewardDeliveryTask`

沿用 `AopUtils.getTargetClass` 的实际类型检查；同名错误 bean、类名形式、别名、带参数、getClass/其他方法继续拒绝。原 `Constants.JOB_WHITELIST_STR` 逐字不改。

原 seed 仅精确字符串判断不存在，空格形式可能造成重复 canonical 定义；R1 的旧读回还把已有主动启用状态视作不通过。新增 forward 候选使用二进制 TRIM 比较：只补缺失；已有暂停、启用、自定义 cron/group/remark 都原样保留。发现重复或已知不规范 canonical 形式时，断言 runner 必须在任何插入前停止，不自行选择哪一行或激活旧行。未知 Spring alias 不能由 SQL 解析，仍交既有应用 canonical-definition 校验在调度前 fail-closed。

## seed 与执行合同

| 环节 | 合同与证据 |
| --- | --- |
| jar 准入 | 只有上述两个精确 target/type 新获准入，旧安全规则不扩大 |
| SQL 预检 | sys_job 形状、intent/cursor 列、事务表与 R7 消息幂等键；重复 target、已知类形式/内部空格/无括号形式均先拒绝 |
| 缺失登记 | R7 `0 0/1 * * * ?`；R1 `0 0/10 * * * ?`，均默认暂停，不补填为启用 |
| 已有定义 | 不 UPDATE，不 DELETE；原暂停/启用/计划/备注字节值不改，其他任务不碰 |
| 提交与回读 | 两项插入在显式事务内，数量回读成功才 COMMIT；断言失败 runner 须 ROLLBACK，不能把脚本当普通 mysql source 忽略 SELECT 的真假 |
| 装载 | 既有 `SysJobServiceImpl.init/refreshPersistedSchedule` → canonical 唯一校验 → `ScheduleUtils.createScheduleJob`，暂停仍暂停 |
| 回调准入 | 既有 `AbstractQuartzJob.execute` → `MySqlSchedulerCoordinator.execute` 的 leader/generation/会话/tasklock/firetime fence → `isCurrentInvocation` 重新读 DB 暂停/删除/target 状态 |
| 实际调用 | 既有 `JobInvokeUtil` 调用两个实际 task bean；没有新 built-in registerCron、没有独立调度线程或替代执行器 |
| 业务消费 | R7 读 PENDING 原 intent，调用原 `IImService.deliverSystemNotification`；R1 调原掉落 reconcile，finally 调原徽章 reconcile；不生成新的抽奖、finish、事件身份或补造资格 |

controller 的 `monitor:job:add/edit/changeStatus` 等权限不变。单独启用或手动运行仍须走获批操作者及真实权限流程；本批不调用它们。广义源码治理授权不等于生产任务启用授权，当前新 seed 只暂停。

## 测试设计与实际状态

`ScheduleUtilsTest.businessTargets` 新增两个参数，覆盖真实类型放行、同名错误类型拒绝，以及参数/别名/类形式/其他方法拒绝，共增加 6 个参数化执行案例。旧目标列表不删、不改、不跳过。

`Wp08DeliveryJobInvocationTest` 的 7 个方法覆盖：seed 暂停状态装载后仍暂停且已排队回调不消费；明确启用的夹具触达实际任务并仅消费原 ID；旧 tasklock 阻挡两个任务；排队后提交暂停再检查；同 firetime/停止后回调不能重复；R1 错误仍执行 badge 恢复且 Quartz 记失败；精确 target 不解除 canonical alias 重复保护。JDBC 与消费边界是替身，不能作 MySQL 证明。

`Wp08DeliveryJobsMySqlIT` 的 7 个方法覆盖：缺失插入两次仍两行且暂停/原 cron 不变；已有暂停/启用/自定义与其他任务完整保留；重复/类形式在插入前失败；缺 intent schema 不登记；已提交原意图在默认暂停时不消费；仅随机测试库内显式启用后，实际 IM 消息/未读数、EXP 及获得明细、原徽章与交付终态一并回读，重放不重复；真实 MySQL task session lock 持有期间两个消费者不能执行，释放后可消费。

IT 使用仓库真实 base DDL 和原 migration 的建表/幂等结构、真实 MyBatis/事务代理、原 ImService/GrowthService/RoamRewardDeliveryService/RoamBadgeDeliveryService，Quartz 计时和日志存储为替身。XP 使用原冻结值 9，徽章使用原 intent；coupon/drop 外部依赖被设为不应调用的边界，没有调用 roll 或新发券。此 IT 不覆盖 coupon 分支，不证明真实 cron 到点、生产 HTTP/RBAC 或外部渠道。

IT 必须显式提供 `CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD/DB_SUFFIX`，只允许回环 MySQL 8；不跳过、不使用默认连接。UUID schema 成功建成后记录精确 `createdDatabase`，AfterAll 只 DROP/读回该库；DriverManagerDataSource 无池要关闭。所有清理仅限新建测试库；本环境从未执行。没有全局清理或生产 DDL。

实际执行结果仅有 `validation-output.txt` 的静态范围/指纹、SQL 结构、Git diff 空白和凭据扫描。`test-status.json` 明确列出所有 Java/MySQL 为 NOT_RUN。未安装依赖，未启动后端/数据库，未 dispatch workflow。受控 CI 由 root 统一安排：先编译及本批测试，再联跑现有 Scheduler/任务/消费者回归；只有同一集成 SHA 的证据才可验收。

## 跨 owner 与未决项

D 仅获准修改报名 producer 两个通知方法，本批未碰。本公共基线的报名成功路径仍可见直接 `sendSystem`；所以本批 IT 用已提交原 intent 证明消费候选，不能声称整个 signup→intent→message 已完成，须 root 与 D 合入后同 SHA 验证。

B 的消费者合同及 mapper 逐字保留。root 应核对 B 实际冻结候选与本批兼容，并验证通知事务/回执、券源回执等；不能用本批 target 准入代替消费者验收。R1 coupon 分支、真实 Quartz 时钟/主切换/线程池及真实操作者权限仍待受控环境验证。旧 `SchedulerSessionLockMySqlIT` 可补 Druid/锁会话真实行为证据，本批不重写调度器。

尚需运营决定仅是何时启用两项任务；当前已按授权保留默认暂停，无须用户再次选择技术身份、cron 或放宽白名单。启用条件至少包括 D/B 接线、同 SHA 测试回读、原意图积压与容量检查、真实权限验收。没有替 owner 做启用决定。

## 迁移、回退限制

新增文件是暂停登记的 DML forward 候选，不新增生产表，不编辑旧 migration，不执行任何 SQL。未来应用前须审核精确 jar、确认既有意图 schema、采用批准的断言 runner、排他迁移和生产许可；不能自动 source 到当前实例。

尚未应用时可直接撤回本候选。若已经补登记，应保留其他操作者原有任务和全部业务意图/消息/EXP/徽章/券回执；不得盲删 target 或清空积压。停用/回退须先经正常权限暂停对应新登记任务并确认在途 work 及原回执，不能以租约过期证明外部未执行。撤销精确准入不会回收已发权益，也不会把业务唯一身份释放给新号重试。
