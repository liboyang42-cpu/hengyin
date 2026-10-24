# G：下一片原现金集合读回施工单

结论：建议先独立实现**完整原现金集合的 current 读回与原快照差异分类**，仅新增窄 DTO / Mapper / Reader / 真 MySQL 测试，不改 `CmsTopicServiceImpl`，不接资金消费者。这一片不需要退款分配或多人固定费决定，不需要 B 的完整 payment fact；能够独立编码/验收，**不能独立启用出款或被当作已统一全仓 fence**。本次仅完成只读施工分析，未写上述生产代码。

## 1. 源身份与本次实际动作

- 公共仓库 `https://github.com/liboyang42-cpu/hengyin`；参考基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 实际只读核对 root 公共源 `4b85984c7a86af59240ab5698337041b590813bd`。本报告方法/行号仅对应该源；整个文件的 hash 用于身份核对，不代表全文件审查结论。
- 文档工作树 `/workspace/hengyin-g-next-cash-slice`，分支 `codex/architecture-day-g-next-cash-slice-20261024`，实际父提交 `61834b1ba96fcfb025d8442955b69f1d6b35d5f3`，创建时干净。只新增本目录，不修改旧候选、生产/测试/schema、D 迁出目标或共享入口。
- 已实际执行 Git fetch/show/grep、源 hash、文档范围和 whitespace/密钥文件静态检查。未跑 Java/MySQL、未派 CI、未连接生产或执行 DDL。
- root 通报 4b859 的 migration 分句/依赖拓扑及现有 harness47 selftests PASS，cash-expand30 真 MySQL 正运行；这些是 root 通报，不是本次独立执行，也不预告其结果。其覆盖不包括本施工单的未来 reader、金额算法、late SUCCESS 或共同锁序。

## 2. 当前代码能确定的差距

| 链路 / 真实位置 | 4b859 实际行为 | 技术施工缺口；不改金额规则 |
| --- | --- | --- |
| `CmsTopicServiceImpl.executeTopicSettlement:1097` → `settleTopic:1227` | 外部列表中的 topic/rule 进入每主题事务；CAS 取得 topic 写锁，但不据此重新读回 topic 的全部冻结字段 | 迁出后入口须先 current topic，按该对象复核 owner/冻结字段，不能只给旧对象补一把锁 |
| `lockTopicCashBasis:1516` → `CoopSettlementEvidenceMapper` 两个 cash SELECT | 已有全状态 TOPIC 及真实父活动 current 查询，包含已删壳，不是全部漏读；但只有 id/owner/member/三个状态/payable | 缺原号、父单、支付分额/渠道/时间等原贡献身份；TOPIC 与 ACTIVITY 分两段锁，不能宣称全 registration 全局升序；不把活动资金并入经典 TOPIC 资格 |
| 新 `CoopSettlementCashEvidenceMapper.recordClose/Contribution/Event` | 仅存储原语；搜索生产 Java 的 record* 只有接口定义，没有业务消费者 | cashset 尚未从业务事务接到原 close/contribution；此处不能声称落库已实现 |
| `settleTopic:1256` 零销售 | 验证零现金合作合同后退保证金并返回；无新 close 调用 | ZERO anchor 已有结构，不等于生产已记录；原待付后 SUCCESS 属独立 P1 |
| `WxPaymentCallbackMutationService.apply:23–32` → inbox Mapper `lockBusinessTarget` | inbox → registration 写锁 → 业务 mutation；无 topic 前置 | PAYMENT 在 target 锁之前取真实 topic，父单/作用域锁后重验；G 不改 B 网关或共享 inbox |
| `RefundServiceImpl.reviewRefund:582` → `loadRegistration` / `lockRefundRegistration:685` | 先锁报名/父单，再退款历史/审核 CAS/旧 DIRECT 调整；没有新 cash_event 调用 | topic 必须前移到 loadRegistration 前及外部包装首锁前；只加在 CAS 后没有意义 |
| `TeamGroupServiceImpl` 付费取消/活动取消；`ClubEventOpsServiceImpl.cancelOccurrence:431` / `cancelActivityWithClubWrapUp:571` | 可先锁 activity/series/club/occurrence/报名/权益，再到退款；主题取消已有 topic 起点 | root/A/包装 owner 统一外层顺序；不能由 G 只给退款内层补锁，造成反序 |
| `payoutOneDueCoopSettlement:1788` | 先锁 head；普通客诉计数和 executed SUM；余额/流水/paid/债务同事务 | 迁出后按定位 head.topic → topic → current head；调整/原现金完整性须同 fence 下复核；当前普通 SUM 不是当前锁读证据 |
| `CoopSettlementClaimServiceImpl.appendExecutedAdjustment:54` | 先锁 claim detail；写 EXECUTED 负/正调整，未执行余额/债务动作；目前无生产消费者 | 不能直接作为挂账后退款执行器；需原事件、头和实际资金回执，且先 topic/原报名/退款/head，再 detail |
| `SettlementDebtLedger.record:48` | insertIgnore；唯一身份为 source_type/source_id/member_id；相同来源异金额不比较读回 | 同会员跨 head 复用原退款键会吃掉第二项；不能把现有 helper 的“已插入/重复”当作整事件全头已执行 |

