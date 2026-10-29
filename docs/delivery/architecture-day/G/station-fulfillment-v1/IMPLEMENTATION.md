# 新确认一单一据点：G 实现候选（NOT_RUN）

parent `047dff9bd9f64762ce3b7c2f0839d10e803956f4`；分支 `codex/architecture-day-g-station-fulfillment-v1`。只交本批源增量，原33fa/047d及G抵扣/退款/payout源保留；不编辑 E invite/domain/mapper/controller、A RegistrationRedemptionApplication/报名生产、D UI、B 两unit、root SQL/DAG/CI。

## 共享合同与实际入口

唯一新增接口：`ICoopSettlementClaimService.recordConfirmedStationFulfillment(Long verificationAuditEventId): java.util.Optional<Long>`，实现 MANDATORY+显式 active/non-readonly transaction 检查。没有 applicable confirmed V1 contract 才 empty；missing audit/unknown V1/歧义/冲突绝不假成功。正值是真插入并完整回读或原 proof 全载荷一致的永久回执 ID。

E/原接受 owner 负责冻结以下既定 JSON（G 不代写/回填）：
`terms_snapshot.supplyBillingScope={version:'REGISTRATION_STATION_V1',stationId:原station,topicId:原topic,merchantMemberId:原toId}`。
A 负责真实 REGISTRATION_STATION_REDEEM audit 插入后在原 topic→registration 事务调用此接口。G 未复制其 caller 或改变原权限；root 串行合入已收 A/E 调用者。

新纯 support `CoopOrderStationScope` 严格解析四键、正整数 ID、原 merchant/SUPPLY 归属，保留原 raw terms 字节 SHA；未知/残缺 scope 拒绝，旧无 scope 不推定新归属。锁下复核同 topic/member 的 active V1 order station 不重复。没有把 SUPPLY/REGISTRATION_SCAN 偷换 GROUP/ORGANIZER。

写入：源 audit 仅定位 → 原 topic FOR UPDATE → 原 registration FOR UPDATE → 原 audit/核销世代/原 effective orders 当前读 → 真实 FrozenReader 与原 supported-cash guard → 源报名状态/金额/主体、audit前后状态/actor/实际station/窗口/原terms SHA → append严格INSERT SELECT → 完整回读。新写须 topic 未结；原完整 proof replay 可回读闭合后的原 ID。只有原本地事实，不调用 SDK/外部 provider，也不造 PAYMENT VERIFIED。

INSERT SELECT 逐项连接原 order/invite 的 accepted supply_station_id、原 station、原 registration 和实际 audit；校验同 topic/merchant、原角色/选集/证据规则、原金额、source action/target、原 terms SHA/冻结 JSON 与时间。FK只证明引用存在，scope隔离依靠此guard与同事务Java current-read强断言，不把六个独立FK冒充scope证明。没有 INSERT IGNORE 或 UPDATE/DELETE receipt 方法。

receipt.source_fingerprint 包含原 order/topic/member/registration/station/audit、原销售基数、原 terms SHA、原 audit时间/actor/请求/前后状态和原报名主体/数量/核销时间；重放逐字段与指纹一致，原记录永不改写。核销世代按原 registration 锁下原核销/撤销事件当前读，旧事件不释放身份。

## Reader 与原账本

FrozenReader 保留全部旧准入/归属校验，并从原 JSON 填入 snapshot `billingStationId/orderTermsSha256`（无scope的历史原单仍是旧路径）。TopicEvidence 对新V1只走新Mapper的原 order+station+terms hash 凭证选集，完全不读 merchant scan 汇总作为 V1 候选/fallback。它先锁原报名，再锁 proof/audit，核对当前有效世代、完整源与销售快照。旧世代保留但不计酬。当前 live station 状态不用于重算历史已成立履约的归属。

候选增加 `stationFulfillmentId` 仅作此源的精确比对；原 origin line/claim/key/头/detail/原分项/原退款净额/保证金负调整与正收款/payout皆不改变。新源按实际报名范围生成每单计划，33fa/047d 的全单写前交叠检查仍强制，商家数仍唯一商家。

V1 当前源是原整单station核销，不把 order_num 扩为真实核销席位。FIXED_PER_FULFILLMENT 多人票缺station席位证明时继续原 FIXED_FEE_QUANTITY_UNPROVEN；原 GROUP席位源不被偷用。NODE/ACTIVITY/CLUB真实cash guard未放开。历史未确认合同不补JSON/事件，若仍与新单范围交叠仍拒结，不能自动重分历史销售。

## Canonical schema 与 F 精确接线

