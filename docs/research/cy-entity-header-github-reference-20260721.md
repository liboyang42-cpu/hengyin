# CyEntityHeader：GitHub 一手实现调研（2026-07-21）

## 范围与结论

本调研只考察 Vue 2 + Element UI 后台中的「详情对象头 / 对象摘要」；来源均为上游 GitHub 的源码、`package.json` 或许可证，**不是**第三方教程。结论是：`CyEntityHeader` 应当把抽屉或详情页最上方的对象身份、当前状态、少量关键事实和页面提供的主操作收拢成一个视觉锚点；它不应变成可编辑表单，也不应取得详情请求、权限或弹窗的控制权。

建议先服务审核和退款详情抽屉，采用一个深而小的边界：

- 必填身份：`title`、可选 `eyebrow`（对象类型 / 编号）与 `subtitle`；标题旁可放现有 `CyStatusTag`。
- 可选事实：`facts` 为已经格式化好的 `{ label, value }` 数组，只展示 2--4 条；调用页决定字段和值，组件不理解审核、退款等领域模型。
- 可选 `actions` 插槽：只负责标题区布局；点击、权限判断、二次确认和请求仍留在调用页。
- 不接受 `form`、`api`、`permissions`、`loading` 或领域状态机等参数。加载、错误、详情抽屉开闭也属于页面。

这样可让详情在第一屏先回答「这是什么、当前处于什么状态、处理时最需要知道什么、下一步能做什么」，而不是复刻一组编辑控件。

## 可直接借鉴的证据

| 上游实现 | 一手证据与许可 | 借鉴点 | 不照搬的部分 |
| --- | --- | --- | --- |
| RuoYi-Vue 用户详情抽屉 | [用户详情源码](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-ui/src/views/system/user/view.vue) 以「基本信息 / 其他信息」分组，在首段放身份、归属和状态；其 [package.json](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-ui/package.json) 使用 Vue 2.6 + Element UI 2.15，仓库为 [MIT](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/LICENSE)。 | 详情应先呈现身份与状态，再放较少的关键事实；宽抽屉可使对象摘要稳定留在首屏。 | 该文件的 `open(userId)` 直接请求 `getUser`，并把岗位、角色字典拼装在组件内。`CyEntityHeader` 不能拥有这些 API、数据字典或抽屉状态。 |
| vue-element-admin Profile / UserCard | [Profile 组合源码](https://github.com/PanJiaChen/vue-element-admin/blob/master/src/views/profile/index.vue) 把用户卡与时间线、账户内容分列；[UserCard 源码](https://github.com/PanJiaChen/vue-element-admin/blob/master/src/views/profile/components/UserCard.vue) 把头像、姓名、角色与少量资料组织在一张独立卡片。项目基于 [Vue + Element UI](https://github.com/PanJiaChen/vue-element-admin/blob/master/README.md)，采用 [MIT](https://github.com/PanJiaChen/vue-element-admin/blob/master/LICENSE)。 | 身份信息应和后续活动/时间线内容分层；标题、状态/角色与少量事实可形成独立、可复用的顶部摘要。 | `UserCard` 的教育、技能进度和 `PanThumb` 是个人主页专用内容，不进入通用业务对象头；也不把整张详情卡片及后续 Tab 一并封装。 |
| Element UI `el-card` | [组件源码](https://github.com/ElemeFE/element/blob/dev/packages/card/src/main.vue) 只提供 `header`、正文插槽、`bodyStyle` 和阴影样式；Element 明确维持 Vue 2，仓库为 [MIT](https://github.com/ElemeFE/element/blob/dev/LICENSE)。 | 采用「容器提供稳定结构、页面提供内容」的插槽边界；`CyEntityHeader` 可复用现有语义 token，而不再创建一套视觉主题。 | 不把 `el-card` 的通用 `header/bodyStyle` 原样透出。那会把布局决策丢回每个页面，重新产生不一致的手写头部。 |
| eladmin-web 个人中心 | [个人中心源码](https://github.com/elunez/eladmin-web/blob/master/src/views/system/user/center.vue) 把头像和关键资料集中在左侧信息卡，编辑表单与操作日志留在后续 Tab；[package.json](https://github.com/elunez/eladmin-web/blob/master/package.json) 固定 Vue 2.7.16、Element UI 2.15.14，项目为 [Apache-2.0](https://github.com/elunez/eladmin-web/blob/master/LICENSE)。 | 「摘要先于编辑」是正确层级：对象头只承载识别与关键上下文，编辑和记录留给详情内容。 | 它的 `crud` mixin、Vuex 用户资料、头像上传、密码/邮箱弹窗都是账户领域耦合；不复制到通用组件。 |

## 补充：抽屉中的对象头

RuoYi-Vue 的 [公告详情](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-ui/src/layout/components/HeaderNotice/DetailView.vue) 已呈现一条更贴近本任务的对象头顺序：小型分类标识、唯一主标题、低对比元信息，再进入正文。可借其视觉顺序为 `eyebrow` → `title`（旁置 `CyStatusTag`）→ 至多 2--3 条 `meta`；不要照搬其中的 `open(payload)`、`visible`、请求和 `v-html` 内容渲染，它们是抽屉容器的职责，且后者不属于对象头。

现有详情抽屉仍应维持 Element UI 的可访问性与关闭行为；[ElDrawer 源码](https://github.com/ElemeFE/element/blob/dev/packages/drawer/src/main.vue) 已提供 `title` 插槽与关闭后焦点回归。因此 `CyEntityHeader` 放在 Drawer body 的顶部，不控制 `visible` 或关闭流程。若对象确有头像，Element UI 的 [ElAvatar](https://github.com/ElemeFE/element/blob/dev/packages/avatar/src/main.vue) 支持 `src`、`alt`、错误回调与 slot 回退；头像须是可选装饰，不能变成审核/退款对象头的必填数据。状态则继续消费项目现有的 `CyStatusTag`，不重包 [ElTag](https://github.com/ElemeFE/element/blob/dev/packages/tag/src/tag.vue)。

## 首版验收边界

首版应在审核和退款详情抽屉各接一个真实消费者，并以以下结果验收：

1. 首屏能识别对象标题、类型/编号、状态和 2--4 个当前处理最关键的事实；不出现输入框、选择器或编辑表单。
2. 页面的详情请求、权限条件、确认弹窗、提交动作和抽屉状态均仍留在页面；组件只接收已准备好的展示数据并提供 `actions` 插槽。
3. 保持现有 `CyStatusTag`、semantic token 与 Element UI 2 的依赖边界；不新增 UI 包，不搬入上游的 CRUD mixin 或全局状态。
4. 以组件契约测试覆盖身份必填项、事实数量限制、空事实与 actions 插槽，并在两个真实页面构建验证。

## 许可证与使用方式

上述 RuoYi-Vue、vue-element-admin、Element UI 为 MIT；eladmin-web 为 Apache-2.0。这里仅提炼布局层级和组件边界，不复制上游源码、样式或领域逻辑；实现仍需遵循本仓库的 token、Vue 2 与 Element UI 既有约束。
