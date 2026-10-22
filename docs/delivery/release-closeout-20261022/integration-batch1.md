# 发布收尾 batch1 集成候选记录（2026-10-22）

- 集成分支：`integrate/closeout-batch1-20261022` → `master`（draft PR #475，仅供人工审阅后合并）
- 基线：master `063a6c3cd7c1f131618148c385174037f1d2786e`
- 范围：不部署、不连生产、不执行任何 DB 变更；未合并 / 关闭任何 PR，未推 master，未删分支。
- 未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（会破坏工作区）；只单跑子门禁与 Node 单测。

## 1 合入顺序

全部 `git merge --no-ff <PR head>`，按下表顺序，**0 文本冲突**。

| 顺序 | PR | head | 说明 |
|---|---|---|---|
| 1 | #434 | `65cc424d` | 启动预检与重启任务验收 |
| 2 | #454 | `6b4ec349` | Lane A 会费开关 N-01/N-01b |
| 3 | #455 | `c180d5b0` | Lane B 社区帖图 OSS key 送检（接续 #377） |
| 4 | #460 | `85a9aeb3` | Lane B2，叠在 #455 上 |
| 5 | #456 | `4e39708f` | Lane E 同意台账去重 + 资质图删除恢复（重放 #418 / 承接 #412） |
| 6 | #458 | `6bffd1b9` | Lane F 排行榜 D-13 + IM 屏蔽未读 |
| 7 | #462 | `34b1f4eb` | Lane H1 |
| 8 | #464 | `68d6c8b9` | Lane H2 |
| 9 | #466 | `4a95444c` | Lane H5 |
| 10 | #468 | `eac17ae5` | Lane H3 |
| 11 | #461 | `52aa9ab7` | Lane H4（docs） |
| 12 | #457 | `f07f7d9b` | Lane C2 CloudTen/CI 夹具 |
| 13 | #467 | `ece01d38` | Lane C：#449 + 有限 API 误判修复 |
| 14 | #469 | `c1aaf814` | Lane C 收口记录（docs） |
| 15 | #459 | `21be708e` | Lane G 处置清单（docs） |
| 16 | #465 | `4e6bfe64` | Lane G2 私有语义组（docs） |
| 17 | #463 | `300f3ce2` | Lane D 自动云草稿（条件合入，见下） |

### #463 条件核对

- `docs/delivery/release-closeout-20261022/lane-d.md`（其分支）：F-2 标为 **FIXED（`bc42f3b1`，复验 PASS，见 7.6）**。
- 其 CI（run `39767030388`，head `7179d151`）只有 3 项失败：unit shard 8 的 `action-ledger.test.js:1672`（台账 byte-exact）、static gates 的 `ACTION-LEDGER FAIL` 与 `UI-GATE-0 FAIL`（12 条 U4-DYNAMIC 行号漂移），以及汇总 job。都属于 action-ledger / ui-integrity-baseline 项，满足条件，合入。最新 head `300f3ce2` 只改了 lane-d.md。

### 排除

无。W1–W11 新开的 draft PR 属 batch 2，未纳入。

## 2 冲突处理

没有文本冲突。语义上唯一的交汇点是 `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`：#467 收紧 U1 后留下 3 条过期登记，#463 改 `fabu/index.js` 后有 12 条 U4 登记行号下移。两处都在第 3 节的再生成步骤里一次处理，没有丢弃任何 lane 的修复或测试。

## 3 再生成（在合并树上各做一次）

| 产物 | 生成器 | 旧 → 新 | 说明 |
|---|---|---|---|
| `scripts/uiaudit/action-ledger.json` | `build-action-ledger.js --write` | 控件 4947 → 4947 | 只有 #463 带来的 fabu 行号漂移（index / runtime-editor / step3 / topic-detail-sheet 四个 wxml 入口）；把数字去掉后控件集合逐项相同 |
| `scripts/uiaudit/route-matrix.json` | `build-route-matrix.js --json` | 145 路由 / 27 场景，无差异 | — |
| `tests/lint/ui-integrity-baseline.js` u1Variable | 手工按 #467 CY-HANDOFF | 11 → 8 | 删除 profile:1095、official-inbox:109、myproject:571；`ratchet.u1Variable` 11→8；`keyDigest.u1Variable` `7b3271d1…bb4` → `52fbcba7…bfb`（与 CY-HANDOFF 一致） |
| 同上 u4Dynamic | 纯行号重锚 | 62 → 62 | 见下表；`keyDigest.u4Dynamic` `69fcc9f3…108` → `9dfb7bb2…54c` |

u4Dynamic 重锚（`chengyinhub-xcx/pages/publish/fabu/index.js`）。每条都核对过：锚点行 ±3 行的文本在合入 #463 前后逐字相同，理由一字未改。

| 旧行 | 新行 | 位移 |
|---|---|---|
| 758 | 849 | +91 |
| 1010 | 1105 | +95 |
| 2698 | 2806 | +108 |
| 3758 | 3872 | +114 |
| 4139 | 4253 | +114 |
| 5202 | 5316 | +114 |
| 5461 | 5575 | +114 |
| 6117 | 6231 | +114 |
| 6127 | 6241 | +114 |
| 6180 | 6294 | +114 |
| 6521 | 6635 | +114 |
| 8419 | 8601 | +182 |

没有新增登记，冻结上限也没有上调。摘要算法沿用门禁的 `sha256(sorted keys joined by "\n")`。

### 对照计数（`node tests/lint/ui-integrity.js --warn-as-error`）

| 项 | 再生成前 | 再生成后 |
|---|---|---|
| U1 | PASS：静态 899 / 动态 60 / 变量 8 | 同左 |
| U2 | PASS：绑定 5120 | 同左 |
| U3 | PASS：62/62 | 同左 |
| U4 | FAIL：动态 setData 63/51 已登记 | PASS：63/63，零消费 126/126 |
| U5 | PASS：37 | 同左 |
| UI-GATE-0 | FAIL（27 条：12 U4 未登记 + 3 u1 过期 + 12 u4 过期） | **PASS** |

另外：`ui-integrity.js --selftest --warn-as-error` PASS；`build-action-ledger.js --check` / `--selftest` PASS；`build-route-matrix.js --check` PASS；`action-ledger.test.js` + `route-matrix-freshness.test.js` 50/50 通过。

## 4 合并后人工可关闭（已被取代，依据 disposition.md）

- #412、#418（#456 已重放 / 承接）、#377（#455 已接续）、#449（#467 已包含并修复）、#314
- disposition.md 列出的 26 个 CLOSE_SUPERSEDED：#7 #8 #9 #12 #14 #15 #17 #28 #29 #30 #31 #32 #33 #34 #36 #44 #72 #294 #375 #376 #379 #380 #381 #388 #390 #394
- 本批 17 个成员 PR（本集成合入后，它们的提交已进入 master）

## 5 CI

见 PR #475 的检查结果与描述更新。
