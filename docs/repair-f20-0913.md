# F20 修复记录：漫游结算把「格式化后的公里」当米提交

日期：2026-09-13 · 范围：小程序漫游页结算距离链路（仅前端，不改后端）
来源：`第六轮_全产品查漏与官方活动实跑.md` · F20 · P2

## 问题

受控 GPS 实测累计 **44.528 米**，实跑结束屏显示 `0.04 公里`（正确），但服务端会话
`distance_m=0`。其他距离也按 100 米粒度失真。

## 根因

`chengyinhub-xcx/pages/roam/index.js` 的 `_arrive()`：

1. 先算展示值 `fin.distance = ((this._dist || 0) / 1000).toFixed(1)` → `44.528m` 得 `'0.0'`；
2. 再把这个**展示字符串** `parseFloat(fin.distance)` 传进 `_postRoamFinish(distanceKm)`；
3. `_postRoamFinish` 又按「公里」乘 1000 → `Math.round(0.0 * 1000) = 0`。

即：展示格式化与上报请求共用了同一个被截断的值，原始米数 `this._dist` 在中途丢失。

## 修复（最小改动）

契约改为：**上报入口只收原始米数，展示格式化另做**。

- `_postRoamFinish(distanceKm)` → `_postRoamFinish(distanceM)`；
  `_lastDistanceKm` → `_lastDistanceM`；请求体 `distanceM: String(Math.round(distanceM || 0))`。
- `_arrive()` 上报改传 `this._dist || 0`（原始米数），`fin.distance` 仍是公里展示串，互不影响。
- `retrySettle()` 重试沿用 `this._lastDistanceM`。

后端接口 `/api/roam/finish` 的 `distanceM` 字段语义未变（本就是米），只修正了前端喂值。

## 改动文件

| 文件 | 改动 |
|---|---|
| `chengyinhub-xcx/pages/roam/index.js` | 距离提交改原始米数；展示/请求分离；注释写明契约 |
| `chengyinhub-xcx/tests/unit/roam-finish-distance.test.js` | 新增负控 + 回归（短距/跨公里/多段累计/重试） |
| `chengyinhub-xcx/tests/unit/play-request-recovery.test.js` | 既有 `_postRoamFinish(1.2)` → `1200`（对齐米契约） |
| `chengyinhub-xcx/tests/unit/celebration-feedback-contract.test.js` | 同上 |

## 验证

### 负控（先 RED，复现旧码）

```
cd chengyinhub-xcx
node --test tests/unit/roam-finish-distance.test.js
```

旧码下 4 条全红，且失败值正是旧 bug 的指纹：

- `44.528m` → 实际提交 `'0'`（期望 `'45'`）
- `1550m` → 实际提交 `'1600'`（期望 `'1550'`，100 米粒度失真）
- 多段短程累计 `44.528m` → 实际 `'0'`
- 重试 → 实际 `'0'`

### 修复后（GREEN）

```
node --test tests/unit/roam-finish-distance.test.js
# tests 4 · pass 4 · fail 0
```

覆盖边界：

- 短距离：`44.528m` 提交 `45`，展示仍为 `0.0` 公里；
- 跨公里边界：`1550m` 提交 `1550`，展示 `1.6` 公里；
- 多段短程：`_realTick` 三段（20 + 24.528）累计后提交 `45`，不再归零；
- 结算重试：`retrySettle()` 仍提交原始米数，不回退到格式化值。

### 既有测试

```
node --test tests/unit/play-request-recovery.test.js \
          tests/unit/celebration-feedback-contract.test.js \
          tests/unit/roam-finish-distance.test.js
# tests 38 · pass 38 · fail 0
```

### 全量单测与门禁

```
node --test "tests/unit/**/*.test.js"          # tests 5405 · pass 5403 · fail 0 · skipped 2
node scripts/uiaudit/build-action-ledger.js --check   # ACTION-LEDGER PASS: 3963 controls
npm run lint:ui                                # UI-GATE-0:PASS
npm run lint:scope                             # JS-SCOPE PASS: 495 个生产 JS
```

本页改动**净行数为 0**（7 增 7 删，文件仍 4716 行），未引入任何行号漂移，因此
`action-ledger.json` 与 `tests/lint/ui-integrity-baseline.js` 均无需改动即通过；
按总控指示不纳入任何生成物改动。

## 未验证项

- 未连生产、未真机实走：结论来自模拟器受控输入 + 单测，不代表真机 GPS 表现。
- 未验证后端 `/api/roam/finish` 对 `distanceM` 的落库（任务限定不改后端、不连生产）；
  端到端 `distance_m` 与前端提交值的一致性需在联调环境回读。
- 未改动 GPS 精度阈值、跳点/漂移守卫、路线业务规则、UI 皮肤与生产配置。
- 未 push / 未开 PR / 未合并；未改动 `action-ledger.json` / `ui-integrity-baseline.js` 等生成物。
