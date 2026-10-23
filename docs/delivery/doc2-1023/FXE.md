# DOC2 FXE · O-07 计提链 + F3 Phase 2 分账打款 · 只读对账报告与分阶段设计 · 2026-10-23

- 基线:`master @ f099c5be9eb3ef22214ce81e80303eb851f417dc`
- 授权范围(负责人 2026-10-23 批准):**只做设计和对账报告**。不打款、不动钱,生产代码的写路径一行不改。
- 本批改动:
  - 本文档。
  - 只读对账 SQL:`FXE-reconciliation.sql`。
  - 两份 DDL 草案,放在 `drafts/` 下。它们**不在** `sql/` 或 `chengyinhub-admin/sql/` 里,也没有登记依赖图。
  - 一套只钉现状的真库 IT:`FundsChainO07PinMySqlIT`,已登记进 `ci/backend-test.sh` 的 MYBATIS_ITS。
- **产品代码 0 行改动。**
- 前置文档:
  - `docs/delivery/doc2-survey-20261022/survey.md` §5(C5-1..C5-5)
  - `docs/delivery/doc2-f3-phase1-20261022/design.md` §7(K1..K8)
- 路径缩写:
  - `sys/` = `chengyinhub-system/src/main/java/com/chengyinhub/business/`
  - `mapper/` = `chengyinhub-system/src/main/resources/mapper/business/`
  - `base:` = `sql/base_schema_20260818.sql`
  - `msql/` = `chengyinhub-admin/sql/`

> **对任务卡前提的一处更正。** 卡上写「settlement_claim 只有 REDEMPTION 行」,与代码不符。
>
> - `SettlementEntryServiceImpl.java:349-355` 和 `CoopSettlementClaimServiceImpl.java:139-144` 都在写 `fulfillment_type='REGISTRATION'` 的 claim。盘点 C5-4 已经指出过这一点。
> - 准确的断点是:**`merchant_payable` 只能挂在 REDEMPTION claim 上**。原因有三:
>   - `redemption_id NOT NULL`(base:2909);
>   - `entry_type` 只允许 ACCRUAL/REVERSAL,`amount >= 0`(base:2924-2925);
>   - 唯一写入方是 `accrueOnRedemption` 和 `reverseOnRedemption`。
> - 所以凡是「不是一次核销」的资金事件,都既挂不上 claim,也进不了应付台账。这类事件包括:退款调整、套票罚没或换店、违规倒扣、负额结转。
> - 真库钉桩:`o07PayableShapeCannotRepresentNonRedemptionEvents`。

---

## 1. 现状资金链只读地图

### 1.1 主链(③ 自由探索 / 探索票，平台采购计酬档)

