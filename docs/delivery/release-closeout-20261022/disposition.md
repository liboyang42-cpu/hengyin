# 发布收尾 · open PR 处置清单（Lane G，2026-10-22）

> Doc 1 §6.1 任务 T-3 / T-4 / T-5。**本文件只读分析，不构成任何合并、关闭或标签操作。**本 lane 没有对其他 PR 做任何写操作（没有评论、关闭、加标签、推送）。所有处置都只是建议，由 owner 决定。
>
> 机器可读版本见同目录的 `disposition.json`。T-4 要求的大 PR 完整文件清单放在其中的 `file_lists` 字段。

- **master**：`063a6c3cd7c1f131618148c385174037f1d2786e`（2026-10-22 01:34 +0800，“Merge pull request #408”）。与参考 SHA `063a6c3c` 一致，本 lane 运行期间 master 没有前移。
- **open PR**：46 个，与参考时点一致，没有新增。
- **不在范围**：Doc 1 §6.2 的 64 个私有语义组。它们需要 owner 后续提供私有输入。

## 方法与证据口径

1. 用 blobless 克隆（`--filter=blob:none`）拉取 master 和全部 `refs/pull/*/head`。全程没有使用 `git grep <rev>`。
2. **PR 文件清单**取 `git diff --name-status <merge-base(base_sha, head)> <head>`。这与 GitHub PR files API 用的三点 diff 是同一个集合。
   - 本会话的 `gh` CLI 没有认证，所以 `gh api --paginate .../pulls/<n>/files` 不能直接跑，用上面的本地等价方式代替。
   - 大 PR 的文件数已与 GitHub `changed_files` 逐个核对一致：#334=194、#335=340、#336=160、#338=143、#35=7、#398=754。不受 `gh pr view` 只显示前 100 个文件的限制。