缓存是附加技术缺口，不冒充锁协议：4b859 的 `CmsTopicMapper.selectCmsTopicByIdForUpdate`、`CoopSettlementMapper.selectDuePayoutForUpdate`、`CoopSettlementAdjustmentMapper.selectByClaimEventForUpdate`、`UmsMemberSettlementDebtMapper.selectOpenByMemberForUpdate` 未显式禁用/冲刷 MyBatis 查询缓存；G 已修的 Evidence 三个 cash/refund current 查询则有精确 flags。后续归属明确后分别加 flags 与重复 current 读行为证据，不能只改 flags 宣称 RR/fence 已修。

## 3. 建议先做的最小独立片（仅新文件，尚未实施）

拟独占五个新文件，现有类/Mapper 不编辑：

1. `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/TopicCashSetSnapshot.java`
2. `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/TopicCashSetMapper.java`
3. `chengyinhub-system/src/main/resources/mapper/business/TopicCashSetMapper.xml`
4. `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/TopicCashSetReader.java`
5. `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp04TopicCashSetMySqlIT.java`

方法合同候选：`readCurrent(Long topicId)` 只供已有可写资金事务中的内部调用，MANDATORY、无 REQUIRES_NEW；返回原事实，不返回 `canPay`/`verified`/经济资格。Reader 的首个共享领域锁是实际 topic，之后按共同协议读真实活动关系及报名。**调用方若在进入前已持 reg/activity/head 锁，内部新取 topic 仍会反序**；这一合同不能靠注解自动证明。当前不装到任何生产消费者，因此可以独立真实 MySQL 测试，不提前改变旧链锁图。

最小输出/查询合同：

- current topic：id / productType / memberId / isSettlement / 四冻结费率。NULL 保留；不用0或全局规则造冻结字段。
- current activity 关系：实际 `cms_activity.id,topic_id,del_flag`；包括软删，不拿 ID 数值相等、模板/sourceTopicId 或 active 列表替代父关系。无父 topic 的独立活动不伪造关联。
- 当前来源行：原 registrationId/no、parentOrderNo、ownerType/id、memberId、ticketId、orderNum、payment/registration/verification 状态、payable/wechat/point 分额及既有冻结字段、transactionId/paymentTime。只选资金必要字段，不把整张含联系方式的 bean 序列化。
- 来源范围：TOPIC owner=1/id=实际 topic，或 ACTIVITY owner=2/id 属于该 topic 的真实活动；不按 paid/verified/reg/del 状态过滤原来源。原 close 中引用但当前已改归属的来源也须按原 ID 查出并报告冲突，不静默消失或当成可计入其他主题。
- 原 close 和原贡献：精确读回已有三表，不创建 anchor、不回填、不改原 S/amount/快照。原 snapshot 缺字段就报告缺失，不用 current 字段覆盖/补成历史证据。
- 最小差异分类只描述观察：`NO_ORIGINAL_CLOSE`、`CURRENT_SOURCE_NOT_IN_ORIGIN`、`ORIGIN_SOURCE_MISSING`、`SOURCE_IDENTITY_CHANGED`、`SOURCE_STATE_OR_AMOUNT_CHANGED`、`ORIGIN_FIELDS_UNAVAILABLE`。本地 paid=2 只是本地事实，不命名为已验证 late SUCCESS，也不由差异分类直接拒绝合法退款/生成调整。
- 当前 cashset 不计算退款净额/分项比例/固定费，不把活动现金解释为已支持 TOPIC 结算，不把 parent 的首单/实付主体猜成最小 ID；不确定付款主体仅保留原行与缺证据事实。

查询必须 current + 精确 useCache=false/flushCache=true，不沿较早 RR view 做普通 SUM/COUNT 来证明集合。最终共同 fence 下必须稳定关系/来源集合并重验范围。**ORDER BY id 本身不是物理取锁顺序证明**；跨 TOPIC/ACTIVITY、父单及原归属冲突的锁实现需实际 MySQL EXPLAIN/受控交错核实，禁止为求固定顺序全表扫描锁住无关主题，或持旧 topic 后追加不同 topic 的锁。无法证明时，该 reader 仍只可做候选，不能接资金准入。

## 4. 这片真实 MySQL 的最小验收设计（0 实现 / 0 执行）

