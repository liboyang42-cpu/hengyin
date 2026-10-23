# WP08：实际 afterCommit 与恢复源盘点

结论：afterCommit 本身不是缺陷。RoamBadgeDelivery、CoopDepositRefund、TeamGroup 的持久退款派发、附近分享撤回审计、CRM 文件删除与调度定义重载都存在各自恢复源，应保留。确认缺口集中在只有内存任务/闭包的通知：退款到账通知、报名成功站内卡片与订阅、保证金待缴提醒，以及 CommittedBusinessNotice 多个领域结果通知。不能把“业务终态可读”“通知尝试有日志”“存在 reconcile 方法”误当成可恢复通知。

推荐下一步只做**报名成功站内卡片的原意图接入**，复用已存在的 B 站内通知接口，不新建全站 outbox、不重定义支付/退款/优惠券规则。详细方案及文件所有权见 `minimal-cut.md`。本批只交盘点/方案，不修改生产代码或接线测试。

## 基线、冻结和文件所有权

- 公共来源仅 `liboyang42-cpu/hengyin`，扫描基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 冻结 D 候选：`e71777f375fcbb19a17b46647c36391ec03ed3b4`，tree `7a836874abeec1d9af3a23770dcc5d7e6868a4cf`。本批没有修改该工作树/分支/候选。
- WP08 独立工作树 `/workspace/architecture-day-d-wp08`，分支 `codex/architecture-day-d-wp08-audit`，从公共基线建立，单独提交。
- D 本批独占写入仅 `docs/delivery/architecture-day/D/wp08/`。A 的支付/资金实现、B 的 IM/意图实现、G 的调度注册与准入都只读。C 的玩法 Controller 不改。
- 代码观察依赖：公共基线 + 单独标注的冻结 D 广播增量；不声称包含 A/B/G 尚未集成的新候选，不猜其 commit。最小实现方案依赖 A/B/G 的现有合同，由协调者安排所有权切片。
- 协调者已授权在联合 CI 候选仅删除 `OfficialBroadcastDeliveryMySqlIT` 的冗余 `CHENGYIN_BROADCAST_MYSQL_IT` 断言，registry 使用 `gates={}`；显式类选择及 PORT/USER/PASSWORD 硬前提不变，7 个用例不变。该测试接线调整不在本批提交中，冻结 D 候选不改。

## 覆盖范围与证据等级

扫描所有 `**/src/main/**/*.java`，不是只查业务 Service：2,427 个文件；去掉注释和 Java 字面量后识别 15 处实际 `registerSynchronization`，其中 afterCommit 12、afterCompletion 3；没有 `@TransactionalEventListener`。追踪 CommittedBusinessNotice 的 16 处外部调用。冻结 D 候选另新增 1 处广播 afterCommit。因此覆盖观察中的同步注册共 16 处（13 个 afterCommit、3 个 afterCompletion），不把注释提到的旧回调算作代码。

`evidence.json` 保留精确路径、行号、回调类型、16 个 helper 调用点、关键来源文件 SHA256；D 增量带单独 commit/源码 SHA。以下表使用缩写，完整路径见该 JSON：

