# Lane C2 — acceptance-213 CloudTen / CI 夹具复核（实施文档一 §5.3，包 E3）

基线：`origin/master` @ `063a6c3c`（2026-10-22）。输入：acceptance-213 旧工作树中与 master 不同的 36 个非文档路径（其中生成物 `scripts/uiaudit/action-ledger.json` 未随输入提供）。
判定规则：只补「master 上没有等价形态」且「master 当前有测试/门禁/工作流读取」的夹具与契约；不整树复制。

## 结论

| 判定 | 数量 |
|---|---|
| ADDED | 0 |
| EQUIVALENT | 3 |
| NO_CONSUMER | 1 |
| CONFLICTS_WITH_CURRENT | 32 |
| 合计 | 36 |

本 lane 没有需要新增的夹具或契约：旧树的 CloudTen 原生 MySQL IT 覆盖在 master 上已以另一形态存在，且更严格。

### CloudTen 覆盖在 master 上的等价形态

旧树做法：在 `ci.yml` 的 backend job 里每次推送都跑 `Run cloud ten native MySQL IT by exact classes`，由 `ci/test_cloud_ten_ci_contract.py` 锁住该步骤并回读 9/8/7 份 surefire 报告。

master 做法（已覆盖旧契约的每一项要求）：

- `ci/real-storage-it-registry.json` 登记 `ExplorationAnniversaryMySqlIT`=9、`PersonalExplorationGoalMySqlIT`=8、`PersonalExplorationExportMySqlIT`=7，门禁 `CHENGYIN_TEN_FEATURES_MYSQL_IT=1`，库前缀 `cy_cloudten_it`，归入 `extra-storage` / `port-extra-storage` 批次。
- `ci/real_storage_it.py` 从 surefire 报告回读实跑条数 == 登记数，skipped/failures/errors（含 flaky/rerun）全为 0、无重复、无他类混入，fail-closed。
- `ci/test_real_storage_it.py::test_cloudten_routes_keep_their_namespace_and_never_claim_hosted_is_vps` 锁住条数、门禁、命名空间、loopback、run 后缀，并证明托管 runner 不能冒充 VPS（`CHENGYIN_TEN_FEATURES_VPS` 被剥离）。本地复跑：44 tests OK。
- `.github/workflows/real-storage-it.yml` 为 `workflow_dispatch`、GitHub-hosted；按其注释，真库批次故意不挂在每个无关 PR 上。把旧的每推送步骤加回 `ci.yml` 属于 CI 计费/触发策略变更，不在 E3 范围内。
- `CloudTenMySqlFixture` 在 master 上已改为 SHA-256 主机拒绝名单（`ForbiddenDbHosts`），托管判定额外绑定 `GITHUB_REPOSITORY`，并有 `CloudTenMySqlFixtureConfigTest` 的正负对照。

把旧的 `ci/test_cloud_ten_ci_contract.py` 原样放进 master 并运行：5 个用例里 1 failure + 1 error（找不到它锁定的 `ci.yml` 步骤）。它在 master 上没有消费者。

## 36 路径逐项判定