3. **“与 master 一致”**用 `git ls-tree` 比对 blob 哈希，也就是字节级一致，不需要下载 blob。
4. **“master 更新”**的判定：PR 头的 blob 曾在 master 的历史中出现过，说明 master 后来又改了这个文件。
5. **可合并性**：用 `git merge-tree --write-tree origin/master <head>` 试算三方合并。
6. **CI**：只读获取各 PR 头的 check-runs 和失败 job 日志（GitHub MCP）。
7. **本地复测**：只在 scratch worktree 里对合并树跑 Node 单测、Python 单测和少量 Maven 定向单测。没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`，没有访问任何生产资源。
8. C～F 组和 H 组的逐项语义核对由并行的只读子代理完成，抽查结论见文末“抽查记录”。子代理全程只读代码，没有跑 CI。凡写着“测试存在”的地方，只表示测试类在 master 上存在，不表示已经看到它通过。

## 处置图例

| 处置 | 含义 |
|---|---|
| MERGE_AFTER_CI | 修复仍需要；在当前 master 上重跑 CI 变绿后，由 owner 合并 |
| BLOCKED_CI_RED | 修复仍需要，但 CI 在当前 master 上是红的，要先修 |
| CLOSE_SUPERSEDED | 内容已被 master 吸收或取代，建议 owner 关闭（本 lane 不操作） |
| PORT_REMAINING_DIFF | 大部分已在 master，只剩少量差异值得另开小 PR 移植 |
| SPLIT_PORT_SUBITEMS | 不整体合入；把其中 master 仍缺的修复拆成小 PR 逐个移植 |
| REFERENCE_PRESERVED | 文档或历史证据，只保留作参考，不合入、不改写 |
| KEEP_OPEN_OWNER_DECISION | 含未完成功能或设计冲突，需要 owner 决策 |
| DO_NOT_PORT | 用于子项：与固定决策（D-12/13/14/16/18）或小程序“不做”清单冲突 |


## 总表（46 个 open PR）

| 组 | 数量 | 处置分布 |
|---|---|---|
| A. 必合修复（T-5） | 5 | MERGE_AFTER_CI×4，BLOCKED_CI_RED×1 |
| B. 已在主线 | 2 | CLOSE_SUPERSEDED×2 |
| C. 产品代码已一致 | 3 | CLOSE_SUPERSEDED×3 |
| D. 审核拆分/整合容器 | 6 | CLOSE_SUPERSEDED×6 |
| E. E 系列与 10-15 修复 | 9 | CLOSE_SUPERSEDED×8，PORT_REMAINING_DIFF×1 |
| F. 10-14 底层整合 | 9 | PORT_REMAINING_DIFF×1，CLOSE_SUPERSEDED×7，SPLIT_PORT_SUBITEMS×1 |
| G. 文档/计划 | 6 | REFERENCE_PRESERVED×6 |
| H. 大载体与未完成功能（T-4） | 6 | SPLIT_PORT_SUBITEMS×3，KEEP_OPEN_OWNER_DECISION×3 |
| **合计** | **46** | CLOSE_SUPERSEDED×26，REFERENCE_PRESERVED×6，SPLIT_PORT_SUBITEMS×4，MERGE_AFTER_CI×4，KEEP_OPEN_OWNER_DECISION×3，PORT_REMAINING_DIFF×2，BLOCKED_CI_RED×1 |

| PR | 标题 | 头 | PR 文件数 / 与 master blob 一致数 | 处置 |
|---|---|---|---|---|
| **A** | **必合修复（T-5）** | | | |
| #412 | fix: 删除失败资质图片后恢复主理人申请 | `28ee751a` | 2 / 0 | **MERGE_AFTER_CI** |
| #418 | fix: 退出登录保留账号同意补传台账 | `393759c5` | 3 / 0 | **MERGE_AFTER_CI** |
| #377 | fix: 社区帖图送检按 OSS 对象 key 登记 inbox（审核分级 P2 剩余修复） | `e8971ae1` | 6 / 0 | **MERGE_AFTER_CI** |
| #434 | fix: 稳定启动预检并补齐重启任务验收 | `65cc424d` | 6 / 2 | **MERGE_AFTER_CI** |
| #449 | fix: 按精确定义与词法绑定核验UI静态门禁 | `0936ff1a` | 3 / 0 | **BLOCKED_CI_RED** |
| **B** | **已在主线** | | | |
| #390 | feat: 审核跟进三项（图片下架通知作者 / 社区帖申诉时限与复核中 / 审核原因编码） | `9e8c870c` | 24 / 16 | **CLOSE_SUPERSEDED** |
| #388 | fix: 后台新建/编辑活动走分级审核 + 统一门禁（Gap 12·03 P0-5） | `6d26f4cc` | 7 / 6 | **CLOSE_SUPERSEDED** |
| **C** | **产品代码已一致** | | | |
| #31 | [E03] CY-HANDOFF 结局冻结会话与旧快照兼容修复（未验证） | `362f8a6b` | 35 / 34 | **CLOSE_SUPERSEDED** |
| #72 | fix: 第三Cloud统计日期与玩家成长并发修复（未验证） | `429aff0c` | 10 / 6 | **CLOSE_SUPERSEDED** |
| #294 | fix: 同主题并发通关死锁在最外层事务边界整笔重试一次 | `604b8c66` | 10 / 4 | **CLOSE_SUPERSEDED** |
| **D** | **审核拆分/整合容器** | | | |
| #375 | feat: 审核单处理时限 + 工作台紧急度排队 + 认领(AUDIT-P3-1/P3-2) | `1d190300` | 8 / 4 | **CLOSE_SUPERSEDED** |
| #376 | feat: 活动分级审核 P0(先发后审 + 高风险先审 + 总闸) | `16a64be9` | 16 / 7 | **CLOSE_SUPERSEDED** |
| #379 | feat: 官方活动先审后发 + 商家优惠券先发后审(资质闸) | `8dc06c7f` | 32 / 19 | **CLOSE_SUPERSEDED** |
| #380 | feat: 主题先发后审 P1（统一门禁 POST_REVIEW + 开售审核与内容编辑解耦） | `765f0d15` | 35 / 19 | **CLOSE_SUPERSEDED** |
| #381 | fix: 活动侧审核缺口收尾(父主题在架闸 / 编辑走门禁 / 俱乐部系列开放 / 官方活动图片 / 审核快照) | `eb93afaf` | 42 / 29 | **CLOSE_SUPERSEDED** |
| #394 | chore: 独立整合移植后端与审核补充并验收 | `663729b8` | 1097 / 989 | **CLOSE_SUPERSEDED** |
| **E** | **E 系列与 10-15 修复** | | | |
| #28 | fix: 补完回执单调、作者快照和NPC删除并发问题 | `4a943501` | 31 / 11 | **CLOSE_SUPERSEDED** |
| #29 | [E01] CY-HANDOFF 排期候补回归与终态回读修复（未验证） | `d3468d38` | 38 / 27 | **CLOSE_SUPERSEDED** |
| #30 | [E02] CY-HANDOFF 社区读取快照与隐私回归（未验证） | `f74dd9be` | 54 / 42 | **CLOSE_SUPERSEDED** |
| #32 | [E04] CY-HANDOFF 券生命周期回归与真实调用链审计（未验证） | `ca5e69f6` | 48 / 32 | **CLOSE_SUPERSEDED** |
| #33 | [E05] CY-HANDOFF 退款确认与回包账号代次隔离（未验证） | `f01b5c06` | 40 / 28 | **CLOSE_SUPERSEDED** |
| #34 | [E06] CY-HANDOFF 定位取消身份与常亮失败恢复（未验证） | `a9d1dcda` | 36 / 25 | **CLOSE_SUPERSEDED** |
| #36 | fix: hide cancelled or unpublished activity reference cards | `8822ab38` | 254 / 190 | **CLOSE_SUPERSEDED** |
| #44 | fix: 小程序现有bug与共享后端修复（协作者输入与取消幂等） | `5752ee6f` | 410 / 251 | **CLOSE_SUPERSEDED** |
| #314 | test: PR #69 小程序修复第三轮定向验收（V3） | `d74c53d1` | 8 / 0 | **PORT_REMAINING_DIFF** |
| **F** | **10-14 底层整合** | | | |
| #3 | fix: 修补小程序对账、消息、草稿、同意记录和NPC有效期 | `0f0c32f1` | 31 / 4 | **PORT_REMAINING_DIFF** |
| #7 | fix: 小程序B包消息隐私、原通知收据及事件广场可靠性 | `aaf6a7be` | 73 / 22 | **CLOSE_SUPERSEDED** |
| #8 | fix: A包原订单票务资格、维护与资金恢复（待运行验证） | `f9974526` | 97 / 34 | **CLOSE_SUPERSEDED** |
| #9 | fix: C包AI原日补偿与漫游玩法恢复（含暂停迁移） | `e65d5f79` | 99 / 39 | **CLOSE_SUPERSEDED** |
| #11 | feat: 小程序底层A/B/C集成（CI全绿候选，draft） | `0b301c9b` | 317 / 130 | **SPLIT_PORT_SUBITEMS** |
| #12 | fix: 后端缺陷 B1 定位精度闸 / B4 主题点赞方向 / B5 俱乐部徽章泄漏 | `a5af41f5` | 10 / 7 | **CLOSE_SUPERSEDED** |
| #14 | fix: 任务2 外壳/商家/俱乐部修复（返回守卫、图片预览、定位兜底、打卡码文本、公告、门店认领） | `6a964771` | 64 / 10 | **CLOSE_SUPERSEDED** |
| #15 | feat: 任务3 漫游与功能入口（继续漫游/官方路线/收到的邮票/清除对话/举报队伍/积分明细/状态键） | `8884a678` | 61 / 14 | **CLOSE_SUPERSEDED** |
| #17 | fix: 首页定位独立同意与撤回并发保护，补齐注销清理 | `c2f43d0c` | 27 / 15 | **CLOSE_SUPERSEDED** |
| **G** | **文档/计划** | | | |
| #2 | docs: 明确公开仓库开发与VPS停止CI策略 | `41dfcee0` | 1 / 0 | **REFERENCE_PRESERVED** |
| #16 | docs: 小程序bug与共用后端架构Cloud并行任务 | `46848178` | 98 / 0 | **REFERENCE_PRESERVED** |
| #21 | docs: Claude 小程序UI与新功能对账及任务卡（CL1–CL5） | `6bfc9e57` | 377 / 152 | **REFERENCE_PRESERVED** |
| #24 | docs: VPS复测231项通过并保留失败修复与资源证据 | `90b81ee7` | 65 / 0 | **REFERENCE_PRESERVED** |
| #26 | docs: 追加六路Cloud精确接收启动包（待启动） | `78c9b389` | 2 / 0 | **REFERENCE_PRESERVED** |
| #70 | docs: 第三Cloud统计日期与成长并发独立开发包 | `9f96f7a8` | 4 / 0 | **REFERENCE_PRESERVED** |
| **H** | **大载体与未完成功能（T-4）** | | | |
| #334 | port: 携带未迁出的私有工作 — 10-13 发布候选（后端+后台） | `81382f28` | 194 / 3 | **SPLIT_PORT_SUBITEMS** |
| #335 | port: 携带未迁出的私有工作 — 10-13 发布候选（小程序） | `54f9fb2e` | 340 / 36 | **SPLIT_PORT_SUBITEMS** |
| #336 | port: 携带未迁出的私有工作 — Codex batch1-cloud | `7d186d55` | 160 / 0 | **SPLIT_PORT_SUBITEMS** |
| #338 | port: 携带未迁出的私有工作 — questify 平台集成（后台/CI/小程序） | `35ce7cc7` | 143 / 0 | **KEEP_OPEN_OWNER_DECISION** |
| #398 | port: questify 后端重新携带（v2；17 个设计层冲突待 owner 决定） | `0127e3dc` | 754 / 0 | **KEEP_OPEN_OWNER_DECISION** |
| #35 | feat: CL6 联合主办展示组件与参与方视图模型（草稿，待读接口补字段） | `27d95dda` | 7 / 0 | **KEEP_OPEN_OWNER_DECISION** |

## A. 必合修复（5）— T-5：对当前 master 的可合并性 + CI

方法：`git merge-tree --write-tree origin/master pr/<n>` 做三方合并试算（不落盘，不推送）；CI 读取各 PR 头提交的 check-runs（GitHub MCP 只读）。另在 scratch worktree 里把合并结果树铺出来，本地跑 PR 触及的 Node / Python 测试（只读复现，不推送）。注意：PR 上的 CI 都是在**旧 base** 上跑的，落后 master 96–243 个提交，所以“CI 绿”不等于“对当前 master 绿”。合并前必须让 PR 在当前 master 上重跑一次。

| PR | 头 | 落后 master | 与 master 合并 | PR 头 CI | 本地对合并树复测 | 处置 |
|---|---|---|---|---|---|---|
| #412 删除失败资质图片后恢复主理人申请 | `28ee751a` | 217 | **干净**（树 `42cf895c`） | 全绿；只改小程序，后端/迁移类 job 按改动范围 skipped | `club-apply-state-contract.test.js` 21/21；`tests/unit/**/club*.test.js` 762/762 | MERGE_AFTER_CI |
| #418 退出登录保留账号同意补传台账 | `393759c5` | 199 | **冲突**：`chengyinhub-xcx/utils/roam-player-memory.js` 一处 | 全绿（在旧 base `428305e1` 上） | 冲突按“两条保留规则取并集”手工解后：`roam-player-memory.test.js` + `usage-consent-app.test.js` 26/26；`**/*verification*.test.js` 265/265 | MERGE_AFTER_CI（作者先合入 master 解冲突） |
| #377 社区帖图送检按 OSS 对象 key 登记 inbox | `e8971ae1` | 243 | **干净** | 全绿，含 Backend tests、Migration safety/layout、Funds MySQL IT、Followup 39 | 合并树上 `mvn -pl chengyinhub-admin -am -Dtest='CommunityPost*Test,WxContentSecurity*Test'`：14 个类 208 个用例全过（0 失败 / 0 错误 / 0 跳过） | MERGE_AFTER_CI（与 Lane B 协调） |
| #434 稳定启动预检并补齐重启任务验收 | `65cc424d` | 158 | **干净**（树 `20d3237b`） | 34 个 check-runs 全绿（含 Real-HTTP chains、Backend tests、20 个小程序分片） | `python3 -m unittest ci/test_chain_acceptance.py ci/test_chain_admin_readback.py scripts/e2e/test_chain_lib.py`：101 通过，1 个 import 错误；该错误是本机缺 `pymysql`，master 上同样报，属环境问题。`ci/test_upgrade_startup_network.py` 19/19 | MERGE_AFTER_CI |
| #449 按精确定义与词法绑定核验 UI 静态门禁 | `0936ff1a` | 96 | **干净**（树 `435ded1e`） | **红**：`Mini program unit shard (2/20)` 失败 1 个；`Mini program static gates` → `UI-GATE-0 全仓扫描(U1-U5)` 红 | **在合并树上复现**，见下方 | BLOCKED_CI_RED：先修再合 |

### #418 冲突详情
- 双方都在 `clearStoragePreservingPlayerRoamMemory` 的同一行加了一条“退出登录时保留”的规则。
  - master 加的是 `27cd17f2` 里的核销 UNKNOWN 键（`verification_unknown_v1:<64hex>`）。
  - PR 加的是同意台账键（`cy_usage_pending_v1:<memberId>`）。
- 最小解法是在同一个 `if` 里同时保留这两类键。PR 的两个测试文件自 base 以来在 master 上没有改动，可以原样带入。
- 当前 master 在退出登录时**仍会清掉** `cy_usage_pending_v1:*`（全仓 grep 只在测试里出现），所以这个修复确实缺失。
- 与 Lane E（同意台账）的文件有重叠，合并顺序需要 owner 协调。

### #377 说明
- 后端 Java。本机在合并树上跑了定向单测，全过（见上表，需要设 `LC_ALL=C.UTF-8`，否则 javac 读中文文件名会报错）。真库 IT 没有跑；合并前仍需 GitHub CI 在当前 master 上重跑一次。
- 与 Lane B（社区图片审核）的文件可能重叠，需要先确认 Lane B 的改动是否已经吸收了 #377。

### #449 失败根因（对当前 master 的合并树复现）
1. **单测回归**（是 PR 本身造成的，不是基线问题）
   - 失败用例：`chengyinhub-xcx/tests/unit/community-api-namespace-gate.test.js:26` 中的 “real Controller class mapping supplies namespaces; full unknown route remains RED in both scanners”，断言在 :41：`violations.some(x => x.message.includes('运行时变量')) === true`。
   - 同一个测试在 master 上 10/10 全过，在 PR 头和合并树上都是 `false !== true`。
   - 原因：PR 改写了 `tests/lint/ui-integrity.js` 的 U1 文案/分类，`BASE + path` 这种写法不再报“运行时变量”。
2. **UI-GATE-0 全仓扫描 11 条违规**（master 上 `node chengyinhub-xcx/tests/lint/ui-integrity.js --warn-as-error` 退出码为 0，合并树上为 1）
   - 8 条 `[U1]` 是新口径对现有生产代码的报红：
     - `components/cy/scene-member-invite-history/index.js:179,185`
     - `pages/index/index.js:362`
     - `pages/merchant/index/index.js:1220,1249`
     - `utils/features/exploration-anniversary.js:68`
     - `utils/roam-share-snapshot.js:79,104`
   - 3 条 `[BASELINE]` 是过期的基线项，需要删除：
     - `subpackageMember/components/cy/profile/index.js:1095`
     - `pages/activity/official-inbox/index.js:109`
     - `subpackageA/pages/myproject/index.js:571`
   - 最小修法：PR 内补齐这 8 处的有限 /api 证明（或登记带理由的白名单），删掉 3 条过期基线，再把测试 :41 的断言与新文案对齐。不要放宽门禁。
   - 与 Lane C（CI 门禁）同属门禁文件，建议由 Lane C 或 #449 作者接手。

## B. 已在主线（2）

### #390 审核跟进三项（图片下架通知作者 / 社区帖申诉时限与复核中 / 审核原因编码）
- **祖先证据**：`git merge-base --is-ancestor 9e8c870c 063a6c3c` 返回 0，说明 PR 头 `9e8c870c` 是 master 的祖先。经 master 上的 `df816885`（“整合审核跟进并补齐原因展示与迁移校验”）→ `557b4a98` 进入主线。PR 没有任何不在 master 里的提交（ahead=0）。
- 24 个 PR 文件中，16 个与 master 的 blob 完全一致。其余 8 个是 master 在合入后**又改过**的，不是 PR 独有的内容：
  - `CommunityPostMapper(.java/.xml)`
  - `CommunityPostServiceImpl`
  - `ICommunityPostService`
  - `WxContentSecurityServiceImpl`
  - 两个社区测试
  - `ci/migration-dependencies.json`
- **处置：CLOSE_SUPERSEDED**（建议 owner 以“已被 master 吸收”为由关闭；本 lane 不操作）。

### #388 后台新建/编辑活动走分级审核 + 统一门禁（Gap 12·03 P0-5）
- PR 改了 7 个文件。其中 6 个业务/测试文件与 master **字节一致**（blob 哈希相同）：
  - `CmsActivityController.java`
  - `ICmsActivityService.java`
  - `CmsActivityServiceImpl.java`
  - `BusinessActivityEditSoldLockTest.java`
  - `CmsActivityAdminReviewGateTest.java`
  - `ContentAuditBypassControllerSafetyTest.java`
- 唯一不同的是 `ci/prelaunch-seed-provenance.json`（PR blob `ad766f0c`，master blob `7e894a67`），只差 2 行：`CmsInfomationController.java` 和 `AnalyticsController.java` 的源码 sha256。
  - master 上这两个文件的实际 sha256 前缀是 `fa57c59a…` 和 `66927cbc…`，与 **master** 的 provenance 一致；PR 里的是旧值。
  - `CmsActivityController.java` 的哈希（`0ac7044d…`）三方一致。
- **处置：CLOSE_SUPERSEDED**。PR 剩下的那 1 个差异文件如果带入，反而会让 provenance 门禁报红。

## C. 产品代码已一致（3）

Master = `063a6c3cd7c1f131618148c385174037f1d2786e` (the working tree is master). Baseline is taken from base.json. Each diff below was run against `origin/master` with `git diff origin/master pr/<n> -- <path>`. No CI or tests were run by me.

### #31 阶段结算沿有效非空冻结节点检查完成门槛 (head `362f8a6b`, base `codex/mini-bug-arch-integration-20261015` @ `1e403b13`)

**Disposition: CLOSE_SUPERSEDED**

Evidence:
- The PR touches 35 files (28 A, 7 M). 34 of them have a blob identical to master. All 5 product files are among them: `JourneyStory.java`, `PlayJourneyReadServiceImpl.java`, `PlayJourneyStoryServiceImpl.java`, `TopicRouteSessionScope.java` and the docs. The remaining test files are also identical.
- The only differing file is `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/SeventhPierFixtureTest.java`, with head blob `e12f3373` against master blob `068fd576`. The exact diff (master→PR) is:
  ```
  @@ -337,8 +337,6 @@
           when(sessionMapper.updateStateCas(any())).thenReturn(1);
  -        // CORE OWN-C06-25：动作回执总以最终结算值回写原动作。
  -        when(sessionMapper.updateActionResponse(any(), any())).thenReturn(1);
  ```
  These are two lines that **master added** and the PR does not have. Master added them in commit `3d7a1de8` ("test: master 既有回归夹具对齐 CORE 的身份与回执合同"), which adds the `updateActionResponse` stubs needed by OWN-C06-25.
- Master's runtime code calls `routeMapper.updateActionResponse(...) != 1 → throw`. Examples are `TopicRouteRuntimeServiceImpl.java:731, 2252, 3105, 3253, 4126, 4275, 4341`. If the stub were missing, the Mockito default would return 0 and the fixture would throw. So the 2-line test diff **must stay on master**. Taking the PR version would break the test.

| Item | State on master | Action |
|---|---|---|
| 5 product files + 29 tests/docs | blob-identical | none |
| SeventhPierFixtureTest 2 lines | master is newer (has the stub) | do not port PR side |

Recommendation: close as superseded. Nothing to port.

### #72 第三队成长并发/统计本地证据 (head `429aff0c`, base `codex/mini-forward-cloud-plan-20261015` @ `9f96f7a8`)

**Disposition: CLOSE_SUPERSEDED** (the docs could be kept as REFERENCE, but they are not needed)

Evidence:
- The PR touches 10 files. All 6 product/test files that are identical by blob are `AnalyticsController`, `PlayerGrowthMapper.java/.xml`, `PlayerProgressionServiceImpl`, `PlayerProgressionServiceImplTest` and `AnalyticsControllerTest`.
- `PlayerGrowthConcurrencyMySqlIT.java` has PR blob `413c912c` against master blob `668f36cf`. The PR is the **older** 11-test version (the d592334a lineage). Master is a strict superset of it:
  - Master's `reset()` uses `TRUNCATE` with `lock_wait_timeout=10`. The PR uses `DELETE`.
  - Master has the extra negative-control test `sameTopicDuplicateWithoutRetryBoundaryIsDeadlockVictim`.
  - On master, `sameTopicOuterUniqueMarkerCallsGrowthOnlyOnce` goes through the `DeadlockRetryInterceptor` boundary and asserts `markerInserts==3`.
  - Master adds a lock-wait wait on `performance_schema.data_lock_waits` that checks the real transaction connection.
  - Master has `awaitRequestBarrier`/`requirePending`.
  - The PR has none of these. Taking the PR side would remove coverage of the deadlock-retry behavior.
- `docs/verification/mini-forward-cloud-20261015/{CLAIMS.json,DELIVERY.md,QUEUE.json}` (836 lines) are missing on master. They hold Cloud-session coordination and evidence notes: claims/queue, "COMPILE_PASS_ONLY, 实际测试0" and "NOT_RUN_EXISTING_ENTRY_COVERAGE_GAP". They contain no runnable evidence. The two open questions they raise are already handled on master:
  - RR old-read-view on `TopicCompletionAwardServiceImpl` DuplicateKey recovery: covered by `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RedeemExploreReadViewMySqlIT.java` (registry batch `port-f3-evidence`, 4 tests).
  - `BizFunnelDailyMapper.xml` 9999-12-31 DATE_ADD: per the registry this is `FunnelMaxDateMySqlIT` (#296). That is a separate group and I did not verify it here.
  - The IT itself is now registered in `ci/real-storage-it-registry.json` (`port-f3-growth`, 12 tests, gate `CHENGYIN_FUNDS_MYSQL_IT=1`). This closes the "existing entry coverage gap" that DELIVERY.md complains about.

| Item | State | Worth porting? |
|---|---|---|
| PlayerGrowthConcurrencyMySqlIT (PR older) | master superset | No |
| docs/verification/mini-forward-cloud-20261015/* | absent on master | No (process notes; questions resolved elsewhere). Keep the PR branch as reference only if the owner wants the audit trail |

### #294 同主题并发通关死锁在最外层事务边界整笔重试一次 (head `604b8c66`, base = #72 head `429aff0c`, stacked)

**Disposition: CLOSE_SUPERSEDED**

PR's own delta (`429aff0c..604b8c66`, 3 commits `763dcb43`, `88e01452`, `604b8c66`):
- It adds `DeadlockRetry.java`, `DeadlockRetryInterceptor.java` and `DeadlockRetryConfig.java`. All three are **blob-identical on master**.
- It adds `@DeadlockRetry` to `PreferencePlayService.submit` (blob-identical) and to 6 controller endpoints, 2× `CmsRegistrationServiceImpl.redeemExploreChapterAndRecord`, 2× `TopicCompletionAwardServiceImpl.grantOnce` and `TopicRouteRuntimeServiceImpl.completeOpening`. **Every one of these annotations is present on master**:
  - `ApiPlayProgressController.java:1328 (checkin), 1481 (answer), 1601 (puzzle/reveal), 1902 (sensor-result), 1958 (arrive), 2141 (photo)`
  - `CmsRegistrationServiceImpl.java:3157, 3167`
  - `TopicCompletionAwardServiceImpl.java:39, 46`
  - `TopicRouteRuntimeServiceImpl.java:267`
  - `PreferencePlayService.java:66`
- Master ported this in `cfea9c74` ("test: 成长并发真库用例改用 #294 头 604b8c66 的去竞态版本") and hardened it later in `21aaa153` and `0f4dc94b`.

Remaining diff, pr/294 vs master, per file. In every file the hunks are master being newer; none of them is PR-only content:

| File | Diff (PR→master) | Nature |
|---|---|---|
| `ApiPlayProgressController.java` | 78 lines | 8 later master commits; PR adds no retry/deadlock lines (grep of PR `+` lines for retry/deadlock = 0) |
| `CmsRegistrationServiceImpl.java` | 483 lines | 14 later master commits; same |
| `TopicRouteRuntimeServiceImpl.java` | 1531 lines | 25 later master commits; same |
| `TopicCompletionAwardServiceImpl.java` | 11 lines | Master adds `@Lazy` on `topicService` (breaks the circular dependency) and `addCompletionExp`+`linkEarnedExp` XP fact linking; the PR has the older `addExp(...,"通关奖励")` without the link. Master is newer |
| `DeadlockRetryInterceptorTest.java` | 43 lines | Master adds `rateLimiterCountsOnceAcrossTheRetry` and RateLimiterAspect wiring (Redis counted once across a retry). The PR lacks it |
| `PlayerGrowthConcurrencyMySqlIT.java` | 98 lines | Master checks the lock wait by a precise transaction/connection-bound `performance_schema.data_lock_waits` query. The PR uses a loose `innodb_trx trx_state='LOCK WAIT' and trx_query like '%player_badge%'` check. Master is stricter |

(a) Outermost-boundary check on master. The interceptor skips the retry when `TransactionSynchronizationManager.isActualTransactionActive()` is true, so an annotation on an inner method does nothing. That is safe, but it would be pointless if no outer boundary had the retry. I traced every caller of the methods that write growth/award:
- Node growth `playerProgressionService.onNodeDone` is only reached from `advanceProgress` (`ApiPlayProgressController.java:3053`). `advanceProgress` is only called from checkin (1412), answer (1581/1585), revealPuzzle (1644), sensorResult (1943), arrive (2074), photo (2206/2244) and submitPreference (1735, via the lambda inside `PreferencePlayService.submit`). All of them carry `@DeadlockRetry` + `@Transactional(READ_COMMITTED)` themselves, and the `submitPreference` controller has no transaction of its own. **Outermost: OK.**
- Topic growth `onTopicComplete` is only reached from `TopicCompletionAwardServiceImpl.grantOnce:97`. Its callers are:
  - (1) `advanceProgress:3126/3127`: the outer controller boundary has the retry.
  - (2) `TopicRouteRuntimeServiceImpl.completeOpening:311/313`: completeOpening has the retry. Its caller `ApiPlayJourneyActionController.completeOpening:162` has no `@Transactional`.
  - (3) `CmsRegistrationServiceImpl.redeemExploreChapterAndRecord:3265`: that method has the retry. Its caller `ApiRegistrationController.doRedeemExploreChapter:1514/1523/1527` has no `@Transactional` anywhere in that controller.
  - **All outermost boundaries carry the retry.** The inner `grantOnce` annotation only matters for a direct non-transactional caller, and there is none.
- Not on the completion path, and so not annotated: `/puzzle/hint` (1663, `@Transactional` only; calls `puzzlePlayService.requestHint`, with no progress or award) and `/scan-entry` (read-only resolve).
- Aspect order: `RateLimiterAspect` is `@Order(DeadlockRetryConfig.ORDER - 1)` (outside the retry), and `DeadlockRetryConfig.ORDER = LOWEST_PRECEDENCE - 1` (outside the transaction advisor).
- The catch-alls in the endpoints wrap parameter parsing (`catch (Exception e) { return error("参数有误"); }`) or photo verification. Nowhere did I find a catch-all around the DB writes that would swallow `DeadlockLoserDataAccessException`. This is from reading the code only.

(b) Retry cap: `DeadlockRetryInterceptor.MAX_ATTEMPTS = 2` (one retry). Backoff is 10–60 ms of random jitter. Only `DeadlockLoserDataAccessException` (1213) and `CannotAcquireLockException` (1205) trigger it. The values are identical in the PR and on master, since the file blob is identical. Tested by `DeadlockRetryInterceptorTest.givesUpAfterTwoAttemptsAndRethrowsTheLockFailure`, `lockWaitTimeoutIsRetriedOnceToo`, `otherFailuresAreNotRetried`, `innerParticipantDoesNotRetryAndLeavesItToTheOuterBoundary` and `retryAdvisorIsOrderedOutsideTheDefaultTransactionAdvisor`. Master also has `rateLimiterCountsOnceAcrossTheRetry`.

(c) Persisted outcome after a retry:
- The retried whole transaction is idempotent:
  - Node progress insert: `DuplicateKeyException` → re-read the existing row (`ApiPlayProgressController.java:~2984`).
  - Topic completion: unique `TOPIC_FINISH_<topicId>` badge (`uk_member_badge`) → `firstCompletion=false` on the retry. Only the first completion calls `onTopicComplete`.
  - Points: deduplicated by `(member,13,eventId)`.
  - Coupon: deduplicated by the log uk.
  - XP: checked through `xpAwardMapper.selectOne`.
  - Clawback re-grants: the member row is locked first, then the markers are consumed under `FOR UPDATE`.
- Coverage, all in `PlayerGrowthConcurrencyMySqlIT` on real MySQL:
  - `sameTopicOuterUniqueMarkerCallsGrowthOnlyOnce`: retry boundary on; second call returns `firstCompletion=false`; markerInserts=3; growth 4/10.75/2; one TOPIC_CLEAR.
  - `sameTopicDuplicateWithoutRetryBoundaryIsDeadlockVictim`: negative control.
  - The class is registered in `ci/real-storage-it-registry.json` batch `port-f3-growth` (12 tests, `CHENGYIN_FUNDS_MYSQL_IT=1`).
  - I did not check whether that workflow has gone green on master: **UNVERIFIED**.
  - One small stale note: the registry text says the master IT is "byte-identical to 604b8c66", but master blob `668f36cf` ≠ `4fa95b74` after `21aaa153`. The wording is stale; the coverage is not reduced.
- Residual caveats, not regressions from #294:
  - The retry scope of `redeemExploreChapterAndRecord` includes `merchantReferralBountyService.onVerification` (in-transaction, swallows exceptions) and `committedBusinessNotice.executeAfterCommit` (only after commit). I did not audit the bounty service for non-DB side effects that a retry would repeat: UNVERIFIED.
  - Controller paths: I found no `redisCache`/`redisTemplate` writes in `ApiPlayProgressController`. I did not audit the downstream services deeply.

Minimal remaining fix: **none from #294.** Optional: correct the stale "byte-identical to 604b8c66" note in `ci/real-storage-it-registry.json` (`port-f3-growth`).

## D. 审核拆分/整合容器（6）

Master = origin/master `063a6c3c`. All evidence below is from reading code (`git show/diff/log`, plus grep in the master working tree). **No CI or tests were run by me.** Wherever these notes say a test "exists", that means the test class is present on master. It does not mean I saw it pass.

Method: for each PR I read the body (GitHub `pull_request_read get`) and listed its commits with `git log <merge_base>..pr/<n>`. For every path in base.json `differs_from_master` I ran `git diff --numstat origin/master pr/<n>`, `git log -1 origin/master -- <path>`, and a line-level check: does each `+` line of the PR→master diff appear in master or in the merge-base? Scripts: （本地临时脚本/文件，未入库）.

The four stacked audit PRs (#375/#376/#379/#380) share merge-base `18e09885`. #381 has merge-base `3c5e0ee7`. Their commits have all landed on master under new SHAs (cherry-pick or re-merge):

| PR commit | master equivalent(s) |
|---|---|
| #375 `1d190300` 审核单写入处理时限…认领 | `c3b8a6e3`, `8e0cefc5` |
| #376 `16a64be9` 活动分级审核 P0 | `631dc1e8`, `e9d6cd64`, `a7f36587`, `78d55644`, `fefb6a60`, `51bcd223`; follow-up fix `774eef85` (AUDIT-INT-10) |
| #379 `b5a2c886` / `c445d89b` / `8dc06c7f` | `b83361f8` / `bf9d60ec` / `dfd3f71e` |
| #380 `1226a7c6` / `e07cc230` / `765f0d15` | `aa1498fc`, `8c22fe41` / `cb7a014e`, `ce14bcf9` / fixtures re-done in `0f4dc94b`, `21aaa153` |
| #381 `ed155e4f` `d3a19c7f` `8b107bdb` `900bf9ea` `19feedd2` `eb93afaf` | `310f2ef5` `d0bbd585` `8ec042ac` `1a43aefb` `d60d96f0` `7efeed4f` |

---

### #375 feat: 审核单处理时限 + 工作台紧急度排队 + 认领(AUDIT-P3-1/P3-2)
**Disposition: CLOSE_SUPERSEDED**

Evidence (focus: claim CAS). **Yes:** on master the claim is an atomic conditional UPDATE.
- `chengyinhub-system/src/main/resources/mapper/business/AuditTaskMapper.xml:141-148` `claimAuditTask`: `update audit_task set current_assignee=#{assignee}, update_time=#{updateTime} where id=#{id} and current_assignee is null and status = 0`. It has no `version` predicate, which matches the PR's stated decision that claiming does not change content.
- `AuditTaskServiceImpl.java:1773-1785` `claim()`: `claimAuditTask(...) != 1` → `ServiceException("该任务已被认领或已处理")`. This runs `@Transactional`, so two concurrent claimers cannot both win. Master adds one thing on top of the PR: an appeal task that gets claimed calls `auditAppealMapper.markReviewing`, which the PR lacks.
- Endpoint `AuditWorkbenchController.java:96-103` `POST /business/audit/{id}/claim` has `@PreAuthorize('business:audit:list')` + `@Log`.
- Tests on master: `AuditTaskQueueH2Test.claimOnlyOnceAndNeverOnFinishedTask` (chengyinhub-admin/src/test/.../mapper/AuditTaskQueueH2Test.java:96-116), `AuditTaskDeadlineSlaTest`, `AuditWorkbenchClaimEndpointTest`.
- The 4 differing files are all master-superset. PR→master is `-874/+26` on `AuditTaskServiceImpl.java`, and the 26 `+` lines are pre-refactor versions of master code (`findOpenTaskId`, `isRevertSupported`, …). Mapper, IAuditTaskService and XML are deletions only. Master last touched these in `9e8c870c` (FOLLOWUP-C-3).

| Sub-item | Master | Remaining / minimal fix |
|---|---|---|
| P3-1 `deadline_at` written on all 4 creation paths | Covered: `AuditTaskServiceImpl.java:214,245,332,559` + `deadlineAt/slaHours` :1788-1806, configurable `@Value` :106-113 | Existing rows are not backfilled. That needs DML via the migrate skill, so it is an owner decision. |
| P3-2 urgency queue `selectAuditTaskQueue` | Covered: XML:87-90 (`order by deadline_at is null, deadline_at asc, submitted_at asc, id asc`); service :1760-1763 | — |
| Claim CAS | Covered (above) | Minor: `status = 0` only, so ESCALATED tasks (`STATUS_ESCALATED=3`, AuditTask.java:16) cannot be claimed. If wanted, change the predicate to `status in (0,3)` and add an H2 test case. |
| Overdue monitor effective | Covered by the queue/deadline mapper (`deadline_at < now`, XML:203,213) | — |
| UI claim button / release / reassign (out of scope in the PR) | Not present: `chengyinhub-ui/src/views/business/audit/index.vue` shows `deadlineAt` (:91) but has no claim call | Follow-up UI ticket only, not part of this PR. |

Recommendation: close as superseded by `c3b8a6e3`/`8e0cefc5`. Do not rebase. The escalated-claim and UI items are optional follow-ups.

---

### #376 feat: 活动分级审核 P0(先发后审 + 高风险先审 + 总闸)
**Disposition: CLOSE_SUPERSEDED** (porting its diff would regress master: **DO_NOT_PORT** `PublishSafetyRequest.java` head blob `8c29d5d3`)

Evidence: all 9 differing files have master last-modified after the PR, and every one shows master-superset in the diff stats (e.g. `CmsActivityServiceImpl.java` +12/-168, `ApiActivityController.java` +13/-48). The one substantive PR-side difference is the **default `reviewMode = POST_REVIEW`** in the PR (`PublishSafetyRequest.java`). Master fixed it in `774eef85` (AUDIT-INT-10) to `PRE_REVIEW` (`PublishSafetyRequest.java:41`), so a caller that forgets the mode fails closed. Re-applying the PR version would make any un-set caller fail open.

**Which content types and actors get which review mode in production (master code, no sys_config rows seeded):**

| Content / actor | Mode on master | Where |
|---|---|---|
| Unset or garbage `reviewMode` (any caller) | PRE_REVIEW | `PublishSafetyRequest.java:41`; `ContentSafetyGateImpl.resolveMode` :106-117 (exact enum name only) |
| User activity publish `/api/activity/publish` | Policy: POST_REVIEW by default. PRE_REVIEW if the merchant is unapproved, the category is in `audit.activity.high_risk_categories`, `audit.activity.pre_review_all=true`, config read throws, or request/publisher is null | `ActivityReviewPolicy.java:35-58`; `ApiActivityController.java:813-842` |
| User activity edit `/api/activity/update` | Same policy (DB category used when the request omits it) | `ApiActivityController.java:925-945` |
| Club session open (single + series) | Same policy | `ClubSessionServiceImpl.java:130-158` |
| Back-office activity create/edit (staff) | Policy with `STAFF=Publisher(false,false)`: category and master switch apply, with no exempt channel; openid "" → IDENTITY_REVIEW hold or flag | `CmsActivityController.java:159-206` |
| Merchant coupon | POST_REVIEW | `ApiCouponController.java:176` |
| Official event V1 / V2 schedule | PRE_REVIEW | `ApiOfficialEventController.java:260`; `OfficialEventV2ServiceImpl.java:456` |
| Community post / reply | PRE_REVIEW (explicit) | `CommunityPostServiceImpl.java:288, 623` |
| Topic create/edit text | POST_REVIEW | `TopicTextSafety.java:123` |
| Clear violation (any mode) | BLOCK | `ContentSafetyGateImpl.java:72-76` |

Production config fact: no migration or seed anywhere in the repo inserts `audit.activity.high_risk_categories` or `audit.activity.pre_review_all`. I grepped the whole tree; those keys appear only in Java and `CmsActivityAdminReviewGateTest`. With the rows absent, `selectConfigByKey` returns empty or null, so **every activity from an approved merchant, club, user or staff publisher is POST_REVIEW in production.** Only unapproved merchants get PRE_REVIEW, and those are already 403'd at `MerchantAccessService.require`. This matches the owner's 2026-10-20 decision as stated in the PR. But the rollback switch and the high-risk list do nothing until ops inserts the two rows.

| Sub-item | Master | Remaining |
|---|---|---|
| `ReviewMode` enum + `SafetyDecision.passFlagged` | Covered (`PublishSafetyRequest.java:18`) | — |
| Gate branches PRE/POST/EXEMPT | Covered (`ContentSafetyGateImpl.java:45-104`) | — |
| `ActivityReviewPolicy` | Covered (`service/support/ActivityReviewPolicy.java`); test `ActivityReviewPolicyTest` | — |
| Activity publish via the gate + flag spot-check task | Covered (`ApiActivityController.java:823-842`, `flagForSpotCheck`) | — |
| `publishActivity(..., preReview)` | Covered (`CmsActivityServiceImpl`, last `832b46a9`) | — |
| Default mode | Master is **stricter** (PRE) | Do not port the PR's POST default |
| sys_config rows (ops pre-deploy step) | **Missing** | Owner decision: add a forward `chengyinhub-admin/sql/migration_audit_activity_review_mode_config_<date>.sql` with idempotent `INSERT … SELECT … WHERE NOT EXISTS` for the two keys (`''` / `'false'`), or keep it as a documented ops step. Either way the behaviour is unchanged (POST); the point is that the rollback switch is ready. |
| Known side-effect: approving a flag after the owner self-unpublished re-lists the activity | UNVERIFIED on master | Check `applyModeration` BIZ_ACTIVITY approve for `post_review_flag`: it should not set status=1 when the owner has unpublished. |

Recommendation: close as superseded. Optionally open a tiny follow-up for the sys_config seed migration. The Ask-first rule applies (configuration).

---

### #379 feat: 官方活动先审后发 + 商家优惠券先发后审(资质闸)
**Disposition: CLOSE_SUPERSEDED**

Evidence: all 4 commits are on master (`b83361f8`, `bf9d60ec`, `dfd3f71e`, plus the P0 commit). The 13 differing files are all master-superset. `AuditTaskServiceImpl` PR→master is +27/-912, and the `+` lines are older code. `OfficialEventServiceImpl` +1/-12 (master last `681c8ea0`). `index.vue` 0/-1.

| Sub-item | Master | Remaining |
|---|---|---|
| V1 `/api/official/publish` gate PRE_REVIEW → `submitForReview` (status=0, PENDING_REVIEW) | Covered: `ApiOfficialEventController.java:260`; test `ApiOfficialEventPreReviewControllerTest` | — |
| V2 schedule PRE_REVIEW, bindings not activated, no resubmit/overwrite while pending | Covered: `OfficialEventV2ServiceImpl.java:456`; test `OfficialEventPreReviewTest` | — |
| Workbench `applyModeration` OFFICIAL_EVENT / COUPON branches | Covered: `AuditTaskServiceImpl.java:1637-1640` (+ rollback mapping :1710-1721); test `AuditTaskOfficialEventCouponModerationTest` | — |
| Coupon POST_REVIEW + flag task + reject → status=3 | Covered: `ApiCouponController.java:169-180`; test `CouponPublishReviewModeTest` | — |
| Qualification gate: no merchant row → refuse | Covered: `ApiCouponController.java:163-167` ("商家未通过入驻审核，暂不能发布优惠券") | — |
| Workbench labels 官方活动 / 优惠券抽检 | Covered: `chengyinhub-ui/src/views/business/audit/index.vue:272-273` | — |
| Back-office V2 schedule text "已提交审核" / restore admin official controller (seed hash pin) | Covered: `bf9d60ec`, `dfd3f71e` | — |

Recommendation: close as superseded.

---

### #380 feat: 主题先发后审 P1（统一门禁 POST_REVIEW + 开售审核与内容编辑解耦）
**Disposition: CLOSE_SUPERSEDED** (one residual gap found on master, to be fixed in a new PR and not by porting this one)

Evidence: the 16 differing files are all master-superset. `CmsTopicServiceImpl.java` +43/-132 contains only a moved `saleTerms`/`refreshPendingSaleReview` block and lines master later removed (content-safety hold, `markReviewFlag`). The test files (`OWN_C03_21/24Test`, `TopicSaleReviewDecouplingTest`, `ApiTopicPostReviewTest`) are alternative fixtures for the same assertions; master's versions come from `0f4dc94b`/`21aaa153`.

**Focus: is the reviewed snapshot of terms frozen?** Yes, for the sale terms:
- Fingerprint: `service/support/TopicSaleTerms.java:29-52`. It strips only topicId and the chapter id/name/description/blocksJson, and treats null as changed (fail-safe).
- Rebuild path: `CmsTopicServiceImpl.java:4643` (before) / `:4836-4841`. If the terms changed, `invalidateApprovedCanonical` resets the sale review. Otherwise `refreshPendingSaleReview` refreshes the *pending* task snapshot in place (:4882-4900).
- Freeze at finalization: `TopicSaleFinalizationServiceImpl.java:333-345` `freezeCanonical15` refuses on a duplicate snapshot or on a schema or parameter version drift between the approved canonical and the locked draft. `ApprovedSaleCanonicalSource` is unchanged.
- Tests: `TopicSaleTermsTest`, `TopicSaleReviewDecouplingTest`, `TopicTextSafetyTest`, `ApiTopicPostReviewTest`.

**Draft-content gap (new finding, master):**
- `ApiTopicController.flagForSpotCheck` (:976-1004) returns early for drafts (`selectTopicIsDraft==1`) **before** `markReviewFlag` and before creating the `post_review_flag` task.
- `POST /api/topic/publish-draft` (:1316-1380) → `CmsTopicServiceImpl.publishDraftInternal` (:2915+) then publishes simple-mode drafts with `checkStatus=1` ("机审通过/免审"). It **never re-runs `TopicTextSafety`**, and it creates no flag task.
- Result: draft text that the machine check could not clear (REVIEW_REQUIRED, detection unavailable, missing openid, detection disabled) can be published as "machine-passed", with no spot-check record and no `review_flag`.
- Minimal fix: in `flagForSpotCheck`, still call `markReviewFlag(topicId,true)` for drafts (skip only the task). Then in `publishDraftInternal`, if `review_flag=1`, create the `post_review_flag` task and do not set `checkStatus=1`. The alternative is to re-run `TopicTextSafety.check` on the persisted texts in the `publish-draft` endpoint. Add a controller test: draft saved with a REVIEW_REQUIRED verdict → publish-draft → expect a flag task.
- Severity: content-safety and data-integrity, not funds. I traced this by reading the code only; it is UNVERIFIED by any test.

| Sub-item | Master | Remaining |
|---|---|---|
| Topic create/update → `TopicTextSafety` POST_REVIEW (incl. narrative texts, per-entry re-check) | Covered: `ApiTopicController.java:511-521, 727-736` | — |
| Flag via `BIZ_TOPIC post_review_flag`; skip drafts / skip if an open task exists | Covered (plus `review_flag` column from #377, `markReviewFlag`) | **Draft gap above** |
| Flag task does not lock edit; converted to `topic_bundle_v2` on bundle submit | Covered in `AuditTaskServiceImpl` (master last `9e8c870c`) | — |
| Edit re-checks only changed text | Covered: `ApiTopicController.java:727-736` | — |
| Sale review stays review-first; terms fingerprint decouples text edits | Covered (above) | — |

Recommendation: close as superseded and open a small fix PR for the draft-flag gap.

---

### #381 fix: 活动侧审核缺口收尾(父主题在架闸 / 编辑走门禁 / 俱乐部系列开放 / 官方活动图片 / 审核快照)
**Disposition: CLOSE_SUPERSEDED**

Evidence: all 6 commits are on master (`310f2ef5`, `d0bbd585`, `8ec042ac`, `1a43aefb`, `d60d96f0`, `7efeed4f`). The 13 differing files are master-superset. Their `+` lines are older variants: `CmsActivityMapper.xml` 0/-20, `WxContentSecurityServiceImpl` +6/-108, `CommunityPostServiceImpl` +38/-565 (older community code). Master later layered AUDIT-INT-5/7 on top (`50c0f589`, `3300101a`).

**Focus: parent-topic takedown propagation.** On master, child activities are hidden and new orders blocked:
- Public reads: `CmsActivityMapper.xml:55-61` `livePublicParentTopic` = parent `del_flag=0 AND status=1 AND user_status=1`. It is included in `selectPublicCmsActivityList` (:165), `selectPublicCmsActivityById` (:239) and `selectCmsActivityDetailById` (non-owner branch). Roam `RoamHangoutMapper.xml:118-120` and recommendation `RecommendationScoreDailyMapper.xml:45-47, 90-92` use the same rule.
- Orders: `CmsRegistrationServiceImpl.java:1207-1212` `isLiveParentTopic` + :1248-1253 throws "主题活动已下架". Quote, create and retry-pay share this path, and the check sits after the club-member gate.
- Club open: `ClubSessionServiceImpl.java:130-132` "主题未上架或已下架，不能开团".
- Content-safety image takedown writes `user_status=0` on the topic (comment at `CmsRegistrationServiceImpl.java:1808-1812`), so it propagates through the same predicate. Existing orders are untouched, and activities come back automatically when the topic is re-listed. No activity rows are rewritten.
- Tests on master: `ActivityParentTopicSignupGateTest`, `ActivityEditResubmitServiceTest`, `AuditTaskActivityRefreshTest`, `ActivityUpdateReviewModeTest`, `OfficialEventMediaCallbackTakedownTest`.

Residual (not in the PR's scope, UNVERIFIED impact): two read/write paths select `cms_activity … publish_status=1` without the parent-topic predicate.
- (a) `ClubEventOpsMapper.xml:308-314` `selectPromotableActivityForUpdate`: waitlist promotion could still promote into a session whose parent topic was taken down.
- (b) `CommunityPostMapper.xml:383+` `selectReferenceOptions`/`selectReferenceSnapshot` for `ACTIVITY` references.
- Minimal fix: add the same `live_parent` exists-clause to (a), or have the promotion service call `isLiveParentTopic`, and add a test. (b) is low-risk display.

| Sub-item | Master | Remaining |
|---|---|---|
| G1-1 parent-topic live gate (reads + orders) | Covered (above) | Residual (a)/(b) |
| G1-2 activity edit via gate, rejected → resubmit, `submitOrRefreshActivity` | Covered (`ApiActivityController.java:925-945`; `IAuditTaskService` master last `5d90238e`) | — |
| G1-3 club open/series: parent live + gate + policy | Covered (`ClubSessionServiceImpl.java:130-158`) | — |
| G1-4 official event cover violation → ST_OFFLINE | Covered (`WxContentSecurityServiceImpl.java:847-871`) | — |
| G1-5 `ActivityAuditSnapshot` | Covered (`d0bbd585`) | — |
| G1-6 stale comment | Covered (`d60d96f0`) | — |
| G1-7 update-future (no code) | N/A (pinned by `ClubEventOpsServiceImplTest.futureOnlyEditRejectsRemovingOccurrenceWithAnyRegistrationHistory`) | — |

Recommendation: close as superseded. Fold residual (a) into a small follow-up fix.

---

### #394 chore: 独立整合移植后端与审核补充并验收
**Disposition: CLOSE_SUPERSEDED** (keep only as a historical acceptance record)

> ⚠️ **Do not release the integration build from this PR.** Its candidate JAR (CI run 39439466174, SHA256 `9a5c6b60…d5ca`, built from `663729b8`) and the a89a74c4 storage/HTTP evidence belong to a source tree that is now 331 master commits behind (base `a6ec8bbd`, merge-base `df891d78`). That JAR must not be deployed, promoted, or used as a rollback image. Any release must be built from current master. The PR's own "仍未验" list (real devices, real WeChat callbacks/payments, persistent-target migrations, old-JAR rollback) is still open.

Evidence: the PR touches 1097 files.
- 989 are byte-identical to master.
- 108 differ from master:
  - **67** have the PR head blob present in master's own history. Master took that exact version and has since moved past it, so master is newer.
  - **41** diverged. A line-origin check shows their PR-only lines are pre-refactor or alternative versions of features master already has. For example, the `play/index.js` account guard is on master at `pages/play/index.js:522-624`, the topic report flow at `pages/topic/index/index.js:26-27, 1884`, and `E04_07MySqlIT.legacyNullIdentity…` at :145. The rest are recomputed ledger counts and hashes.
- **No file carries functional content that master lacks.**
- All 14 migrations the PR adds exist on master. 14 are byte-identical. 1 diverges: `migration_topic_completion_coupon_history_receipt_20261016.sql`, where the PR has an older "REVIEW ONLY" text and master `c33aa572` adds a post-DDL verification SELECT. Master's version stands, and editing an existing migration is forbidden by AGENTS.md.

108 differing files by area (OLD = master already contains and supersedes the PR blob; DIVERGED = both sides edited, PR side older or alternative, nothing to port):

| Area | OLD | DIVERGED | Files (DIVERGED in bold) |
|---|---|---|---|
| backend-main | 6 | 6 | ApiCommonController, ApiTemplateController, ChapterFlowCompiler, RefundServiceImpl, TopicSaleFinalizationServiceImpl, WxContentSecurityServiceImpl; **CmsTopicServiceImpl** (moved block only), **CommunityPostServiceImpl** (master has an extra replay/discard guard), **CommunityPostMapper.java/.xml** (master has extra statements), **CmsTopicCompletionCouponRewardMapper.java**, **SmsCouponHistoryMapper.xml** (comment-only) |
| migrations | 0 | 1 | **migration_topic_completion_coupon_history_receipt_20261016.sql** (master newer; do not touch) |
| backend-tests | 11 | 7 | PlayerGrowthConcurrencyMySqlIT, AuditReportLaneMyBatisTest, RedemptionRewardClawbackMyBatisTest, E04_07Test, TopicSaleFinalizationServiceImplTest, CurrentRegistrationStubs, GameplayReskinFixture, ObjectCardMintHookTest, PlayKitPhotoCheckRuntimeTest, TopicCompletionCouponRewardServiceImplTest, ApiCommonUploadSecurityTest; **CommunityPostAuditLevelTest, CommunityPostMapperMyBatisTest, CommunityPostServiceImplTest, E01_12Test, E04_07MySqlIT, NpcCurrentRegistrationMySqlIT, TopicSaleReviewDecouplingTest** (alternative fixtures; master versions present) |
| xcx-src | 27 | 7 | app.js, **app.json (OLD: never restore)**, club/topic-detail wxml, index/index json+wxml, merchant/{apply,citynode,decor,decor/gallery}, roam/index.js, square/detail, templatedetail js+wxml, topic/index wxml, topic/merchantinfo js+wxml, subpackageA/myproject js+wxml, subpackageB/im chat+list, subpackageMember 2 components, subpackageP3 object-cards, utils/{audit-timeline,roam-player-memory,subscribe}.js; **pages/play/index.js, pages/topic/index/index.js, pages/club/detail/index.js+wxml, pages/square/list/index.js, pages/square/utils/community-report-sheet.js, pages/publish/fabu/topic-detail-sheet.wxml** |
| xcx-tests/ledger | 19 | 13 | scripts/fixtures.json, shot-matrix.js, uiaudit/route-matrix.json + 16 unit tests (c01/c06/club-governance/followup-a1/a2/im-foundation/malformed-list/merchant-*/nav-title/roam-recovery/route-matrix-freshness/title-system/v4-club-*); **scripts/uiaudit/action-ledger.json, tests/lint/ui-integrity-baseline.js, tests/unit/{action-ledger, club-detail-ia, club-game-director, club-invite-feedback, club-journey-audit-fix, club-owner-entry-recovery, club-p1-state, club-role-ui, get-auth-matrix, play-free-explore-detail, roam-revocation-evidence}** (recomputed counts/hashes for the integration tree; master has its own) |
| ci | 3 | 6 | funds-mysql-it-gate.sh, real-storage-it-registry.json, test_real_storage_it.py; **.github/workflows/real-storage-it.yml, funds_it_gate.py, test_funds_it_gate.py, migration-dependencies.json, prelaunch-seed-provenance.json, silent-error-allowlist.txt** (master superset or newer) |
| admin-ui | 0 | 1 | **chengyinhub-ui/src/views/business/audit/index.vue** (PR −1 line vs master) |
| docs | 1 | 0 | docs/design/miniprogram-page-cleanup-1009.md |

