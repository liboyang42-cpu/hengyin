# 走查 1113 结果

分支 `fix/walkthrough-1113`，领队提交 63c0c892 的两项业主决定未改产品行为，只把测试对齐。

## 业主决定
1. D-46「点了才能进」（2026-11-11）：章节开场门与章节完成屏只在玩家点按时前进。`pages/play/index.js` 里的页面兜底计时（4.2s/10.8s）已移除；队长放行后翻转门出现并等待点按。
2. 2026-11-13：隐私弹窗只有一颗白色「同意并继续」，同时同意两份文件并授予隐私授权，无勾选框；「暂不同意」保留为文字链。`pages/privacy` 页不变。

## 测试改动（均带 D-46 / 2026-11-13 注释，未删除覆盖）
- `usage-consent-app.test.js`：拆成 pages/privacy 不变断言 + 弹窗单按钮断言（无 checkbox、同意无需勾选、两份文件链接仍在、暂不同意文字链）。
- `play-lead-chapter-hold.test.js`、`play-chapter-hold-map-p08-r5d.test.js`、`play-chapter-hold-screen-p08-r4.test.js`：放行后显示翻转门并等点按；仍被挡则保持被挡；无计时自动前进。
- `action-ledger.test.js`：台账重建后计数 controlCount 6035→6034、event-contract 3000→2999、external tap 381→380、本地 tap 2654→2655。
- `tests/lint/ui-integrity-baseline.js`：U5 登记点行号 8923→8915（仅位移），keyDigest 重算；未新增基线条目。

## 验证
- 全量小程序单测：见 PR；UI-GATE-0: PASS；route-matrix PASS（172 路由 / 27 场景）。
- 未改 ci/architecture/*、.github/workflows/*；未运行 ci/xcx-check.sh 与 ds-hardcode-gate-selftest.sh。
