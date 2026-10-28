# 集成接线与只读预检（D 不执行）

候选父 source 为 `33ffb50086e85fca2238d9ca75df585a46226835`。只取本分支相对此父 source 的 delta；不要把本地 2ec HEAD 的全部暂存差异当成本轮补丁。生产仅 `CmsRegistrationServiceImpl.java`，B/E/G 的实现和接口不变。

## A/root 所有的手工 fixture

`RegistrationFundsMybatisIntegrationTest.setUp()` 新建 `CmsRegistrationServiceImpl` 后未注入 `systemNotificationIntents` / `imService`。A/root 应补齐其通知桩，保持原资金/库存/积分真实 mapper 与断言；D 不改该文件、不用生产 null fallback 掩盖。

最小示意（由 A/root 对实际 import/fixture 风格适配）：

```java
ISystemNotificationIntentService intents = mock(ISystemNotificationIntentService.class);
when(intents.findIntentId(anyString())).thenReturn(null); // Long 默认 0 不是“无记录”
when(intents.enqueue(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenReturn(1L);
ReflectionTestUtils.setField(target, "systemNotificationIntents", intents);
IImService im = mock(IImService.class);
when(im.deliverSystemNotification(anyLong())).thenReturn(1L);
ReflectionTestUtils.setField(target, "imService", im);
```

若该资金 fixture 使用主题报名，须提供其真实/明确的 `cmsTopicMapper` 依赖；不能为测试加空依赖生产降级。独立活动无主题卡仍用原 `signupNoticeExtra(null)` 语义。上述 fixture 是资金测试的通知替身，不是 B 持久化证明。

## root/G 现有 runner 登记建议

- Class：`com.chengyinhub.business.service.impl.SignupNoticeAfterCommitPersistenceMySqlIT`。
- 存储 mysql，预期 18 个测试；既有源码标准条件注解 `@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")` 保留。按现有 scanner/registry 的 gate 格式登记该变量，不新建自定义 flag/framework，不增加跳过。
- 连接仍由既有 `MysqlIntegrationTestConfig` 的受控 PORT/USER/PASSWORD 等前提提供。缺必要配置不能伪绿。使用 scratch runner 原路径，数据库按每次 UUID fixture 隔离，验证 cleanup。
- 同时执行 producer9、Standalone4、既有卡片helper2、付款CAS合同1、B `ImNotificationIntentTest`6 / `ImFoundationBMybatisIntegrationTest`4 及 A 修正 fixture 后相关资金真库回归；此处是建议验收清单，不是已执行输出。
- 新 IT 使用原报名 JDBC fixture adapter，但真实 B MyBatis IM/intent mapper、事务代理、dispatcher 和 task；不声称覆盖生产报名 mapper或完整资金算法。手工同步单测不是真实事务证明。

## 只读数据库预检建议

下面仅是供已授权受控环境操作人员审查的查询，D 未连接或执行。没有提供/执行新 DDL，不申请凭据。

```sql
SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('cms_registration','im_notification_intent','im_message',
                     'im_conversation','im_conversation_member');
SHOW CREATE TABLE im_notification_intent;
SHOW INDEX FROM im_notification_intent;
SHOW INDEX FROM im_message;
SELECT status, COUNT(*) AS total
FROM im_notification_intent GROUP BY status;
SELECT COUNT(*) AS invalid_receipts
FROM im_notification_intent
WHERE (status = 'PENDING' AND (message_id IS NOT NULL OR delivered_at IS NOT NULL))
   OR (status = 'DELIVERED' AND (message_id IS NULL OR message_id <= 0 OR delivered_at IS NULL))
   OR status IS NULL OR status NOT IN ('PENDING','DELIVERED');
SELECT event_key, COUNT(*) AS total
FROM im_notification_intent
GROUP BY event_key HAVING COUNT(*) > 1;
SELECT job_id, invoke_target, status, concurrent
FROM sys_job
WHERE invoke_target = 'imNotificationDeliveryTask.deliverPending()';
```

应确认 InnoDB、永久原事件唯一键、原消息幂等约束，所有参与表同源且生产者/消费者用同一预期事务管理器。查询不扫描/输出原 recipient、payload 或付款隐私。异常状态不能静默清理或改值；交 B/G/root 决策。G 负责精确 target 与已有暂停状态合同，D 不启用任务。没有原意图的历史付款不得因部署补造通知。

新旧 producer 不可双轨并行；保留原回执和消息、不删除失败行。停用/回退时先核对 pending 与已投递事实，继续以原 ID 恢复，不能让旧 `sendSystem` 再生成同事件消息。
