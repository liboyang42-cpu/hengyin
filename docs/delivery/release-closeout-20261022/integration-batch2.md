# 发布收尾 batch2 集成候选记录（2026-10-22）

- 集成分支：`integrate/closeout-batch2-20261022` → `master`，对应 draft PR #486，只供人工审阅后合并。
- 基线：从 batch1 集成分支 head `5b272b9f` 切出。#475（batch1）在本批进行中合入 master（`f0b43d62`）。之后本分支已并入 master，PR base 也已从 `integrate/closeout-batch1-20261022` 改指 `master`。
- 范围：
  - 不部署、不连生产、不执行任何 DB 变更。
  - 没有合并或关闭任何 PR，没有推 master，没有删分支。
  - 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`，只单独跑了子门禁和 Node 单测。

## 1 合入顺序

全部用 `git merge --no-ff <PR head>`。每一步合并时，都把 `scripts/uiaudit/action-ledger.json` 和 `route-matrix.json` 还原为集成分支当时的版本，丢弃各 lane 自己的改动。台账最后在合并树上统一再生成一次（见第 3 节）。

| 顺序 | PR | Lane | head | 说明 |
|---|---|---|---|---|
| 1 | #472 | W4 | `e3b302dc` | 同意记录重复 requestId 一致性 |
| 2 | #471 | W5 | `9921d4ad` | 审核正确性收尾，含迁移 `migration_20261022_audit_activity_switch_seed.sql` |
| 3 | #473 | W2 | `df6a7363` | 内容安全/完整性小修 |
| 4 | #477 | W10b | `c79d7eca` | 请求回读与审核回执，含两个迁移（见第 5 节） |
| 5 | #479 | W3 | `12cece62` | 资金路径加固（PR 自述「资金路径，合并前需审」） |
| 6 | #474 | W6 | `3ba609ce` | 可见性/隐私 |
| 7 | #478 | W8 | `c7c974f4` | 传感器 attemptId 与奖励上限 |
| 8 | #481 | W10c | `7d5ba7d5` | 首次合入 |
| 9 | #482 | W11 | `b8188d48` | 首次合入 |
| — | master | — | `f0b43d62` | #475 合入 master 后同步 |
| 10 | #482 | W11 | `06aa4119` | 更新 head：还原保留文件，断言改走 CY-HANDOFF |
| 11 | #481 | W10c | `eeef56dc` | 更新 head：FX2 撤回保留文件改动，并已合入 master |
| 12 | #476 | W7 | `ecda12d1` | play 运行时与玩家文案 |
| 13 | #480 | W9 | `9c67529d` | 搜索 ¥0 上限与前端收尾 |
| 14 | #484 | W10a | `c2f30832` | 俱乐部月历场次、口碑服务端筛选、期次空态原因 |
| 15 | #470 | W1 | `69a9c6b4` | 小型正确性修复。源码指纹更新带 owner 批准说明，PR 自身 CI 绿后合入 |
| 16 | #484 | W10a | `ca8da72e` | 更新 head（只改后端）：俱乐部月历的公开视角复用仅成员可见的主题闸 |
| 17 | #477 | W10b | `84c33fea` | 更新 head：门店 AI 形象头像审核回执改读 MERCHANT_NPC/npcId 送检目标；get-auth-matrix 还原后改为 CY-HANDOFF |

### 纳入依据（各 PR 自身 CI）

- 全绿：#473、#479、#472、#471、#478、#482（`b8188d48`）、#484。
- 仅剩台账过期：#474、#477、#476、#481（`eeef56dc`）。
- 仅剩台账过期加待落地的 CY-HANDOFF（get-auth-matrix）：#480（`9c67529d`）。这个 head 已修好之前的 U6 RAW_ERROR_TEXT 真失败（`fda37f43`）。
- #470：先前唯一的红是 Migration safety gate 的源码指纹，在 `69a9c6b4` 更新后转绿。

- #484 `ca8da72e`：PR 自身 CI 只有 Mini program unit shard (13/20) 一项红（run 39788518561），这个 head 只改后端，Backend tests 通过。本地复现：在 master `f0b43d62` 合并 W10a 后单跑该分片，`subscribe-recipient-contract.test.js` 报 `ENOENT …/subpackageRoam/scan-comment-test-XXXX/regex.js`，每次失败的用例不固定。原因是 master 上的 `roam-comment-scan.test.js` 在仓内建临时目录，和遍历源码树的 `subscribe-recipient-contract` 在同一分片里并发时会撞上。这是测试之间的竞态，问题早已存在，不是 W10a 引入的，单跑这两个文件都通过。本集成分支的分片组合在 CI 上全绿，本地全量 13686/13686 通过。建议另开修复：给扫描测试的临时目录换到系统 tmp，或让遍历跳过 `scan-comment-test-*`。
- #477 `84c33fea`：PR 自身 CI 的红是 unit shard 5（get-auth-matrix 69≠65，待落地的 CY-HANDOFF）、shard 8 和 static gates（台账过期），都属于可接受类，Backend tests 通过。

### 排除

无。

### 需要人工在合并前注意（来自各 PR 自述，集成没有改变它们的结论）

- #476 W7：PR 自述 NEEDS_OWNER_APPROVAL。一是暂停时延长归零 HP 与挑战截止时间，二是取消或退队不再写 HP_ZERO。两项都会改变游戏结局。
- #479 W3：资金路径，合并前需审。
- #478 W8：后端 jar 与小程序必须同发。主题完成奖励是否封顶仍待 owner 决定，需要真机核对。
- #473 W2：二维码上传归属校验没有移植，待 owner 决定。
- #472 W4：#11 R2 没有移植，需要 owner 确认。
- W7、W9、W10a、W11 都没有做模拟器或真机截图，需要人工在开发者工具中复核。

## 2 冲突处理

- 无文本冲突。合入顺序先后端、后前端。
- 先前试合时，W9 与 W11 在 `action-ledger.test.js` 的计数断言上有冲突。两条 lane 随后都把该文件还原为 merge-base，改由 CY-HANDOFF 交接，正式合入时就没有冲突了。计数统一按合并树重建后的实测值落地（见第 4 节）。
- 语义交汇点 `get-auth-matrix-contract.test.js`：W10b 自带 4 条矩阵行（65 → 69），W9 的 CY-HANDOFF 写的是 65 → 66。集成时把 W9 那一行叠加在 W10b 之后，结果为 69 → 70。两边的矩阵行都保留。
- `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`：没有任何 lane 在最终 head 上改它，合并树上 UI-GATE-0 直接 PASS，因此没有重锚，也没有删除任何条目。

## 3 再生成（在合并树上各做一次）

| 产物 | 生成器 | 旧 → 新 | 说明 |
|---|---|---|---|
| `scripts/uiaudit/action-ledger.json` | `build-action-ledger.js --write` | 控件 4947 → 4958 | 增量 = W10a +3、W9 +7、W11 +1，与各 PR 自报一致。本地 tap 2106 → 2114，事件合同 2464 → 2467 |
| `scripts/uiaudit/route-matrix.json` | `build-route-matrix.js --json` | 145 路由 / 27 场景，无差异 | — |
| `tests/lint/ui-integrity-baseline.js` | 未改 | U1 变量 8 / U3 62 / U4 63 动态 + 126 零消费 / U5 37，不变 | 不需要重锚，也没有过期条目 |

过程中的中间态（只有前 9 个 PR）：台账为 4948，提交于 `b2624505`。

## 4 落地的 CY-HANDOFF

owner 已于 2026-10-22 批准由集成方接管保留文件维护。下面每一条都已检查，没有放宽任何门禁。

| 来源 | 文件 | 落地内容 |
|---|---|---|
| #480 W9 | `tests/unit/get-auth-matrix-contract.test.js` | MATRIX 新增 `['pages/shezhi/components/post-governance/index.js', "url: '/api/v1/community/me/enforcements'", 'required', 'member', 'self-account']`；`directCount` 改为 70，并附 W9 注释（基数按 W10b 的 69 叠加，交接块原写 65 → 66） |
| #480 W9 | `scripts/uiaudit/action-ledger.json` | 在合并树上重建（第 3 节） |
| #480 W9 | `tests/unit/action-ledger.test.js` | 三条注释原样落地：「设置入口 + 提交申诉/取消/提交/加载更多五个 tap」「设置半屏 close 与加载失败重试两个事件合同」「上述五个 tap + 半屏关闭 + 加载失败重试两个事件合同」。数值叠加在 W10a 之上：tap 2108 → 2113、事件合同 2465 → 2467、controlCount 4950 → 4957 |
| #482 W11 | `tests/unit/action-ledger.test.js` | 「收藏页「收藏与推荐」说明 +1 tap(showRecommendNote)」：tap 2113 → 2114；「收藏页「收藏与推荐」说明 +1 tap」：controlCount 4957 → 4958（交接块写明在集成当时的值上 +1） |
| #482 W11 | `scripts/uiaudit/action-ledger.json` | 重建 |
| #481 W10c | `tests/unit/action-ledger.test.js` | 交接 diff 中的 `test('GET /api/publish/home 是读;真实发布写端点仍是写', …)` 整段原样追加在 `isRecognizedMedia('truncated.png', truncated)` 用例之后（只增加断言） |
| #474 W6 | `scripts/uiaudit/action-ledger.json` | 「合并 W6 后用 build-action-ledger.js 重建」，已在合并树上一次完成 |
| #477 W10b | `scripts/uiaudit/action-ledger.json` | 「action-ledger 重建（C00）」，已完成。交接块中的 #23 客户端与部署顺序两条不涉及保留文件，转记在第 5 节和 PR 描述中 |
| #477 W10b（`db4f3850` 之后） | `tests/unit/get-auth-matrix-contract.test.js` | 交接要求 MATRIX 加 `npc-chat` `/api/ai/npc/chat/request`、`publish/simple` `/api/ai/theme/draft/request`、`gerenziliao` `/api/user/profile/review`、`merchant/decor/ai-npc` `/api/merchant/npc/review` 四行，并把 directCount 改为 69。这四行和 69 已经在本分支上（来自 W10b 早先的 head），合入新 head 时保留集成分支版本，叠加 W9 后为 70 |
| #479 W3 | — | 交接内容是 `pages/club/topic-detail/index.js` 的 `confirmOpsTime` 文案和 `CLUB_OPS_SOLD_LOCKED` 映射，属于 club topic-detail owner 的代码工作，不是保留文件行。**本集成未实施**，留给对应 owner |
| #484 W10a | `tests/unit/action-ledger.test.js` | 不是交接块：W10a 分支自带了计数改动（2108 / 2465 / 4950）和注释，随合并进入，最终值见上面几行 |

被拒绝的交接行：无。所有交接都是新增矩阵行、新增断言，或按实测更新计数。

## 5 迁移与部署顺序（③ 数据库迁移）

本批带入 3 个新迁移（都只是新文件，没有改动既有 migration）：

- `chengyinhub-admin/sql/migration_20261022_audit_activity_switch_seed.sql`（#471 W5）
- `chengyinhub-admin/sql/migration_roam_share_version_20261022.sql`（#477 W10b）
- `chengyinhub-admin/sql/migration_member_profile_version_20261022.sql`（#477 W10b）

这 3 个迁移都必须**先于新 jar** 手动落库：先走 chengyin-db-migrate 只读预检，再由用户确认，并按 `ci/migration-dependencies.json` 的拓扑序执行。原因是新 jar 发布足迹分享时会写 `roam_share_snapshot.version`，迁移没落库会导致发布失败。本集成没有执行任何 DB 操作。

## 6 本地验证（合并树 `7b75669b`）

- `TZ=Asia/Shanghai node --test "tests/unit/**/*.test.js"`：13685/13685 通过
- `build-action-ledger.js --check` / `--selftest`：PASS（4958）
- `build-route-matrix.js --check`：PASS（145 / 27）
- `ui-integrity.js --warn-as-error`：UI-GATE-0 PASS（U1 静态 909 / 动态 60 / 变量 8；U2 5132；U3 62/62；U4 126/126，63/63；U5 37）；`--selftest` PASS
- `ci/silent-error-gate.js`、`ci/api-existence-gate.js`：PASS

## 7 CI

- run `39777540687`（workflow_dispatch，head `b2624505`，前 9 个 PR）：30 个 job 全部 success。当时 PR base 不是 master，`pull_request` 触发器不派发，所以手动派发。
- run `39783877003`（head `7b75669b`）：只有 Migration safety gate 的 [4/5] 迁移声明门是红，原因是 PR 描述缺少「## 影响分类 ③」。描述已补，随下一个提交重新派发。
- head `2b089767`（13 个 PR 全部合入，含本记录首版）：35 个 check run，32 success，3 skipped（Admin UI 与 SBOM 按变更区域跳过），0 失败。
  - GitHub CI run `39785313826`：Backend tests、Migration safety / layout gate（迁移声明门转绿）、20 个小程序 unit shard、Mini program static gates、Fast gates、gitleaks，全部 success。
  - Funds MySQL IT run `39785313609`、Mini followup focused regression run `39785313522`、Isolated authenticated Redis recovery run `39785313756`：success。
- 之后合入 #484 `ca8da72e` 与 #477 `84c33fea`，最终 head 以 PR #486 的检查为准。

## 8 再并 master（#511 店铺参谋改版，PR 标记 ready 之后）

PR 转为 ready 后，master 前移到 `c225b462`（#511），PR 变成 dirty。处理：用 merge commit 把 master 并入本分支，不做 rebase / force-push。

- **文本冲突**
  - `pages/merchant/marketing/ai-insight/index.wxml`：#511 重写了整页，W11（#482）在旧的解读卡里加了一行「解读依据 {{generatedAt}} …次日生效」。处理：取 #511 的新版结构，把 W11 的 `ai-source` 那一行放回新解读卡里 `ai.problem` 的后面。`index.wxss` 的 `.ai-source` 样式自动合并。
  - `scripts/uiaudit/action-ledger.json`：丢弃双方版本，在合并树上重建，控件 4958 → **4967**（#511 +9：7 tap + 2 事件合同，和 #511 自报的 4947 → 4956 增量一致）。
  - `tests/unit/action-ledger.test.js`：三处计数冲突。双方的注释都保留，数值取合并树重建实测：tap 2114 → 2121，事件合同 2467 → 2469，controlCount 4958 → 4967。
- **语义冲突**（两侧单测各自通过，合并后变红）
  - #511 的 `merchant-insight-redesign-20261022.test.js` 要求页面（去掉注释后）不再出现「店铺档案」。W11 那一行原文是「当时的**店铺**档案」。
  - W11 的 `ai-insight-source-version-note.test.js` 按旧的 `<cy-card wx:if="{{ai}}">` 结构截取卡片。
  - 处理：文案改为「解读依据 {{generatedAt}} 的经营数据与当时的档案；今天修改的档案次日生效」，与 #482 PR 描述里写的「依据 {generatedAt} 的数据与当时的档案；今天修改的档案次日生效」同义，W11「档案次日生效」这个如实说明保留。W11 的测试改按 #511 的 `view.ai-card` 结构定位，断言内容不放宽，并增加「结构必须可定位」的断言，防止空转。
- **验证**
  - 合并树全量单测：处理语义冲突前是 13692/13694，红的就是上面两条。处理后这两个文件 9/9 通过，`action-ledger.test.js` 连同店铺参谋相关测试 70/70 通过。
  - `build-action-ledger --check`（4967）PASS，`build-route-matrix --check`（145/27，无差异）PASS，UI-GATE-0 PASS（U1 909/60/8；U3 62/62；U4 126/126，63/63；U5 37，基线未改），silent-error 与 api-existence 门禁 PASS。
