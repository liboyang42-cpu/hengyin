# WP08 W17：补位供给首次生效通知，只读结论与施工草案

结论：确认一处实际恢复缺口。`ChapterMerchantNodeServiceImpl.audit()` 在补位供给首次 `0→1` 成功后，仅把玩家通知放入内存 afterCommit；受众也在回调中才查询。业务提交后进程退出会永久失去这次通知，已有业务终态既无原收件人快照，也无投递回执，原恢复任务不能凭终态重建通知。本交付只新增 D 目录报告、源码证据及未应用 patch；共享生产实现、测试、迁移均未修改。

## 身份、范围与证据边界

- 公共仓库 `liboyang42-cpu/hengyin`，当前观察 source `d49c12b820419308236010e0710f96d7bf48c4c9`，tree `c3a3ce6d95abc054d0183bb27b409b058476757a`。原读取源为 dbbb；本轮读取完整公开 d49 tree，28份证据逐blob一致，非AI main Java全部未变；唯一main Java差异为排除范围内的AiCreatorServiceImpl。未从三点compare的共同祖先差异推断文件相同。
- 原工作区、57文件 graph 候选及旧提交全部保留。文档写在原 graph 工作区独立 D 子目录；该不完整本地 checkout 的 shell HEAD 仍为公共 `2ec688...`，本次源码读取以公共 d49 tree 的 blob 匹配为准，不以 shell HEAD 替代观察源。
- 当前执行器 running/connected。本轮使用 cloud-environment runtime skill 做只读运行状态核对，无环境重建。
- 逐 blob 匹配读取 2,432 份当前 main Java；另由 GitHub connector 读取并核对其余 12 份非 AI main Java（含新 HTTP/付款事实/玩法入口），检索无该 producer 或额外调用者。21 份 AI 源排除，未作全站通知治理完成声明。
- `evidence.json` 固定 28 份调用链/mapper/消费者/原测试/原迁移的 Git blob、SHA256、当前行号。原 27 项/9 个恢复入口清单仅作定位索引；本结论重新核对当前实现，不沿用旧行号。

## 真实入口与原事务

1. `POST /api/merchant/chapter-node/audit` → `ApiChapterMerchantNodeController.audit:228–239`，原登录、内容送检、project owner 解析 → `IChapterMerchantNodeService.audit`。Controller 返回审核后的节点对象；没有全通道送达统计。
2. `ChapterMerchantNodeServiceImpl.audit:257–330` 是 `@Transactional(rollbackFor=Exception.class)`。当前唯一 `approveReplacement` 生产 caller 就在这里。原审核/坐标/模板/条款校验通过且 `approve=true`、补位 offer status=0、`approveReplacement(...)=1` 后，才调用 `notifyPlayersOfReplacement:316`；之后继续保存点位审核结果，后续失败整体回滚。
3. 实际锁序：`TopicSupplyReviewService.lockBeforeChapter` 先定位，再 **publication task→topic**；调用方继而 **chapter→同 topic 回读**；随后 application、原 offer、replacement offer 的锁定读取，模板/条款校验，供给 CAS，再登记通知，最后点位写。不是旧注释所暗示的简单 chapter→topic。本草案不移动现有锁或经济动作。
4. `MerchantChapterApplicationServiceImpl` 的撤回和审核调用 `auditAlongApplication`；后者只逐节点更新审核字段，**不调用 audit 或 approveReplacement**。`NodeNpcService`/`PlayCheckinCodeServiceImpl` 等仅复用读/归属接口；后台 `CmsTopicNodeController` 只引用审核常量。上述路径不纳入补丁。
5. 原事件身份是**本次补位 offer 主键**，不是原退出 offer、节点 ID、申请 ID或通知正文。`MerchantOfferServiceImpl` 新插入补位供给时 status=0；CAS 要求 replacement_of_offer_id 非空、withdraw_reason 为空且未过期。已退出行保留非空原因，不能走同一正常 CAS 再生效；下一次替补使用另一补位 offer。越权数据库修复/任意改写历史字段不属于已证明的正常生命周期。

## 可复核的丢失机制与受众变化

