# 商家/俱乐部承接与多方分账 · 技术设计

> 日期:2026-07-12
> 产品真源(决策已锁):《商家俱乐部承接与多方分账_设计spec_2026-07-05》
> 本文档只定**技术实现与分期**,不改产品决策。
> 前置已完成:§6.3 type1 俱乐部分账资损 Bug 已修(commit `771ce0fc`,`resolveOrderPayee` 按 toType 解析)。

---

## 1. 背景与目标

把 coop 合作从"发起人一人管全场 + 每商家一行结算"升级为:
- **运营权委托**:发起人留所有权/核心编辑/换俱乐部权;组织权(带队/核销/运营编辑)下放承接方。
- **分项计价**:一张票拆成若干"行",每行归一个人、各结各的,加人不稀释。
- **责任链**:平台单一入口 + 先行垫付 + 过错方扣划。

目标:俱乐部真能赚到带队钱、多商家参与不互相稀释、出事有据可追偿。

## 2. 已锁产品决策(引用原 spec §一,不重述细节)

D-收款(平台代收各方被结算)· D-模型(运营权委托)· D-分账(分项计价无共享池)· D-定价(乙-2 价格优先+保底)· D-发起人(保底 5%+2n% 挂核销/封顶5家)· D-角色(创建者=发起人)· D-移交(商家移交俱乐部强制城市定向+有人带)· D-核销(承接方现场负责人)· D-责任(谁承接谁担+连带+过错扣划)· D-重机制(coop_order/¥50保证金/信用/双死线/lifecycle 全保留只加不删)。

## 3. 技术设计

### 3.1 账期:统一 T+7 + 客诉冻结【已定】
- 放款时点沿用现有 `CmsTopicServiceImpl.payoutDueCoopSettlements`(T+7,即 `payableTime = endDate + 7d`),**不引入第二套 T+3 账期**。
- 新增「客诉冻结」:结算单/主题上有未结客诉(见 §3.6)时,`payoutDueCoopSettlements` 跳过该主题相关行,不放款。客诉关闭后正常放。
- 理由:T+7 本身覆盖客诉窗口;一套账期最省改动,复用现成放款/对账机器。

### 3.2 分项分账数据模型:扩 `coop_settlement` 加 `line_type`【已定】
- **不新建主子表**。现表 `coop_settlement` 已是"每 coop_order 一行 {merchant_id=收款人, amount, status, payable_time, verified_reg_ids}",天然可当"分项行"。
- 迁移 `migration_coop_settlement_line.sql`(幂等 `IF NOT EXISTS`)给 `coop_settlement` 加:
  - `line_type` varchar:`PLATFORM` / `INITIATOR_GUARANTEE`(发起人保底)/ `INITIATOR_MARGIN`(发起人毛利)/ `LEAD_FEE`(承接俱乐部带队费)/ `MERCHANT_VERIFY`(商家核销费)。
  - `payee_type` varchar:`platform` / `initiator` / `club` / `merchant`(便于对账口径,收款人仍落 `merchant_id`=真实 member_id)。
  - `coop_order_id` 允许为空(平台/发起人行没有对应 coop_order)。
- 结算时 `settleCoopTopic` 由"每 order 一行"改为"每主题生成 N 行分项",`merchant_id` 一律经 §前置 `resolveOrderPayee` 落成真实 member_id;`PLATFORM` 行只入账不放款(payout 跳过 `payee_type=platform`)。

