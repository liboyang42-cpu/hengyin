# Claude 任务卡

公共规则（每张卡都适用）：
- 基于 `claude/mini-ui-features-20261015`（= d551c6b + 本文档）切分支，草稿 PR 合回该分支，**不合 master、不合 Codex 分支**。
- 不改 Codex 独占文件（PR #16 `tasks.json` 的 `owned_files`）：`pages/play/index.js`、`utils/subscribe.js`、`pages/mylike/mylike.js`、`subpackageMember` 订单/参与详情组件、`ApiTopicController`、`TopicCreateDTO`、积分/券/应付/冲正服务与 mapper、排期与 `ApiClubEventOpsController`、`CommunityPostServiceImpl` 及其 policy/mapper、会话/路线运行时服务、AI 网关、剧情编译与校验。需要碰时停下并在 PR 里写明，由协调者排序。
- 只用已有组件（`components/cy/*`）与设计 token；新页面四态用 `cy-state-shell` / `cy-inline-error`；错误不得裸文案（U6）；命中区 ≥ 44px；数字后单位要有 `||` 兜底。
- 不得编造接口字段、状态值或成功态；先读控制器/DTO/Service，字段语义不清时只做只读并在 PR 写清。
- **协调者统筹文件（CY-COORD-20261015-v1）**：`app.json`、`scripts/uiaudit/action-ledger.json`、`tests/lint/ui-integrity-baseline.js`、`tests/unit/action-ledger.test.js` / `get-auth-matrix-contract.test.js` 的冻结计数、共享 fixture、请求封装、workflow/pom 不归 Claude。卡内可以在自己分支上生成这些文件的变更用于本地验证，但在 PR 正文里必须作为「交协调者整合的最小补丁」单列，不得声称已拥有。
- 新文件与冲突文件先在 PR #21 的 `CY-HANDOFF: CLAUDE` 记录里申请；每次接 API 或联调前重读控制分支 `docs/plans/mini-codex-cloud-20261015/BACKEND_CHANGES.md`，只按 CONTRACT_FROZEN 及以上状态接线。本批 CL1–CL4 只消费已有接口（NO_API_CHANGE），`POST /api/points/list` 为一次全量（非分页）。
- 跑：`node --test "tests/unit/**/*.test.js"`、`node tests/lint/ui-integrity.js --warn-as-error`、`node scripts/uiaudit/build-action-ledger.js --check`、`bash scripts/ds-hardcode-gate.sh --ci`（需先 commit）、`node scripts/ui-theme-unit-lint.js --units`、`node scripts/a11y-tap-target-lint.js`、`node scripts/ui-validity-gates.js`。**禁止运行 `ci/xcx-check.sh`**（自证会写夹具并提交）。推送后 `gh workflow run ci.yml --ref <分支>`。
- PR 描述必须有：改了什么、实际验证了什么（命令+计数+CI run）、**本地验收清单**、未完成项。接口 200 不等于业务验收。

| 卡 | 范围 | 独占文件 | 依赖 | 验收 |
|---|---|---|---|---|
| CL1 漫游·跟随官方路线 | G1 + G3 | `pages/roam/**`、`subpackageRoam/**`、`utils/roam-*.js`（不含 Codex 文件）；如需后端仅限漫游新功能专属代码（不加表、不改既有会话运行时服务） | 无 | 从路线详情点「按这条路线开始」进入漫游且地图标出路线站点；不支持时明确降级说明；单测覆盖入参/降级 |
| CL2 举报透明化 | N1 | 新页面（建议 `subpackageMember/report-*`）+ 现有举报成功后的「查看进度」入口（只在 Claude 可改的页面加） | 无 | 我的举报列表/详情/被举报方视图/申诉四态；状态枚举取自后端 `stage` 真值 |
| CL3 俱乐部勋章 + 昵称 | N2 + N4 | `pages/club/**`（勋章管理）、资料编辑页（`pages/gerenziliao` 或 `pages/userinfo`） | 无 | 主理人可创建/列表/授予，非主理人不可见；昵称输入有校验与推荐，失败可恢复 |
| CL4 官方活动主办方 + 数据导出 | N3 + N5 + G4 | `pages/activity/official-*`、`components/cy/npc-chat`（只加导出）、积分规则展示所在的 `subpackageMember/components/cy/profile` 积分区 | 无 | 邀请/转交/排期/暂停/恢复各有二次确认与回读；导出可复制/保存；积分规则从既有接口读取，缺失时诚实空态 |
| CL5 门店认领证明材料 | G2 | `pages/merchant/components/merchant-claim-sheet`、`utils/merchant-claim.js` | **Codex C00 给出合同后才启动** | 按合同上传对象存储标识；不再提交任意 URL |
