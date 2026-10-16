# 合并冲突预检（BL-135，2026-10-16 快照，基线 `d0307a78`）

> 只写文档，没有推送任何合并结果。工具：`git merge-tree --write-tree --name-only`（git 2.43），纯内存三方合并，不动工作区和 refs。
> 范围：open 且 diff 非空的 `claude/bl-*`/`claude/cl*` PR，共 **71** 个（另有 59 个 diff 为空，见 `CLOSE-CANDIDATES.md`，BL-134 #178）。其中 55 个改代码或测试，16 个只改 `docs/`。
> 与 `MERGE-ORDER.md`（BL-127，#171）配合阅读：那份按「改了同一文件」排顺序，本文件给出**真实的文本冲突**。按同一文件判断的热点里，很多实际能自动合并（例如 `pages/roam/index.js` 在 8 个 PR 里两两合并只有 #37×#38 冲突）。

## 1. 对当前基线

**71/71 都能干净合入基线**（基线在这些 PR 切出后没有再动过相关文件）。单独合任何一个都没有冲突，冲突只出现在 PR 之间。

## 2. 两两预检：77 对冲突，涉及 16 个文件

| 文件 | 冲突对数 | PR 对 |
|---|---|---|
| `ci/silent-error-allowlist.txt` | 50 | #119×#120、#119×#122、#119×#123、#119×#132、#119×#144、#119×#147、#120×#122、#120×#123、#120×#132、#120×#144、#120×#147、#122×#123、#122×#132、#122×#144、#122×#147、#123×#132、#123×#144、#123×#147、#132×#144、#132×#147、#144×#147、#25×#130、#73×#119、#73×#120、#73×#122、#73×#123、#73×#132、#73×#144、#73×#147、#74×#119、#74×#120、#74×#122、#74×#123、#74×#132、#74×#144、#74×#147、#75×#119、#75×#120、#75×#122、#75×#123、#75×#132、#75×#144、#75×#147、#77×#119、#77×#120、#77×#122、#77×#123、#77×#132、#77×#144、#77×#147 |
| `chengyinhub-xcx/scripts/uiaudit/action-ledger.json` | 11 | #22×#37、#23×#119、#23×#25、#23×#27、#23×#37、#25×#119、#25×#27、#25×#37、#27×#119、#27×#37、#37×#119 |
| `chengyinhub-xcx/tests/unit/action-ledger.test.js` | 10 | #23×#119、#23×#25、#23×#27、#23×#37、#25×#119、#25×#27、#25×#37、#27×#119、#27×#37、#37×#119 |
| `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js` | 9 | #109×#112、#110×#118、#68×#109、#68×#112、#73×#110、#74×#110、#75×#110、#77×#110、#95×#110 |
| `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml` | 7 | #110×#118、#27×#65、#73×#110、#74×#110、#75×#110、#77×#110、#95×#110 |
| `chengyinhub-xcx/tests/unit/title-system-owner-contract.test.js` | 6 | #23×#119、#23×#25、#23×#27、#25×#119、#25×#27、#27×#119 |
| `chengyinhub-xcx/tests/unit/route-matrix-freshness.test.js` | 6 | #23×#119、#23×#25、#23×#27、#25×#119、#25×#27、#27×#119 |
| `chengyinhub-xcx/tests/unit/nav-page-title-spacing-contract.test.js` | 6 | #23×#119、#23×#25、#23×#27、#25×#119、#25×#27、#27×#119 |
| `chengyinhub-xcx/subpackageMember/components/cy/profile/index.json` | 6 | #110×#118、#73×#110、#74×#110、#75×#110、#77×#110、#95×#110 |
| `chengyinhub-xcx/scripts/ui-silent-error-allowlist.js` | 6 | #119×#120、#119×#144、#120×#144、#130×#164、#130×#166、#25×#130 |
| `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js` | 5 | #22×#120、#22×#25、#22×#37、#25×#37、#37×#120 |
| `chengyinhub-xcx/scripts/uiaudit/route-matrix.json` | 3 | #23×#25、#25×#119、#25×#27 |
| `chengyinhub-xcx/scripts/shot-matrix.js` | 3 | #23×#25、#23×#27、#25×#27 |
| `chengyinhub-xcx/scripts/fixtures.json` | 3 | #23×#25、#23×#27、#25×#27 |
| `chengyinhub-xcx/tests/unit/get-auth-matrix-contract.test.js` | 1 | #25×#27 |
| `chengyinhub-xcx/pages/roam/index.js` | 1 | #37×#38 |