Full per-file table: `disposition.json → file_lists["394"]` and （本地临时脚本/文件，未入库）. Line-origin detail: （本地临时脚本/文件，未入库）.

Recommendation: close. Point to master as the sole release source. Do not cherry-pick anything, do not restore its app.json, and do not reuse its JAR or ledger counts.

---

## E. E 系列与 10-15 修复（9）

Master = origin/master 063a6c3c (working tree). This was read-only analysis. No tests or CI were run, so every "covered" below means the test exists on master and the PR's product lines are present on master. It does not mean the tests pass.

## How coverage was checked
- For each PR commit (`git log --no-merges <merge_base>..pr/<n>`), every added line of 8+ chars was checked against the master file. When master moved a file, its new path was found by basename (scripts are in `scratchpad/eseries/cov.py`, `agg.py`, `alltests.py`).
- Every `test('…')` / `@Test void x()` added by each PR commit was checked by name against master's copy of that test file.
- Results across all 9 PRs:
  - Only 3 PR test files are absent from master: `b01-refund-account-regression.test.js`, `longrun-e06-06.test.js`, and `TopicLikeExplicitCancelConcurrencyTest.java`. All 3 are replaced or superseded; see below.
  - The 8 test files of #314 are absent from master.
  - Every other added test name exists on master. The two apparent misses (`E03_11Test::absentReferencesPreserveRawAndDoNotExpandOtherSystemVariableScope`, `OWN_C06_29Test::emptyFrozenObject…`) were renamed inside the PR branch itself, and master has the final names.
