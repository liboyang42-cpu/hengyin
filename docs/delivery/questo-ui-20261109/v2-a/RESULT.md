# v2-a 动效 v2:章节开始(时刻 1)+ 通关(时刻 3)

规格:`docs/delivery/questo-ui-20261109/proto/motion-v2/index.html`(origin/claude/questo-ui-brief-20261109)。

## 改了什么

- 新增组件 `pages/play/components/chapter-moment`(WXML + 组件 `animate()` + WXSS transition,**没有新增 `@keyframes`**)
  - 时刻 1:暗色氛围 → 一颗光点长大 → 28 点排成章节印环 → 点阵章节号逐点亮起 → 光晕 + 慢光线 → 章名逐词(模糊→清晰)→ 读数滚到位 → 主按钮最后升起;点任意处跳终态,终态后再点才进正文
  - 时刻 3:粒子汇聚 + 核心旋转(第一幕)→ 白光 + 光环外扩(第二幕)→ 勋章升起、光线在后(第三幕)→「已通关 · 日期」+ 主题名 → 三格读数卡(外观色)→ 一行「保存图片 · 分享」+ 主按钮「查看结算」最后
  - 减少动态(应用内开关)直接终态;系统 `prefers-reduced-motion` 由 WXSS `@media` 去掉过渡
  - 外观色全部读现有 `--lk-*`(`--lk-a / --lk-b / --lk-ink / --lk-bg` 及 `-rgb`);原型里的 `color-mix` 改成 rgba 叠层,不依赖基础库
- 纯函数 `pages/play/utils/chapter-moment.js`:缓动取样(wx animate 只支持 5 种缓动,原型的 cubic-bezier 取样成带 offset 的多帧)、点阵数字、章名切词、读数滚动、时间轴常量、确定性粒子
- `chapter-flip` 外壳:章节开始/通关改为挂 `chapter-moment`;**移除**三棱锥、银色城瘾标志、碎片、方块波、彩纸、解码文案区和标题残影(只留一个主角)。章节完成整屏(「第 N 章完成」画布 + 粉尘)保持不变
- 宿主 `pages/play/index.js`:自己进正文的兜底计时 4200→6600、10800→13400(动画 2.8s 播完后再停 2.8s)
- `chapter-flip-data.buildFinale` 眉标改为「已通关 · M月D日」,其余数据不变
- 删除随旧呈现一起退场的测试(x-design1-d2-finale、x-fix7-finale-ghost-glyph),其余受影响的旧断言改写为新合同

## 与原型的有意差异

1. 原型第一幕左上角的小分享图标没做(开场屏没有对应动作,不加死控件)
2. 原型自带的四种外观色是示意色,和 `look-tokens.wxss` 真实值不同(例如纸本的 `--lk-b` 是灰,勋章偏银)。对比图因此给三列:原型(示意色)/ 原型(真实色值)/ 真实 play 页,版面、尺寸、时序以第二、三列逐帧核对
3. 按钮文案沿用现有数据(「读下去」),不是原型里的「下一站 · 星图时间轴」
4. 按钮、统计的时间点按原型代码实际值(统计 2270ms、按钮 2770ms;通关按钮 2550ms),而非右侧文字里的取整值
5. 矮屏(窗口高 < 844)整体上提最多 96px,避免读数压到按钮

## 对比图

`docs/delivery/questo-ui-20261109/v2-a/compare/`
- `m1-{stardust,paper,survival,cyber}.jpg`:t = 0.3 / 1.1 / 2.0 / 2.8 s
- `m3-{stardust,paper,survival,cyber}.jpg`:t = 0.5 / 1.0 / 1.6 / 2.6 s

每个时刻三列并排。抓图脚本在 `v2-a/tools/`(`capture.cjs` / `sheet.cjs`),真实页来自 cloud-walk rig 的 mpweb 运行时(打包真实 `pages/play/index` 与组件;rig 的 `animate` 按微信语义补了关键帧转换)。**后端不在本地起**(按仓库规则),所以 play 页数据由抓图脚本用 `setData` 注入章节门 / 通关数据,组件、WXML、WXSS 都是真实代码。

已知残余差异:抓图有 ±50~100ms 的截图延迟抖动,读数滚动与结尾按钮淡入在个别帧上比原型晚几十毫秒;勋章亮度呼吸用白色叠层近似(微信 animate 不支持 filter)。

## 门禁(本分支最后一次运行)

- 全量 mini 单测 `node --test tests/unit/**/*.test.js`:17411 条,首轮 5 条失败(动作台账分母 ×2、按压反馈棘轮 ×3),均已修;修后台账测试 48/48、按压反馈 9/9、本次新增 14/14、受影响旧测试 249/249 全绿
- `ui-integrity` **UI-GATE-0:PASS**(无新增基线条目;首轮 2 条动态 setData 已改为静态字段)
- 动作台账重建:`ACTION-LEDGER PASS: 6030 controls`(tap 3029→3030、事件合同 2996→3000、本地 tap 2650→2651、控件 6025→6030);路由矩阵 `route-matrix PASS: 171 routes / 27 scenes`
- WXS / WXSS 类门禁:undefined-token、wxss-selector、component-wxss-selector(修了标签选择器)、button-reset、wxml-conditional、ui-theme-unit、cy-sheet-attr、component-attr、component-path-resolve、wxml-handler、js-orphan-handler、setdata-dead-field、a11y 两项、pulldown、sticky-bottom-bar、ds-hardcode(非自证)全部通过;WXSS 数值里无注释
- `js-scope-lint`:PASS(957 个 JS)
- 关键帧棘轮 `motion-property-ratchet` 9/9(上限 146,未新增 `@keyframes`)
- `danger-confirm-gate`:DANGER-GATE PASS
- 主包大小:新增仅 2 个小组件文件与一个纯函数模块,无新资产
- 未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`;未改 `ci/architecture/*`、`.github/workflows/ci.yml`

## 遗留

- `chapter-flip/engine.js` 里三棱锥几何已无调用者,留待统一清理
- 抓图用 `setData` 注入章节门 / 通关数据(本地不起后端);真机走查仍需在微信开发者工具里看一遍

STATUS: DONE
