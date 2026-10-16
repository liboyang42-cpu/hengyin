# 第十六批本地验收清单（BL-168–173，BL-174）

> 只写文档。第十六批是合并前核对：两张修本线新代码的小卡（白名单计数、注释/用例名），其余为核对文档。不改现有 UI 与标题，无协调者文件改动。
> 快照 2026-10-16 03:35Z，PR 状态以 GitHub 为准。期望值照抄各 PR 正文与监督评论；本卡只复核了两处：#217 分支上 `git grep 回执` 无结果；`git diff d0307a78 origin/claude/mini-ui-features-20261015 -- chengyinhub-admin chengyinhub-xcx` 为空（与 #220 一致）。
> 统一前置：代码命令在 `cd chengyinhub-xcx` 下执行；`ci/` 命令在仓库根目录执行。

## 一、逐卡验收命令与前后对照

| 卡 | PR | 须在其后合并 | 命令 | 改前 | 改后（期望） |
|---|---|---|---|---|---|
| BL-168 安全感受摘要 U5 白名单 | #216（监督 OK） | #109 BL-66、#206 BL-159 | `node scripts/ui-validity-gates.js \| grep ^U5` | #206 树：`U5 FAIL: 调用数变化: subpackageMember/components/cy/profile/index.js(白名单 1,实际 2)` | `U5:通过（96 文件 / 269 个调用均精确白名单并说明原因）` |
| | | | 根目录 `node ci/silent-error-gate.js` | 【超额】1 个文件 | 【未登记/超额/虚额】0；exit 1 只剩基线已有 `fabu`/`complaint` 两条【册子本身有问题】（监督实测，与本批无关） |
| | | | `node --test tests/unit/merchant-safety-summary-bl66.test.js` | — | pass 4 / fail 0 |
| BL-169 去残留「回执」 | #217 | #144 BL-100、#205 BL-158 | `node --test tests/unit/merchant-crm-campaigns.test.js tests/unit/audit-f01-f17-contract.test.js` | — | pass 12 / fail 0 |
| | | | `grep -rn 回执 pages/merchant/crm-campaigns tests/unit/merchant-crm-campaigns.test.js` | `view-model.js:3`、`merchant-crm-campaigns.test.js:41` 两处 | 无输出（本卡复核） |
| BL-170 U5 名额核对 | #219（只文档） | — | 在 #73 分支：`node tests/lint/ui-integrity.js` | — | 报 `report-sheet/index.js` 两条 U5 未登记（抽查 `U5-QUOTA.md` 数据） |
| | | | 读 `U5-QUOTA.md` §6 | — | C00 在三种处理方式中选定（抬上限到 37 / 改可见反馈 / 只放一个）；本批不裁定 |
| BL-171 冻结计数累计表 | 快照时只有认领分支 `claude/bl-171-frozen-counts`，无 PR | — | — | — | 待 PR 开出后补本行 |
| BL-172 基线漂移复跑 | 快照时只有认领分支 `claude/bl-172-drift-check`，无 PR | — | — | — | 待 PR 开出后补本行 |
| BL-173 零调用接口增量复扫 | #220（只文档） | — | 按 `ZERO-CALL-DELTA.md`「复跑」一节执行 | — | 第一次 `diff`（`d0307a78` vs 基线）为空；第二次（vs Codex 控制分支）只有 2 行：`im/send-receipt`、`roam/session/records`（基线在合并基之后新增，不算删除）；零调用仍 220 条 |

**一次跑**（#109、#206、#144、#205 与本批 PR 都合入后）：

```sh
cd chengyinhub-xcx
node --test tests/unit/merchant-safety-summary-bl66.test.js \
  tests/unit/merchant-crm-campaigns.test.js tests/unit/audit-f01-f17-contract.test.js
node scripts/ui-validity-gates.js
cd .. && node ci/silent-error-gate.js
```

期望：单测 fail 0；`ui-validity-gates` U1、U5 通过；`silent-error-gate` 只剩基线已有的两条册子问题。`ui-integrity.js` 的 U5（另一套门禁，`u5Silent` 冻结上限 29）在 C00 按 #219 §6 裁定前预计仍会因 #73/#110/#123/#83/#113 的未登记调用点转红，不算本批回归。

## 二、合并顺序与去重要点

- #216 ← #109 + #206；#217 ← #144 + #205。两者分支都已合入上游分支，合入前 diff 会带上游提交，只需审最后一个 fix 提交（#216 `54ed1fd9`、#217 `419f77e2`）。
- **#216 与 #207 重复**：#207 在 03:18Z 的 line-fix（`74f775e7`）补了内容相同的 #109 一节。只落一个：合 #216，或在 #109 上 apply #207 那一节后关闭 #216（见 #216 第二条评论）。合 #216 时，#207 的 #109 一节不要再 apply，否则 profile 计数会被改两次产生冲突。
- `scripts/ui-silent-error-allowlist.js` 与 `ci/silent-error-allowlist.txt` 同时被 #207 各节、#216 以及 #73/#110 等改；合并时保留各自行，按最终树计数。
- #219 只提供数据：全部 open PR 合入后 `ui-integrity` U5 需逐项理由 37，比冻结上限 29 多 8；#120（BL-77）与 #218（BL-166）争同一个第 29 名额。#38/#83/#113 各带 11 条 roam 既有 key 行号漂移，要在最终树上一次性换 key。
- #220 只登记，无新功能卡；`ZERO-CALL-DELTA-scan.js` 是文档目录下的辅助脚本，不进 CI。

## 三、需要设备走查的项

无。#216、#217 不改 UI 与文案（#217 只改注释与单测用例名）；#219、#220 只文档。

## 四、未验证

- 期望值照抄各 PR 正文与 #216 监督评论；除文首两处复核外，未在叠加树复跑；没跑 CI、全量单测、`ci/xcx-check.sh`。
- BL-171、BL-172 快照时无 PR，未收录。
