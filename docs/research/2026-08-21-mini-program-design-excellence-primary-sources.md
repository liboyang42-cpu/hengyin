# 城瘾原生微信小程序：移动产品功能与设计卓越审计基准

> 研究日期：2026-08-21
> 资料范围：Apple Design Awards、Apple Human Interface Guidelines、Apple Accessibility，以及微信小程序官方设计、无障碍和性能文档。
> 来源原则：只使用 Apple 与微信官方一手资料；所有网页均于 2026-08-21 访问。

## 1. 结论先行

“最高标准”不是视觉精致度的同义词。根据 Apple 的规范与获奖维度、微信小程序的目标平台规范，一个卓越的移动产品应同时做到：

1. **有真实、清晰的用户价值**：每个页面和流程服务于明确任务，没有与目标无关的噪声。
2. **功能闭环且让用户掌控**：能发现、理解、执行、确认并恢复；异常不会把用户困住，关键结果不会静默失败。
3. **交互自然、响应明确**：符合目标平台心智模型；等待、状态变化、成功、失败和风险都有与严重程度相称的反馈。
4. **包容且可访问**：常用任务不是“看起来支持”，而是能用读屏、放大文本、非颜色线索等方式真正完成。
5. **可靠、快速且可度量**：性能不能凭主观感受判断，应使用真实设备和正式版数据按平台、机型、网络和入口拆分。
6. **克制而有辨识度**：视觉和动效形成连贯主题，提升理解、情感和品牌记忆，不能遮蔽内容或牺牲可用性。
7. **值得信任**：权限只在功能需要时索取，目的具体透明，只收集产品所需信息。

Apple Design Awards（ADA）给出的是 Apple 认可的**卓越信号**，不是强制合规条款；HIG 与 Accessibility 给出更可执行的审计依据。对城瘾小程序，规则优先级应为：

> 微信目标平台明确规则 > 可移植的人因与产品原则 > Apple 的 iOS 实现细节

因此，不应把 Liquid Glass、SwiftUI、Dynamic Type API 或 `44×44 pt` 等 iOS 规则原样强加给微信小程序；应提取它们背后的层级、一致性、可读性和防误触原则，再用微信原生能力验证。

## 2. “卓越”的官方定义信号

Apple 2026 Design Awards 说明其表彰创新、巧思和技术成就，并将优秀产品分为六类。它们可以作为产品上限的观察维度：

