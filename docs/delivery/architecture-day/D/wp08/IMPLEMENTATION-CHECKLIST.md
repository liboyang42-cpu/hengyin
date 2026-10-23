# WP08 可执行清单：逐副作用复用现有恢复源

结论：先落地一个 signup 站内卡片生产者，复用现有 im_notification_intent 与分页恢复任务；健康的 afterCommit 只保留为唤醒。其余缺口按原业务事件和通道逐项治理，不能统一迁入一张表或重放资金入口补通知。本清单是交给现有 A–H owner 的可实施切片，**不是生产实现完成报告**；D 当前仅写本目录文档。

## 当前身份与验收事实

本增量以原 WP08 文档候选 202826bd63bbea0abd859ea172ce49bde7fe0ebe 为唯一父身份（tree 24245b4dff8caea1db20c3ce3aae7c4959e0a69a）。独立分支 codex/architecture-day-d-wp08-checklist，目录 /workspace/architecture-day-d-wp08-checklist；本地 HEAD 为公共基线 2ec68899099a1e250df0949a8383bf85b5d3ffe0，已重建父文档树后添加本清单。不改已冻结 e717、db5db8、928d779 或原 WP08 候选。

联合观察源码为公共仓库 commit 089790aaf3254f62ebbc79394b8aaf5fa66cb2b5。GitHub compare 对公共基线为5个提交、135个变更文件；原审计 evidence.json 的54份关键源码/SQL/测试与之没有交集，故 signup、RefundService、B原意图合同和G任务准入的源码结论仍有效。逐一读取15份变更生产Java，仅广播文件有本批已知 afterCommit；其余无同步回调/事务事件监听引用。精确 Git blob 和回调行号见 implementation-evidence.json，后续联合 SHA 变化须重核，不能套行号。

**受控证据更新**：协调者报告真实 MySQL run40016650848 在 combined089790aaf 上执行 OfficialBroadcastDeliveryMySqlIT 7/7、CLEAN。D 本轮没有重新下载该 IT XML，不把此结果扩为微信真实投递、生产迁移或 db5db8 新并发测试通过。原 README 中“D候选待Java/MySQL”的描述是当时快照；上述仅更新已明确范围。db5db8 频控新增IT和928d779 Controller fixture的Java重跑仍待协调者验证。

## 所有权与分阶段执行

| 顺序 | 可交付切片 | owner / 必须共同核对 | 进入下一步所需证据 |
| --- | --- | --- | --- |
| 0 | 固定新的联合SHA，核对下表每项源/调用者，不覆盖已修接线 | D维护审计；协调者集成 | compare和逐文件摘要；发现相同producer已接入即停止重复补丁 |
| 1 | 现有恢复任务“注册→准入→暂停→实际消费”回读 | G，B核对站内原意图消费者；各业务owner核对资金任务 | 只读 sys_job/表结构/新鲜任务日志，不能从 migration 有文件推断已运行；不由D开任务 |
| 2 | signup IM 最小生产者、专属测试 | A拥有 CmsRegistrationServiceImpl 支付事务；B拥有原意图实现；D只在协调者明确分配通知函数切片后可实现 | 下节S1–S8全部满足；不改付款/退款/库存/积分规则 |
| 3 | signup 切片隔离MySQL回归和故障边界 | 协调者现有批准VPS/GitHub路径，G负责测试接线 | 原付款拒绝/rollback零意图零消息；提交后丢唤醒可恢复；双worker/丢ACK无重复 |
| 4 | 逐项接后续本地IM通知；外部WX另外审评 | 表中领域owner+B；经济通知须A确认原事实身份 | 每种事件具有稳定身份、冻结recipient/payload和合法producer事务后才能写实现 |
| 5 | 经批准窗口实施已有迁移/注册及版本切换 | 集成人/G/领域owner | 受控演练、原意图回读、paused实际值和回退条件齐全；本清单不授权生产DDL/部署 |

## S：第一个切口的逐条实施清单（未实施）

