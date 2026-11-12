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
PR：#808（draft）。首轮 CI（提交 9fa3a127）：Backend tests、Funds MySQL IT、小程序单测分片 20/20、静态门禁等 29 项成功，3 项跳过；仅 1 项失败：
- `Fast gates (CWD / API existence / silentError)` 内的 architecture gate 步骤红。原因与 #803 说明一致：D-93 改了 `MerchantMarketingAnalyticsMapper.xml#selectAnalytics` 的 SQL，`ci/architecture/baseline.json` 中该条的 sha256 需要更新；任务约束禁止修改 `ci/architecture/*`，故未处理，也不属于 “CI checkout is neither PR head nor its exact synthetic merge” 的重跑情形。需有权限者更新该基线条目后再跑。

PR: #808

STATUS: PARTIAL - architecture gate 需更新 ci/architecture/baseline.json（selectAnalytics sha256），按约束未改
