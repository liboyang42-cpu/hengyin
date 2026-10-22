# DOC2 F3 自由探索（product_type=2）套票 · Phase 1 设计 · 2026-10-22

- 决策依据：D-11 只批准 Phase 1，内容是设计、DDL、只读接口，以及默认关闭的售卖/核销。
- **不在本批**：Phase 2 的分账打款，以及 `merchant_payable` 计提口径修复（O-07），均未获批，本批一行未动。
- 前置盘点：`docs/delivery/doc2-survey-20261022/survey.md` §5（PR #483）。
- 性质：funds-adjacent，仅 Phase 1，默认关闭，合并前需评审。

## 1. 原则：在既有链路上补件，不另起一套

下表是复用的既有入口和既有表。订单真源仍是 `cms_registration`。

| 环节 | 复用的既有入口 / 表 | 本批补的件 |
|---|---|---|
| 主题页 / 章节招商 | `cms_topic`（product_type=2）、`cms_topic_chapter`、`merchant_chapter_offer` | 只读目录 `GET /api/free-explore/bundle/catalog` |
| 购票 | `/api/registration` → `RegistrationOrderServiceImpl`（requestId 幂等键 + 指纹）→ `CmsRegistrationServiceImpl.createRegistrationInternal` | 在 `reserveEntitlements` 之后、同一事务内调用 `freezeOnOrderCreated`：冻结快照，并按持有人发放单位 |
| 权益 | `registration_entitlement`（每票每章一行） | 新增 `explore_bundle_grant` / `explore_bundle_unit`：按 (订单, 持有人, 项目, 发放) 记余额，每单位一码 |
| 核销 | 商家扫玩家券：`/scan_qr_code_chapter` → `PerkLedgerServiceImpl.redeemChapter` → `redemption` | 插入核销流水之后、同一事务内调用 `onChapterRedeemed`，消耗该持有人的一个单位 |
| 撤销 / 退款 | `reverseLockedRedemption`、`releaseOnRefund` | 调用 `onRedemptionReversed` / `onRegistrationRefunded` 处理对应单位 |
| 计提 / 结算 | `MerchantPayableServiceImpl.accrueOnRedemption`、`settlement_claim` | 不改任何代码。只给 `settlement_claim` 加 `unit_code` 维度（DDL），留给 Phase 2 使用 |
| 风控 | `VerificationRiskService` | 不改。master 已对 FREE_EXPLORE 跳过 0.90 高核销率规则，保留 burst 检测（见 `VerificationRiskFreeExploreTest`） |

扫码角色分开：

- **核销**：商家扫玩家券，走既有接口。
- **玩法**：玩家扫节点任务码，走既有玩法接口。

本批没有新增任何扫码入口。

## 2. 表（两条新迁移，均未执行）

### `migration_doc2_f3_bundle_ledger_20261022.sql`：只新建 6 张表

| 表 | 关键约束 | 用途 |
|---|---|---|
| `explore_bundle_order` | UK `registration_id`、UK `idempotency_key`；mapper 没有 update/delete 语句 | 建单时冻结五个版本：product / quota / policy / contract / settlement_rule，并存快照正文 |
| `explore_bundle_grant` | UK `(registration_id, holder_member_id, chapter_id, grant_no)` | 发放记录，即余额键；记录有效期 `valid_from/valid_to` |
| `explore_bundle_unit` | UK `unit_code`、UK `redemption_id`；CHECK「状态为 REDEEMED ⇔ redemption_id 非空」 | 每个单位一行，单位状态就是持有人余额的真源 |
| `explore_bundle_reservation` | 生成列 `active_unit_key` 加 UK，同一单位同时只能有一条 BOOKED；UK `(holder, idempotency_key)` | 预约 |
| `explore_bundle_waiver` | UK `(member_id, waiver_month)` | 每人每自然月一次主动豁免（Asia/Shanghai） |
| `explore_bundle_exit_case` | UK `(unit_id, exited_offer_id)`、UK `(holder, idempotency_key)` | 商家退出后由持有人做的决定 |

幂等方式：

- 建表用 `CREATE TABLE IF NOT EXISTS`。
- 列和唯一键各算一个 SHA-256 fingerprint 做比对，不一致时 guard 主键冲突，脚本报错退出。
- `FreeExploreBundleLedgerMySqlIT` 在真 MySQL 上验证：迁移可以重跑；表形状被改过之后重跑会报错（负控）。

### `migration_doc2_f3_settlement_claim_unit_20261022.sql`：给 `settlement_claim` 加单位维度

