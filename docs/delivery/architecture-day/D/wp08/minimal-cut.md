# 建议小切口：报名成功站内卡片的原意图接入

结论：优先修 C15/D13，不动退款与钱。当前已付事实成功落库后，`registerPaymentAfterCommit` 才临时向 scheduler 投 `sendSystem`；进程中断或任务拒绝会丢消息。扫码 QR 有自己的 paid-missing 恢复，signup IM 没有。既有 `ISystemNotificationIntentService` 和 `im_notification_intent` 足以承载这一种**本地站内通知**，无需新建共享框架或新的领域表。

这是一份可实施方案，**尚未修改生产代码**。当前源码属于 A/B/G 的共享合同，D 不把方案视为已获独占写权限。

## 文件所有权与最少修改范围

| 文件/切片 | 当前/拟负责方 | 方案修改/依赖 | 明确不改 |
| --- | --- | --- | --- |
| `CmsRegistrationServiceImpl.registerPaymentAfterCommit` 的 signup IM 分支及所需注入 | 付款 Service 归 A；由协调者将该通知切片交给 A 或明确委托 D | 在原业务事务里 enqueue 固定站内卡片；afterCommit 只唤醒同一 intentId 的投递，移除原直接 sendSystem 路径 | 不改 completePaymentSuccess 的支付 CAS、金额校验、结算、奖励、兄弟单/库存语义；只读现有首位 registration 身份 |
| `ISystemNotificationIntentService`、ImServiceImpl、ImNotificationIntentMapper | B | 直接使用现有 findIntentId/enqueue 与 deliverSystemNotification 合同；B 核实同数据源 REQUIRED/回执原子性 | 不修改接口签名、通用通道 fanout、表合并/TTL 或通用实现 |
| `ImNotificationDeliveryTask`、现有 intent migration、ScheduleUtils 任务准入 | B/G，迁移由集成人单一落地 | 核对现有 task/PENDING 扫描、迁移、paused/准入及实际注册；缺口交相应 owner，不私自加新执行框架 | 不由 D 自动启用任务、修改 schema、改大 whitelist 或 runner |
| signup 通知专属测试（新增，不抢改原资金测试） | D 可提出测试源码；A/B/G 提供受控运行 | 同事务零消息/回滚、首事件重放、重启补交付、两 worker、消息/receipt 一起回滚、不同 payload 冲突 | 不以 Mock/H2 冒充 MySQL，不降低余额/结算断言，不改变历史 baseline |
| 本 wp08 交付目录 | D 已独占 | 盘点、方案、只读预检、静态证据 | 不覆盖冻结 D 候选/别人的报告 |

## 原调用和可复用合同

`completePaymentSuccess:2321` 构造 `paidRegistrationIds` 时先写入本次已验证原报名 `registration.getId()`，再追加 siblings；只有 paymentTransitioned 时调用 `registerPaymentAfterCommit:2373`。原 IM 分支明确消费 `registrationIds.get(0)`，所以无需根据 live siblings 重新选一笔“主单”，也不新增合单规则。

`registerPaymentAfterCommit:2574` 的 QR 和 WX 分支保持原样。原独立 IM 分支用 `ImMessage.MSG_CARD`、标题“报名成功”和 `RegistrationCheckoutServiceImpl.signupNoticeExtra(topicId)`，不是 `notifySignupSuccess` 的 fanout；当前 InAppNotifyChannel 没有 SIGNUP_SUCCESS 分支。因此替换这一条直接 sendSystem，不应额外产生第二条站内卡片。

B 的实现已有：

- `enqueue`/`enqueueSystemNotification` 是 REQUIRED 事务，event_key ascii 唯一；同 key 的 recipient/type/content/extra_json 摘要冲突拒绝，不重写原 payload。
- `findIntentId(eventKey)` 先回读原意图，可用于已有事件路径；已有时不能按 live topic/owner 重建不同正文。
- `deliverSystemNotification(intentId)` 锁原行，DELIVERED 返回原 messageId；PENDING 调 systemMessage，固定 clientMessageId=`notification_<intentId>`；消息、unread 和 DELIVERED receipt 在同一数据库事务提交。
- `ImNotificationDeliveryTask.deliverPending()` 在有限高水位内分页扫描 PENDING，逐项事务，某条失败不挡后页；迁移默认暂停，启用和准入由 B/G 验证。

## 最小实现顺序（伪代码，非已实施补丁）

