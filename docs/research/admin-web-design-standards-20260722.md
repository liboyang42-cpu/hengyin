# 城瘾后台 Web 设计规范依据（2026-07-22）

## 使用方式与适用边界

- 下列来源是**一手设计依据**，用于把后台 IA/G2 的设计判断变成可复核检查项；它们不是对现有页面的自动验收结论。
- Apple HIG 面向 Apple 平台应用，Material 3 是 Google 的设计系统；二者可作为桌面/Web 交互的跨平台依据，但都不替代 Web 无障碍标准或本项目的业务、权限约束。
- 微信小程序设计和 `wechat-miniprogram/weui-miniprogram` 面向小程序；`Tencent/weui` 面向微信 Web / Mobile Web。三者都不是后台桌面 Web 的布局规范；它们只能约束同一业务对象在小程序、微信 Web 与后台之间的术语、状态和信息表达一致，**不能**直接决定后台的侧栏、hover、桌面尺寸或 CSS。
- Web 可访问性的正式可测基线应另以 [WCAG 2.2](https://www.w3.org/TR/WCAG22/) 成功准则验收；Apple/Material 的建议不能替代它。

## 规范原则 → 后台可审计检查项

### Apple Human Interface Guidelines

| 官方来源 | 规范原则 | 对城瘾后台的可审计检查项 |
| --- | --- | --- |
| [Layout](https://developer.apple.com/design/human-interface-guidelines/layout) | 用层级、分组和留白让主要内容与主要动作优先被理解。 | 每页只有一个任务标题与一个明确主动作；筛选、列表、详情、证据按任务分组；详情字段不以无标题的长表单堆叠。 |
| [Navigation and search](https://developer.apple.com/design/human-interface-guidelines/navigation-and-search) | 导航要让人知道当前位置、可去哪里，并让搜索范围可预期。 | 侧栏仅表达一级域/二级任务；对象详情能回到所属列表；搜索结果显示对象类型、ID、权限过滤与跳转目标，不能把菜单模糊匹配冒充业务搜索。 |
| [Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons) | 控件的标签、可用性和后果应清楚，不以隐含手势或图标猜测关键动作。 | 写操作使用动作+对象文案；危险操作显示对象、后果、原因和确认；图标按钮有可访问名称；禁用、加载、失败不只靠颜色区分。 |
| [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility) | 为不同能力和输入方式提供可感知、可操作的界面。 | 键盘可到达侧栏、筛选、表格操作、抽屉/弹窗；焦点可见且弹窗关闭后返回触发控件；状态同时有文字/图标；辅助技术可读标题、表格列和操作名称。 |

### Material Design 3

| 官方来源 | 规范原则 | 对城瘾后台的可审计检查项 |
| --- | --- | --- |
| [Navigation drawer](https://m3.material.io/components/navigation-drawer/overview) | 导航组件应与信息架构匹配，并用明确选择状态表达当前位置。 | 默认浅色侧栏的当前项同时具备背景、左侧定位标记和文字权重；不允许未定义的三级业务菜单；菜单、Breadcrumb、TagsView 对同一对象的名称一致。 |
| [Interaction states](https://m3.material.io/foundations/interaction/states/overview) | 组件需要覆盖可辨认的交互与状态变化，而非只定义静态外观。 | 复核按钮、表格行、筛选 chip、页签、链接在 default/hover/focus/pressed/selected/disabled/loading/error 下的文字、焦点和可操作性；空态、局部失败、无权限与重试责任明确。 |
| [Accessibility](https://m3.material.io/foundations/interaction/accessibility) | 可访问性包含触控目标、焦点、对比和不只依赖颜色的反馈。 | 为鼠标/触控可点区域设项目 token（Material 的触控指导为 48dp，Web 不应未经换算直接写成结论）；键盘焦点不被裁切；状态标签同时提供文本；以 WCAG 2.2 实测对比度、焦点和目标尺寸。 |

### 微信小程序设计 / WeUI

| 官方来源 | 能证明什么 | 对后台 Web 的正确用法与边界 |
| --- | --- | --- |
| [微信小程序设计](https://developers.weixin.qq.com/miniprogram/design/) | 微信官方的小程序设计入口，定义的是小程序体验和组件语境。 | 同一订单、审核、退款、提现等对象在两端复用状态名称、风险文案和结果口径；不将该资料视为后台 Web 的导航、桌面布局或可访问性规范。 |
| [WeUI for Mini Program](https://github.com/wechat-miniprogram/weui-miniprogram) | 小程序 WeUI 组件库。 | 小程序端可用其组件与交互语义；后台只可借鉴业务术语/状态，不复制其移动端控件尺寸、层级或样式。 |
| [Tencent WeUI](https://github.com/Tencent/weui) | 微信设计团队为微信 Web 设计的 UI 框架，README 明确定位在 mobile web。 | 可校对微信 Web 与小程序中的业务术语、状态和结果口径；仍不能把移动 Web 控件直接等同于后台桌面的侧栏、表格或弹窗规范。 |

**无法证实的点**：上述微信小程序、微信 Web 一手资料都没有提供可直接套用到“后台桌面 Web 管理端”的官方设计规范；因此不能声称任一 WeUI 版本已为本后台的桌面侧栏、表格或弹窗背书。

## 非规范证据

“award-winning apps”没有稳定的颁奖主体、评审版本、评分标准或可执行成功准则，不能作为“符合/不符合”的验收依据。它最多是**视觉标杆**：保留截图、说明借鉴的层级或信息密度，并另以本表检查项和 WCAG 测试验收。

## 本次在线回读与限制

- 2026-07-22 已回读 Apple HIG 首页的当前索引说明；页面主体由 JavaScript 加载，自动工具只能确认其公开的层级、和谐、一致性总则，不能把未加载的二级页逐字当作证据。
- 2026-07-22 已验证 Material 3 navigation drawer 的官方页面地址；该站点同样要求 JavaScript，因此本表只采用其已公开的组件/可访问性原则，不伪造未抓到的精确尺寸或文案。
- 2026-07-22 已回读 `Tencent/weui` README，其明确将 WeUI 描述为微信设计团队为微信 Web 服务设计、覆盖 mobile web 的 UI 框架；已与 `wechat-miniprogram/weui-miniprogram` 分开记录，二者都不能直接等同于本后台的桌面规范。
- 微信小程序设计入口在当前自动阅读环境不能解析；保留直接链接与适用边界，合并或发布前仍应在可交互浏览器中回读。
