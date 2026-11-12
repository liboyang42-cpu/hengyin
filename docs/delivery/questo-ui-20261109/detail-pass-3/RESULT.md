# detail-pass-3 · 一期复核修正（2026-11-12）

- 分支 `claude/detail-pass-20261112`（草稿 PR，未合并、未标记 ready）。没碰 `master`、`launch/questo-20261110`、`ci/architecture/*`、`.github/workflows/ci.yml`；没有生产、密钥；没跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`。版式、文案、配色方案不变。

## (1) 主包体积 —— **未达标，差 8,879 B**

`mainpkg-size-report.js`（压缩前源码字节）：

| | 字节 |
|---|---|
| master 基线（920141d5） | 3,311,747 |
| 修正前（二期收尾） | 3,343,172（+31,425） |
| **修正后** | **3,320,626（+8,879）** |

已做（共回收约 22.5 KB）：
- `parallax.js` → `pages/topic/utils/`；`menu-open.js` → `pages/play/utils/`；读数滚动的 `init/roll/clear` 拆到 `pages/play/utils/count-roll.js`（主包 `count-up.js` 还原为纯函数）。
- 897 处重复的按压过渡长串收成一个 token `--cy-tr-press`，同文件相邻规则合并成逗号选择器。
- 新增的说明性注释压短；`entry-rhythm.js`、`life.js` 重写精简。
- 零引用组件（`club-topic-*` 等，不会被打进包）的二期样式改动整体还原。

没做到的原因：剩余 +8.9 KB 是主包里共享组件（`components/cy/*` 的卡面层次/按压样式）、`tokens.wxss` 新 token、`entry-rhythm.js`/`life.js`（`cy-empty`、`cy-error` 在主包使用）。要再降只能（a）砍掉这些组件的质感改动，或（b）把 `club-direct-ledger`（9.8 KB，被 `subpackageA` 跨包引用）等既有内容下沉——都动到范围外的东西，留给负责人定夺。另：本工具量的是源码字节，真正的 2048 KiB 判据要在开发者工具里看打包体积。

## (2) D3 主题详情头图

`pages/topic/index` 两处无封面兜底（首屏 `slide1`、详情头图 `det1` 的 swiper）原先铺满 `icon_line.jpg` 黑白线稿；改为与主题卡一致的 `cy-cover-fb`（look 渐变）+ 120rpx 小路线标（`/images/cover-route.svg`），不改布局。契约断言 `uiux-player-state-adoption-contract` 对应更新。

## (3) 可见度

- 暗色卡面顶边高光 `--cy-shadow-hi-dark` .06 → **.10**；S1 地点卡外观强调色光芒 18% → **24%**。
- 亮色同步：悬浮影 .04/.08 → .06/.12，静止卡面影 .04 → .06，商家白弹层 `--cy-comp-sheet-role-lift` 同值（`r2-visual-tokens` 断言同步）。亮色顶边高光本来是 .9，已是上限。
- 六帧条（0/80/160/240/360/480 ms，上排 before = master 数值，下排 after = 本分支）：`strips/{press,entry,count,sheen,halo,sheet,menu}.png`，脚本 `rig/strips.js`。
- **局限**：云端容器没有 Docker 后端（且仓库规定不得本地起 Docker），走查 rig 起不来。所以条带是用 Chromium Web Animations 渲染**产品代码给出的关键帧计划**（`entry-rhythm.plan`、`count-up.frame`、`sheenFrames`、`haloFrames`、`menu-open.frames`、按压 token 值），不是真机 `animate`，也不是整页截图；sheet 一条里 before/after 的弹层本体相同，差别只在卡面顶边高光。
- **发现**：条带里入场上浮的位移是 24（`RISE_RPX`），微信 `animate` 的 translate 单位是 px，实际可能是 24px 而非设计的 12px。未改（单测钉着 24），请真机确认。

## 门禁

| 门禁 | 结果 |
|---|---|
| 全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | 17409 条 / 17409 过 / 0 红 |
| ui-integrity | UI-GATE-0:PASS，无新增 baseline 条目 |
| 动作台账 / 路由矩阵 | `--write` 重建后 `--check` PASS（6025 控件）；路由矩阵 PASS（171 routes / 27 scenes） |
| WXS / WXSS | WXS PASS（14 + 4 内联）；值内注释 / 选择器 / 组件选择器 PASS |
| keyframes 棘轮 | 无新增 `@keyframes` |
| danger-confirm | PASS（53 调用点，38 不可逆有确认） |

STATUS: PARTIAL（主包体积仍超基线 8,879 B）