### 冲突性质与处理
| 文件 | 性质 | 建议 |
|---|---|---|
| `ci/silent-error-allowlist.txt`（50 对） | 各 PR 都在**文件末尾追加一行**登记，位置相同所以冲突 | 取并集，保留每个 PR 的那一行；若两个 PR 改的是同一文件的计数行，按合并后的树重新统计计数。可以由协调者一次性重生 |
| `chengyinhub-xcx/scripts/ui-silent-error-allowlist.js` | 同上，数组末尾追加 | 取并集 |
| `scripts/uiaudit/action-ledger.json`、`route-matrix.json`、`scripts/fixtures.json`、`scripts/shot-matrix.js` | 生成物或冻结清单，各 PR 各自重生过 | **不要手工合**：协调者按合并后的树重新生成（`npm run audit:actions:update` 等，见 `COORDINATOR-PATCHES.md` BL-126 #170） |
| `tests/unit/action-ledger.test.js`、`route-matrix-freshness.test.js`、`title-system-owner-contract.test.js`、`nav-page-title-spacing-contract.test.js`、`get-auth-matrix-contract.test.js` | 冻结计数 / 名单，属于协调者统筹文件 | 协调者按合并后的计数重写一次；PR 正文里的补丁只当参考 |
| `tests/lint/ui-integrity-baseline.js` | 行号键：每个 PR 按自己的树算了 roam/profile 行号 | 按合并后的树重算；#22、#37 的映射不能叠加套用 |
| `subpackageMember/components/cy/profile/index.{js,json,wxml}` | **真实代码冲突**：举报串（#73 系）、#110 纠错入口、#109/#112、#68、#27×#65 改了同一个菜单、`usingComponents` 或相邻行 | 人工合：菜单项和 `usingComponents` 取并集；按 §4 顺序串行合，每合一个跑一次 profile 相关单测 |
| `pages/roam/index.js`（#37×#38） | 真实代码冲突：筛选与断网提示改了相邻行 | 先 #37 再 #38，人工合 |

## 3. 按 MERGE-ORDER §2 顺序的串行模拟

做法：从基线出发，按 BL-127 §2 的顺序逐个 `merge-tree`，干净的就用 `commit-tree` 生成临时合并提交，继续往下；**冲突的跳过**（不解决，只记录），所以后面的结果是「跳过冲突项之后」的近似。§3 取方案 A（#77 + #104，不合 #118），#75 不合。

| 步 | PR | 结果 |
|---|---|---|
| 1 | #40 | ✅ |
| 1 | #42 | ✅ |
| 1 | #91 | ✅ |
| 1 | #43 | ✅ |
| 1 | #55 | ✅ |
| 1 | #56 | ✅ |
| 1 | #62 | ✅ |
| 1 | #88 | ✅ |
| 1 | #120 | ✅ |
| 1 | #122 | ❌ `ci/silent-error-allowlist.txt` |
| 1 | #132 | ❌ `ci/silent-error-allowlist.txt` |
| 1 | #141 | ✅ |
| 1 | #144 | ❌ `chengyinhub-xcx/scripts/ui-silent-error-allowlist.js`、`ci/silent-error-allowlist.txt` |
| 1 | #147 | ❌ `ci/silent-error-allowlist.txt` |
| 1 | #152 | ✅ |
| 1 | #158 | ✅ |
| 1 | #164 | ✅ |
| 1 | #166 | ✅ |
| 2 | #23 | ✅ |
| 2 | #27 | ❌ `chengyinhub-xcx/scripts/fixtures.json`、`chengyinhub-xcx/scripts/shot-matrix.js`、`chengyinhub-xcx/scripts/uiaudit/action-ledger.json`、`chengyinhub-xcx/tests/unit/action-ledger.test.js`、`chengyinhub-xcx/tests/unit/nav-page-title-spacing-contract.test.js`、`chengyinhub-xcx/tests/unit/route-matrix-freshness.test.js`、`chengyinhub-xcx/tests/unit/title-system-owner-contract.test.js` |
| 2 | #25 | ❌ `chengyinhub-xcx/scripts/fixtures.json`、`chengyinhub-xcx/scripts/shot-matrix.js`、`chengyinhub-xcx/scripts/uiaudit/action-ledger.json`、`chengyinhub-xcx/scripts/uiaudit/route-matrix.json`、`chengyinhub-xcx/tests/unit/action-ledger.test.js`、`chengyinhub-xcx/tests/unit/nav-page-title-spacing-contract.test.js`、`chengyinhub-xcx/tests/unit/route-matrix-freshness.test.js`、`chengyinhub-xcx/tests/unit/title-system-owner-contract.test.js` |
| 2 | #22 | ❌ `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js` |
| 3 | #37 | ❌ `chengyinhub-xcx/scripts/uiaudit/action-ledger.json`、`chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`、`chengyinhub-xcx/tests/unit/action-ledger.test.js` |
| 3 | #38 | ✅ |
| 3 | #39 | ✅ |
| 3 | #60 | ✅ |
| 3 | #83 | ✅ |
| 3 | #113 | ✅ |
| 3 | #84 | ✅ |
| 4 | #57 | ✅ |
| 4 | #59 | ✅ |
| 5 | #119 | ❌ `chengyinhub-xcx/scripts/ui-silent-error-allowlist.js`、`chengyinhub-xcx/scripts/uiaudit/action-ledger.json`、`chengyinhub-xcx/tests/unit/action-ledger.test.js`、`chengyinhub-xcx/tests/unit/nav-page-title-spacing-contract.test.js`、`chengyinhub-xcx/tests/unit/route-matrix-freshness.test.js`、`chengyinhub-xcx/tests/unit/title-system-owner-contract.test.js`、`ci/silent-error-allowlist.txt` |
| 5 | #128 | ✅ |
| 5 | #123 | ❌ `ci/silent-error-allowlist.txt` |
| 6 | #73 | ❌ `ci/silent-error-allowlist.txt` |
| 6 | #74 | ❌ `ci/silent-error-allowlist.txt` |
| 6 | #110 | ✅ |
| 6 | #109 | ✅ |
| 6 | #112 | ❌ `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js` |
| 6 | #114 | ✅ |
| 6 | #138 | ✅ |
| 6 | #95 | ❌ `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js`、`chengyinhub-xcx/subpackageMember/components/cy/profile/index.json`、`chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml` |
| 6 | #68 | ❌ `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js` |
| 7 | #77 | ❌ `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js`、`chengyinhub-xcx/subpackageMember/components/cy/profile/index.json`、`chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml`、`ci/silent-error-allowlist.txt` |
| 7 | #104 | ✅ |
| 7 | #130 | ❌ `chengyinhub-xcx/scripts/ui-silent-error-allowlist.js` |
| 8 | #58 | ✅ |
| 8 | #65 | ✅ |
| 9 | #35 | ✅ |
| 9 | #98 | ✅ |
| 10 | #100 | ✅ |
| 10 | #117 | ✅ |
| 10 | #125 | ✅ |
| 10 | #134 | ✅ |
| 10 | #150 | ✅ |
| 10 | #101 | ✅ |
| 10 | #126 | ✅ |
| 10 | #151 | ✅ |
| 10 | #159 | ✅ |
| 10 | #167 | ✅ |
| 10 | #170 | ✅ |
| 10 | #171 | ✅ |
| 10 | #103 | ✅ |
| 10 | #172 | ✅ |
| 10 | #174 | ✅ |
| 10 | #175 | ✅ |
| 10 | #176 | ✅ |
| 10 | #177 | ✅ |
| 10 | #178 | ✅ |