| # | 路径 | 判定 | 依据 |
|---|---|---|---|
| 1 | `.github/workflows/ci.yml` | CONFLICTS_WITH_CURRENT | 旧版独有的只有每推送 CloudTen 步骤及其 path filter（见上，master 已用 real-storage-it 等价覆盖）；旧版缺少 master 的禁用主机门禁、Boot Jar 溯源/上传、20 分片小程序单测（`max-parallel: 20`）、小程序汇总 job。覆盖回去会删除现有门禁。 |
| 2 | `chengyinhub-admin/.../service/CloudTenMySqlFixture.java` | CONFLICTS_WITH_CURRENT | 旧版用明文生产主机比对（公开仓不可引入），并要求后缀 `_<runId>_<attempt>`，与 master 运行器生成的 `_gh######aN` 后缀不兼容；master 版为哈希名单 + 仓库绑定。 |
| 3 | `chengyinhub-admin/.../service/CloudTenMySqlFixtureConfigTest.java` | CONFLICTS_WITH_CURRENT | 与 #2 配套，断言旧后缀格式及明文主机；master 版已有托管/VPS/哈希名单正负对照。 |
| 4 | `chengyinhub-xcx/app.json` | CONFLICTS_WITH_CURRENT | C00 保留文件；旧版缺 master 的分包/页面登记。非夹具。 |
| 5 | `chengyinhub-xcx/components/cy/scene-roam-history/index.wxml` | CONFLICTS_WITH_CURRENT | 仅 `wx:key` 一处，master 为 `index`（较新）。非夹具。 |
| 6 | `chengyinhub-xcx/pages/activity/baoming/baoming.js` | CONFLICTS_WITH_CURRENT | master 已补报名/支付埋点 `eventKey` 幂等键；旧版是更早快照。非夹具。 |
| 7 | `chengyinhub-xcx/pages/roam/index.js` | CONFLICTS_WITH_CURRENT | 产品页旧快照（旧独有 77 行 / master 独有 312 行）；非夹具，不在 E3 范围。 |
| 8 | `chengyinhub-xcx/pages/roam/index.wxml` | CONFLICTS_WITH_CURRENT | 同上（旧独有 6 / master 独有 49）。 |
| 9 | `chengyinhub-xcx/pages/roam/index.wxss` | CONFLICTS_WITH_CURRENT | master 已改四格布局并加 BL-01 点位筛选样式。 |
| 10 | `chengyinhub-xcx/pages/team/detail/index.js` | CONFLICTS_WITH_CURRENT | master 已有队伍举报入口；旧版是其子集。 |
| 11 | `chengyinhub-xcx/pages/team/detail/index.wxml` | CONFLICTS_WITH_CURRENT | 同上。 |
| 12 | `chengyinhub-xcx/pages/team/detail/index.wxss` | CONFLICTS_WITH_CURRENT | 同上。 |
| 13 | `chengyinhub-xcx/scripts/danger-action-registry.json` | EQUIVALENT | 旧版无独有行；master 多一条 NPC 对话删除登记。 |
| 14 | `chengyinhub-xcx/scripts/fixtures.json` | CONFLICTS_WITH_CURRENT | C00 保留；旧快照（差 5776 行）。 |
| 15 | `chengyinhub-xcx/scripts/shot-matrix.js` | CONFLICTS_WITH_CURRENT | C00 保留；旧行/统计已被 master 改写。 |
| 16 | `chengyinhub-xcx/scripts/uiaudit/action-ledger.json` | CONFLICTS_WITH_CURRENT | C00 保留生成物，输入未提供；旧 `action-ledger.test.js` 的控件计数与 master 不一致，旧生成物不可能与当前源码对齐，应由 C00 在 master 上重新生成。 |
| 17 | `chengyinhub-xcx/scripts/uiaudit/route-matrix.json` | CONFLICTS_WITH_CURRENT | C00 保留；旧版 136 路由，master 已更新。 |
| 18 | `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.wxml` | CONFLICTS_WITH_CURRENT | 仅一句退款说明文案不同，属文案线（§5.4），非夹具；未移植。 |
| 19 | `chengyinhub-xcx/subpackageRoam/nearby/index.js` | CONFLICTS_WITH_CURRENT | 产品页旧快照（旧独有 14 / master 独有 23）；非夹具。 |
| 20 | `chengyinhub-xcx/subpackageRoam/nearby/index.wxml` | CONFLICTS_WITH_CURRENT | 仅 `wx:key` 两处，master 较新。 |
| 21 | `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js` | CONFLICTS_WITH_CURRENT | C00 保留；冻结基线与哈希不同，不可替换。 |
| 22 | `chengyinhub-xcx/tests/lint/ui-integrity.js` | CONFLICTS_WITH_CURRENT | 旧版缺 master 的共享组件 a11y 门禁（`scanSharedA11y`）与 `u5Silent` 37 的登记；覆盖即削弱门禁。另：PR #449 正在修改此文件。 |
| 23 | `chengyinhub-xcx/tests/unit/action-ledger.test.js` | CONFLICTS_WITH_CURRENT | C00 保留；旧计数与 master 不同。 |
| 24 | `chengyinhub-xcx/tests/unit/get-auth-matrix-contract.test.js` | CONFLICTS_WITH_CURRENT | 旧版 71 个 GET 调用点；master 矩阵已扩充。 |
| 25 | `chengyinhub-xcx/tests/unit/map-team-page-contract.test.js` | CONFLICTS_WITH_CURRENT | 旧 `navigateTo` 桩不回调 `complete`，与 master 跳转中保护不兼容。 |
| 26 | `chengyinhub-xcx/tests/unit/nav-page-title-spacing-contract.test.js` | CONFLICTS_WITH_CURRENT | 旧 74 页，master 83 页。 |
| 27 | `chengyinhub-xcx/tests/unit/play-c-fixes-0916-contract.test.js` | CONFLICTS_WITH_CURRENT | 断言旧的请求体写法；master 已改为固定 payload。 |
| 28 | `chengyinhub-xcx/tests/unit/play-request-recovery.test.js` | CONFLICTS_WITH_CURRENT | 旧版缺 master 的偏好题已读前置建模。 |
| 29 | `chengyinhub-xcx/tests/unit/play-ui-contract.test.js` | CONFLICTS_WITH_CURRENT | 断言旧的 `that.data.doneCount`；master 为 artwork 快照。 |
| 30 | `chengyinhub-xcx/tests/unit/roam-intro-passport.test.js` | CONFLICTS_WITH_CURRENT | 旧 app 桩缺 `sendRequest`、导航桩不回调 `complete`。 |
| 31 | `chengyinhub-xcx/tests/unit/route-matrix-freshness.test.js` | CONFLICTS_WITH_CURRENT | 旧 136 路由计数。 |
| 32 | `chengyinhub-xcx/tests/unit/title-system-owner-contract.test.js` | CONFLICTS_WITH_CURRENT | 旧 136 页计数。 |
| 33 | `chengyinhub-xcx/utils/danger-actions.js` | EQUIVALENT | 旧版无独有行（master 为超集）；C00 保留。 |
| 34 | `ci/migration-dependencies.json` | EQUIVALENT | 旧版无独有依赖边；master 为超集（多出 2026-10-13 之后的迁移登记）。 |
| 35 | `ci/silent-error-allowlist.txt` | CONFLICTS_WITH_CURRENT | 旧版仍登记已下沉到 `subpackageMember/` 的旧组件路径，且缺 master 的后续实名登记；覆盖会让门禁失配。 |
| 36 | `ci/test_cloud_ten_ci_contract.py` | NO_CONSUMER | 它锁定的 `ci.yml` 步骤在 master 上不存在（原样运行 1 failure + 1 error）；等价覆盖见上文 real-storage-it。 |