| # | 环节 | 入口 / 写点 | 落库 | 备注 |
|---|---|---|---|---|
| 1 | 下单 | `sys/service/impl/RegistrationOrderServiceImpl.java:45`(requestId + 指纹幂等)→ `CmsRegistrationServiceImpl.java:618`(`reserveEntitlements`)、`:622`(F3 `freezeOnOrderCreated`,默认关闭) | `cms_registration`;`registration_entitlement`(base:4383,每票每必选章一行) | 容量占位真源是 entitlement 的 status=0 |
| 2 | 支付回调 | `CmsRegistrationServiceImpl.java:2303-2328`(`completePaymentSuccess` → `settlementEntryService.recordOnPayment`) | `cms_registration.payment_status=2` | `SettlementEntryServiceImpl.java:60-85`:③ EXPLORE_PASS 在支付时**不入账**(:76-79),`recordPaidRegistration` 遇到 ③ 直接抛异常(:123-127) |
| 3 | 核销 | `ApiRegistrationController.java:1333` `/scan_qr_code_chapter` → `CmsRegistrationServiceImpl.java:3250` `redeemExploreChapterAndRecord` → `PerkLedgerServiceImpl.java:389` `redeemChapter` | `redemption`(base:4303;`active_key` 保证每票每章同时只有一条 ACTIVE)、entitlement 1、PERK 档扣 `quota_used` | 同事务内调 F3 `onChapterRedeemed`(`PerkLedgerServiceImpl.java:474-477`) |
| 4 | 结算主张 | `CmsRegistrationServiceImpl.java:3338` → `MerchantPayableServiceImpl.java:113-116` `settlementClaimService.claim(...)`;`SettlementClaimServiceImpl.java:26-52`(唯一键冲突后按 route 和 sourceContract 判重放或 `LEDGER_CONFLICT`) | `settlement_claim`(base:4698;7 列经济键 `uk_settlement_claim_key`) | claim 在两道闸**之前**创建:平台付费闸 `:120`、自营闸 `:130` |
| 5 | 应付计提 | `MerchantPayableServiceImpl.java:81-177` `accrueOnRedemption` | `merchant_payable`(base:2906):ACCRUAL,`idempotency_key=accrual:{redemptionId}`,`event_key=ORIGIN`,冻结 `per_head_fee_snapshot` | 金额 = 人头费 × 1(:170-172)。俱乐部直付分支在 `:108-111`,见 §1.2 |
| 6 | 核销撤销 | `CmsRegistrationController.java:157/188`(后台)→ `PerkLedgerServiceImpl.java:579-632` `reverseLockedRedemption` → `MerchantPayableServiceImpl.java:181-233` `reverseOnRedemption` | 追加一行 REVERSAL(`reversal:{redemptionId}`,复制原 claim_id 和快照) | claim **不变**;已开票批次只打 warn(:225-232) |
| 7 | 月结批次 | `MerchantPayableBatchController.java:65-67` `POST /business/payablebatch/generate` → `MerchantPayableServiceImpl.java:244-281` | `merchant_payable_batch`(base:2955):`amount_total` = 当月净额,在生成时冻结 | 只能结已经过完的月份(:248-251);批次成员按 `date_format(create_time)` 推导,表里不存 batch_id |
| 8 | 确认 / 打款 | `MerchantPayableServiceImpl.java:294-324` → `mapper/MerchantPayableBatchMapper.xml:98-112`(CAS,要求 `amount_total > 0`、未冻结) | PENDING → CONFIRMED → PAID(只记录凭证号,**不转账**,见类注释 :35-36) | 双人复核:`MakerCheckerGuard.requireDistinct`(:322) |
| 9 | 违规倒扣 | `MerchantClawbackServiceImpl.java:76`(选下一个未付批次)、`:132-142` → `mapper/MerchantPayableBatchMapper.xml:144-148` `deductForClawback` | **直接改** `amount_total`;找不到可冲抵批次时扣余额,不足部分记欠款 | 冻结 / 解冻:`MerchantPayableServiceImpl.java:352-375` |
| 10 | 退款 | `RefundServiceImpl.java:582-670` `reviewRefund` → `:878-918` `markRegistrationRefunded`(`releaseOnRefund` 只动 status=0 的 entitlement)→ `:642-669` 只冲 `settlement_detail` → `RefundPayoutServiceImpl.java:67-148` 原路退 | `refund_application`、`settlement_detail` 冲销 | **不碰 `merchant_payable`**。③ 已核销单走人工路放行(`RefundPolicy.java:132-140`、`RefundServiceImpl.java:858-876`) |
| 11 | 对账(现有) | `FourLedgerReconcileServiceImpl.java:122-143`(维度三:冻结额与台账净额比较);商家台 `mapper/MerchantDeskMapper.xml:251-276`(claim 汇总、无 claim 的应付行) | 只读 | 维度三不认识倒扣,见 B8 |

### 1.2 旁路

- **俱乐部直付**:offer 上 `payer_club_id` 非空时走这条路。
  - 代码:`MerchantPayableServiceImpl.java:108-111` → `ClubDirectMerchantReceivableService.java:35-76`。
  - claim 的 route 是 `CLUB_DIRECT`。迁移注释 `msql/migration_settlement_claim_20260811.sql:14` 的枚举里**没有**这个值,但表上也没有 CHECK,所以能写进去。
  - 应收状态:OPEN → SETTLED;撤销时 OPEN → VOID,SETTLED → REFUND_DUE → REFUNDED(`mapper/ClubDirectMerchantReceivableMapper.xml:55-69`)。
  - 和平台月结完全隔离。
- **系统 1 分账(①② 与活动)**:`SettlementEntryServiceImpl.java:97-186` 写 `settlement_detail`,并配一条 REGISTRATION claim。退款按比例逐条冲销(`RefundServiceImpl.java:926` 起)。
- **合作单(coop)**:`CoopSettlementClaimServiceImpl.java:139-144` 写 REGISTRATION claim、`coop_settlement_detail` 和 `coop_settlement_adjustment`。
  - 后者的 `(claim_id, entry_kind, event_key)` 正是 Phase A 要给 `merchant_payable` 复用的范式。
- **F3 套票(Phase 1,默认关闭)**:`FreeExploreBundleServiceImpl.java`。
  - 钩子:核销 `:214`、撤销 `:272`、退款 `:286`。
  - 罚没 `:497`、`:522`;商家退出 `REFUND_PENDING` 在 `:573`。
  - 单位价值按实付均分(`:146`,`UNITS_PER_CHAPTER=1` 见 `:54`)。
  - 退款报价 `FreeExploreBundlePolicy.java:65-99`(`PLATFORM_BALANCE` 路由只给报价,不执行)。
- **购物车(积分商城)**:
  - 链路:`ApiCartController.java:113/131` → `UmsMemberCartServiceImpl.java:232-261`(预览)/ `:263-353`(结算)。
  - 结算时一次性 `deductPoints`(`:285-295`),然后按商家拆成多张 `oms_order`(`:300-351`),最后**只返回第一张单的 id**(`:353`)。