`notifyPlayersOfReplacement:332–346` → `CommittedBusinessNotice.executeAfterCommit:43` → 内存 synchronization → `dispatchInNewTransaction:57` → 同一进程 scheduledExecutor 10ms → REQUIRES_NEW 中执行 Supplier。eventKey/businessId 只用于日志，无数据库登记。调度拒绝、进程退出、发送失败仅记录/告警，没有通知恢复任务。

具体反例：原审核成功提交（replacement=1、node=approved）→ 尚未运行 afterCommit 或 executor 中尚未取出任务时进程终止 → 重新请求审核被“只能审核待审版本”拒绝；即使后来点位重新提交为待审，同一个 active offer 也跳过 status=0 分支 → 没有 IM intent/message，也不会重新进入原通知。这里不是以调用次数推断缺陷。

原 Supplier 运行时才调用 `RegistrationEntitlementMapper.selectAffectedPaidMembers`：同 topic/chapter、entitlement status=0、registration payment_status=2 且 registration_status=2、member>0；兼容 topic 直购与 activity→topic，SQL distinct/order by member_id。随后 LinkedHashSet 去重并排除原 topic owner。若回调前权益核销/取消或新付款，实际受众会变化；从 live 表重扫无法证明当时该通知谁、谁已收到。

原消费者链为 `notifyOfficialBroadcast` → `NotifyServiceImpl.fanout` → InAppNotifyChannel 的无业务键 `sendSystem` + WxSubscribeChannel。fanout 的 true 只表示至少一个通道 true；没有逐人/逐通道收据。并且当前 Supplier 包在新数据库事务中，微信也会在其中调用，不能把该事务称作外部原子投递。

## 最小施工范围与冻结合同（尚未授权生产落地）

`proposed-source.patch` 只针对当前 blob `c0675aaa3ef31f272883e7f775d9776a184ef0e5` 的 `ChapterMerchantNodeServiceImpl`：替换唯一通知 helper、专属注入并增加一个专属微信调度 helper。原 audit/submit/auditAlongApplication、所有授权/锁序/CAS/条款/权益/库存/资金/退出算法保持原字节。没有新通用服务/缓存/依赖/DDL，也不改 W19、广播、AI、NotifyServiceImpl、ImServiceImpl 或调度器。

- 只在原 `approveReplacement=1` winner 的**原事务**中，用原 SQL 一次读取受众；维持有效成员及 owner 排除条件，按 memberId 升序登记以固定 intent 锁序。冻结的是该原事务实际读到的 eligible 集合，**不是承诺在提交瞬间线性化的所有付款人集合**。原 SQL 是普通一致性读，既有 RR 快照/并发权益变化需受控对照验证，草案不私加付款或 member 锁。
- 每人永久键 `chapter-supply-replace-player:<replacementOfferId>:<memberId>`，两个正 BIGINT 的最长键为69字符，满足现有96字符合同。复用 `ISystemNotificationIntentService.findIntentId/enqueue`；已有 intent 仅复用原 ID，不用 live topic/recipient 重写 payload。
- 新意图固定原 recipient、`ImMessage.MSG_TEXT`、`title + "\n" + body`、extra=null，与当前站内正文一致。任何登记失败让原事务失败；无“通知失败仍把原业务提交了”的危险 fallback。后续原点位写失败也应回滚全部 intent。
- afterCommit 仅通过既有 helper 唤醒 `deliverSystemNotification(原ID)`，删除该事件旧全通道 fanout，避免 IM 双轨。一次 recipient 的失败不允许重发其他已成功成员。
- 首次新 intent 的微信仍一次事务外尝试，沿用原 title/sub 和通道，不进入本地恢复。缺模板/false/异常/响应丢失都不能记成功；false 不足以区分确定未发与 UNKNOWN。仅日志标记“未确认”，**不新增持久微信收据、不声称其可恢复/全送达**。保留原 offer/member 身份供人工核实；无查询能力不发明查证接口、不用租约到期重投。
- 重复审核不能补造历史通知。部署前已生效但无 intent 的 offer 不补造；已有 intent 的恢复只交现有消费者，不重新触发业务 CAS。原审核 API 仍表示“审核操作成功”，不是“通知已送达”；不能将 intent PENDING 或微信 UNKNOWN 展示为全送达。

## 已有恢复口与 owner 依赖