| ADA 维度 | Apple 官方含义 | 转化为城瘾审计问题 | 来源 |
|---|---|---|---|
| Delight and Fun | 令人难忘、投入且满足的体验 | 核心旅程中是否存在贴合品牌、不会妨碍任务的愉悦细节？ | [Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21） |
| Inclusivity | 为不同背景、能力和语言的人提供优秀体验 | 常用任务是否覆盖读屏、可读性、语言和不同能力，而非只照顾默认用户？ | [Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21） |
| Innovation | 以新颖技术运用创造品类领先体验 | 新技术是否确实减少步骤、提高理解或创造以前做不到的价值？ | [Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21） |
| Interaction | 直觉、轻松，且为平台量身打造 | 是否无需反复教学即可操作，并符合微信小程序与触控平台惯例？ | [Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21） |
| Social Impact | 有意义地改善生活或照亮重要议题 | 功能是否带来可说明、可验证的用户或社区收益，而非只增加页面？ | [Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21） |
| Visuals and Graphics | 高质量图像、精心界面、高质量动效和独特一致的主题 | 视觉是否既有辨识度又连贯，并帮助信息理解与操作？ | [Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21） |

### 获奖案例只证明什么

Apple 2026 获奖说明提供了几个可借鉴但非强制的模式：Guitar Wiz 同时覆盖 VoiceOver、Dynamic Type、Increase Contrast 和 Differentiate Without Color；Pine Hearts 在开始使用前让文本、控制、动效和感官选项可发现；Sago Mini Jinja’s Garden 用自然手势和极少阅读要求让儿童直接进入任务；Primary 让极简界面退居内容之后。这些例子共同支持一个判断：**包容、平台贴合、内容优先和情感品质可以同时成立**，但不能把某个获奖产品的具体形式当成通用规范。来源：[Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21）。

## 3. 可移植到微信小程序的明确审计判据

下表把官方原则转为可观察、可复测的判据。“通过”需要产品行为证据，不能只靠设计稿或截图。

| ID | 判据 | 通过条件 | 典型失败 | 官方依据 |
|---|---|---|---|---|
| P01 | 价值与重点 | 每个入口/页面能快速说清“这里解决什么问题”；主任务在层级、位置和文案上占优；无关入口不打断当前流程 | 首页堆满同权入口；活动流程中插入无关推广；用户无法判断下一步 | 微信要求重点突出、流程明确；Apple 要求目的和简洁。[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)；[Apple Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)（均访问 2026-08-21） |
| P02 | 导航与位置感 | 用户始终知道“我在哪、能去哪、如何返回”；次级页有稳定返回路径；同级导航语义和位置一致 | 深链进入后无返回；同一图标在不同页含义不同；弹层套弹层且无法退出 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)；[Apple Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)（访问 2026-08-21） |
| P03 | 端到端功能闭环 | 每条核心旅程至少覆盖：发现入口 → 理解条件 → 执行动作 → 明确结果 → 状态持久化/可复查；所有分支都有终点 | 只做了漂亮入口；提交后不知道是否成功；刷新后状态回退；入口指向占位页 | 这是对“技术成就 + 直觉交互 + 明确反馈 + 异常可退”的综合判据。[Apple Design Awards 2026](https://developer.apple.com/design/awards/)；[Apple Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)；[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| P04 | 状态与结果反馈 | 控件触发后立即显示进行中/选中/禁用等状态；成功、失败和风险反馈强度匹配其重要性；反馈尽量贴近对象并包含下一步 | 点击无响应；错误只闪现 1.5 秒；成功提示出现但真实状态未变化；重复点击导致重复提交 | Apple 要求反馈说明状态、结果、风险和纠错机会；微信区分局部、轻提示、模态和结果页。[Apple Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)；[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| P05 | 等待与渐进呈现 | 尽快显示骨架/占位/已有内容；局部任务优先局部加载；已知时长显示真实进度；长任务可取消；页面不同时出现多个无意义加载动画 | 长时间白屏；全屏 loading 遮住无关功能；假进度；网络卡住后无解释/取消 | [Apple Loading](https://developer.apple.com/design/human-interface-guidelines/loading)；[Apple Progress indicators](https://developer.apple.com/design/human-interface-guidelines/progress-indicators)；[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| P06 | 异常可控与恢复 | 错误说明发生了什么、为何、如何解决；表单错误定位到字段；失败后保留有效输入；高代价动作提供确认或撤销；任何异常都有返回路径 | “操作失败”但无原因；清空整表；删除后不可恢复且无确认；错误页只有空白 | [Apple Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)；[Apple Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)；[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| P07 | 输入经济性与防错 | 优先选择、历史、定位/相机等合适能力，减少键盘输入；标签和格式清楚；尽可能实时原位校验；触控区足够大且有间距 | 能选却要求手输；错误到提交后才出现；小图标密集；只改颜色表示“必填错误” | [Apple Entering data](https://developer.apple.com/design/human-interface-guidelines/entering-data)；[Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)；[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| P08 | 一致性与平台贴合 | 相同控件在不同页面保持相同外观、语义和响应；优先微信标准组件；自定义界面不与官方胶囊菜单冲突；设计在 iOS 与 Android 微信上均成立 | 私造系统控件；页面间按钮样式和反馈漂移；可点击区域压住官方菜单；只在单一机型可用 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)；[Apple HIG overview](https://developer.apple.com/design/human-interface-guidelines)；[Apple Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)（访问 2026-08-21） |
| P09 | 信息层级与视觉克制 | 阅读顺序清楚；对齐、间距、字号、颜色和动效共同服务内容；视觉主题一致；装饰不会抢走核心任务注意力 | 所有卡片同权；标题/正文层级混乱；高饱和颜色到处争抢；动效阻断操作 | [Apple Layout](https://developer.apple.com/design/human-interface-guidelines/layout)；[Apple Color](https://developer.apple.com/design/human-interface-guidelines/color)；[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| P10 | 非颜色单一编码 | 选择、状态、错误、图表除颜色外还用文字、形状、图标、纹理或位置表达；灰度下核心任务仍可完成 | 红/绿圆点是唯一状态；只用品牌色表示可点击；图表系列只能靠颜色区分 | [Apple Color](https://developer.apple.com/design/human-interface-guidelines/color)；[Differentiate Without Color Alone evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/differentiate-without-color-alone-evaluation-criteria)（访问 2026-08-21） |
| P11 | 对比度与可读性 | 严格审计参考：普通文本至少 `4.5:1`，大号/粗体文本可按 `3:1`，关键非文本控件/状态至少 `3:1`；浅色、深色及高对比情境均不丢信息 | 浅灰小字；图片上文字无遮罩；禁用态与可用态无法区分；暗色模式品牌色失真 | 这些数值来自 Apple 当前 Accessibility 评价标准，属于可移植的人因参考，不是微信平台明文门槛。[Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)；[Sufficient Contrast evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/sufficient-contrast-evaluation-criteria)（访问 2026-08-21） |
| P12 | 文本放大与重排 | 用至少约 `200%` 文本放大作为严格负控；核心任务中不出现遮挡、关键截断、不可滚动或控件错位；多语言长文本也验证 | 字体变大但按钮高度不变；标题截断后语义丢失；输入框/弹窗互相覆盖 | `200%` 是 Apple 对 Larger Text 支持的评价目标，移植到小程序时应作为测试目标而非声称支持 iOS Dynamic Type。[Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)；[Larger Text evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/larger-text-evaluation-criteria)（访问 2026-08-21） |
| P13 | 读屏与语义 | 所有重要可见信息、交互控件、状态和值均有正确名称/角色/顺序；自定义控件可操作；装饰内容不制造噪声；读屏能完成全部常用任务 | 图标按钮读成“未命名”；卡片整体重复朗读；视觉顺序与焦点顺序相反；更新状态不播报 | 微信为非原生组件提供 `aria-role`、`aria-label` 等能力，并说明 Android/iOS 朗读可能不同；Apple 强调完整常用任务而非首页抽查。[微信 aria-component](https://developers.weixin.qq.com/miniprogram/dev/component/aria-component.html)；[Apple VoiceOver evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/voiceover-evaluation-criteria)；[Accessibility Nutrition Labels overview](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/overview-of-accessibility-nutrition-labels)（访问 2026-08-21） |
| P14 | 动效安全与目的 | 动效解释状态、空间关系或品牌情绪，但不作为唯一信息；不强制等待装饰动画；对持续、缩放、旋转、视差等高刺激运动提供停止/弱化方案 | 自动轮播无法停；整屏缩放；闪烁/弹跳反复；动画结束前不能操作 | [Apple Motion](https://developer.apple.com/design/human-interface-guidelines/motion)；[Reduced Motion evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria)（访问 2026-08-21） |
| P15 | 权限、隐私与信任 | 只在用户触发相关功能时申请必要权限；申请前后的文案具体说明用途和收益；拒绝后仍有可理解的替代/恢复路径；不为“更好体验”泛化索权 | 首次启动连环索权；不给定位就整页不可用；目的文案含糊；收集与功能无关的数据 | 这是 Apple 的可移植 Responsibility/Privacy 原则；具体授权 UI 必须以微信平台实现为准。[Apple Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)；[Apple Privacy](https://developer.apple.com/design/human-interface-guidelines/privacy/)（访问 2026-08-21） |
| P16 | 性能以真实数据证明 | 采集启动、代码注入、首屏/最大内容绘制、路由等指标；按 Android/iOS/PC、机型、网络和入口拆分；验证慢网与低端机；用户等待时仍有明确反馈 | 只报开发者工具单次结果；把不同平台平均在一起；只优化包体却不看首屏；“感觉很快” | 微信官方明确指出不同平台实现和耗时差异大，只有分平台比较才有意义，并提供 `wx.getPerformance`/We 分析。[微信小程序性能数据](https://developers.weixin.qq.com/miniprogram/dev/framework/performance/perf_data.html)；[微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| P17 | 引导克制且可跳过 | 产品尽量通过直接体验自解释；确需引导时短、可跳过、贴近当前任务，并可稍后重看；先让用户体验价值，再请求评分/购买 | 首次启动多屏功能宣讲；不可跳过；教系统通用手势；每次启动重复出现 | [Apple Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding)（访问 2026-08-21） |
| P18 | 有意义的愉悦与创新 | 独特文案、微交互、图形或设备能力应强化理解、效率、信任或情感；删除后若任务反而更清楚，则它只是装饰债务 | 为动画而动画；新技术增加步骤；品牌表达压过内容；复制竞品外观无自身逻辑 | [Apple Design Awards 2026](https://developer.apple.com/design/awards/)；[Apple Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)（访问 2026-08-21） |

## 4. 微信小程序目标平台的明确规则

这些是审计城瘾原生小程序时应直接采用的微信平台判据，而不是从 iOS 类比得出。

| 微信规则 | 城瘾审计落点 | 官方来源 |
|---|---|---|
| 每页重点突出，流程不得被目标外内容打断 | 逐页标出唯一主要目标；记录与目标无关的入口、广告、弹窗和中断 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 页面要说明当前位置、可去方向和返回方式 | 覆盖首页、次级页、深链、分享卡片进入和异常页；次级页左上角应提供返回上级操作 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 官方胶囊菜单区域必须预留，附近交互不得冲突 | 在不同设备、状态栏高度和横竖屏情境检查遮挡、误触及安全区 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 页面内 Tab 为 `2–5` 个，建议不超过 `4` 个；单页不应出现一组以上 Tab | 对所有自定义顶/底部 Tab 数量、层级和用途做静态清点；Tab 只用于同级导航 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 加载反馈尽量简洁；局部加载优先；长加载提供取消和进度；单页不要同时出现超过一个加载动画 | 对弱网、超时和重试做动态验证；检查按钮重复提交及全屏遮挡 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 一闪而过的图标型成功提示不适用于错误；重要结果应用模态或结果页并给下一步 | 检查错误是否能被看见、理解、复现和恢复，不能只看是否调用了 toast | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 表单错误应说明原因并标记错误字段 | 提交空值、非法值、冲突值、网络失败后验证定位、文案、焦点和数据保留 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 触控目标物理尺寸约 `7–9 mm`，优先标准组件或仿照其尺寸 | 用真机量测热区而非只量图标；检查相邻危险操作间距 | [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 非原生组件可用 ARIA 属性补足角色和名称；Android/iOS 读屏结果可能不同 | 至少在微信 iOS 旁白和 Android 读屏各走一次全部核心任务，不能单端推断 | [微信 aria-component](https://developers.weixin.qq.com/miniprogram/dev/component/aria-component.html)（访问 2026-08-21） |
| 性能比较必须区分平台、下载比例、入口页面等条件 | 报告附分层条件、版本、设备、网络、样本量和分位数；不只给总体均值 | [微信小程序性能数据](https://developers.weixin.qq.com/miniprogram/dev/framework/performance/perf_data.html)（访问 2026-08-21） |

## 5. 可移植原则与 iOS 专属规则的边界

| 主题 | 可移植到城瘾的原则 | iOS / Apple 专属，不应原样搬用 | 城瘾采用方式 |
|---|---|---|---|
| 触控 | 控件足够大、间距足够，降低误触 | iOS 默认 `44×44 pt`，Apple 表中 minimum `28×28 pt` | 以微信 `7–9 mm` 与微信标准组件热区为主；`44 pt` 只能作 iOS 侧辅助对照。[Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)；[微信设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 字体 | 内容可放大、重排、不遮挡，且对比充分 | Dynamic Type、iOS 默认 `17 pt`/最低参考 `11 pt` 是 Apple 平台机制和数值 | 对小程序做约 `200%` 放大、长中文/英文和系统字号场景测试；不宣称实现 Dynamic Type。[Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)（访问 2026-08-21） |
| 辅助技术 | 重要信息和控件都有语义，全部常用任务可完成 | VoiceOver、Voice Control、Switch Control、Accessibility Inspector 是 Apple 工具链 | 使用微信 `aria-*` 能力，并在微信 iOS/Android 的实际读屏环境双端验证。[微信 aria-component](https://developers.weixin.qq.com/miniprogram/dev/component/aria-component.html)；[Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)（访问 2026-08-21） |
| 动效 | 动效有目的，不是唯一信息，可停止或降级 | iOS `Reduce Motion` 系统设置及相应 API | 小程序提供产品内弱化/停止策略并验证；若能读取宿主偏好再适配，但不假定存在等价 API。[Reduced Motion criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria)（访问 2026-08-21） |
| 布局 | 尊重设备遮挡、方向和可达区域 | iOS safe areas、Dynamic Island、状态栏、左右横屏细则 | 以微信胶囊菜单、系统状态栏、不同机型与横竖屏实测为目标平台真源。[Apple Layout](https://developer.apple.com/design/human-interface-guidelines/layout)；[微信设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 导航/手势 | 返回、退出、撤销和层级必须可发现且一致 | iOS 标准 Back/Close、边缘返回、undo/redo 手势、iOS tab bar 形态 | 不重造微信/系统手势；按微信页面栈、官方导航和 Android/iOS 返回行为验证。[Apple Designing for iOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-ios)；[微信设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |
| 权限 | 最小、适时、具体透明，有拒绝后的路径 | iOS system permission alert、purpose string、pre-alert 细则 | 采用微信授权 API 和平台隐私规范；Apple 文案原则用于审计理由是否具体。[Apple Privacy](https://developer.apple.com/design/human-interface-guidelines/privacy/)（访问 2026-08-21） |
| 视觉系统 | 清晰层级、统一行为、主题连贯 | Liquid Glass、SF Symbols、Apple system materials | 不仿制 Apple 外观；使用微信原生组件/WeUI 思路与城瘾品牌语言。[Apple HIG overview](https://developer.apple.com/design/human-interface-guidelines)；[微信设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21） |

## 6. 建议用于城瘾审计的门禁与评分

### 6.1 P0 硬门禁：任一失败，不得称为“卓越”

1. **核心功能可达**：每个核心角色都能完成全部核心旅程，且结果真实持久、可复查。
2. **无死路**：深链、空态、拒绝授权、弱网、超时、服务端错误、重复提交等分支都有解释和退路。
3. **破坏性动作安全**：高代价或不可逆操作有确认/撤销，不因误触静默损失数据。
4. **读屏可完成**：微信 iOS 与 Android 读屏都能完成全部常用任务；重要控件有正确名称、角色、状态和顺序。
5. **信息不依赖颜色**：灰度负控下仍能识别状态、选择、错误和图表含义。
6. **关键内容可读**：普通文本对比度参考不低于 `4.5:1`；约 `200%` 放大后核心任务无阻断。
7. **权限可信**：无启动即连环索权、无功能外数据索取、无拒绝后无路可走。
8. **等待与错误不静默**：所有异步核心动作都有进行中和终态，错误能定位且可恢复。

### 6.2 100 分质量评分

硬门禁全过后再评分，避免“漂亮界面”抵消功能或无障碍缺陷。

| 维度 | 分值 | 评分要点 |
|---|---:|---|
| 用户价值与任务聚焦 | 15 | 主任务清楚、内容有用、无无关打断、社会/社区价值可说明 |
| 功能闭环与信息架构 | 15 | 入口可发现、流程完整、导航稳定、状态持久、深链成立 |
| 反馈、异常与用户掌控 | 15 | 即时反馈、幂等/防重复、错误可诊断、可返回/撤销、数据不丢 |
| 包容与无障碍 | 20 | 双端读屏、非颜色编码、对比度、文本放大、动效安全、多语言 |
| 微信平台贴合与一致性 | 10 | 官方组件、胶囊避让、Tab/返回惯例、跨页面一致、双端行为 |
| 性能与技术完成度 | 10 | 真机与正式数据、分平台分层、弱网/低端机、首屏与交互响应 |
| 视觉、内容与愉悦 | 10 | 层级、排版、图形、动效、品牌语调，且不损害任务和可读性 |
| 有意义的创新 | 5 | 新技术/新机制带来可验证的效率、理解、信任或情感收益 |

建议分档：

- `90–100`：卓越候选；仍需用户实测和生产性能数据复核。
- `80–89`：高质量，但尚有明显提升空间。
- `70–79`：基本可用，存在阻碍一致性或完成效率的问题。
- `<70`：需要重新设计关键流程或基础质量。
- 任一 P0 失败：无论总分多少，结论均为 **No-Go / 不得宣称卓越**。

## 7. 审计证据要求

每条结论至少绑定一种可复测证据：

| 证据层 | 最低要求 |
|---|---|
| 流程证据 | 按角色和入口记录核心任务逐步结果，覆盖成功、拒绝、空态、错误、重试和返回 |
| 真机证据 | 至少微信 iOS 与 Android 各一台；触控热区、胶囊遮挡、键盘、返回、横竖屏按实际支持范围验证 |
| 无障碍证据 | 双端读屏完整走通 common tasks；灰度负控；约 `200%` 文本放大；减少/停止动效场景 |
| 性能证据 | 版本、平台、设备、网络、入口、样本量、分位数；启动、首屏、路由和核心交互数据 |
| 视觉证据 | 关键状态截图/录屏，包括默认、加载、空、成功、失败、禁用、超长文案和深浅背景 |
| 回读证据 | 提交/发布/收藏/报名等写操作后从目标状态重新读取，证明真实结果而非只证明按钮回调成功 |

Apple 官方建议自动 Accessibility audit 覆盖每个 workflow 的每个 screen，同时使用辅助技术人工完成全部常用任务；这支持“自动检查 + 真机任务验证”双层证据，而不是用单次扫描替代真实可用性。[Performing accessibility audits for your app](https://developer.apple.com/documentation/accessibility/performing-accessibility-audits-for-your-app)；[Accessibility Nutrition Labels overview](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/overview-of-accessibility-nutrition-labels)（访问 2026-08-21）。

## 8. 官方来源索引

### Apple

- [Apple Design Awards — 2026 winners and finalists](https://developer.apple.com/design/awards/)（访问 2026-08-21）
- [Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines)（访问 2026-08-21）
- [Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)（访问 2026-08-21）
- [Designing for iOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-ios)（访问 2026-08-21）
- [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)（访问 2026-08-21）
- [Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)（访问 2026-08-21）
- [Loading](https://developer.apple.com/design/human-interface-guidelines/loading)（访问 2026-08-21）
- [Progress indicators](https://developer.apple.com/design/human-interface-guidelines/progress-indicators)（访问 2026-08-21）
- [Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding)（访问 2026-08-21）
- [Privacy](https://developer.apple.com/design/human-interface-guidelines/privacy/)（访问 2026-08-21）
- [Layout](https://developer.apple.com/design/human-interface-guidelines/layout)（访问 2026-08-21）
- [Color](https://developer.apple.com/design/human-interface-guidelines/color)（访问 2026-08-21）
- [Motion](https://developer.apple.com/design/human-interface-guidelines/motion)（访问 2026-08-21）
- [Entering data](https://developer.apple.com/design/human-interface-guidelines/entering-data)（访问 2026-08-21）
- [Performing accessibility audits for your app](https://developer.apple.com/documentation/accessibility/performing-accessibility-audits-for-your-app)（访问 2026-08-21）
- [Overview of Accessibility Nutrition Labels](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/overview-of-accessibility-nutrition-labels)（访问 2026-08-21）
- [VoiceOver evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/voiceover-evaluation-criteria)（访问 2026-08-21）
- [Larger Text evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/larger-text-evaluation-criteria)（访问 2026-08-21）
- [Sufficient Contrast evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/sufficient-contrast-evaluation-criteria)（访问 2026-08-21）
- [Differentiate Without Color Alone evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/differentiate-without-color-alone-evaluation-criteria)（访问 2026-08-21）
- [Reduced Motion evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria)（访问 2026-08-21）

### 微信小程序

- [微信小程序设计指南](https://developers.weixin.qq.com/miniprogram/design/index.html)（访问 2026-08-21）
- [aria-component：无障碍访问](https://developers.weixin.qq.com/miniprogram/dev/component/aria-component.html)（访问 2026-08-21）
- [性能数据](https://developers.weixin.qq.com/miniprogram/dev/framework/performance/perf_data.html)（访问 2026-08-21）

## 9. 研究边界与更新提示

- 本文没有把 Apple 获奖案例当成规范，也没有把 iOS API 能力当成微信必备实现。
- Apple HIG 与微信开放文档均会更新；开展正式审计前应重新打开上述直链核对版本变化。
- 本文建立的是审计标准，不包含对城瘾当前代码、页面或线上版本的实际审计结论。
