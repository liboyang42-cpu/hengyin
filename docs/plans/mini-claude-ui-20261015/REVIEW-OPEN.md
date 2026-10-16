# 监督未决意见汇总（BL-129，2026-10-16 00:30Z 快照）

> 只汇总，不改代码。范围：所有合入 `claude/mini-ui-features-20261015` 的 open PR（`claude/bl-*` 与 `claude/cl*`，共 124 个，#22–#171）。
> 依据：每个 PR 的 issue 评论里**最新一条** `<!-- supervisor sha=... -->` 的结论、该评论之后是否有 `<!-- line-fix -->`、当前 head 是否等于被审 sha。file:line 照抄监督评论原文，未二次核对代码。
> 监督线的结论词是 `OK` / `CHANGES NEEDED` / `CLOSE`（没有 `APPROVE`）。PR 状态以 GitHub 为准；本文件生成后的新评论不在其中。
> 相关文档：`MERGE-ORDER.md`（BL-127，#171）、`COORDINATOR-PATCHES.md`（BL-126，#170）、`DECISIONS-NEEDED.md`（BL-128，#172）。

## 1. 最新结论为 CHANGES NEEDED（7 个）

| PR | 被审 sha → 当前 head | 未决项（file:line — 内容） | 状态 |
|---|---|---|---|
| #22 CL1 G3 | 9ff0edc3 = head | Must 1：PR 标题/正文仍描述 G1（4483 controls、roam-follow-route 单测、验收 1–11），需改成「仅 G3」并附实测 ledger 4480；Must 2：`scripts/uiaudit/action-ledger.json`、`tests/lint/ui-integrity-baseline.js` 未单列为「交协调者整合的最小补丁」，`pages/roam/index.{js,wxml}` 为 CONFLICT_HELD，需写成 G3 补丁请求；nit：入口「官方路线」与 `subpackageRoam/official-routes` 列表页「官方线路」（漫游红线 R2）不一致，需 C00 决定 | 正文 19:34 已更新、标题已是「仅 G3」，看起来两条 Must 只需改正文且已改；**但没有 line-fix 评论，也没有复审** → 待监督复审 |
| #119 BL-76 | b2726587 = head | Must（只改正文）：`chengyinhub-xcx/app.json:141`、`scripts/uiaudit/route-matrix.json`、`scripts/uiaudit/action-ledger.json`、`tests/unit/action-ledger.test.js:555,765,1379`、`route-matrix-freshness.test.js:35-36`、`title-system-owner-contract.test.js:76,126,254-255`、`nav-page-title-spacing-contract.test.js:478,488` 已提交在分支，正文仍写「未提交」；需列为已提交的 C00 补丁并注明与 CL3 #23、#144 的计数冲突 | line-fix 23:57Z 声称已改正文（列 7 个文件、注明与 #23/#27/#25/#144 冲突） → 待复审 |
| #120 BL-77 | 3795f1e3 = head | Must（只改正文）：`tests/lint/ui-integrity-baseline.js`（:261-262、:964、:1376-1377）+ `scripts/uiaudit/action-ledger.json` 需改称「交协调者整合的最小补丁」；写明台账只有 coupon.wxml 4 个控件行号 +2（控件数 4480）、u5Silent +1、ratchet.u5Silent 28→29、冻结上限 29 未动（删掉「冻结上限 28→29」）；「改动文件」补这两个文件；「未验证」旧数 161 换新数 | line-fix 23:22Z 声称已全部改正文 → 待复审 |
| #128 BL-85 | de554bf2 = head | Must：`pages/merchant/index/index.js:747` 新增 `GET /api/merchant/ops/prerequisites` 未登记，正文协调者补丁需加 MATRIX 行（required / merchant-owner / self-merchant）和计数变化；nit：无 path 的行仍绑定空 bindtap | line-fix 22:35Z：Must 已在正文补（MATRIX 行 + 33→34）；nit 有意不改并写理由 → 待复审 |
| #138 BL-95 | c166569a → **7ce671f4** | Must：`profile/index.js:958` 新增行使 `tests/lint/ui-integrity-baseline.js:1205` 的 `profile/index.js:1589` 键错位，`ui-integrity --warn-as-error` 失败（[U4] `profile/index.js:1590`、[BASELINE] `:1589`），需把 `'exploreStat.km'` 并到 `'exploreStat.shops'` 同一行；nit：正文「X km」有空格，模板为 `Xkm` | line-fix 23:56Z + 新提交 7ce671f4，声称两项已改、ui-integrity PASS → 待复审（更新：00:43Z 监督复审 7ce671f4 为 **OK**） |
| #139 BL-96 | 690d9f7d = head | Must：`components/cy/scene-route-content` index.js:86,188-190,262-284 / index.wxml:39 / `shezhi.wxml:92` / `shezhi.js:41`——「唯一宿主是 mylike.js」不成立，需评估在 settings-likes 场景 src-favorites 补收藏活动，或写清不能的原因；nit：详情跳转需写具体路由 | line-fix 22:35Z：只改正文，路由写为 `/pages/activity/detail/index?id=`，评估后维持 BLOCKED 并写理由 → 待复审 |
| #151 BL-107 | 0a572218 → **16360cbc** | `ACCEPTANCE-BATCH5-8.md:104`：#138 已非 BLOCKED，需移到第一节、照抄 4 条验收清单并注明碎片/彩蛋仍 BLOCKED；`:6` 依赖提示补 #138 的 action-ledger 重建；`:3` 更新快照说明 | line-fix 23:58Z + 新提交 16360cbc，声称三项已改 → 待复审（更新：00:43Z 监督复审 16360cbc 为 **OK**，仅剩正文过时的 nit） |

