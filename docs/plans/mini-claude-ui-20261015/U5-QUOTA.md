# ui-integrity U5 名额核对（BL-170）

> 只写文档，不改协调者文件。快照 2026-10-16 03:25Z，基线 `claude/mini-ui-features-20261015`。
> 门禁：`tests/lint/ui-integrity.js` 的 U5 要求每个「fail 回调里没有可见反馈」的 `silentError: true` 调用点在 `tests/lint/ui-integrity-baseline.js` 的 `u5Silent` 逐项登记理由，且 `ratchet.u5Silent` 必须等于登记条数、不得超过 `ui-integrity.js` 里的冻结上限 `NON_INCREASING_CEILING.u5Silent = 29`。
> 本门禁与 `scripts/ui-validity-gates.js` 的 U5（`scripts/ui-silent-error-allowlist.js` 按文件计数，#207/#216 处理的那份）是两套，互不替代。

## 1. 现状

| 项 | 值 |
|---|---|
| 基线 `ratchet.u5Silent` | 28 |
| 基线 `u5Silent` 登记条数 | 28（全仓扫描实测需逐项理由 28，未登记 0） |
| 冻结上限 | 29 |
| 剩余名额 | **1** |

## 2. 方法

对每个 open `claude/bl-*` / `claude/cl*` 分支：取它相对基线改过的小程序 js（不含 `tests/`、`scripts/`），只在 diff 里出现 `silentError` 增删时，分别在基线树和分支树上对这些文件跑 `scanU5`（门禁自身导出的函数），差值即该分支新增的「需逐项理由」调用点数，同时记下未登记的 key。全仓 `scanU5` 一次约 97 秒，只扫改动文件得到的是同一结果的增量。

另用 `git diff base...branch -- tests/lint/ui-integrity-baseline.js | grep u5Silent` 找改了 `u5Silent`/`ratchet.u5Silent` 的分支：**只有 #120（BL-77）**，28→29。#218（BL-166）以补丁形式提出 28→29，未提交该文件。#37（BL-01）只等量换 key、条数不变。

## 3. 各 PR 新增需求

| PR | 卡 | 新增调用点 | 已在 baseline 登记？ | 改 `ratchet.u5Silent` | 备注 |
|---|---|---|---|---|---|
| #120 | BL-77 券统计 | +1 | 是（分支内） | 28→29 | 用掉最后一个名额 |
| #218 → #132 | BL-89 安全感受半屏 | +1 `pages/play/merchant/components/safety-rating-sheet/index.js:115` | 否，#218 给补丁 | 补丁 28→29 | 与 #120 抢同一名额 |
| #73 | BL-32 共用举报半屏 | +2 `components/cy/report-sheet/index.js:46`、`:89` | 否 | 否 | #74/#75/#77/#95/#118 都带着这两处（继承自 #73，不重复计）；#95 合入后行号移到 `:47/:90` 或 `:48/:93`（两条 BL-51 分支不同） |
| #110 | BL-67 商家纠错 | +2 `components/cy/merchant-correction-sheet/index.js:44`、`:84` | 否 | 否 | #189（BL-145 2/2）继承，不重复计 |
| #123 | BL-80 官方通知复盘 | +1 `pages/activity/official-mine/index.js:146` | 否 | 否 | |
| #83 | BL-41 漫游官方活动 | +1（`pages/roam/index.js`） | 否 | 否 | 另有 11 条既有 roam 登记行号漂移（见 §4） |
| #113 | BL-70 漫游访客头像堆 | +1（`pages/roam/index.js`） | 否 | 否 | #196（BL-152）继承；同样带 11 条漂移 |
| 其余 xcx 代码分支（#38 除外，含 #23/#25/#27/#84/#109/#112/#114/#119/#122/#128/#130/#141/#144/#147/#152/#164/#183/#184 及其 follow-up） | — | 0 | — | — | 新增的 silentError 都被门禁自动认定有可见反馈 |

**合计新增 9 处**（#120 1 + #132 1 + #73 2 + #110 2 + #123 1 + #83 1 + #113 1）。

## 4. 只是行号漂移、不占名额

| PR | 情况 |
|---|---|
| #38 BL-03 | `pages/roam/index.js` 插入代码后 11 条既有 u5Silent key 行号位移（749、1805…5438），条数不变，需按 #37 的做法等量换 key 并重算 digest |
| #83 / #113（及 #196） | 同上 11 条漂移，外加各自 +1 |
| #37 BL-01 | 已在分支内等量换 key（u4Dynamic 4 / u5Silent 11），条数不变 |

多个 roam PR 合入后行号会再叠加移动，换 key 要按最终合并树做一次，不能把各分支的 key 直接拼起来。

## 5. 合并后累计

| 合入范围 | 需逐项理由 | 与上限 29 差 |
|---|---|---|
| 基线 | 28 | -1 |
| + #120 | 29 | 0 |
| + #132（#218 补丁） | 30 | **+1** |
| + #73 | 32 | +3 |
| + #110 | 34 | +5 |
| + #123 | 35 | +6 |
| + #83 | 36 | +7 |
| + #113 | 37 | **+8** |

## 6. 给 C00 的选项（只列数据，不做裁定）

1. **抬上限到 37**：在 `ui-integrity.js` 冻结上限注释里逐条写放宽理由（照 2026-09-17 / 09-21 的写法），协调者按最终树一次性登记 9 条理由、换 roam key、重算 digest。
2. **让 PR 自己消化**：#73、#110、#123、#83、#113 把 fail 回调改成有可见反馈（门禁会自动认定，不占名额），只保留真正需要静默的点；需要各 PR 改代码，且不能动现有 UI。
3. **只给最先合的一个**：#120 与 #132 二选一占用最后名额，其余 PR 在上限抬高前不能合。

无论选哪种，`ratchet.u5Silent` 与 `keyDigest.u5Silent` 都必须按最终合并树的 key 集合计算一次。

## 7. 未验证

- 只扫了改动文件的增量，没有在叠加合并树上跑全量 `node tests/lint/ui-integrity.js`；合并顺序不同时行号会变，但条数不变。
- 没有逐条判断 9 处静默是否合理（理由由各 PR 或协调者写）。
- 没跑 CI。