### 1.3 打款(payout)今天是什么

- 平台对商家**没有任何自动打款**。`markBatchPaid` 只记录线下对公转账的凭证号。
- ③ 计酬档只覆盖自营主题(D21)。第三方之间分钱属于二清,需要等收付通(`MerchantPayableServiceImpl.java:124-135`)。
- F3 Phase 2「分账打款」因此是一个**新能力**,不是修旧链路。

---

## 2. 断点与不一致(每条附具体场景)

「钉桩」列指 `FundsChainO07PinMySqlIT` 中把该现状在真 MySQL 上复现的用例;「对账」列指 `FXE-reconciliation.sql` 中能检出它的查询。

| # | 级别 | 断点 | 具体场景 | 钉桩 / 对账 |
|---|---|---|---|---|
| **B1** | 高(O-07) | claim 在「主题须自营」闸之前创建(`MerchantPayableServiceImpl.java:113` 早于 `:130`) | 商家入驻了自营主题的计酬章,之后运营把内容槽改判为第三方(P0-35 允许)。商家每核销一次,就多一条 `route=CHAPTER_OFFER` 的 claim,应付却是 0,只在日志里留一条 warn。商家台把它算作生效 claim,对账端看到的却是「有凭据、没钱」 | `o07ClaimIsCreatedBeforeSelfOwnedGate…` / R02、R06 |
| **B2** | 高(O-07) | 应付表写不进任何「非核销事件」:`redemption_id NOT NULL`,entry_type 只能是 ACCRUAL/REVERSAL,金额 ≥ 0 | 套票持有人爽约,单位被罚没(FORFEITED,不写 redemption)。即使负责人决定给商家一笔补偿,也没有行可写。退款后的部分冲回、倒扣、负额结转同样写不进来 | `o07PayableShapeCannotRepresentNonRedemptionEvents` / — |
| **B3** | 中 | claim 没有生命周期。全仓没有任何写路径把 `settlement_claim.status` 改离 ACTIVE(`SettlementClaimMapper.xml` 只有 insert 和 select) | 店员误扫后被撤销,应付已经冲平(净额 0),claim 仍是 ACTIVE。`MerchantDeskMapper.xml:251-256` 按 status 计数,商家看到的生效 claim 数偏大 | `reversalLeavesClaimActive…` / R04 |
| **B4** | 中 | 存量 `claim_id IS NULL` 会被冲正复制下去(`MerchantPayableServiceImpl.java:201`)。唯一键 `uk_merchant_payable_claim_event` 遇到 NULL 不生效 | 0811 迁移之前的计提被撤销后,冲正行同样没有 claim,只能靠 `uk_payable_idem` 防重 | 同上 / R01 |
| **B5** | 高(退款→资金) | 退款审批不触达 `merchant_payable`。`RefundServiceImpl.java:642-669` 只冲 `settlement_detail`;③ 已核销单可经后台人工审批放行(`RefundPolicy.java:136-138`),全额也能批 | 玩家买 ¥30 探索票,在 A 店核销 1 章(计提 ¥15),之后以「商家没兑现」为由经人工全额退款。结果是玩家拿回 ¥30,平台仍欠 A 店 ¥15,而且这笔照常进月结打款。这是否正确取决于「核销即应付」口径在退款场景下的裁决(D-3) | `refundedRegistrationStillCarriesPositiveMerchantPayable` / R07 |
| **B6** | 高 | 负额批次卡死,且不会结转。`confirm` 和 `markPaid` 都要求 `amount_total > 0`(XML :102、:111 加服务层 :337-344),同时没有任何结转机制 | 1 月计提 ¥15 并已打款;2 月只发生了这笔的撤销,2 月批次为 -¥15,永远停在 PENDING;3 月新计提 ¥15,照常全额打款。商家实际多拿 ¥15,系统里没有任何待办 | `negativeBatchIsStuckAndNeverCarriedForward` / R10 |
| **B7** | 中 | 倒扣直接改 `amount_total`(XML :144-148),而且可以改**已经 CONFIRMED** 的批次 | 运营甲按 ¥100 确认了批次;倒扣 ¥30 经复核后执行,`amount_total` 变成 70;运营乙按 70 打款。甲确认的数和实际打出的数不一致,确认留痕失去意义 | `clawbackOffsetAndLateRows…` / R08 |
| **B8** | 低(对账误报) | 四账对账维度三(`FourLedgerReconcileServiceImpl.java:134-142`)直接拿冻结额和台账净额比,不扣除已执行的倒扣 | 每一笔合法倒扣都会被报成「批次完整性差异」。真漂移淹没在误报里 | 同上(断言「冻结额 ≠ 净额」)/ R08 已扣除倒扣 |
| **B9** | 中 | 孤儿应付。批次成员按 `date_format(create_time)` 推导。`create_time` 是 Java `new Date()` 在**写入时**取的值,而事务提交更晚;「只能结已过完的月份」的闸用的是 JVM 时区(:248、:377-379),和 MySQL 会话时区可能不一致 | 10-31 23:59:59 开始的核销事务在 11-01 00:00 之后才提交,而运营恰好在这之间生成了 10 月批次:这笔应付落在 10 月,却不属于任何已生成的批次。如果是该商家当月唯一一笔,它就永远不会被打款 | `clawbackOffsetAndLateRows…` / R08、R09 |
| **B10** | 低 | 冲正命中已开蓝票的批次时,只打一条 warn(`MerchantPayableServiceImpl.java:225-232`),不落待办 | 7 月批次已开票,8 月撤销了 7 月的一笔核销:红字发票没有任何跟踪,只能翻日志 | — / R11 |
| **B11** | 中(F3) | 单位维度。`redemption.active_key` 加 `uk_entitlement` 从结构上限定每票每章只核销一次(K1);claim 的 7 列唯一键里没有单位维度(K7) | 要卖「同一章 3 次」的套票,第 2 次核销会撞 `uk_redemption_active`。就算放开,第 2 条 claim 也会撞 `uk_settlement_claim_key` | 盘点 C5-1/C5-2;本 IT 的 B2 用例钉住了 claim 唯一键 |
| **B12** | 中(F3 退款→资金) | 套票的资金去向只有报价,不执行。`PLATFORM_BALANCE`(部分退到余额)和 `REFUND_PENDING`(商家退出、退未履约部分)都没有落库的待办(Phase 1 K2/K6) | 商家闭店,持有人选择「退未履约」,单位变成 REFUND_PENDING,但没有退款单,钱是否退出去全靠人工记忆 | — / R15 |
| **B13** | 中(购物车回读) | 购物车结算没有请求幂等键,只返回第一张单的 id;预览和结算都按**实时** SKU 价格计算,没有比对预期金额;扣积分不写积分流水,也不带订单 id;`pay_type` 被写成 `cart_type`(`:319`),而 `addCart` 从不设 `cart_type`(`:182-202`) | ① 一次结算跨 2 家商家,生成 2 张单,前端只拿到第 1 张的 id。② 网络超时后重试,购物车行已被删,只返回「购物车数据已变更」,玩家无法回读刚才是否成功、扣了多少积分。③ 预览看到 100 积分,运营在结算前改价为 120,实际扣 120 且没有提示。④ 积分账和订单无法逐笔对上 | — / R18 |
| **B14** | 低(文档漂移) | claim 的 route 实际有 `CLUB_DIRECT`,但迁移注释的枚举里没有;`settlement_claim.status` 也没有 CHECK | 新人照注释写校验,会把直付 claim 判为非法 | — |