1. 在现有原支付业务事务中调用的 registerPaymentAfterCommit 内，使用原 `registrationIds[0]`（已由付款路径验证，不加危险缺字段 fallback）。若列表/原 recipient 不完整，fail-closed，交 A 明确原回调的拒绝/重试处理；不伪造用户或消息身份。
2. 确定事件键 `registration-signup:<originalRegistrationId>`，该 ID 严格沿用当前首位原支付报名，不用随机 UUID、不随响应 TTL、查询顺序或重新投递改变；不对所有 sibling 各发一条新卡片。
3. 在原事务先 `findIntentId(key)`。存在时使用原 intentId，不根据当前 topic 名称/归属改写；首次才读取当前原报名对应的 topicId，按原 card 规则生成一次 payload，并 `enqueue(memberId, MSG_CARD, "报名成功", frozenExtra, key)`。B 自有 hash 再兜同 key 异内容冲突。
4. 原业务效果 + 这条通知意图共同提交。enqueue 不得放在 afterCommit 内；否则 crash-before-callback 仍无恢复源。A 需核对首次 callback 和 repairHistoricalPaidHalfState 的调用事务均覆盖此写入，不由 D 更改资金事务规则来掩盖问题。
5. 提交后只通过现有唤醒能力尝试 `imService.deliverSystemNotification(originalIntentId)`；可继续用 CommittedBusinessNotice 的现有异步 executor/new transaction 作为加速器，成功判据为有效 messageId。**删除原同事件直接 sendSystem 分支**，避免双发。唤醒丢失/失败时原 PENDING 仍由已注册的 B 任务恢复。
6. 站内成功以原 intent DELIVERED/messageId 为事实；不因 afterCommit 执行完、enqueue 返回 ID 或 executor 接单就叫“已送达”。没有外部网络动作，不需要把本地 commit 不确定误标为可重投的新事件；先锁查原 receipt。

上述 key 只定义当前已有一次支付确认触发的那条 signup 卡片；若 A 的新联合候选已经改变支付聚合身份或已接入相同 intent，停止重复补丁，沿其原事件合同重新核对。必须先搜索联合候选的 enqueue 生产者，不按旧行号覆盖。

## UNKNOWN、owner fence 与经济边界

- 这是本地 IM 交付：用原意图、原收件人和本地事务查证，不调用微信，不会因微信 UNKNOWN 重发或烧授权。原 signup WX 分支仍有 D14 缺口，**本切口不宣称已修它**，不新增/移除该外部动作。
- recipient 必须来自原支付事实的 memberId；title/card extra 首次冻结。恢复不重新圈人，不把 live owner 或别的付款人换进原通知；消息会话以原 recipient 建立。原卡片只存 topic 引用，点击后的详情仍走既有鉴权。
- 写业务/领取状态的合法性仍由 A 原路径负责；拒绝请求不得留通知 intent 或实际消息。enqueue 失败时原本地业务事务不应“成功而没源”，但已经由微信确认的外部付款不能被称为已回滚；回调 FAIL、inbox 重放/支付对账属于 A 的既有处理合同，必须在其受控测试中证明。
- 不变更实付额、退款、分账、积分/券、库存和核销规则；不借通知恢复重跑任何这些经济效果。不从终态退款扫描去反向生产 signup。
- 暂不为历史已支付且从未有 intent 的旧记录自动补建：没有原消息 receipt 时无法证明是否已发，禁止批量导入 live payload 并重投。历史核实为独立决策。

## 最少验收矩阵（待 A/B 受控真实 MySQL 执行）

| 场景 | 必须可证明的结果 |
| --- | --- |
| 原付款事务回滚/原业务拒绝 | 无 intent、无消息、无新增 unread，经济/业务状态沿原失败合同 |
| 提交后、afterCommit 之前退出 | 同一 key 的 PENDING 存在；恢复任务交付一次，原业务不重做 |
| scheduler 拒绝/服务重新实例化 | PENDING 可由任务查到；不新建另一事件号；真实进程退出另测 |
| 消息写入后 receipt 写失败 | 消息/unread/receipt 同事务回滚；再次交付一条，不冒充 MySQL 原子性已由单测证明 |
| DB commit 响应丢失 | 先锁查原 DELIVERED/messageId；不能根据异常创建新 intent/第二条消息 |
| 两 worker / callback 重放 | 原唯一 key、intent 锁及 clientMessageId 使同事件只一条消息；资金断言保持 |
| 同 key 异 recipient/payload | 冲突且无额外业务/通知动作，不覆盖旧正文 |
| topic/owner/文案后来变化 | 恢复使用原 payload/recipient；不二次查询 live 内容改写原事件 |
| 合单/历史半成品 repair | 沿 A 原始支付 registration 身份；原一次卡片不变成每 sibling 一条，已有 intent 不再造 |
| 任务注册与实际运行 | 确认具体 sys_job 行、paused、cron、准入、fresh job log 与 PENDING→DELIVERED；仅看 Java bean/SQL 文件不算验收 |

## 迁移、依赖与回退

此切口不新增表/列/依赖；但依赖 B 的 `im_notification_intent` 结构和现有任务迁移已获批准并落地，不能从“源码有文件”推断数据库已存在。只读预检见本目录 SQL，未执行。

G 需核对当前公共基线缺少 `imNotificationDeliveryTask.deliverPending()` 精确 business target 的后台 add/edit 准入项；D 不扩大 whitelist。任务迁移默认暂停，不自行启用；是否在已有 sys_job 状态切换路径允许启用，应由 G 查证，不把 UI add/edit 限制误称为 Quartz 所有执行路径均阻断。

回退生产切片前保留原 intent/receipt/clientMessageId 唯一身份，不清空任务、回执或经济记录。不能让旧直接 sendSystem 与新原意图消费者同时负责同一 signup 事件；先由 A/B 明确 producer 版本边界，再按批准窗口切换。历史 UNKNOWN 不自动重建。

下一步不是全站迁移：协调者先确认 A 尚无重叠生产补丁并分配该函数切片，B 核实接口/迁移，G 核实注册与准入，再实现这一个通知并跑上述 MySQL 场景。退款、保证金、其他领域通知独立保留本表风险和所有权，不顺手改。
