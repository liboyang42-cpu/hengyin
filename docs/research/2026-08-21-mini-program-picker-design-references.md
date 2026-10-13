# 城瘾微信小程序 Picker 设计参考与推荐路线

> 研究日期：2026-08-21
> 目标：回答“网络上有哪些值得城瘾参考的 Picker 设计”，并把视觉参考转成适用于原生微信小程序的组件选型与验收边界。
> 来源原则：只采用微信、Tencent TDesign、Vant Weapp、Apple、Google/Android 的官方文档或官方仓库。TDesign 仓库证据固定到 `d973e4aab21de41cb3f051d7b6393003be7c99ba`，Vant Weapp 固定到 `7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b`。
> 说明：文中的“官方事实”来自链接所指来源；“城瘾建议”是基于这些事实与当前仓库使用场景作出的产品判断，不代表平台强制规范。

## 1. 结论先行

最值得城瘾参考的不是某一套皮肤，而是 **“按数据形态选择交互”**：

1. **短列表（约 2–5 项）**：直接用单选行、Chip 或卡片，不打开滚轮。Apple 明确建议短列表使用 pull-down/直接列表，Picker 对短列表视觉过重。
2. **中等长度、顺序可预测的单列数据**：使用微信原生 `<picker mode="selector">`。例如人数、固定场次、规则类型。
3. **连续时间值**：使用原生 `<picker mode="time/date">`；业务允许时把分钟粒度收敛到 5、10 或 15 分钟，减少无效滚动。
4. **日期浏览或日期区间**：使用“月历 + 明确起止状态”，不要用三列滚轮模拟范围选择。Vant Calendar 与 Material 3 Date Picker 是更好的视觉、状态和文案参考。
5. **省/市/区、主题/章节/节点等层级数据**：使用逐级 Cascader，并显示已选路径；数据较多时增加搜索。TDesign 的 step/tab Cascader 是首选参考。
6. **很长或用户无法预测顺序的集合**：使用可搜索的全宽列表/半屏，不使用滚轮。商家、主题、活动、模板这类名称集合尤其适合此模式。

对城瘾的推荐组合是：

> **原生 `<picker>` 负责简单、标准、低风险的选择；品牌化半屏列表负责长文本和可搜索集合；逐级 Cascader 负责层级数据；月历负责日期范围。**

不建议全局引入 TDesign 或 Vant 来统一 Picker。它们更适合作为交互和状态参考：TDesign 当前 Picker、Cascader、DateTimePicker 的官方 README 均明确标注渲染框架支持为 WebView；城瘾若同时推进 Skyline，直接依赖前必须做目标页双渲染器 PoC。

## 2. 值得直接看的官方设计参考

