# 协调者补丁累计校验（BL-139）

> 只提交本文档，叠加后的统筹文件**没有**提交、没有推送任何合并分支。
> 做法：在本地临时 worktree（基线 `origin/claude/mini-ui-features-20261015` @ `d0307a78`）按 BL-127 `MERGE-ORDER.md`（#171）§2 的 10 步顺序真实 `git merge` 各 PR 分支（§3 取方案 A：合 #77 + #104，不合 #118；#75 不合），按 BL-126 `COORDINATOR-PATCHES.md`（#170）§2.1 补 `app.json` 页面，每步结束后用仓库自带生成器重建 `route-matrix.json`（`build-route-matrix.js --json`）和 `action-ledger.json`（`build-action-ledger.js --write`），再跑 TASKS.md 公共规则里的 node 检查。**没跑** `ci/xcx-check.sh`、`ds-hardcode-gate.sh`、CI、真机。
> 快照时间 2026-10-16 01:00–02:30Z；PR head 以当时 `origin/claude/*` 为准。

## 0. 冲突处理规则（决定了下文数字的含义）

| 冲突文件 | 处理 | 影响 |
|---|---|---|
| `action-ledger.json`、`route-matrix.json` | 取叠加树一侧，步末用生成器重建 | 计数是**合并树实测值** |
| `app.json` | 取叠加树一侧，再按 BL-126 §2.1 补页面 | 页面数是实测值 |
| `action-ledger.test.js`、`route-matrix-freshness.test.js`、`title-system-owner-contract.test.js`、`nav-page-title-spacing-contract.test.js`、`get-auth-matrix-contract.test.js`、`ui-integrity-baseline.js`、`fixtures.json`、`shot-matrix.js` | 取叠加树一侧，**不手改冻结计数** | 这些测试的 `expected` 是先合入的那个 PR 分支里写的值（如 4502、134、72、38），`actual` 是合并树实测；两者之差就是协调者要改的量 |
| `ci/silent-error-allowlist.txt`、`scripts/ui-silent-error-allowlist.js` | 去掉冲突标记，两侧行都保留（并集） | 同一文件两侧都改了计数行时会出现「白名单重复」，那是本做法的副作用，真实整合时应按合并树重新计数 |
| 其他代码文件 | **放弃该 PR**（`merge --abort`）并记录 | 被跳过的 PR 的增量不在下文数字里 |

## 1. 逐步结果

基线（未叠加）：单测 8301 / 失败 0；`ui-integrity --warn-as-error`、`build-action-ledger --check`、`ui-theme-unit-lint --units`、`a11y-tap-target-lint`、`ui-validity-gates` 全部 exit 0。

| 步 | 合入 | 跳过（代码冲突） | app.json 页面 | route-matrix routes | ledger controlCount | tap 分母 | 直接 GET | cy-page-title 页 | 单测 总数/失败 | ui-integrity | ui-validity |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 基线 | — | — | 133 | 133 | 4480 | 1796 | 33 | 71 | 8301 / 0 | ✅ | ✅ |
| 1 独立功能 | #40 #42 #91 #43 #55 #56 #62 #88 #120 #122 #132 #141 #144 #147 #152 #158 #164 #166 | — | 134 | 134 | 4509 | 1805 | 36 | 72 | 8354 / 10 | ❌ 6 | ❌ U5×2 |
| 2 CL 大卡 | #23 #27 #25 #22 | — | 138 | 138 | 4571 | 1834 | 47 | 76 | 8430 / 10 | ❌ 36 | ❌ U5×2 |
| 3 漫游串 | #37 #39 #60 #83 #113 #84 | **#38**（`pages/roam/index.js`，与 #37） | 139 | 139 | 4582 | 1841 | 48 | 77 | 8449 / 12 | ❌ 65 | ❌ U5×4 |
| 4 俱乐部串 | #57 #59 | — | 139 | 139 | 4584 | 1841 | 48 | 77 | 8453 / 12 | ❌ 65 | ❌ U5×4 |
| 5 商家 | #119 #128 #123 | — | 140 | 140 | 4592 | 1844 | 50 | 78 | 8464 / 12 | ❌ 72 | ❌ U5×6 |
| 6 举报/个人页串 | #73 #74 #109 #114 #138 #95 | **#110**（profile `index.js/json/wxml`）、**#112**（profile `index.js`）、**#68**（profile `index.js`） | 140 | 140 | 4615 | 1848 | 52 | 78 | 8483 / 12 | ❌ 82 | ❌ U1×1 U5×9 |
| 7 私信串（方案 A） | #77 #104 #130 | — | 140 | 140 | 4617 | 1848 | 53 | 78 | 8497 / 12 | ❌ 84 | ❌ U1×1 U5×12 |
| 8 跨区小改 | #58 | **#65**（profile `index.wxml`） | 140 | 140 | 4617 | 1848 | 53 | 78 | 8497 / 12 | ❌ 84 | 同上 |
| 9 解锁项 | #35 | — | 140 | 140 | 4617 | 1848 | 53 | 78 | 8507 / 12 | ❌ 84 | 同上 |
| 10 合同测试 | #98 #100 #117 #125 #134 #150 | — | 140 | 140 | 4617 | 1848 | 53 | 78 | 8539 / 12 | ❌ 84 | 同上 |

