# 首页「推荐主题」横向轮播动效研究与 Figma 规格

日期：2026-08-30
范围：只研究并定义「非激活卡缩小 + 黑色半透明 Background Blur 遮罩，滑入时放大且遮罩/模糊连续退到 0」的交互；不代表当前小程序代码结构，也不包含代码修改。

## 结论

这个效果应该设计成一个**由横向拖动进度统一驱动的焦点变化**，而不是「先移动、再放大、最后去遮罩」三段动画：右侧预览卡在向焦点位移动的同一时间，从 `280px` 连续增长到 `306px`，黑色遮罩从 `18%` 连续降到 `0%`，Background Blur 从 `8px` 连续降到 `0px`。旧的焦点卡做完全对称的反向变化。

建议第一版直接采用：

| 状态 | 卡片宽度 | 相对尺寸 | 黑色填充 | Background Blur |
| --- | ---: | ---: | ---: | ---: |
| 非激活 / 右侧预览 | 280px | 91.5% | 18% | 8px |
| 拖动 50%（检查点，不必单独做帧） | 293px | 95.8% | 9% | 4px |
| 激活 / 焦点卡 | 306px | 100% | 0% | 0px |

`306/280` 来自当前已调整的参考画面；`18%/8px` 是建议起始值，不是平台强制值。实看时可把黑色填充在 `16%–22%`、模糊在 `6–10px` 内微调。不要先上 `12–20px` 的重模糊，它会让下一张看起来像不可用或加载失败，而不是“可继续滑动”。

## 为什么这样设计