### 3.3 五分项结算模型 + 守恒
一张票拆成(平台佣金不放款,其余各成一行):
| line_type | 收款人 | 金额 |
|---|---|---|
| PLATFORM | 平台 | 总核销销售额 × 10% |
| INITIATOR_GUARANTEE | 发起人 | 销售额 × (5% + 2%×N_核销商家),封顶 +10% |
| LEAD_FEE | 承接俱乐部主理人 | 分成 x%×核销销售额 / 固定 ¥F×核销人头 / 引流 0 |
| MERCHANT_VERIFY | 各后加入商家 | 各自礼品/券实际核销额,独立成行 |
| INITIATOR_MARGIN | 发起人 | 终价 − 平台 − 保底 − 带队费 − Σ商家核销费(残差,≥0) |
- **守恒断言(先算不写→校验→再落库,沿用 #1 引入的 `assertSettlementConservation` 模式)**:平台 + 保底 + 带队 + Σ商家 + 毛利 == 总核销销售额;任一行为负 = 超拆 → 抛异常告警,拒绝动钱。
- N_核销商家 = 本场有真实核销的被邀商家数(防凑数,`+2%` 仅对有核销的激活)。

### 3.4 乙-2 定价保底
- 成本地板 `P_min = C ÷ (1 − 平台率 − 发起人保底率 − Σ分成型比率)`,C = 带队成本/成团人数 + Σ固定型费 + 发起人自担权益成本。
- 保底率 = 5% + 2%×min(邀商家数, 5)(定价阶段按"招募锁定的商家数"预估,结算时按"真实核销商家数"重算激活)。
- 落 `PricingServiceImpl`:新增 `initiatorGuaranteeRate(topicId)` + `priceFloor(topicId)`;定价页滑杆下限 = P_min。纯计算,单测覆盖。

### 3.5 运营权委托(权限)
- 现状:带队/核销 gated 给 `activity.member_id`(=商家)。改为:该主题若已被 type1 俱乐部承接,gated 给**承接俱乐部主理人**(`resolveOrderPayee`/`resolveInviteeOwner` 解析)。
- 核心编辑(奖励/票价/结算方式/安全底线)锁发起人;运营详情编辑(集合时间地点/领队分工)承接俱乐部可改并报备留痕通知发起人。
- 主办展示双署名:发起方(主)+ 承接执行方(次)。

### 3.6 客诉 / 责任链(四件套)
- 新表 `coop_complaint`(工单):topic_id、reporter、被诉方(过错方 member/club/merchant)、状态(受理/垫付/追偿/关闭)、垫付额、过错比例、扣划记录。
- 平台单一入口受理 → 先行垫付(平台余额)→ 冻结相关结算行放款 → 从过错方保证金/未结算金扣划 → 关闭解冻。
- 责任归属按 spec §8.1(带队→俱乐部;商家礼品→对应商家;路线规则→发起人),连带责任玩家可找任一方。

## 4. 分期实施(每期可独立上线验收)

### P1 · 钱算对(核心,先做)
- 迁移:`coop_settlement` 加 line_type/payee_type/放开 coop_order_id 可空。
- `settleCoopTopic` 改分项生成 5 类行 + 守恒断言扩展到五分项。
- `PricingServiceImpl` 乙-2 保底 + P_min。
- 俱乐部收入看板:`ApiClubController` 新增 `/api/club/mybiz`(镜像 `/api/coop/mybiz`:履约率+结算记录+本场核销进度+预计带队收入)。
- B-Fix 真机验证:一笔 type1 俱乐部带队核销 → T+7 → 主理人余额到账 + 明细。
- **验收**:单测覆盖五行守恒、保底激活/封顶/抗稀释、P_min 计算;真机验一笔 type1 到账。

### P2 · 权与放款
- 运营权委托:带队/核销 gating 改承接俱乐部主理人;核心编辑锁发起人 + 运营详情可改留痕。
- 客诉冻结接入 `payoutDueCoopSettlements`(有未结客诉跳过放款)。
- 前端:发布流第三步开关(商家「邀俱乐部带队」「开放商家市场」/俱乐部「愿意商家参与」)+ 打开才露字段;双署名展示。
- **验收**:单测权限归属;冻结挡放款用例;前端开关露字段真机走查。

### P3 · 责任链
- `coop_complaint` 工单表 + 单一入口受理 + 先行垫付 + 过错方保证金/未结算扣划 + 过错比例(判例式,非一刀切)。
- 移交规则:商家主题移交俱乐部 → 强制城市定向+有人带(非此类型转类目或复制一份 + 移交前提醒)。
- 配额护栏:每场被邀商家合计封顶 5(不分商家/俱乐部邀)。
- **验收**:单测扣划守恒、移交转类目、配额拦截。

## 5. 不在本期(P2 备选,写清避免默认覆盖)
GPS 地理围栏核销、异常抽检自动化、强制人身责任险、过错比例仲裁完整工单流、后加入商家风险金(非对称保证金扩展)。这些留待后续评估,本计划不含。

## 6. 开放项 / 风险
- 微信支付实付/退款/垫付回合需生产真证书验证(P3 客诉垫付依赖)。
- 「同城参考价」依赖同类主题样本,冷启动样本不足时降级为纯提示(P1 定价页可先不做参考价,仅 P_min 下限)。
- 分项行数增多 → `coop_settlement` 行量上升,`payoutDueCoopSettlements` 需确认按主题批量放款性能(P1 观察)。

## 7. 前置已完成
- §6.3 B-Fix(type1 俱乐部分账资损):`resolveOrderPayee` 已落码(commit `771ce0fc`),P1 只需在真机验证到账闭环。