- Key master port commits:
  - `62a95c3d` (feat: 移植 EXTRA 后端 1e403b13..8822ab38 到 Java21/Boot4 master, i.e. #36 head). It explicitly keeps master's `tryIncrementIssuableReceiveCount` design over EXTRA's `tryReserveIssuableCoupon`, and merges `selectReferences` with master's topic_id projection.
  - `e4b720e9`: deferred E01_10/E03_01/E04_07/E04_13 and B04 tests, added after the base was ready.
  - `90f0afca`: B05 activity-table fixture.
  - Per-ID mini-program ports `7707f14d 8956e00d 276482de 87d34e2e 0dea0348 65b2378a 2d9b221d` (E05) and `8a234835 6fbba138 3303d12c 1f7b19b7 c7d1968b fe9c6ff0 d7e5ca42 209e586d d47e7dcf` (E06).
  - `c82d80e7` (E06-06), `92c90f06` (E05-03/04 refund modal lifecycle), `de039107` (E03-01).
  - Workflow `.github/workflows/mini-followup-regression.yml` is on master (`6fe5b9af`, `c18dec08`).
- All E01–E06 handoff docs (`docs/verification/mini-longrun-20261015/EXTRA6/E0x-NN.md`) and `docs/verification/mini-bug-arch-followups-20261015/` (C00_HANDOFF etc.) are present on master.

Test root abbreviations:
- J = `chengyinhub-admin/src/test/java/com/chengyinhub/`
- L = `J/business/longrun/`
- X = `chengyinhub-xcx/tests/unit/`

---

### #28 fix: 补完回执单调、作者快照和NPC删除并发问题
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence:** head 4a943501, base 1e403b13 (not an ancestor of master). Of the 31 files, 11 are identical to master. The rest differ only by later master evolution:
  - `pages/play/index.js` uses `_sessionKey()` and a revision guard at line 2607.
  - `NpcChatService` uses a `storedOrUnavailable(...,true)` variant and `setTokenIn(null)` (line 734), which is stricter than the PR's 0.
  - The draft util moved to `chengyinhub-xcx/pages/publish/utils/publish/pro-editor-draft.js`, which has `publishAttempt` UNKNOWN (lines 103-105) and member-scoped keys.

| Requirement (commit) | Master coverage |
|---|---|
| Old receipt or projection must not roll back current play state (1865030c) | X`player-game-session-entry-contract.test.js` ("same-session lower projection cannot replace current revision", "confirmed old receipt never lowers an existing newer projection revision") and X`player-game-module.test.js`. All 10 added names present. |
| AI log shows unknown usage as unknown, marked as estimate (5585d228) | `chengyinhub-ui/tests/unit/a02-ailog-usage.test.js`, identical blob |
| Author edit-detail reads one transaction snapshot (7f1fe08e) | J`web/controller/api/B02TopicEditDetailSnapshotMyBatisTest::concurrentReplacementReturnsOneOldSnapshotThenIndependentRequestSeesNewSnapshot`, identical |
| NPC self-delete revokes old requests; late logs cannot recreate data (628492e6) | J`business/service/impl/A02NpcDeleteBarrierMySqlIT::inFlightProviderCannotRecreateDeletedDataOrRepeatPaidRequest` (+7 more); `NpcChatServiceTest::revokedLeaseReturnsPersistentDeletionTombstoneInsteadOfWorkerAnswer` |
| Publish result UNKNOWN keeps the draft and blocks duplicate create (1e8ad8ee, 86524454) | X`b02-publish-write-unknown.test.js`, all 13 names present, e.g. "create commit with lost response blocks retry and survives real storage reload". Also X`pro-editor-draft.test.js`, `fabu-edit-existing-topic.test.js`, `publish-step3-switches.test.js`. |
| 39-item backend gate workflow (d8b0cf8b, 4a943501) | `.github/workflows/mini-followup-regression.yml` on master |

- **Remaining:** none. `ui-integrity-baseline.js` and `action-ledger.json` are gate artifacts that master has regenerated, so they must not be ported.
- **Recommendation:** close as superseded.

### #29 [E01] 排期候补回归与终态回读修复
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence:** every product line is on master (agg: 0 product files below 90%). All added test names are present. Master changed the fixtures of E01_07/12/06/09/18 to match its own behavior (commit 62a95c3d says so for E01_07).

| Requirement | Master coverage |
|---|---|
| E01-01/02/03/04/05/08/15: docs-only counterproofs (反证) | docs present; no code needed |
| E01-06: waitlist cancel vs promotion interleaving | L`E01_06Test::offeredCancellationCommitsBeforeBlockedPromotionWithoutSecondRestoreOrOffer` |
| E01-07: expiry readback keeps concurrent claim terminal state (87684e7b) | L`E01_07Test::claimedBeforeExpiryLockRemainsClaimedWithRegistrationAndNoPayToken` |
| E01-09: claimed registration lifecycle | L`E01_09Test::oldClaimCannotRebindAfterConversionOrTouchNewlyJoinedEntry` |
| E01-10: schedule aggregate read in one snapshot (f411bead) | L`E01_10Test::detailKeepsParentVersionAndChildrenAtFirstSnapshotThenRefreshes` |
| E01-11: read-only audit | doc only |
| E01-12: reject normalized 24:00 (86c2b2b1) | L`E01_12Test::createRejectsSmartMidnightAndMalformedTimesBeforeAnyInsert` |
| E01-13: new waitlist entry reuses activity eligibility and signup deadline (d3468d38, re-landed as 530e7228 in #36) | L`E01_13Test::eligibleSoldOutJoinUsesSameInstantForActivityCheckAndJoinedAt` |
| E01-14: fixed-date replay after day rollover (d4d07d37) | L`E01_14Test::onceOriginalRequestReplaysAfterShanghaiDayRolloverWithoutNewWrites` |
| E01-16: cancel rollback | L`E01_16Test::occurrenceCasZeroRollsBackEarlierRefundAndRoleWrites` |
| E01-17: cancel audience is frozen | L`E01_17Test::filteredDistinctAudiencesRemainFrozenAfterRefundChangesAndWaitlistTermination` |
| E01-18: recheck activity before a new offer (f4b80795) | L`E01_18Test::newOfferRejectsUnavailableActivityUsingExactExistingMaintenancePredicate`; J`business/service/ClubWaitlistPromotionMembershipRaceTransactionTest`, `ClubWaitlistServiceImplTest` |
| Real-DB schedule readback (04e03b95) | J`business/service/B04ScheduleReadbackMySqlIT::assemblyTimesSurviveRealMapperReadbackAcrossJvmZones`, identical |

- **Recommendation:** close.

### #30 [E02] 社区读取快照与隐私回归
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence:** the only partial product file is `CommunityPostMapper.xml`. Master lines 421-434 are a superset: the `ca` alias NOT EXISTS (publish_status=0 / cancel_time) is combined with the topic_id LEFT JOINs. All 5 MySQL fixtures that needed `cms_activity` (E02_01/06/11, E02_25MySqlIT, B05) create it on master.

| Requirement | Master coverage |
|---|---|
| E02-01: detail aggregate snapshot | L`E02_01Test::detailKeepsPublicParentAndReferencesInOneSnapshotDuringPrivateEdit` |
| E02-02/04/05/07/08/09/17/19/21: no-change or counterproof records | docs only |
| E02-03: hide cancelled or unpublished activity cards | L`E02_03Test::cancelledActivityHidesOnlyItsCardAndPreservesBodyAndStoredSnapshot`, `::offlineActivityHidesOnlyItsCardAndPreservesBodyAndStoredSnapshot`, `::activeCardKeepsHistoricalProjectionDespiteTargetEditsOrRegistrationChanges`, `::unrelatedTypesMissingTargetsNullStatesAndOriginalRowGatesRemainUnchanged` |
| E02-06: comments snapshot | L`E02_06Test::commentsKeepParentAuthorizationAndThreadRowsInOneSnapshot` |
| E02-10: revisions snapshot | L`E02_10Test::revisionsUseTheAuthorizedParentSnapshotDuringConcurrentAuthorEdit` |
| E02-11: feed snapshot | L`E02_11Test::feedKeepsAuthorizedRowsAndReferencesInOneSnapshotDuringPrivateEdit` |
| E02-12: POI options online state | L`E02_12Test::poiOptionsRequireExplicitOnlineStateLikeSaveSnapshot` |
| E02-13: stale review task vs content version | BLOCKED report only (0e95dbd2); nothing to port |
| E02-14: recheck membership before publishing a draft | L`E02_14Test::publishingExistingCommunityDraftRechecksMembershipBeforeSafetyOrWrites` |
| E02-15: delete rollback | L`E02_15Test::exceptionAfterRealMediaOrphanRollsBackLifecycleVersionAndRetention` |
| E02-16: notification ownership | L`E02_16Test::foreignNotificationIdCannotMarkReadOrCreateCommandReceipt` |
| E02-18: governance affected rows | L`E02_18Test::existingGovernanceProjectionUpdateCompletesOutboxWithoutRetry` |
| E02-20: atomic nullable patch | L`E02_20Test::concurrentDisjointPatchesPreserveBothChangesOnExistingRow` |
| E02-22: comment-like immutable receipt | L`E02_22Test::oldLikeReplayAfterUnlikeKeepsTheActionRemovedAndDoesNotNotifyAgain` |
| E02-23: legacy square bookmark mapping | L`E02_23Test::resolverRequiresOldRowAuthorExactRequestAndServerMarker`; J`web/controller/api/ApiCreativeSquareCommunityPolicyTest` |
| E02-24: comment replay bound to parent | L`E02_24Test::fastReplayToAnotherPositiveParentIsConflictBeforeAnyWrite` |
| E02-25: duplicate-key current read | L`E02_25Test::duplicateRecoveryUsesCurrentWinnerForTheSameRequest`, L`E02_25MySqlIT::exactWinnerIsRecoveredAfterTheLosingTransactionEstablishedRepeatableRead` |
| Real-DB privacy (7fd6855e) | J`business/service/B05CommunityVisibilityMySqlIT::deletedOldDetailAndRevisionReadsRejectBothIdentities` |

- **Recommendation:** close. E02-13 is a report-only dependency and needs no port.

### #32 [E04] 券生命周期回归与真实调用链审计
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence:**
  - 7e2ccb51 (`tryReserveIssuableCoupon`) was deliberately not ported. Master's `SmsCouponMapper.xml:222-230` `tryIncrementIssuableReceiveCount` checks status not in (3,4), enable/start/end window and stock, which is a superset of the PR's check (62a95c3d message).
  - `chengyinhub-admin/sql/migration_topic_completion_coupon_history_receipt_20261016.sql` is on master and adds `history_id`. Only its comments differ.
  - The clawback current read (fbb7b363) is on master and covered by E04_13.

| Requirement | Master coverage |
|---|---|
| E04-01/04/05/08/12/15: counterproofs or existing coverage | docs only |
| E04-02: per-member limit scope | BLOCKED owner-policy report, no code |
| E04-03: current stop status blocks issue | L`E04_03Test::stopCommittedAfterLimitedSnapshotPreventsNewHistoryAndStockMutation`, using master's mapper |
| E04-06: lock reverse association | L`E04_06Test::administrativeAssociationEditWaitsForReverseHistoryLockAndCanRetryAfterCommit` |
| E04-07 A/B: unknown original completion coupon only audits; exact receipt saved | L`E04_07Test::missingOriginalIdentityOnlyAuditsAndKeepsCouponAndGrantUnchanged`, L`E04_07MySqlIT::newCompletionBindsActualHistoryAndDuplicateCompletionDoesNotIssueAgain`, J`business/it/RedemptionRewardClawbackMyBatisTest`, `TopicCompletionCouponRewardServiceImplTest` |
| E04-09: admin history and statistics in one transaction | L`E04_09Test::statisticalUpdateConstraintFailureRollsBackPreviouslyInsertedAdministrativeHistory` |
| E04-10: invalidation gate during redeem | L`E04_10Test::invalidationCommittedAfterTemplateReadRollsBackClaimCountAndSettlement` |
| E04-11: bulk expiry scope | L`E04_11Test::bulkExpiryPreservesOtherMemberDeletedUndatedFutureAndVoidedRowsAndIsIdempotent` |
| E04-13: points clawback current balance | L`E04_13Test::committedBalanceIncreaseAfterRrSnapshotUsesCurrent105To95Ledger`, identical |
| E04-14: lock redemption identity | L`E04_14Test::administrativeIdentityEditWaitsForRedeemHistoryLockAndCanRetryAfterCommit` |
| Real-DB lifecycle (495e5b51) | J`business/service/B03CouponLifecycleMySqlIT::concurrentSameSourceReturnsOneOriginalHistoryAndConsumesOneStock`, identical |

- **Blocker (deploy, not port):** the `history_id` migration must be applied to the production DB with owner approval before the backend that depends on it is deployed. Follow the chengyin-db-migrate flow; never connect to production directly.
- **Recommendation:** close.

### #33 [E05] 退款确认与回包账号代次隔离
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence:**
  - Product lines are on master. Exception: `scene-member-order-history/index.js` is at 83% because master re-implemented E05-09 in 87d34e2e. `longrun-e05-09.test.js` is identical on master.
  - Master later changed E05-03 policy in 92c90f06: the test is now named "E05-03 confirmation rejection prevents writes; refund proceeds without subscription opt-in".

| Requirement | Master coverage |
|---|---|
| E05-01: three unique subscribe template IDs | X`longrun-e05-01.test.js`, identical |
| E05-02/05/08/11/16: counterproofs | docs only |
| E05-03: order-history refund account and component generation | X`longrun-e05-03.test.js` ("E05-03 old account completion cannot release B cancellation resources" etc.) |
| E05-04: participation refund bound to account and record | X`longrun-e05-04.test.js` ("E05-04 old completion cannot release new account request") |
| E05-06: favorites keep committed page after failed load | X`longrun-e05-06.test.js` |
| E05-07: confirmed removals survive in-flight lists | X`longrun-e05-07.test.js` |
| E05-09: history unmount generation | X`longrun-e05-09.test.js`, identical |
| E05-10: detail bound to live account | X`longrun-e05-10.test.js`, identical |
| E05-12: return refresh queued | X`longrun-e05-12.test.js` |
| E05-13: ignore inherited template keys | X`longrun-e05-13.test.js`, identical |
| E05-14: malformed frozen encounter actions | L`E05_14Test::presentInvalidPlayablesRejectInsteadOfExposingLateLiveActions` |
| E05-15: UTF-16 share truncation | L`E05_15Test::crossingFortyAndSixteenUnitBoundariesDropsOnlyTheSplitPair` |
| E05-17: encounter uses current session | L`E05_17Test::encounterUsesNewRunningFreezeInsteadOfCompletedDefaultProjection`; J`web/controller/api/ApiPlayEncounterControllerTest` |
| "未验收" failure-first file `b01-refund-account-regression.test.js` (53b4aadc) | NOT on master. Equivalent coverage: X`longrun-e05-03/04` and X`b01-lifecycle-account.test.js` ("topic unlike removes stable ID after a refresh reorders rows", "favorites old account list and unlike callbacks cannot change the new account"). Its exact cases (refund dispatch before late subscription; A topic confirmation cannot send under B) are UNVERIFIED against master. |

- **Recommendation:** close. Optionally port `b01-refund-account-regression.test.js` as test-only after adapting it to master's E05-03 policy (92c90f06). This is low priority.

### #34 [E06] 定位取消身份与常亮失败恢复
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence:**
  - `audio-session.js` and `poller.js` moved to `chengyinhub-xcx/pages/play/utils/`, and 100% of the PR's added lines are there.
  - `last-location.js` and `location-manager.js` are identical.
  - `bg-tracker.js` was re-ported per ID (1f7b19b7, c7d1968b, 359f8e73, fe9c6ff0, d7e5ca42, 209e586d). Line overlap is only 30%, but `longrun-e06-09/10/11/12/13` are identical blobs.

| Requirement | Master coverage |
|---|---|
| E06-01: duplicate audio registration is idempotent | X`longrun-e06-01.test.js` |
| E06-02/04/07/14/15/16: no change needed | docs only |
| E06-03: keep-awake failure recoverable | X`longrun-e06-03.test.js` ("failed enable retries only on a later owner operation…") |
| E06-05: poller delays | X`longrun-e06-05.test.js` |
| E06-06: coercible missing coordinates | X`last-location-recovery.test.js` has the same 4 names ("valid rejects coercible missing and nonnumeric types in either coordinate", …) plus 2 more. The PR's `longrun-e06-06.test.js` is not needed. |
| E06-08: cancellable acquisition identity | X`longrun-e06-08.test.js` |
| E06-09: startup throw | X`longrun-e06-09.test.js` |
| E06-10: consumer exception isolation | X`longrun-e06-10.test.js` |
| E06-11: replaced failure identity | X`longrun-e06-11.test.js` |
| E06-12: stop completion error propagation | X`longrun-e06-12.test.js` |
| E06-13: picked coordinate types | X`longrun-e06-13.test.js` |
| E06-17: transient share read stays retryable over HTTP 503 | L`E06_17Test::queryTimeoutUsesHttp503WithExistingErrorShapeAndKeepsCauseOutOfResponse` |
| Platform lifecycle regression (8a1f0176) | X`b01-platform-lifecycle-regression.test.js` |

- **Recommendation:** close.

### #36 fix: hide cancelled or unpublished activity reference cards
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence:**
  - The backend was ported wholesale by `62a95c3d` (range 1e403b13..8822ab38 = this PR's head). Deferred items landed later in `e4b720e9` and `42c9d511`; the B05 fixture in `90f0afca`. The mini-program side came through #309 and the per-ID ports.
  - The headline fix is `CommunityPostMapper.xml` selectReferences NOT EXISTS (master lines 429-433), with L`E02_03Test`, 4 methods (see #30).
  - Across all 183 commits, every added test name is present on master, except the two mini-program files covered above (`b01-refund-account-regression`, `longrun-e06-06`).
  - Other bundled fixes are present:
    - 0ee9a998 stage settlement frozen-node gate: L`E03_04Test`
    - 8822ab38 (#287) `freezeEnding(...,session)`: line identical on master; L`E03_01Test::fourArgumentsUseFrozenBodyAndProjectConditionsWithoutReadingLiveChapter`
    - a9ad53ae: E04_07MySqlIT merchant fixture
    - 923170ab, dedb44d5, 13be9976, 1bfa81a5, 530e7228: same as #32, #30 and #29
- **Remaining:** coupon reserve, superseded by master design (see #32).
- **Recommendation:** close as superseded by 62a95c3d.

### #44 fix: 小程序现有bug与共享后端修复（协作者输入与取消幂等）
- **Disposition: CLOSE_SUPERSEDED**
- **Evidence (stacked on #28 head 4a943501; 207 commits):**
  - **Collaborator input (adf59791):** `assertCollaboratorIdShape` is on master at `chengyinhub-system/.../CmsTopicServiceImpl.java:4091` (create), `:4655` (FULL update) and `:5981`. Tests: L`OWN_C03_14Test::createRejectsNullZeroNegativeBeforeAnyAggregateWrite`, `::fullUpdateRejectsNullZeroNegativeBeforeAnyAggregateWrite`, `::createPreservesPositiveDuplicatesAndTheirOrder` (+7).
  - **Cancel idempotency (e0e3f517):** superseded. Master's `ApiTopicController.topicLike` (lines 1422-1443) is desired-state: `cmsTopicLikeService.setLiked(memberId, topicId, desired==1)` always succeeds for type=0, and blank type means like instead of a legacy toggle. Tests:
    - J`web/controller/api/ApiTopicLikeExistenceTest::cancellationResponseLossRetryKeepsTheDesiredFalseStateAndRejectsInvalidType`
    - J`business/service/TopicLikeDesiredStateMySqlIT::concurrentRepeatRequestsHaveOneLikeAndCancelOnlyItsOwner`
    - J`web/controller/api/TopicLikeRetryDirectionV2Test::repeatedLikeOnlyEnsuresAndRepeatedUnlikeOnlyDeletes`
    - The PR's `TopicLikeExplicitCancelConcurrencyTest` targets the removed `rows==0` branch, so it must not be ported.
  - **Content-security 400 (OWN-C03-21/24, 6f5f43b7, d7507d6d):** master moved the check out of the controller (`TopicContentSafetyTestFixtures`). L`OWN_C03_21Test::titleRejectionIsKnownBeforeWrite` and `OWN_C03_24Test` still assert 400 with no service write.
  - **Everything else:** all OWN-C01..C06 slices (backend and mini-program) have their added test names on master under L`OWN_C0x_NNTest` and X`longrun-own-c0x-*.test.js`. The only product files below 90% line overlap are the two that master refactored:
    - `pages/play/index.js` (89%): master uses `_sessionKey()` in place of inline `JSON.stringify(this._sessionParams())`.
    - `pro-editor-draft.js`: the active copy is `pages/publish/utils/publish/pro-editor-draft.js`, a superset with `operationScope`.
- **Not covered by me:** a line-by-line review of all 410 files. Only the automated name and line checks above were done. 410 files, of which docs make up much.
- **Recommendation:** close as superseded.

### #314 test: PR #69 小程序修复第三轮定向验收（V3）
- **Disposition: PORT_REMAINING_DIFF** (test-only)
- **Evidence:** base b90b44b2 (#69 head), 1 commit d74c53d1, 8 added files. None exist on master:
  - `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/Pr69V3PhotoCheckCrossAccountTest.java`
  - `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/Pr69V3UploadRejectRetryTest.java`
  - `chengyinhub-xcx/tests/unit/pr69-v3-cl{03-template-reject-reason,11-nickname-propagation,18-badge-wall-guest-copy,24-home-privacy-gate-text,25-consent-login-failure-resubmit,26-home-location-revoke-inflight}.test.js`
- The fixes under test exist on master:
  - `mytemplate.wxml:72-80` reason/marketReason
  - `utils/template-market.js:18` 个人审核未通过
  - `app.js:434` showPrivacyGate and `pages/index/index.wxml:312` cy-privacy-gate
  - `consent-client.js:43` intentCurrent
  - `pages/index/index.js:1521` REVOKE early return
  - `AppMediaReferenceGuard.java:28` `owned()`
  - `baoming.js` `_hostShareConsentReady`
  - `gerenziliao.js` setNickname
- All Java imports resolve on master, and every file path the Node tests read exists.
- The existing tests it cites are on master: `ApiTemplateMerchantOperatorScopeTest::ownerPendingReadDoesNotExpandRemovedRejectedUnlistedOrUnknownStates`, `ApiCommonUploadSecurityTest::localSingleRejectedImageReturnsHttp400WithoutWritingOrReturningUrl`, `AppMediaReferenceGuardTest::approvedAppPhotosAndAudioCanOnlyBeProcessedByTheirUploader`, `NodeNpcServiceTest::managerCannotEnrollOwnersApprovedAudioThroughOwnerNodeScope`, and the listed X files. One exception: `privacy-submit-state-contract` "隐私页两种进入状态首屏只用一句用途说明" was reworded on master after the #293 copy change (36dabfb8).

| Item | Status on master | Action |
|---|---|---|
| CL-03, CL-11, CL-18, CL-25, CL-26, CL-40, CL-41 new tests | fixes present; tests missing | port the test files as-is, then run `node --test` / `mvn -pl chengyinhub-admin -am -Dtest=Pr69V3*`. UNVERIFIED until run. |
| CL-24 home privacy text test | gate present; copy simplified by #293 | port, but expect to adapt the "only one sentence" text assertion to master copy |
| CL-39 provider runtime / CL-24 native popup | NEEDS_RUNTIME (device/provider) | out of scope |

- **Recommendation:** port the 8 test files onto a feature branch from master in a test-only PR, adjust CL-24 if it fails, then close #314.

## F. 10-14 底层整合（9）— 按当前消费方拆分

Master = 063a6c3c. Everything below is read-only analysis. I ran no CI or tests. Test names are the classes/files that exist on master.
Rule applied: split each PR by **current consumer**. Never restore an old package, an old `chengyinhub-xcx/app.json`, or old ledgers/baselines (`scripts/uiaudit/action-ledger.json`, `tests/lint/ui-integrity-baseline.js`, `docs/verification/mini-foundation-*`). These derived files and docs are excluded from every "missing" list (DO_NOT_PORT, regenerate on master if needed).

Lineage (to avoid double counting): #7 (B), #8 (A) and #9 (C) all target `codex/mini-foundation-cloud-handoff-20261013` (e67e72a7, **not** an ancestor of master). #11 (0b301c9b) = squash 231f201e of A+B+C plus integration fixes, plus 2 self-play-pass commits. Blob check: 69/73 of #7's files, 88/97 of #8's files and 85/99 of #9's files equal #11's blob. The differing ones are #11's later integration edits, so #11 supersedes #7/#8/#9. #12, #14 and #15 target #11's branch. All residuals are counted once, under #11.

Master ports found by subject/patch: 231f201e→**bc05d9cb** (2026-10-19, 200 of #11's 317 files; 123 blobs byte-equal, 77 adapted); 67bfe34f→5fbcd4ed; 0b301c9b→db505c94; all 6 #11 migrations are byte-identical on master (`chengyinhub-admin/sql/migration_20261013_b_im_notification_intent.sql`, `migration_20261013_roam_{badge_delivery,reward_delivery,session_record_archive}.sql`, `migration_ai_quota_reservation_recovery_20261013.sql`, `migration_mini_foundation_coupon_source_grant_20261013.sql`).

---

### #3 fix: 修补小程序对账、消息、草稿、同意记录和NPC有效期
**Disposition: PORT_REMAINING_DIFF** (one small backend item left)

Evidence: the base `feature/public-ci-bootstrap` fafde987 is an ancestor of master. The PR's commits appear on master under the same subjects: 0984189e→f1341d06, 9c5d9034→f2b2c7c2, 806abb8f→b44d43df (all 2026-10-19). The commit 55cd5573 is **not** on master.

| Sub-item | Current consumer on master | Status |
|---|---|---|
| Paying-order reconcile keeps progress across page errors (`OrderCloseTask`) | `chengyinhub-system/.../task/OrderCloseTask.java` | covered (f1341d06). Test `OrderClosePayingReconcileTaskContractTest` is blob-identical |
| IM list: stale response must not restore a deleted/muted row | `chengyinhub-xcx/subpackageB/pages/im/list/index.js` | covered (f1341d06, later edits). Test `tests/unit/im-list-mutation-race.test.js` |
| Normal draft save writes chapter/site order | `CmsTopicServiceImpl` | covered. `TopicDraftSubmittedOrderTest` is identical |
| Expired self-play pass cannot start new NPC text/voice chat; history export kept | `ApiAiNpcController` | covered (f2b2c7c2/b44d43df). `ApiAiNpcPassExpiryTest`, `ApiAiNpcVoiceChatFormatTest` (identical), `NpcCurrentRegistrationMySqlIT` |
| Invalid conversation cannot update read state | `ImServiceImpl.markRead` → `ImConversationMemberMapper.xml#markReadVisible` (`deleted = 0` + cutoff exists) | covered by a later rewrite. `ImLegacyReadLockTest`/`ImLegacyReadLockMySqlIT` |
| **Duplicate consent `requestId` must match the original event (member/doc/version/scene/scope/eventType), otherwise 409** (55cd5573) | `ConsentRecordServiceImpl.record` still does `catch (DuplicateKeyException ignored) { return 0; }` (master L51-53). `consent_record.request_id` is **globally** unique (`uk_consent_request`) | **partial**. `ApiComplianceController` (L91-113) fails closed through `byRequestId(memberId, requestId)` + `matchesWrite` → "授权状态回读失败" (tests `ApiComplianceControllerTest#zeroRowsWithoutExactUuidReadbackNeverReportsAgreementRecorded`, `#homeCityConsentWithoutMatchingReadbackFailsClosed`). **Missing**: `ApiAgreementController` L73 (`app_launch` AGREE) ignores the result and returns `success()` even when the requestId belongs to another member's/doc's event. `ApiRoamController` L664 treats 0 rows as an error (OK). Minimal fix: in `ConsentRecordServiceImpl.record`, on duplicate, re-read by requestId and compare to the original event (port of 55cd5573 + `ConsentRecordIdempotencyMySqlIT`), or do a readback in `ApiAgreementController.agree` |
| Action-ledger test speed + line numbers (0f0c32f1) | derived ledger | DO_NOT_PORT (regenerate) |

Recommendation: close #3 after a small new PR from master ports only 55cd5573's service-level original-event check (with `ConsentRecordServiceImplTest` cases + `ConsentRecordIdempotencyMySqlIT`).

### #7 fix: 小程序B包消息隐私、原通知收据及事件广场可靠性
**Disposition: CLOSE_SUPERSEDED** (by #11 → master bc05d9cb + 93532510)

| Sub-item | Current consumer | Status |
|---|---|---|
| IM original-request readback, send-before-identity persistence, account/credential isolation, monotonic readTo | `subpackageB/pages/im/chat/index.js`, `utils/im-send-recovery.js` | covered. Master 93532510 (2026-10-21 "恢复IM原请求回读与历史路线隐私保护") is a superset (`_viewEpoch`/`_messageRequestEpoch` guards; `im-send-recovery` exports `epoch, begin, current, complete, pending, abandon, invalidate`). Tests `im-recovery-source-b.test.js`, `im-foundation-recovery-b.test.js`, `im-send-retry.test.js` |
| History route card / summary privacy projection | `ImServiceImpl` | covered. `ImRouteProjectionTest`, `ImFoundationReadRecoveryTest`, `ImFoundationBMybatisIntegrationTest` |
| Durable original-signup notification intent + single delivery receipt | `ISystemNotificationIntentService` (impl `ImServiceImpl`), `ImNotificationDeliveryTask`, `ImNotificationIntentMapper.xml`, migration identical | infrastructure covered. The producer is A-side (see #11 residual R3) |
| Analytics queue accepted-batch ack / persistence / account isolation; creativesquare dedupe | `utils/analytics.js`, `pages/square/list/index.js` | covered (#11 blobs ported; square/list 18/18 added lines present) |
| `shezhi.js` logout delta | `pages/shezhi/shezhi.js` | see #11 R2 |

### #8 fix: A包原订单票务资格、维护与资金恢复（待运行验证）
**Disposition: CLOSE_SUPERSEDED** (by #11). The A residuals are tracked once, under #11 R1/R3/R4.

| Sub-item | Current consumer | Status |
|---|---|---|
| Original-source coupon grant | `CouponSourceGrantMapper`, migration identical | covered. `CouponSourceGrantTest` |
| Explore-ticket new use rejected after the activity ends / end time missing | `ApiPlay*`, `ExploreRedeem*` | covered. `ExploreRedeemRecordTest`, `ApiPlayCurrentUseBoundaryTest` |
| Merchant subscription renew (ON DUPLICATE=2 is success) | `MerchantCommerceService.java` L147-151 (comment "移植自 PR #8 mini-foundation-a") | covered. `MerchantCommerceServiceTest` |
| Waitlist scan / TeamGroup paying / OfficialEvent capacity | `ClubWaitlistServiceImpl`, `TeamGroupServiceImpl`, `OfficialEvent*` | covered via bc05d9cb (+58c77694). `ClubWaitlistServiceImplTest`, `OfficialEventContractTest` |
| Registration callback through the inbox fence + inbox identity conflict | `CmsRegistrationServiceImpl.handleWechatPayCallback` | **missing** → #11 R1 |
| Cash-withdraw failure recovery in one transaction | `CashWithdrawServiceImpl` | covered by a different later design → #11 R4 |
| Signup notification as a durable intent | `CmsRegistrationServiceImpl.registerPaymentAfterCommit` | different design → #11 R3 |

### #9 fix: C包AI原日补偿与漫游玩法恢复（含暂停迁移）
**Disposition: CLOSE_SUPERSEDED** (by #11 → bc05d9cb, f40b4f23)

| Sub-item | Current consumer | Status |
|---|---|---|
| AI daily quota reservation / REFUND_DUE / UNCERTAIN | `AiQuotaReservationMapper`, `AiQuotaReservationRecord`, migration identical | covered. `AiQuotaRecoveryMySqlRedisIT` |
| Roam revision clears dirty only after persisting; archive of finished records | `RoamSessionWriteService`, `ApiRoamController#archiveRecords` (L496), `pages/roam/index.js` `_archiveFinishedRecords` (L1717) | covered (later rewrite). `RoamSessionArchiveTest`, `RoamSessionWriteMybatisIntegrationTest` |
| Reward/badge delivery with cursor | `RoamRewardDeliveryService`, `RoamBadgeDeliveryService` | covered. `RoamRewardDeliveryTest`, `RoamBadgeDeliveryTest` |
| NPC lease/expiry gate; WeRun padding | `NpcChatService`, `WeRunDecryptor` | covered. `NpcChatServiceTest`, `WeRunDecryptorTest` |
| Free registration keeps qualification; current-registration recheck | `CurrentPlayRegistration` | covered. `CurrentPlayRegistrationTest`, `SelfPlayPassIdempotencyTest` |
| Roam cache epoch | `utils/roam-cache-epoch.js` (master f40b4f23) | covered. The logout call site is still missing → #11 R2 |

### #11 feat: 小程序底层A/B/C集成（CI全绿候选，draft）
**Disposition: SPLIT_PORT_SUBITEMS** (the bulk is on master as bc05d9cb/5fbcd4ed/db505c94; 2 real residuals, 2 owner decisions)

Of #11's 317 files, 122 were not in bc05d9cb. 13 of those are identical on master, 66 are only on the PR (docs/verification plus 6 Java/JS files), 43 differ. Checking added lines file by file gives the table below.

| # | Sub-item | Current consumer on master | Status |
|---|---|---|---|
| R1 | **Payment callback inbox fence.** `WxPaymentCallbackInboxServiceImpl.register` must throw when the notify_hash already exists with a different bizType/bizId/eventType. `handleWechatPayCallback` must `register`, then `process(notifyId, mutation)` before locking/reading the registration. `TeamGroupServiceImpl` L~876 then calls `applyConfirmedWechatPaymentWithinTransaction` | `WxPaymentCallbackInboxServiceImpl.java` L58 (`return mapper.insertIgnore(candidate) == 1;`, unchanged since a20fb9b6). `CmsRegistrationServiceImpl.java` L2101-2104 (register only, result ignored, no `process` fence) | **missing** (funds, defense in depth: master still relies on the CAS in `completePaymentSuccess`/`claimRegistrationRefundedByGateway`). Minimal port: identity-conflict check in `register` + fence in `handleWechatPayCallback` + an in-transaction variant for `TeamGroupServiceImpl`. Tests to port: `PaymentCallbackFenceEntryTest` (not on master) and the 3-line delta of `WxPaymentCallbackInboxServiceImplTest`. Needs funds MySQL IT rerun. **Ask-first (fund path)** |
| R2 | **B×C: logout invalidates the roam cache epoch** so late roam callbacks cannot rewrite pre-logout unsynced tiles | `pages/shezhi/shezhi.js` `onDangerConfirm.clearLocalLogin` (L299-312) calls `sendRecovery.invalidate()` but **not** `invalidateRoamCache(owner)`. `pages/roam/index.js` L55 already subscribes `onRoamCacheInvalidated` | **missing**. Minimal fix: in `clearLocalLogin`, take `roamOwner = app.getUserID()` before clearing `user_id` and call `invalidateRoamCache(roamOwner)`. Port `tests/unit/logout-roam-cache-epoch-integration.test.js` (not on master) |
| R3 | First-signup notification as a durable same-transaction intent (`RegistrationSignupNotificationService` → `ISystemNotificationIntentService.enqueue`) | Master sends the signup IM in `CmsRegistrationServiceImpl.registerPaymentAfterCommit` (L2494-2510) via `CommittedBusinessNotice.dispatchInNewTransaction("registration-signup", …)`: after commit, in-memory scheduler, alarm on failure, **not durable**. `enqueue` has **no producer** on master; `ImNotificationDeliveryTask` drains an empty table | **KEEP_OPEN_OWNER_DECISION**. Either wire `enqueue` in `CmsRegistrationServiceImpl` (one consumer) or accept the after-commit design and later drop the unused intent task. Do not port the A service wholesale |
| R4 | Cash-withdraw failure recovery (`CashWithdrawFailureRecoveryService.prepare/recover`) | `CashWithdrawServiceImpl.refundFailedClaim` (L338-350, TransactionTemplate: CAS 3→2 + `addBalance` atomic) + `noteManualIfStillPending` | covered by a later master design (90a8c136, ed682117). Only gap: master does not check the row count of the CW/CD identity `updateMemberCashWithdraw(idc)` (L140-145). Low risk because `syncOne` recomputes `CW+id`. DO_NOT_PORT the service |
| — | IM `markReadTo` (monotonic client readTo) | `ImServiceImpl.markRead` L1118-1135 uses server-side latest visible + `markReadVisible` | superseded (later rewrite) |
| — | `pages/roam/index.js` (86/111 lines), `subpackageB/pages/im/chat/index.js` (62/109) | later master rewrites (f40b4f23, 93532510) | superseded (master superset) |
| — | `baoming.js`, `square/list`, `roam-tile-memory-sync.js` | — | all added lines present |
| — | Self-play pass valid within the current run (2 commits) | `ApiPlayProgressController`, `TopicRouteRuntimeServiceImpl` | covered (5fbcd4ed, db505c94) |
| — | `docs/verification/mini-foundation-cloud/**`, action-ledger, ui-integrity-baseline, silent-error allowlists, migration-dependencies | derived | DO_NOT_PORT |

### #12 fix: 后端缺陷 B1 定位精度闸 / B4 主题点赞方向 / B5 俱乐部徽章泄漏
**Disposition: CLOSE_SUPERSEDED**

| Sub-item | Consumer on master | Status |
|---|---|---|
| B1 GPS accuracy gate (>80 m) | `ApiPlayProgressController.java` L2064, L2374 `GEOFENCE_ACCURACY_METERS = 80d` (125d5521) | covered. `ApiPlayGpsGeofenceValidationTest` (blob-identical, L306), `ApiPlayRouteContractTest` identical |
| B4 like direction by `type` | `ApiTopicController.topicLike` L1424-1442 | covered by a **later decision**: missing `type` now means like (1), no toggle (CL-21/F-R2S-11, a2360954). Test `TopicLikeRetryDirectionV2Test#repeatedLikeOnlyEnsuresAndRepeatedUnlikeOnlyDeletes`. #12's "missing type keeps toggle" and `ApiTopicLikeDirectionTest` = **DO_NOT_PORT** |
| B5 platform badge wall excludes club badges + own club badges restored | `GrowthBadgeMapper.xml`, `BadgeReadModelServiceImpl` (3bb4b26f, 4d2a41cf) | covered. All files blob-identical (`GrowthBadgeMapperXmlTest`, `BadgeReadModelServiceImplTest`) |

### #14 fix: 任务2 外壳/商家/俱乐部修复
**Disposition: CLOSE_SUPERSEDED** (master d1d1516c, 35838140, 428305e1/e11eb8e4)

| Sub-item | Consumer on master | Status |
|---|---|---|
| Decor page back guard | `pages/merchant/decor/index.{js,wxml}` (`onNativeBackGuard`) | covered (d1d1516c). `tests/unit/merchant-decor-back-guard-contract.test.js` |
| media-preview with `current` | `utils/media-preview.js`; used by `pages/merchant/decor/index.js:6`, `pages/publish/activity/index.js:2` | covered (#414) |
| Location fallback (last success → fixed coordinates) | `utils/location/last-location.js` used by `pages/roam/index.js:85`, `subpackageRoam/nearby/index.js:10` | covered for roam. **searchmap/square parts = DO_NOT_PORT**: master `pages/searchmap/index.js` L576-579 deliberately sends no nearby query without real location, so a fake fixed coordinate conflicts with that later rewrite |
| wx:key `*this` → `index` | e.g. `pages/topic/merchantinfo/merchantinfo.wxml:165` (`sysCategoryList` object array, the real defect) | covered (35838140). Remaining `*this` are on string arrays (harmless) |
| Check-in text code fallback | `pages/merchant/utils/code-text.js`, `pages/merchant/components/cy/code-text`, `pages/merchant/citynode`, `pages/topic/utils/code-text.js`, `pages/topic/merchantinfo/merchantinfo.js` | covered (d1d1516c, files relocated). `club/group-code` intentionally not done (CU-C-78) |
| Club pinned notice | `pages/club/components/club-pinned-notice` | covered (35838140) |
| Claim existing store sheet | `pages/merchant/components/merchant-claim-sheet`, `pages/merchant/utils/merchant-claim.js` | covered (d1d1516c) |
| B9 refund subscription (skipped by the PR itself) | `scene-member-order-detail` etc. | not a PR item. Still not wired on master (`refundRejected` only defined in `utils/subscribe.js`). Optional separate follow-up |

### #15 feat: 任务3 漫游与功能入口
**Disposition: CLOSE_SUPERSEDED** (master f40b4f23, 7448f645, d1d1516c)

| Sub-item | Consumer on master | Status |
|---|---|---|
| `ROAM_POI_RADIUS_M` constant | `utils/roam-poi-filter.js` / `pages/roam/index.js` | covered (f40b4f23) |
| Continue-last-roam bar | `pages/index/index.json:17` → `subpackageRoam/components/scene-roam-resume-bar`, `pages/index/index.wxml:111` | covered |
| Official routes list/detail (read-only preset, roam = product_type 2) | `subpackageRoam/official-routes`, `subpackageRoam/official-route` | covered (7448f645). No D-18 conflict |
| Stamp album "received" tab | `components/cy/scene-roam-stamp-album/index.js:68` | covered |
| Clear my NPC chat | `subpackageMember/components/cy/npc-chat/index.js:214` | covered |
| Report team | `subpackageMember/components/cy/team-report-sheet` | covered (d1d1516c, relocated from `pages/team/report-sheet`) |
| Points history | `subpackageMember/points-history` (app.json:257), `profile/index.js:1527 goPointsHistory` | covered |
| Topic state keys | `pages/publish/fabu/*` | covered (d1d1516c) |
| `tests/unit/roam-features-task3.test.js` | — | not on master. Per-feature tests exist under other names; UNVERIFIED that every assertion is covered. Low value |

### #17 fix: 首页定位独立同意与撤回并发保护，补齐注销清理
**Disposition: CLOSE_SUPERSEDED**

Evidence: the base `feature/prelaunch-remaining-20261013` 4ba7bd1c is an ancestor of master. 8534daba→**e2cd334a** and b1f66dbf→**4add8d98** are on master with identical subjects and identical per-file line counts (only ledger/baseline differ). c2f43d0c is just the merge with #10 (derived conflicts).

| Sub-item | Consumer on master | Status |
|---|---|---|
| Home auto-location: separate purpose consent; withdraw-epoch guards late requests | `pages/index/index.js` L119-141, L1568 (`_locationEpoch`/`_locationLoadingEpoch`), `utils/compliance/consent-client.js`, `RoamLocationConsentService` (identical) | covered. `tests/unit/home-location-scope-epoch.test.js`, `consent-client.test.js`, `ApiComplianceControllerTest` (identical) |
| Shorter privacy prompt | `pages/privacy/index.{js,wxml}` | covered (4add8d98, then 36dabfb8) |
| Deregistration cleanup when the account is missing/deleted | `AccountDeregistrationServiceImpl.java` L294, L299 (`deleteMemberAddresses`) | covered. `AccountDeregistrationCompletionPersistenceTest` (identical) |

## G. 文档/计划（6）— 一律 REFERENCE_PRESERVED

这 6 个 PR 只作历史参考保留。不合入，不改写，也不把其中的旧证据当作今天的结论。

| PR | 内容 | 与 master 的关系 | 处置与注意 |
|---|---|---|---|
| #2 明确公开仓库开发与 VPS 停止 CI 策略 | 只改 `AGENTS.md` 1 个文件，在文件顶部加了 3 段英文 | master 的 `AGENTS.md` 没有这几段 | REFERENCE_PRESERVED，**不要合入**。原因有两个：① 正文写死了个人本机绝对路径（`/Users/<user>/Downloads/hengyin`），公开仓不应收录；② 其中“后端测试与 MySQL/Redis 只在测试 VPS 上跑”的规定已被现行做法取代，现在 Java/MySQL/Redis 测试走 GitHub-hosted CI。如需保留“不得在 VPS 注册 self-hosted runner”这一原则，应由 owner 另写一份新稿 |
| #16 小程序 bug 与共用后端架构 Cloud 并行任务 | 98 个文件：`docs/plans/mini-codex-cloud-20261015/**`（91）和 `docs/verification/mini-backend-validation-20261015/**`（7） | master 上全都没有 | REFERENCE_PRESERVED。这是 10-15 那一轮的任务卡和签收记录（例如“签收 605 完整隔离建库与权限互斥读回”），只作历史 |
| #21 Claude 小程序 UI 与新功能对账及任务卡（CL1–CL5） | 名义上是 docs，但 base 已过时（`codex/mini-bug-arch-integration-20261015`），三点 diff 里有 377 个文件，其中包含 Claude 集成分支的 BL-* 代码合并 | 152 个文件 blob 与 master 一致；72 个 PR 版本在 master 历史中出现过（master 后来又改了）；128 个 PR 版本从未进过 master，其中 44 个是测试；25 个路径 master 上已不存在。PR 自带台账 `docs/plans/mini-claude-ui-20261015/TASKS.md` 的收尾一节写明 BL-230–254 已逐个移植到移植分支，并经 #293 进入 master（master 上有 28 个提交消息引用 BL-23x/24x/25x） | REFERENCE_PRESERVED。**不得合入**：会把旧 `chengyinhub-xcx/app.json` 和旧页面整包带回。台账自列的遗留项：BL-255（#372，现已不在 open 列表）、`pages/publish/fabu/index.js:6821`（归 Codex）、#35（CL6，见 H 组）。这些遗留项是否已在 master 上逐项落地，本 lane 只核到提交消息层面，记为 **UNVERIFIED** |
| #24 VPS 复测 231 项通过并保留失败修复与资源证据 | 65 个文件：`docs/verification/mini-bug-arch-20261015/**` | master 上全都没有 | REFERENCE_PRESERVED。**这 231 项是 2026-10-15～10-18 在旧 VPS、旧提交（如 `69c281af`、`6c6f82d7`、`2ac55d20`、`0d63043a`）上的历史回执，不是新的测试结果**，不得改标为当前 master 的验收证据。当前 master 的覆盖情况以 E 组列出的回归测试和 GitHub CI 为准 |
| #26 追加六路 Cloud 精确接收启动包 | 2 个文件：`docs/plans/mini-extra-cloud-20261015/**` | master 上没有 | REFERENCE_PRESERVED。它对应的 E01–E06 工作已在 master 上，见 E 组 |
| #70 第三 Cloud 统计日期与成长并发独立开发包 | 4 个文件：`docs/plans/mini-forward-cloud-20261015/**` | master 上没有 | REFERENCE_PRESERVED。它的实现 PR #72、#294 已被 master 吸收，见 C 组 |

## H. 大载体与未完成功能（6）— T-4

Master = `origin/master` 063a6c3c. #334, #335, #336 and #338 are each **one commit** on top of `integrate/master-port-20261019@a53883f1`. That base is an ancestor of master; master has 645 more commits. Each commit is a per-file 3-way merge of private work, and the conflict markers were left in on purpose ("冲突标记待云端解决"). #35 is 4 commits on `claude/mini-ui-features-20261015@0a1a6acf`.

**File-count cross-check.** Local count is `git diff --no-renames --name-status <merge_base> pr/<n>`. API count is `mcp__github__pull_request_read get → changed_files`.

| PR | local files | API changed_files | identical to master | differ | master changed same path after fork | files with conflict markers (head) | PR body states |
|---|---|---|---|---|---|---|---|
| #334 | 194 | 194 | 3 | 191 | 40 | 20 | 20 |
| #335 | 340 | 340 | 36 | 304 | 127 (6 are `A` paths that master added independently) | 32 | 32 |
| #336 | 160 | 160 | 0 | 160 | 39 | 57 | 57 |
| #338 | 143 | 143 | 0 | 143 | 26 | 29 | 29 |
| #35 | 7 | 7 | 0 | 7 | 0 | 0 | – |

The full per-file lists are in `files_334.tsv`, `files_335.tsv`, `files_336.tsv`, `files_338.tsv` and `files_35.tsv`, all in this scratchpad. Columns: `status, path, identical_to_master, master_newer`. `master_newer=yes` means master's blob differs from the merge-base blob, so master edited the path after the fork. `no` means master never touched it, which makes the PR hunk wholly absent from master. `na` means the file is identical to master.

**Line coverage.** Also in this scratchpad: `cov_<n>.tsv`. For each file it gives the share of the PR's added lines (≥12 characters, conflict markers excluded) that exist verbatim in master's file. Results:
- #334: 0 of 191 differing files reach 0.9 coverage. The release-candidate backend is essentially absent from master.
- #335: 5 of 304 differing files reach 0.9. These match the files that master's 10-21 "*-recovery" PRs (#413–#453) re-implemented.
- #336: 0 of 160 reach 0.9. However, many #336 conflict hunks show the `public` side (= master, unchanged since the fork) already carrying an equivalent fix. Details below.
- #338: 0 of 143 reach 0.9.

No master commit message references any of the carriers' F/X/M/N codes (checked against `git log a53883f1..origin/master`). Master did recover parts of the mini-program work independently, under different names: #416 #417 #421–#444 #450–#453 (mini) and #405/#406/#407 (refund/roam/IM).

---

### #334 port: 携带未迁出的私有工作 — 10-13 发布候选（后端+后台）
**Disposition: SPLIT_PORT_SUBITEMS.** Port the fixes below as small PRs on master. Leave the features to an owner decision. Do not merge the carrier: it is draft, `mergeable_state=dirty`, has 20 conflict files, and is 645 commits behind.

**Evidence.**
- Head `81382f28`.
- Source is private `feature/w2-release-without-cloud22-20261013@8472ebae7b`. The source commit list is in `docs/plans/stranded-port-20261019/cand-be/PORT_NOTES.md` on `pr/334`.
- Owner comment 6278445658: #334 and #336 both add a callback branch for "type 17 = merchant NPC". **On master, 17 is already `BIZ_TYPE_COMMUNITY_POST`** (`IWxContentSecurityService.java:113`, `WxContentCallbackInboxMapper.xml:91`). The new type must be renumbered (for example 18). It must not be ported as 17.

**Missing fixes (master lacks them).** Line numbers refer to master.

| # | Area | File (master) | What is wrong on master | Why it matters | Minimal port | PR tests to bring |
|---|---|---|---|---|---|---|
| F1 | data integrity / identity (X036) | `chengyinhub-common/.../IdCardUtils.java:13`; mini `chengyinhub-xcx/utils/form-state.js:15` (the mini fix is in #335) | The `CHECK_CODES` table has 10 entries and is missing the trailing `'2'`. When `sum%11==10` the code throws `ArrayIndexOutOfBoundsException` (HTTP 500) and the front end rejects the number. | About 1 in 11 legitimate ID numbers can never register for publisher identity. | Add `'2'` in both places. Also fix the test helper `IdCardUtilsTest.java:68`. | `IdCardUtilsTest.校验位为2的号码合法且不越界` |
| F2 | security / visibility (F0504) | `ApiProductController.java:93-99` | The detail endpoint returns products that are off-shelf (`publish_status≠1`), deleted, or belong to disabled or closed merchants. It returns the whole `PmsProduct` entity, including audit fields, and `detailHtml` unfiltered. | Leaks unpublished goods and admin fields. Disabled merchants' goods remain reachable. | Add a gate on publish/del/merchant status/account_status. Return a whitelisted map and pass `detailHtml` through `HTMLFilter`. #336 has a smaller gate with only the first condition. Take #334's version. | `ApiProductDetailBoundaryTest` (#334), `Batch1AProductVisibilityTest` (#336) |
| F3 | privacy / IDOR-style forgery (F0098/F0119) | `ApiClubController.java:1800`, `:1858-1866`, `:1869-1876` | (a) A club post can cite any `refType/refId`. The API does not check that the poster actually joined that activity or topic, or that the target is public. The public post list then projects the target's title and cover. (b) `/post/history` and `/post/comment/list` skip `canViewClub`. | Anyone can forge "my play record" cards for others' activities. Titles and covers of deleted or members-only topics leak. A members-only club's history and comments are readable by direct link. | New `ClubPostReferenceGuard` (requireCitable / isPubliclyVisible), `CmsRegistrationMapper.countSucceededJoin`, `ClubPost.refUnavailable`, plus a `canViewClub` check in history and comment list. For the `postList` conflict hunk, keep master's side. | `ClubPostReferenceGuardTest`, `ApiClubPostPrivacyProjectionTest` |
| F4 | privacy (F0412) | `ImServiceImpl.java:737-745` | Route-card sending is gated only on `selectCmsTopicById` + `user_status==1`. Deleted, unreviewed and members-only topics pass. The stored summary is `"[路线] "+topic.getName()`. | The topic title is persisted into conversation lists and notifications and cannot be retracted. | Use `cmsTopicMapper.selectCmsTopicDetailById(topicId,null)`. Store `ROUTE_CARD_SUMMARY="[路线]"`. Apply `redactRouteSummary` on read. In the conflict hunk (conversation list), keep master's `selectVisiblePage`/`projectRouteMessage` side. Check that it also redacts legacy summaries (UNVERIFIED). | `ImServiceImplTest` (route-card cases) |
| F5 | content safety (F0225/F0435/F0436) | `WxContentCallbackInboxMapper.xml:76-94`; `ApiMerchantController.java:846`; `ApiUmsMemberController` profile save | (a) `lockBusinessTarget` has no `bizType==16` branch. `NodeNpcService.java:158` submits type 16, so the callback throws "callback target missing" (`WxContentCallbackMutationService.java:41`). A risky node-NPC avatar is never taken down. (b) The merchant NPC avatar is submitted as `MERCHANT_PROFILE`/merchantId, so its hash never matches and a violating avatar is ignored. (c) The member contact QR (`wechat`) is neither submitted nor cleared. New case pics and QR images are not checked for upload ownership. | Unsafe images stay public. Other members' OSS objects can be attached. | Add `<when bizType==16> … npc_profile … scope_type=4 for update`. Add a new merchant-NPC type with a **free number, not 17**, plus `rejectMerchantNpcByContentSecurity` and an `isCurrentSubmittedMedia` case. Add per-field `clearRiskyMemberProfileMedia`, `UmsMemberMapper.clearRiskyProfileMedia`, and `verifyNewProfileMediaOwnership`. Keep master's 13/15/17 branches. | `WxContentSecurityServiceImplTest`, `ApiMerchantNpcSaveTest`, `ApiUmsMemberProfileMediaPrivacyTest` |
| F6 | data integrity (F0258) | `CmsActivityServiceImpl.java:894-904` | Saving activity collaborators writes `setMemberId(memberId)` (the publisher) instead of `collaboratorId`. Update never rewrites collaborators. | Every activity "lineup" shows the publisher N times. Edits cannot add or remove co-hosts. This is also the data that #35-style co-host displays depend on. | Add `saveActivityCollaborators` (dedupe, check the member exists, check the insert result). Add `deleteActivityCollaboratorsByOwner`, whose WHERE has `exists cms_activity a.member_id=#{owner}`; this satisfies the AGENTS owner rule. | `ActivityCollaboratorSaveTest`, `ActivityCollaboratorScopeMyBatisTest` |
| F7 | funds / data race (F0122) | `ApiClubLeadController.java:260-267` | Check-then-write: `countOpenSaleRegistrations` is checked, then `updateCmsActivity` runs unconditionally. Rows are not checked. The response says "已更新并报备发起人" although nothing is pushed. | A first sale racing with a time or place change lets the meeting time change after money is taken. | Add `CmsActivityMapper.updateActivityOpsIfUnsold` (conditional UPDATE with `not exists cms_registration payment_status in (0,1,2)`). Return an error when rows≠1. Change the receipt to `notification:"NOT_SENT"`. | `ClubLeadLeaderResolutionTest`, `GameSessionRuntimeServiceImplTest` |
| F8 | funds / reward integrity (C1 sensor games) | `ApiPlayProgressController` node completion (~L2950); `AdvancedGameRuntimeServiceImpl` | Node rewards are FULL (points, coupons, drops) even when completion rests only on device-reported sensor scores (reaction, ballShake, quietHold, shout, stopwatch, compass), the photo-check fallback pass, or a static node scan code. There is no `attemptId`, so a stale or abandoned attempt can still be settled. | Forging a client payload earns points and coupons. | Add `IAdvancedGameRuntimeService.rewardCappedToXpBadge` (default false), `untrustedCompletionEvidence`, `requireCurrentAttempt`, and the kit `evidence/rewardCeiling` fields. Downgrade to `RewardPolicy.XP_BADGE_ONLY`. **This changes reward economics: owner confirmation needed** (it was in the owner-approved 10-13 RC). | `SensorGameTrustBoundaryTest`, `AdvancedGameRuntimeServiceImplTest` |
| F9 | data integrity / audit (X028/X030/X031/X032/X008) | `GameSessionRuntimeServiceImpl.java:433`, ~`:452`, export (~`:716`), `:2381` | (a) RUN_PAUSE does not freeze the zero-HP or challenge timers. Cancel, interrupt and staff-exit run the survival sync and can write an HP outcome. (b) ARRIVAL_REVOKE succeeds even when there is no arrival and leaves no audit. (c) STAFF_TEAM_EXIT can exit an already-exited team and overwrite its receipt. (d) The replay export includes the one-time arrival `nonce` inside manual signatures. (e) The clue list uses `intValue()!=1`, which hides clues whose value is >1. | Wrong outcomes while paused, an unaudited host action, and leaked one-time credentials. | `survivalTime` / `resumeFrozenTimers` / `saveOwnerWithoutSurvival`, a revoke guard plus `arrivalRevocations` audit, an exited-team guard, stripping the nonce on export, and `doubleValue()<1`. F0129 directed broadcast in the same diff is a feature and optional. | `SharedRunHostControlTest`, `GameSessionRuntimeServiceImplTest` |
| F10 | audit integrity (F0529, plus part of F0528) | `AuditTaskServiceImpl.java:736-756`; `AuditWorkbenchController` decision path | For non-versioned review types, a late submission from the pre-escalation level can still decide an ESCALATED task. Moderation side effects (delete post or comment) are `catch(Exception ignore)` and are reported as done. | A stale reviewer can override an escalated case. A failed takedown looks like it was applied. | Small: the F0529 version guard (`ERROR_REVIEW_STALE_LEVEL`). Larger, optional: F0528 `ModerationOutcome` plus `/moderation/replay`. Resolve the 6-marker conflict in `AuditTaskServiceImpl` against master's newer `applyModeration`. | `AuditEscalationLevelTest`, `AuditModerationOutcomeTest`, `AuditTaskAppealFlowTest` |
| F11 | gameplay integrity (X008) | `TopicRouteEngine` (no `TOOL_CLUE`) | Clue evidence (`clue.h_*`) can be revoked by later SET or INC effects. | Players lose evidence they already earned. | A 4-line monotonic guard. | `TopicRouteEngineTest` |
| F12 | input validation (F0554) | `UserPermissionController` | The role quota endpoint accepts a null id and negative or huge `maxThemes/maxEvents/maxCoupons`. | Corrupt quota rows. | Add an id check and a 0–99 range check. | `UserPermissionControllerTest` |
| F13 | funds display (F0423, backend half) | missing `/api/withdrawal/summary` (no `WithdrawalSummaryService` on master) | The mini withdraw form shows and pre-fills the **total** balance (see #335). | The "全部提现" amount includes frozen bounty and complaint-cooling funds. The backend re-validates, per the code comment (UNVERIFIED). | Port `WithdrawalSummaryService` + `ApiWithdrawalController` summary. Pinning `consentVersion` (`migration_withdrawal_consent_version_20261012.sql`) is a compliance feature: owner decision. | `WithdrawalSummaryServiceTest` |

**Features in #334 that are not fixes (KEEP_OPEN_OWNER_DECISION):**
- F0572 coupon rule governance: versions, whitelist, stop-issue vs platform-invalidate, plus migration `migration_c2_coupon_rule_version_20261012.sql`.
- F0535 order payment facts: `OmsOrderPaymentCheck`, migration.
- F0575 platform redemption records.
- X036 self-service identity re-bind: `MemberIdentityChangeService`, migration, PII typeHandler. **Needs a privacy review.**
- X034 group-reception readback and revoke before settlement.
- M0117/F0384 roam share per-token revoke and version CAS, plus migration.
- F0395 growth-mission evidence source.
- X039 club compensation empty-state reason.
- N003 author trial and the F0551/F0578/F0579/F0513 admin read-state UIs (analytics, config, notice, banner, dict, world, preset).

Do not apply the migrations (`chengyinhub-admin/sql/migration_*_20261012.sql`) to any database. They need `ci/migration-dependencies.json` and ledger registration on master.

---

### #335 port: 携带未迁出的私有工作 — 10-13 发布候选（小程序）
**Disposition: SPLIT_PORT_SUBITEMS.** Master already recovered a large share through the 10-21 recovery PRs. Port only the remaining mini fixes, each paired with its backend item from #334.

**Covered by master.** Files byte-identical, or the same intent re-implemented:
- M0154/M0155/M0156 shared a11y and motion preference (#417, #428). `utils/motion-preference.js` and `tests/lint/shared-a11y.js` are identical.
- F0263/F0264 story media duplicate upload (#416).
- F0013 home editorial (#429).
- F0498 club local draft (#430; `utils/club-edit-draft.js` was added independently).
- F0466 route road vs straight-line (#435).
- F0494 participation voucher (#426).
- F0502 + M0070 POI or verification unknown-result (#424, #433).
- Reaction, ball-shake and badge-3D recovery (#432, #437, #440).
- F0385 location revoke (#439, re-implemented without `utils/location/roam-consent.js`).
- `_unlikeRequests` (F0018) exists on master in one file. This is a partial recovery, UNVERIFIED for completeness.

**Missing fixes (mini).**

| # | File (master) | What | Why | Port |
|---|---|---|---|---|
| M1 | `utils/form-state.js:15` | `ID_CARD_CHECK_CODES='10X9876543'` is missing `'2'` | Pair of F1. Valid IDs are rejected on the client. | 1 character |
| M2 | `subpackageA/pages/assetcenter/earnings/index.js:255` | `loadTxBalance` reads `/api/user/info` balance (total) | Funds UX: "全部提现" pre-fills an unavailable amount | Switch to `/api/withdrawal/summary` `available` with strict money regex. Needs F13. Also `components/cy/withdraw-channel/*` (feature) |
| M3 | `utils/discover-search.js:118-119` | `Number(null)===0`, so `maxP=0<1000` turns on a ¥0 ceiling when no price is chosen (F0004) | Search silently drops every paid topic or activity | Treat null or '' as NaN. `rowPrice` rejects blank, boolean and negative values |
| M4 | `pages/play/components/playkit-{quiethold,shout,compass,stopwatch,photocheck,scan}` + `pages/play/utils/sensor-contract.js` (new) | The client still labels the photo fallback as "验收通过". The static scan is not labelled as a task-code match. Sensor results are invalid after backgrounding. | Pair of F8. Honest UI. Without it F8's `rewardCeiling` is invisible. | Port together with F8 |
| M5 | `pages/play/components/playkit-album/index.js` + play page (X019) | Retrying the same image after a network or 5xx failure. 4xx requires reselecting. | Duplicate submits or stuck uploads | Small |
| M6 | `pages/club/detail/index.js` (F0098 `refUnavailable` degrade), `pages/gerenziliao/gerenziliao.js` (F0434/F0436: no city field, QR under review), `pages/club/topic-detail/director.js` + `pages/club/utils/generic-director-ops.js` (X028–X031 pause, exit, non-repeatable confirm), `pages/publish/activity/index.js` (F0258 collaborator prefill and removal) | Client halves of F3, F5, F6, F9 | Port with each backend item | – |

**Features: KEEP_OPEN_OWNER_DECISION.**
- `subpackageMember/identity-change/*` (X036).
- `pages/merchant/group-reception/*` + `utils/group-reception.js` (X034).
- `pages/shezhi/components/post-governance/*` (F0521).
- `utils/registered-navigation.js`.
- roam share revoke and redaction (`utils/roam-share-snapshot.js`, `utils/roam-route-privacy.js`).
- NPC chat unknown-state (F0229/F0231/F0228; `components/cy/npc-chat` diff is 39 lines and UNVERIFIED against master).

**C00-reserved hunks.** These are under `docs/plans/stranded-port-20261019/cand-xcx/c00/` (app.json, action-ledger, route-matrix and others). **Do not apply `app.json` wholesale** (BRIEF rule). Re-derive only the route lines that a ported page actually needs.

---

### #336 port: 携带未迁出的私有工作 — Codex batch1-cloud
**Disposition: SPLIT_PORT_SUBITEMS.** Master already has its own equivalent of most of the conflicted backlog code. Port the small remaining set.

**Covered by master.** In these conflict hunks the `public` side is master's current code, unchanged since the fork:
- Refund reconcile stale-payout paging (`RefundReconcileTask` `selectStalePayoutScanUpperBound/selectStalePayoutProcessingAfter`).
- Order-close unpaid paging (`OrderCloseTask` `getExpiredUnpaidScanUpperBound`).
- Waitlist maintenance cursors (`ClubWaitlistServiceImpl.maintain`, `selectWaitlistMaintenanceMaxId/Page`).
- Merchant subscription end boundary (`MerchantSubscriptionMapper.xml`, same predicate).
- AI quota refund by reservation (`AiGatewayService.java:68 refundQuota(memberId,bizType,reservation)`).
- IM `markRead(member,conv,readTo)` (`IImService.java:41`).
- Text check fails closed on an unknown verdict (master already maps non-pass/non-risky to `REVIEW_REQUIRED`).

**Missing fixes.**

| # | File (master) | What | Why | Port |
|---|---|---|---|---|
| B1 | `task/RegistrationAutoVerifyTask.java:34-48` + `CmsRegistrationMapper.xml:923-942` | The task scans `order by id limit 200` with no cursor | Head-of-line blocking: if the first 200 expired tickets cannot be closed (CAS skip, refund in flight), later tickets are never auto-verified | Cursor plus a fixed upper bound (`registrationScanUpperBound`, `listExpiredUnverifiedIds(afterId)`). `RegistrationAutoVerifyTaskTest` |
| B2 | `UmsMemberCartServiceImpl.java:328` / `ApiCartController.orderSettlement` | Points checkout has no requestId receipt. `insertOmsOrder` and `insertOmsOrderItem` results are unchecked. | Points funds: retry after an unknown result cannot be reconciled, and a silent insert failure still deducts | `CartSettlementReceipt` + receipt readback. The migration must go to `chengyinhub-admin/sql/` (the PR puts it under root `sql/`, which on master only holds `base_*`/`quartz.sql`) and be registered in the ledger. `CartSettlementReceiptMySqlIT`, `CartSettlementTransactionalH2Test` |
| B3 | `ApiProductController.java:93`, `ApiMerchantController.java:846`, `WxContentCallbackInboxMapper.xml` | Same as #334 F2/F5 | – | Take #334's versions. **Do not** take #336's `BIZ_TYPE_MERCHANT_NPC=17` or `when bizType==16 or bizType==17 → npc_profile` (17 is community_post on master) |

**Features: KEEP_OPEN_OWNER_DECISION.**
- IM incremental sync and own-message readback (`listMessagesAfter`, `findOwnMessage`, `selectAfterPage`: absent on master; #407 did a different recovery).
- NPC chat request intent hash and lease (`migration_20261012_bottomc_npc_chat_intent.sql`).
- Notification delivery receipts (`NotificationDeliveryReceipt*`, 3 migrations).
- Typed text check (`WxTextCheckResult`).
- Roam-hangout expiry.
- `.github/workflows/aws-runner-smoke.yml` (CI infra, owner).

The VPS relay tooling was excluded by the author, which is good. The C00 hunks for `app.js` and `action-ledger.json` are under `batch1/c00/`.

---

### #338 port: 携带未迁出的私有工作 — questify 平台集成（后台/CI/小程序）
**Disposition: KEEP_OPEN_OWNER_DECISION.** Some sub-items are DO_NOT_PORT; see the table.

**Evidence.**
- Its backend counterpart #337 was **closed 2026-10-21**: "Superseded by draft #398 (questify backend re-carry)".
- Master has no questify backend: no CityBoard, PrivateHome, Rendezvous or MeetingConsent classes, and no such migrations.
- The admin pages and CI gates here (`city-board-drafts`, `private-home-mysql-*`, `w16/w17` meeting gates, `npc_draft`, `coupon_command`) call or test backend code that exists only in #398. They cannot be ported standalone.
- The admin `request.js`/`login.js`/`user.js` audit-context fencing is infrastructure for the questify audit candidate console. It is not a standalone fix.
- Mini parts (theme runtime/backpack, result poster, journey grant editors, saved-check) build on master's theme runtime (`migration_theme_runtime_20261004.sql` exists). They have 24–39 conflict markers in `pages/play/index.js` and `pages/publish/fabu/index.js`/`theme-runtime.js`.

| Sub-item | Disposition | Reason |
|---|---|---|
| City board drafts (`chengyinhub-ui/src/views/business/city-board-drafts`, `tests/e2e/city-board/*`, `VUE_APP_CITY_BOARD_DRAFTS` route) | **DO_NOT_PORT** (unless the owner re-scopes) | A resident "城市棋盘" layer is a standalone game/RPG system layer, which is on the not-doing list |
| w16 meeting / rendezvous gates (`ci/w16_mysql_gate.py`, `scripts/verify-w16-meeting.sh`) | **DO_NOT_PORT** pending owner | Stranger meet-up handoff borders on friends/social, which is on the not-doing list. Its backend is in #398 |
| Workshop / mod paid licences (backend in #398) | **DO_NOT_PORT** | This is the merchant plugin catalog |
| Private-home (私人据点) gates, NPC draft gate, coupon-command gate, audit candidate console + browser e2e, template choice editor (`membertemplate/index.vue`) | KEEP_OPEN_OWNER_DECISION | Unfinished questify features. Decide together with #398 |
| Theme runtime mini (backpack HP, result poster, FIRST identity, journey grants, saved-check) | KEEP_OPEN_OWNER_DECISION | Check against the "gacha/combat/gear" exclusion: the backpack plus HP is close to gear/combat |
| `ci/*` funds-gate / migration-ledger edits, `.gitleaks.toml` | Do not port as-is | They conflict with master's newer gates (`master_newer=yes`) |
| Small candidate fixes: `components/cy/sheet/index.js` (ignore close while `stacked`), `scripts/lib/xcx-scan.js` (resolve exported factory mixins, `ed87463816`) | Optional cherry-picks | Low risk. UNVERIFIED whether master's scan already handles mixins |

No fund or security fix was found in #338 that master lacks. The questify backend fixes listed in the source commits (`75a40b8dd8`/`b590eb6885` invalid registration principals, `27c2bedae3`/`f859cd6e26`/`13ef25a068` template republish moderation, `d275497b1c`, `f19e82eb68`) are **not in this carrier**. They belong to #398 (other agent).

---

### #35 feat: CL6 联合主办展示组件与参与方视图模型（草稿，待读接口补字段）
**Disposition: KEEP_OPEN_OWNER_DECISION.** The PR is blocked on a read-API contract that the owner or Codex has not decided.

**Evidence.**
- 7 files, all new except `tests/unit/unused-component-registry.test.js` (+3 lines). None of them is on master.
- Master `official-detail/index.wxml:159-162` still has the `CL6-SLOT` comment ("当前 detail/eventBase 不返回 parties").
- The supervisor verdict on the PR was "OK". The only red CI items were failures already present on its base branch.

**What the component needs vs what master returns.** The public read API is `GET /api/official/events/{id}` → `OfficialEventV2ServiceImpl.detail` (`:574-610`) → `eventBase` (`:2123-2147`).

| Field the component or patch reads | Master public detail | Where it exists on master |
|---|---|---|
| `parties[]` | **absent** | Only in `draftInternal` (`:346-355`) and `activity360Internal` (`:674`), reachable only from the admin `@PreAuthorize` endpoints or from `draftForOperator` (operator-gated) |
| `parties[].id, partyType, partyId, role, status, statusAt, invitedAt` | absent publicly | `OfficialEventParty.java:32-55` has them all |
| `parties[].displayName` | **absent everywhere** | Not a column. A name join exists only in `selectPartyInboxForMember` (`OfficialEventV2Mapper.xml:230-232`, left join `mms_merchant` and `club`) |
| `parties[].avatar` | **absent everywhere** | Same as above: needs a join on `ums_member` avatar, `mms_merchant` logo and `club` logo |
| `viewerCanManage` | **absent** | Logic exists as `canManage`/`isManageParty` (`:368-405`); the PR proposes `organizerEntry` (publish-check) as a stopgap |
| `acceptedAt` (for stable ordering) | **absent** | `statusAt` is overwritten by `activateAcceptedParties` |
| Visibility filter (non-manager sees only ACCEPTED/ACTIVE/COMPLETED) | n/a | Must be implemented server-side. Internal fields (`responsibilitySummary`, `contactRole`, `snapshotJson`, `statusReason`, `organizerAuthority`) must not be sent |

**Activity and topic co-host data on master.**
- Activity detail `ApiActivityController.java:1214-1224` and topic detail `ApiTopicController.java:1817-1826` return `collaboratorsList` as `PublicCollaboratorVO{id, memberId, memberNickname, memberAvatar, memberRealName}`. This has no role or status.
- The topic editor read (`:884-905`) returns only `collaboratorIds`.
- So the CMS activity "co-host" list exists, but its data is wrong because of #334 F6 (every row is the publisher).
- Separately, and not from any PR: `PublicCollaboratorVO` exposes `memberRealName` on public details. This is a possible privacy issue. Whether the field holds a legal real name is UNVERIFIED.

**Recommendation.** Decide the N02 read contract: add the `parties` + `viewerCanManage` fields to `detail`, with displayName and avatar joins and the visibility filter. Port F6 first. Then merge #35 and apply its coordinator patch to `official-detail`.

---

### Not covered / limits
- I read the hunk-level diffs for every #334 backend main-code file and the key #336 funds and security files.
- #335 mini files were checked by intent and grep against master, not line by line. Items marked UNVERIFIED need a closer diff.
- Test files and UI tests in all carriers were not individually reviewed.
- No build or test was run by me. Test names above are the PRs' own test classes.

### #398 port: questify 后端重新携带（v2，基于今日移植分支；17 个设计层冲突待 owner 决定）

**Disposition: KEEP_OPEN_OWNER_DECISION**. Do not merge #398 as it stands. The narrow fixes in it (F1–F9) can be ported separately as SPLIT_PORT_SUBITEMS, and the workshop/marketplace parts are DO_NOT_PORT.

#### Evidence (baseline)
- head `pr/398` = `0127e3dc12509114289ef164536f83a3a9617265`. It is one commit, by "Bob (OpenClaw Agent)", dated 2026-10-20.
- base `integrate/master-port-20261019` = merge_base `417ff2146245765d63335a331f49e3ea3a8b9931`, which is an ancestor of master `063a6c3c`.
- Master has **361 commits** that #398 does not have. The head is not an ancestor of master.
- Files: `git diff --no-renames --name-status 417ff214 pr/398` gives **754**, which matches GitHub `changed_files=754` (mcp `pull_request_read get`). That is +69001/−962 lines, `mergeable_state=dirty`, draft.
  - A = 573. All are absent on master.
  - M = 181. All exist on master, and **none** are byte-identical to master. Full list: `disposition.json → file_lists["398"]`.
- Source: the private `feature/questify-platform-integration` `57b22916e0` (base `5e23cd5228`). It replaces #337's carrier.
- The conflict list comes from the PR body and the commit message. There is no conflict doc in the PR. The only "conflict" filenames are `ApiPlayRouteConflictHttpContractTest.java` and `ContentCaptureReviewConflictException.java`, and neither is a doc.
- **There are 17 files that still contain literal `<<<<<<< port / ======= / >>>>>>> questify` markers**, so the PR **cannot compile**. I verified this by scanning every file at `pr/398` with `^(<<<<<<<|>>>>>>>)`. Each file is one of the 17 conflicts.
- In each conflict, the "port" side is the master lineage: master still has the port side at the lines cited below.
- Other PR contents:
  - 34 new `.sql` files: 27 under `chengyinhub-admin/sql/`, 7 test fixtures.
  - New dependencies: `jackson-dataformat-cbor` (chengyinhub-system/pom.xml) and `bouncycastle.version 1.86` (root pom). These are in AGENTS.md's Ask-first list.
  - Framework changes:
    - `DruidConfig` → `PrivateHomeDruidDataSource`
    - `logback.xml` → `PrivateHomeLogFilter`/`PrivateHomeLogAppender` wrapping all root appenders. This is a cross-cutting change and also Ask-first.
  - `.gitleaks.toml` adds 2 allowlist paths for test fixtures.

#### The 17 design-layer conflicts

| id | file (pr/398 marker lines) | topic | current master behaviour | carried by #398 (questify side) | minimal option | funds impact | recommendation |
|---|---|---|---|---|---|---|---|
| C01 | `chengyinhub-system/.../service/impl/TopicCompletionCouponRewardServiceImpl.java` L51-60 | **Completion-coupon issuance receipt identity** | `TopicCompletionCouponRewardServiceImpl.java:39-48` does the following in one transaction:<br>1. `insertIgnore` the log.<br>2. `historyId = smsCouponHistoryService.grantCouponWithReceipt(member, coupon)`.<br>3. `rewardMapper.bindHistoryId(topic, member, coupon, historyId)`, which writes `cms_topic_completion_coupon_reward_log.history_id` (`CmsTopicCompletionCouponRewardMapper.xml:12-16`, `... and history_id is null`).<br>Migration: `migration_topic_completion_coupon_history_receipt_20261016.sql`. **Key = `reward_log.history_id` (column on the log row).** | 1. Schema probe `receiptProtocol.protocolInstalled()` (`TopicCompletionCouponHistoryResolver`, JDBC metadata).<br>2. If the receipt profile is installed: `TopicCompletionCouponReceiptService.grantForNewCompletion`, which appends to a **separate table `cms_topic_completion_coupon_receipt(grant_log_id PK, history_id UNIQUE, granted_at)`** and sets `reward_log.receipt_contract_version=1` (`migration_topic_completion_coupon_receipt_draft_20261005.sql`, `..._marker_draft_20261005.sql`, both "DRAFT ONLY").<br>3. Otherwise: plain `grantCouponById` with **no receipt at all**.<br>**Key = receipt row keyed by `grant_log_id`.** If the questify side is taken, the `historyId` used below the marker is undefined, so it does not compile. | Keep master's `history_id` column as the single source of truth and drop the questify receipt table and marker. If the questify read API (`ApiTopicCompletionCouponReceiptController`) is wanted, rebase it on `reward_log.history_id`. | **high**: two competing receipt sources. The questify fallback path issues coupons with no recorded identity, which breaks exact clawback. | Owner: choose master. Do not run the 2 questify DRAFT migrations. |
| C02 | `.../service/impl/RedemptionRewardClawbackService.java` L272-295 | **Clawback history lookup on refund** | `RedemptionRewardClawbackService.java:269-282`:<br>1. `selectCompletionRewardHistoryId(member, topic)` (`SmsCouponHistoryMapper.xml:188-195`, a join on `l.history_id` that requires same member/coupon and `del_flag=0`).<br>2. `selectSmsCouponHistoryByIdForUpdate`.<br>3. Re-check id/member/coupon/delFlag. On a mismatch it returns `UNKNOWN_IDENTITY_NOT_RECOVERED` (audit only, no guessing). | 1. `completionCouponReceipts.currentHistoryForClawback(...)` (receipt table, throws on any mismatch).<br>2. Falls back to the master query when it returns null.<br>3. After the lock, it **drops the member/coupon/delFlag re-check** and only tests `history == null → NOT_FOUND`. | Keep master's lookup and guards. Only if the owner adopts C01-questify, add the receipt lookup *before* the master query and keep master's post-lock identity checks. | **high**: if the post-lock ownership re-check is dropped, the wrong coupon could be voided. Result code semantics also change (`NOT_FOUND` vs `UNKNOWN_IDENTITY_NOT_RECOVERED`). | Owner: master. |
| C03 | `.../service/impl/WechatPayServiceImpl.java` L729-740 | **W18 workshop payment callback routing** | `WechatPayServiceImpl.java:719-742`. After SDK signature verification, it routes in order:<br>1. `CLM` → club-membership<br>2. `merchantCommerceService.supportsOutTradeNo` → merchant-commerce<br>3. `DEP` → coop-deposit<br>4. anything else → `registrationService.handleWechatPayCallback`<br>Every non-registration route sets `route` so that a FAIL raises an alarm (L746-753). **There is no W18 route.** | Adds a first branch `otn.startsWith("W18_")` → `WorkshopPaidNotificationRoute.dispatch(transaction, workshopPaidPaymentHandler.getIfAvailable())`. It fails closed if the handler is absent, but **does not set `route`**. A W18 FAIL is therefore logged as `route=registration`, and the alarm is **skipped** (`if (!"registration".equals(route))`). The questify text also loses `route = "club-membership"` for CLM. | Do not add W18. If the owner ever approves workshop, add `route="workshop-paid"`. W18 orders cannot be created on master today: no prepay path exists, so they cannot reach the callback. | **high** if merged: silent unalarmed paid-order failures, plus a new paid product line. | **DO_NOT_PORT**: the W18 workshop is the paid merchant module/plugin marketplace (`business/marketplace/paid|free|reservation|editor|contract`, 5 `ApiWorkshopPaid*Controller`s, `migration_w18_paid_*`), which is on the not-doing list ("merchant plugin catalog"). |
| C04 | `.../service/impl/CmsTopicServiceImpl.java` L2880-2963 | Topic publish-draft (snapshot freeze at publish) | `CmsTopicServiceImpl.java:2864-2885` covers the `publishDraft(id)` and `publishDraft(id, expectedOwnerId)` overloads. `publishDraftInternal` at L2915 handles:<br>- `supplyReviewService.lockTopic` + **owner check** (403)<br>- `mediaPublicationGuard.requireTopicPublication`<br>- supply-review resubmission flow | `publishDraftInternal(Long id)` **without the owner check, media guard or supply-resubmission**. It adds `themeRuntimePolicy.requireConfiguredEnabled(...)` (feature switch) before `clearDraftIfDraft`. | Keep master. If the theme switch is wanted, add only `themeRuntimePolicy.requireConfiguredEnabled` inside master's function. | low (the regression is security/ownership) | Owner: master. The questify side is an ownership regression. |
| C05 | same file L3866-3875 | Theme runtime config save | `CmsTopicServiceImpl.java:4136`: `mediaPublicationGuard.requireJson` + `ThemeRuntimeConfig.withoutClientBindings` + `assertJourneyReferences` | `ThemeRuntimeConfig.normalize(...)` + `requireConfiguredEnabled` when publishing (no media guard, no journey-reference assertion) | Master, plus an optional switch check | none | master |
| C06 | `.../service/impl/PlayJourneyStoryServiceImpl.java` L106-289 (5 hunks) | **Per-session frozen story snapshot** | `PlayJourneyStoryServiceImpl.java:101,157` use `TopicRouteSessionScope.findCurrent(...)`. `freezeEnding(topicId, raw, state[, session])` at L187-192 reads the session's frozen journey (`config_snapshot_json`, written by `TopicRouteRuntimeServiceImpl.freezePublication:1571`/`freezeJourney:1733-1770`). `assertChapterNodesCompleted(..., session)` | `TopicRouteSessionScope.find` + `themeRuntimePolicy.requireSessionEnabled`. `frozenThemeSnapshot(session)` / `frozenEndingChapter` falls back to live `chapterMapper` when nothing is frozen. `freezeEnding(session, state)` uses `ThemeRuntimeFeaturePolicy.hasFrozenTheme`. | Keep master's snapshot (already frozen at session start). Drop questify's parallel `JourneyContentSnapshotBuilder` path. | low (it changes content shown in progress, not money) | Owner: master is the single snapshot. Questify's live-chapter fallback breaks "frozen at start" consistency. |
| C07 | `.../service/impl/TopicRouteRuntimeServiceImpl.java` (30 hunks, 60 marker lines; L57-4906) | **Per-session (场次) frozen runtime snapshot at start + replay** | Session start (L1153 and L2071) calls **`CurrentPlayRegistration.require(...)`**, a paid-registration entitlement gate, then freezes:<br>- `session.setConfigSnapshotJson(snapshot)` L1179/L2117<br>- `freezePublication` L1571<br>- `freezeJourney(... configByTemplate, templatesById, sceneIndex)` L1770<br>Other master behaviour:<br>- `currentScopedSession`, `factSession` + `genericPlayer` guard<br>- `syncTheme(session, state, eventId, JourneyStatLinks.Reason)`<br>- `requireReplayScope` | At L1328 and L2308 it **replaces `CurrentPlayRegistration.require` with `guardFirstStart(scope)`** (the questify side has no registration gate). Other questify behaviour:<br>- `JourneyContentSnapshotBuilder.freezeStory/freezeThoughtChapters`<br>- `currentSessionForUpdate`<br>- `syncTheme(session, state, settleTerminal)`<br>- `ThemeTeamRunCoordinator`/`replayThemeTeam` (team replay, `migration_theme_team_replay_20261009.sql`)<br>- `themeRuntimePolicy.requireEnabled` | Keep master. Any later questify addition (team replay, feature switch) must be layered on master and **must keep `CurrentPlayRegistration.require/requireAll*`**. | **high**: the questify side lets someone start a paid topic or session run without a valid registration. | Owner: master. Reject the questify side wholesale. |
| C08 | `.../service/impl/AdvancedGameRuntimeServiceImpl.java` (16 hunks, 32 marker lines) | Advanced-game per-session snapshot, team, native steps | Master L217-218 and L282 use `selectOwnerByInstance` with `gameplayInstance`/`sharedInstance`. It has the zero-challenge flow, `CurrentPlayRegistration.requireToContinue` L358/L820, the shared-route-snapshot guard, and `expireIfNeeded(session, memberId)`. | `requireInput(..., teamId)` with `TeamGameScope` and `selectPlayTeamByIdForUpdate`. Native-steps (`NativeStepProtocol`, App) audit-only path, `assertCurrentPlayInstance`, `lockTeamGame`, `requireOpeningAccessReadOnly`. It drops the zero-challenge receipt branches and the "队伍共享任务...不能重复领取个人节点奖励" guard (master side of hunk @976). | Master. Native-steps for the App are an owner decision (D-16). | low–high: the questify side drops the duplicate-personal-node-reward guard. | Owner: master. |
| C09 | `.../service/ITopicRouteRuntimeService.java` L17-119 | Runtime interface | Master L17-36: guided-route/zero-challenge/gameplayAction/`freezePublication`/`freezePublishedJourney` defaults | `NodeAwardPermit`, `TeamGameScope`, `CurrentSessionFact`, `replayThemeTeam`, `assertCurrentPlayInstance` | Union only as needed by the chosen side | none | follows C07 |
| C10 | `.../service/support/ThemeRuntimeConfig.java` L14-493 (5 hunks) | Theme runtime config validation/remap | Master L12 ROOT includes `actionName`, `actionLimit`, `actionSources`, `rescueLimit`, `gameplayRuntime`, `templateBindings`. `TemplateResourceSlots`, `GameplayRuntimeConfig.assertChallengeNodes`, `assertPlanEndings` L326. Remap covers operation/challenge/alternative/fallback/exclusiveGroups. | Smaller ROOT (no `actionName`), `SYS_ASKED_REFERENCE` pattern, normalizes node configs in place, `validateReferenceShapes`, `remappedNodeId` | Master + optionally questify's `sys.asked` strict pattern | none | master |
| C11 | `chengyinhub-admin/.../api/ApiThemeRuntimeController.java` L18-84 | Theme API surface | Master L17 `/support-matrix`. `replay` rejects `weRun` (step recovery only) | `/capabilities` (feature switch), `replay` without the weRun guard, new `/team/replay` | Master; add `/capabilities` only if the switch is adopted | none | master |
| C12 | `chengyinhub-admin/src/test/.../TopicCompletionCouponRewardServiceImplTest.java` L26-38 | test for C01 | master test (coupon fixture via `selectSmsCouponById`) | mocks `TopicCompletionCouponHistoryResolving` | follows C01 | — | follows C01 |
| C13 | `.../business/service/CmsTopicPublishDraftTest.java` L102-153 | test for C04 | asserts `AppMediaReferenceGuard` and that `freezeForSubmission` is never called | `LegacyCmsProvenanceMapper` mock, `selectCmsTopicById` before CAS | follows C04 | — | follows C04 |
| C14 | `.../it/ThemeRuntimeMySqlIT.java` (5 hunks) | IT for C07/C10 | master IT (`TopicRouteRuntimeServiceImpl` target, `IShopDayProofService` mock) | advanced + preference + `w18_cms_template_provenance` tables | follows C07 | — | follows C07 |
| C15 | `.../it/AdvancedGameRuntimeMySqlIT.java` L133-175 | IT for C08 | `zeroChallengeHistoryRequiresExactCurrentRunReceiptOrFrozenOrigin` | `nativeChallengeCasAndAssertionCounterRollbackTogetherOnRealMysql` | Keep master; add the native test only if D-16 native-steps are adopted | — | follows C08 |
| C16 | `.../web/controller/api/ApiThemeRuntimeControllerTest.java` L34 + `.../web/controller/api/RoamSessionWriteMybatisIntegrationTest.java` L111-160 | tests for C11 / legacy-template read | master: `costOptionRejectsFractionalAndTextIndexes`; roam reward-log fixture | `capabilitiesDefaultOffWithoutEnteringRuntime`; `cms_member_template` legacy-source fixture | Keep master; append the questify tests only for adopted features | — | follow C11 |
| C17 | `.../service/support/TopicBundleReviewServiceTest.java` L360 | bundle review test | `acceptedMerchantSupplyAllowsOnlyItsApprovedTemplateAndDecisionRechecksSupply` | `missingApprovalGuardRejectsBeforeAnyBundledDraftWrite` (`LegacyCmsTemplateApprovalBoundary`) | Union both tests if the approval boundary is ported | none | union |

(C16 combines 2 test files, so the table covers all 17 marker files: 9 main + 8 test.)

Summary of the 3 named items:
- **Completion-coupon identity.**
  - Master: the `cms_topic_completion_coupon_reward_log.history_id` column, bound in the same transaction from the `grantCouponWithReceipt` return value. Clawback joins on `l.history_id` and then re-checks member/coupon/del_flag after `FOR UPDATE`.
  - #398: a separate `cms_topic_completion_coupon_receipt` table keyed by `grant_log_id`, plus a `receipt_contract_version` marker, switched by a JDBC metadata probe. Without the profile it falls back to `grantCouponById` with no identity. Clawback drops the post-lock identity re-check.
  - Recommendation: keep master.
- **W18 routing.** Master has no `W18_` route, so a W18 order would fall through to registration (none can exist). #398 adds a `W18_` → `WorkshopPaidPaymentHandler` route that fails closed but sets no route label, so its alarm is suppressed. DO_NOT_PORT (merchant plugin marketplace).
- **Per-session frozen snapshot.**
  - Master: at session start it gates on `CurrentPlayRegistration.require`, then freezes `config_snapshot_json` (`freezePublication`/`freezeJourney`). Story/ending reads come from that snapshot.
  - #398: a parallel `JourneyContentSnapshotBuilder` + `ThemeRuntimeFeaturePolicy.hasFrozenTheme`, with a live-chapter fallback. It also drops the registration gate at start.
  - Recommendation: keep master as the single snapshot.

#### FIXES in #398 that master lacks (verified against master working tree 063a6c3c)

| id | fix | master evidence (missing) | #398 evidence | class | port? |
|---|---|---|---|---|---|
| F1 | **Issued coupon validity is immutable.** `updateSmsCouponHistory`/`updateViewCouponHistory` no longer overwrite `start_time/end_time`, and supplied dates become a WHERE guard. | `SmsCouponHistoryMapper.xml:155-156` and the `ViewCouponHistoryMapper.xml` update still `set start_time=#{startTime}, end_time=#{endTime}`. Reachable from admin `SmsCouponHistoryController.java:143` and `ViewCouponHistoryController.java:91`. | diff in both mapper XMLs; tests `SmsCouponHistoryValidityImmutabilityTest`, `ViewCouponHistoryValidityGuardTest` | funds (medium) | yes, standalone |
| F2 | **Template-library re-list content review fails closed and uses the correct key.** | `ApiTemplateController.java:414-434` calls `selectCmsTemplateLibraryById(memberTemplateId)`, passing a member-template id as the library PK. It returns `null` (= pass) on exception or missing record. | `ApiTemplateController` + `TemplateLibraryRepublishReview` + `ITemplatePublishService.updateTemplateLibraryStatus(..., review)`; test `ApiTemplateLibraryRepublishSecurityTest` | security / content-safety | yes (needs `TemplateLibraryRepublishReview` + service overload) |
| F3 | **The LLM client no longer logs provider response bodies or exception messages**, which can echo prompts or keys. | `OpenAiCompatibleLlmClient.java:156` `log.error(... resp={}, truncate(result,500))`, and the catch logs the full throwable | `OpenAiCompatibleLlmClient` diff | security / privacy | yes, standalone |
| F4 | **Fallback NPC lookup uses the merchant id, not the member id.** | `NpcProfileService.java:99` `resolveForMerchant(merchantMemberId)`, where `resolveForMerchant(Long merchantId)` is at L57 | `MerchantNpcIdentityDirectory.availableMerchantIdForMember` | data integrity (could show another merchant's NPC) | yes (small) |
| F5 | **Merchant node submit content check includes `ruleInstructions`.** | `ApiMerchantNodeController.java:215-216` omits `body.getRuleInstructions()` (field exists, `CmsMemberTemplate.java:92`) | 1-line diff | content-safety | yes |
| F6 | Null/≤0 member guard on `/api/registration/list` and `/my-joined` | `ApiRegistrationController.java:764-771` and `:1044-1051` lack it | diff; test `ApiRegistrationOwnerScopeTest` | security (low) | yes |
| F7 | Registration lifecycle audit on payment confirm, refund invalidation and organizer cancel | no `RegistrationLifecycleAudit` on master | `CmsRegistrationServiceImpl`, `RefundServiceImpl:891-901`, `TeamGroupServiceImpl`, `BusinessAuditEventMapper.xml` | funds auditability | optional (owner) |
| F8 | Overnight business hours (`22:00-次日02:00`) parse | `TopicRouteCandidatePolicy.java:26` regex has no `次日` | diff | correctness (low) | yes |
| F9 | Edition reopen: a missing source template fails closed | `TopicEditionReopenServiceImpl.java:407` treats `null` as "no NPC refs" | diff | integrity (low) | yes |

Not counted as master fixes, because they are tied to questify features:
- AI-gateway scoped-NPC moderation (`AiGatewayServiceImpl`)
- Merchant coupon command idempotency (`CouponCommandService`, `stopIssuingMerchantCommand` pins `merchant_id`)
- Theme-replay instance plumbing (`TopicRouteSessionScope`, `PlayerNodeProgressServiceImpl`)
- Merchant accrual basis projection

I did **not** do a full review of the hand-resolved `ApiPlayProgressController` (+163/−35), `PreferencePlayService`, `ShopDayProofServiceImpl`, or the 573 added files. These are UNVERIFIED for hidden fixes.

#### DO_NOT_PORT (fixed decisions / not-doing list)
- **W18 workshop paid/free module marketplace**, which is the merchant plugin catalog:
  - `business/marketplace/**` (~96 files)
  - `ApiWorkshopPaid*`/`ApiWorkshopPurchasedController`, `WorkshopPaidNotificationRoute`
  - `migration_w18_*` (11 sql)
  - the C03 route
- **W16 rendezvous consent**: `ApiRendezvousConsentController`, `business/service/rendezvous/**`, `migration_w16_consent_persistence_candidate.sql`. These are stranger meetup tickets, which falls under friends/social and D-14. Owner should confirm; default is do not port.
- **W17 non-cash pledge inventory**: `migration_w17_noncash_inventory_candidate.sql`, `ApiNonCashRewardReadController`. A new reward-inventory system; owner decision, likely not-doing.
- Checked and **not** found: product_type other than 1/2 (all PR references use 1/2); gacha, pets, gear or minigame keywords (0 matches in added lines).
- Ask-first items, not hard DO_NOT_PORT:
  - App Attest / native device (`ApiNativeDeviceController`, `migration_20261002_native_app_attest.sql`, new cbor + bouncycastle deps). This is App-only (D-16).
  - Private-home Druid/logback wrapping.

#### Recommendation
- Keep #398 open as the owner-decision record and do not merge it: it has 17 conflict-marker files, does not compile, and is 361 commits behind master.
- For all 17 conflicts, recommend master (port side). This matters most for C01/C02 (single `history_id` receipt), C04 (owner check) and C07 (registration gate at session start).
- Port F1–F6 and F8–F9 as small separate PRs on master. F7 is optional.
- Mark the W18 marketplace and its route DO_NOT_PORT. W16 and W17 need an explicit owner ruling.


## 未决阻塞与需要 owner 决策的事项

1. **#449 CI 红**，是 PR 自身造成的回归，已在当前 master 的合并树上复现：
   - `community-api-namespace-gate.test.js:41` 失败；
   - `UI-GATE-0` 报 11 条违规。
   - 需要作者或 Lane C 修复。
2. **必合 PR 的 CI 都跑在旧 base 上**，落后 master 96–243 个提交，需要在当前 master 上重跑。本 lane 没有权限也不应代为触发。#418 要先解一处冲突（取并集），并与 Lane E 协调；#377 要与 Lane B 协调。
3. **新发现、master 仍缺的修复**（建议各开小 PR，涉及资金或鉴权的先问 owner）：
   - **身份证校验**：`chengyinhub-common/.../IdCardUtils.java:13` 的 `CHECK_CODES` 只有 10 位，缺最后的 `'2'`。按 GB 11643 应为 11 位。本 lane 已亲自核对。结果是校验码为 2 的合法证件号会数组越界，接口返回 500。小程序 `form-state.js` 有同样的问题。来源 #334/#335。
   - **微信支付回调去重（资金路径）**：`WxPaymentCallbackInboxServiceImpl.register` 不校验“同一 notify_hash 对应的业务是否一致”，报名回调也没有走 process fence。来源 #11 R1，属于 Ask-first。
   - **同意记录去重**：`ConsentRecordServiceImpl.record` 遇到同一 requestId 但属于另一个同意事件时静默返回，`ApiAgreementController.agree` 照样回成功。来源 #3。
   - **#380 新缺口**：机审没有通过的草稿文本不打 `review_flag`，之后 `publish-draft` 会以 `checkStatus=1` 上线，相当于标成机审通过。
   - **#376**：`audit.activity.pre_review_all` / `audit.activity.high_risk_categories` 两个 `sys_config` 键没有任何 migration 落种，所以回滚总闸在生产上不生效，需要运维写入。本 lane 不碰线上库，需按 chengyin-db-migrate 流程由 owner 确认。
   - 来自 #334/#336 的其余修复：商品详情泄露下架商品、俱乐部帖子越权引用、IM 卡片、内容安全回调 type16、活动协作者 id、自动核销分页、积分结算回执等。完整列表见 H 组。
   - **审核类型编号冲突**：#334/#336 给商家 NPC 用的内容审核 type 17，与 master 上社区帖的 type 17 冲突，移植时必须改号。
4. **#398**：17 个文件里留有冲突标记，原样无法编译，落后 master 361 个提交。17 项设计冲突都需要 owner 裁定，本文建议全部取 master 侧。W18 工坊/插件市场属于“商家插件目录”，DO_NOT_PORT。W16 线下约见和 W17 非现金库存需要 owner 明确表态。新增依赖（jackson-dataformat-cbor、bouncycastle）属于 Ask-first。
5. **#32**：`history_id` migration 要先在生产库执行，再部署依赖它的后端，需要 owner 操作。E04-02（每人领取上限）是 owner 的政策问题。
6. **#35**：公开的官方活动详情读接口缺少 `parties[]`、各方的 `displayName`/`avatar`、`viewerCanManage`、`acceptedAt`，也没有按可见性过滤。应先修协作者 id 的 bug，再补这些字段。
7. **UNVERIFIED**：
   - `port-f3-growth` 真库 IT 在 master 上是否为绿；
   - `merchantReferralBountyService.onVerification` 被重试时有没有非 DB 副作用；
   - #21 台账遗留项是否已逐项落地；
   - #398 的 573 个新增文件中是否还藏有修复，只做了部分审阅。

## 抽查记录（本 lane 亲自复核子代理的结论）

- `#398` 冲突标记：`git diff 417ff214 pr/398` 中新增的 `<<<<<<<` 共 79 处，分布在 **17** 个文件，与子代理结论一致。
- `#375` 认领 CAS：master 的 `AuditTaskMapper.xml` 里确实有 `current_assignee is null` 条件的原子 UPDATE。
- `#376`：master 仓内 `.sql` 文件中确实没有 `pre_review_all` 的落种，该键只在 `ActivityReviewPolicy.java:25` 里作为常量出现。
- `IdCardUtils.CHECK_CODES`：master 上是 `{'1','0','X','9','8','7','6','5','4','3'}`，只有 10 位，确认缺 `'2'`。

## 验证记录（本地，只读复现）

| 对象 | 命令 | 结果 |
|---|---|---|
| #449 PR 头 | `node --test --test-shard=2/20 "tests/unit/**/*.test.js"` | 728 过 / 1 失败（community-api-namespace-gate 第 4 例） |
| #449 合并树 | `node chengyinhub-xcx/tests/lint/ui-integrity.js --warn-as-error` | 退出码 1，`UI-GATE-0:FAIL (11 violations)`；master 上同一命令退出码 0 |
| #412 合并树 | `node --test tests/unit/club-apply-state-contract.test.js`；`node --test "tests/unit/**/club*.test.js"` | 21/21；762/762 |
| #418 合并树（冲突取并集） | `node --test tests/unit/roam-player-memory.test.js tests/unit/usage-consent-app.test.js`；`node --test "tests/unit/**/*verification*.test.js"` | 26/26；265/265 |
| #434 合并树 | `python3 -m unittest ci/test_chain_acceptance.py ci/test_chain_admin_readback.py scripts/e2e/test_chain_lib.py`；`python3 -I ci/test_upgrade_startup_network.py` | 101 过 + 1 个环境 import 错误（缺 pymysql，master 上相同）；19/19 |
| #377 合并树 | `LC_ALL=C.UTF-8 mvn -q -pl chengyinhub-admin -am -Dtest='CommunityPost*Test,WxContentSecurity*Test' test` | 14 个类 208 例，0 失败 / 0 错误 / 0 跳过 |
