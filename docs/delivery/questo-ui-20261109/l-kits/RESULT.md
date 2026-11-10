# l-kits · 玩法组件照原型收口（2026-11-10 上线波次）

分支 `claude/l-kits-20261110`，从 `origin/launch/questo-20261110`（qx-base 写完 `STATUS` 之后的 `ca1f85b6`）切出，草稿 PR 进 `launch/questo-20261110`。范围 = LAUNCH.md 的 l-kits 段：嵌入图片题（题 + 四张大图在故事背景上、无框）、逐字猜词改系统键盘、判案（线索可点开、嫌疑人翻页卡、结果插图 成功 / 失败 / 调查中）、掷骰子摇一摇（业主第二轮批复）。规格 = `proto/game/index.html` 的 `gp-kit-embed` / `gp-kit-full` / `gp-kit-accuse` 三屏（`screens-play.js` + `page.css`），插图 = 底座 `cy-illus`，外观 = 底座 `--lk-*`。

没有推 `master` / `integrate/*` / `staging/*` / `launch/*` / 其他 lane 分支；没有合并、没有标记 ready；没有碰生产；没有新增或修改 migration；没有运行 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`；没有密钥。

## 一、逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| LK-01 嵌入图片题 · 题 + 四张大图无框（原型 `gp-kit-embed`） | 已改 | 配了图的选项题从「两列图卡（灰底、字在卡下）」改成原型的无框大图：两列、没有底没有卡，图就是选项；字母圆标 + 文案压在图下沿的渐变上（图上的字永远白）；描边是一层 `::after`，只切 opacity / 颜色，不过渡 box-shadow；点下去这张上浮 6rpx、图放大 1.04、其余变淡（这只是「我选了它」，对错仍等服务端）；对 = 描边绿（`--ok`），错 = 描边红（`--accent`）+ 红字落在题下（沿用 `.qa__fb--no`），判错后其余复位可再选；圆角 / 切角读 `--lk-r` / `--lk-cut`，没有外观时落回 DS 圆角；没配图的选项是字母块。纯文字题仍是原来的细线文字行（`playkit-fidelity-contract` 不动）。 | `pages/play/components/playkit-qa/{index.wxml,index.wxss,index.js}` | 截图 `lk-qa-img`、`-picked`、`-wrong` 改前 / 改后，`-picked-reduced-after`；单测 `questo-b2-player`、`play-qa-choice-feedback`、`playkit-fidelity-contract`、`tg3-qa-shared-instance-contract`、`play-qa-*` 全过 | 内嵌在故事流里时的宽度与章节配色（rig 只能由整屏宿主承载同一组件）；OSS 图片加载 |
| LK-02 逐字猜词 · 系统键盘（原型 `gp-kit-full`） | 已改 | 不再画隐藏输入框 + 整词栏：一个看得见的输入框就在格子下面，接系统键盘；输入什么，可编辑格按顺序填什么（空白跳过、字数封顶 = 可编辑格数）；点某一格 = 光标挪到它并唤起键盘；预填格深一档（`--lk-ctl`），当前格描边主色（`--lk-a`）上浮；每填一格弹一下（`is-pop` 220ms 过渡，不是关键帧）；「提交」固定底部，填满才亮，只交 `{slots}`；服务端判「没解开」→ 红字「不对，再想想」落在输入框下（升 16rpx 淡入）并清空重来（原型）；答对格子依次变绿（`transition-delay` 70ms × 序号，封顶 8）。格子 68×84rpx，字体 / 圆角 / 切角读 `--lk-*`。 | `pages/play/components/playkit-hangman/{index.wxml,index.wxss,index.js}` | 截图 `lk-hang`、`-typing`、`-solved` 改前 / 改后，`-typing-reduced-after`；单测 `questo-b2-player`（UG-01 四条 + 新增「输入分配 / 光标 / 载荷 / 初值 / 过渡」一条） | 真机系统键盘弹起对输入框的顶起、中文输入法下的逐字落格、`cursor` 在不同基础库的表现 |
| LK-03 判案 · 线索可点开（原型 `gp-kit-accuse` ①） | 已改 | 线索板上的便签 = 本局**已获得**的线索（服务端 `view.clues` 回读，与背包同一份：名字 + 来源段落的文字 / 第一张图），最多画 6 张，位置 / 倾斜按原型三个槽位循环，顶上一颗图钉；点便签开居中弹窗：「线索」+ 图 + 名字 + 一句描述 + 「收起」；图读不出来只撤图不出破图。没有已获得线索就没有便签，不编占位；没板图也没线索就不画板。 | `pages/play/utils/playkit-questo.js`（`accusationKit` 新增 `clues`）、`pages/play/components/playkit/index.wxml`（`clues="{{kit.clues}}"`）、`pages/play/components/playkit-accusation/*` | 截图 `lk-acc`、`lk-acc-clue` 改前 / 改后；单测 `questo-b2-player`「线索板的便签只画服务端回读的已获得线索」 | 见跨 lane 1：线索数据要游玩页把主题会话的 `clues` 塞进交给 `pickPlayKit` 的 view，截图里是驱动直接给的 |
| LK-04 判案 · 嫌疑人翻页卡（原型 `gp-kit-accuse` ②） | 已改 | 3 人一排的人物卡改成翻页卡：一次一张（照片 + 头像字 + 名字 + 身份 + 服务端给的证词），点卡片 / 右下角箭头 / 「再想想」绕竖轴翻到下一张（`rotateY ±90deg` 过渡 480ms，`--cy-ease-flip`），翻完才换当前；圆点 + 箭头叠在卡组底部；底部「指认 X」跟当前这张；指错时红字（服务端 `lastFeedback`）落在卡组上方；确认改成居中弹窗（头像 + 「这是最终指认：X」+ 身份 · 指认后不能更改 + 「确认指认」+ 「再想想」），确认后才交原 `SUBMIT_QA` 的 optionId。弹窗 / 卡片的底、圆角、切角、字体读 `--lk-*`，没有外观时落回玩家弹层 token。 | `pages/play/components/playkit-accusation/*` | 截图 `lk-acc`、`-flip`、`-confirm`、`-wrong` 改前 / 改后，`-flip-reduced-after`；单测「翻页卡」「确认是居中弹窗」两条（含组件实例：翻页中不叠加、到头回第一张、取消不发事件、确认交当前卡的 optionId） | 真机 `backface-visibility` / `perspective` 在低版本基础库；翻页手势（原型只有点按，没做滑动） |
| LK-05 判案 · 结果整屏插图（原型 `gp-kit-accuse` ③ / UG-04） | 已改 | 正常结案 / 指认失败 / 结算确认中整屏：底座 `cy-illus` 的 成功 / 失败 / 调查中 插图（成功带光晕）+ 大字结论（失败红）+ 结局段 + 「继续」，标题 / 结局 / 按钮依次升上来（500 / 800 / 1300ms 过渡）；结算确认中没有按钮，服务端给结论后同屏换成结果。整屏档 `position: fixed` 盖住台面；内嵌档在流里占一块（见真机未验）。r2 的 Google Noto 动态表情（🎉 / 😵 / 🔎）随 `subpackageTools/components/case-anim` 整目录删除（业主：不要那种 icon）。 | `pages/play/components/playkit-accusation/*`、删 `subpackageTools/components/case-anim/**` | 截图 `lk-acc-solved`、`-failed`、`-pending` 改前 / 改后，`-solved-reduced-after`；单测「结论整屏用 cy-illus 插图」 | 内嵌档的结果不能从组件里做成整屏（`.chfull__para` 带 transform，是 fixed 的包含块），只能在流里占一块；要整屏得由游玩页承载（跨 lane 2） |
| LK-06 判定屏（全部玩法共用）· 对勾换插图 | 已改 | `cy-play-verdict`：赢 = `cy-illus` 成功插图弹入（不再画对勾），输 = 失败插图 + 两个字；中性判定（分支去向）不画；液面、气泡、两个字的措辞不变；减少动态效果由 `cy-illus` 直接终态。 | `pages/play/components/play-verdict/{index.wxml,index.wxss,index.json}` | 单测 `l-kits-20261110`「判定屏」、`gameplay-feedback-images-bgm`、`playkit-qa-authored-outcome-p10`、`r12-k8-play-fixes` 全过 | 其他 19 个玩法的判定屏没有逐一截图（同一个组件，只换了中间那一块） |
| LK-07 掷骰子 · 内嵌也可摇一摇 | 已改 | 业主第二轮批复「掷骰子也可以摇晃的」：内嵌档也接 `play-shake` 同一识别器（同阈值、同 900ms 防抖），按钮仍是没有加速度计时的退路；隐藏 / 关掉 / D20 一律撤监听。推翻了 2026-09-18「走路误摇」的旧裁决，注释写明。画面无变化，不截图。 | `pages/play/components/playkit-diceroll/{index.js,index.wxml}`、`tests/unit/play-shake-device-lifecycle.test.js`、`tests/unit/playkit-present-inline-contract.test.js` | 单测 `play-shake-device-lifecycle`（内嵌露出开传感器、摇一下就掷、隐藏撤监听、D20 不听、失效旧回调不掷）、`r2-play-20261110`、`ballshake-recovery` 全过 | 真机加速度计读数与震动手感；rig 没有传感器 |
| LK-08 底座 `cy-illus` 修一处格式错误 | 已改 | rig 实测 25 张插图里 24 张只剩白色底形、物件不渲染：原型把 SVG 内联在 HTML 里，HTML 解析器对同一标签的重复属性取第一个；底座改成 data URI 的独立 SVG 后走 XML 解析，重复属性（`stroke-width="5" stroke-width="4"`、`stroke` 两次）= 格式错误，整张图不渲染（Chrome `naturalWidth` 为 0）。`art.js` 的 `uri()` 里加一步同标签重复属性只留第一个 —— 取第一个 = 与业主在原型里看到的一致。 | `pages/play/components/cy-illus/art.js` | 单测 `l-kits-20261110`「cy-illus 同一标签里重复的属性只留第一个」（25 张 × 两层逐标签核）、`launch-look-tokens-illus` 全过；headless Chrome 实渲 25 张全部有像素（`broken []`） | 真机 `<image>` 对 data URI SVG 的解析器是否同样严格（按 XML 规则推断是） |

## 二、动图 / 素材许可

本 lane 不引入任何第三方素材；插图全部来自底座 `cy-illus`（原创 SVG）。r2-kits 引入的 Google Noto 动态表情（CC BY 4.0）三组 GIF / PNG 已删除，`case-anim/index.js` 头部的署名随文件一起退场。

## 三、证据

- 截图在 `shots/`，390×844、JPEG q70。改前 = `origin/launch/questo-20261110`（`ca1f85b6`，用 `/root/base` 工作树出的包），改后 = 本分支；同一份视图、同一组步骤各跑一次。每张都看过：无空标题、无重叠、无截断、数字不矛盾。改前的包没有新类名，`.qa__pic` / `.hm__in` / `.ac__pin` / `.ac__arrow` 这几步在改前跳过（截的是改前那一屏的静态）。`-reduced-after` 是减少动态效果（`play_reduced_motion` 开关）下的终态。
- 玩家屏用真实 `pages/play/index` + 真实 `cy-playkit` 组件，由本树自己的 `pickPlayKit` 把服务端形状的会话视图（字段名照 `AdvancedGameRuntimeServiceImpl` / `HangmanGameplay.publicProjection` / `accusationView` / `GameSessionRuntimeServiceImpl.clueView`）变成 kit 再渲染；驱动 `rig/kit-shots.mjs`（r2-kits 的驱动 + `type` 步骤 + 减少动态效果开关 + 改前缺选择器时跳过不中断），数据 `rig/kit-states.mjs`，换包 `rig/swap-bundle.sh`。
- rig：`claude/cloud-walk-20261101-rig` + staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff` + qx-u1 `rig-wxs-event-shim.diff` + club 分支 `harness-patches.diff`；后端是本分支打的 jar（Java 与底座相同）。
- rig 限制（HARNESS）：没有建一个带这几种节点的已发布主题，玩家屏不是由真实 `/api/play/advanced/start` 会话驱动的（与 r2-kits 相同）；rig 的宿主是整屏那一个（`index.wxml:1722`），所以「嵌入」图片题截的是同一组件在整屏宿主里的样子，故事流里的宽度 / 章节配色没截到；rig 没有传感器与系统键盘（输入框用 `type` 步骤直接写值）。

## 四、门禁

| 门禁 | 结果 |
|---|---|
| 本 lane 相关单测 `node --test tests/unit/{questo-b2-player,l-kits-20261110,play-shake-device-lifecycle,playkit-present-inline-contract,play-qa-choice-feedback,playkit-fidelity-contract,tg3-qa-shared-instance-contract,play-qa-recovery-contract,play-qa-completed-handoff,playkit-typography-token-contract,playkit-qa-authored-outcome-p10,gameplay-feedback-images-bgm,r12-k8-play-fixes,launch-look-tokens-illus,press-feedback-coverage-contract,action-ledger}.test.js` | 全部通过（见下「全量」） |
| 小程序全量单测（仓库根 `node --test "chengyinhub-xcx/tests/unit/*.test.js"`） | __FULL__ |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS**（U2 / U3 / U4 / U5 / A11Y / Legacy 全 PASS；没有新增 baseline 条目。途中 U4 曾报一处动态 setData，已改成字面量） |
| `build-action-ledger.js --write` | __LEDGER__ |
| `scripts/ds-hardcode-gate.sh`（暂存区模式） | 通过，0 行报警（色值行都带 `ds-ok`，字号全是 `--cy-type-*`） |
| `motion-property-ratchet` | `@keyframes` 实测 169（与底座相同，本 lane +0；上限 146 本来就是红的，见 qx-base 待你决定 2）；`transition: all` 0 |
| `reduced-motion-coverage-contract` | 本 lane 文件零缺口（失败清单与底座完全相同：share-card / ai-npc / team / journey / gift，均非本 lane） |
| `playkit-typography-token-contract` | 通过（途中两处裸字号 36rpx / 60rpx 已换成 `--cy-type-section-title` / `--cy-type-page-title`） |
| 主包 `mainpkg-size-report.js --json` | `mainTotal` = 3,284,987 字节 × 0.633 ≈ **2030.7 KiB**，与底座完全相同（所有改动都在 `pages/play` 分包；`subpackageTools` 少了 218 KB 动图） |

## 五、跨 lane

1. **l-play**（`pages/play/index.js` ≈ 7598 行）：指认页的线索便签读 kit 的 `clues`，`accusationKit` 从交给 `pickPlayKit` 的 view 取 `view.clues`；高级玩法会话视图本身没有这个字段，请在那一处 `Object.assign({}, view, {...})` 里把主题会话的已获得线索（背包用的同一份 `clues`）塞进去。没塞之前线索板只有板图、没有便签（不会出错，也不会编占位）。同一行里的 `clueTransform` 已是无效赋值（r2-kits 跨 lane 1），可一并删。
2. **l-play**：内嵌在故事流里的判案结果 / 确认弹窗只能在流里占一块（`.chfull__para` 的 transform 是 fixed 的包含块）。要像整屏档那样盖住全屏，需要游玩页在 `kitaction` 收到 `verdict` 时自己承载一层（可直接复用 `.ac__end` 的结构与 `cy-illus`）。
3. **qx-base / qx-final**：`cy-illus/art.js` 的重复属性修复（LK-08）在本分支；其他分包逐字复制 `cy-illus` 时请从本分支之后的版本复制，否则物件不渲染。
4. **l-after / 其他用 `cy-illus` 的 lane**：同上。

## 待你决定

1. **线索弹窗的按钮文案。** 原型写「收进背包」，但便签画的是**已经在背包里**的线索（服务端已获得），再写「收进背包」是假动作。**已实现的默认**：按钮写「收起」，线索详情照原型（图 + 名字 + 一句描述）。要照原型字面写「收进背包」，改一个字符串即可。
2. **`cy-illus` 重复属性取第一个还是最后一个。** 取第一个 = 原型在浏览器里实际渲染的样子（例如线索放大镜里那条高光线是黑色、不是柠檬绿）；取最后一个 = 原型作者写代码时的意图（高光线柠檬绿）。**已实现的默认**：取第一个（与你批准时看到的一致）。
3. **判案确认弹窗没有右上角 ×。** 照原型 `pop-accuse`（`close:false`），关掉只有「再想想」文字按钮；r2 那版的右上角关闭随之退场。**已实现的默认**：照原型。
4. **掷骰子摇一摇推翻旧裁决。** 2026-09-18 你说过故事流里内嵌的骰子不要摇（走路误摇），第二轮又批「掷骰子也可以摇晃的」。**已实现的默认**：按第二轮，内嵌也摇；阈值与整屏 / 抛硬币相同，不另调灵敏度。
5. **翻页卡的手势。** 原型只有点按（卡片 / 箭头 / 再想想），没有左右滑。**已实现的默认**：只做点按；要滑动翻页需要接 u1 的 `gesture.wxs`，另起一项。

STATUS: __STATUS__
