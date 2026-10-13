# CyConfirmAction：GitHub 一手实现调研（2026-07-21）

## 范围与结论

本调研只考察 Vue 2 + Element UI 后台中「高风险动作、原因必填、明确确认」的交互；证据均指向上游源码或项目源码，不采用第三方教程。结论是：`CyConfirmAction` 首版应是一个小而明确的**确认对话框壳**，统一风险说明、必填原因、关闭保护与确认按钮表现；它不能成为“执行操作”的抽象层。

首批两个真实消费者有共同的交互骨架、但没有可共享的业务语义：

- 现金提现人工回补：`manualNote` 是处理/核实说明，且 “登记另一人复核” 与 “复核通过并退回余额” 由当前状态决定。
- 俱乐部准入驳回：`rejectReason` 是必填的、用于俱乐部档案审核留痕的可执行原因，提交的是 `auditClub({ id, status: 2, rejectReason })`。

因此建议的深模块边界为：

- 组件拥有：`visible`、`title`、风险说明 `message`、确认/取消文字、`danger` 视觉、可选且必填的单个 `reason` 文本域、前端空白校验、关闭保护和 `confirm(reason)` / `cancel` 事件。
- 页面拥有：目标对象、API 调用、权限 `v-hasPermi` / 能力条件、动作可用性、`loading` 状态值和成功后的刷新/提示/关闭；`loading` 只作为组件展示属性传入，所有权仍在页面。
- 不接受：`api`、URL、`permission`、领域 `status`、自动刷新、成功文案或后端错误翻译。否则组件会伪造一套跨提现与准入的状态机，反而掩盖高风险差异。

当只需一句无原因确认时，页面继续直接使用现有 `$modal.confirm` 即可；不能为了复用而把普通删除确认迁入。原因 + 具体风险说明需要同屏出现时用 `el-dialog` 作为实现基础，不把 `MessageBox.prompt` 的单输入限制扩展成通用表单。

## 上游证据与可借鉴点

| 上游实现 | 一手证据与许可 | 可借鉴的事实 | 本仓的落实方式 |
| --- | --- | --- | --- |
| Element UI MessageBox / `$confirm` / `$prompt` | [MessageBox API 文档](https://element.eleme.io/#/zh-CN/component/message-box)、[默认选项与 Promise 实现](https://github.com/ElemeFE/element/blob/dev/packages/message-box/src/main.js)、[关闭与输入校验实现](https://github.com/ElemeFE/element/blob/dev/packages/message-box/src/main.vue)、[MIT 许可证](https://github.com/ElemeFE/element/blob/dev/LICENSE)。 | `$confirm` 在确认时 resolve、取消/关闭时 reject；默认遮罩点击、ESC、关闭按钮均可关闭。`showInput` 只有一个输入框，`inputValidator` 在确认时同步校验；`beforeClose(action, instance, done)` 可在异步动作时延后关闭。 | 无原因、低风险的现存确认继续走 `$modal.confirm`。高风险组件显式禁用遮罩/ESC/X 关闭，并保留明确的“取消”；若页面选择 MessageBox，异步提交需手动设 `confirmButtonLoading`，成功后才 `done()`。 |
| Element UI `el-dialog` | [Dialog API 文档](https://element.eleme.io/#/zh-CN/component/dialog)、[组件关闭实现](https://github.com/ElemeFE/element/blob/dev/packages/dialog/src/component.vue)、[MIT 许可证](https://github.com/ElemeFE/element/blob/dev/LICENSE)。 | `close-on-click-modal`、`close-on-press-escape`、`show-close` 都可控制；`before-close` 只接管 X/遮罩路径，footer 的确认和取消仍须走同一套关闭逻辑。 | `CyConfirmAction` 应以单个受控 `el-dialog` 实现理由编辑；取消、遮罩、ESC、X 都共享“提交中不关闭”的规则，确认由组件 emit 而非直接请求。 |
| RuoYi-Vue 模态封装与实际用户操作 | [modal.js](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-ui/src/plugins/modal.js) 将 `confirm` 维持为 `MessageBox.confirm` 的薄包装；[用户页的状态变更与删除](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-ui/src/views/system/user/index.vue) 都在页面的 `.then()` 内调用对应 API；[重置密码](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-ui/src/views/system/user/index.vue) 使用 `$prompt` 加 `inputValidator`，项目为 [MIT](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/LICENSE)。 | 弹窗负责确认，页面负责具体请求与结果处理；简单单值输入可用 prompt，不需要封成业务服务。 | 保持本仓 `$modal.confirm` 的职责。`CyConfirmAction` 只减少两个现有业务页的确认 UI 重复，不接管 `manualRefundCashwithdraw`、`auditClub` 或后续列表刷新。 |
| vue-element-admin 角色删除 | [角色页源码](https://github.com/PanJiaChen/vue-element-admin/blob/master/src/views/permission/role.vue) 在 `$confirm(...).then()` 后调用 `deleteRole`、更新本页数组，再提示成功；项目为 [MIT](https://github.com/PanJiaChen/vue-element-admin/blob/master/LICENSE)。 | 同一条确认信息不等于同一个领域动作；请求与局部状态更新留在调用页，才容易审查失败分支。 | 组件只 emit 已 trim 的理由；API reject 时页面保留对话框和理由、撤销 loading，并显示后端返回的错误。 |

## 关闭、提交和理由的安全契约

`CyConfirmAction` 的首版需要在用户层避免误触，但不应声称自己能提供最终安全保证：

1. 对危险确认显式设 `:close-on-click-modal="false"`、`:close-on-press-escape="false"`、`:show-close="false"`；始终给出可见的“取消”。提交中禁用取消和确认，不能因误关闭丢失或重复提交。
2. `reason-required` 时先 trim；空白理由不 emit `confirm`，显示字段级错误。组件只发出 trim 后的文本；不应把 `manualNote` 和 `rejectReason` 合为同名后端字段。
3. 页面在 `@confirm` 前传递当前权限、目标 ID 和已确认状态，在 API 进行期间维护 `loading`，成功后关闭并刷新；对会因状态分支而改变资金后果的动作，服务端必须在同一事务内比对确认状态，失配即拒绝。失败后保持弹窗与原理由以便更正或重试。
4. 后端仍必须独立校验权限、目标状态、幂等/双人复核约束和必填理由。前端原因必填只提升操作质量，不能替代对越权、陈旧状态或重复回补的拒绝。

## 首版验收边界

1. 在 `cashwithdraw` 与 `club` 各接一个真实消费者：两者都能展示对象/后果说明、必须填写适当的理由、确认后只向页面发事件。
2. 人工回补页面继续根据 `manualForm.status` 决定“提交另一人复核”或“复核通过并退回余额”；组件不读取该状态。
3. 俱乐部驳回继续由页面传 `rejectReason` 至审核接口；后端异常不关闭对话框。
4. 覆盖组件契约（受控开闭、风险关闭策略、理由 trim/空校验、loading 禁用、emit payload）和两页静态接入契约；不得新增 UI 包、全局单例或泛化表单配置。

## 许可证与使用方式

Element UI、RuoYi-Vue 和 vue-element-admin 都采用 MIT。本调研只提炼公开交互边界和上游 API 语义，不复制其源码或样式；实际实现仍遵循本仓 Vue 2、Element UI 2、现有 token 与权限约束。
