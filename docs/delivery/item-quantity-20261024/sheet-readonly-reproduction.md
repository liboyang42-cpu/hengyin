# #338 stacked sheet：只读调用链与待复现步骤

核对源码：`e2e05b9614a3c5d62b774718797f9a6792a3eeca`。状态 **unknown**：处理器探针可以证明 stacked 时仍发 close/back，不能证明真实微信触摸能触达下层。本 PR 不修改 sheet，不把本项列为发布阻塞。

## 真实页面调用链

所有路径以下均相对 `chengyinhub-xcx/`：

1. `pages/publish/fabu/index.wxml` 的主题详情卡绑定 `openTopicDetail`；`index.js` 设置 `topicDetailShow=true`。页面通过 include 加载 `topic-detail-sheet.wxml` 和 `runtime-editor.wxml`，通过 `runtimeEditor.methods` 展开 `theme-runtime.js` 方法。
2. `topic-detail-sheet.wxml` 的父 `cy-sheet`：`show=topicDetailShow && !runtimeRewardOpen`，`stacked=runtimeEditor.show || survivalEditor.show || structureEditor.show`，close 绑定 `closeTopicDetail`。主题通关奖励卡绑定 `openThemeRewards`。
3. `theme-runtime.js#openThemeRewards` → `_openRuntime('rewards', ...)` → `runtimeEditor.show=true`。`runtime-editor.wxml` 第二层 sheet：`show=runtimeEditor.show`，`stacked=runtimeRewardOpen`，close 绑定 `closeRuntimeEditor`。
4. 第二层的优惠券 `cy-reward-selector` 在 `index.json` 映射到 `pages/publish/components/reward-selector/index`。其 `index.js#openSelector` / `closeSelector` 发 `openchange`；`theme-runtime.js#onRuntimeRewardOpen` 设置 `runtimeRewardOpen`。第三层选择器在自己的 WXML 使用 `cy-sheet show=open`。
5. `components/cy/sheet/index.wxml`：mask `bindtap=onMask`，关闭键 `bindtap=onClose`，可选返回键 `onBack`，抓手 `onDragStart/Move/End`。
6. `components/cy/sheet/index.js` 上述入口和 `_requestClose` 只检查 `_closing`；干净状态下 `_requestClose` 发 requestclose 和 close。父处理器分别把 `topicDetailShow` 或 `runtimeEditor.show` 置 false。
7. `components/cy/sheet/index.wxss` 的 `.sh--stacked .sh__panel` 有 `pointer-events:none`，mask 不在该选择器内。**这只证明 CSS/handler 范围，不能证明下层 mask 会穿透上层 mask。** exit-motion 的叠层与退场时序亦需在真实渲染中观察。

## DevTools／模拟器步骤（未执行）

前提：使用获准的隔离测试副本、测试账号和已有草稿；不上传、不发布、不提交保存。记录源码 SHA、基础库与设备型号。

1. 正常点击进入主题编辑 → 主题详情 → 主题通关奖励。确认父详情仍开着且 stacked=true，第二层奖励编辑器 show=true。
2. 点击选择优惠券，确认第三层打开、`runtimeRewardOpen=true`、奖励编辑器 stacked=true；此时详情因 show 表达式隐藏，不应误认为是关闭事件。
3. 先在动效完全结束后，用真实鼠标／触摸点第三层遮罩一次；记录触摸目标组件实例以及 open、runtimeRewardOpen、runtimeEditor.show、topicDetailShow。预期只关当前层，奖励编辑器保留，关闭后详情仍可恢复。
4. 重新打开，分别在打开／关闭过渡中做真实双击遮罩、连续轻点、从上层面板外边缘开始的拖动。记录是否有下层 onMask/onClose/onBack/onDragStart 被真实事件触发，以及是否导致父状态意外置 false。
5. 关闭奖励编辑器，再打开背包／生存／结构编辑器，检查父详情 stacked 时的 mask/关闭/返回/拖动是否可被真实触摸触达。分别覆盖正常动效与减动效。
6. 通过判据：所有实际输入只影响最上层。失败判据：真实输入可到达 stacked 父实例并关闭/回退父业务状态；必须同时记录触摸实例与状态变化。仅手动调用 handler 或 setData 不能作为 UI 复现。

若无法复现，继续记 unknown／未观察到，不据此改 sheet。探针发事件不是用户丢数据证据。
