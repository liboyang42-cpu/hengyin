# play-ui-1113c 验收走查缺陷修复

分支 `fix/play-ui-1113c`(基于 origin/master)。每项都在原组件/页面上最小改动,用现有 token。

| # | 问题 | 改动 | 文件 |
|---|------|------|------|
| 1 | 浅纸本主题结局页「继续」「关闭」对比度不足 | 结局页底恒为黑,浅纸本(黄/白)下按钮取了深色墨,与黑底糊在一起;仅在浅纸本下改为白底按钮、次按钮文字提亮 | `chengyinhub-xcx/pages/play/play-stage.wxss` |
| 2 | 「本局档案」站名挤成一行 | 站名改为逐站一行,沿用 `.life-arc__row` | `pages/play/index.wxml` |
| 3 | 开始弹窗露出制作用词 | 去掉「模板 · 问答」「地点 ·」标签行与「绑定地点」字样;标题去掉「第04关」「问答：」前缀,只留站名与任务(编辑器不动) | `pages/play/play-stage.wxml`、`utils/play-stage-pops.js` |
| 4 | 章节开始屏圆形章压住「距离」 | 地点卡数字行右侧留出圆章宽度 | `pages/play/play-stage.wxss` |
| 5 | 「13333 分钟步行」 | 步行超过 60 分钟不显示分钟,「距离」格沿用既有 `_distText`(km 一位小数) | `utils/play-stage-pops.js` |
| 6 | 「二〇〇」折行 | 新增 `keepNumerals`,汉字数字连续串之间放不可见的禁断符,用于故事正文 | `pages/play/utils/keep-numerals.js`、`pages/play/index.js` |
| 7 | 确认订单勾选框被底栏压住 | 底部占位改为 240rpx + 安全区 | `pages/topic/components/cy/self-play-checkout/index.wxss` |
| 8 | 主题详情真实地图取景太松 | `FIT_PADDING` 由 [60,40,60,40] 改为 [36,24,44,24](上留图钉、下让开来源说明行) | `utils/topic-route-preview.js` |

测试:新增 `tests/unit/play-ui-1113c.test.js`(第 2、3、5、6 项),`topic-detail-walk2.test.js` 增加边距上限断言(第 8 项)。

未改 ci/architecture、工作流、ui-integrity-baseline;未跑 ci/xcx-check.sh 与会写夹具的自检。

## 检查结果

- PR #824 最新提交 `c136f071`:GitHub CI 27 项通过、6 项按条件跳过、0 失败(含 20 个小程序单测分片、静态门禁、gitleaks)。
- 本地 `ui-integrity` 通过(UI-GATE-0:PASS),未新增基线条目、未改 keyDigest;`action-ledger` 48 项单测通过。
- 过程中修了两处自己引入的问题:`pages/play/index.js` 多一行 require 使基线行号失配(改为并入原有一行);`index.wxml` 增行使动作台账漂移(按提示重建 `scripts/uiaudit/action-ledger.json`,仅行号位移,并同步 `action-ledger.test.js` 中章节选择控件行号 540 → 543)。

## 未能目视确认
- 第 1 项:找到的是「继续」钮在浅纸本下深墨叠黑底;结局页未找到单独的「关闭」钮,未做改动。
- 第 4 项:按地点卡完成章圆章压「距离」处理,未找到点状圆。
- 第 6 项:未找到「二〇〇」的具体来源,只处理了故事正文;其他位置的汉字数字未覆盖。
- 全部 8 项均未在微信开发者工具中截图验证,需要人工走一遍。

STATUS: PARTIAL - CI 全绿,但第 1、4、6 项的定位基于代码推断、8 项均未截图目视验证
