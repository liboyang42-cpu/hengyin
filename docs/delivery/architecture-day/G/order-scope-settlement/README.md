# 逐原单准备与范围交叠写前拒绝（部分交付，NOT_RUN）

本片修掉 NONE/先到受款方静默跳过后单的关账机制。**同商家多单的生产正向独立范围仍有现存源合同缺项，不能宣称全部已支持或验收通过。**

parent 公共 `6fcd5ac009cec1dd82c438528d24da30c1f302f4`；分支 `codex/architecture-day-g-order-scope-settlement`。我不是唯一开发者，保留原 G7e4/E/B/root 资金片；不改 `CoopSettlementTest.java`、`CoopSettlementPayeeDedupTest.java`（B 独占），不改 ed106 checker/SQL/DAG/CI/退款算法。

## 已实现调用合同

`settleCoopTopicFromCashBasis` 先对**所有**原有效单调用原 `prepare(CoopOrder, Date)`，核对原 order/topic/initiator 身份，原单 ID 重复拒绝。随后调用现有纯合同类新增的 `CoopSettlementContract.requireIndependentOrderScopes(List<CoopSettlementPlan>)`，所有检查通过之后才构造、持久化原单各自应收/NONE claim。已存在原头的订单也不再在 prepare 前豁免合同核验。原头业务幂等不变，已完成主题的原 PLATFORM 哨兵仍保留。

范围判据：准备计划必须来自现有受支持冻结现金合同，line/header 归属全等；每个 line 都有正的原报名 ID、非负原金额及明确销售基数，单内 ID 不重复。相同受益人不得在两单中复用同一个报名销售/履约身份，即使其中一单 NONE/金额为零也不能借另一个原合同占用该经济请求。交叠时抛 `MANUAL_REPAIR:ORIGINAL_ORDER_SCOPE_OVERLAP`，不选最早、最大或任何赢家，也不改金额。

这沿用既有 claim 的 `fulfillment_type=REGISTRATION/fulfillment_id=原报名ID`；销售也按原报名金额归属，既有扫码、group-member、seat 证据归属该报名。现存 DTO/接口/Mapper/schema 都不变，不虚构扫码/节点 ID。当原证据确实提供互斥报名选集时，纯校验器允许各原单分开持久化；它本身不证明冻结合同已接受该来源，也不开放新路径。

原 `prepare/persist/appendExecutedAdjustment/freezeOriginals/applySucceededRefund` 签名不变。NONE 保留原零额 claim/不造应付头语义。原毛额头/原分项、保证金负调整+平台正收款、同事务、退款净额、payout、UNKNOWN/attempt 围栏保持原实现。

商家数仍是“唯一已核销商家”，不因原订单数增加而多加保证费率。对应 `CoopSettlementOriginalProducer` 持久 `originalVerifiedMerchantCount` 同步按唯一商家计，避免新的头数被当商家数。各原单头 `verifiedRegIds` 改为自己的真实 line 集合，平台/发起人原主题审计口径不变。

## 当前源合同缺口及最小后续边界

以下是这次修补的真实调用依赖，不是新全面审计：

- `FailClosedCoopOrderLineupSource.validate`：SUPPLY 只接受冻结 `fulfillment_evidence_rule_snapshot=REGISTRATION_SCAN`；TOPIC 只接受 GUIDED_TICKET，NODE 只接受 EXPLORE_PASS。
- `FrozenCoopOrderSettlementSnapshotReader`：从原 `scope_type_snapshot/scope_id_snapshot` 校验真实父关系，窗口由同主题冻结票目录解析；未冻结订单专属 ticket/registration/scan/seat 范围。
- `CoopSettlementContract.requireSupportedCashPath`：当前现金路径仍限定经典主题 TOPIC/TOPIC_EDITION，MERCHANT_PARTICIPATION/GUIDED_TICKET；NODE/ACTIVITY/CLUB 等原拒结不解除。
- `CoopSettlementEvidenceMapper.merchantScanPredicate`：扫码关联是原 `registration_id + member_id`、类型、状态、窗口；原扫码没有订单/节点归属，不能拿两个不同 order/node 数字 ID 宣称销售与履约独立。因此同商家的两张真实主题扫码单会得到相交选集，本片严格拒结并保持主题未结，不静默漏付后关账。
- 既有 `coop_group_verify` 的 `coop_order_id/activity_id` 和 `coop_group_member_verify.registration_id` 可提供订单专属证据，SQL 已有相应关联；但当前 SUPPLY 冻结来源禁止 COOP_GROUP_VERIFY。这里没有改变该冻结规则或用手造分组计划冒充真实 prepare 成功。

最小后续需 root 协调冻结合同/履约源 owner：明确采用哪个已有订单专属证据源，并由原合同写侧冻结它；有实际订单-履约-原报名销售范围证明之后再接入原 reader。NODE 需真实 node/order 对核销与原报名/席位的不可变归属；仅有节点父关系、game_id 或付款总额不够。不能用 live 配置、任意分币或修改 claim 唯一键放行。本片不申请新金额政策、不新增 schema，也未把此缺项标成已完成。

## 配套源码与验收状态

新增 `Wp04CoopOrderScopeMySqlIT`，复用原 UUID scratch/显式连接/hosted/DDL gate/清理工具、canonical 表和原迁移、真实冻结 lineup/reader/prepare、证据 XML、claim/detail 与 original producer。付款事实来自原 typed fixture producer，非真实渠道调用。Topic/ticket/node adapters 读取原 JDBC 行；topic 首锁与 CAS 是明确 SQL 夹具，不声称已测试 Spring facade/proxy。

新增 **7 个普通 @Test 声明**（没有实跑 case 证据）：

- `noneFirstCannotSilentlyDiscardOverlappingPaidOriginal`
- `paidFirstCannotHideOverlappingNoneOriginalEither`
- `twoPaidOriginalsCannotReuseSameRegistrationsSalesAndScans`
- `laterInvalidFrozenContractNeverGetsSkippedByAnEarlierPayee`
- `noneOriginalKeepsItsZeroClaimsWithoutInventingAPayableHead`
- `onePaidOriginalFreezesNonzeroGrossComponentsAndExactReplayIsZeroEffect`
- `nodeIdCannotInventIndependentSalesOrFulfillmentForAPaidOriginal`

三个范围交叠用例分别保留 NONE→有偿、有偿→NONE、双有偿的原实际顺序；均断言主题 CAS 回滚、零 head/claim/detail/close/component/adjustment/余额效果，原支付事实和消费回执仍保留。坏冻结合同不能按 payee 跳过；单 NONE/单有偿保留真实零额与非零正向及幂等；NODE 只验证原明确拒绝，不伪造独立范围。

已执行：`git diff --check`；静态确认两个 B unit 与 parent 字节一致，源文件 SHA256 如附表。**NOT_RUN：Java compile、unit、上述 MySQL、CI、provider；没有业务控制输出，不能声称红/绿已复现。** root 决定直接新 fixture 的精确 registry/gate 接线；复用现有 `WP04_ORIGINAL_REFUND_DDL_APPROVED` 与其所有连接/hosted/cleanup 门槛，G 未改 CI。

回退：仅源码回退；已持久的 claim/头/原分项/保证金调整与回执不得删除或释放身份。若回退本片会恢复同受款方静默跳过风险，未证明范围的订单不应靠回退强行关账。