root唯一提供 `chengyinhub-admin/sql/migration_20261025_coop_order_station_fulfillment.sql`；本分支不复制/改该文件，交付时该root新文件尚不在本parent，须同源集成后才能运行新fixture。proof表12列：id/order_id/topic_id/beneficiary_member_id/registration_id/station_id/verification_audit_id/sales_basis_amount/order_terms_sha256/source_fingerprint/fulfilled_at/recorded_at。topic/member为BIGINT UNSIGNED，其余引用ID signed BIGINT；DECIMAL(15,2)，两ASCII/bin CHAR(64)，两DATETIME(3)。proof **6 FK**，invite.supply_station_id另1 FK；原audit永久唯一/两immutable触发器由root canonical负责。

新增 FQCN：

- `com.chengyinhub.business.domain.CoopOrderStationFulfillment`：纯实体，非managed service。
- `com.chengyinhub.business.mapper.CoopOrderStationFulfillmentMapper`：新MyBatis mapper/proxy。
- `com.chengyinhub.business.service.support.CoopOrderStationScope`：纯静态叶子，非managed service。

新增 Spring managed service：**0**。既有 ClaimService 和 TopicEvidence 各注入此 mapper，FrozenReader 仅调用纯scope叶子；构造器不变，无@Lazy/Provider/反调环。

Mapper **10 statements**：selectAuditRegistrationLocation（仅路由）、selectRegistrationForUpdate、selectStationForUpdate、selectAuditForUpdate、selectVerificationEventsForUpdate、selectEffectiveOrdersForUpdate、selectByAuditForUpdate、selectByOrderForUpdate、insertConfirmed（唯一writer）、selectV1CandidatesForUpdate。所有 FOR UPDATE 显式 useCache=false/flushCache=true。写consumer仅 `CoopSettlementClaimServiceImpl.recordConfirmedStationFulfillment`；其余为该owner和TopicEvidence只读current consumers。Root/F按这些实际类型、表和方法登记，G未编辑policy。

## 测试源码与实际状态

原 `Wp04CoopOrderScopeMySqlIT` **11个普通@Test声明保留**，仅接真实新mapper/owner字段供新fixture复用；B两个unit未动。新增 `CoopOrderStationFulfillmentMySqlIT` **12个普通@Test声明**，并非12个执行通过：
- `twoConfirmedStationsFreezeIndependentOriginalSalesAndReplayNeverPaysTwice`
- `confirmedNoneOriginalDoesNotHideLaterIndependentPaidOriginal`
- `v1MissingProofNeverFallsBackToExistingMerchantScanSummary`
- `changedFrozenPayloadCannotReuseOriginalProofOrReleaseIt`
- `failureAfterActualAuditAndReceiptRollsBackWholeLocalVerification`
- `concurrentSameOriginalAuditReturnsOneRealReceipt`
- `duplicateConfirmedStationFailsBeforeAuditAndVerificationCanCommit`
- `laterReversalGenerationRetainsButDisqualifiesOldProof`
- `rawMapperCannotBorrowAnotherOrderStationRegistrationOrAmount`
- `canonicalReceiptsAreImmutableAndUnsignedParentKeysMatch`
- `noApplicableV1ReturnsEmptyButMalformedV1CannotBecomeLegacy`
- `fixedFeeNeverExpandsPurchasedQuantityWithoutStationSeatProof`

fixture复用原 hosted/显式loopback/端口账号密码/UUID scratch/准确清理门槛，canonical原表、原audit migration及原治理ALTER，再引用root确定的新migration文件。类型/FK/immutable均对真实canonical SQL断言，不手写缩水表。源核销CAS/audit在夹具中用真实SQL/原auditMapper/原Spring事务明确安排，实际调用G hook/prepare/claim/original producer；**不冒充A caller/proxy已跑或provider真实外呼**。错误source/条款变异和旧世代是明确negative fixture，不自动给历史原单补归属。静态形状控制不当DB真实性证明。

已执行的仅：XML well-formed解析（10 statements）/FOR UPDATE属性静态核对；git diff --check；静态确认原Topic/Invite生产、B两个unit字节未改，旧11声明保留。Java compile/unit/MySQL/CI/provider全部 **NOT_RUN**，没有BUSINESS PASS输出，没有执行DDL。root合入canonical/A/E及精确policy/registry后统一验收。

回退：停新V1写入后回退caller/reader；新proof/原audit/原claim/原分项不得删除、释放或以legacy汇总重解释。已有新V1合同不能靠移除读路径来临时走旧SCAN；保持原合同版本和proof可追溯，由root按统一回滚计划处理。

## A 的精确审计类型接缝

本parent的 `writeVerificationAudit` 未设置actorType，原 Mapper 用COALESCE保存UNKNOWN；新V1原凭证要求与真实调用者一致的MEMBER/SYS_USER命名空间。附 `A-actor-type.review.patch` 供root/A串行核对：本 station API 的actorMemberId本来就是已授权member，仅在真实station核销源写明MEMBER，不改权限/CAS/金额、不猜历史身份。G未改A文件；若A已明确设置正确actorType则保留其现实现，不重复覆盖。新fixture显式MEMBER符合该真实新caller合同，不能把夹具设置当A生产接线已验证。
