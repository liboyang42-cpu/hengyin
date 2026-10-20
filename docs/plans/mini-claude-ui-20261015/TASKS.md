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

## 用户决定（2026-10-17）
- 云端线精简为 L1 开发线 + 监督A。L2、L3、CLF 修复线、监督B 已停用。
- 私信「更多」里原来的「举报」改为「举报这条消息」，与「举报对方」区分（已在 290bc44e 落地）。
- 群投票、漫游约伴、剧透偏好、通用草稿、俱乐部群聊入口、客户页广播：暂不做。

## 集成说明（2026-10-18，协调者）
- #243–#256（BL-188–201）已合入（`cba50ab8`）。按钮台账按合并后实测重建为 4658 控件；单测 8711 通过，失败仍是原有的 8 条冻结计数，ui-integrity / a11y / validity 输出与合并前逐条一致。
- **行号锚点规则（开发线必读）**：`tests/lint/ui-integrity-baseline.js` 的豁免项按「文件:行号」登记，key 集合有冻结 digest，协调者也不改。改动 `scene-route-content`、`cy/profile`、`square/*`、`merchantinfo`、`topic/index` 等已有豁免锚点的文件时，锚点**上方**的净增减行数必须为 0（可把多行写法收成同行数，或加一行注释抵消）。提交前在本地跑 `node tests/lint/ui-integrity.js`，与集成分支的输出逐条对比（去掉行号后 diff），多出 `[BASELINE] … 基线已未命中` 就是锚点漂移了，必须修好再开 PR。#245 曾因上方 +1 行导致锚点漂移，合并时已收成原来的 3 行。
- 第二十七批 BL-202–205 是对本线新建 9 个页面逐页复查后的优化卡，见 BACKLOG 末尾。
- **主包体积（2026-10-18 实测，同 `scripts/preflight-release.js` 4.5 口径）**：本分支主包未压缩 3,195,196 B，已超 3 MiB 棘轮 49,468 B（BL-208 合入后 3,183,633 B，仍超 37,905 B）。Codex PR #259（`codex/prelaunch-pack-budget-230` → master，精确排除 56 个未引用文件）套到本分支后为 3,046,543 B，余 99 KB；本线合入 master 以 #259 为前提（已在 #259 留复核意见）。**#259 已于 2026-10-18 03:13 UTC 合入 master（883064e3）**；本分支（848e6b0c）按 master 的 `project.config.json` 实测主包 3,034,980 B，余 110,748 B，体积门已过。剩余阻塞：master 与本分支分叉较大（合并基点之后 master 改了约 1,000 个后端文件），试合并在 `ci/test_funds_it_gate.py`、`ApiRoamController.java`、`ApiTopicController.java` 及多个资金/核销相关测试上冲突，属后端/资金范围，协调者不自行解冲突，待 Codex 或用户安排先把 master 同步进本分支（或由 Codex 出同步 PR）后再开合 master 的 PR。开发线新增的组件/图片一律放进使用它的分包（如 `subpackageMember/components/`），不要再放根目录 `components/` 或 `images/`；监督审查时把「新文件进了主包」判 CHANGES NEEDED。
- BL-202（#257）、BL-203（#258）已合入。
- BL-204（#260）、BL-205（#261）已合入。BL-203 合入后全量单测多出一条 `all-page-back-stack-contract`「可见返回入口均经过根栈安全审计」失败：`goMine` 有 `wx.navigateBack` 却没有根栈兜底。已在集成分支补 `navigateBack({ fail: () => wx.redirectTo(列表) })`。**开发线与监督：只要改动里出现 `wx.navigateBack`，提交前必须跑全量 `node --test "tests/unit/**/*.test.js"`，与集成分支对比失败名单，不能只跑相关测试**（协调者合并 #258 时也只跑了相关测试，所以漏了）。
- BL-207（#263）、BL-208（#264）已合入。
- BL-209（#265，勋章/官方主办页缺参下拉转圈不收）、BL-210（#266，收藏据点静默回读失败不再把空态换成错误页）已合入；全量单测 8744 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-211（#267，我的举报/我的纠错/收藏据点连点只开一层详情）、BL-212（#268，系统消息举报进度连点只开一层）已合入；全量单测 8757 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-213（#269，纠错成功态「查看我的纠错」连点只开一层列表）已合入；全量单测 8760 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-214（#270，纠错/举报进度「查看我的全部」连点不再连退两页或叠两层列表）已合入；已核对两页原先无 `onShow`，新加的不会覆盖既有生命周期；全量单测 8774 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-215（#271，申诉已受理但回读失败时收起「提交申诉」，关闭回执后回读）、BL-216（#272，补证成功后回读失败不再显示「补充说明」）、BL-217（#273，主办控制台状态两路断网不再当成已读到，有快照保留并轻提示、无快照进网络错误态）已合入；台账因 #273 行号变化重建；全量单测 8782 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-218（#274，举报半屏关掉重开后旧原因回包不再冲掉正在填的表单）已合入；台账因行号变化重建；全量单测 8787 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-219（#275，纠错半屏同一缺陷）已合入；台账重建；全量单测 8792 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-220（#276，安全感受评价提交中不能关闭半屏）已合入；已核对 `submitting` 在成功/业务失败/网络失败/异常状态码各路径都会清掉，不会锁死关闭；全量单测 8794 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-221（#277，昵称推荐旧一轮作废时不再清掉新一轮的「推荐中」）已合入；`pages/gerenziliao/gerenziliao.js` 归 Claude CL3，但 Codex #69 也改此文件，同步 master 时一并解冲突；全量单测 8796 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-222（#278，申诉提交时作废在途刷新，旧回包不再把「提交申诉」放出来）已合入；已核对 `_settle` 在比对序号前先收下拉转圈、申诉只能在 ready 态发起，作废刷新不会留下转圈或 loading；台账重建；全量单测 8798 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- BL-223（#279，纠错进度同一缺陷：补证提交时作废在途刷新）已合入；同样核对过 `_settle` 先收转圈、补证只能在 ready 态发起；台账重建；全量单测 8800 过 / 8 失败（与基线同名），ui-integrity 与合入前一致。
- 合 master 前注意：Codex #69（`feature/prelaunch-code-tail-20261015` → master，209 个文件，2026-10-18 仍在更新）也改小程序 `app.json`、`pages/index`、`pages/member/index`、`pages/activity/list`、若干 `components/cy/scene-*`；它若先进 master，同步 master 进本分支时这些文件要一并解冲突，并按 4.5 口径重测主包体积。