1. Google 的 Material 3 把 Hero carousel 定义为「一个大的焦点项目 + 一小块下一项预览」，正好对应首页推荐主题的主卡与右侧预览卡。Android 官方 Pager 文档也明确建议用页面到焦点的距离驱动透明度等视觉属性，并默认一次 fling 只跨一页。[Material 3 Carousel](https://developer.android.com/develop/ui/compose/components/carousel) · [Pager in Compose](https://developer.android.com/develop/ui/compose/layouts/pager)
2. Google 的开源 Material Carousel 实现不是等滚动结束后才换样式，而是随 mask 尺寸变化连续更新内容位置与 alpha；官方示例直接在 `onMaskChangedListener` 中用 `lerp` 淡出标题。这支持“同一进度驱动尺寸与明暗”的做法。[Material Components Carousel source guidance](https://github.com/material-components/material-components-android/blob/master/docs/components/Carousel.md#reacting-to-changes-in-item-mask-size)
3. Apple 的 SwiftUI `scrollTransition` 是交互式插值：视图接近滚动区边缘时缩小，回到中心 identity phase 时恢复。Apple 的官方示例给出的核心公式就是 `1 - abs(phase.value) * 0.1`，不是离散地切换大小。[Beyond scroll views (WWDC23)](https://developer.apple.com/videos/play/wwdc2023/10159/) · [WWDC26 scroll-effect example](https://developer.apple.com/videos/play/wwdc2026/321/?time=456)
4. Apple 对焦点型图像的描述同样是：失焦内容变暗，获得焦点的内容扩大；同时强调效果要克制，避免夸张的 3D 感。这证明“缩小 + 暗化 + 聚焦时恢复”是成熟的焦点表达，但不支持把它做成强烈弹跳或重度景深。[Apple HIG: Images / Parallax effect](https://developer.apple.com/design/human-interface-guidelines/images)
5. Figma 官方确认 `On drag` 会让用户在前后状态之间可逆、连续地移动，而不是等 swipe 完成后才播放；Smart Animate 支持尺寸、位置、填充，以及带 background blur/layer blur 的匹配图层。因此此效果可以在 Figma 中真实表达，不必用视频假装。[Figma: Prototype triggers](https://help.figma.com/hc/en-us/articles/360040035834-Prototype-triggers) · [Figma: Smart animate](https://help.figma.com/hc/en-us/articles/360039818874-Smart-animate-layers-between-frames)

没有采用 Spotify/App Store 截图作为关键证据：截图只能证明某一静态时刻的外观，不能证明它如何按滚动进度插值。这里优先使用 Figma、Google、Apple 的官方交互文档和 Google 的开源源码。

## 统一的状态插值

令每张卡中心到焦点中心的归一化距离为：

```text
d = clamp(abs(cardCenterX - focalCenterX) / snapDistance, 0, 1)
focus = 1 - d
```

推荐的线性基线：

```text
width       = 280 + 26 * focus                  // 280 -> 306
scale       = 0.915 + 0.085 * focus             // 91.5% -> 100%
blackAlpha  = 0.18 * d                          // 18% -> 0%
blurRadius  = 8px * d                           // 8px -> 0px
```

这与 Android 官方示例按 `pageOffset`、`lerp` 和 `coerceIn(0, 1)` 计算透明度的方式一致。[Android 官方 Pager 示例源码](https://github.com/android/snippets/blob/50755e81ab2871f73ecdb762292d17895a483bff/compose/snippets/src/main/java/com/example/compose/snippets/layouts/PagerSnippets.kt#L266-L294)

运行时代码如果需要更快“清焦”，可以把遮罩改成轻微非线性：

```text
blackAlpha = 0.18 * pow(d, 1.15)
blurRadius = 8px * pow(d, 1.25)
```

但第一版 Figma 不建议增加这层复杂度；一个 `On drag + Smart Animate` 的连续线性基线更容易评审和交接。不要用三个延迟动画制造“位移—放大—去模糊”的错位。

## Figma 落地方法

### 1. 结构

建议把推荐主题轮播做成一个组件集，至少三种状态：

```text
Home/RecommendationCarousel
├── activeIndex=0
├── activeIndex=1
├── activeIndex=2
└── motion=reduce（可选的无障碍展示态）
```

每个业务主题卡在所有 variant 中保持**自己的唯一名字和完全相同的父级层级**，例如：

```text
topic-card/topic-01
├── media
├── deemphasis-overlay
├── title
└── meta
```

不要命名为 `左卡/主卡/右卡`。位置名会导致 Smart Animate 把不同主题内容互相变形；业务 ID/稳定名称才能让同一张卡从右边移动到焦点位。Figma 的匹配同时依赖 layer name 和 hierarchy；官方也建议直接复制原帧来保留匹配关系。[Figma: Identify matching objects](https://help.figma.com/hc/en-us/articles/21523793229463-Identify-matching-objects) · [Figma: Smart animate tips](https://help.figma.com/hc/en-us/articles/360039818874-Smart-animate-layers-between-frames#h_01HBH3VJKN30BD55J86061HNYG)

### 2. 遮罩层

`deemphasis-overlay` 应该始终存在于所有状态中，放在卡片内部并由卡片圆角 `Clip content`：

- 非激活：`Fill #000000 / 18%`，`Background blur / 8px`，layer opacity `100%`。
- 激活：同一图层仍保留，`Fill #000000 / 0%`，`Background blur / 0px`，或保留 `8px` effect 但把 overlay layer opacity 设为 `0%`。
- 不要在激活态删除图层、关闭 visibility 或换一个新图层；新图层会 dissolve，无法得到连续清除效果。
- Figma 的 background blur 是模糊该图层**后面**的内容，不是模糊黑色矩形本身；它只有在填充不是 100% 不透明时才可见。官方也提醒 background blur 通常是最慢的 effect，因此只保留当前可见的 1–2 个模糊层，不要在一串离屏卡上都堆 blur。[Figma: Apply effects to layers](https://help.figma.com/hc/en-us/articles/360041488473-Apply-effects-to-layers#h_01HVD2E919XCSGKX1G78M3XJQF)

推荐把标题、标签等仍需扫描的信息放在 overlay 上层，只让媒体图失焦；如果当前设计明确要整卡失焦，则至少保证卡片的点击热点在遮罩之上，并在真正实现时提供可访问名称。不要让视觉遮罩吞掉交互。

### 3. 尺寸与位置

- 两个状态中分别把焦点卡宽度设为 `306px`、预览卡设为 `280px`，高度按原纵横比同步变化。
- 缩放锚点用卡片中心，保持所有卡的垂直中心线一致；否则卡片会看起来向下长大或向上跳。
- 当前激活卡与滑入卡做完全对称的互换：新卡 `280 -> 306`，旧卡 `306 -> 280`。
- 卡片必须在固定宽度、开启 `Clip content` 的 carousel viewport 里移动；右侧只露出已确认的预览宽度，不要随着缩放改变首页整体横向边距。
- 为避免 Auto Layout 在拖动时重新分配空间造成跳位，原型状态中最好显式设置每张卡的 `x/y/width/height`，或确认 Auto Layout 两个 variant 的 item 顺序、gap 和约束完全一致。

### 4. 原型连接

组件 variant 方案：

```text
activeIndex=0 -- On drag / Change to / Smart Animate --> activeIndex=1
activeIndex=1 -- On drag / Change to / Smart Animate --> activeIndex=2
activeIndex=1 -- On drag / Change to / Smart Animate --> activeIndex=0
```

如果现有首页用顶层 frame，则把 `Change to` 换成 `Navigate to`，其余不变。建议补全双向连接，确保拖到一半可以反向回退。

- Trigger：`On drag`
- Animation：`Smart Animate`
- Easing：`Custom bezier (0.2, 0, 0, 1)`
- Duration：`280ms`（主要影响松手后的补全/回弹；拖动过程由手势直接控制）

Material 的标准 on-screen easing 就是 `cubic-bezier(0.2, 0, 0, 1)`，中等时长为 `250–350ms`；Figma 官方也把常规可感知交互动效放在 `150–300ms` 范围。这里用 `280ms` 是为了兼顾卡片位移与清焦，又不拖慢首页浏览。[Material motion source guidance](https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md#curves-easing--duration) · [Figma interactive button timing guidance](https://help.figma.com/hc/en-us/articles/20953528101783-Design-an-interactive-button)

不要在同一个区域同时依赖 Figma 的 `Horizontal overflow scrolling` 和 `On drag → Smart Animate` 来驱动这个效果。前者能滚内容，但标准原型没有暴露“滚动位置 → 任意图层尺寸/模糊”的绑定；后者才负责连续形变。两者叠在同一触摸区还可能争夺手势。这一点是根据 Figma 两套官方能力边界得出的实现推论。[Figma: Scroll and overflow](https://help.figma.com/hc/en-us/articles/360039818734-Prototype-scroll-and-overflow-behavior) · [Figma: On drag](https://help.figma.com/hc/en-us/articles/360040035834-Prototype-triggers#h_01H8FJ5CNZNDX9C5NMV0WSNS2F)

## 手势与状态规则

推荐交互：

- 横拖过程中即时跟手，不等松手才放大/清除遮罩。
- 松手后吸附到最近一张，一次最多跨一张；Android Pager 的默认 fling 也是一次一页，能维持焦点变化可预测。
- 运行时建议阈值：位移超过卡宽 `25%`，或横向速度超过约 `600px/s` 时前进；否则回到原卡。数值需在真机上复核，这不是平台规定。
- 首页本身仍可纵向滚动时，运行时在 `abs(dx) > 1.2 * abs(dy)` 后才锁定横向拖动，避免斜滑时劫持首页纵向滚动；Figma 只展示意图，不把它当作手势冲突验证。
- 点右侧非激活卡：建议先把它吸附为激活卡；点已激活卡才进入主题详情。这样“模糊 = 尚未选中”的视觉语义和操作一致，也减少误开详情。
- 第一张向右拖、最后一张向左拖时不进入空白状态；回弹到边界卡。
- 不建议自动轮播。若产品以后必须自动播放，用户一拖或一按就应暂停；Android 官方 auto-pager 示例就是在 drag/press 时停止自动前进。[Android Pager auto-advance example](https://developer.android.com/develop/ui/compose/layouts/pager#create-an-auto-advancing-pager)

## Reduced Motion / Reduced Transparency

这个组合同时包含 scaling、外围运动和 animated blur，属于必须提供降级的类型。Apple 明确建议 Reduce Motion 时减少 zoom/scale/peripheral motion，并特别建议避免在 blur 中进出；其 App Store 辅助功能判据也要求对 animated blur、景深和自动轮播作禁用或替代。Web 侧的 W3C Media Queries Level 5 分别定义了 `prefers-reduced-motion` 和 `prefers-reduced-transparency`。[Apple HIG: Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility) · [Apple Reduced Motion evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria) · [W3C Media Queries Level 5](https://www.w3.org/TR/mediaqueries-5/#user-preference)

Figma 中至少补一个 `motion=reduce` 说明态：

- 不做连续放大，也不做 blur 动画。
- 松手后直接吸附，或只用 `Dissolve 120–150ms` 表示选中项变化。
- 用稳定的边框、焦点指示点或标题强调当前项，不依赖景深表达焦点。
- Reduced Transparency 下关闭 background blur；不要把半透明毛玻璃换成更重的毛玻璃。

真正实现时应读取目标平台的系统设置；当前文档不假设小程序现有代码已经有这些能力。

## 常见失败模式

| 失败 | 表现 | 修正 |
| --- | --- | --- |
| 激活态直接删掉 overlay | 遮罩突然 dissolve，不是逐渐消失 | 两态保留同名、同层级 overlay，只改 fill/blur/opacity |
| 按位置命名卡片 | 主题 A 变形为主题 B，图片文字串帧 | 以业务 ID 固定命名卡片，位置只改坐标 |
| 两态层级不同 | Smart Animate 找不到匹配层，整块淡入淡出 | 从已调整首页复制 variant，再改属性 |
| overlay 不在卡片 clip 内 | blur/黑色越过圆角或边界 | overlay 放进卡片 frame，父级开启 Clip content |
| fill 设为 100% 黑 | background blur 不可见，只剩黑片 | fill 保持半透明；Figma 官方要求背景模糊层不是 100% 不透明 |
| blur 太重 | 预览卡像禁用或加载失败 | 先用 8px，真机/原型评审后只在 6–10px 内调 |
| 只放大新卡、不缩小旧卡 | 两卡短时同时抢焦点 | 新旧卡使用同一个 `d/focus` 做对称插值 |
| 缩放锚点在左上角 | 卡片边缘向下/向右跳 | 使用中心锚点，并锁定垂直中心线 |
| Auto Layout 自动推挤 | gap 和露出宽度在拖动中抖动 | variant 中固定 item 顺序与约束，必要时显式坐标 |
| 所有离屏卡都用 Background Blur | Figma 原型卡顿，交接时误以为曲线有问题 | 只保留可见卡的 blur；Figma 官方称 background blur 通常最慢 |
| 同一触摸区同时做 overflow scroll 和 On drag | 手势被争抢、形变不跟手 | 原型评审选 On drag 状态机；真实滚动留给代码实现 |
| 把参考图当当前代码事实 | 设计交接后改错旧结构/旧数据 | 参考图只定视觉意图；编码前重新读取当前首页源代码和实际渲染 |

## Figma 验收清单

- [ ] 从右侧拖到 25%、50%、75% 时，尺寸、黑色遮罩和 blur 同步连续变化。
- [ ] 拖到一半反向退回，三种属性都可逆，没有跳帧或 dissolve。
- [ ] 新焦点卡到 `306px / 0% / 0px`；旧焦点卡到 `280px / 18% / 8px`。
- [ ] 新旧卡同一时刻只有一个明确视觉焦点。
- [ ] 右侧仍保留原参考画面确认过的预览露出量；首页左右边距不漂移。
- [ ] 圆角、图片裁切、标题和标签没有被放大裁掉。
- [ ] 左右双向拖动与首尾边界都能工作。
- [ ] 默认 motion 与 `motion=reduce` 说明态都可评审。
- [ ] Prototype Present 实测，而不是只看静态两帧。
- [ ] 设计备注明确：此图是首页视觉参考，当前代码结构需在实现前重新读取，不能照旧稿猜。

## 证据强度与边界

- 高可信事实：Figma Smart Animate/On drag/Background Blur 能力与限制；Google Pager/Carousel 的按距离插值与焦点布局；Apple scrollTransition 与 Reduced Motion 指南；W3C 用户偏好媒体特性。
- 设计建议：`18%` 黑色、`8px` blur、`280ms`、25%/速度阈值和“两次点击语义”。这些需要在当前 Figma 原型、微信开发者工具和真机上验证后才可定稿。
- 未验证：当前首页实际代码、组件层级、Swiper 能力、低端机 blur 性能、微信上传与真机视觉。用户已经明确代码首页与参考图不同，因此本研究不从旧参考图反推代码实现。
