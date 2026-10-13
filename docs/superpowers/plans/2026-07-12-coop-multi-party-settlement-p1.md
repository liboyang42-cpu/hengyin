# 多方分账 P1「钱算对」实施计划

> **For agentic workers:** 用 superpowers:executing-plans 逐任务执行,checkbox 追踪。
> 设计真源:`docs/superpowers/specs/2026-07-12-coop-multi-party-settlement-design.md`

**Goal:** 把 coop 结算从"每商家一行"升级为"分项计价 5 行 + 守恒",落乙-2 保底定价,补俱乐部收入看板;每步 TDD。

**Architecture:** 扩现有 `coop_settlement` 表加 `line_type`/`payee_type`(不新建表),`settleCoopTopic` 生成分项行并守恒;`PricingServiceImpl` 加保底/地板;`ApiClubController` 加收入看板。复用现有 T+7 放款/对账机器。

**Tech Stack:** Spring Boot 多模块 · MyBatis · JUnit5 + Mockito · 本机 mvn 3.9.16/Java21。

## Global Constraints
- 只 commit 不 push;pathspec add + commit 同一条 bash 命令(防并行会话冲刷)。
- 迁移幂等 `IF NOT EXISTS`;不碰生产库。
- 守恒断言必须"先算不写→校验→再落库"(沿用 #1 的 `assertSettlementConservation` 位置)。
- 收款人一律经 `resolveOrderPayee` 落真实 member_id(平台行除外)。

---

### Task 1: 迁移 — coop_settlement 加分项列

**Files:** Create `chengyinhub-admin/sql/migration_coop_settlement_line.sql`

- [ ] 写幂等迁移:`ALTER TABLE coop_settlement ADD COLUMN IF NOT EXISTS line_type varchar(24) DEFAULT 'MERCHANT_VERIFY'`(平台/INITIATOR_GUARANTEE/INITIATOR_MARGIN/LEAD_FEE/MERCHANT_VERIFY)、`payee_type varchar(16) DEFAULT 'merchant'`、`coop_order_id` 改可空(已可空则跳过)。加注释。
- [ ] 登记进部署台账(交接文档),不跑生产。

### Task 2: settleCoopTopic 分项生成 + 五分项守恒(TDD 核心)

**Files:** Modify `chengyinhub-system/.../service/impl/CmsTopicServiceImpl.java`(`settleCoopTopic`/`assertSettlementConservation`);domain `CoopSettlement` 加 `lineType`/`payeeType` 字段 + mapper insert 列;Test `chengyinhub-admin/.../CoopSettlementTest.java`

**Interfaces:**
- Produces: `settleCoopTopic(CmsTopic, BigDecimal)` 现生成 N 行分项:平台10% + 发起人保底 + 各商家核销费(现有 order 行,markline MERCHANT_VERIFY)+ 带队费(type1 俱乐部行,LEAD_FEE)+ 发起人毛利残差。
- 保底率 `initiatorGuaranteeRate = 0.05 + 0.02 * min(N_核销商家, 5)`(封顶 0.15)。

- [ ] **Step 1 失败测试**:`settleCoopTopic_splitsIntoLineItems_conserves` — 构造 1 主题:总核销销售额100、平台10、发起人保底(0商家→5%=5)、1 商家分成型20%→20(MERCHANT_VERIFY)、发起人毛利残差=100-10-5-20=65。断言插入的行含 line_type 且金额守恒 sum==100。
- [ ] **Step 2 跑测试确认失败**:`mvn -pl chengyinhub-admin -am test -Dtest=CoopSettlementTest`(新用例红:无 line_type/无保底行)。
- [ ] **Step 3 实现**:CoopSettlement 加 lineType/payeeType;settleCoopTopic 第一遍算各行(平台行 payee=platform 不放款、保底行 payee=initiator=topic.memberId、商家行现逻辑、type1 带队行、毛利残差行);`assertSettlementConservation` 扩成五分项和 == 总销售额 + 各行≥0。
- [ ] **Step 4 跑测试确认通过** + 回归旧 CoopSettlementTest 全绿。
- [ ] **Step 5 保底激活/封顶/抗稀释用例**:`+2%仅真实核销商家激活`、`封顶5家=+10%`、`加1商家不动其他行`(3 个断言),跑绿。
- [ ] **Step 6 commit**(pathspec:CmsTopicServiceImpl + CoopSettlement + mapper.xml + Test,原子 add&&commit)。

### Task 3: 乙-2 保底 + 成本地板(PricingServiceImpl,TDD)

**Files:** Modify `chengyinhub-system/.../service/impl/PricingServiceImpl.java` + `IPricingService`;Test `PricingServiceImplTest`(若无则建)

**Interfaces:**
- Produces: `BigDecimal initiatorGuaranteeRate(int verifiedMerchantCount)`(0.05+0.02n 封顶0.15)、`BigDecimal priceFloor(BigDecimal cost, BigDecimal shareRateSum, int merchantCount)` = C ÷ (1 − 平台率 − 保底率 − Σ分成率)。

- [ ] **Step 1 失败测试**:保底率 0商家=0.05、3商家=0.11、6商家=0.15(封顶);priceFloor 给定 C/率 → 期望地板值。
- [ ] **Step 2 跑失败** → **Step 3 实现** → **Step 4 跑绿** → **Step 5 commit**(原子)。

### Task 4: 俱乐部收入看板 /api/club/mybiz

**Files:** Modify `chengyinhub-admin/.../api/ApiClubController.java`(+ 需要的 service/mapper 查询)

**Interfaces:**
- Produces: `POST /api/club/mybiz` 返回俱乐部主理人:履约率 + 结算记录(LEAD_FEE 行)+ 本场核销进度 + 预计带队收入。镜像 `/api/coop/mybiz` 口径。

- [ ] **Step 1**:定位 `/api/coop/mybiz` 实现,抽出俱乐部口径(按 payee_type=club / member_id=主理人 过滤 coop_settlement)。
- [ ] **Step 2**:加 `/api/club/mybiz`(归属校验:调用者须为该俱乐部主理人)。
- [ ] **Step 3**:能编译 + 有单测覆盖聚合逻辑则加;commit(原子)。

### Task 5: B-Fix 真机验证(手工,交接)

- [ ] 写验证清单:一笔 type1 俱乐部带队核销 → 结算 → T+7 payout → 主理人 `ums_member.balance` 到账 + `ums_member_balance_detail` 明细。交给用户在真机/生产验(本机连不上生产库)。

---

## 分期后续(不在 P1)
P2 权与放款(运营权委托+客诉冻结+发布开关)、P3 责任链(客诉工单+垫付+扣划+移交+配额)各自出计划。

## Self-Review
- Spec 覆盖:P1 覆盖设计 §3.2/3.3/3.4 + §6.2 + §6.3验证 ✓。P2/P3 明确后续。
- 无占位:各 Task 有具体文件/测试断言/命令。
- 类型一致:lineType/payeeType 命名贯穿 Task1-4;initiatorGuaranteeRate 签名一致。