`build-action-ledger --check`、`ui-theme-unit-lint --units`、`a11y-tap-target-lint` 每一步都是 exit 0（台账每步都已重建）。合同测试 PR（第 10 步）全部通过，没有新增失败。

## 2. 与 BL-126 累计期望值对照（第 10 步终态）

| 计数 | BL-126 累计期望 | 叠加实测 | 差异说明 |
|---|---|---|---|
| `app.json` 页面 | 140 | **140** ✅ | |
| route-matrix / title-system-owner 盘点 | 140 | **140** ✅ | 生成器重建后与 app.json 一致；冻结测试仍写 134（来自 #23/#27/#119 分支），需改为 140 |
| nav-page-title-spacing | 78 | **78** ✅ | 冻结测试仍写 72 |
| get-auth-matrix 直接 GET | 55 | **53** | 差的 2 条正是被跳过的 #110（contributions/taxonomy）、#112（`/api/city/nodes/merchant/{id}`）；补上即 55 ✅ |
| action-ledger `controlCount` | ≥ 4586，约 4600 | **4617** | 已不含被跳过的 #38/#68/#110/#112/#65；全部合入后会更高，**只能按最终合并树重建取值** |
| tap 分母 | ≥ 1826 | **1848** | 同上 |
| shot-matrix / fixtures | 333–336 | 未核 | 冲突时取了叠加树一侧，#27/#25 的追加行被丢弃；需协调者按 BL-126 §2.7 手工追加 |

## 3. 叠加后仍然红、且 BL-126 没有列出的项（需协调者或各线处理）

按「冻结计数调到实测值后仍会红」筛出。冻结计数本身（§2）不重复列。

