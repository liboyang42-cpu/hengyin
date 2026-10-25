# 原单—履约—原报名销售范围：最小新事实接缝（提案，未启用）

结论：现存事实足以复用“整单核销时实际选择的 station + 原报名”的意图，但**现有原合作单没有冻结 station 归属**。不能仅靠同商家的扫码汇总，把它分给多张 TOPIC 原单。建议用原 terms_snapshot 增加新确认合同的明确 station 锚点，并新增一张只追加的关联凭证表；不新增分账算法，不替换 REGISTRATION_SCAN，不开放 NODE/ACTIVITY/CLUB 现金路径。

公共 source 为 6fcd，已冻结修补 parent `33fa0bafb39d5ba647bbcd866e7a5973d38dd053`。本增量只含 G 方案/候选 SQL，33fa 生产与 fixture 不变。**没有执行 DDL、Java、数据库、CI 或业务测试。** 下文是请求 root 确认的共享合同，不能当已实现接口、已支持正向或已经验证的生产方案。

## 1. 实际原来源与可复用程度

| 真实入口/语句 | 已保存身份/规则 | 可以复用 | 不能推定 |
| --- | --- | --- | --- |
| `CoopInviteServiceImpl.acceptInviteWithLock(inviteId,nodeId)` → `createCoopOrder` → `freezeOrderContractSnapshot` | invite_id、topic_id、from/to；role/scope/eligiblePurchaseKinds/evidenceRule 冻结列；terms_snapshot 的 shareMode/rate/fixedFee/两轴；接受事务已先锁 topic 再锁 invite | 原授权/锁序/条款冻结入口；新确认时冻结明确来源 | nodeId 不是 stationId；同主题同商家的两张 TOPIC 单没有各自销售范围 |
| `freezeOrderContractSnapshot` | merchant→SUPPLY、REGISTRATION_SCAN；club→ORGANIZER、COOP_GROUP_VERIFY | 保持各自身份和规则原义 | 不把 SUPPLY 改为 ORGANIZER，也不把历史 SCAN 改 GROUP |
| `RegistrationRedemptionApplication.scanQrCodeStation` → `verifyAndSettleForStation` | 客户端仅选择 registrationMerchantId；原服务在事务中复核 station.member/topic/auditStatus/delFlag | 实际核销时的明确 station 意图；采用真实锁后行 | 不能从后来的 station 当前配置倒推旧事件属于哪张原单 |
| `verifyAndSettleForOwnStation` → `verifyAndSettleInternal` | 服务端唯一中标 station；报名 CAS；实际核销商家；原报名主体/金额 | 单一合法 station 的实际事件 | station 对应两个原单时，不能按先到/最小 ID 选择 |
| `writeScanLedger` / `CmsRegistrationScanMapper` | type=1 的 (registrationId,memberId) 汇总；类型、状态、时间；station 仅复制名称/位置/templateId | 保留原商家统计、风控、赏金和旧结算来源 | 该行是“该商家核过这张票”，不是每站/每原单的不可变事件；模板 ID/名称/坐标不能补成原单身份 |
| 同一事务 `writeVerificationAudit` | domain=REGISTRATION/domainId=报名；action=REGISTRATION_STATION_REDEEM；target=CMS_REGISTRATION/targetId=报名；ownerMemberId=商家；relationId=实际 station；actor、requestId、createTime；insert 获得真实事件 ID | 新关联凭证的首选原履约源。原 mapper 插入并返回 generated ID，无本业务的修改接口 | 一条普通审计外键不能单独证明同 topic/order/station；必须逐字段锁读/比对 |
| `verificationAuditRequestId` / `reverseWholeOrderVerification` | 原核销/撤销世代保留，重新核销有新 requestId；原扫码可变状态不代替世代身份 | 以真实 audit ID 防重；当前有效性从原报名与最后核销/撤销事件核实 | 不用 MIN(scanTime)/最后更新时间猜世代；撤销后不删除原凭证 |
| `PerkLedgerServiceImpl.redeemChapter` → `redemption` → `MerchantPayableServiceImpl.accrueOnRedemption` | registration/chapter/topic/merchant/offer/operator、ACTIVE/REVERSED、幂等键；原 claim=REDEMPTION + CHAPTER_SUPPLY + CHAPTER_OFFER/NONE | 现成章核销已经有独立不可变来源，维持其原应付 | 不是 coop_order；不可再对同履约加一笔 SUPPLY，也不能改 serviceUnit 绕原 claim |
| `coop_group_verify` + `coop_group_member_verify` | order/activity/merchant + 原报名和窗口 | 原 ORGANIZER/既有分组来源保留 | SUPPLY 原冻结来源不接受此规则，不能借字段现成就放行 |
| `CoopSettlementClaimServiceImpl.persist` / `SettlementClaim.economicKey` | REGISTRATION/报名ID + context/beneficiary/serviceUnit，source=原 COOP_ORDER | 原不可释放经济键、原 head/detail、调整、退款、payout | claim 是结算后归属，不是先验履约证明。不能把 orderId 加进 economicKey 允许同一报名被同受益人重复领款 |

