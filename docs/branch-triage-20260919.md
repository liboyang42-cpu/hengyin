# 本地未落地盘点与分支清理清单（2026-09-19 快照）

- 判定基准：`github/master` @ `dfad0ef31`（tree `caf1d864cefc4a5f239213ccc412093f78a3b6da`）— 盘点期间 master 仍在被其他会话合并，结论以本快照为准。
- 判定方法：**不**用 `git cherry`/`git log --grep`（squash 合并会让它们全线误报 MISSING），改用
  `git merge-tree --write-tree github/master <branch>` 得到「合并后的树」，再与 master 现树做 numstat。
  该树与 master 树相同 ⇒ 该分支对 master 净贡献为 0，内容已全部落地。

## 一、工作区未提交（当前分支 fix/audit-wiring-202609）

| 类别 | 数量 | 说明 |
|---|---|---|
| 已修改且内容在 GitHub 任何 ref 上都不存在 | 103 | 真未落地 |
| 已修改但内容与 PR #1114 某文件逐字节相同 | 14 | 已随 #1114 上架，未合并 |
| 未跟踪·内容与 PR #1114 相同 | 12 | 已随 #1114 上架，未合并 |
| 未跟踪·本地版本比 #1114 **更旧** | 3 | 见下节，**不要**从这里提交 |
| 未跟踪·GitHub 上完全不存在 | 7 | 真未落地 |
| 工作区删除（未提交） | 17 | playkit-woodfish / game-timer / stickerbook / decor-story |

### 比 #1114 更旧的三份本地副本（提交造成功能回退）

- `chengyinhub-admin/sql/migration_ums_member_identity_20260919.sql` — 本地写 `/api/member/identity`，#1114 已是 `/api/publisher/identity`
- `chengyinhub-xcx/utils/publisher-identity.js` — 本地把两个 URL 提成常量；#1114 刻意改回调用点字面量（提成常量会让 UI-GATE-0 的 U1 判为运行时变量路径，后端 Mapping 核不到）
- `chengyinhub-xcx/tests/unit/publisher-identity-contract.test.js` — 本地缺 #1114 已合入的 identityTicket/submitEpoch staleness 闸断言