已核实**不是**断点(以免误修):

- PERK/TRAFFIC 占一条 `route=NONE` 的 claim 是有意设计(`MerchantPayableServiceImpl.java:118`;`MerchantPayableServiceTest:219/233`)。
- 撤销后再核销会生成新的 redemption id,因此产生新的 accrual 键和新的 claim,不会撞键。
- 俱乐部直付应收被撤销时,SETTLED 会转为 REFUND_DUE,有追回态。
- 支付阶段对 ③ 不入账,有三道闸。

---

## 3. 只读对账查询与只读报表端点设计

### 3.1 查询清单(`FXE-reconciliation.sql`,全部是单条 SELECT/WITH)

| ID | 检出 | 对应断点 |
|---|---|---|
| R01 | 无 claim 的应付行 | B4 |
| R02 | 有 CHAPTER_OFFER claim、无 ACCRUAL | B1 |
| R03 | ACCRUAL 与所挂 claim 不一致(不存在、类型、履约 id、商家、路线) | O-07 完整性 |
| R04 | 核销已撤销、claim 仍是 ACTIVE | B3 |
| R05 | 计提与冲正不成对(撤销后漏冲、生效核销被冲、重复计提) | 冲正链 |
| R06 | 生效的平台计酬核销没有 ACCRUAL | B1 / 丢账 |
| R07 | 已退款报名仍有正净额应付 | B5 |
| R08 | 批次冻结额 ≠ 台账净额 − 已执行冲抵倒扣 | B7 / B8 / B9 |
| R09 | 该月已开批、本商家却无批次的孤儿应付 | B9 |
| R10 | 非正额批次卡死,以及其后已 PAID 的批次数 | B6 |
| R11 | 冲正命中已开蓝票批次,且没有红票号 | B10 |
| R12 | 俱乐部直付应收与核销状态不成对 | 旁路 |
| R13 | 同一核销同时进了平台应付和直付应收(双付) | 旁路 |
| R14 | 套票单位与核销流水不成对 | F3 |
| R15 | REFUND_PENDING 单位没有退款单 | B12 |
| R16 | 退款已批准,超过 24h 钱仍未出去 | 退款→资金 |
| R17 | 已批准退款累计超过实付 | 退款→资金 |
| R18 | 有金额的订单没有积分流水 | B13 |

