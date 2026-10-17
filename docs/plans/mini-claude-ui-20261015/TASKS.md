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

## 用户决定（2026-10-15，覆盖上方卡片中与之冲突的描述）
| 卡 | 决定 |
|---|---|
| 全部 | 弹层一律用现有 `cy-sheet`（标题 + 关闭按钮），不自造弹层；商家/浅色页灰底（`--cy-color-bg-page`）、卡片与输入框**无描边**。 |
| CL1 | 官方路线「按这条路线开始」**暂缓**，撤掉 G1 全部改动，只保留 G3（入口文案「官方路线」+ 拆分一行两语句）。漫游=自由探索不限顺序；城市定向（`pages/play` mode=1）本来就按顺序，不另做。 |
| CL2 | 阶段名确认：已提交/已受理/调查中/已处理/已关闭/申诉中。申诉**只支持文字**（后端 `normalizeEvidence` 本批也拒绝附件）。**不做被举报方入口与视图。** 「我的举报」**不单独放入口**，从系统消息进入：后端举报进度通知 `notificationType=SYSTEM_GOVERNANCE`、payload 含 `reportId`/`publicStage`（`CommunityTrustServiceImpl.enqueueReportProgress`），在现有系统消息/通知渲染处让这类消息可点进举报详情（含申诉）。如需改 Codex 或协调者文件，只交补丁。 |
| CL3 | 勋章图标**不改**，沿用现有勋章图标/样式渲染；昵称被占用时列出推荐供点选，不自动填。 |
| CL4 | 对话导出 = 复制文本。暂停表单用「预设选项 + 补充文字」（四项后端字段仍为文本）。「提交排期」改用户能懂的名称：后端语义是草稿通过发布检查后进入 SCHEDULED（`OfficialEventV2ServiceImpl.schedule`），建议文案「发布活动」+ 副文案「通过发布检查后对报名可见」。**邀请候任主办/转交暂不做。** 重点改为**联合主办**：后端 `official_event_party.role` 已有 `CO_ORGANIZER`（另有 ORGANIZER/FULFILLMENT_*/SPONSOR/PROMOTION_PARTNER），协办方在 `official-inbox` 接受；本卡先在活动详情展示主办方+全部协办方（读接口已有字段才展示，不编造）。**新增协办方**目前只能经草稿 `/v2/drafts` 的 parties 全量覆盖，已排期活动无接口——若要在小程序里加协办方，需要 Claude 专属新接口，先在 PR #21 以 `CY-HANDOFF: CLAUDE` 提合同申请，获批前不写后端。 |

## 用户决定（2026-10-15 晚，最高优先级）
- **不改现有主标题与现有 UI。** 现有界面就是基准：不得把现有页面改成别的标题样式（保留 `cy-page-title` 大标题等现状）、不得替换现有弹层/描边/四态/token 的既有视觉。已关闭 BL-08/09/10/11/13/14/15/24/29 等改样式 PR。
- **新功能、新页面按现有 UI 做**：照搬同类现有页面的结构、组件和样式（例如二级页沿用现有 `cy-nav-bar` + `cy-page-title` 的做法），不自创样式。
- 先前「弹层带关闭、灰底无描边」是说现有 UI 本来就是这样，不是要改现有页面。
## 监督规则修正（2026-10-16，覆盖以上与监督/开发线提示中的冲突内容）
清单已出现空转（文档汇总、拆分单测、PR 修 PR 的依赖链），一律停止：
1. **不再新增**纯文档 / 汇总 / 清单 / 复扫类任务（ACCEPTANCE*、ZERO-CALL*、MERGE-ORDER、REVIEW-OPEN、COORDINATOR-PATCHES* 等），也不再新增只含单测的任务。单测随功能 PR 一起写。
2. 对未合并 PR 的修正**直接提交到原 PR 分支**（20 分钟规则不变），不得另开「依赖 #NN」的新 PR。
3. 监督补充任务只允许：用户可见的新功能或真实缺陷修复，且有现成后端接口、不改现有 UI。找不到就不补，开发线转去处理 CHANGES NEEDED。
4. 已有的上述空转 PR 不要再追加提交，等待用户决定是否关闭。

## 集成说明（2026-10-16）
- 85 个监督通过的 PR 已合并进本分支；新任务一律从本分支最新提交切出。
- 本分支上已知的冻结计数 / U5 白名单 / ui-integrity 行号基线失败属协调者统筹文件，已在 #21 交接，开发线不要追改；只保证自己改动相关的测试通过。

## 补卡前置检查（2026-10-17，主监督）
BL-185/186 是主监督写的卡，BL-187 是监督线写的卡，三张都在领取后才发现做不了，各开了一个空 PR。以后补卡前必须先完成下面三步：
1. **文件归属**：确认要改的文件不在 Codex `tasks.json` 的 `files`/`owned_files` 里，也不在本文件公共规则列出的 Codex 独占文件里（如 `pages/play/index.js`、`pages/publish/fabu/index.js`）。只能改 Codex 文件的需求，写进 BACKLOG 末尾的「交 Codex」清单，不要建卡。
2. **既有决定**：`git grep` 一下 `tests/unit` 里有没有断言把这个功能列为退役或禁止（如 D-001/RUN-001 已退役客户页广播）。有就不建卡。
3. **接口真值**：用 `git grep` 确认小程序里没有调用，并读后端 Controller，确认参数和归属校验。
开发线如果遇到阻塞：只在 BACKLOG 对应条目后面加一行说明，直接推到集成分支（docs-only，不开 PR），然后继续下一张卡。