## 合 master 方案(2026-10-19,协调者)

**不把 master 合进本集成分支。** 本分支基线 d551c6ba 是 Codex PR11–15 的组合候选,后端(资金回调收件箱、提现失败回补等)与 master 是两套实现;手工混合有重复退款/回调风险,且合并会让基线提交成为 master 的祖先而内容被丢,#18(Codex 集成 → master)将来会被静默跳过。基线由 Codex 经 #18 进 master。

**改为移植分支 `claude/mini-ui-on-master-20261019`**(基于 master 883064e3,只含本线改动,零后端改动;本线页面调用的 57 个接口 master 均有)。现状(f9ca0b22):
- 单测 11213 过 / 9 失败,9 个全是下面 A–E 的冻结数值;silent-error 门禁通过;route-matrix、action-ledger `--check` 通过。
- ui-integrity:U1–U5、Legacy 全部通过,只剩 6 项棘轮/digest/上限(G、H)。
- 随本线功能带上 4 个基线依赖(接口 master 均有):积分明细页 + 个人主页积分入口、集邮册「收到的」tab、AI 对话「清除记录」、media-preview / last-location。
- 私信用 master 的拉黑/解除/隐藏会话(本线 BL-61 解除实现及其单测退场),保留「举报这条消息 / 举报对方」;漫游以 master 上线版为底重放 BL-01/02/03/23/41/70。