| 参考 | 值得看的模式 | 适用场景 | 优点 | 局限 / 风险 |
|---|---|---|---|---|
| [微信原生 picker](https://developers.weixin.qq.com/miniprogram/dev/component/picker.html) | 底部系统滚轮；`selector`、`multiSelector`、`time`、`date`、`region` 五种模式 | 简单枚举、时间、日期、行政区 | 宿主一致、代码少、系统交互熟悉；同时支持 WebView 与 Skyline | 弹层视觉几乎不可定制；`header-text` 仅 Android；自定义禁用原因、辅助文案、搜索能力弱 |
| [微信原生 picker-view](https://developers.weixin.qq.com/miniprogram/dev/component/picker-view.html) | 嵌入页面的可定制滚轮 | 自有半屏、上下文内联选择、需要自定义确认流程 | 可自定义蒙层和中间选中框；可多列组合 | 需要自己负责弹层、草稿值、确认/取消、语义、深色模式、跨渲染器差异；滚动事件存在官方已知边界 |
| [TDesign Picker](https://tdesign.tencent.com/miniprogram/components/picker) · [官方源码文档](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/components/picker/README.md) · [开发者工具预览](https://developers.weixin.qq.com/s/T7yFwMmi8O57) | 圆角底部半屏、标题、取消/确认、5 个可见项、选中项有独立背景；单列/多列 | 城瘾通用滚轮的视觉与状态模板 | 层级清楚；区分 `pick`（滚动中）与 `confirm`（提交）；有主题变量、关闭来源和插槽 | 官方 README 标注 WebView；作为依赖前需 Skyline 验证 |
| [TDesign Cascader](https://tdesign.tencent.com/miniprogram/components/cascader) · [官方源码文档](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/components/cascader/README.md) · [开发者工具预览](https://developers.weixin.qq.com/s/WOWeTNmJ8X5B) | step/tab 两种逐级选择；当前路径；次级标题；任意级可选；搜索 | 城市层级、主题/章节/节点、分类树 | 长名称可读；层级关系比并排滚轮清楚；支持搜索、禁用、字段映射 | 比滚轮占用更大空间；异步子级需处理加载、错误与回退；官方 README 标注 WebView |
| [TDesign DateTimePicker](https://tdesign.tencent.com/miniprogram/components/date-time-picker) · [官方源码文档](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/components/date-time-picker/README.md) · [开发者工具预览](https://developers.weixin.qq.com/s/RJkr4Omi8Y6H) | 年/月/日/时/分/秒组合；可显示星期；步长；最小/最大时间 | 营业时间、活动精确时间、核销窗口 | 能按业务只显示必要维度；`steps` 可减少分钟列表 | 多列过多时认知负担高；不适合日期范围浏览；官方 README 标注 WebView |
| [Vant Weapp Picker](https://vant-ui.github.io/vant-weapp/#/picker) · [官方源码文档](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/picker/README.md) | 顶部工具栏、单/多列联动、禁用项、异步 loading、6 个可见项 | 动态多列数据、存在禁用项的选择 | 状态覆盖较全；联动 API 明确；loading 是很好的参考 | 只能参考布局：当前样式固定 `-webkit-text-size-adjust: 100%`，渐隐遮罩写死白色，模板也没有明确 ARIA 语义，不宜作为大字体、深色模式或无障碍基线 |
| [Vant Weapp Cascader](https://vant-ui.github.io/vant-weapp/#/cascader) · [官方源码文档](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/cascader/README.md) | Field 触发 + 底部 Popup + 分级 Tab；异步加载；完整路径回填 | 地区、分类、组织层级 | 打开前字段显示结果；完成后回填路径；适合异步子级 | 需要完整处理空子级、超时、失败和禁用原因 |
| [Vant Weapp Calendar](https://vant-ui.github.io/vant-weapp/#/calendar) · [官方源码文档](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/calendar/README.md) | 单日、多日、日期区间；起点/中段/终点；范围上限；禁用确认文案 | 活动周期、招募期、券有效期、预约日期范围 | 日期关系可视；确认按钮可提示“请选择结束时间”；可限制最大范围 | 日期跨度过大有性能成本；需限制 `min/max`；单一远期日期未必比原生 date 高效 |
| [Apple HIG — Pickers](https://developer.apple.com/design/human-interface-guidelines/pickers) | 短列表不用 Picker；中长且可预测序列用滚轮；超大集合用列表/表格；日期可用紧凑、内联、滚轮 | 组件选型规则，而非照搬 iOS 外观 | 给出了“何时不用 Picker”的清晰标准 | iOS 具体外观、键盘和 popover 行为不能原样移植到微信 |
| [Material 3 — Date Picker](https://m3.material.io/components/date-pickers/overview) · [Android 官方实现说明](https://developer.android.com/develop/ui/compose/components/datepickers) | docked、modal、modal input；单日与范围；日历和文字输入双入口 | 浏览日期、跨月范围、需要精确输入 | 起止关系清楚；可在日历与输入间切换；模态时聚焦任务 | 微信无现成等价组件；文字输入要自行做格式、合法性和键盘处理 |
| [Material 3 — Time Picker](https://m3.material.io/components/time-pickers/) · [Android 官方实现说明](https://developer.android.com/develop/ui/compose/components/time-pickers) | dial 与 input 两种时间选择 | 参考“同一任务提供不同输入方式”的思路 | 手动输入对精确时间和辅助需求友好 | 钟面拨盘不符合微信惯例，不建议城瘾照搬；保留“可选输入方式”原则即可 |

## 3. 城瘾场景的推荐映射

当前仓库同时存在原生 `<picker>` 与多套自定义 `<picker-view>`，覆盖商家入驻营业时间、活动/奖励起止时间、搜索日期、券有效期、模板/主题/俱乐部等选择。这说明优先事项不是再造一套新皮肤，而是先统一场景到组件的映射。

| 城瘾场景 | 推荐组件 | 推荐交互 | 不推荐 |
|---|---|---|---|
| 规则类型、收费方式、人数档位等 2–5 项 | 页面内单选行 / Chip / 卡片 | 所有选项直接可见，点击立即生效；当前项同时有文字或图标标记 | 为 3 个选项打开滚轮 |
| 活动类型、指标、固定模板等约 6–20 项且名称较短 | 原生 `picker mode="selector"` | 触发行显示字段名、当前值/占位、箭头；选择后回填 | 用只显示索引的自定义滚轮；把长描述塞进滚轮项 |
| 主题、商家、俱乐部、活动、章节模板等名称集合 | 品牌化底部半屏“可搜索列表” | 顶部标题和搜索；行内主标题 + 次级信息；单选勾选；选择后立即返回或底部确认 | 长文本滚轮；仅凭截断后的名称选择 |
| 省/市/区、主题/章节/节点、分类树 | Cascader step/tab | 顶部显示已选路径；每级列表；可返回上一级；大量节点时搜索 | 并排 3 列且每列长文本；父级变化后保留失效子级 |
| 营业开始/结束时间 | 原生 time 或共用的品牌滚轮 | 用 5/10/15 分钟粒度；结束时间随开始时间约束；显示“次日”语义 | 60 个分钟值无业务价值；结束早于开始但静默接受 |
| 单个截止日期、券有效期 | 原生 date | 明确最小/最大日期；字段旁说明业务时区/是否含当天 | 无范围限制；把当天默认值误当用户已确认 |
| 活动日期区间、招募期、预约范围 | 月历范围选择 | 起点/终点/区间三态；底部按钮实时显示已选范围或“请选择结束日期”；限制最大跨度 | 两个相互独立的 date picker，导致结束早于开始；三列滚轮看不见日期关系 |
| “最近 7 天 / 本周 / 周末”等搜索筛选 | 快捷项 + 月历兜底 | 常用范围做快捷 Chip；“自定义”再打开日历 | 每次都让用户滚年/月/日 |

### 推荐的城瘾视觉结构

品牌化 Picker 半屏可以参考 TDesign 的结构，但保留城瘾自己的 token：

1. 顶部：左侧“取消”，中间任务标题，右侧“确定”；标题用任务语言，如“选择营业结束时间”，避免泛化的“请选择”。
2. 中部：当前选择的摘要或依赖提示，例如“开始 10:00 · 最晚 22:00”。
3. 主体：滚轮、逐级列表或月历；只允许一种主选择模式。
4. 底部：必要时放校验说明或主按钮；不要同时在顶部和底部放两个同权“确定”。
5. 状态：默认、滚动中、已选、禁用、加载、空、错误均有明确视觉和文案；不能只靠颜色。

选中行可以用浅品牌色背景、较高字重和轻微刻度/触感形成“城瘾感”，但不建议给滚轮加入持续弹簧、视差或装饰性粒子；Picker 是高精度输入，动效应服务定位与确认。

## 4. 确认、取消与状态提交

### 4.1 统一“草稿值”和“已提交值”

自定义 Picker 打开后先复制一份 `draftValue`：

- 滚动/逐级点击只更新 `draftValue` 和预览；
- 点击“确定”才写入表单正式值；
- 点击“取消”、返回或遮罩关闭，恢复原正式值；
- 重新打开时，以当前正式值定位，而不是回到第一项。

这是对 TDesign `pick` / `confirm` 和 Vant `change` / `confirm` 分离的应用。微信原生 `<picker>` 则把 `bindchange` 视为系统确认后的提交，把 `bindcancel` 视为不提交。

### 4.2 什么时候可以不设确认按钮

满足以下条件时，可点击选项后立即提交并关闭：

- 单列、结果可逆；
- 选项含义明确；
- 不会触发支付、发布、删除或服务端写操作；
- 用户可轻松重新打开修改。

多列联动、日期范围、会改变价格/库存/资格的值应保留确认步骤。遮罩点击默认按“取消”处理，不能把用户滚动中的临时值偷偷提交。

## 5. 级联选择的实现与防错

1. **稳定 ID 是真值，名称只负责展示。** 原生 `region` 可返回 `value`、统计区划 `code` 和 `postcode`；业务数据应保存后端定义的稳定标识，不用显示名称做主键。
2. **父级变化后重置所有下游级。** 使用原生 `multiSelector` 时监听 `bindcolumnchange`；更换父列后，更新后续列数据，并把后续索引归位到第一个有效值，禁止提交已失效组合。
3. **异步子级必须显式 loading。** Vant Picker/Area 提供 loading，Vant Cascader展示了异步子级模式；城瘾应在请求期间禁用“确定”，失败时保留上一级并给“重试”。
4. **不可选项必须说明原因。** TDesign Cascader 和 Vant Picker 支持 disabled，但城瘾还应在行内给出原因，如“该场次已满”，不能只变灰。
5. **长路径要可回看。** 确认前展示“主题 / 章节 / 节点”完整路径；超长时可以分行，不应只显示末级名称。
6. **是否允许选中父级要由业务明确。** TDesign `check-strictly` 能让任意层级可选；只有后端合同允许选择非叶子节点时才能启用，不能仅因交互方便而放宽业务约束。

## 6. 微信小程序实现边界

### 6.1 原生 `<picker>`

微信官方文档确认：

- 从底部弹起；支持 `selector`、`multiSelector`、`time`、`date`、`region`；WebView 与 Skyline 均支持。
- `selector/multiSelector` 用索引表达当前值；对象数组可用 `range-key` 指定展示字段。
- `time/date` 支持 `start/end`；`date` 还支持 `year/month/day` 粒度。
- `region` 支持省、市、区、街道层级，并返回名称、统计区划代码和邮编。
- `header-text` 仅 Android 可用；因此不能把关键任务说明只放在该属性中。
- 原生弹层的按钮、行高、选中背景和动画不是稳定的品牌化接口；设计稿不能要求逐像素还原系统弹层。

因此，原生 Picker 的视觉设计重点应放在 **打开前的触发字段、打开后的数据正确性、关闭后的值回填**，而不是企图控制系统弹层皮肤。

### 6.2 自定义 `<picker-view>`

微信官方文档确认：

- 它是嵌入页面的滚动选择器，内部只能放 `picker-view-column`，其它节点不会显示；标题、按钮、说明应放在外层容器。
- `value` 是各列索引数组；越界索引会选择该列最后一项，因此数据更新时必须主动校正索引，不能依赖越界自动兜底。
- WebView 有 `indicator-class`、`mask-style`、`immediate-change`；Skyline 的 `indicator-style` 从基础库 3.3.4 起支持，且当前只支持 `height`、`border`、`background-color`。
- 官方记录了滚动过程中 `bindpickstart`、`bindpickend` 可能多次触发的 Skyline 问题；不能把一次 start/end 当作严格事务边界。
- iOS 滚动触感来自系统设置；不应叠加高频自定义振动。

这意味着自定义 Picker 必须建立 WebView/Skyline 属性矩阵，并在两种渲染器、iOS/Android 真机上分别验证。TDesign 当前官方 README 对 Picker、Cascader、DateTimePicker 均标注 WebView，故现阶段应先借鉴其结构和 token，不直接把“组件能安装”当成“城瘾双渲染器可用”。

## 7. 无障碍、大字体与深色模式

### 7.1 无障碍

- 触发字段必须有可见标签、当前值/占位和可点击线索，读屏名称应包含任务与当前值，例如“营业结束时间，当前 22 点”。微信为自定义组件提供 [`aria-role`、`aria-label` 等能力](https://developers.weixin.qq.com/miniprogram/dev/component/aria-component.html)，但官方也提示 iOS/Android 朗读表现可能不同，必须双端走完整任务。
- 自定义半屏的焦点/朗读顺序应为：标题 → 当前约束 → 选择区域 → 错误/提示 → 取消/确定；关闭后回到原触发字段。
- 选中、禁用、错误不能只靠颜色或中心淡化表达；同时使用文字、字重、图标、边框或状态说明。
- 原生 Picker 的宿主语义是优势，但不能据此直接判定无障碍通过；仍需在微信 iOS 旁白和 Android 读屏完成打开、改变、取消、确认和回读。

### 7.2 大字体

Apple 的 [Larger Text 评价标准](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/larger-text-evaluation-criteria)建议将约 200% 作为严格测试目标，并避免重叠和不可理解的截断。移植到微信时，不应声称实现 iOS Dynamic Type，而应验证：

- 触发字段、标题、取消/确定、错误和当前值在约 200% 文本下仍可理解并操作；
- 顶部横排按钮和标题空间不足时允许标题换行或改为纵向布局；
- 滚轮行高随字体 token 调整，文字不被上下裁切；
- 长名称如果只能严重截断，就改用列表/Cascader，并提供完整文本，而不是继续塞进滚轮；
- 大字体、长中文、英文和小屏机型组合验证。

### 7.3 深色模式

微信 [DarkMode 适配指南](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/darkmode.html)要求通过 `app.json` 的 `darkmode`、`themeLocation/theme.json` 和/或 `prefers-color-scheme` 适配；声明适配后，基础组件会按系统主题展示默认样式。对城瘾：

- 原生 Picker 弹层由微信/系统控制；只需保证触发字段在城瘾浅/深主题下正确，不能承诺原生弹层的品牌色。
- 自定义 Picker 的背景、蒙层、选中行、主/次文字、分隔线、禁用、错误和按钮都要使用语义 token，不写死浅色值。
- TDesign 的 [深色模式方案](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/tdesign-miniprogram/site/docs/dark-mode.md)可参考：基于 Design Token 与 `prefers-color-scheme`；其 Picker 还暴露背景、按钮、选中行和文字相关 CSS Variables。注意 `root-portal`/脱离 `page` 的弹层变量作用域问题。
- Vant Picker 当前 [样式源码](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/picker/index.less)的上下渐隐遮罩仍使用硬编码白色渐变，且固定 `-webkit-text-size-adjust: 100%`；直接照搬会与深色模式、大字体目标冲突。
- 深色模式不能只看截图；应测选中行、禁用项和按钮在浅/深两套背景上的对比度。Apple [Accessibility HIG](https://developer.apple.com/design/human-interface-guidelines/accessibility/)建议普通文本参考 `4.5:1`、大号或粗体文本参考 `3:1`，并要求两种外观都检查。

## 8. 可执行的验收门禁

### 8.1 组件级

1. 空数据、单项、长文本、禁用、异步加载、加载失败均有可理解状态。
2. 打开时定位当前正式值；取消不改变正式值；确定后字段立即回读。
3. 父列变化后，所有子列数据和索引都合法；请求失败不能提交旧组合。
4. 日期/时间严格受最小值、最大值、步长和起止关系约束。
5. 约 200% 文本下无按钮遮挡、文字裁切和无法滚动。
6. 深色模式下选中、禁用、错误不依赖单一颜色且对比足够。
7. 微信 iOS 与 Android 读屏都能完成打开、选择、取消、确认、回读。
8. WebView 与 Skyline 分别真机通过；如果组件只承诺 WebView，Skyline 页面必须有降级或禁止接入。

### 8.2 负控

门禁至少要证明以下错误会红：

- 取消后正式值被改写；
- 父级改变后仍提交旧子级；
- 结束时间早于开始时间；
- 加载失败仍允许确认；
- 选项标签放大后被裁掉关键语义；
- 深色背景下选中行与普通行不可区分；
- 读屏只读出“按钮”或“选择器”，没有任务名和当前值。

## 9. 推荐落地顺序

1. **先统一触发字段**：标签、占位、当前值、箭头、禁用和错误样式一致，不动原生弹层。
2. **收敛时间选择器**：把仓库多套开始/结束 `<picker-view>` 归并到一个合同，先解决草稿/确认、步长、起止约束和读屏。
3. **新增“可搜索单选半屏”**：接管主题、商家、俱乐部、模板等长名称集合。
4. **新增级联选择模式**：先试点一个真正有层级的数据场景，不用它替换普通枚举。
5. **日期范围试点月历**：优先活动或招募期；单个截止日期继续用原生 date。
6. **最后评估第三方依赖**：用同一试点页比较自研薄组件、TDesign 和 Vant 的包体、双渲染器、无障碍、主题和维护成本，再决定是否引入。

## 10. 官方来源索引

### 微信小程序

- [picker](https://developers.weixin.qq.com/miniprogram/dev/component/picker.html)
- [picker-view](https://developers.weixin.qq.com/miniprogram/dev/component/picker-view.html)
- [aria-component](https://developers.weixin.qq.com/miniprogram/dev/component/aria-component.html)
- [DarkMode 适配指南](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/darkmode.html)
- [小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)

### Tencent TDesign MiniProgram

- [Picker 官方文档源码（固定 SHA）](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/components/picker/README.md)
- [Cascader 官方文档源码（固定 SHA）](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/components/cascader/README.md)
- [DateTimePicker 官方文档源码（固定 SHA）](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/components/date-time-picker/README.md)
- [深色模式（固定 SHA）](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/tdesign-miniprogram/site/docs/dark-mode.md)
- [自定义主题（固定 SHA）](https://github.com/Tencent/tdesign-miniprogram/blob/d973e4aab21de41cb3f051d7b6393003be7c99ba/packages/tdesign-miniprogram/site/docs/custom-theme.md)

### Vant Weapp

- [Picker（固定 SHA）](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/picker/README.md)
- [Picker 样式源码（固定 SHA）](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/picker/index.less)
- [Cascader（固定 SHA）](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/cascader/README.md)
- [DatetimePicker（固定 SHA）](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/datetime-picker/README.md)
- [Area（固定 SHA）](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/area/README.md)
- [Calendar（固定 SHA）](https://github.com/youzan/vant-weapp/blob/7a7d43757ed19d3ad5e6bca69059e0b9ea565d0b/packages/calendar/README.md)

### Apple 与 Material / Android

- [Apple HIG — Pickers](https://developer.apple.com/design/human-interface-guidelines/pickers)
- [Apple HIG — Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility/)
- [Apple Larger Text evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/larger-text-evaluation-criteria)
- [Material 3 — Date Pickers](https://m3.material.io/components/date-pickers/overview)
- [Android Developers — Date pickers](https://developer.android.com/develop/ui/compose/components/datepickers)
- [Material 3 — Time Pickers](https://m3.material.io/components/time-pickers/)
- [Android Developers — Time pickers](https://developer.android.com/develop/ui/compose/components/time-pickers)

## 11. 研究边界

- 本文是设计与实现选型研究，不代表已经验证某个第三方组件适合城瘾，也没有修改任何小程序代码。
- 官方文档里的 Apple/Material 具体控件是各自平台实现，城瘾只采用可移植的人因和任务设计原则，不复制其平台专属外观。
- 微信、TDesign 与 Vant 会持续更新。实际立项前应重新核对目标基础库、微信客户端、组件版本和 Skyline 支持声明，并在真机负控后做 Go/No-Go。
