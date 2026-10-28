# 独立范围守卫与 cash 准入分工（NOT_RUN）

parent `fac725d135e550ca23ca95eabfdb0317b872db1a`；生产底为已收 33fa。分支 `codex/architecture-day-g-scope-guard-separation`。本片不启用 station 提案，不含 DDL/迁移/共享 caller 变动。

仅改 `CoopSettlementContract.requireIndependentOrderScopes`：移除该方法中重复的经典 cash 准入调用，改为显式通用不变量。保留正 order/topic/initiator/beneficiary/context/scope 身份，同批同 topic/initiator；角色/服务配对只接受 MERCHANT/MERCHANT_PARTICIPATION 或 CLUB/ORGANIZER_LEAD；mode/route 必须为已知一致组合，有偿计划必须有原分项。原 line/header 归属、明确非负销售/原金额、注册身份/重复、NONE 零额与同受益人交叠拒绝均保留。不因移除准入留下 null/未知 plan 漏检。

**Cash 准入没有删除或放宽。** `CoopSettlementClaimServiceImpl.prepare` 与原 `requireSupportedCashPath` 定义、`originLines` 及该 guard 之外全部生产字节未改。真实 CLUB/NODE/ACTIVITY 等仍必须经过 prepare 原严格拒绝；此方法只允许完整 CLUB 等计划进行范围形状校验，不证明它已通过生产现金准入。B 原 CLUB 组合 unit 不需伪改 MERCHANT；两 unit 文件未编辑。

已有 `Wp04CoopOrderScopeMySqlIT` 原 7 声明全部保留，新增 4 声明（总 **11 普通 @Test 声明，不是实跑 case 数**）：
- `completeClubScopePlanDoesNotEnableRealUnsupportedClubCashPrepare`
- `scopeGuardRejectsMissingSnapshotAndBrokenOriginalIdentities`
- `scopeGuardRejectsForgedRolesUnitsRoutesAndUnprovenLines`
- `scopeGuardAllowsDisjointShapeButRejectsSharedSalesIncludingNoneInBothOrders`

其中 CLUB 用例从真实冻结 lineup/reader 读取角色/来源，范围校验通过后，再走实际 Topic→prepare 并要求 UNSUPPORTED_CASH_PATH、原主题未结且零经济写入。其他新增用例是纯范围形状控制（放在既有 fixture 中），覆盖 null snapshot/plan、缺失/非正身份、跨 topic/initiator、伪角色/unit、未知 mode/route、缺/坏 line/header/销售范围、重复与交叠/NONE 顺序；这些手工形状计划不生成事实/claim，不冒充被真实 prepare 接受的独立 station 来源。

执行仅：`git diff --check`，静态确认生产 diff 完全位于该方法、prepare 与两个 B unit 字节不变、原 7 声明保留、新增 4 声明。源码 hash 见附表。**Java 编译、unit、MySQL、CI 全部 NOT_RUN**；没有红/绿业务输出，不把 11 声明当 PASS。root 串行集成并更新现有精确 case count 后统一验收。