- `I/`：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/`。
- `H/`：同模块 `business/service/support/`；`T/`：同模块 `business/task/`。
- `A/`：`chengyinhub-admin/src/main/java/com/chengyinhub/`。
- `Q/`：`chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/`。
- `M/`：system `src/main/resources/mapper/business/`；`S/`：`chengyinhub-admin/sql/`。
- Java 文件名省略 `.java`。`R1` 等恢复注册编号见后表；“无”指已搜索的公共基线没有这一通知的持久待投/回执与消费者，不是推断生产没有某表。

没有访问运行数据库、日志平台或生产；SQL migration 只能证明仓库有注册方案，不能证明已执行、已启用或实际成功跑过。下表的“保留”是结构判断，不代表完成运行验收。

## 直接事务同步：副作用 → 持久源 → 注册 → UNKNOWN/owner fence → 经济影响

| ID / 实际入口 | 提交后的副作用 | 持久源及恢复能力 | 恢复任务/注册证据 | UNKNOWN 与 owner fence | 经济影响/判断 |
| --- | --- | --- | --- | --- | --- |
| D01 `I/RoamBadgeDeliveryService:46` | 原漫游徽章交付 | `roam_badge_delivery` 原意图、原 badge/source/session 快照与扫描游标；`prepare` 同业务事务写入 | `T/RoamRewardDeliveryTask.run` finally 调 `badges.reconcile()`；R1 | 同会员锁、原 source 身份、已有徽章回读、交付/终态同事务；失败保留原意图，不回滚 finish 再赚 XP | 权益影响；**健康模式保留**，任务迁移默认暂停待回读 |
| D02 `I/CoopDepositRefundService:53` | 合作保证金原路退款派发/查证 | `coop_invite` 的原支付号、退款号、金额、refund_state 与 lease token；`requestLocked` 先提交意图 | `reconcile` → `refresh`；`T/RefundReconcileTask:55` 经 CoopInvite 转调，R2 | 原号先查；只有明确 NOT_FOUND 且允许状态才同号恢复；UNKNOWN/归档/历史已退只查或人工；token/金额/CNY/支付号 fence | 真实资金动作；**保留，不改金额/退款规则**，不能因 afterCommit 而迁走 |
| D03 `I/TeamGroupServiceImpl:1511` | 已取消报名的现金退款唤醒 | 同业务事务建立 `refund_application`，`PAYOUT_DISPATCH_PENDING=5`；afterCommit 仅唤醒 payout | `T/RefundReconcileTask` 扫 0/5/6，R2 | 原 `RF+registrationId`；派发 lease token；已确认终态不被迟到写覆盖。callback 与 query 的详细资金规则归 A/B/G | 真实资金动作；**健康模式保留**，不修改库存/积分/权益回补 |
| D04 `A/web/controller/api/ApiRoamController:664` | 分享已关闭后补撤回审计 | `roam_presence_revoke_intent` 与关闭事实同事务；原 requestId/member/occurredAt，`consent_record` 原请求去重 | `RoamPresenceRevokeAuditService.retryDue`；R3 | owner_token claim/complete/fail fence；原 member/requestId 匹配；重试绝不重新打开分享 | 隐私审计；**持久源存在，保留**；恢复写失败仍需监控 |
| D05 `A/web/controller/api/ApiRoamController:847` | 回读已提交 badgeDelivery，更新返回 data | finish_result 和 badge 原意图已保存；这一个回调只是回读，不再发奖 | 交付本身见 R1；此回读无需独立 outbox | 回读失败只留待重试；不重新 finish/计 XP | 仅响应投影；**不能当交付缺源来重构** |
| D06 `I/MerchantCrmExportServiceImpl:415` | 删除过期/归属失配导出私有文件 | task 的失效/清空存储引用是持久 desired state；`expireAndClean:266` 还扫受控目录，对 DB 无引用 UUID 文件删除 | `MerchantCrmExportJob.cleanupExpired`，R4 内置计划 | 原 owner/version CAS 使下载失效；删除幂等；DB 回读失败不删；文件 key 不改指其他对象 | 隐私泄露窗口；**有孤儿重扫源，保留**，非“删除 intent 表缺失” |
| D07 `I/SysConfigServiceImpl:224` | 资金政策缓存失效 | 真源为 MASTER DB；资金政策当前读/加锁读绕过此缓存；丢失失效不等于资金规则回滚 | 无需通知恢复任务；后续读仍走 DB | key 白名单；异常记录；不能将缓存删除失败解释为写入撤销 | 资金政策相关但该副作用是缓存；**不迁 outbox** |
| D08 `Q/service/impl/SysJobServiceImpl:118`，定义变更分支 | Quartz 暂停、删除、创建/更新计划 | `sys_job` 已提交定义；fingerprint 变化时从 DB 重载，不依赖一次 callback | R5，`MySqlSchedulerCoordinator.maintainLeadership` 每 2 秒调用 refresh | leader 物理会话 + generation/task lock；执行前检查当前 status/target；旧 queued callback 被 fence | 可调资金/通知任务；**持久定义恢复存在**，不改 G 调度规则 |
| D09 同 D08，`run:281` 一次触发分支 | 手工 `scheduler.triggerJob` | `sys_job` 只保存定义，不保存这一次“立即执行请求”；进程在提交后/触发前退出时无持久触发收据 | 没有针对这次手工触发的 replay queue；R5 重载不会自动补发这次 trigger | 不得把未见响应当“任务没执行”而盲触发；下游自身业务 fence 仍必须存在 | 取决于被触发任务，可能真实出款；**单次触发有缺口，非本次通知切口** |
| D10 `I/RefundServiceImpl:480` | 到账终态后发 REFUND 用户通知（IM+WX fanout） | `refund_application` 终态可读，但**没有通知原意图/逐通道回执/待重试字段**；false/exception 仅日志 | 退款 R2 只扫 0/5/6，排除终态 1/4；无通知消费者 | 主状态终态 CAS winner 才发；重复终态回调 ACK 不再发；fanout true 仅任一通道成功，false 可含 WX UNKNOWN；不能用新退款/回滚资金来补消息 | 通知不应改变退款金额/状态；**确认通知丢失风险，资金派发恢复不能替代它** |
| D11 `I/CoopInviteServiceImpl:687` | 锁价后待缴保证金提醒 | `deposit_status=5` 的应缴事实存在，但没有提醒状态或逐渠道收据；boolean 返回被忽略 | 未找到应缴提醒恢复任务；保证金退款 R2 不消费这个提醒 | 原 payer/topic 在闭包里，事件不落通知源；若 WX 结果不明不能盲重发/消耗授权 | 可能错过应缴，**不等同保证金未登记**；资金字段/固定金额不由 D 调整 |
| D12 `I/CmsRegistrationServiceImpl:2604`，QR 分支 | 为已付报名生成核销二维码/文件 | paid registration + 缺 qrcode_url 可重扫；`generateVerificationQrcode` 已有 URL 返回，但缺 URL 时生成新 code | `T/VerificationQrcodeRepairTask.repairMissing`，R6 | 7 天 create_time、10 分钟支付宽限、部分活动有效期过滤；不是无限历史恢复。代码并非可证明的外部确定身份，不能把注释“幂等”当并发/外部写原子证明 | 已付款履约可用性；**有恢复源但有窗口与生成边界限制**，不随意重构 A |
| D13 同 D12，signup IM 分支 `2586` | 报名成功站内卡片 | 已付款报名行存在；回调只把 sendSystem 放入内存 scheduler，没有 `im_notification_intent` 生产行/receipt marker | 通用 R7 消费者存在但本调用未 enqueue；支付重放 paymentTransitioned=false 不再安排 | 新事务只改善隔离，不持久化 Supplier；中断/拒绝/消息已写响应丢失均无该事件恢复收据 | 已付款通知缺失，**选为最小切口**；不改任何付款规则 |
| D14 同 D12，signup WX 分支 `2597` | 报名成功微信订阅 | 支付事实持久，但订阅尝试无原意图/通道状态；boolean 被忽略 | 没有 signup 微信恢复消费者；R6 只修二维码 | 网络 UNKNOWN 与未配置/拒收折为 false；不得靠支付回调重放盲投 | 授权消耗/通知风险；**单列缺口，不在最小 IM 切口中冒充修复** |
| D15 `H/CommittedBusinessNotice:47` | 领域通知/告警/QR Supplier 入现有 scheduler | helper 只保留 JVM Supplier，eventKey/businessId 仅日志/告警标识；不是 ledger，也没有唯一约束 | 无 helper 消费者；逐调用效果见 C 表。某些领域另有恢复源不能一概判丢 | REQUIRES_NEW + 10ms delay 不是持久意图；外部动作包在 DB tx 也不产生外部回滚能力；不许整体改成通用 outbox | 有资金语义文案但 helper 本身不做经济核算；**逐领域治理** |
| D16 冻结增量 `I/OfficialBroadcastServiceImpl:99`，仅 e717 | 官方广播 afterCommit 唤醒 | 新广播主记录 + 冻结逐成员通道日志，站内消息/成功回执原子提交；主 pending 支持恢复 | `A/web/task/OfficialEventTask.run` → 新 selectRecoverableBroadcasts，R8 | subscribe 先持久 UNKNOWN 再事务外调 WX；只有 true 成功；原日志身份不释放，UNKNOWN 人工核实；旧行迁移保守 UNKNOWN | 通知/授权，无新增经济动作；**候选冻结待 Java/MySQL 与迁移审评** |

## 三处 afterCompletion：不应混进通知 outbox 迁移

| 实际入口 | 副作用/源/恢复 | owner fence / 影响 |
| --- | --- | --- |
| `I/AppSessionRegistryServiceImpl:170` | 事务结束后释放 Redis 会员变更门闩，成功/回滚均执行；key 有 30 分钟 TTL，认证 epoch 自有逻辑 | Lua 按 mutationId 比较释放，不删接管者的键；TTL 只是门闩寿命，不证明外部动作未执行。鉴权实现只读，不改 |
| `I/PlayCheckinCodeServiceImpl:106` | rollback 时 CAS 将本次 consuming 恢复原码，其他结束删除本次占用；原签名到期时间保持 | 比较原 code/reserved 值，不能覆盖商家新码；不重新发码延长到期。关联核销/权益规则归原 owner，不能全禁同步回调 |
| `Q/util/MySqlSessionLock:99` | 事务结束释放物理会话锁，失败时终止自身连接 | IS_USED_LOCK/CONNECTION_ID 与自身 lease/session；不是外部业务投递，不能把租约到期当付款未发生 |

## 16 个 CommittedBusinessNotice 外部调用：逐效果核查

C 表沿用 D15 的“无 helper 持久意图/无 helper 恢复任务”事实；有独立来源/恢复的逐项注明。每条包含真实业务源、恢复注册、owner/UNKNOWN 和经济影响，不能据 eventKey 名称宣称幂等。

| ID / 调用点 | 副作用 → 持久业务源 | 恢复任务注册 | UNKNOWN / owner fence / 重复身份风险 | 经济影响 |
| --- | --- | --- | --- | --- |
| C01 `I/ChapterMerchantNodeServiceImpl:334` | 供给补位后通知受影响已付玩家；原新 offer/点位审核可读，但受众在提交后临时查询 | 无通知待发源；R7 当前未接入 | 重复审核有业务闸，但 callback 丢失不再进；原 replacementOfferId 稳定，尚未冻结成员/通道与 payload | 既购履约安排；不是自动退款/重算权益 |
| C02 `I/VerificationRiskService:64` | 风险人工复核 webhook 告警；`verification_review` 原队列行已 insertIgnore | 人工复核列表可见；无本告警重投回执/消费者 | reviewId 稳定；boolean 只看 AlarmDelivery.SUCCESS；webhook UNKNOWN 不可推断未收到 | 安全/风控告知；不自动冻结/扣款 |
| C03 `I/MerchantOfferServiceImpl:456` | 商家退出后通知原主办；withdraw_reason/状态0为持久事实，无 notice ledger | 无该主办通知消费者 | caller 已锁章节→主题→供给且校验 merchant/topic；退出重放短路不再发送；原收件人是瞬时闭包 | 影响售卖暂停/履约响应；通知本身无金额写入 |
| C04 同文件 `459` | 退出后通知受影响已付玩家；受众在 afterCommit 后读 entitlement | 无逐成员/通道源 | 任一成功不证明全通道；整体 delivered boolean 丢失部分成功身份；不能重查 live 成员当原事件 | 已购履约风险；不自动动资金 |
| C05 `I/CoopComplaintServiceImpl:97` | 垫付进展通知；complaint + balance_detail + cooldown 为持久经济事实 | 无 complaint-advance 通知恢复 | accepted CAS 保护垫付，不能重新调用 advance 补通知；memberId/amount 文案未落独立通知源 | 垫付款真实，**资金逻辑只读不改** |
| C06 同文件 `140` | 关闭投诉通知举报人；工单 CLOSED 持久 | 无该通知恢复 | 重调 close 被工单闸拒绝，不是补消息入口；原 reporter 不应被 live owner 替换 | 工单结算响应，通知不改变退款/赔付 |
| C07 同文件 `143` | 关闭投诉通知过错方；实际扣划及欠款账本持久 | 无该通知恢复 | 实扣金额只能取冻结事实；不能为补发重新 moveBalance/debt；recipient 不同，不能与 C06 合并一条身份 | 真实扣划/欠款存在，**A/B/G 规则保留** |
| C08 `I/MerchantChapterApplicationServiceImpl:172` | 撤回申请通知主办；application del_flag=2 | 无通知恢复 | withdraw winner 才通知；申请可 restoreWithdrawn 重申，event+applicationId 会混不同轮次 | 申请/供给响应，无直接金钱 |
| C09 同文件 `283` | 审核申请/回应邀请结果；application audit/status 持久 | 无通知恢复 | 两种 event label；reapply 会复用 applicationId 并清旧审核，不能用单行ID当永久审核事件ID | 准入/容量相关；不改其状态/容量规则 |
| C10 同文件 `490` | 原撤回行恢复后的章节邀请 | 无通知恢复 | reinviteWithdrawn 复用原 ID；同邀请行可经历多轮，需领域事件版本后才能设计稳定通知身份 | 准入，无直接经济动作 |
| C11 同文件 `499` | 新建章节邀请 | 无通知恢复 | 首次 inviteId 稳定，但后续 C10 会复用；不把当前行当不可变历史 | 准入，无直接经济动作 |
| C12 同文件 `529` | 撤销邀请通知商家 | 无通知恢复 | 原主办权限/状态 CAS 在业务入口；再次邀请/再撤销仍同 applicationId，需轮次 | 供给响应，无直接金钱 |
| C13 `I/ClubMemberServiceImpl:451` | 入会审批结果；club_member 状态/最后 review_reason/reviewer/reviewed_at | 无通知恢复 | club 锁 +审批权限保护本次；被拒后重申复用 member行，最后审核列覆盖旧值；不可只用 club-member ID 去重所有轮次 | 会员权益状态；无现金，仍不能省略授权闸 |
| C14 `I/CmsRegistrationServiceImpl:2577` | 生成核销 QR；报名支付/缺 URL 持久 | **有 R6**，覆盖 D12 限定条件 | 不是“所有 CommittedBusinessNotice 都缺恢复”；生成/并发限制需原 owner 实测 | 已付履约可用性 |
| C15 同文件 `2586` | 报名成功站内卡片；已付报名存在但无通知事件行 | **R7 有任务但无本事件生产行** | callback 原 first paidRegistrationId 仅日志标签；原一次消息缺幂等回执；最小切口见方案 | 用户已付但通知可能丢失；不改变款项 |
| C16 同文件 `3228` | 风险抽检入队异常告警；核销事实可能已写，但 review 入队失败 | 无失败告警持久源/重投消费者；不能把正常 review 队列视为这个失败事件已存在 | registrationId/failureType 仅 Supplier；同报名可多次核销，不能只用报名 ID 生成所有失败事件键 | 风控可见性；保留不回滚已核销经济事实的现有规则 |

## 恢复任务注册与操作就绪：源码 / seed / 实际运行分开

| 编号 | 消费者与来源 | 仓库注册证据 | 默认/保留策略与准入 | 本次能证明 / 不能证明 |
| --- | --- | --- | --- | --- |
| R1 | `roamRewardDeliveryTask.run()` → reward reconcile，finally badge reconcile | `S/migration_20261013_roam_reward_delivery.sql:24`，每10分钟 | 新建行 `status=1` 暂停；等待 A 原券来源、C 原 XP/奖励合同。当前 ScheduleUtils 精确 business task 表未列此目标，通用包白名单也不含 business.task | 能证明 bean/调用链/seed 存在且不会被 finally 漏掉徽章；不能证明已迁移/启用/跑过。后台 add/edit 的准入需 G 核对，不能推断全部运行路径均被该表阻断 |
| R2 | `refundReconcileTask.reconcile()` → deposits + refund payout/query | `S/seed_refund_manual_menu_20260714.sql:20` 原启用；`S/migration_20261013_monitor_remaining_jobs.sql:25` 只补缺失且暂停 | 已有行保留运营状态；不能用后者推断现存一定暂停。ScheduleUtils 精确目标存在 | 存在注册方案及持久原号消费者；**不含终态1/4通知恢复**；实际环境状态未知 |
| R3 | `roamPresenceRevokeAuditTask.run()` → retryDue | `S/migration_20261012_roam_presence_revoke_job.sql:17`，每分钟 | POST_DEPLOY 前置 intent schema，缺失才插 `status=0`，已有暂停/自定义计划不改；精确准入目标存在 | 注册提案存在，不代表已获授权执行/上线；未查询运行状态 |
| R4 | `merchantCrmExportJob.cleanupExpired()` → expireAndClean | `A/web/job/LeaderScheduledJobsConfig:34` 内置 cron 默认每10分钟 | `chengyin.jobs.enabled=true`（缺省 true）且 leader；不是需另插 sys_job 的任务 | 能定位内置注册和孤儿扫描；当前实例 feature 开关/leader/目录及运行未知 |
| R5 | Quartz 定义恢复 | `SysJobServiceImpl.init` 注册 refresh callback；`MySqlSchedulerCoordinator:89/101` 每2秒维护及重读 | jobsEnabled、leader、canonical/definition/task fence；status1 为暂停。manual run 是另一类无持久触发源的操作 | 旧 migration 注释“只在重启加载”不能覆盖当前源码：当前有持续 fingerprint 重载；但本次没验证任何实际 tick |
| R6 | `verificationQrcodeRepairTask.repairMissing()` | `S/migration_verification_qrcode_repair_job.sql:19` 每10分钟、缺失插启用；ScheduleUtils 精确目标存在 | paid + registration ready + 缺 URL + 近7天 +10分钟宽限；部分活动有效期；原行计划不覆盖 | 仅覆盖 QR，不修 signup IM/WX；不证明并发生成或外部文件 identity 已验收 |
| R7 | `imNotificationDeliveryTask.deliverPending()` → B 原站内意图 | `S/migration_20261013_b_im_notification_intent.sql:23`，每分钟 | 新建行默认暂停；本基线没有生产侧 enqueue 调用者，当前 ScheduleUtils 精确 business 表也没列此目标；B/G 确认消费合同与准入后再启用 | 代码/迁移存在；**有消费者不等于 C15 事件已落意图**。不得未经审评替本次自动开任务 |
| R8 | `officialEventTask.run()` → 状态流转 + 结算 + 广播恢复 | `S/migration_official_event_sysjob.sql:9` 每5分钟、缺失插启用；bean 在 web.task 白名单包 | 共享任务还会推进活动/结算，不能为修通知单方面改 cron/开关。广播恢复查询仅冻结 e717 新实现具有 | 候选代码和旧注册方案可审阅；新日志迁移/实际启用/Java/MySQL 未验证 |
| R9（相关持久源对照） | `merchantCrmDeliveryRecoveryTask.run()` | `S/migration_merchant_operator_crm_phase1a_delivery_job_20260825.sql:9`；另有专门暂停修复脚本，不可猜实际状态 | campaign/recipient/guard 持久；owner/version/token、实时 consent、UNKNOWN 标记；ScheduleUtils 精确目标存在 | 源码有逐收件人恢复和 UNKNOWN 隔离，不能把 campaign lease 过期当外部没发过；本次不是其资金/发券原子性审计 |

## 退款到账通知：调用者/恢复入口完整追踪

生产搜索 `notifyRefund(` 的唯一业务调用者是 `RefundServiceImpl.notifyRefundAfterCommit:469`；其余是 INotifyService/IWxSubscribeMsgService 声明、NotifyServiceImpl fanout、WxSubscribeChannel 和 WX 实现。站内通道只在 REFUND 事件时直接 sendSystem，不 enqueue。

业务入口两条：

1. `verifyManualPayout:352` 成功 `markPayoutVerified` 从3→4，rows=1 调 notifyRefundAfterCommit；重放 rows=0 不通知。
2. `handleRefundNotify:377` 校验原单、支付号/CNY/金额，登记回调 inbox；SUCCESS→`updatePayoutTerminal:449`。只有 `updatePayoutResult=1` 的 winner 调通知；已为相同终态时只返回 true。入口来自 WechatPayServiceImpl 的验签回调/refresh，以及 RefundController 只读查证触发的 refresh。

资金对账 `RefundReconcileTask` 通过 mapper `selectStalePayoutScanUpperBound/selectStalePayoutProcessingAfter` 仅选 payout_status=0 或已审核5/6，**不选1/4**。保证金 reconcile 也是原保证金退款，不是 REFUND 消息补偿。没有原通知行，无法在重启后证明哪一通道成功、是否因网络 UNKNOWN 消耗授权。

可复核反例：SUCCESS CAS 已提交 → 进程未进入 callback 或 callback/scheduler/发送失败 → 后续相同 SUCCESS 返回 ACK，终态不再被资金对账扫描 → 用户没有站内消息/订阅恢复。`RefundNotificationMybatisIntegrationTest.failedOrThrowingChannelIsLoggedWithoutUndoingConfirmedRefund:273` 甚至明确断言“当前 best-effort 没有重试合同，不能把重复回调当重发”。本次只是阅读此测试，**没有执行**。

因此这是“通知可能永久遗失”的源码证据；不是“资金没退”的证据，也不是授权重发微信或回滚/重跑资金的理由。建议其通知恢复单独交资金 owner 与 B 约定原终态事件身份，D 本批不改。

## 验证、限制与依赖

本批实际执行：全生产源静态扫描、逐调用/Mapper/任务/迁移读取、证据 SHA256、文档覆盖/引用检查、git diff --check；输出见 VERIFY.txt。没有 Java/MySQL/Redis/微信/生产测试，没有执行 readonly-preflight.sql，也没有 DDL。

本清单只承诺对固定基线及冻结 D 增量的 afterCommit/afterCompletion 与 helper 调用完整；不声称全站所有直接 HTTP、支付、异步线程或所有通知接口已完成持久性审计。没有用静态搜索替代运行配置证据。后续 A/B/G 联合候选变化必须重核源码身份，不能套用这些行号。

依赖/不改事项：

- 冻结 D `e71777f…` 只作为广播增量观察源；本 WP08 文档提交从公共基线单独建立，不含也不重写 D 源码。
- A：付款事实、回调 inbox 与原事件身份；不得改变价格、退款、库存、分账、积分或合单规则。
- B：ISystemNotificationIntentService/ImServiceImpl 的事务/唯一键/回执合同及已批准迁移；不由 D 改公共实现或整站迁表。
- G：真实任务注册、paused/准入、调度 owner fence；不为跑通文档而放宽 whitelist、减少 gates 或启用资金任务。
- 新切口落地前由协调者分配所列文件切片；不创建第9个 Agent，不新增依赖。