**结论**：7 个 CHANGES NEEDED 中 6 个已有 line-fix（#119/#120/#128/#138/#139/#151），1 个（#22）只改了正文没发 line-fix。全部等监督按当前 head 复审，没有本线仍需动手的项。

## 2. 最新结论为 CLOSE（3 个，建议关闭，本卡不关）

| PR | 理由（监督原文摘要） |
|---|---|
| #53 BL-16 | 已按用户决定作废；CL4 合并后也不要重开 |
| #66 BL-30 | 已按用户决定作废，空提交 |
| #121 BL-78 | 客户名册已存在；建议在 `BACKLOG.md:111` 把 BL-78 标为「已存在」 |

另：#75 BL-34 最新结论 OK 但注明「BLOCKED 记录，不要合并」——分支内容与 #73 相同。

## 3. 结论 OK 但仍有未处理的 nit（head 未变、无后续 line-fix）

只列监督要求改动或明确「仍未改」的项；纯提醒（真机看观感、可接受无需改）放在 §4。

| PR | 未决 nit（file:line — 内容） | 归属 |
|---|---|---|
| #23 CL3 | `pages/club/detail/index.wxml:299-305` `.ov-badge-entry` 是新写行样式（`index.wxss:1026-1039`），应改用页内现有 cell/行样式——作者 19:02 同意随下次改 club/detail 推送一起改，**仍未改** | 本线 |
| #25 CL2 | report-detail 的 `goMine` 进入 report-mine，该页不在用户决定里，需用户确认是否保留；未读点不消失（未接 `POST /notifications/{id}/read`，BL-31）；`limit: 50` 端上筛选可能挤掉举报进度；新文件在 OWNERSHIP 仍是 REQUESTED | 用户决定 / 协调者 |
| #55 BL-17 | `perks/index.js:83` / `:92` `discardVisible` 重复键，建议删新加的那个 | 本线 |
| #56 BL-19 | 「放弃编辑？」弹窗开着时再按返回只重挂守卫不关弹窗；`navigateBack` 失败时 `guardLeaving` 恒为 true | 本线（影响小） |
| #59 BL-18 | 范围说明有误：`pages/publish/fabu` 不在 owned_files，「主题编辑页」一半没做且理由不成立，建议另开项或在 BACKLOG 写明只覆盖资料编辑 | 本线 / 协调者 |
| #62 BL-26 | 正文写「无协调者补丁」需更正：wxml 改动导致 inventory 漂移，`action-ledger.test.js`「release 前重建 inventory」会失败 | 本线（只改正文） |
| #77 BL-36 | 私信菜单「举报」与「举报对方」并列（前轮 `subpackageB/pages/im/chat/index.js:14,116`），合并预演在 #118 | 用户在 #118 取舍 |
| #100 BL-57 | `batch2-api-contract.test.js` BL-45 断言 methodBody 截 1200 字符，易成弱断言，应截到下一个 `@PostMapping` | 本线 |
| #101 BL-59 | `ACCEPTANCE.md:5`「BL-40 → #81 / #82（内容一致）」不准确，应保留 #82；未收录 #102/#103/#104 | 本线（文档） |
| #103 BL-60 | `READ_CONTRACT_REQUESTS.md`「R9-24」未写出处 | 本线（文档） |
| #104 BL-61 | `subpackageB/pages/im/chat/index.js:667` `dc.done()`「已解除拉黑」在回读前显示，可挪到回读之后；取对方 senderId 逻辑重复 3 次 | 本线 |
| #108 BL-65 | 正文写成 `ApiOfficial*Controller`，应为 `ApiOfficialEventController.java:116-118` | 本线（只改正文） |
| #114 BL-71 | 正文说要补 get-auth 矩阵，但 player-code 是 POST，只需重建 action-ledger | 本线（只改正文） |
| #123 BL-80 | GET 矩阵补丁除计数外还需 MATRIX 条目（如 `['pages/activity/official-mine/index.js', "'/api/official/broadcast/'", 'required', 'publisher', 'self-broadcast-sender']`） | 本线（只改正文） |
| #126 BL-83 | `ACCEPTANCE-BATCH3-4.md:108,:110,:111` 合入前重新同步 #120 措辞；`:6` 未提 #120 协调者补丁（重建台账 + u5Silent 28→29，占满 29/29） | 本线（文档） |
| #130 BL-87 | 协调者补丁 scope `'self-submitter'` 不在 `get-auth-matrix-contract.test.js:114` 的 VALID_SCOPES，照原样会在 `:244` 失败；需加枚举或改用 `'self-account'` | 本线（补丁正文） |
| #132 BL-89 | 同上，scope `'public-taxonomy'` 不在 VALID_SCOPES；需加枚举或改用 `'global-authenticated-read'` | 本线（补丁正文） |
| #134 BL-91 | 测试未断言 `AuditTaskDetailVO.java:11-12` 外层 task/timeline 字段（#130 用到）；未断言 `ApiMerchantController.java:351-358` `@PostMapping("/public-home")` 与 `body.get("memberId")`（#132 用到） | 本线 |
| #141 BL-98 | （可选）`index.wxml:549` 注释把 `registration_status=2` 写成「已支付」需核实；趋势请求无序号保护 | 本线（可选） |
| #150 BL-106 | （可选）`:88` 未断言 total = fulfil + violationCount（`CoopInviteServiceImpl.java:1136`）；`:91-92` 断言 Javadoc 文字易碎；`:67` 对全部 String 常量 deepEqual 过严 | 本线（可选） |
| #152 BL-108 | `decor/index.js:486-497` `loadActiveSubs` 成功回调未检查 `that._destroyed`（loadPerks 有）——监督注明「不阻塞，仍未改」 | 本线 |
| #158 BL-114 | （可选）`batch9-api-contract.test.js:24` `slice(at, at + 700)` 定长截方法体，建议截到下一个 `@PostMapping` | 本线（可选） |
| #159 BL-115 | `ACCEPTANCE-BATCH9.md:32` #155 行末「核销列表无分页属另一问题」属于 #157，需删除（**上轮已提，仍未改**）；`:4` 引用 `ACCEPTANCE-BATCH5-8.md` 补 PR 号 #151 | 本线（文档） |
| #164 BL-120 | commit 改了 `scripts/ui-silent-error-allowlist.js:95`、`ci/silent-error-allowlist.txt:180`，正文「改动文件」未列 | 本线（只改正文） |
| #167 BL-123 | `ACCEPTANCE-BATCH10.md:30` #160 只有表格行，缺验收行「确认 pages/play/circle 结束时显示记录卡」；PR 标题写「第九至十批」，文件只覆盖第十批 | 本线（文档） |