### regId 有界核对结论

`CoopOrder.regId` 原意是**商家承接候选报名**，不是玩家票销售/核销 ID：实体字段注释“来源报名(候选池报名侧;邀约侧为空)”，接口 `createCoopOrder` 的 B3 注释为“候选池确认报名占槽”。这两处是原意说明；当前真实 caller 更明确：`ApiCoopController.confirmCandidate`（/candidates/confirm）将 registrationId 查为 `CmsRegistrationMerchant`，经主题 owner 复核后调用 dispatch 确认；随后的原合同注释与返回文案明确“选中不是成交、这里不生成 coop_order”，必须另发带条款邀约→商家接受→原单。

当前生产调用搜索中只有 accept→create 漏斗写 CoopOrder，没有调用者调用 `setRegId`；`createCoopOrder` 的 `selectCoopOrderByRegId` 幂等分支存在，但该字段没有被当前接受路径填充，FrozenReader 也没有把它验证为玩家销售身份。因此不能把该幂等分支当已有消费销售关联，不客户端补填、不历史回填。即便候选 station 的身份真实，也还缺“该候选明确属于哪张双方确认合同”的持久接缝，仍须按本提案在接受时确认/冻结。`game_id` 同样不是可替代的 station/node 来源。

## 2. 窄 V1 正向及明确尚缺的选择

仅保持现有经典 TOPIC/TOPIC_EDITION + SUPPLY/MERCHANT + GUIDED_TICKET/REGISTRATION_SCAN 现金路径。对**新确认原单**增加服务端冻结的 JSON：

```
"supplyBillingScope": {
  "version": "REGISTRATION_STATION_V1",
  "stationId": <实际 cms_registration_merchant.id>,
  "topicId": <原 topic>,
  "merchantMemberId": <原 toId>
}
```

值来自原接受权限下的明确 station 确认，事务内复核真实 station/topic/merchant/中标状态，并保存原条款字节 hash；不是信任客户端 JSON。保留原 role/scope/compensation/evidenceRule 等列。只在原接受/成单时写，不修改已冻结历史单，不以当前配置回填。

对一个 topic+merchant，V1 原单须各自确认不同 station；在原 topic 锁内当前读其全部有效原单并拒绝重复绑定同一 station。原 public create 漏斗也须在首个业务锁前进入共同 topic fence，不能仅依赖 accept 的外层锁。这里“一单一 station”是本提案的最窄支持范围，**尚未替用户选择任何 station，也没有新增生产限制**；若真实业务一单涵盖多个 station，可把冻结锚点扩成明确集合，仍须互斥，不能推导集合或改价。

当前 `acceptInviteWithLock` 只有 inviteId/nodeId，没有 confirmedStationId。因此：

- 有双方已确认且唯一的实际 station 来源时，root 可批准直接复用；不能只凭目前只有一条可变 station 配置就认定双方确认过。
- 若同商家两单都只声明整个主题，root 需要让原接受/条款 UI 明确“原单 A 对应哪些实际据点，原单 B 对应哪些”，而不是再问一套抽象分摊算法。谁在原接受动作确认仍沿现有 owner 权限，不能新增越权后台归属入口。
- 不建议让店员在核销时临时挑计酬订单；核销只选择实际 station，订单归属由已冻结映射唯一解析。

整单 V1 用原报名作为不可拆销售范围：已经成立的同一整单核销 CAS 只能产生一个当前有效 station 事件，凭证记录该报名原 payable_amount，不按 station 数分币，不修改票价/比例/退款额。只支持该语义能证明的原报名；不得把多人票 order_num 乘费或拆出虚构席位。固定费仍沿现有实际席位规则，缺逐席位的 station 来源时不在本 V1 启用该缺证分支。

同一票想让同商家多站分别计酬、但销售仍是同一不可拆报名，超出本 V1：需要另外明确独立销售子范围/既有付费单位来源，不能借 redemption 或两个 node 数字重复使用整票销售。此项不能靠改 claim key 解决。

## 3. 最小 additive 凭证与为什么旧表不足

候选 DDL 见 `PROPOSED_TABLE.sql`，**不是 canonical migration，不在 runner 执行计划中**。

`coop_order_station_fulfillment` 仅记录新确认合同对应的一次实际 station 审计事件：原 order/topic/merchant/registration/station/audit ID、原不可拆销售基数、精确 terms hash/源载荷 fingerprint、履约与记录时间。没有新付款 VERIFIED 标志，没有状态机/出款/人工判赔字段，不复制支付标识。