`FundsChainO07PinMySqlIT.everyReconciliationQueryIsReadOnlyAndRunsOnRealSchema` 守着这份文件,做三件事:

1. 断言共 18 条,每条以 SELECT 或 WITH 开头,不含写入或加锁关键字,不含多语句。
2. 逐条在真 DDL(base + club_direct + F3 账本迁移)上执行一遍。
3. 关键条目(R01/R02/R04/R05/R06/R07/R08/R09/R10)必须对用例造出的现状场景命中,或按设计不误报。

**负控已实测**:把 R04 的 `r.status = 'REVERSED'` 改成 `'NEVER'`,`reversalLeavesClaimActive…` 变红;改回后转绿。

### 3.2 可选的只读后台报表端点(只是设计,**本批未实现**)

- **接口**:`GET /business/funds-recon/report?checks=R02,R07&periodFrom=2026-09&periodTo=2026-10&limit=200`
  - 返回 `AjaxResult`:`{ checkId: { title, rowCount, truncated, rows[] } }`。
- **权限**:新权限点 `business:fundsrecon:query`,通过菜单 SQL 迁移授权。只给财务和运营复核角色,和 `payablebatch:edit` 分开,保证「看差异的人」和「推进批次的人」可以是两个人。
- **只读保证(三层)**:
  1. Service 方法加 `@Transactional(readOnly = true)`,让 MySQL 连接进入 read-only 会话,任何写语句都会被服务端拒绝。
  2. 查询文本不从请求拼接。按 checkId 白名单,从打包进 jar 的 SQL 资源里取出 MyBatis 的 `<select>`。
  3. 有单测断言资源里只有 select。
- **数据边界**:
  - 不返回手机号、昵称等 PII,只返回 id、金额、状态、时间。
  - `limit` 上限 500;每条查询加 `MAX_EXECUTION_TIME` hint(例如 5s)。
  - 期间参数只影响带月份的查询(R08–R11)。
- **不做**:不提供「一键修复」或「重放」按钮;不导出带个人信息的 CSV;不接入定时任务。定时巡检属于 Phase A 的「对账零差异闸」(§4.4 G0),需另行批准。

---

## 4. 分阶段修复设计(O-07 + F3 Phase 2)

总原则:

- **账先对、再能打**。台账、批次、对账三件事零差异之前,不开任何打款能力。
- 每个阶段:
  - 都是独立迁移加默认关闭的开关;
  - 都有回退口径;
  - 每次推进都要负责人点头。
- 所有资金表保持 append-only:原行不 update,纠正一律追加事件。

### 4.1 Phase A —— O-07 计提链修复(不动钱)

**DDL 草案**:`drafts/draft_migration_fxe_o07_payable_links.sql`。本地 MySQL 8.0.46 上已实测:

- 连跑两次,末尾断言都返回 1;
- 列形态被改动后重跑,fail closed(`JSON_EXTRACT` 报错);
- `ck_payable_source`、`ck_batch_adjustment_sign`、`ck_batch_adjustment_maker_checker` 均按设计拒绝非法行。

草案只做结构,不回填:

1. `merchant_payable`:
   - `redemption_id` 放宽为可空;
   - 新增 `source_event_type` 和 `source_event_id`;
   - 新增 `ck_payable_source`:要么是核销事件,要么有完整的来源事件,二选一;
   - 新增 `idx_payable_source_event`。
   - **entry_type 仍只有 ACCRUAL/REVERSAL,表示方向**;事件语义由 `event_key` 和 `source_event_type` 承载。这与 `coop_settlement_adjustment` 的 `(claim_id, entry_kind, event_key)` 范式一致,`uk_merchant_payable_claim_event` 不需要改。
2. 新表 `settlement_claim_event`:claim 的 REVERSED / REOPENED / CLOSED 事件,append-only。`settlement_claim.status` 保留,作为同事务 CAS 维护的当前态投影。
3. 新表 `merchant_payable_batch_adjustment`:倒扣冲抵、负额结出和结入,有符号、append-only、带双人复核 CHECK。**从此不再改 `amount_total`**。

**代码改动清单**(本批不改,批准后另开 PR):