| # | 检查 | 失败内容 | 来源 PR | 建议 |
|---|---|---|---|---|
| X-1 | `tests/unit/audit-f01-f17-contract.test.js:79`「F13 已确认的工程术语不再出现在用户可见文案」 | `pages/merchant/crm-campaigns/index.{js,wxml}` 5 处「触达回执」 | #144 BL-100 | **代码问题**：「回执」是 F13 禁用工程术语，需改文案（如「触达明细」「逐人结果」）；不是计数补丁能解决的 |
| X-2 | `tests/unit/success-toast-ratchet.test.js:86`「成功 toast 只减不增」 | 102 > 上限 101 | #88 BL-43 `toast.success('角色声音已清除')`；#112 BL-69 `cyToast.success(...)` 合入后会到 103 | 改成界面状态（如按钮行变为「未录音」）或由协调者连同 CHANGELOG 抬上限；两个 PR 正文都没提 |
| X-3 | `tests/unit/merchant-card-project-actions-contract.test.js`「商家卡与我的项目管理动作具有 44px 热区」 | 管理动作数 expected 5 / actual 6 | #164 BL-120（另存为模板） | 冻结计数 5→6，BL-126 §2 未列 |
| X-4 | 孤儿页负控（`app.json 每一页至少有一处运行时跳转指向它`）及其负控 | `/subpackageRoam/node-favorites/index` 无入口 | #84 BL-42 | 入口待用户决定（BL-126 已记）；在决定前合入页面必须同时登记孤儿页豁免，否则单测红 |
| X-5 | `ui-validity-gates` U5「未进白名单 / 调用数变化」 | `subpackageP3/pages/object-cards/index/index.js`（#122）、`pages/club/topic-detail/index.js` 6→7（#141）、`subpackageRoam/node-favorites/index.js`（#84）、`pages/merchant/index/index.js` 2→3（#128）、`pages/activity/official-mine/index.js` 1→2（#123）、`components/cy/report-sheet/index.js` 2（#73 系）、`components/cy/scene-share-invite/index.js` 1（#114）、`pages/roam/index.js` 12→14（漫游串）、`subpackageA/pages/myproject/index.js` 5→6（#164）、`subpackageB/pages/im/list/index.js` 1→2（#25） | 多个 | 这些 PR 大多只登记了 `ci/silent-error-allowlist.txt`，**没有同步 `scripts/ui-silent-error-allowlist.js`**（`ui-validity-gates` 读的是后者）；#141、#114 两个都没登记。BL-126 §2.4 写「已在各自分支内登记」不成立于 #122/#123/#128/#141/#114/#73 |
| X-6 | `ui-validity-gates` U1 | `subpackageMember/components/cy/profile/index.js:724` `'/api/community-trust/safety/merchants/' + id` 判为「没有后端 @*Mapping」 | #109 BL-66 | 后端 `ApiCommunityTrustController.java:174` 有 `@GetMapping("/safety/merchants/{merchantId}")`，是门禁不认「字面量 + 变量」拼接；改成模板字符串 `` `/api/community-trust/safety/merchants/${id}` ``（与 #130 写法一致）即可，不必改门禁 |
| X-7 | `ui-integrity` UI-GATE-0 [U5] | `pages/play/merchant/components/safety-rating-sheet/index.js:115` silentError 未登记逐项理由 | #132 BL-89 | #132 只加了两份白名单，ui-integrity 的 U5 逐项理由（`ui-integrity-baseline.js`）未登记 |
| X-8 | `ui-integrity` UI-GATE-0 [U1]/[BASELINE] | `pages/activity/official-inbox/index.js:103 → :109`（U1-VARIABLE 键漂移） | #43 BL-06 | BL-126 §2.5 只列了 #164 的 myproject 键，漏了 #43 |
| X-9 | `ui-integrity` UI-GATE-0 [U4]/[BASELINE] | `pages/publish/fabu/index.js` 12 条 U4 动态 setData 键漂移 | #60 BL-23 | BL-126 未列。另：该文件在 Codex `tasks.json` `followup_lanes[4]`（L05 publish_unknown_sol）的 files 里，合并前请协调者确认与 Codex 不冲突 |
| X-10 | `ui-integrity` UI-GATE-0 [U4]/[U5] | `subpackageRoam/nearby/index.js` 1 条键漂移；`pages/activity/official-mine/index.js` U4×6 + U5×1；`subpackageB/pages/im/chat/index.js` U4×1；profile `index.js` U1×2/U4×2/BASELINE×4 | #60、#123、#130/#77、#73/#109/#114/#138 | 行号键漂移，按最终合并树重算 `ui-integrity-baseline.js` |
| X-11 | `ui-integrity --warn-as-error` [LEGACY WARN] | `subpackageRoam/node-favorites/index.js:12,:42`「开发者口径文案（参数/数据异常类）」；`subpackageB/pages/im/chat/index.js:645` 提示 18 字 > 16；`pages/activity/official-inbox/index.js:202` 提示 21 字 > 16 | #84、#130、#43 | `--warn-as-error` 下会让门禁红；需改文案（#84 两处尤其是用户可见的错误态） |

## 4. 被跳过的 PR（真实代码冲突，需人工合）

| PR | 冲突文件 | 与谁冲突 | 与 BL-135 `CONFLICTS.md`（#179）是否一致 |
|---|---|---|---|
| #38 BL-03 | `pages/roam/index.js` | #37 | 一致（#37×#38） |
| #110 BL-67 | profile `index.js` / `index.json` / `index.wxml` | #73/#74/#95 举报菜单 | 一致 |
| #112 BL-69 | profile `index.js` | #109 | 一致（#109×#112） |
| #68 BL-21 | profile `index.js` | #109 | 一致（#68×#109） |
| #65 BL-27 | profile `index.wxml` | #27 | 一致（#27×#65） |

## 5. 复跑方法

```sh
git worktree add --detach /tmp/stack origin/claude/mini-ui-features-20261015
cd /tmp/stack
# 对 MERGE-ORDER §2 的每个 PR 分支（顺序同上表）：
git merge --no-ff origin/claude/<branch>
#   冲突：§0 表里的统筹文件 → git checkout --ours <f>；两份 silentError 白名单 → 保留两侧行；其他文件 → git merge --abort 并记录
#   合入 #23/#25/#27/#84/#119/#144 后按 COORDINATOR-PATCHES §2.1 补 app.json
# 每步结束：
cd chengyinhub-xcx
node scripts/uiaudit/build-route-matrix.js --json
node scripts/uiaudit/build-action-ledger.js --write
node --test "tests/unit/**/*.test.js"
node tests/lint/ui-integrity.js --warn-as-error
node scripts/uiaudit/build-action-ledger.js --check
node scripts/ui-theme-unit-lint.js --units
node scripts/a11y-tap-target-lint.js
node scripts/ui-validity-gates.js
```

每步完整单测约 140 秒。叠加 worktree 只在本地，用完 `git worktree remove /tmp/stack`。
