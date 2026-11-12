# #806 / #805 合入 master 结果

目标：把 master（含 #802 `2f465e45`）合入两个草稿 PR 分支，用合并提交，不 force-push。

## #806（claude/dec-ui-1-20261112）

- 合并提交 `fe2653ec`。唯一冲突是 `scripts/uiaudit/action-ledger.json`（生成文件）。
- 处理：取分支侧后用 `build-action-ledger.js --write` 重新生成（6024 条），再 `--check` 通过。#802 对 play / index / roam / roam-track-filter 的改动自动合并，无手工冲突。
- 门禁：`node --test chengyinhub-xcx/tests/unit/*.test.js` 17509/17509 通过；`ui-integrity.js` 为 UI-GATE-0:PASS。
- CI：`fe2653ec` 上 Mini followup、Funds MySQL IT、Redis recovery 均成功，`GitHub CI` 的 Backend tests 失败。该失败来自分支自身的 D-78 Java 控制器测试，与合并无关；他人随后在分支上推了 `67b49847`（补齐两个控制器测试的已存主题封面）。截至写本文档时，`67b49847` 上没有新的 CI 运行，所以修复是否转绿**未验证**。

## #805（claude/dec-ui-2-20261112）

- 合并提交 `f54726a2`。唯一冲突是 `tests/lint/ui-integrity-baseline.js`。
- 处理：两侧头部注释都保留（dec-ui-2 与 dec-b）；`u1Variable` / `u4Dynamic` 两个 keyDigest 按门禁同一算法（排序后的基线 key 以换行连接取 sha256）对合并后的 key 集合重算。没有新增基线条目，key 数量不变；锚点行号未需再动。`action-ledger.json` 自动合并，用脚本 `--write` 重新生成（6027 条）后 `--check` 通过。
- 门禁：单测 17516/17516 通过；`ui-integrity.js` 为 UI-GATE-0:PASS。
- CI：`f54726a2` 推送后没有任何 pull_request 工作流运行出现（check runs 为 0），CI 结果**未取得**。

## 其他

- 查看过程中 master 又前进了（#807 `a9c2df3a` 已合并），两个分支可能需要再合一次。
- 本环境没有 `gh`，CI 状态通过 GitHub MCP 查询。

STATUS: PARTIAL - 两个分支均已合入并通过本地门禁，但 #806 修复提交与 #805 的 CI 尚无结果，且 master 已再次前进