### GitHub 上完全不存在的 7 份（真未落地）

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/PlayKitBingoRuntimeTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/TimeWindowSubscribeRuntimeTest.java`
- `chengyinhub-xcx/tests/unit/advanced-game-config-slowtask.test.js`
- `chengyinhub-xcx/tests/unit/dailysign-save-poster-contract.test.js`
- `chengyinhub-xcx/tests/unit/date-range-sheet-overrange.test.js`
- `chengyinhub-xcx/tests/unit/play-merchant-advanced-handoff-contract.test.js`
- `chengyinhub-xcx/tests/unit/playkit-action-routing.test.js`

## 二、本地分支 22 个「未推送 commit」— 结论：无需推送

本地 `fix/audit-wiring-202609` 是 **rebase 前的旧副本**：远端同名分支被 rebase 到新 master 后重推，
并由 **PR #1117 于 squash 合并入 master**（`71daef00a`）。22 个提交的内容全部在里面，
远端还多 4 个收口提交（`20f6c852f`/`cf230e023`/`5769cb135`/`fc5b0e993`），也已随 #1117 落地。
`git cherry` 报 19 个未合并是 rebase 改了 patch 上下文的假阳性。

## 三、292 个本地分支的落地判定

- CHANGES_TREE: 5
- CONFLICT: 262
- MERGE_TREE_FAIL: 5
- SAME_TREE: 20

| 合并净变化 | 分支数 | 含义 |
|---|---|---|
| 0 文件（已全部落地） | 25 | 可直接删 |
| 1-10 文件 | 78 | 多为单个真未落地小修，值得逐条看 |
| 11-100 文件 | 80 | 需判断 |
| >100 文件 | 109 | 基线过旧，合并会带回大量陈旧内容 |
| 无共同祖先 | 5 | 孤立历史（app-* 系列）|

### A. 净变化 0 文件 —— 内容已全部落地，删除不丢东西（20 个）

- `audit/fix-p1-08`
- `audit/fix-r1`
- `[历史工具路径或分支标识已移除]`
- `[历史工具路径或分支标识已移除]`
- `[历史工具路径或分支标识已移除]`
- `codex/repair-nearby-runner-shops-0913`
- `codex/repair-spoiler-access-0913`
- `feat/deploy-ui-lost-runner-audit`
- `feat/pp-backend`
- `fix/ci01-crm-flaky-requestid`
- `fix/community-legacy-projection-delflag`
- `fix/community-migration-having-guard-202609`
- `liboyang42-cpu/audit-fix-p1`
- `liboyang42-cpu/audit-fix-p1-08`
- `liboyang42-cpu/audit-fix-r1`
- `liboyang42-cpu/e12-invite-0916`
- `liboyang42-cpu/gitleaks-fixture-0916`
- `liboyang42-cpu/xcx-check-falsegreen-0915`
- `master`
- `shots-master`

### B. 从未推送到 GitHub 的分支（126 个）—— 按合并净变化降序

| 分支 | 合并会改动的文件数 | 新增行数 |
|---|---|---|
| `liboyang42-cpu/action-ledger-tool-0915-2` | 198 | 307922 |
| `liboyang42-cpu/shots-0915b` | 163 | 392255 |
| `shots-union` | 148 | 259510 |
| `audit/ui-int` | 132 | 124676 |
| `liboyang42-cpu/audit-ui-int` | 132 | 124676 |
| `rb-1095c` | 132 | 133921 |
| `liboyang42-cpu/publish-leftover-0915` | 121 | 307980 |
| `shots-mapteam` | 120 | 297930 |
| `shots-refundclock` | 120 | 308027 |
| `codex/ci-fix-0915` | 115 | 308006 |
| `liboyang42-cpu/branch-triage-0915-2` | 115 | 308006 |
| `liboyang42-cpu/dup-window-review-0915` | 115 | 308006 |
| `liboyang42-cpu/release-integration-0915` | 115 | 308006 |
| `pr1073` | 115 | 308006 |
| `chore/rm-automator-oneoff-scripts` | 114 | 0 |
| `codex/coop-pool-audit-0915` | 112 | 302689 |
| `codex/paused-triage-0915` | 112 | 302689 |
| `codex/roam-team-plan-0915` | 112 | 302689 |
| `fix/coop-final-integration-0913` | 112 | 302689 |
| `fix/coupon-lifecycle-integrate-0913` | 112 | 302689 |
| `fix/roam-comment-parser-0913` | 111 | 302662 |
| `liboyang42-cpu/repair-coupon-follow-review-0913` | 100 | 302493 |
| `feature/exp-card` | 83 | 21142 |
| `feat/run52-publisher-identity` | 74 | 337954 |
| `liboyang42-cpu/theme-world-template-owner-resubmit-0910` | 72 | 7185 |
| `liboyang42-cpu/repair-moderation-0913b` | 62 | 365068 |
| `liboyang42-cpu/repair-roam-growth-0913b` | 61 | 365004 |
| `perf/xcx-202609` | 58 | 2794 |
| `codex/repair-reaction-rounds-0913` | 57 | 365080 |
| `codex/repair-order-time-0913` | 56 | 364987 |
| `codex/repair-waitlist-token-0913` | 55 | 365095 |
| `codex/repair-coop-invite-id-0913` | 53 | 359374 |
| `codex/repair-coop-list-readonly-0913` | 53 | 359205 |
| `codex/repair-order-refresh-0913` | 53 | 365056 |
| `codex/repair-roam-recovery-0913` | 53 | 359284 |
| `audit/ui-b1` | 47 | 2522 |
| `audit/ui-b1-rv` | 47 | 2536 |
| `liboyang42-cpu/audit-ui-b1` | 47 | 2522 |
| `liboyang42-cpu/audit-ui-b1-rv` | 47 | 2536 |
| `audit/ui-17b` | 45 | 1660 |
| `audit/ui-b3-rv` | 45 | 1794 |
| `codex/repair-refund-notification-0913` | 45 | 1268 |
| `liboyang42-cpu/audit-ui-17b` | 45 | 1660 |
| `liboyang42-cpu/audit-ui-b3-rv` | 45 | 1794 |
| `liboyang42-cpu/audit-fix-notify` | 43 | 1279 |
| `liboyang42-cpu/audit-fix-p2-rv` | 41 | 26847 |
| `audit/fix-p2` | 40 | 26688 |
| `liboyang42-cpu/audit-fix-p2` | 40 | 26688 |
| `audit/ui-b3` | 37 | 1569 |
| `liboyang42-cpu/audit-ui-b3` | 37 | 1569 |
| `audit/ui-b2b` | 35 | 7114 |
| `liboyang42-cpu/audit-ui-b2b` | 35 | 7114 |
| `audit/ui-b4-rv` | 34 | 174081 |
| `liboyang42-cpu/audit-ui-b4-rv` | 34 | 174081 |
| `liboyang42-cpu/jr-x-int` | 34 | 6963 |
| `audit/ui-b4` | 29 | 115369 |
| `liboyang42-cpu/audit-ui-b4` | 29 | 115369 |
| `liboyang42-cpu/jr-x-kit-play` | 28 | 6388 |
| `feature/xcx-logo-black` | 27 | 98 |
| `liboyang42-cpu/audit-fix-p7` | 22 | 758 |
| `feat/pv-b64` | 21 | 29662 |
| `audit/ui-b2a` | 17 | 930 |
| `liboyang42-cpu/audit-ui-b2a` | 17 | 930 |
| `fix/predict-wait-label-kit-prop-gate` | 11 | 217257 |
| `audit/fix-p6` | 10 | 111 |
| `liboyang42-cpu/audit-fix-p6` | 10 | 111 |
| `audit/fix-p4` | 9 | 1980 |
| `liboyang42-cpu/audit-fix-p4` | 9 | 1980 |
| `liboyang42-cpu/jr-r16-pack` | 9 | 430 |
| `liboyang42-cpu/jr-x-kit-editor` | 9 | 22960 |
| `codex/repair-team-0913` | 8 | 139 |
| `audit/fix-weather` | 7 | 14486 |
| `codex/repair-f20-deepseek-0913` | 7 | 525 |
| `codex/repair-settlement-display-0913` | 7 | 64 |
| `liboyang42-cpu/audit-fix-refund2` | 7 | 475 |
| `liboyang42-cpu/audit-fix-weather` | 7 | 14486 |
| `audit/fix-p3` | 6 | 7751 |
| `audit/fix-p6-rv` | 6 | 14449 |
| `audit/p6-line` | 6 | 14442 |
| `liboyang42-cpu/audit-fix-p3` | 6 | 7751 |
| `liboyang42-cpu/audit-fix-p6-rv` | 6 | 14449 |
| `liboyang42-cpu/audit-fix-refund` | 6 | 226 |
| `liboyang42-cpu/audit-p6-rebase` | 6 | 14442 |
| `audit/fix-emp` | 5 | 1685 |
| `liboyang42-cpu/audit-fix-emp` | 5 | 1685 |
| `liboyang42-cpu/audit-ui-kb` | 5 | 1656 |
| `rebase/1088` | 5 | 1685 |
| `liboyang42-cpu/codex-audit-f18-0913` | 4 | 39 |
| `p2-director-fix-0915.backup-7860bfa0f` | 4 | 5243 |
| `audit/fix-p2-26` | 3 | 2594 |
| `audit/fix-p3-rv` | 3 | 7737 |
| `codex/repair-cancel-feedback-0913` | 3 | 29 |
| `liboyang42-cpu/audit-fix-p1-rv` | 3 | 204 |
| `liboyang42-cpu/audit-fix-p2-26` | 3 | 2594 |
| `liboyang42-cpu/audit-fix-p3-rv` | 3 | 7737 |
| `pr1092` | 3 | 6713 |
| `rb/fix/audit-p2-26` | 3 | 101 |
| `rebase/p3line` | 3 | 7737 |
| `audit/fix-money3` | 2 | 19 |
| `audit/fix-p2-26-rv` | 2 | 2590 |
| `audit/fix-run29` | 2 | 10 |
| `feat/pl-seed` | 2 | 33 |
| `feat/pp-xcx` | 2 | 13 |
| `liboyang42-cpu/audit-fix-money3` | 2 | 19 |
| `liboyang42-cpu/audit-fix-p2-26-rv` | 2 | 2590 |
| `liboyang42-cpu/audit-fix-run29` | 2 | 10 |
| `rebase/p2-26` | 2 | 2590 |
| `audit/fix-r1-rv` | 1 | 5 |
| `liboyang42-cpu/audit-fix-p5-rv` | 1 | 108 |
| `liboyang42-cpu/audit-fix-r1-rv` | 1 | 5 |
| `liboyang42-cpu/h-ticket-0916` | 1 | 336 |
| `rebase/r1` | 1 | 5 |
| `audit/fix-p1-08` | 0 | 0 |
| `audit/fix-r1` | 0 | 0 |
| `[历史工具路径或分支标识已移除]` | 0 | 0 |
| `[历史工具路径或分支标识已移除]` | 0 | 0 |
| `[历史工具路径或分支标识已移除]` | 0 | 0 |
| `codex/repair-nearby-runner-shops-0913` | 0 | 0 |
| `codex/repair-spoiler-access-0913` | 0 | 0 |
| `feat/pp-backend` | 0 | 0 |
| `feature/app-platform-integration-0826` | ? | ? |
| `liboyang42-cpu/audit-fix-p1` | 0 | 0 |
| `liboyang42-cpu/audit-fix-p1-08` | 0 | 0 |
| `liboyang42-cpu/audit-fix-r1` | 0 | 0 |
| `liboyang42-cpu/e12-invite-0916` | 0 | 0 |
| `shots-master` | 0 | 0 |

### C. 有 OPEN PR 的本地分支（等 review/merge，别删）

- `chore/home-drop-search-entry`
- `feat/run52-publisher-identity-v2`
- `fix/community-legacy-projection-delflag`
- `fix/community-migration-having-guard-202609`
- `fix/del-home-search`

### D. PR 被关闭且未合并（内容确认废弃）

- `codex/product-repair-0913`

## 五、盘点期间发生并发变更（读这段再看上面的数字）

本报告统计进行中，**另一个会话在同一 worktree 上提交了 `b3c4eccfa`**（2026-09-19 21:31 -0700，
"端到端审查收口批 —— 商家数字位一律落数不放横杠 + 死按钮接线 + 台账重钉"），
把第一节里 91 个「未提交」文件收进了这个提交。因此：

- 第一节文件数已过期。截至复核：未提交 = **62 修改 + 13 删除 + 20 未跟踪 = 95 项**
  （其中 `docs/branch-triage-20260919.md` 是本报告自身）。
- 未推送提交从 22 变 **23**，且这 23 个要分开看：
  - 前 22 个 = rebase 前旧副本，内容已随 **PR #1117** 落地 master，**无需推送**；
  - `b3c4eccfa` = **确有未落地内容**（`merge-tree` 判定它与 master 有 93 文件净差异），
    这才是真正等 ship 的那个提交。
- 复核用的 `git fetch` 之后 `github/master` 仍为 `dfad0ef31`，第一节的
  103 / 14 文件判定在最新 refs 上重跑一次，数字不变（可信）。
- 第一节"7 份 GitHub 上完全不存在的未跟踪文件"中，
  `chengyinhub-xcx/tests/unit/date-range-sheet-overrange.test.js`
  已被 `b3c4eccfa` 收走，实际剩 **6 份**。

> 同一 worktree 有并发写入时，任何「未落地计数」都只是瞬时快照。要拿稳定数字，
> 需先让并发会话停下来，或把本会话切到独立 worktree 再统计。

## 六、78 个「合并净变化 1–10 文件」分支逐条判定

三重过滤，避免把"陈旧"当"未落地"：
1. 剔除派生件（`action-ledger.json`、`impact-map/route-matrix`、`fixtures.json`、`ui-integrity-baseline.js`、`action-ledger.test.js`、`ci/migration-dependencies.json`、`docs/`、截图相册）——合并只会带回旧台账，不是修复。
2. **方向判定**：取分支相对自身 merge-base 的新增行，查 master 现版本是否已含 ⇒ 区分「确有独有内容」与「只是比 master 旧」。
3. **归属判定**：该文件版本 blob 是否存在于 GitHub 任一 ref（含开放 PR）⇒ 区分「从没上过 GitHub」与「已在开放 PR 上等合并」。

去重后 48 组（同一工作在 `audit/*`、`liboyang42-cpu/audit-fix-*`、`rebase/*` 上有 2–4 份副本），**其中 34 组内容已全部在 GitHub 上**（多数已进 master，少数在开放 PR 里）。

### A. 确有未落地内容、且 GitHub 上一处都没有 —— 需要决策（按分量排序）

| 分支 | 日期 | 未落地内容 | 从未上 GitHub 的文件数 |
|---|---|---|---|
| `liboyang42-cpu/jr-x-kit-editor` | 09-16 | 创作端「推理类」三玩法（排序/连线/分类）配置面板，答案不下发 | 6 |
| `liboyang42-cpu/jr-r16-pack` | 09-16 | 模板市场 `pack_type` 形态筛选轴；主体在开放 PR **#1028**，分支另有比 #1028 更新的修订 | 5 |
| `audit/fix-p3` / `liboyang42-cpu/audit-fix-p3` | 09-18 | IM/内容互动 MyBatis 集成测试 + `ImServiceImpl` | 6 |
| `audit/fix-p3-rv` / `rebase/p3line` / `pr1092` | 09-18 | 同上复审返修副本 | 8 |
| `liboyang42-cpu/p2-director-fix-0915`（+ `.backup-7860bfa0f`） | 09-15 | 导演台/结算页入口收敛 | 4 |
| `audit/fix-weather` | 09-18 | 天气取消主办闸相关（含 `HostWeatherCancelTest`） | 3 |
| `audit/fix-p6-rv` / `audit/p6-line` | 09-18 | 主题取消只取消未结束场次的返修 | 2 |
| `audit/fix-money3` / `audit/fix-run29` / `audit/fix-p2-26` / `liboyang42-cpu/audit-fix-refund{,2}` / `audit-fix-p1-rv` | 09-18 | 各 1–6 个文件版本从未上 GitHub，但 master 已有同题修订版 ⇒ **大概是中间版本，需人工确认是否已被后续取代** | 1–6 |

### B. 已在 GitHub 的开放 PR 上，不用重做，只需推合并

| 分支 | PR | 说明 |
|---|---|---|
| `chore/home-drop-search-entry` | #1119 | 首页撤掉搜索药丸（用户改令推翻拍板 #15）；本地与远端 blob 一致 |
| `fix/del-home-search` | #1120 | 同一目标的另一方案，与 #1119 并存，**二者需择一** |
| `feat/run52-publisher-identity-v2` | #1114 | 发布者实名三入口 |
| `audit/fix-emp`、`audit/fix-p4`、`audit/fix-p6`、`codex/repair-*`、`liboyang42-cpu/{fix-club,fix-consent,fix-p1,rule-*,it-register,f21-fix,...}-091*` | — | 全部文件内容已在 GitHub（已进 master 或在开放 PR），分支本身可删 |

### C. 纯派生件/文档，无任何未落地源码（14 个，可直接删）

`audit/fix-p2-26-rv`、`audit/fix-p3-rv`*、`audit/fix-r1-rv`、`fix/publish-fab-template-only`、
`liboyang42-cpu/audit-fix-p2-26-rv`、`liboyang42-cpu/audit-fix-p3-rv`*、`liboyang42-cpu/audit-fix-p5-rv`、
`liboyang42-cpu/audit-fix-r1-rv`、`liboyang42-cpu/claim-revoke-0916`、`liboyang42-cpu/fix-b2-0917`、
`pr1092`*、`rebase/p2-26`、`rebase/p3line`*、`rebase/r1`

> \* 标注的四个（`fix-p3-rv`/`audit-fix-p3-rv`/`pr1092`/`rebase/p3line`）合并净变化虽只碰派生件，
> 但 blob 检查显示其 6–8 个文件版本从未上 GitHub（属 A 组第 4 行），**不要按 C 组删**。

### 方法学注脚（踩过的坑）

- `git cherry` / 按 commit 标题 grep 在 squash + rebase 双改下全线假阳性（本轮 22 commit 报 19 MISSING，实为已落地）。
- `git merge-tree` 的 CONFLICT **不能**当未落地证据：陈旧分支即使内容全落地，在 `action-ledger.json` 这类生成文件上仍必冲突。
- 按路径查 blob 会对「文件被搬走」报假 NOWHERE——本仓 PR #1103 把 134 个主包文件下沉进分包，
  `utils/playkit-view.js`、`utils/publish/advanced-game-config.js` 等因此被判 UNLANDED，需再做全树 grep 复核。

## 七、已删除的零贡献分支与恢复清单

删除时基准 `github/master` @ `6dcb206c9`。以下 17 个分支经 `merge-tree` 证明**合并进 master 后树零变化**（对 master 无任何独有内容），已删除本地 ref。

恢复方式：`git branch <分支名> <SHA>`

| 分支 | tip SHA | 最后提交 | 标题 |
|---|---|---|---|
| `audit/fix-p1-08` | `304250ebaf7e139a921b8bda9c7e339f762b9869` | 2026-09-18 | docs(coop): config_deadline 字段注释标为历史列,过期任务 javad |
| `audit/fix-r1` | `e14916475df7868967ca228eb38e345a72eb9c13` | 2026-09-18 | chore(db): 8-07 给 role 2 补 business:analytics:li |
| `[历史工具路径或分支标识已移除]` | `e0c65fd884f4fdabdd662b635143f00f875bcc07` | 2026-09-12 | feat(xcx): 审核收口——占位入口做成或删掉，活动未售可改 (#1067) |
| `[历史工具路径或分支标识已移除]` | `e0c65fd884f4fdabdd662b635143f00f875bcc07` | 2026-09-12 | feat(xcx): 审核收口——占位入口做成或删掉，活动未售可改 (#1067) |
| `[历史工具路径或分支标识已移除]` | `e0c65fd884f4fdabdd662b635143f00f875bcc07` | 2026-09-12 | feat(xcx): 审核收口——占位入口做成或删掉，活动未售可改 (#1067) |
| `codex/repair-nearby-runner-shops-0913` | `4ccbfbdb8ec50e8483bfd6dd10ba20b887eeae22` | 2026-09-13 | fix: 修复附近人店铺图片查询排序规则冲突 |
| `codex/repair-spoiler-access-0913` | `f9ac46dd9003f0f6f9fa8c54a83e61bde68217e1` | 2026-09-13 | fix: 正确回执剧透偏好并修复剧情资格查询 |
| `feat/deploy-ui-lost-runner-audit` | `e28d71235d8590796e7e903feaf19f3f164f09c0` | 2026-09-15 | ci: 部署链路失败即刻对账,不等 6 小时窗口 |
| `feat/pp-backend` | `f22d1676a96431ae73d09aa249c991a7acf00b3d` | 2026-09-18 | feat(playkit): 呈现方式 present 进根层——只能全屏的段配 inline  |
| `fix/ci01-crm-flaky-requestid` | `2c147430a5bef8afcfc9c52a7a4d729262a32251` | 2026-09-18 | test(crm): 修 CI-01 不稳定断言——requestId 只禁号码本身，不禁随机串 |
| `liboyang42-cpu/audit-fix-p1` | `0d9224a2fe01fb60f6d04a1ede02767f448e479d` | 2026-09-18 | 商家退款售后两页照 Revolut 结构重做 + 工作台快捷圆钮/头像放大 (#1084) |
| `liboyang42-cpu/audit-fix-p1-08` | `304250ebaf7e139a921b8bda9c7e339f762b9869` | 2026-09-18 | docs(coop): config_deadline 字段注释标为历史列,过期任务 javad |
| `liboyang42-cpu/audit-fix-r1` | `e14916475df7868967ca228eb38e345a72eb9c13` | 2026-09-18 | chore(db): 8-07 给 role 2 补 business:analytics:li |
| `liboyang42-cpu/e12-invite-0916` | `90e66d70fb5c8690bd69fdda560d650a6f4c6639` | 2026-09-16 | fix(test): F13 会话关联假数据降熵,解 master 上 gitleaks 误报挡 |
| `liboyang42-cpu/gitleaks-fixture-0916` | `6f98f095a1ca5ce02abcccb6514f65ff6cf0929f` | 2026-09-15 | fix(test): F13 会话关联假数据降熵,解 master 上 gitleaks 误报挡 |
| `liboyang42-cpu/xcx-check-falsegreen-0915` | `a341bd01a15bab41a322c59796ab4e952f3403bc` | 2026-09-15 | fix(xcx): U3/U4/U6 读基线不再吞错——新增文件显式全量扫描,基线读失败拒绝静默 |
| `shots-master` | `9becda644fbd3ea0501b856c05aaad57ea6f47f4` | 2026-09-16 | release: 0915 全产品修复轮集成主线(258 commit · 11 条迁移已落库  |

> 未删但同为 FAIL/零贡献的保留项：`feature/app-{integrity-ai,push,storekit,platform-integration}-0826`、`feature/apple-iap-reservation-0827` —— 与 master **无共同祖先**，`merge-tree` 报 `refusing to merge unrelated histories`，其零贡献是 awk 把 `"?"+0` 读成 0 的假象，实含 4200+ 文件差异（App 端 FCM 推送等），**不可删**。
> `fix/temp-coupon-sheet-portal` 虽为零贡献，但正被 worktree `/private/tmp/fix07` 检出，未删。

## 八、A 组第一项已试移植：结论是「半条功能」，卡在玩家三屏

`liboyang42-cpu/jr-x-kit-editor`（tip `2204f5e79`，base `90e66d70f`，落后 master 42 个 commit）**内容确证全域未落地**：`推理类` / `answerOrder` 在 `github/master` 全树零命中。

已把它移植到 `github/master @ 6dcb206c9`，检查点在 **`port/jr-x-kit-editor` @ `a9559d2c7`**（隔离 worktree `/tmp/jrx-port`，本地分支，未 push）。移植中的五处冲突全是「两边都另加」，按下述解法保留双方：

| 处 | 解法 |
|---|---|
| `node-game-catalog.js` 段名注释 | 采用分支的去计数写法（master 的「十九个」在 09-17 加段后本就已是假的），`ENCOUNTER_SECTIONS` 豁免表整块留 master |
| `advanced-game-config.js` SECTIONS | `…concat(STORY_SECTIONS).concat(INFERENCE_SECTIONS)`，两组并存 |
| `qa` 默认值 / normalize / validate | `shotNote`（master）与 `multi`（分支）并列，互不覆盖 |
| `temp/index.wxml` 选项问答提示 | 分支那条 `{{multi ? … : …}}` 是**替换**旧定值句，keep-both 会并排留两条相同文案 → 删旧句 |
| `temp/index.js` 方法表 | 补回被冲突吃掉的 `removeNotePreset` 收尾 `},` |

配套修正：测试里两处 `require('../../utils/publish/node-game-catalog.js')` 跟 #1103 改到 `pages/publish/utils/publish/`；「data-path 必须真存在」那条扫描收窄到推理类三段，否则会把 master 新增的 `profile.questions` 运行时拼接表达式当字面量误判。台账重跑 4140 controls。

### 为什么这条不能就这样合

`roundtrip` 14/14 绿，但 `playkit-view-contract` 有条 2026-09-17 起的新门禁（`tests/unit/playkit-view-contract.test.js:410`）：玩法目录里每个 `PLAYKIT_SECTION` 都必须在 `SCREEN_OF` 里指明用哪一屏，且 `pickPlayKit` 要还出那个 type。

**`cy-playkit-{sort,match,classify}` 三个玩家屏组件在本仓一处都不存在**（全树 grep 零命中，分发器 `pages/play/components/playkit/index.wxml` 也没有对应分支）。分支当年只补了 `TYPE_OF` 桩。今天照桩把 `SCREEN_OF` 填上，门禁是绿了，实际是往分发器里造三个渲染不出任何东西的 type —— 商家配得出排序节点，玩家那边一片空白。这正是 `TIMED_GAMES` 注释里那条铁律：「给一个不会执行的开关，比不给更坏」。所以三条红留在检查点上没压。

服务端这一半倒是齐的：`AdvancedGameRuntimeServiceImpl` 有 `SUBMIT_SORT / SUBMIT_MATCH / SUBMIT_CLASSIFY` 判定，`AdvancedGamePublicProjection#copySort` 明确剥掉 `answerOrder`。**缺的只有小程序玩家端三屏。**

### 剩余 A 组（未动）

`jr-r16-pack`（pack_type，主体在 #1028）、`audit/fix-p3` 系列 IM 测试、`p2-director-fix-0915`、`audit/fix-weather`、`audit/fix-p6-rv`。

## 九、A 组第二项：`jr-r16-pack`（模板市场 `pack_type` 形态筛选轴）

**性质与第一项相反 —— 这一条是完整的一半，可以单独合。** PR #1028 自 2026-09-09 开着，head `6298d0a40` 落后 master 41 个 commit；本地 `liboyang42-cpu/jr-r16-pack` 是它的 rebase 版，**多出两处真实修订**：

| | #1028 head | 本地分支 |
|---|---|---|
| 迁移 SQL | 60 行 | **43 行**（`1d912a639` 收敛了范围） |
| `TemplatePackTypeTest` | 128 行 | **206 行** |
| H2 真执行结果集契约 | 无 | **`TemplateLibraryPackTypeFilterMyBatisTest`（`ff2f241fe` 新增）** |

`pack_type` 在 `github/master` 全树零命中 ⇒ 整条功能确实一天都没落地。

### 移植验证（`port/jr-r16-pack @ 10dfabe36`，worktree `/tmp/jrr16`）

- `git cherry-pick 1b3ca1c4d..ff2f241fe` 三个 commit **零冲突**落在 `6dcb206c9` 上
- `mvn -pl chengyinhub-admin -am -Dtest='Template*Test,ApiTemplate*Test'` ⇒ 13 个测试类 106 项全绿（含 28 项 `ApiTemplateSecretsGateTest` 回归）
- `bash ci/migration-safety-check.sh` ⇒ 五道门全 OK（新增 1 个迁移已声明依赖，按文件名顺序跑满足批内依赖）

### 合并前必须先落的硬依赖（不是可选项）

迁移 SQL 自己写着「**合并 jar 前跑**」，而且这是真的：`selectCmsTemplateLibraryVo` 把 `pack_type` **无条件加进了 SELECT 列表**。jar 先上、列还没加 ⇒ 模板库每一次读都 `Unknown column 'pack_type'`，是全站级故障，不是新功能不显示。筛选条件本身是 `<if test="packType != null">`，不传不过滤，这半边安全。

**只读预检（2026-09-20 补齐，全程只读）**：真库 `cms_template_library`

| 项 | 结果 |
|---|---|
| `pack_type` 列 | **不存在**（`information_schema.COLUMNS` 计数 0）⇒ 迁移确实从没跑过 |
| MySQL 版本 | 8.0.45 ⇒ `ADD COLUMN ... NOT NULL DEFAULT 0` 走 **INSTANT**，只改元数据，不重建表、不锁写 |
| 引擎 / 行数 | InnoDB / 约 73 行 |
| 本表 trigger | 无 ⇒ 写集合不会经 `ACTION_STATEMENT` 扩到别的表 |
| 外键（引用本表 / 本表引出） | 双向均无 ⇒ 无 `CASCADE`/`SET NULL` 连带改写 |
| 幂等性 | 迁移自带 `information_schema` 判存在再 `ADD`，重跑不报 1060 |
| 可逆 | `ALTER TABLE cms_template_library DROP COLUMN pack_type;` |

（首次尝试时 `~/.zshrc` 里那份口令 1045 拒通，后经用户提供当前口令完成预检。skill 里那条「该密码曾长期暴露在公网，应轮换」的遗留待办仍未闭环。）

### 迁移已于 2026-09-20 落库（用户批准执行）

`run_migration.py --config <druid.yml> --sql migration_template_library_pack_type_20260905.sql`
经 ssh 隧道打到 `47.103.124.64/chengyinhubdb`，7/7 句 OK，分句器用的是 worktree 里的 `ci/sql_splitter.py`。

**新只读连接回读**（不用执行会话自身的回执）：
- `pack_type  tinyint  null=NO  模板形态:0单节点玩法 1剧情包 2店铺IP包` ⇒ 列已存在，形状与注释正确
- `SELECT pack_type, COUNT(*) GROUP BY pack_type` ⇒ `((0, 73),)` —— 73 行全部落「单节点玩法」，没有 NULL、没有误标

⇒ **jar 部署的硬依赖已解除**：`selectCmsTemplateLibraryVo` 那个无条件的 `pack_type` 现在读得到。
回滚仍可逆：`ALTER TABLE cms_template_library DROP COLUMN pack_type;`

### 已开 PR：#1123 替代 #1028（用户选 (b)）

- 分支 `github/feat/template-market-pack-type-0920` = 本地 `port/jr-r16-pack @ 10dfabe36`，三个 commit 零冲突落在 `6dcb206c9`
- **#1123** 已开，描述里带「## 影响分类 + ③」以过 `migration-safety-check.sh [4/5]` 声明门，并把「迁移已落库 + 回读证据」写成事实
- **#1028** 已关，留了替代说明（head 停在 `6298d0a40`、落后 41 commit；三处差异；以及 selectVo 无条件加列造成的部署顺序硬依赖）
- 本地检查点分支 `port/jr-r16-pack` 与 worktree `/tmp/jrr16` 保留（/tmp 每会话被清，分支 ref 在仓库对象库里，丢不了）
⚠️ 但**在列已存在的情况下回滚 = 把 master 上还没有、而这份代码已有的 selectVo 反向劈开**，
真要回滚得先确认没有别的分支依赖它。

### 原三动作推论（保留存档）

推论：这条要落地需要三个动作，前两个我能做，第三个只能你做 ——
1. 把 `feature/shop-npc-p4-0905` 更新成 rebase 版（force-push 覆盖 #1028 的 head，**破坏性、影响 GitHub 上的开放 PR，等批**）
2. #1028 描述里补上「已 rebase + 迁移收敛 + H2 契约」并等 CI
3. 真库先跑 `migration_template_library_pack_type_20260905.sql`，**再**部署 jar

### 与第一项的对比（为什么这条能合、那条不能）

`jr-x-kit-editor` 缺的是玩家端三屏 —— 商家配得出、玩家看不见，属于「给一个不会执行的开关」。
`jr-r16-pack` 缺的是运营在后台逐个标形态 —— 接口能力先齐、UI 后接，中间态是「筛选轴没数据」，不是「功能坏了」。

### #1123 CI 实况 + Funds IT 卡死的真根因（2026-09-20 07:55 UTC 直查）

`gh api .../commits/10dfabe366/check-runs`：**11/12 已定**（Backend tests、Mini program static
checks、Migration layout/safety gate、Fast gates、Secret scan、Detect changed areas、
Funds IT gate self-test 全 success；Admin UI 三项 skipping）。唯一没出结论的是
**`Funds MySQL IT (50/9/30, skipped=0, fail-closed)` = queued**，自 07:21:28Z 起没动过。

它**不会自己跑完**，原因不在 CI 配置，在本机：

- `gh api .../actions/runners` → `arlan-mac-chengyin` 1~4 **全部 `status=offline`**；
- 但 `pgrep runsvc.sh` → 6 个 runner 进程**活着**（本机 `~/actions-runner*`）；
- `_diag/Runner_20260920-0723*-utc.log` 每 30 秒重复：
  `SocketException (61): Connection refused (127.0.0.1:7897)` → `Retriable` → `Sleeping 30s`；
- `lsof -iTCP:7897` 无人监听，只剩 `clash-verge-service` 这个特权 helper，**Verge 内核没起**。

即：runner 的出网走 `127.0.0.1:7897` 的 Clash Verge，代理内核一停，runner 与 GitHub broker
的 session 就断，面板上显示 offline，job 永远排在队列里。**修法是起来代理，不用重跑 CI**，
runner 重连后会自己接走 queued 的 job。同因的另一份证据：仓库里 `ann-b-0806`、
`tandianri-uishoot-master-0818` 两个 GitHub CI 从 8 月起 `queued` 至今 —— 同一个症状，不是巧合。

PR 状态：`state=OPEN`、`mergedAt=null`、`mergeStateStatus=BLOCKED`。**未被合并，我也没合**。

#### 恢复经过（08:11:48Z 收官）

真根因不是内核没起，而是 **Verge 处于 `mode=direct`**：mihomo 在跑、runner 与 7897 已 ESTABLISHED，
但流量出代理后原路直发 —— 与「没开代理」等价。判据：broker 域名走代理与 `--noproxy '*'` 症状完全一致
（TLS 拿到 0 字节），而 `api.github.com` 两边都通（它本来就能直连，不能当对照物）。

处置两步（都记在这里，便于回溯）：
1. `PATCH /tmp/verge/verge-mihomo.sock:/configs {"mode":"rule"}` → 切回规则模式。
   节点侧核过：`US-Reality` 到 broker 206 ms 可达，`US-WS-TLS` / `US-KCP` 连 api.github.com 都不通。
2. runner 恢复 online 且空闲后，**排队 46 分钟的 job 不会自己接走**；`gh run rerun --job` 对 queued job
   报 `cannot be rerun`，改用 `gh run cancel 35496635820` 触发重新派发，08:08:26Z 真正开跑。

结果：`Funds MySQL IT` 08:08:26Z → 08:11:48Z **success**（3 分 22 秒，落在 master 历史 4~10 分钟量级）。
12 项检查全部落定：9 success + 3 skipped（Admin UI 三项，无 UI 改动）。
PR #1123 现为 `mergeStateStatus=CLEAN`、`mergeable=MERGEABLE`，**仍未合并**（`state=OPEN`、`mergedAt=null`）。

#### 已合并（2026-09-20 08:14:34Z）

用户批「合 #1123」→ squash 合并为 `d37e89003`。落地核对不是看 PR 页面绿字，是回 master 读盘：

- `git show github/master:.../CmsTemplateLibraryMapper.xml` → `selectCmsTemplateLibraryVo` 里
  确含 `...,rating,use_num,pack_type, create_by,...`，resultMap / `<if packType>` / insert / update 五处齐全；
- `chengyinhub-admin/sql/migration_template_library_pack_type_20260905.sql` 已在 master 树里。

部署顺序风险已提前卸掉：**列 `pack_type` 在合并前就已在真库存在并回读确认**（`tinyint NOT NULL`、
`GROUP BY pack_type ⇒ ((0,73),)`），所以 master 的新 jar 上机不会撞 "Unknown column"。

⚠️ 遗留：合并会自己触发 master 的 `GitHub CI` + `Funds MySQL IT`（08:14:38Z 起跑），
本仓的 `Deploy verified master to ECS` 是**跟着 master 验证结果走**的 —— 也就是说这次「合」并不等于
纯代码动作，部署链路会被带动。合并前 AGENTS.md 讲的「部署需用户操作」在这条流水线上并不成立，留意。

顺带记录：B 组那条 #1119 / #1120 之争，**#1120 已于 07:56:47Z 被另一会话合入**（`99f48671f`，
首页搜索入口摘除），#1119 仍是 OPEN 但已被同一结果的实现取代 —— 待用户批复是否直接关掉。

## 十、A 组剩下四项复判（2026-09-20，基准已换成 master `d37e89003`）

快照那四条「确有未落地内容」的判定**在 master 前进三个提交后全部作废**：四项逐行核对后
**零真未落地内容**，方法是对每个文件取「分支相对自身 merge-base 的新增行」，再查 master 现版本是否已含。

| 项 | 快照 | 复判 | 残留行的真身 |
|---|---|---|---|
| `audit/fix-p3` | 6 文件未落地 | 已吸收 | 2 行是旧写法：master 已把 `cms_registration` 插入的 `member_id` 提成参数（`VALUES (?,?,?,?,?)`），分支还写死 `VALUES(?,?,1,?,?)` |
| `audit/fix-weather` | 3 文件（含 `HostWeatherCancelTest`） | 已吸收 | 6 行是 master 后来加第 6 参 `refuseSettled` **之前**的旧调用；mapper 1 行是 `vcs` 别名加上之前的旧版（`where t.id = view_creative_square.data_id` vs master `= vcs.data_id`）。测试文件在 master 上已扩到 455 行 |
| `audit/fix-p6-rv` | 2 文件 | 已吸收 | 与 weather 同一批文件的同一批旧行 |
| `liboyang42-cpu/p2-director-fix-0915` | 4 文件 | 已吸收 | `resolvePrimary` master 版是分支的严格超集（多了 `_canDirect` / `_canManageSessions` 闸与 `selfrun` 态）；契约测试 master 用 `status:1,isOwner:1` 新夹具并加了 wxml 真渲染断言，分支还是 `auditStatus:3` 旧形态 |

### 我这轮自己踩的坑（务必带走）

**主工作区是脏的（95 个未提交项），所以按路径 `grep <file>` 读到的是本地旧副本，不是 master。**
本轮就是靠这个脏副本差点得出「结算页注释在 master 上是错的、需要救一条」的假结论 ——
master 的 `pages/club/settlement/index.js` 头部早已重写成「金额由服务端区分核算净额与实际入账」，
那句「`/api/club/settlement/*` 目前不存在」只存在于本地脏树。正解：读 master 一律
`git show github/master:<path>` 或另开干净 worktree，别在脏主树里 grep 判事实。

## 十一、A 组四项对应分支的删除清单（2026-09-20，基准 master `d37e89003`）

删除前逐个复核「非派生残留行」全部为**master 已取代的旧写法**，其中新增核对一项：
`chengyinhub-xcx/components/cy/post-compose/index.js` 在 master 上「路径不存在」是 PR #1103 分包下沉
造成的假象 —— 它已搬到 `chengyinhub-xcx/pages/square/components/cy/post-compose/index.js`，
分支新增的两行（`// 宿主页常驻不卸载…(3-19)` + `this._published = false;`）在搬后的 master 版
第 189-190 行**逐字都在**。⇒ 判已落地。

这些分支在 GitHub 上没有任何关联 PR（逐个 `gh pr list --head` 查过，全「无」）。

| 分支（本地 ref） | tip SHA | 判定 |
|---|---|---|
| `audit/fix-p3` | `cc917b82c892577cbd5dff37527f22f908d507da` | 残留 2 处均为旧写法/已搬路径，master 已含 |
| `liboyang42-cpu/audit-fix-p3` | `cc917b82c892577cbd5dff37527f22f908d507da` | 同 tip 的副本 |
| `audit/fix-weather` | `a3cb8cd9efcc8827b5a4ee17906c86b7ba2f2088` | 6+1 行是加 `refuseSettled` / `vcs` 别名前的旧形 |
| `liboyang42-cpu/audit-fix-weather` | `a3cb8cd9efcc8827b5a4ee17906c86b7ba2f2088` | 同 tip 的副本 |
| `audit/fix-p6-rv` | `905b38822ec528daf0fdecd9e1fbbbc9a130c13f` | 1+1 行同上 |
| `liboyang42-cpu/audit-fix-p6-rv` | `905b38822ec528daf0fdecd9e1fbbbc9a130c13f` | 同 tip 的副本 |
| `audit/p6-line` | `4a4ee175c252d843b6d97eca92f072b9141aab69` | 同上（p6line 的早期 tip） |
| `liboyang42-cpu/p2-director-fix-0915` | `40cb23485ad8552c0e5079ca8a1053143ee351d5` | 逻辑与测试被 master 超集取代；GitHub 上仍有同名远端分支，本轮只删本地 |
| `p2-director-fix-0915.backup-7860bfa0f` | `40cb23485ad8552c0e5079ca8a1053143ee351d5` | 同 tip 的备份 |

> 计数更正：上一条消息我说「8 个 ref」，按四类逐个展开副本实际是 **9 个**
> （漏算 `liboyang42-cpu/audit-fix-p6-rv`）。多出的这个与 `audit/fix-p6-rv` 同 tip、同一批文件。

恢复：`git branch <分支名> <SHA>`（tip 全 40 位见本节表格的前 9 位 + `git reflog` 30~90 天内可查）。

## 十二、§6A 末行「中间版本」六条复判（2026-09-20，基准 master `d37e89003`）

| 分支 | tip | 残留 | 复判 |
|---|---|---|---|
| `audit/fix-p2-26` | `0badacaa1` | 无（非派生 0 文件） | 已吸收 |
| `liboyang42-cpu/audit-fix-p1-rv` | `88d964af1` | 无 | 已吸收 |
| `audit/fix-money3` | `2ef07538d` | RefundServiceImpl 3 行、TeamGroupServiceImpl 2 行 | 已吸收：master 的 FX-06 注释已扩成「FX-06 / FX-14」合并版并补了绕过机理；`inspectPaidOrderForCancellation` 在 master 是 4 参（多 ownerType），分支是 3 参旧形 |
| `audit/fix-run29` | `fe990425d` | IWxContentSecurityService 1 行 | 已吸收：master 同一段 javadoc 在前一行加了「其他」二字，语义一致 |
| `liboyang42-cpu/audit-fix-refund2` | `0b2026246` | TeamGroupServiceImpl 6 行 | 已吸收：见下条的抽类 |
| `liboyang42-cpu/audit-fix-refund` | `11b37b639` | TeamGroupServiceImpl 32 行 | **已吸收，但差点误判成整块丢失** —— 私有 helper `remainingOrderCash(order, payer)` 在 master 全树 0 命中；真因是它被抽成 `com.chengyinhub.business.util.OrderRefundCash`，master 调 `OrderRefundCash.remaining(order, payer, refundService)`，三条错误文案（"同单已退金额无法核对"/"同单有退款申请正在审核"/"已退的那张到底退了多少"）随类一起搬走 |

**方法学（补 §6 脚注）**：判「某段逻辑是否丢失」**不能只 grep 方法名**，本仓审计返修常把 helper 抽进
`business/util/*`。正确姿势是 grep 该段逻辑的**用户可见文案 / 异常 message**（这类字符串最不容易在重构中改名），
上面三条文案一命中 `OrderRefundCash.java` 就证明逻辑在、只是换了宿主。

⇒ §6A 表格至此**全部复判完毕，无一需要再落地**。这 6 个分支未删（本轮批复只覆盖 A 组四项的 9 个 ref）。

## 十三、`jr-x-kit-editor` 正式判搁置（2026-09-20）

用户批复「3」，取**可逆的那一支**：在台账里正式记为**搁置**，A 组盘点到此收口；不擅自补做玩家端三屏。

- 现状不变：创作端半条已移植好，检查点 **`port/jr-x-kit-editor` @ `a9559d2c7`**（本地 ref，未 push；
  隔离 worktree `/tmp/jrx-port` 会被系统清，但分支 ref 存在主仓 `.git` 里，丢不了）。
- 卡点原样保留：`sort` / `match` / `classify` 三段没有玩家端屏（`cy-playkit-{sort,match,classify}` 不存在），
  且 `tests/unit/playkit-view-contract.test.js:410` 的 `SCREEN_OF` 一对一断言会钉住这个缺口 ——
  合了就是「商家配得出、玩家看不见」。当时**故意留 3 条红测不压门禁**。
- 要复活只需一句「补三屏」，从该检查点起：先定三屏交互（排序 / 连线 / 分类各一屏），
  再补 `SCREEN_OF` 映射，最后解掉那 3 条红。这属新功能，需先出设计口径并按 UI 截图门禁过目。

至此 §6A 的 8 行全部有终判：2 条已落地（`jr-r16-pack` 经 #1123、`jr-x-kit-editor` 移植后搁置），
6 条复判为已被 master 吸收。