| 改动 | 位置 | 要点 |
|---|---|---|
| A1 claim 后移,或闸外降级 | `MerchantPayableServiceImpl.accrueOnRedemption` | 按 D-2 二选一。(a) 先判 platformPays 和 isPlatformOwned,非自营的计酬核销占一条 `route=NONE` 的 claim 并写待办;(b) 维持现状,R02 每日巡检。建议 (a):claim 的 route 应如实反映「平台是否欠钱」 |
| A2 claim 生命周期 | `reverseOnRedemption` 同事务内 | CAS `ACTIVE→REVERSED` 写入 `settlement_claim_event`,`event_key=REDEMPTION_REVERSED:{rid}`。已是 REVERSED 时空转 |
| A3 退款→应付 | `RefundServiceImpl.markRegistrationRefunded` 之后、同事务 | 按 D-3 执行。若选「退款冲回商家应付」:对该报名每条 ACTIVE 计提追加 REVERSAL,`event_key=REFUND:{refundId}`、`idempotency_key=refund:{refundId}:{claimId}`、`source_event_type=REFUND`,claim_id 指向原 claim。若选「商家已服务,应付保留」:不写台账,在退款单上记录 `merchant_cost_retained` 供对账白名单使用(R07 排除) |
| A4 倒扣改为追加 | `MerchantClawbackServiceImpl.executeOffsetBatch` | 改写 `merchant_payable_batch_adjustment(kind=CLAWBACK_OFFSET, idempotency_key=clawback:{id})`。只允许挂在 **PENDING** 批次;CONFIRMED 批次须先撤回确认(新增 CAS `CONFIRMED→PENDING` 并留痕),杜绝 B7 |
| A5 负额结转 | 新的 `carryForward(batchId)`,运营双人复核 | 负额批次写一笔 CARRY_OUT(+x,本批归零后视为结清,不打款);下一个 PENDING 批次写一笔 CARRY_IN(−x),`idempotency_key=carry-in:{from}->{to}`。没有后续批次时转为既有欠款账本 `SettlementDebtLedger` |
| A6 批次应付口径 | `confirm` / `markPaid` / 维度三对账 | 应付 = `amount_total + SUM(adjustment.amount)`;`requireProgressionAllowed` 和 SQL 闸都改为基于这个数;维度三按 R08 口径比较 |
| A7 红字发票待办 | `reverseOnRedemption` 命中已开票批次时 | 在 adjustment 或新增待办里落一行,替代只打 warn(B10) |
| A8 孤儿兜底 | `generateMonthlyBatches` | 闸改为「月份 < MySQL `CURRENT_DATE` 所在月 **且** 已过宽限期(例如次月 2 日 00:00 之后)」,与台账同源取库时间。批次生成后,R09 必须为 0 才能确认 |
| A9 存量 NULL claim | 一次性回填迁移(另行批准) | 只读预检:R01 行数和金额。按 D-1 给存量行补 claim(`TOPIC_EDITION/REDEMPTION/rid/MERCHANT/mid/CHAPTER_SUPPLY`,route 取 offer 当时的档位),或标记为 LEGACY 白名单。之后才能 `claim_id NOT NULL` |

**状态机**:

- **claim**:`ACTIVE —(撤销核销)→ REVERSED —(撤销被纠正,重新核销会生成新的 redemption,因此几乎不用)→ ACTIVE`;`ACTIVE/REVERSED —(对应批次 PAID 且净额结清)→ CLOSED`,CLOSED 是终态。每次迁移在 `settlement_claim_event` 留一行。
- **应付行**:不可变;纠正只追加。
- **批次**:`PENDING → CONFIRMED → PAID`,外加两条:`CONFIRMED → PENDING`(仅撤回确认,留痕),PENDING 上可追加 adjustment。PAID 之后发生的任何差额,都进入**下一个**批次的 adjustment,或落到欠款账本。

### 4.2 Phase B —— F3 单位级计提与退款落账(不打款)

前提:Phase A 上线,并且 R01–R11 连续 N 天零差异(N 由 D-8 定)。

- **单位维度**(B11/K7)。**不改** `settlement_claim` 的 7 列唯一键,改用 `fulfillment_type='BUNDLE_UNIT'`、`fulfillment_id=explore_bundle_unit.id`。
  - 单位 id 全局唯一,所以同一章的 N 个单位天然是 N 条 claim,不会放宽唯一闸。这回应了 RV3 B-527-1 对「DROP+ADD 唯一键」的担忧。
  - 草案 `drafts/draft_migration_fxe_f3p2_payout.sql` 只给 claim 加一个枚举 CHECK,把三种值钉死;末尾断言唯一键仍是 7 列。
  - 只要每章一次的约束还在(K1 = A),核销仍以 REDEMPTION 计提,BUNDLE_UNIT 只用于不经核销的单位事件。
- **罚没 / 爽约**(D-4):
  - 若决定商家有补偿:单位变为 FORFEITED 的同一事务内,`claim(BUNDLE_UNIT, unitId, route=CHAPTER_OFFER)` 加一行 ACCRUAL(`source_event_type=BUNDLE_UNIT`、`source_event_id=unitId`、`idempotency_key=unit-forfeit:{unitId}`),金额 = 预约门店 offer 的人头费 × 补偿比例。
  - 若决定商家无补偿:不写任何东西,保持 Phase 1 的现状。
