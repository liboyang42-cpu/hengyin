# F12 订单深链与切单隔离

依据《续跑核验_第二轮》F12。组件此前只在 attached 读取一次 orderId，页面 onLoad 后属性才到达时停在“参数缺失”。现监听属性变化并校验正整数ID，首次挂载和后续变化走同一加载入口。同值通知不重复请求；切单清除旧内容，详情和完局请求按切单代际隔离，包括 A→B→A。

独立 Spec 复核发现旧退款确认和支付回执仍可作用于新单，已补充动作代际校验，并在切单时销毁旧 checkout workflow、创建新 workflow。已发的业务操作不能因此当作撤销，只阻止过期确认发起新操作和旧界面回执影响新单。

## 证据

- 初始3项行为RED：晚到参数不请求、切单旧响应覆盖、非法参数仍留旧单。`/tmp/cy-f12-red.log`。
- 补充RED：A→B→A旧响应复活；旧退款确认仍提交A、旧支付参数仍打开A支付。`/tmp/cy-f12-aba-red.log`、`/tmp/cy-f12-actions-red.log`。
- GREEN：`node --test chengyinhub-xcx/tests/unit/orderinfo-load-state.test.js chengyinhub-xcx/tests/unit/scene-member-order-reliability-contract.test.js`，45项通过，0失败/跳过；`/tmp/cy-f12-green.log`。
- 原生组件脚本 `chengyinhub-xcx/scripts/_verify_order_deeplink.js` 使用固定版本兼容 harness、专属端口9763与受控接口响应。晚到76参数→ready，改77→拒权且info清空，均实际读回通过；截图 `/tmp/chengyin-f12-ui/late-id-ready.png`、`changed-id-denied.png` 已人工查看。初次开发者工具逻辑层启动失败和脚本选择器错误未计为产品失败；修正选择器并待逻辑层恢复后实跑通过。

动作隔离补充后，独立 Standards 与 Spec 复审均0发现；原生组件脚本再次通过，日志 `/tmp/cy-f12-ui-final.log`。`npm run lint:ui` exit0、UI-GATE-0 PASS。脚本按项目验证skill要求显式加入版本控制（该命名默认被忽略），截图和临时日志不提交。

受控响应只证明组件行为，不证明后端订单归属鉴权、真实支付渠道回跳或生产功能。