- [ ] S1 A在当前联合SHA复核 completePaymentSuccess → registerPaymentAfterCommit 的真实调用事务；只有原 paymentTransitioned winner 安排当前那一张卡片。记录原 registrationIds 首位身份、memberId和topicId来源，不从重排siblings/live owner另选“主单”。列表、owner、原topic等必要事实不足须按原支付处理合同 fail-closed，不能生成假收件人/危险缺字段fallback。
- [ ] S2 B确认 ISystemNotificationIntentService 的实现和原资金事务使用同一数据源、REQUIRED；enqueue的摘要冲突、唯一event_key及DELIVERED receipt不因TTL清除。只复用已有接口，不改变B公共合同。
- [ ] S3 在原业务事务提交前，以 registration-signup:<原首位registrationId> 回读原intent；存在时复用原ID/recipient/payload；首次才按原 signupNoticeExtra 卡片结构冻结一次并enqueue。不能把enqueue移进afterCommit。该键仅针对原有一次signup卡片；如A已改变聚合身份，先重新确认，不能套用旧ID选择规则。
- [ ] S4 回调只唤醒 imService.deliverSystemNotification(原intentId)，可复用现有executor；删除同事件旧直接sendSystem分支，不能双轨发送。QR和WX分支不动、不宣称已恢复。不要为吞enqueue错误而把已确认外部付款称作回滚成功；回调失败/inbox重试由A既有合同承接并实测。
- [ ] S5 G核对 imNotificationDeliveryTask.deliverPending() 的现有seed、后台精确target准入和运行注册。当前观察源码seed默认暂停、ScheduleUtils精确表缺该business目标；由G修其拥有的接线，不由D扩大包白名单或自动启用。共用Quartz任务的并发/owner fence保持。
- [ ] S6 保留生产者的原付款锁序；不得让持有intent的投递消费者反向锁付款/member领域行。当前B消费者只锁intent→IM会话，先查原DELIVERED再执行本地写；新producer不得把微信/QR网络或文件动作放入该事务。
- [ ] S7 受控MySQL验证：业务拒绝及回滚；commit后唤醒前退出；executor拒绝；原消息/未读/回执一起回滚；commit响应丢失；双worker及支付回放；同key异内容拒绝；live标题/归属变化仍用原payload；原一张卡片不变为每sibling一张。真实进程退出与“新建service对象”分开记证据。
- [ ] S8 切换前核对新producer只有一个；不自动补造历史无intent的已支付行。历史是否已发无法证明，先人工核实。留存原intent/receipt/clientMessageId；回退先停相关producer/消费者，不允许旧直接sendSystem与新恢复任务同时负责同一事件。

详细调用/伪代码和测试矩阵继续使用 minimal-cut.md；本清单增加执行顺序，不改变未授权源码归属。S1–S8没有一项可仅凭本文件打勾。

## 全副作用处置单（覆盖原D01–D16、C01–C16及3个afterCompletion）

“复用”表示已有机制可延续，**不表示运行验收完成**。外部结果UNKNOWN不能通过新建请求号/租约到期/缺日志推断未执行。下表每项先搜索当前调用者和测试，再在owner的独占切片内实施。