## 4. 仅提醒类意见（无需本线改动，合并/验收时留意）

| PR | 内容 |
|---|---|
| #35 CL6 | 组件零使用，待 N02 只读 parties 合同与 #27 CL6-SLOT（`official-detail/index.wxml:159`）；接线时用 bare，避免新白卡 `.oc-sec__card` |
| #37 BL-01 | silent-error-gate、Migration safety 两项 CI 红是基线问题，需协调者把 master 合入基线 |
| #42 BL-05 | 深色集邮册上的 cy-sheet 配色真机看一眼 |
| #43 BL-06 | `index.wxss:96` 给现有 `.oi-scope` 加了 flex-wrap（对现有 UI 的微调，验收留意）；toast.success 与「还没同步」前后矛盾，真机看观感；ROLE_NOTE 与 #35 `utils/official-parties.js` 轻度重复 |
| #57/#68/#88/#109/#122/#147 等 | action-ledger 行号/计数漂移，需协调者重建（见 `COORDINATOR-PATCHES.md`） |
| #58 BL-22 | `pages/roam/index.wxml` 与 BL-01~03 有 5 处属性文本冲突，合并时按行处理 |
| #60 BL-23 | `cy_last_location` 存储键不分账号，建议另立项 |
| #61 BL-25 | A/B 方案都不执行；PR 漏列 `enroll/index.wxml:13` |
| #73 BL-32 | 冻结计数靠协调者补丁；401 重登失败也走「结果未知」，文案略不准（可接受） |
| #83 BL-41 | `utils/roam-official-active.js` 调 `/api/official/active-for-roam` 未带 city，后续考虑传当前城市 |
| #82/#41 | 读合同 displayName 来源、clubId/clubName 并入 BL-60 |
| #84 BL-42 | app.json 注册与入口位置待用户决定，合入前页面不可达 |
| #98 BL-54 | #73 合并后补 report-sheet 处理器单测 |
| #107 BL-64 | 解锁条件需小程序先展示互动/提及/关注三类 v1 通知 |
| #110 BL-67 | 是否开放写入口待用户决定（后台无审核页），保持草稿 |
| #145/#149/#168/#169 | 后端问题已转 Codex/协调者（xpBudget owner 校验、`ApiTopicController.java:2086` userType、`MerchantDetailAggregationServiceImpl.java:35` delFlag、groupByCategory 未剥离答案/未过滤 status） |
| #146/#162/#163/#165 | 待产品决定（见 `DECISIONS-NEEDED.md`） |
| #166 BL-122 | `batch10-api-contract.test.js:49/:60` 正则断言脆弱（监督判可接受） |

## 5. 尚无监督结论

> 这一节是 00:30Z 快照，早于 #172–#175 开出；这几个 PR 的监督状态以 GitHub 为准。

| PR | 说明 |
|---|---|
| #170 BL-126 | 无任何评论 |
| #171 BL-127 | 无任何评论 |

## 复跑方法

对每个 open PR 取 issue 评论，按时间取最后一条含 `<!-- supervisor sha=` 的评论，解析 `**Supervisor verdict: ...**`；再看其后是否有含 `<!-- line-fix -->` 的评论，并比较 sha 与 PR 当前 head。例如：

```bash
gh api "repos/liboyang42-cpu/hengyin/issues/<N>/comments?per_page=100" \
  --jq '[.[] | select(.body | test("<!-- (supervisor sha|line-fix)"))] | .[] | {created_at, head: (.body | split("\n")[0]), verdict: (.body | capture("verdict: (?<v>[^*]+)").v? // "")}'
```
