# 业主决定集成 · #803 #805 #806

分支 `integ/decisions-20261112`，基于最新 `origin/master`（含 #802 游玩/漫游、#807 结局规则 D-66），按 806 → 805 → 803 顺序以合并提交并入。

## 并入的决定
- #806（dec-ui-1）：D-38 删紧急求助入口、D-46 开场/完成屏不再自动前进、D-56 指认翻页卡左右滑、D-63 失败插图不抖、D-78 无封面不能存草稿/发布、D-119 金额空值显示 0。
- #805（dec-ui-2）：D-76 官方路线入口合同测试、D-81 发布预览风格/配色写回 formData.look、D-87 添加栏分割线图标、D-91 商家看玩家主页浅色、D-112 通行证卡字段、D-116 钥匙序列帧预下载。
- #803（dec-ui-3）：D-82 章节奖励（含迁移）、D-93 统一核销指标口径与自然日修正、D-118 图标补画。

## 冲突与处理
| 合并 | 冲突文件 | 处理 |
|---|---|---|
| 806 | `scripts/uiaudit/action-ledger.json` | 取一侧后用 `build-action-ledger.js --write` 重新生成，`--check` 通过 |
| 805 | `action-ledger.json` | 同上，重新生成（6026 项） |
| 805 | `tests/lint/ui-integrity-baseline.js` | 保留 master/#806 侧（含 #807 dec-b3 对 fabu 的 +28 行位移）与 #805 的注释；合并树上仅 `fabu/index.js` 两条 U4-DYNAMIC 锚点行号 7775→7781、10079→10085，未新增任何基线条目；`u4Dynamic` digest 按门禁算法重算 |
| 805 | `tests/unit/action-ledger.test.js` | 计数在两侧基础上相加：tap 3030、本地 tap 2651、总数 6026 |
| 803 | `action-ledger.json` | 重新生成（6027 项） |
| 803 | `action-ledger.test.js` | event-contract 2997、总数 6027（D-82 +1） |

master 的 #802 游玩/漫游逻辑与 #807 结局规则未被覆盖；`play-sos.js` 删除（D-38）与 master 的引用已核对（仅有断言其不存在的测试）。

## 门禁
- 动作台账 `--check`：PASS（6027）；路由矩阵：PASS（171 路由 / 27 场景）。
- UI-GATE-0：PASS（无新增基线项）。
- 小程序全量单测 `node --test chengyinhub-xcx/tests/unit/*.test.js`：17528 / 17528 通过（含 keyframes/motion 棘轮）。
- WXS 语法门禁、WXSS 选择器 / 组件选择器 / 值内注释门禁：PASS。
- danger-confirm 及 selftest：PASS。
- Java：按仓库约定（Java 构建/测试只在测试 VPS 上跑）未在本地运行，交 CI。
- 未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`。

## 遗留提示（来自原 PR）
- `ci/architecture/baseline.json` 中 `MerchantMarketingAnalyticsMapper.xml#selectAnalytics` 的 sha256 因 D-93 变更需更新；按约定未改 `ci/architecture/*`，架构门禁可能为红，需有权限者处理。
- `pages/play` 编译后体积需在开发者工具确认 < 2048 KiB。
- 迁移 `migration_20261112_chapter_reward.sql` 不部署，需用户走既有流程。

## CI
PR：#808（draft）。首轮（9fa3a127）仅 architecture gate 红：D-93 原实现改了 `selectAnalytics` 的 SQL 指纹，而任务约束禁止改 `ci/architecture/*`。随后分支上的后续提交把 D-93 改为保持 `selectAnalytics` 不变、由新增 `selectVerifyEventTimes` 提供核销时间，并并入既有店铺参谋服务（不新增未登记边界），同时补齐 `MerchantAggregateReadServiceTest` 的 `ReflectionTestUtils` 导入（后又去重）。
最终头 `df28a256`：32 项成功、3 项跳过、0 失败（含 architecture gate、Backend tests、Funds MySQL IT、小程序单测分片与静态门禁）。

PR: #808

## 架构闸收口：指标合并并入既有店铺参谋服务（PR #808）

- 问题：D-93 新增的 `IMerchantMetricsService` / `MerchantMetricsServiceImpl` 触发两条 NEW_UNOWNED_BOUNDARY，闸为 BLOCKED。
- 处理：删除这两个类型；共用的时间窗与核销计算并入既有 `IMerchantInsightService` / `MerchantInsightServiceImpl`（`metricWindow` / `metricWindows` / `metricVerify`），营销首页（`MerchantAggregateReadServiceImpl`）改调用它。`business.service` 下未新增任何类；未改 `ci/architecture/*`、`.github/workflows/*`。
- 保持不变：D-93 单一指标口径、`selectVerifyEventTimes`、`selectAnalytics` 及其结果 map、Asia/Shanghai 自然日边界及其测试（测试已改为装配真实的店铺参谋服务，仅桩核销事件查询）。
- 验证：按仓库规则未在本地跑 Java/Maven，以 CI 为准。最终提交 `df28a256` 的 GitHub CI、Backend tests、Funds MySQL IT 等均成功，Fast gates 作业成功（此前为失败）；日志中无 NEW_UNOWNED。中途一次失败为测试缺少 `ReflectionTestUtils` 导入，已修，未削弱任何测试。
- 说明：`gh pr checks` 因 GraphQL 被禁用，改用 REST 查看；architecture-findings 制品所在的 blob 域名被代理拦截无法下载，故以 Fast gates 作业成功及日志无 NEW_UNOWNED 作为证据，未能亲眼核对 artifact 内 verdict 字段。

STATUS: PARTIAL - 未能下载 architecture-findings 制品核对 verdict 字段（域名被拦截），其余已通过 CI 确认