| 单号 / 覆盖原ID | 最小动作、复用源和恢复入口 | 稳定身份 / owner与UNKNOWN要求 | owner、验收和决策缺口 |
| --- | --- | --- | --- |
| W01 D01 | 保留 roam_badge_delivery.prepare→业务提交→唤醒→reconcile；不另造通知表 | 原member/session/badge/source；member-first锁；徽章+终态同事务 | C/原奖励owner+B；G核实R1注册、paused和精确target。验证漏唤醒、已授予回读，不重新finish赚XP |
| W02 D02 | 保留 CoopDepositRefund.requestLocked→refresh/reconcile 原意图 | 原支付/退款号、金额/CNY、token；UNKNOWN原号查证，租约不证明未退 | 资金owner；G回读R2。只在明确NOT_FOUND且合同允许时同号恢复，不动退款金额/归档规则 |
| W03 D03 | 保留 TeamGroup DISPATCH_PENDING 和 afterCommit唤醒 | 原refund_application及registration身份；终态CAS/owner token | A/团队领域owner；测试提交后未唤醒可由R2消费，不能重取消/重退权益补消息 |
| W04 D04 | 保留 sharing关闭+revoke intent同事务与R3重试 | 原member/requestId/occurredAt；owner_token CAS防迟到覆盖 | 隐私/玩法owner；验证漏审计可恢复但分享绝不重开；任务启用需G回读 |
| W05 D05 | 保留已提交badgeDelivery回读；无需另建outbox | 原finish_result/intent，只读投影 | 玩法owner；响应回读失败不能重做finish或计XP |
| W06 D06 | 保留失效task为desired state及受控目录孤儿重扫 | DB owner/version失效先提交；DB回读失败不删；仅受控UUID文件 | 导出owner+G内置R4；演练提交后删除前退出、无引用文件清理；不把缺delete-intent当无恢复源 |
| W07 D07 | 保留资金政策DB真源/绕缓存读取，缓存失效仍是优化 | 政策key白名单，不能用Redis缓存当金额真源 | 资金owner；不新增outbox；失效失败时验证资金读仍取MASTER/锁定事实 |
| W08 D08 | 保留sys_job已提交定义、fingerprint重载和R5 leader循环 | 物理会话/generation、task锁、执行前status/target校验 | G；故障后从DB重建计划，不能把旧注释“只启动重载”当实际代码 |
| W09 D09 | 一次“立即执行”请求需与定义恢复分开；目前没有持久触发收据 | 无触发意图不能盲重放；下游原业务唯一身份仍必需 | G/集成人决策：仅声明best-effort还是需要一次触发请求身份与收据。若后者先提最小schema/预检/回退，D不造新调度框架 |
| W10 D10 | 退款到账的本地IM可复用B意图，但必须在终态CAS winner原事务生产；WX单列 | 原refund终态事件身份待A确认；不以terminal表可读或任一通道true当回执 | A+B；R2排除1/4，不能代替通知恢复。验证重复回调只ACK时PENDING仍能恢复，资金不重退；不可先猜人工/自动终态事件合并规则 |
| W11 D11 | 待缴保证金提醒先冻结payer/topic/原应缴事实；本地IM可用B，外部单列 | deposit_status=5只是应缴状态，尚未证明同一行是否可多轮提醒；先定义原事件版本 | 合作/资金owner+B；boolean忽略和无提醒消费者为现存缺口。不得为补提醒重新锁价/设款；无明确事件轮次前阻塞实现 |
| W12 D12+C14 | 复用paid-missing-qrcode的R6修复，保持原支付事务外生成 | 已有URL回读；缺URL生成新code，外部写确定身份与并发边界需验证 | A/核销owner；量化7天/10分钟/活动有效期覆盖，超窗另列。修复QR不等于修IM或WX |
| W13 D13+C15 | 按S1–S8接入B原站内意图和R7 | 原首位registration事件+冻结recipient/card；本地commit丢ACK先查receipt | A拥有producer、B拥有consumer、G拥有注册；最优先新实现切片；D本轮只交方案 |
| W14 D14 | signup WX单列持久原意图与UNKNOWN合同，不沿IM成功记成功 | 当前boolean无法区分明确拒收/结果未知；无确定外部请求/查询能力 | 公共通知/WX owner待协调分配；可设计失败前可证明未发和UNKNOWN人工核实，不编造查询接口/盲重发；不阻塞先做W13 |
| W15 D15 | 保留CommittedBusinessNotice为提交后唤醒加速器；逐调用修W17–W26 | eventKey/businessId仅日志，Supplier/newtx/10ms不是恢复账本 | B/各领域owner；不整体替换helper、不全禁afterCommit，不把外部动作放DB事务当外部原子性 |
| W16 D16 | 保留官方广播逐成员/通道源与R8，独立取db5db8频控delta | 原日志身份；member→delivery→IM；微信先UNKNOWN、事务外调、人工核实 | D+集成人；e717原7IT已由协调者报告通过；新第8并发IT、fixture4项待验证；共享officialEventTask还做结算，不擅改cron/开关 |
| W17 C01 | 供给补位通知冻结受影响已付成员列表；本地IM逐成员复用B | 原replacementOfferId可作来源组件，通道/成员身份与正文首次冻结；不要恢复时再查live受众 | 供给owner+B；实现前确认补位事件不可复用/版本；验证部分成功只补未成功，无退款/权益重算 |
| W18 C02 | 风险复核队列已有持久review行，webhook告警仍缺收据；复用review来源做专属恢复提案 | reviewId稳定；webhookUNKNOWN可能已收到，明确受理才成功 | 风控owner；先确认webhook幂等/查询能力与告警登记事务；人工列表可见不算告警送达 |
| W19 C03+C04 | 商家退出通知拆主办与原已付玩家，每recipient/channel各自身份 | 退出原事实/轮次+冻结人群；fanout部分true不能覆盖其他通道失败 | 供给owner+B；不能复用一条总boolean或重查live entitlement补原通知；退出是否可再启/再退出先查领域版本 |
| W20 C05 | 垫付进展本地IM复用B，意图随原accepted winner提交 | 冻结complaint/原垫付事实、收款成员与金额文案；不重跑advance | A/投诉owner+B；先确认同投诉是否多次advance，缺不可变advance ID时阻塞唯一键；财务事实不得回滚补通知 |
| W21 C06+C07 | 关闭投诉至少拆举报人和过错方两个原收件人；本地IM复用B | 原关闭事件+recipient/角色；实扣/欠款文案来自原经济账本快照 | A/投诉owner+B；不重跑close/moveBalance/debt；核对是否允许reopen再close，需版本则先领域最小方案 |
| W22 C08 | 申请撤回通知需事件轮次，不能仅applicationId去重 | restoreWithdrawn可复用ID；原recipient/原因冻结 | 章节申请owner+B；先选择已有不可变审计/事件ID；无则提最小轮次方案及迁移预检，未授权不执行DDL |
| W23 C09 | 审核/回应邀请结果按原结果事件生产IM | reapply复用行并清审核，当前状态不是历史；审核与回应kind分开 | 章节申请owner+B；先确认审核轮次/审计事件主键；不同轮次都可通知，同轮重放不得重复 |
| W24 C10+C11+C12 | 新邀请、重邀、撤销按原轮次各自产生事件 | reinviteWithdrawn复用原ID，需原round+kind，不能随机键或覆盖payload | 章节申请owner+B；若缺round，先决策新增领域版本而非先建全站表；验证重邀/撤销循环及迟到worker |
| W25 C13 | 入会审批结果按审核轮次生产本地IM | 被拒重申复用club_member且覆盖reviewed_at等列；时间戳不能未经证明当永久唯一ID | 俱乐部owner+B；保留club锁/授权，先找原审计事件；缺来源时阻塞，不从最后状态恢复全部历史通知 |
| W26 C16 | 核销风险入队失败告警先定义独立失败事件来源 | 正常review队列不存在正是该事件；同报名多次核销需原scan/attempt，而非registrationId一把键 | 风控/核销owner；不撤销已核销经济事实；若用现有inbox/审计需证明原事务可落源，否则先最小专属方案 |
| W27 afterCompletion×3 | AppSessionRegistry门闩释放、PlayCheckin原码CAS、MySqlSessionLock自身连接释放全部保留 | mutationId/code-reserved值/session lease fence；租约TTL不证明付款未发生 | 原认证/核销/G owner；分别测提交/回滚/接管，不能全迁到通知表或延长签名码有效期 |