- **换店**(`SWAP_CONSENTED`):原单位不计提;新单位核销时按新门店的 offer 正常计提。不需要新事件。
- **退款落账**(B12/K2):
  - 部分退到平台余额:新增退款路由列 `refund_application.refund_route`(ORIGINAL_METHOD / PLATFORM_BALANCE),余额入账走 `ums_member_balance_detail`,用新的 event_type 和 `event_id=refund_id`,幂等键 `refund-balance:{refundId}`。
  - 被退的单位同事务置为 REFUNDED。
  - 已核销的单位按 D-3 的口径处理商家应付。
- **罚没绕过**(K3):`RefundPolicy` 增加判据「存在 FORFEITED 单位 ⇒ 转人工」。这是资金路径改动,需单独评审。

### 4.3 Phase C —— 分账打款(真正动钱)

前提:

- Phase B 稳定;
- 负责人选定通道(D-6);
- 第三方计酬(二清)问题有了合规结论(D-7)。

在此之前,打款仍是**线下对公转账 + `markBatchPaid` 记凭证**,本阶段只是把它升级为可审计的指令。

- **指令表** `merchant_payout_instruction`(草案):
  - 与批次 1:1。生成列 `active_batch_key` 加唯一键,保证同一批次同时只有一条非终止指令。
  - 金额 = 冻结额 + adjustment,并配一个 `amount_fingerprint`(SHA-256,覆盖批次、金额、台账行 id 集合、调整行 id 集合)。
  - CHECK 约束:maker ≠ checker;APPROVED 必须有 checker 和 approved_at;SUCCEEDED 必须有 external_ref。
- **状态机**:

  ```
  DRAFT --approve(checker≠maker, 指纹重算一致, G0..G3 全过)--> APPROVED
  DRAFT --cancel--> CANCELLED
  APPROVED --submit(开关开启, 额度内)--> SUBMITTED --通道回执成功--> SUCCEEDED → 批次 PAID(同事务)
                                         \--通道回执失败--> FAILED → 新建指令(attempt+1)
  APPROVED --cancel(未提交)--> CANCELLED
  ```

  每次迁移都写一行 `merchant_payout_instruction_event`。`uk_payout_event_transition` 保证同一迁移只记一次。

### 4.4 幂等键、冲正矩阵与审批闸

**幂等键一览**:

| 事件 | 键 | 唯一约束 |
|---|---|---|
| 核销计提 | `accrual:{redemptionId}`(保留) | `uk_payable_idem` + `uk_merchant_payable_claim_event(claim, ACCRUAL, ORIGIN)` |
| 撤销冲正 | `reversal:{redemptionId}`(保留) | 同上(`REDEMPTION_REVERSED:{rid}`) |
| 退款冲回 | `refund:{refundId}:{claimId}` | 同上(`REFUND:{refundId}`) |
| 单位罚没补偿 | `unit-forfeit:{unitId}` | 同上 |
| claim 状态 | `(claim_id, event_type, event_key)` | `uk_claim_event` |
| 倒扣冲抵 | `clawback:{applicationId}` | `uk_batch_adjustment_idem` |
| 负额结转 | `carry-out:{from}` / `carry-in:{from}->{to}` | 同上 |
| 余额退款 | `refund-balance:{refundId}` | 余额流水(event_type, event_id)唯一(随 Phase B 迁移新增) |
| 打款指令 | `payout:{batchId}:{attempt}` | `uk_payout_idem` + `uk_payout_active_batch` |

**冲正处理矩阵**(撤销核销或退款冲回发生时,原计提所在批次处于不同状态):

| 原批次状态 | 处理 |
|---|---|
| 尚无批次(当月) | 当月追加 REVERSAL,净额自然抵消 |
| PENDING | REVERSAL 落在**发生月**(批次归属口径不变)。原批次冻结额不变,差额由下一期净额承担。若下一期为负,走 A5 结转 |
| CONFIRMED | 同上;不自动撤回确认 |
| PAID | 同上;若原批次已开蓝票,写入红字发票待办(A7) |
| 有指令处于 SUBMITTED | 不打断在途指令;差额只进下一期 |

**审批闸**(Phase C 每一次打款都必须全过):

- **G0** 对账零差异:该商家和期间的 R01–R11 全为 0,否则不能生成指令。
- **G1** 批次已 CONFIRMED、未冻结,应付 > 0。
- **G2** 指纹重算一致:台账和调整自确认以来没有变化。
- **G3** maker ≠ checker,checker 持 `business:payout:approve` 权限。
- **G4** 全局开关 `chengyin.funds.payout.enabled` 默认 false;单笔和单日额度上限由配置给出,默认 0 即禁用。
- **G5** 首月灰度:只放白名单商家;通道回执必须回写 `external_ref`。

### 4.5 测试计划