模拟结论：共模拟 69 个（不含 #75、#118），52 个在这个顺序下能自动合入，17 个冲突。注意，被跳过的 PR 会让后面的结果偏乐观，例如 #38 显示 ✅，是因为 #37 已被跳过；两两预检里 #37×#38 是冲突的。人工冲突里大部分只是登记文件或冻结计数（协调者重生即可）；真正要人工合代码的只有 profile 组件（#112、#95、#68、#77）和 #37 之后的漫游串。

## 4. 建议处理顺序（对 MERGE-ORDER 的补充）

1. **先由协调者统一处理登记类文件**：合并任何功能 PR 前，约定 `ci/silent-error-allowlist.txt` 和 `scripts/ui-silent-error-allowlist.js` 的冲突一律取并集，或者合完一批后一次性重生。这样第 1 步的 #122、#132、#144、#147 和第 5 步的 #123 就不会卡住。
2. **CL 大卡（#23 → #27 → #25 → #22）每合一个就重生一次**冻结计数和清单（action-ledger、route-matrix、fixtures、shot-matrix、四个 contract 测试），再合下一个。模拟里 #27、#25 冲突就是因为 #23 先改过这些文件。
3. **#119 放在 CL 大卡之后**，并在 CL 重生之后再重生一次（#119 自带 C00 补丁，与 #23/#25/#27 计数冲突，见 `REVIEW-OPEN.md` §1）。
4. **profile 串**：#73 → #74 → #110 → #109 → #112 → #114 → #138 → #95 → #68，私信串 #77 也排在这里。每一步人工合 `profile/index.{js,json,wxml}` 的菜单项和 `usingComponents`（取并集）。#95 带着旧版 #73 提交，要先合入最新 #73。#27×#65 的 wxml 冲突在 #65（第 8 步）时处理。
5. **漫游**：#37 → #38 人工合 `pages/roam/index.js` 相邻行，之后 #39/#60/#83/#113 在模拟里都能自动合入；`ui-integrity-baseline.js` 在漫游串合完后统一重算。
6. **文档 PR（16 个）两两无冲突**，可以最后任意顺序合入。

## 复跑

```bash
B=origin/claude/mini-ui-features-20261015
git fetch origin '+refs/heads/claude/*:refs/remotes/origin/claude/*'
# 对基线
git merge-tree --write-tree --name-only "$B" origin/<branch>; echo rc=$?   # rc=1 表示冲突，第 2 行起是冲突文件
# 两两
git merge-tree --write-tree --name-only origin/<a> origin/<b>
# 串行：干净时 tree=$(… | head -1); cur=$(git commit-tree $tree -p $cur -p origin/<b> -m sim)
```
