# R9-11 详情取消后的订单列表同步

基线：`4a6181f98`。第九轮报告中的订单列表与详情子层同处一个页面：关闭子层只清理 scene 栈，不触发列表的 page show。取消成功原来只重新读取详情，因此返回后仍显示旧资格。

详情当前订单取消成功后发出 `orderchanged`。订单宿主仅接受当前订单详情的匹配 ID，调用既有列表重试入口，从第一页重新读取 `/api/registration/list`。返回箭头与原生 sheet 关闭共用此结果；仅查看、取消失败及其他弹层不会刷新。保留原取消反馈、退款规则和详情回读。没有本地覆盖资格或状态标签。

列表读请求仍按递增请求序号隔离，同时绑定发起账号；账号变化后的读取清理前一账号快照。详情取消回执也绑定原账号及 F12 订单代际，旧回执不能触发新账号或新订单的刷新。成功写后的列表回读失败保留同账号最后确认快照并显示既有错误与重试入口；重试成功才替换快照。

## 修改范围

- `scene-member-order-detail/index.js`：成功事件及取消回执账号约束。
- `scene-member-order-history/index.js`：列表读取账号约束，保留既有请求序号保护。
- `subpackageMember/order/order.js`、`order.wxml`：事件绑定、当前详情 ID 校验与权威回读。
- `tests/unit/order-detail-parent-refresh.test.js`：实际 Page / Component handler 与正式 WXML 绑定驱动的受控回归。

## RED → GREEN

证据目录：`/tmp/r9-11-order-refresh-probe/`，所有响应在 Node VM 中受控，无真实网络。

- `tdd-red.log`：详情返回与 sheet 返回均 RED，实际列表请求只有 1 次，预期 2 次；`tdd-green.log`：最小事件闭环后 2/2 GREEN。
- `account-red.log`：账号变化后旧取消回执仍刷新、旧在途列表阻止新账号读取，两项 RED；`account-green.log`：补账号约束后 4/4 GREEN。
- `regression-green.log`：10/10 GREEN，0 跳过，另含业务失败、网络失败、仅查看、无关层、重复关闭、写请求 single-flight、旧读响应、回读失败与重试、详情复用。
- `related-green.log`：新增测试与既有订单可靠性、F12 深链、订单单一来源、sheet 及订单状态测试共 81/81 GREEN，0 跳过。
- `ui-gate.log`：UI-GATE-0 PASS；`scope-gate.log`：496 个生产 JS 的作用域检查 PASS。未改门禁基线。`git diff --check` 通过。

## 票夹及验证边界

本次链路从订单详情经 `_leave` 关闭子层，再 `navigateTo` 新建票夹页；票夹 `onLoad` 会重新发起活动票与路线票读取，没有复用本次订单宿主下的列表缓存，因此未修改票夹。此结论只覆盖该入口，不声称其他票夹历史返回路径已验证。

Node 验证使用真实 handler、事件绑定和状态转换，服务端响应受控；不证明后端退款执行、真实网络或原生组件生命周期。原生 UI 尚未验证；未接生产、未操作数据库、未提交或发布。覆盖的是取消成功后关闭；关闭销毁后才返回的旧写回执继续遵守 F12 的失效约束。

## 独立 Spec 退回后的资源清理修正

独立审查指出：账号失效检查也拦住了取消请求 complete，导致 loading 与 cancelling 未释放。`cleanup-red.log` 独立复现切号后的成功、失败，以及详情销毁，共 3 项 RED（loadingDepth 实际 1，预期 0）。

取消请求现在持有独立的幂等清理函数。complete、切单及销毁只释放该请求自己的 loading 和锁，业务 success/fail 仍执行账号与订单代际校验。旧清理函数不能清除后续请求的锁或 loading；没有恢复旧回执刷新。

最终新增回归 15 项；`cleanup-related-green.log` 相关组共 86/86 GREEN、0 跳过，包含不同代际新请求与同代际重试期间旧 complete 的正控。`cleanup-ui-gate.log`、`cleanup-scope-gate.log` 均 PASS，门禁未修改。此修正仍未经过原生 UI 验证。

## 确认弹层清理补充

二次独立 Spec 发现同源的确认弹层锁问题：弹层打开后切号，旧 success/complete 都被账号校验挡住，下一次取消静默返回。`prompt-red.log` 含 5 项 RED（切号 confirm/fail/complete、同订单旧回调干扰新弹层、销毁不释放提示锁），另有切单新弹层正控。

确认阶段与写请求阶段现在各自持有幂等清理函数。每个函数只能清理自己仍拥有的资源；success 必须先取得本弹层清理权，再检查账号与代际，才能发写请求。fail/complete 不因账号变化跳过本弹层清理。切单与销毁会释放两阶段各自资源，晚到旧回调不能清新弹层或新请求，也不能重放旧确认。退款资格、提示和单请求约束未变。

第三版新增回归合计 21 项；`prompt-related-green.log` 相关组 92/92 GREEN、0 跳过。`prompt-ui-gate.log`、`prompt-scope-gate.log`、diff-check 通过。仍是本地候选，未占用开发者工具或操作生产。

## 第三版总控集成与原生补验

第三版双轴独立审查均0项，各自21/21通过；源提交 fb028502b 已集成本地 root c08953103。root 订单相关测试130/130通过（`/tmp/cy-r911-integrated.log`）。前述“尚未原生验证”为各候选阶段记录，本节补充最终集成后的证据。

`/tmp/r9-11-native-green/evidence.md` 与 receipt.json：在现有开发者工具9763通过真实订单卡→详情→确认弹窗→关闭sheet进行三条受控路径。成功写后列表读取从1到2，延迟权威响应前保留未开始，回包后才显示已取消；取消失败与仅查看保持1次读取、原状态及操作。实际确认弹窗切合成账号后恢复身份，再次确认可用，旧提示锁没有残留。未替换产品handler。总控已查看 view/success/failure 三张截图。

全部10次请求均被拦截，包含8读与2次取消替身；未发真实取消或退款。替身已恢复且disconnect完成。合成订单未填金额、封面与地点，不拿视觉空字段当成产品变更，也不将受理提示证明为资金到账。本次证明开发者工具受控交互，不证明真人真机、真实身份权限、后台退款或生产发布。