- `verification_audit_id` 全局唯一：同一个原事件只能绑定一个原单；重放必须所有列/指纹一致，否则冲突。
- 普通外键只证明引用存在，**不证明同 scope**。所有写入必须走专用 `INSERT ... SELECT`，在原 topic→registration 锁内逐项连接原冻结条款、audit 与报名/站点，校验相同主题、商家、station、原注册主体/金额/当前资格、源 action/target/时间、原合同的接受/窗口与版本；要求恰好一行。同一 proof 的已有行须完整比对，禁止 INSERT IGNORE 后默认成功。
- 不增加 `(registration,merchant)` 永久唯一：合法撤销后重核有新 audit。读方在原报名锁下对核销/撤销事件做当前读，只有与原当前有效核销世代匹配的凭证可进入计划；同一当前报名出现两个原单即拒绝。原不可释放 settlement_claim 继续防止已经结算的经济身份被另一单复用。
- 明确不新建 head/调整/分润表；原 claim/detail/冻结分项/退款/net/payout 全复用。
- terms_snapshot 可存原合同锚点，不能装每次不断增长的核销事实；scan 行汇总且可反向标记，不能覆盖它来保存两个原单；audit 有 station 事件却无原 order，不能事后按 live station 推断；这些是新增窄关联表的具体理由。

## 4. 拟议签名、caller 与所有者（root 确认后才编码）

- **合同冻结 owner（root 协调现 ICoopInviteService/CoopInviteServiceImpl/接受 DTO 与原 UI）**：给接受动作一个明确 `confirmedRegistrationMerchantId`（或已确认集合）；在 `freezeOrderContractSnapshot` 拼原 terms_snapshot 前完成服务端校验/冻结。现有 `prepare(CoopOrder,Date)`、`persist(plan,head)` 不变。仅新的明确确认合同生成 V1，不默认给历史单补字段。
- **核销 owner A**：`verifyAndSettleForStation` / `verifyAndSettleForOwnStation` 的首业务锁前先按原报名定位并锁 topic，然后保留原报名/station 当前复核和 CAS；`writeVerificationAudit` 私有返回值由 void 改为 `Long`，仍 require insert=1 且 generated ID 有效。旧忽略返回值的 caller 不必改变语义。成功 audit 后同一事务调用下列 G 窄入口；不得独立新事务丢失原核销 CAS。
- **G 既有 claim owner**拟加：`Optional<CoopOrderStationFulfillment> recordConfirmedStationFulfillment(Long verificationAuditEventId)`，MANDATORY；只收真实源审计 ID，不收客户端 order/amount/payee。按原锁序读取源并从新确认 terms 锚点唯一解析原单，append 严格凭证。Optional.empty 仅表示无适用的新确认合同，**不是生成了关联回执或完成了经济结算**；新 V1 明确匹配却缺源/冲突/歧义必须失败，不允许 fallback。root 可选择把“无适用 V1”筛选留在 caller，让写入口始终返回非空 receipt，安全条件相同。
- **G evidence reader/Mapper**：新增只读当前凭证 statement；V1 必须以原 order ID+terms hash+audit ID+原报名及窗口精确读取，缺记录不退回主题聚合 SCAN。原合同 requireSupportedCashPath 不泛放 NODE/ACTIVITY/CLUB，旧单继续33fa的严格路径。生成原 OriginLine 仍按原报名 ID/金额，保持旧 claim key；多单集合交叠仍由33fa写前拒绝。
- **root**：唯一 schema/DAG/fixture registry/集成 owner。A 的 reversal caller、退款、支付 facts 只复用原范围/锁序，不由 G 越权修改。F 只在真实新增 mapper/managed type 最终确定后登记精确 policy，本提案尚未新增任何 managed type。

## 5. 受控迁移、验收与回退要求

预检：目标新表/触发器/约束/索引名冲突；原 FK 类型/引擎；JSON V1 结构完整且 station/topic/merchant 对应；同 topic/merchant 的已明确 station 锚点无交叠；不得为历史未确认合同补 JSON/事实。只 expand 新表，无数据回填。已有确认/核销事务与 topic 关账必须成组接共同首锁，未接齐不启用新 reader。

必要真实 SQL/业务负控：不同 order/topic/merchant/station/audit/报名互借；同 proof 不同 payload；缺 station 选择；两新单绑定同一 station；同额不同注册主体；非法/重复/反向核销世代；原支付缺证；部分写失败全部回滚；双事务并发一次凭证；原成功事实与凭证不可删改；原正金额分项/保证金调整/退款净额/payout 仍守恒；两个真实互斥 station 原单按各自完整报名范围结算，交换读取顺序金额和集合不变。

DDL/Java/MySQL/CI 全部 NOT_RUN。无凭证时回退只读接线和源码；有凭证后停止新写，禁止删记录/释放 proof 身份。只有严格核验为空且 shape 完全匹配时才允许受控 drop 本任务新对象，不能删除任何原账本/回执/原 SQL。