- 新列 `unit_code VARCHAR(32) NOT NULL DEFAULT ''`，唯一键扩成原 7 列加 `unit_code`。
- 默认值用空串，不用 NULL。MySQL 唯一键里 NULL 互不相等，用 NULL 会让存量 7 列键失去唯一性。
- 现有 `SettlementClaimMapper` 不引用这一列，所以 jar 部署在迁移之前或之后都能跑。
- 脚本先判唯一键形状、再执行 DDL，形状不对就报错，不会半途停下。
- 需要在低峰期执行：唯一索引会重建（INPLACE、LOCK=NONE）。

两条迁移都已登记进 `ci/migration-dependencies.json`。

## 3. 余额与快照

- **余额只按持有人算**：持有人余额 = 该持有人在 `explore_bundle_unit` 中按状态计数的结果。
  - 永远不读 `merchant_chapter_offer.quota_total - quota_used` 这类共享名额。mapper 的契约测试会拦住这种读法。
  - A、B 买同一套票是两张 registration，各有一组单位。`selectUnitsForUpdate` 把 holder 写在 WHERE 条件里，所以 A 的核销锁不到、也改不到 B 的行。
- **同一请求只产生一张单**：
  - 建单幂等沿用既有的 `cms_registration.request_id` + 指纹。
  - 套票单本身有 UK `registration_id` 兜底，重放时直接返回原单，不再写入。
- **结果 UNKNOWN 时回读**：`GET /order/receipt?requestId=`。
  - 回 `NOT_FOUND`：原请求没有落库，可以用同一个 requestId 重试。
  - 回 `FOUND`：返回完整订单视图。
- **改产品不影响已售快照**：快照只插不改。改章节名之后，新单的 `product_version` 会变，旧单的 `snapshot_json` 逐字不变（真库 IT 已验证）。
- **小程序和 App 读到同一张单**：两端共用同一组 `/api` 接口，同一份回读结构，接口不接收任何端或渠道参数（有测试钉住）。
- **切换分类不产生下单锁**：目录接口是纯读，不调用 ForUpdate，不写库（有测试钉住）。

## 4. 产品规则（按任务卡原样实现）

| 规则 | 实现位置 |
|---|---|
| 主动退套票：完全未用 → 原路全额 | `FreeExploreBundlePolicy.refundQuote` → `ORIGINAL_METHOD` |
| 部分使用，剩余价值占比 > 20% → 可退平台余额 | 同上 → `PLATFORM_BALANCE`，金额为剩余价值 |
| 剩余价值占比 ≤ 20% → 不走常规退款，商家违约走售后 | 同上 → `NOT_REFUNDABLE` + `afterSalesForMerchantBreach=true`。恰好 20% 也落在这一档 |
| 商家退出 / 闭店：经持有人同意换等值项，否则退未履约部分；不静默换店，不缩短有效期 | `decideMerchantExit`：只在门店确实已不在营时允许处置。`SWAP_CONSENTED` 的目标只能是同章节的在营门店，且其供给期必须覆盖单位有效期；`REFUND_UNFULFILLED` 把单位置为 `REFUND_PENDING` |
| 预约：开始前 ≥ 12h 免费取消 | `cancelReservation` → `CANCELLED`，单位退回可用。恰好 12h 也算免费 |
| < 12h 取消或爽约：只消耗该单位，不另收现金 | `LATE_CANCELLED` / `NO_SHOW`，单位置为 `FORFEITED` |
| 每会员每自然月一次主动豁免 | `useWaiver=true`，靠 UK `(member, month)`；本月已用过时返回 `BUNDLE_WAIVER_USED`，不改任何状态 |
| 罚没不记为真实核销，不产生商家分成 | `FORFEITED` 不写 `redemption`，`redemption_id` 保持为空（CHECK 约束保证）⇒ 计提链路根本看不到这类单位 |

## 5. 接口（`/api/free-explore/bundle`，统一返回 AjaxResult）

- 返回码：业务拒绝统一为 `409`，并带 `businessCode`（如 `BUNDLE_NOT_FOUND` / `BUNDLE_LEDGER_OFF` / `BUNDLE_WAIVER_USED` / `BUNDLE_IDEMPOTENCY_CONFLICT`）。
- 归属校验：「不存在」和「不是你的」返回同一个 `BUNDLE_NOT_FOUND`，防止按 id 枚举他人订单。

