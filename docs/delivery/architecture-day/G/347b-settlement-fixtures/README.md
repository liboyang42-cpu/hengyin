# 原单结算 / 据点来源夹具修补

源码包完成；未执行修后验证。Parent `347bcd0226c3bed71616e38ea7c1391fc76efe5c`，独立分支 `codex/architecture-day-g-347b-settlement-fixtures`。仅七个获分派测试文件与 G 交付目录；没有改生产 guard、Mapper/XML、SQL、CI、DAG、渠道或其他 owner 文件。

## 共同首因与接线

1. **旧 schema**。已读取 run40221047209 三个指定 job 原日志。Scope 的 3 error，以及 Refund 的 8 error / 2 failure 均包含 `CoopInviteMapper.selectCoopInviteList -> Unknown column supply_station_id`；退款两 failure 原本要求 IllegalStateException，被缺列异常提前截断，未到原 UNKNOWN/注入失败断言。没有改这些断言或异常要求。

   原 Scope、退款保证金分支都从 canonical base 装配旧 invite。现在复用 `Wp04OriginalRefundMySqlIT.stationScopeSchema(source)`：原 merchant-station base、原审计 migration、原 governance 中同一条 audit ALTER、完整 canonical `migration_20261025_coop_order_station_fulfillment.sql`。按原顺序执行一次；Station 的重复装配移入共同入口，原 regex 唯一性断言亦原样保留。未手写缩表/单独补列或修改旧 migration。新增源码断言可空新列、无历史站点归属；原 FK、immutable triggers 全部来自 canonical。原独占 UUID 库/连接准入/清理不变。

2. **站点审计时间输入**。Station 原 9 error 全停在 `recordConfirmedStationFulfillment` 原窗口/身份复合检查；日志没有输出逐项值，不能称已观察实际九个时间。源码可见 JDBC 连接指定 GMT+8，票/核销时间是 SQL DATETIME，原审计却用 `Timestamp.valueOf` 按 JVM 时区解释同一字面量。现审计时间直接由同连接当前原报名 `verification_time` 读取，保留原日期/原冻结窗，不从当前时间猜，也不扩大容差。原 order/topic/merchant/station/terms hash/audit/generation 与 source INSERT 全不改。

   新 `originalFrozenWindowKeepsInclusiveStartAndExclusiveEndWithoutTimezoneTolerance` 源码：08:59:59、10:00:00 拒绝且 audit/凭证/核销零提交；09:00:00 接受并精确读回原票起点毫秒。原失败回滚场景额外要求确实到 `INJECTED_AFTER_REAL_STATION_RECEIPT`，避免先被其他拒绝误当回滚通过。原 12 case 全保留。

3. **旧组合替身缺少来源 header**。`PreparedCoopPlanFixture.install` 的全部直接 caller 只有 GroupBasis、PerMerchant、CoopSettlement 三类。该 helper 是已有明确的纯组合输入替身，并非实际 reader/claim/evidence。它的原 scalar 金额/人数 oracle 和 mocked persist 保留；补明确原 order/topic/context/beneficiary/service/scope 与原已声明报名集合的 header-complete OriginLine。金额按原输入合计，不增加扫码/席位/audit/payment receipt，不 mock 独立范围守卫，不放开真实 prepare 的现金或缺履约拒绝。尤其旧 summary 中“人数100而登记集合仅1”仍是既有聚合组合样本，不能当真实逐席位或 claim 持久化证明；零接待者 scalar 仍 0 元 / 0 人，不借新增范围信息变成履约成功。

   **共享 helper 未在 G 树直接覆盖**：root 必须解码并串行应用 `prepared-fixture.review.patch.b64`；decoded SHA256 `86ae4020db5629a7df4b115f220b4bc2b85f15965bb473f3663643291e1e0524`。忘记应用时这三类仍缺来源。本增量保留 helper API，没有新 caller 合同。

   Group/PerMerchant 各增一个源码控制，调用真实 scope/header 检查并断言原 CLUB 角色、现金路径仍拒绝、未服务者 0 元 / 0 人、不写经济头；未把 CLUB 改成 MERCHANT/GROUP。CoopSettlement 原“已结算跳过”正例补原商家100元、2个原报名、30%合同来源，仍先验证该原单，再由原已存在头计数阻止重复写；原平台10/保底5/毛利85金额断言全部保留。入口 guard 独立 plan 补同一 header。null-plan 断言精确对齐当前生产 `COOP_CLAIM_PLAN_IDENTITY_CONFLICT`，仍为 IllegalStateException、零写、无 live 聚合 fallback，未放宽为任意异常。

## 声明与验证边界

| 文件 | parent 普通 @Test 声明 | 本片声明 |
|---|---:|---:|
| CoopSettlementGroupBasisTest | 6 | 7 |
| CoopSettlementPerMerchantTest | 5 | 6 |
| CoopSettlementTest | 29 | 29 |
| Wp04TopicSettlementEntryGuardsTest | 14 | 14 |
| Wp04CoopOrderScopeMySqlIT | 11 | 11 |
| Wp04OriginalRefundMySqlIT | 25 | 25 |
| CoopOrderStationFulfillmentMySqlIT | 12 | 13 |

以上是源码声明计数，不是实跑 case。入口 guard 当前 parent 已有14声明，未用较旧 XML 的12冒充本源码数量。新增总计3，原声明/金额/权限/UNKNOWN/身份/异常类型/清理全部保留。

仅源读取、原 job 日志、Git diff 与 SHA；**parse / tests / compile / Java / DB / HTTP / CI 全部 NOT_RUN**，未 dispatch，未 DDL/外呼。未声称资金或据点链验收关闭。root 应一次合入七文件和共享 helper 窄 hunk，按同源实际回归裁决；本片无证据要求生产修改，故生产零变更。