`ImServiceImpl` 同时实现 ISystemNotificationIntentService/IImService：enqueue 默认 REQUIRED，唯一 event_key + frozen request_hash；消费者锁 intent→系统会话/会话成员→消息，固定 `notification_<intentId>`，消息/未读/DELIVERED 同事务。DELIVERED 回读原 messageId；提交 ACK 丢失无需再次发送。消费者不反锁 topic/chapter/offer，生产者登记阶段不锁 IM 会话。

`ImNotificationDeliveryTask.deliverPending()` 固定 maxPendingId、100条分页、逐项事务失败继续后页。`ScheduleUtils` 已有精确 `imNotificationDeliveryTask.deliverPending()` target，B 原表迁移和 G 准入迁移已有默认暂停/保留已有状态合同。本轮只读源码，**没有读取真实 sys_job、表结构、运行状态或消费日志**；不能从源码登记推断任务已启用。由 root/G 验证，D 不改或启动任务。

后续所需生产授权仅上述 helper/注入；章节领域 owner 核对审核事务及受众时点，B 核对原 intent 消费语义，G/root 持有任务和验收。特别是这是玩家通知，当前交付严格停在 doc-only 草案，不能把前期“不任意迁移玩家通知”当作已获实现授权。

## 必须保留及新增的回归范围（均未执行）

当前已读原 `ChapterMerchantNodeServiceImplTest` 39、`ChapterMerchantNodeAuditFreezeAcceptanceTest` 4，全部原 case/权限/异常/条款/坐标/金额断言保留。首类 `replacementApprovalChecksSupplyPointAndTemplateBeforeActivation` 当前靠 businessNotice=null 跳过通知；落地时只为真实 winner 装配原 intent doubles/实际事务，不删除该断言或把图/审核 mock 成成功。原 `ImNotificationIntentTest` 6、CommittedBusinessNoticeTest 3、ImNotificationDeliveryTaskTest 2 是可复用消费者表征，未声称已跑。

新增本域最小10项受控回归：

1. 越权/拒绝/坐标或模板无效/供给 CAS=0：原业务、intent、消息和 WX 均零新增。
2. winner + 原节点写后抛异常：真实生产 mapper/同 Spring manager 下 offer、node、全部 intent 原子回滚；含第二 recipient enqueue 失败。
3. winner 原事务受众去重/排除 owner/无效ID、topic/activity 两类查询和原文案；空受众不制造伪意图。
4. 提交后丢唤醒/executor拒绝：新建消费者对象调用现有任务能恢复；分别报告该模拟与真正进程 kill，不能互相冒充。
5. 多收件人部分成功：恢复只补 PENDING；已交付成员 message/unread 不增加。
6. 双 worker 同 intent 与 ACK 丢失：同一 messageId、只一条消息/一次未读，终态持久。
7. 重复审核/多节点/原 active offer 重新提交：不新建同事件；历史无 intent 不补造。
8. 首次登记后改 topic 名/owner/权益/新增买家：恢复原 recipient/content，不查 live audience；同键异payload enqueue 原409断言保留。
9. WX 模板缺失/false/异常/响应丢失：0真实WX，事务外至多一次受控调用；IM恢复不唤醒WX，部分成功不展示全送达。
10. 两个不同补位事件/并发付款核销受众读：验证事件键互不吞并、原锁序和原 SQL 快照边界；记录受众规模及原事务时长，不能用任意上限截断玩家。

## 实际检查、迁移与回退

实际执行：公共 tree/blob 对照、全部非AI main Java 的调用检索、28份证据 hash、草案 `git apply --check`（exit0，未应用）、静态核对 helper 以外生产字节不变。**没有 Java 编译/测试、MySQL、HTTP、Spring 启动、CI 派发、真实WX或任何DDL。** 缺口反例为源码可推导故障路径，尚未实跑故障注入；patch不等于实现完成或验收通过。

无新增 migration，已有表/唯一键/任务准入的实际状态需 root/G 在受控环境只读预检。未清理历史通知、资金、权益或审计。若后续落地，回退不能删原 intent/终态消息；旧 fanout 与原消费者并行会双发，不能仅恢复旧 helper 即称安全回滚。需停止该 producer 的发布切换、保留原 intent，由 root 分配单一投递责任。