**待用户审批的冻结台账数值**(均为新增页面/调用后的实测值,未改动):
- A `tests/unit/action-ledger.test.js`:tap 2022→2092;控件 4706→4892;inventory digest 419c88…→6673ea…
- B `nav-page-title-spacing-contract`:cy-page-title 页 74→84
- C `title-system-owner-contract`:注册页 136→145(custom 同)
- D `route-matrix-freshness`:路由 136→145
- E `get-auth-matrix-contract`:直接 GET 38→63、req 包装 28→29(21 条新 GET 已逐条归类入矩阵)
- F `scripts/shot-matrix.js` STATS:total 332→341、P1 189→198、actionable P1 182→191 / total 308→317、B 67→68、C 54→56、D 39→44、F 92→93、setData 171→179、identity+setData 116→117
- G `tests/lint/ui-integrity-baseline.js`:ratchet u4Dynamic 58→64、u5Silent 28→37;keyDigest u1Variable→edd72b…、u4Dynamic→03bb12…、u5Silent→8e9594…
- H `tests/lint/ui-integrity.js`:`NON_INCREASING_CEILING.u5Silent` 30→37(本线新增 9 处静默调用,逐项已写理由)

**主包体积阻塞(发版 preflight 硬限)**:移植分支主包未压缩 3,235,112 B,超 3 MiB 棘轮 89,384 B;master 自身只剩约 1.7 KB(#259 之后)。本线主包增量 91,123 B,其中只有约 13 KB 是单一分包独用、可下沉的工具(nickname-check、audit-detail、merchant-subscription-view、safety-rating、merchant-safety-summary、merchant-node-favorite);其余在主包 tab 页漫游(BL-01/02/03/41/70 约 20 KB)、多分包共用组件(举报半屏 12 KB、circle-theme 9 KB)、AI 对话导出/清除、集邮册等。需要决策后才能合 master。

**集成分支照旧作为云端开发基线**:新 PR 照常合入,并同步移植到移植分支(#280 / BL-224 已同步)。待定位置:收藏据点页(node-favorites)未注册、无入口;官方路线入口随 master 漫游改版消失。

## 云端代管(2026-10-19 起,用户出差)

- 移植分支最新 `96da5312`:本线代码已下沉分包(6 个单分包工具、举报半屏改跨包异步组件、AI 对话组件下沉会员分包、圈层主题表两分包各一份并有一致性单测),主包 3,235,112 → 3,169,696 B,仍超 3 MiB 23,968 B(约等于本线漫游功能,需 master 腾空间或分批上)。
- Codex #69 头 `b90b44b2` 自身让 master 主包超 6,559 B(CI 不跑发版 preflight),已在 #69 留体积复核评论(含一处更正)。
- 新增云端例程「hengyin 协调者(云端代管 :25)」`trig_01VLcUSL11R8BuTiAovHj27R`:合入监督判 OK 的 PR(同一套全量单测/ui-integrity 对比)、移植到 `claude/mini-ui-on-master-20261019`(带上面的路径映射)、只读观察 master/#69/#18、在本文件记一行。不碰 master、不改 A–H 冻结数值。
- 仍等用户:A–H 冻结数值审批;主包空间方案;收藏据点页入口、官方路线入口位置。
- 2026-10-19 云端协调:#283(BL-225 活动详情主办控制台连点保护)、#284(BL-226 我发布的活动主办控制台连点保护)合入集成分支(全量单测 8811 过 / 8 失败,与基线同名;ui-integrity 不变;台账无需重建);已移植 `73f9074d`(移植分支 11223 过 / 8 失败同名,silent-error/ui-integrity U1–U5+Legacy/组件路径门禁通过,主包 3,169,696 B 不变)。
- 2026-10-19 Codex 观察:master 移到 `4667f716`(#281,只加真 MySQL/Redis 手动回归 CI 4 个文件),已无冲突合入移植分支 `262cd9b6`,门禁同上;#69、#18 仍未合。#286(BL-227)尚无监督评论,未合。
- 2026-10-19 12:25 云端协调:#286(BL-227 俱乐部勋章跳转中保护)、#289(BL-228 商家工作台核销记录/开店前置项)、#290(BL-229 客户页触达记录)合入集成分支 `9c432800`(全量单测 8823 过 / 8 失败,与基线同名;ui-integrity 去行号后不变;台账重建无变化);已移植 `a612a958`(移植分支 11235 过 / 8 失败同名,silent-error/ui-integrity U1–U5+Legacy/组件路径门禁通过,主包 3,169,696 B 不变——改动均在 pages/club、pages/merchant 分包)。master 仍 `4667f716`,#69、#18 仍未合。
- 2026-10-19 云端协调:无待合 PR(仅 #35 开着,非 bl-*)。master 移到 `e1ae7514`(#288,只改 ci/ 下真库回归 3 个文件),已无冲突合入移植分支 `981aab38`(全量单测 11238 项 0 失败;silent-error、ui-integrity `UI-GATE-0:PASS`、组件路径、action-ledger/route-matrix `--check`、shot-matrix selftest 全过;主包不变,未动小程序文件)。#69、#18 仍未合。

## master 移植 PR(2026-10-19,本地协调者)

- 用户 2026-10-19 批准冻结数值 A–H(只针对移植分支),已在 `b4b1ffa8` 落地:全量单测 0 失败,`UI-GATE-0:PASS`。落地值按重建实测,与原清单有三处不同:B 为 83、E 直接 GET 为 62(都因收藏据点页移出),另补 action-ledger 事件合同 2323→2436(与 A 同一台账,A 通过后才显出)。集成分支仍是原来 8 个冻结失败,不在本次批准范围。
- 收藏据点页(node-favorites)按用户决定暂不进 master,只留在集成分支。移植时跳过 `subpackageRoam/node-favorites/**`、`subpackageRoam/utils/node-favorites.js`、`tests/unit/roam-node-favorites.test.js`、`tests/unit/bl210-node-favorites-quiet-empty.test.js`,以及其他文件里该页的用例和登记。官方路线入口保持 master 现状(无入口),等用户另行决定。
- 草稿 PR #293(移植分支 → master)已开,合并与否由用户决定;云端例程不得把它标为 ready、评论或合并。
- 主包:移植分支 3,169,696 B,超 3 MiB 23,968 B;master 余 1,739 B,#69 另加 8,298 B。用户选择由 Codex 在 master 腾出 ≥40 KB,#293 里有给 C00 的 CY-HANDOFF(候选 `images/d_yhqon.png` 27,191 B → subpackageMember、`utils/mockData.js` 17,398 B → pages/club 分包,合计 44,589 B)。移植不得增加主包字节。
- 另一会话在做 master 移植(9 个云端例程,跟踪 issue #292,集成分支 `integrate/master-port-20261019`),与 #293 共用冻结台账和主包预算;后落地的一方要重建台账并重新实测冻结值(需用户批准)。已在 #292 留言。
- 冻结值此后仍只能由本地协调者在用户批准后修改;云端协调者遇到需要改冻结值的移植,不推送,在本文件记下实测旧→新值。
- 2026-10-19 本地协调:#293 首轮 GitHub CI 抓到两处本线问题(集成分支的 PR 不触发 CI,所以之前没暴露):分享邀请二维码的可点预览框缺无障碍名称(两条分支都已修,`93ccf966` / `4b2c6393`);AI 对话「清除我的对话记录」的不可逆删除登记在移植时丢失(移植分支按下沉后路径补回,`4b2c6393`)。`4b2c6393` 上 GitHub CI 全绿(小程序静态检查、secret scan、fast gates、Funds MySQL IT),主包 3,169,798 B。云端协调者提示已加 CI-LINT 清单,合集成分支 PR 时也要比对。
- 2026-10-19 本地协调:用户决定主包空间由本线在 #293 内处理,Codex 停止此项(#293 里给 C00 的 CY-HANDOFF 已撤回并留言)。移植分支 `63dd6ce2` 把 5 张只被会员分包使用的图片下沉到 `subpackageMember/images/`:`d_yhqon.png`(27,191 B)、`group2–5.png`(14,862 B),引用改为 `/subpackageMember/images/…`;`utils/mockData.js` 不动(多个单测共用的 dev 数据集)。主包 3,169,798 → 3,127,745 B,距 3 MiB 余 17,983 B,`preflight-release.js` 第 4.5 项为 INFO。全量单测 0 失败,CI-LINT 全过。集成分支不跟着挪(主包预算只对 master 有意义),移植时按路径映射处理。
- 2026-10-19 本地协调(更正上一条):Codex 已在 #307(17:27 合入 master)把 `images/d_yhqon.png` 下沉到 `subpackageMember/images/`、`utils/mockData.js` 下沉到 `pages/club/utils/`(共 44,589 B);上一条「Codex 停止此项 / CY-HANDOFF 撤回」写于没看到 #307 时,作废(#293、#292 都已留言更正)。移植分支 `d51f6810` 合入含 #307 的 master:`d_yhqon.png` 两边改动逐字相同,无冲突;本线 3 个俱乐部详情单测(bl-196 / bl-197 / bl-227)的 mockData 桩路径跟随 #307 改为 `../utils/mockData.js`。本线只额外下沉 `group2–5.png`(14,862 B)。主包 3,110,347 B,距 3 MiB 余 35,381 B。`63dd6ce2` 的 CI 红就是这 3 个桩:GitHub 按 PR 与最新 master 的合并结果跑测试。**移植路径映射新增**:`utils/mockData.js`→`pages/club/utils/mockData.js`(俱乐部详情页 require 为 `../utils/mockData.js`)。
- 2026-10-19 本地协调(云端加量):监督 A 17:50 找到的漫游「官方活动」连点叠页(本线 BL-41 入口)已直接修好,两条分支同改:移植分支 `d5d656da`、集成分支 `e2d75110`(原行内改,行数不变;不要再移植)。PR20 已于 2026-10-17 合入 master,`pages/roam/index.js` 的 PR20 原写者持有不再阻塞本线修自己的入口。用 CI 同款扫描本线新增/修改的 88 个 JS,补第四十三批 BL-230–241(127 处跳转中保护,一卡一文件,统一改法写在批首);`pages/publish/fabu/index.js:6821` 记入「交 Codex」。新增并行开发线 **L2**(`trig_015866FmmAmYCEMpp9YhLBfG`,每小时 :35,从最高号往下领;L1 从最低号往上领,领取前再拉一次分支防撞号),L1 已手动提前开跑。协调者主包规则改为:允许修复本身带来的少量主包字节,主包须保持距 3 MiB ≥ 25,000 B(当前余 35,128 B)。
- 2026-10-19 本地协调:master 移到 `51155bfa`(#319 #320 #303;#303 新增生产主机地址门禁 `ci/forbidden_hosts_gate.py`,按 SHA-256 比对 IPv4 字面量)。已无冲突合入移植分支 `a09ae905`:门禁自证与扫描均通过,全量单测 11234 过 / 0 失败,ui-integrity、台账、路由、silent、shot-matrix、danger、a11y 全过,主包 3,110,600 B 不变。`d5d656da` 的 GitHub CI 与 Funds MySQL IT 均已通过。**文档与单测里不要写任何 IP 字面量。**
- 2026-10-19 18:45 云端协调:无待合 PR(仅 #35 开着,非 bl-*;BL-230–241 尚无 PR)。master 移到 `51155bfa`(#303 #319 #320,只改后端/ci/docs,不碰小程序);本地协调者已无冲突合入移植分支 `a09ae905`,云端在同一棵树上复核:全量单测 11239 项 0 失败,silent-error、ui-integrity `UI-GATE-0:PASS`、action-ledger/route-matrix `--check`、shot-matrix selftest、CI-LINT 全过;主包 3,110,600 B 不变。#293 CI:secret scan、fast gates、Funds MySQL IT 绿,小程序静态检查运行中。#69、#18 仍未合。
- 2026-10-19 本地协调(断档恢复):19:05–21:50 UTC 账号五小时用量上限,期间所有云端运行开机即被拒(`rate_limit: rejected (five_hour)`),线路空转约 3 小时。断档前 L1 一次领了 BL-231–235、L2 领了 BL-240,都只有空的领取提交、没有 PR;已删除这 6 个空领取分支,卡片重新可领。BL-230(#343)、BL-241(#342)有实际修复,等监督审。L1/L2 提示已加「过期领取」规则(只有领取提交、无 PR、超过 45 分钟 → 原分支续做)和「一次只领一张」;21:55 已手动触发 L1、L2。master `9d62c00d`(#312 新增 V4 验收单测等)已合入移植分支 `e11eb8e4`:三份 V4 俱乐部详情单测的严格 require 白名单补了本线 `media-preview.js` 桩;全量 11303 项 0 失败(8 todo),CI 全绿,主包 3,112,276 B(余 33,452 B,#328 的 `utils/update-manager.js` 进了主包)。
- 2026-10-19 23:15 云端协调:#342(BL-241 零散单点跳转,L2)合入集成分支 `adc8afe2`(全量 8842 过 / 8 失败同名;ui-integrity 去行号后不变;CI-LINT 失败集合不变——集成分支原有 ui-validity-gates U5、coverage-gate 6 页未覆盖、u1_mapping 三项红,非本 PR 引入;台账重建无变化),#343(BL-230 个人主页组件,L1)合入 `db5364a9`(8867 过 / 8 失败同名,其余同上)。已移植 `fd9b9527`(#342;invite-detail / square list / templatedetail 三处按 master 版结构手工落保护)、`44cee961`(#343;积分明细保持移植分支分行写法):全量 11346 项 0 失败,silent-error、`UI-GATE-0:PASS`、台账/路由 `--check`、shot-matrix selftest、CI-LINT 全过;主包 3,112,432 B(+156 B,余 33,296 B)。#293 CI 在 `44cee961` 上运行中。#347 #349 #351–#354 尚无监督评论,未合。master 仍 `9d62c00d`。
- 2026-10-19 23:25 云端协调:#347(BL-240 附近页/路线内容组件,L2)、#349(BL-231 俱乐部详情 23 处,L1)、#351(BL-239 聊天页/会话列表,L2)、#352(BL-232 漫游页其余入口,L1)批量合入集成分支 `7b381724`(全量 8929 项 8916 过 / 8 失败,与基线同名;ui-integrity 去行号后不变;CI-LINT 失败集合不变;台账重建无变化)。已移植 `3ba2585f`:nearby / im chat 的 onShow 按 master 结构补清锁;#352 只落 master 漫游有的入口(intro 卡、官方路线入口、goStart 字面量跳转 master 没有,对应用例不移植),移植版单测的请求桩改挂模块加载时的 app(master 的 `req()` 走 community-post-api);#349 新单测 mockData 桩按映射改 `../utils/mockData.js`,master V4 `v4-club-project-cards` 的 navigateTo 桩改为回调 complete(断言不变)。全量 11390 项 0 失败(8 todo),silent-error、`UI-GATE-0:PASS`、台账/路由 `--check`、shot-matrix selftest、CI-LINT 全过;主包 3,113,621 B(+1,189 B,主要是 #352 roam,余 32,107 B)。#293 CI 在 `44cee961` 上全绿,`3ba2585f` 运行中。#353–#358 尚无监督评论,未合。master 仍 `9d62c00d`,#69、#18 未合。
- 2026-10-20 00:55 云端协调:#353(BL-233 商家装修页,L1)、#354(BL-234 商家工作台,L1)、#355(BL-238 会员分包活动详情/券/模板,L2)、#356(BL-235 主题章节详情,L1)、#357(BL-236 主题页/商家信息页,L1)、#358(BL-237 我的项目页,L2)、#359(BL-243 广场列表/详情动态卡,L2)、#360(BL-242 个人主页头像编辑资料/发消息,L2)批量合入集成分支 `57f0a125`(全量 9022 项 9009 过 / 8 失败,与基线同名;ui-integrity 去行号后不变;CI-LINT 失败集合不变;台账重建无变化)。已移植 `4d35b59a`…`f1886fd6`(每 PR 一个 port 提交):活动详情组件清锁并入 master 已有的 `pageLifetimes.show`;券/商家信息页 onShow 按 master 结构补清锁;我的模板跳转保留 master 的 `operationScope`;广场详情 onShow 首行清锁、保留 master 刷新逻辑;个人主页发消息改走 master 的 `community.request`(`/api/im/start` 直通 `app.sendRequest`,`successStatusAbnormal` 照常生效)。移植版单测两处适配:BL-236 商家信息页 onShow 正则放宽跨度,BL-243 列表 onShow 用例桩掉 master 的 `_syncCommunityAccess`(断言不变)。全量 11483 项 0 失败(8 todo),silent-error、`UI-GATE-0:PASS`、台账/路由 `--check`、shot-matrix selftest、CI-LINT 全过;主包 3,113,621 B 不变(余 32,107 B)。#293 CI 在 `f1886fd6` 上全绿。#361(BL-244)尚无监督评论,未合。master 仍 `9d62c00d`,#69、#18 未合。
- 2026-10-20 03:40 云端协调:#361(BL-244 俱乐部详情动态卡/场次工具/导演台,L2)、#362(BL-245 漫游页半屏入口,L2)、#363(BL-246 附近页地图点,L2)、#364(BL-247 会话列表/客户页空态按钮,L2)、#365(BL-250 商家工作台据点半屏「投一张」,L2)批量合入集成分支 `7c8aa42c`(全量 9052 项 9039 过 / 8 失败,与基线同名;ui-integrity 去行号后不变;CI-LINT 失败集合不变;台账重建无变化)。已移植 `4a59230f`…`e65ec72b`(每 PR 一个 port 提交,`git merge-file` 全部无冲突)。移植版单测两处适配(断言不变):BL-244 mockData 桩按映射改 `../utils/mockData.js`;BL-247 应用桩补 `canUseCommunity: () => true`(master 会话列表 `onEmptyCta` 先过社区准入)。全量 11513 项 0 失败(8 todo),silent-error、`UI-GATE-0:PASS`、台账/路由 `--check`、shot-matrix selftest、CI-LINT 全过;主包 3,114,058 B(+437 B,#362 roam 本身,余 31,670 B)。#293 CI 在 `e65ec72b` 上全绿(小程序静态检查、fast gates、secret scan、Funds MySQL IT)。#366–#368(BL-249/248/251)尚无监督评论,未合。master 仍 `9d62c00d`,#69、#18 未合。
- 2026-10-20 05:30 云端协调:#366(BL-249 商家信息页选点半屏,L2)、#367(BL-248 商家装修页/活动详情门卡,L2)、#368(BL-251 俱乐部主题详情去结算/团码,L2)、#369(BL-252 我的项目空态与操作半屏,L1)、#370(BL-253 官方活动详情绑定主题任务,L1)、#371(BL-254 商家工作台管理优惠券/项目卡,L1)批量合入集成分支 `b1fbc5f7`(全量 9077 项 9064 过 / 8 失败,与基线同名;ui-integrity 去行号后不变;CI-LINT 失败集合不变;台账重建无变化)。已移植 `665435b0`…`1553df89`(每 PR 一个 port 提交,`git merge-file` 全部无冲突)+ `65cab954`(移植版 BL-249 单测 onShow 正则按 BL-236 同款放宽跨度,断言不变)。全量 11538 项 0 失败(8 todo),silent-error、`UI-GATE-0:PASS`、台账/路由 `--check`、shot-matrix selftest、CI-LINT 全过;主包 3,114,058 B 不变(余 31,670 B)。#293 CI 在 `e65ec72b` 上全绿,`65cab954` 上也全绿(小程序静态检查、fast gates、secret scan、Funds MySQL IT)。master 仍 `9d62c00d`,#69、#18 未合。

## 云端线收尾(2026-10-20,本地协调者)

- 用户 2026-10-20 05:37 UTC 要求停止云端:L1、L2、监督 A、协调者四个例程全部停用(enabled=false),无在跑会话,协调锁已清除。
- 第四十三批起的连点保护卡 BL-230–254 共 25 张全部合入集成分支并移植;移植分支 `65cab954`:全量单测 11538 项 11528 过 / 0 失败 / 8 todo,CI-LINT 全过,ui-integrity `UI-GATE-0:PASS`,主包 3,114,058 B(距 3 MiB 余 31,670 B);#293 GitHub CI 与 Funds MySQL IT 全绿,可合并(CLEAN)。
- 未完成:BL-255(#372,商家信息页「加入活动」连点保护)已开 PR 但未审未合;`pages/publish/fabu/index.js:6821` 归 Codex(见「交 Codex」);#35(CL6,待接口字段)。集成分支仍有 8 条冻结计数失败与 ui-validity U5 / coverage-gate 两项(不进 master)。
- 待用户决定:#293 合并时机(与另一会话 W2 移植有 21 个文件重叠,后合的一方要解冲突并重测冻结值);收藏据点页入口;官方路线入口。
