# W19 集成说明（未执行部署/DDL/CI）

父 source `00fc58c50febc3366f78af600c8f12636cd13de5`，父 tree `b0ae8ba46eca8bcc3c2e61abb5a6c4e2536e101c`。仅取本轮 delta，不把本地 2ec HEAD 的全部暂存变化当成本补丁。

本轮 source/test 文件仅六个，见 evidence.json 的 source_files。唯一生产实现为 MerchantOfferServiceImpl 的 owner 通知；原玩家 supplier/所有其他方法、Controller、mapper、Notify/IM、ScheduleUtils、pom、SQL 和 CI 均不改。

建议 root 的受控执行：

- 新 `MerchantOfferOwnerNoticeIntentMySqlIT` 21 项，标准 `CHENGYIN_FUNDS_MYSQL_IT=1` gate；使用既有 MysqlIntegrationTestConfig 的 `CHENGYIN_TEST_MYSQL_PORT/USER/PASSWORD` 显式配置和 scratch runner，不新增自定义 flag/跳过逻辑。
- 新 `MerchantOfferOwnerNoticeIntentTest` 11 项；原 MerchantChapterOfferWithdrawTest 10、FreeExploreNodeSupplyMySqlIT 8、ApiCoopOfferWithdrawTest 10、ApiCoopOfferWithdrawRouteTest 8。
- 查看逐类 XML 数量、0 failure/error/skip 和独立 cleanup=CLEAN；不要仅看整个 job 绿。新 IT 是真实退出/申请 XML＋真实 B mapper/消费者，主题/章节/审核边界明确是 fixture，不替代原8完整断言或 G 的调度验收。
- 本轮已补两个手工 new MerchantOfferServiceImpl 的已知退出 fixture 新依赖；其他构造处若不调用本退出路径无需为了编译重写。生产字段必须由 Spring 正常注入，不能增加空依赖成功 fallback。
- 测试 WX 始终 mock；禁止真实付款、微信、生产访问或启用任务。

只有原退出 winner 登记，重复退出保持原提前返回，由 R7 消费其已存在 PENDING。已有原意图在 fresh 路径只唤醒原 ID、不重建 payload、不再排 WX；历史已退出无意图不补造。`enrollmentPaused`/`已退出供给` 是原业务结果，与通知通道送达分离。

运行下面命令可复核静态合同、生产范围、原断言和指纹；它不运行 Java：

```sh
python3 docs/delivery/architecture-day/D/w19-owner-intent/verify_scope.py
sha256sum -c docs/delivery/architecture-day/D/w19-owner-intent/CANDIDATE.sha256
```

已有 schema 的只读预检建议（D 未执行，须由已有授权环境操作人员核对）：

```sql
SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('merchant_chapter_offer','merchant_chapter_application',
                    'im_notification_intent','im_message','im_conversation','im_conversation_member');
SHOW CREATE TABLE im_notification_intent;
SHOW INDEX FROM im_notification_intent;
SHOW INDEX FROM im_message;
SELECT status, COUNT(*) AS total FROM im_notification_intent GROUP BY status;
SELECT COUNT(*) AS invalid_receipts
FROM im_notification_intent
WHERE (status = 'PENDING' AND (message_id IS NOT NULL OR delivered_at IS NOT NULL))
   OR (status = 'DELIVERED' AND (message_id IS NULL OR message_id <= 0 OR delivered_at IS NULL))
   OR status IS NULL OR status NOT IN ('PENDING','DELIVERED');
SELECT job_id, invoke_target, status, concurrent
FROM sys_job
WHERE invoke_target = 'imNotificationDeliveryTask.deliverPending()';
```

确认意图和原供给使用同一预期事务管理器/数据库、永久事件唯一键和原消息幂等约束。查询不输出 recipient/payload。异常数据交 B/root；不静默清理、不新增或执行迁移。任务暂停/启用由 G/root 原合同处理。

发布或回退都不能将旧 owner fanout 与新原意图消费者双轨用于同一事件。保留 pending/DELIVERED 原身份和所有资金/权益记录，不通过清表或删失败行解决问题。
