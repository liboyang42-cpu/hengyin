# F01/F02 财务与商家聚合入口

基线 `7860bfa0f79ad77d02a4e3051465353ec86cafad`，依据桌面审查《登录与入口诊断》。

财务六个 POST 经 MerchantFinanceQueryService 逐方法核对为 SELECT 与聚合，加入精确查询清单；关系、营销聚合先移除全局过期邀约写副作用（见 repair-f02-backend-0913.md），再允许查询。原下单、退款、核销写保护保留。

本地环境阻断添加 `errorCode=PRODUCTION_WRITE_BLOCKED`，关系页面识别它为请求错误，不再当身份失效或要求登录。营销原有错误处理已正确，补回归，未改其实现。

RED：六个财务请求实际在 request-client 被本地403拒绝；关系页把带环境阻断的403判作 denied；新增聚合请求放行用例原样拒绝。日志分别为 `/tmp/cy-repair-f01-red.log`、`/tmp/cy-repair-f02-red.log`、`/tmp/cy-f02-allow-red.log`。

GREEN：`node --test chengyinhub-xcx/tests/unit/production-write-gate.test.js chengyinhub-xcx/tests/unit/request-client.test.js chengyinhub-xcx/tests/unit/merchant-relation-state-contract.test.js chengyinhub-xcx/tests/unit/merchant-marketing-state-contract.test.js`，73项通过、0失败/跳过；同时断言下单/退款/核销未发送。日志 `/tmp/cy-repair-f01-f02-green.log`。

独立 Standards、Spec 审查在本项均0发现。未证明生产已部署后端只读变更，正式入口HTTP与商家UI仍待实测。发布时后端只读修复必须先于前端查询放行生效。
