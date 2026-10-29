# 投诉先行垫付通知：可恢复候选，待业务 owner 接线

基础设施 parent `7da3e4ea385596aa850dc760f2cf6adc6c554602`、冻结源码 `974f6af8a25f76baa31c39f8f4e48ffcf31f7846`。其后续候选已在 `CoopComplaintServiceImpl.advance` 原通知提交点实接同事务 durable owner，并同步 2 个原投诉测试夹具；资金/CAS/冷静期算法未改。当前 `review.patch` **仅保留 G 的精确任务白名单**，不再重复提交已实接的业务 hook。参见 ../complaint-notice-hook/README.md；仍待 root 统一 Java/真库验收。

## 确证与复用

在该 parent 对 2505 个生产 Java 文件作调用追踪：2317 个本地 blob 与公共树一致，188 个读取公共当前源码；7 个调用者类为 ChapterMerchantNodeServiceImpl、ClubMemberServiceImpl、CoopComplaintServiceImpl、MerchantChapterApplicationServiceImpl、VerificationRiskService、CmsRegistrationServiceImpl、MerchantOfferServiceImpl。后两项 signup/W19 已有原意图，不重迁；玩家 fanout/不稳定受众及 AI 均不扩大本片。

选 `CoopComplaintServiceImpl.advance`：原 ACCEPTED→ADVANCED CAS 赢家完成本地余额/明细/冷静期后只登记内存 Supplier。事务提交后进程丢失、线程池拒绝或新事务内 IM 异常，仅有日志/告警，无投递原意图；工单 ADVANCED 使再次 advance 拒绝，冷静期恢复也不是通知恢复源。因此不能用再次垫付恢复通知。

复用 B 的 `ISystemNotificationIntentService.enqueue`、`ImServiceImpl.deliverSystemNotification`、原 IM mapper/永久唯一键与 `imNotificationDeliveryTask`。原文本精确保持 `title + "\n" + body`、MSG_TEXT、extra=null。既有 IM ledger 只有 PENDING/DELIVERED，原恢复任务明确仅站内；不扩展其外部语义。OfficialBroadcastLog 归 broadcast_id/member/channel、ClubPostMediaReviewOutbox 归 post_id/version/media_hash，支付 outbox 归支付/退款身份；不伪造这些业务身份来挂投诉微信。

## 单条合同

- 接线：原付款/余额算法、CAS/金额/冷静期均不改；唯一 winner 在原事务 MANDATORY 登记 `complaint-advance:<原工单ID>`。原 recipient/title/body 冻结，返回 ID 仅表示“已登记待投递”；advance 返回1仍只表示业务完成。
- 原业务事实 + IM intent + 最小微信伴随行同库同事务；登记失败整笔本地事务回滚。相同 key/payload 复用，同 key 异 recipient/title/body 冲突；即便合并后的 IM 文本相同也冲突。拒绝无新意图/消息/外呼；不补造历史 ADVANCED 无意图记录。
- 提交后只将原 ID 唤醒至现有 scheduler；唤醒丢失由持久 PENDING 游标扫描恢复。IM 消息/未读/终态原回执继续由原消费者原子提交。新 owner 从不以 aggregate boolean/HTTP200 记“全送达”。
- 处理锁序：producer 原业务→IM intent→微信行；consumer 只短事务读/释放微信行，再独立 IM 事务，再独立领取微信行。绝不持微信行等 IM 行，不形成反向链；外呼无数据库事务。
- 微信 PENDING=该通道尚未尝试；原行领取 UUID owner，**先提交 UNKNOWN 再外呼**；固定 OFFICIAL_BROADCAST 事件和冻结载荷，既有 WxSubscribeChannel true（底层 errcode=0）才归同 owner 写 ACCEPTED，仅证明受理。false 混有缺模板与吞掉的 I/O，不能据此重发；异常、丢 ACK、进程停在领取后均保留 UNKNOWN/原身份。无租约、TTL、自动重置或编造微信查询接口；UNKNOWN 仅人工核实，当前无自动恢复授权。
- IM 失败但微信受理：原 IM 任务仍能独立恢复；IM 成功但微信 UNKNOWN：只复用 IM 原收据，微信不自动重发。`reconcilePending` 返回枚举数量，不是送达数。

## 迁移与接线前置（DDL 未执行）

新增 `committed_official_notice_wx`，永久 event_key/原 im_intent_id 双唯一、冻结 recipient/title/body/hash、三状态与 owner/attempt/accepted 时间约束；不改旧表/资金/审计，不回填历史。MySQL须支持执行 CHECK（8.0.16+）。迁移串行执行；新表已存在时停止并比对结构，不静默 CREATE IF NOT EXISTS。

只读预检：`SELECT VERSION(),@@transaction_isolation`；查 information_schema.tables 的上述新表（应不存在）、im_notification_intent/im_message/sys_job/coop_complaint 的 InnoDB；查 information_schema.columns/statistics 核实 B 永久 event_key 唯一键与原IM原子回执列；`SELECT job_name,invoke_target,status FROM sys_job WHERE invoke_target IN ('imNotificationDeliveryTask.deliverPending()','committedOfficialNoticeTask.deliverPending()')` 核对原暂停态。已存在同 target/新表必须人工比对，不清数据/回执。

新 Quartz 精确 target `committedOfficialNoticeTask.deliverPending()` 默认 paused，已有行/status 保留；G 白名单仅 review.patch，未改 workflow/registry/ScheduleUtils 原文件。Root 负责迁移批准、精确注册与是否启用；原本暂停的任务不得自动开启。未接线/未批准迁移/未运行真库前，不称闭环验收。

回退：暂停新 task/停止新 producer 登记，保留新表及 UNKNOWN/ACCEPTED/IM 唯一身份；不能 DROP/删行/重置状态/回退成双轨直发。已提交原 IM 仍由原任务恢复；微信未知须按原号核实，不能靠回退释放身份。

## 验证边界

13 个普通 @Test 真库源码：原事务回滚、提交后 executor 拒绝与重建任务、CAS重复、相同身份复用、title/body歧义冲突、冻结受众与文案、false/异常UNKNOWN、外部受理后回执丢失、IM回滚独立恢复、双worker/丢本地ACK、owner fence、MANDATORY与暂停注册。实际生产 IM mappers/消费者/新 owner + canonical migration；业务 CAS 为 JDBC fact adapter，不冒充完整真实资金 mapper。WX为受控替身，真实外呼0；重建对象不等于 process-kill 证明。

实际仅 Python/XML/范围与 review.patch 应用静态检查；Java/MySQL/Spring完整启动/CI/生产 DDL 全部 **NOT_RUN**。原投诉单测全部 case 和经济/异常断言保留，通知断言迁到新 typed owner 并加强冻结文案；旧真库金融竞争夹具只补注入。本候选没有删除/skip/放宽旧场景。

后续 7 项真实生产者回归复用原投诉金融 scratch 与生产投诉/会员余额/明细 mapper，实际 Controller/Service→事务内意图→postcommit/恢复任务接线见 complaint-notice-hook；仍无完整冷静期/权限过滤器/HTTP/真实WX实证。
