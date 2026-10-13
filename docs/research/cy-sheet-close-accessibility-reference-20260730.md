# cy-sheet 关闭控件：HIG 与本机参考依据（2026-07-30）

## 范围与结论

本轮只修 components/cy/sheet 标题栏的默认关闭控件：保留现有 closable / close 事件、遮罩和拖拽把手行为，不改 23 个 WXML 消费者（44 个 <cy-sheet> 标签）、24 个 JSON 注册或任何页面。

结论是把小号视觉 ✕ 放进现有 --cy-btn-h（当前 88rpx = 44pt）的透明真实热区，并给它 aria-role="button" 与 aria-label="关闭"。热区在仅 closable 的 header 内绝对居中；同一 header 最小容纳 88rpx，标题仅在此时预留完整右侧关闭位，故 X 保持原右侧锚点且不压标题/body 的命中区。字体、颜色和右侧留白继续分别读 --cy-font-subtitle、--cy-text-secondary、--cy-space-1，不引入颜色、文字资产或新 token。

几何裁决：原单行 header 小于 88rpx，不能在“不改变任何 flow”的前提下同时获得完整、不裁切且不压相邻内容的 88rpx 热区。故仅 closable=true 时允许 header 的最小可用高度提升到现有 --cy-btn-h；这是约 18rpx 的结构余量，不是放大视觉 X，也不会影响 closable=false 的标题栏。禁止用外溢或与 body 重叠来伪造 88rpx 命中区。

## Apple HIG 口径

- [Modality](https://developer.apple.com/design/human-interface-guidelines/modality) 要求 modal 有明显的退出方式；关闭会丢失输入时，调用页应拥有自己的确认/保存策略。本轮不改页面业务关闭策略。
- [Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons) 要求按钮 hit region 至少 44 × 44 pt。这里量的是可点容器，不是 ✕ 图形本身，因此采用现有 88rpx 按钮高度 token。
- [Gestures](https://developer.apple.com/design/human-interface-guidelines/gestures) 说明不能假定所有人都能使用特定手势；本组件保留既有 handle/遮罩关闭路径，同时为有标题的可关闭 sheet 提供非手势语义出口。
- [Icons](https://developer.apple.com/design/human-interface-guidelines/icons) 将 xmark 用作 Cancel/Close 的标准符号。这里沿用项目现有 ✕，仅补可读标签；不从 HIG 推出必须圆形、必须某个边角或强制所有 sheet 同时拥有 Cancel/Done。

## 约 10 张相关参考的筛选边界

不能诚实地把 8–10 张 Revolut 图都称为“一方 close/sheet 证据”：直接可核实的只有关闭 X、返回/保留草稿和 sheet grabber。故最终书面依据只保留下列 10 张相关图，明确区分关闭、层级返回和辅助动效：

1. [Revolut Business App Store Screen 3](https://is1-ssl.mzstatic.com/image/thumb/PurpleSource221/v4/bb/37/e2/bb37e241-87de-ba8b-f411-4dc8a6b0bd34/Screen_3_1242x2688_en-UK.jpg/600x1300bb-60.jpg)：全屏账户选择覆盖层的显式 X。
2. [App Store Screen 5](https://is1-ssl.mzstatic.com/image/thumb/PurpleSource221/v4/ae/da/38/aeda3890-2b7f-e51b-d944-2c87432b4474/Screen_5_1242x2688_en-UK.jpg/600x1300bb-60.jpg)：层级 Back，不替代 modal Close。
3. [App Store Screen 6](https://is1-ssl.mzstatic.com/image/thumb/PurpleSource211/v4/71/2f/9f/712f9fdf-d5bd-855c-5b67-0270097797f3/Screen_6_1242x2688_en-UK.jpg/600x1300bb-60.jpg)：转账流程的 Back 与 Save draft。
4. [App Store Screen 7](https://is1-ssl.mzstatic.com/image/thumb/PurpleSource211/v4/b1/7c/c1/b17cc165-fda6-55af-a681-71848c6a0a8d/Screen_7_1242x2688_en-UK.jpg/600x1300bb-60.jpg)：卡片详情的底部 sheet grabber。
5. 本机 Revolut Business ios Feb 2026.zip 中的 413.png：底部成功 sheet，内容层与背景层分离。
6. 本机 /Users/arlan/Downloads/Revolut Business iOS 415.png：Withdraw 流的 Back 语义。
7. 本机 05-参考资料/mobbin动效参考_20260719/ff-sheet-1.png。
8. 本机 05-参考资料/mobbin动效参考_20260719/ff-sheet-2.png。
9. 本机 05-参考资料/mobbin动效参考_20260719/luma-sheet-1.png。
10. 本机 05-参考资料/mobbin动效参考_20260719/luma-sheet-2.png。

本机 ZIP 的条目时间为 2026-07-28 且带 Mobbin 水印，因此只作二方视觉参考，不能证明 2026-02 的一方产品事实。可借鉴的是“小图标/把手与更大交互区域分层、sheet 不因退出控件而改变正文层级”；不复制深色底、圆形返回键、字体、文案、图标或资产。

## 仓内同类对照与最小方案

- components/cy/upload 已采用“88rpx 热区 + 更小视觉删除角标”的分层模式。
- components/cy/free-map 已用 88rpx 外层命中区包住较小视觉退出控件，且以契约负控锁定横纵尺寸。
- components/cy/creation-success 已使用 aria-role="button" 与 aria-label="关闭"；cy-sheet 只吸收同一语义模式，不改其余组件。

实现约束：关闭控件仍只在 title && closable 时渲染；不让无标题 picker 自动长出 X，不改变 maskClosable、onClose、事件名或页面绑定。

## 可验证判据

新增 focused contract 读取真实 WXML/WXSS/token，并要求：

1. aria-role="button"、aria-label="关闭"、原有 closable 和 onClose 全部存在。
2. 热区横纵均读 --cy-btn-h，并反查该 token 当前值不小于 88rpx。
3. 仅 closable 的 .sh__head 用 --cy-btn-h 完整容纳热区，标题用同一 token 预留关闭位；.sh__close 保持居中绝对定位，视觉 X 仍使用原有字体/颜色 token，不增加背景、描边或资产。
4. 负控分别移除标签、把宽度收窄到 64rpx、撤销标题关闭位预留，均须失败。