## 与 #434 / #449 / lane C 的重叠

- 本 PR 只新增本文件，不改任何 CI、测试或小程序文件。
- PR #434 改 `.github/workflows/chain-acceptance.yml`、`ci/chain_*.py`、`ci/test_chain_acceptance.py`、`ci/test_upgrade_startup_network.py`、`ci/upgrade-startup-smoke.sh`：与 36 路径无交集。
- PR #449 改 `chengyinhub-xcx/tests/lint/ui-integrity.js`（即 #22）及两份单测：本 lane 判定 #22 不移植，无编辑冲突。
- lane C 分支 `feature/lane-c-ci-gates-20261022` / `feature/lane-c-449-fix-20261022` 在本次核对时尚未推到远端，无可比对内容；本 PR 不触碰任何 CI 文件，因此不会与之冲突。

## 需报告而未修改的事项

- C00 保留文件（#4、#14–#17、#21、#23、#33）在 E3 下无需改动；`action-ledger.json` 若需刷新，应由 C00 在 master 上重新生成，不得取旧生成物。
- #18 退款说明文案差异属文案线（§5.4），如需恢复旧文案由该线决定。
- 若要让 CloudTen 真库 IT 每次推送都跑（旧树做法），属于 CI 触发/计费策略变更，需单独决策；当前 master 的 dispatch 方式已能回读 9/8/7 条并 fail-closed。
