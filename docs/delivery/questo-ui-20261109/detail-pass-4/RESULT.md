# detail-pass-4 · 三期复核修正（2026-11-12）

- 分支 `claude/detail-pass-20261112`（草稿 PR，未合并、未标记 ready）。没碰 `master`、`launch/questo-20261110`、`ci/architecture/*`、`.github/workflows/ci.yml`；没有生产、密钥；没跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`。版式、文案、配色方案不变。

## (1) 主包体积 —— 达标

`scripts/mainpkg-size-report.js`（压缩前源码字节）：

| | 字节 | 相对 master |
|---|---|---|
| master 基线 | 3,311,747 | 0 |
| 三期收尾（修正前） | 3,320,626 | +8,879 |
| **本期** | **3,313,743** | **+1,996**（≤ +2,000） |

按负责人的决定：
- `life.js`（活的细节）整个删除；`cy-empty` / `cy-error` 只保留入场上浮，不再有呼吸。
- 入场节奏 `utils/entry-rhythm.js` 重写到最小（一个常量帧组 + `play`），不带说明注释；两个组件的 `ready` 用前缀拼选择器。
- `components/cy/*` 的质感与按压：全部改用已有 token 表达，不再每个组件各写一份数值——
  - 去掉新增的 `--cy-motion-press-in/out`、`--cy-ease-release`、`--cy-ease-rise` 与四个 `--cy-shadow-hi/float-*`；按下沿用已有 `--cy-motion-press`（80ms），松手弹性只剩一个 `--cy-tr-press`（= `--cy-motion-standard` + 已有 `--cy-ease-spring`），景深收成 `--cy-depth-rest` / `--cy-depth-float` 两个，亮色在主题块里覆盖。
  - 主包内各组件新增的「松手弹性」过渡规则撤掉（按下的 scale .97 / opacity .92、景深阴影保留）。这与 `btn` 样式里早已写明的取舍一致（「全局 .cy-pressed 拿不到基态过渡，逐个给宿主加不值」）；弹性松手现在在 `btn` 与分包页面上保留。
  - 主包内新增的 `tabular-nums` 规则撤掉（分包内的保留）。`count.wxs` 的 `stat` 挪到 `pages/topic/utils/stat.wxs`（只有主题页用）。
  - 商家亮色弹层的抬升与亮色景深写进 `.theme-light/.theme-merchant` 块；`.theme-topic-editor` 保持原来的「白底细线、无投影、弹层不抬升」。
  - 为压进预算，主包内去掉：首页卡片（`.v2-fcard` 照片卡、`.v3-locprompt`、`.v3-reco-card`）与 `stat-card` 的景深阴影、`.v2-fcard::before` 顶边高光；`toast` 的悬浮景深与 `card`/`modal`/`empty` 的按压保留。亮色 / 暗色主题块照既有门禁各自声明景深 token。
- 没动 `club-direct-ledger` 与其他既有内容。

## (2) 入场上浮单位

`entry-rhythm` 把 24 直接传给 `this.animate` 的 `translateY`，而微信该接口的位移单位是 px，所以实际上浮 24px、不是设计的 12px。现在直接用 12（px），单测改成无注释断言：用 `play` 捕获 `animate` 实参，断言 `translateY: 12`、缓动、420ms、错开 40ms、减少动态效果不播。

## (3) 六帧条

`strips/{press,entry,count,sheen,halo,sheet,menu}.png`，脚本 `rig/strips.js`（0/80/160/240/360/480 ms，上排 before = master，下排 after = 本分支）。

- **press**：0 = 静止，80 / 160 = 按住（scale .97、opacity .92），240 / 360 / 480 = 松手弹性回位。曲线按产品代码里的值：按下 80ms，松手 `--cy-tr-press`。条带只画产品代码真实做的 scale + opacity，不另画「景深变化」——按压态本身并不改阴影，画出来会是假的。
- **halo**：章节门光晕改成明显的呼吸，opacity .4→1、scale 1→1.12（原 .5→.8、1→1.04），仍是 3 秒一来回；`.cf__halo` 的静态 opacity 随之 .5→.4。halo 一条时间轴是半个周期 0/240/480/720/960/1200 ms（0–480ms 内慢呼吸看不出来）。
- **entry**：用 `play` 捕获的真实帧重画，位移 12px。
- **局限**：同上期——云端容器无 Docker 后端（且规定不得本地起），走查 rig 起不来，条带是 Chromium Web Animations 渲染产品代码给出的关键帧计划，不是真机 `animate`；sheet 一条里 before / after 弹层本体相同。主包体积以源码字节量，真正 2048 KiB 判据请在开发者工具里看打包体积。

## 门禁

| 门禁 | 结果 |
|---|---|
| 全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | 17409 条 / 17409 过 / 0 红 |
| ui-integrity | UI-GATE-0:PASS，无新增 baseline 条目 |
| 动作台账 / 路由矩阵 | `--write` 重建后 `--check` PASS（6025 控件；171 routes / 27 scenes） |
| WXS / WXSS | WXS PASS（15 + 4 内联）；值内注释 / 选择器 / 组件选择器 PASS |
| keyframes 棘轮 | 无新增 `@keyframes`（在全量单测内） |
| danger-confirm | PASS（53 调用点，38 不可逆有确认） |

另：undefined-token 门禁 PASS（481 份 wxss）；`wxs-syntax-gate` 需 `node --expose-internals` 运行。

STATUS: DONE（主包 +1,996 B ≤ +2,000 B；全量 0 红）