| 夹具 | 必须实际观察 |
| --- | --- |
| topic11，activity22 的真实父为11；另有 topic22 同数字 | 真父 ACTIVITY 行进入11，数字等同的其他 TOPIC 行不进入；软删活动也保留 |
| 同主题同时有待付0/PAYING1/已付2/本地关闭/退款4、正常/异常核销、NULL 状态 | 全来源行及NULL原样读回，不能只取已核销或已付；不增加任何资格 |
| 先建 RR 旧视图，再由另一连接提交源状态/金额；同 SqlSession 重复读 | current 查询看到新事实；不是仅依靠清缓存或普通 SUM 通过 |
| 事务外旧 topic 列表对象；另一事务已改 owner/费率后再拿锁 | 返回 current topic；未知费率NULL不补0，不继续用旧对象冻结 |
| 原 ZERO close S0，原待付20/贡献0；本地源后来变paid2 | 原S/贡献0不变，状态差异可发现；event/余额/权益均零新增，不声称真实 late SUCCESS 接线已完成 |
| 空 ZERO close 后出现本地 paid 来源 | 发现来源不在原集合，不造贡献/假头/verified事件，不改原close |
| 父单原payer302、child301且301较小；零额/缺支付主体证据 | 保留原号及分额，未因 min(id) 交换角色；缺证据保持未知 |
| 原贡献所指来源当前改归属/父关系损坏 | 冲突可见，不漏掉原ID，不重新归并/补取另一 topic 锁执行经济动作 |
| 连续两次读回或读中注入异常 | 资金/权益/审计原账/新三表无DML；异常不产生event或终态 |

新 IT 继续显式要求 HOST/PORT/USER/PASSWORD/DB_SUFFIX，逐测试自身 UUID schema、精确 AfterEach cleanup；不改 cash-expand30 harness 或 root 的 CI 登记，不派 CI。

## 5. 后续共同施工边界（不在本片内）

- 全链目标顺序仍是私有 inbox（如有）→ topic（多主题升序）→ 已统一的包装资源/activity → registration（完整父单）→ refund history → heads → claim/detail/adjustment → member（升序）→ 流水/债务。新增 close/contribution/event 的锁位置须由 root/A/G 一起列入实际锁图，不能自行插到反向位置。
- 生产入口必须成组包括：inbox PAYMENT target 锁前；`reviewRefund/loadRegistration` 与退款建单前；TeamGroup/Club 包装的首锁前；创建/waitlist 与 `beginPaymentAttempt` 的首个共享锁/经济效果前；零元完成首CAS前；迁出的结算与 payout。不只改 complete 内层、也不把外部调用塞进锁事务。
- payout 后续由普通定位取得 topicId，先 topic 再 current head，重验归属/到期/状态；current 原现金、退款和调整一致后才能计算。不能用 head→topic、原普通 executed SUM 或 topic 已结算位当实付证明。
- 退款归一必须真实覆盖 PLATFORM、发起人各头及 merchant，原头不可覆盖。现 adjustment.claim_id NOT NULL 仍在；PLATFORM/发起人无 merchant claim，不能伪造扫码 claim 强行复用 appendExecutedAdjustment。当前三表是原证据结构，不是已完成的全头调整/执行回执。
- 跨 head 债务：只有上游已批准的完整逐头 signed delta 及原执行身份明确后，才能决定按真实 adjustment ID 逐项记债，或按不可变全事件/member 的已批准计划一次记债。不能提前拼 sourceType/headId 就声称不漏第二项，也不能用 insertIgnore 吃掉不同载荷。此片不加该消费者/表/金额。
- B 完整持久已验证支付事实未交：late SUCCESS = **UNIMPLEMENTED/P1**，不从 SDK 查单推导已落库事实、不造 self-VERIFIED event、不 STALE ACK 丢成功事实。零close存在不等于来源恢复/支付权益/资金归一已完成。
- 用户待决仍是退款“按原订单冻结分项冲回”或“全期净销售重算”、多人票固定费。不默认算法，不以拒绝合法退款或永久冻款作为闭环。现金 dispatch 保持关闭，生产无 DDL。

## 6. 交给迁出 owner 的具体接缝

G 可先持有本节五个新文件；D 继续迁出大服务时无需复制/改这些文件。后续迁出的结算入口以 Reader 返回的 current topic/来源集合为唯一输入，并在同一资金事务、守恒验证后接 `recordClose/recordContribution`，保证原现金效果和业务回执一同提交；ZERO 分支也记录合法原 anchor。后续 payout 接同一 current 原事实核对，但须等全链锁序与 latefact/退款归一验收，不在迁出时把新 Reader 直接当启用开关。本次只提交施工文档，不已经创建这些生产类或宣布上述接线完成。