## 注册和消费者核对单

以下对每个目标分别回读定义、paused实际值、准入、leader执行证据、业务原意图变化；禁止只凭一条成功job日志宣称交付。

| 原注册ID | 现有机制 / 下一步具体核对 |
| --- | --- |
| R1 | roamRewardDeliveryTask.run() 的finally必须继续执行badge reconcile；新seed默认暂停、精确target待G。奖励合同未就绪不因徽章需求全开经济任务 |
| R2 | refundReconcileTask.reconcile() 已有seed和缺失补seed的默认值不同，回读已有行；扫描0/5/6，不拿它扫描终态补W10消息 |
| R3 | roamPresenceRevokeAuditTask.run()：核对intent schema、owner token、缺失插启用与已有暂停保留；重试只补审计 |
| R4 | MerchantCrmExportJob.cleanupExpired为内置计划，核对leader注册与受控存储清理证据；不要因为sys_job无行就添加重复任务 |
| R5 | MySqlSchedulerCoordinator约2秒刷新定义；验证DB fingerprint变更重载和执行前fence，手工trigger缺源另属W09 |
| R6 | verificationQrcodeRepairTask.repairMissing()：确认具体筛选窗口、实际覆盖和缺URL数量；不得把数量降零等同外部身份与并发安全已验证 |
| R7 | imNotificationDeliveryTask.deliverPending()：确认表/唯一索引/状态、maxPendingId高水位与100条分页；毒性失败行不饿死后页；默认paused和精确target缺口由B/G处理 |
| R8 | officialEventTask.run()：共用活动/结算/广播任务，不由D改调度周期；广播原PENDING恢复及UNKNOWN停留均是合法结果 |
| R9 | merchantCrmDeliveryRecoveryTask.run()：campaign/recipient/guard与consent、owner/version/token现有合同保留；不改发券/权益规则，未知外部结果不因lease超时重投 |

预检先使用原 readonly-preflight.sql（10条SELECT，仅建议、未执行），验证表/列存在后才查询业务表；返回脱敏聚合/任务定义。须人工对照完整预期目标，HAVING重复查询不会显示缺失目标。没有数据库访问权限/受控回读时保持“未验证”，不复制VPS/生产凭据补齐。

## 接受标准、历史与退出条件

每个新producer必须给出：原业务成功与原意图同事务、拒绝零业务/经济/权益/外部动作、固定recipient/payload、每member/channel身份、合法恢复入口、部分成功只处理未成功通道。原本地消息/终态receipt必须同事务；外部动作先持久原意图再事务外调用，UNKNOWN先原号查证，无查询能力则保留原身份交人工。不能以response body TTL释放业务唯一身份，不能删除失败行通过唯一约束。

没有原通知回执的历史终态行不能默认“未发送”。禁止批量清库、补造live payload、重放付款/退款/扣划/审核入口来制造新意图。任何所需领域轮次/事件字段都先提交具体最小schema、只读预检、迁移/回退方案，由集成人授权实施；当前清单不执行DDL、不建立全站outbox。

每个切片交付独立候选、父SHA、逐文件摘要与新鲜受控测试输出；编码完成/实际测试/未验证三列分开。D后续不得代A/B/G接管资金、公共Notify/IM合同或调度注册。所有经济语义、轮次身份和历史处置未明确的行按表中决策阻塞保留，不能为了24小时目标放宽门槛。