| 层 | 内容 |
|---|---|
| 单测(Mockito) | 每个新闸一正一负,并做负控实测(参照 `MerchantPayableServiceTest` 的范式):A1 路由、A2 CAS 空转、A3 两种口径、A4 拒绝改 CONFIRMED 批次、A5 结转幂等、A6 口径、指令状态机的非法迁移 |
| 真库 IT | `FundsChainO07PinMySqlIT` 按设计**翻转**,不删除:B1 → R02 为 0;B3 → R04 为 0;B5 → 按 D-3 改断言;B6 → 2 月 CARRY_OUT、3 月 CARRY_IN,3 月打款 0 元;B7 → CONFIRMED 不可冲抵 |
| 迁移 IT | 正式迁移重跑零变更、形状被改则 fail closed(参照 `FreeExploreBundleLedgerMySqlIT`);`uk_settlement_claim_key` 仍为 7 列 |
| 并发 | 同一核销并发撤销和退款只冲一次;同一批次并发生成指令只成功一条;倒扣与确认并发时以 CAS 为准 |
| 对账回归 | R01–R18 在全部 IT 夹具上只命中预期行;`everyReconciliationQueryIsReadOnly…` 持续守住只读 |
| 预发演练 | 在还原的预发库上跑 R01–R18 基线;Phase A 上线后连续 N 天(D-8)零差异,才申请 Phase B |
| 回退 | 每个 Phase 都是新增列和表,开关默认关;回退 = 关开关,不删表(append-only 事实不得删除) |

---

## 5. 进入 Phase 2 之前,负责人必须拍板的决策

| # | 决策 | 选项 | 影响 |
|---|---|---|---|
| **D-1** | 存量 `claim_id IS NULL` 的应付行(R01)怎么处理 | A 按当时 offer 补 claim 并回填;B 打上 LEGACY 白名单、不补;只有 A 之后才能 `claim_id NOT NULL` | A9、B4 |
| **D-2** | 非自营主题的计酬核销(B1) | A claim 降为 `route=NONE` 并落人工待办(推荐);B 维持现状,靠 R02 巡检;C 照付(违背 D21,不推荐) | A1 |
| **D-3** | ③ 已核销单被人工全额或部分退款时,商家应付怎么办(B5) | A 冲回(商家承担);B 保留(平台承担,商家已服务);C 按退款原因区分,例如「商家未兑现」冲回、「玩家原因」保留 | A3、R07 白名单 |
| **D-4** | 套票单位罚没或爽约时,商家有无补偿;有的话按什么比例 | A 无(Phase 1 现状);B 按人头费 × x% | Phase B 罚没计提 |
| **D-5** | 负额批次怎么处理(B6) | A 自动结转到下一期 PENDING 批次(双人复核);B 一律转欠款账本;C 人工逐笔 | A5 |
| **D-6** | 打款通道 | A 继续线下对公(指令只记录凭证);B 微信分账(需要商户号能力、证书,并评估二清) | Phase C 是否接入任何外部通道 |
| **D-7** | 第三方主题的计酬(D21 的边界)是否在 Phase 2 放开 | A 不放开,仍只覆盖自营;B 等收付通上线后再议 | 自营闸是否保留 |
| **D-8** | Phase A → B → C 的观察期 | 零差异连续天数 N、灰度商家白名单、单笔和单日额度 | G0、G4、G5 |
| **D-9** | 多单位套票(K1) | A 保持每章 1 个单位;B 放开,需改 `redemption` 唯一键(线上 DDL,单独评审) | B11 |
| **D-10** | 单位价值与人头费的关系(K4) | 是否要求「单位均摊价 ≥ 人头费」作为发布闸,防止卖一单亏一单 | Phase B 发布校验 |
| **D-11** | 购物车回读(B13)是否纳入 Phase A | A 纳入:requestId 幂等、返回全部订单 id、预期金额比对、积分流水;B 另立条目 | 积分商城 |
| **D-12** | 只读报表端点(§3.2)是否实现;权限授给谁 | 实现 / 不实现;角色 | 新菜单和权限 SQL |

---

## 6. 验证证据与边界

- **真库 IT**:`FundsChainO07PinMySqlIT` 7/7 通过(`tests=7 errors=0 skipped=0 failures=0`)。
  - 环境:本机 MySQL 8.0.46,命令 `CHENGYIN_FUNDS_MYSQL_IT=1 mvn -pl chengyinhub-admin test -Dtest=FundsChainO07PinMySqlIT`。
  - 测试只建立随机命名的临时库,结束后删除。
  - 已登记进 `ci/backend-test.sh` 的 MYBATIS_ITS,由 GitHub CI 的 MySQL service 执行,并由 `assert_it_ran.py` 回读确认 skipped=0。
- **负控**:破坏 R04 后,对应用例变红;改回后转绿(见 §3.1)。
- **DDL 草案**:在本地临时库上验证了「连跑两次幂等、形状漂移 fail closed、CHECK 和唯一键按设计拒绝」,之后已删除该库。草案**未**放进任何迁移目录。
- **没做的事**:
  - 没有改任何生产代码或写路径;
  - 没有新增依赖;
  - 没有连接生产库;
  - 没有执行任何迁移;
  - 没有打款或动钱;
  - 没有实现任何端点。