| 方法 | 路径 | 开关 | 说明 |
|---|---|---|---|
| GET | `/catalog?topicId=` | 无（只读既有表） | 只返回已开售的 ③ 主题；不外露商家采购价 |
| GET | `/order?registrationId=` | ledger | 本人订单 + 余额 + 单位 + 预约 + 退出处置 |
| GET | `/order/receipt?requestId=` | ledger | 下单结果 UNKNOWN 时回读 |
| GET | `/order/refund-quote?registrationId=` | ledger | 只读报价，不执行任何资金动作 |
| POST | `/reservation` | reservation | 带幂等键 + 指纹；同键并发时，失败的一方在新事务里走重放分支 |
| GET | `/reservation/receipt?idempotencyKey=` | ledger | 回读预约 |
| POST | `/reservation/{id}/cancel` | reservation | `{useWaiver}`；对已终态的预约重复调用，原样返回 |
| POST | `/exit-decision` | reservation | `{unitId, decision, targetOfferId, idempotencyKey}` |
| GET | `/merchant/reservations?from=&to=` | ledger | 需商家 VERIFY 权限，区间最长 31 天，只能查本店 |
| POST | `/merchant/reservation/{id}/no-show` | reservation | 需商家 VERIFY 权限，只能记本店预约，且须已过开始时间 |

## 6. 开关（全部默认 false，未改 application.yml）

| 配置键 | 作用 |
|---|---|
| `chengyin.free-explore.bundle.ledger-enabled` | 允许读写套票表。关闭时所有钩子空转，既有路径行为与改前一致 |
| `chengyin.free-explore.bundle.sale-enabled` | 新的 ③ 报名单冻结快照并发放单位。必须同时开 ledger 才生效 |
| `chengyin.free-explore.bundle.reservation-enabled` | 打开预约、取消、爽约、商家退出处置。必须同时开 ledger 才生效 |

- **上线顺序**：执行两条迁移 → 部署 → 开 ledger → 评审第 7 节的冲突 → 再开 sale / reservation。
- **关闭顺序**：先关 sale；等在途套票单清零后，才能关 ledger。ledger 一关，核销就不再扣单位，余额会和真实核销脱节。

## 7. 与现有代码的冲突（按任务卡要求停下，给出选项，未替决策方选）

| # | 冲突 | 本批做法 | 选项 |
|---|---|---|---|
| K1（=C5-1） | `redemption.active_key` 和 `uk_entitlement` 从结构上限定「每票每章只能核销一次」 | 每章固定发 1 个单位（`UNITS_PER_CHAPTER=1`）。表结构已支持 N 个单位 | A：保持每章 1 次。B：放开为多单位，需要改 `redemption` 唯一键（线上 DDL，需先问） |
| K2 | 部分退「平台余额」需要新增一条余额入账的资金路由；且现有 `RefundPolicy` 以 `verification_status` 为唯一判据，首次核销后整单不可退 | 只给只读报价，不执行。完全未用的单仍走既有原路退款 | A：部分退长期走人工售后。B：Phase 2 新增余额退款路由，需资金评审 |
| K3 | 罚没（FORFEITED）不置 `verification_status`，所以有罚没的单仍能走既有整单原路全退，可以绕过罚没 | 不改既有退款路径；reservation 开关默认关闭，罚没不会发生 | A：在冲突解决前不开 reservation。B：给 `RefundPolicy` 加「有 FORFEITED 单位须人工处理」的判据（改资金路径，需评审） |
| K4 | 单位价值的口径未定 | 按实付均分，余数落在最后一个单位，保证合计等于实付 | A：均分。B：按章节 `perk_min_value` 或人头费加权 |
| K5 | 有效期来源 | 取活动 `end_date`；没有结束日期时拒绝冻结（仅在 sale 开启时生效）。主题直售路径（ownerType=1）在 API 层不可达 | A：沿用活动结束日。B：在 `settlement_rule.entitlement_days` 等处配置独立有效期 |
| K6 | 商家退出的触发方式 | 由持有人拉起：门店确已不在营时，才允许处置。不挂钩既有的 offer 撤回流程，也不自动改单。`REFUND_PENDING` 的钱走既有人工退款 | A：持有人拉起。B：在撤回流程里自动开处置单（改既有路径） |
| K7（=C5-2/C5-4） | `settlement_claim` 的单位维度 | 只做 DDL（默认 `''`），现有写入方不变；按单位写 claim 属于 Phase 2 计提 | Phase 2 决定 |
| K8 | 有效期到期后不会自动翻转状态 | 在读余额和核销时按时间判断 `expired`，不新增定时任务 | A：保持按时判断。B：加到期任务（要补 sys_job 注册 SQL） |

## 8. 验证

- `FreeExploreBundleServiceTest`：21 条 Mockito 单测。
- `FreeExploreBundleLedgerMySqlIT`：4 条真 MySQL 用例，已登记进 `ci/backend-test.sh` 的 MYBATIS_ITS。
- 两套测试在本机 MySQL 8.0.46 上均全绿。最终以 GitHub CI 的结果为准。
