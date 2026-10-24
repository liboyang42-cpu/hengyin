> 历史提案已于 v8 获得 ApiRegistrationController.payCallback 方法所有权后应用；当前代码与新增五项 HTTP 测试为准，以下保留原提案。

# 共享入口 HTTP 应答提案（未应用）

结论：ApiRegistrationController.payCallback 当前返回 String，因此 JSON FAIL 仍 HTTP200，会让微信视为接收成功。B 未改共享 controller；本提案交协调者单一合并。

依据：https://pay.wechatpay.cn/doc/v3/merchant/4012791902 及 APP 的 https://pay.wechatpay.cn/doc/v3/merchant/4013070368 。成功应答200/204，失败4xx/5xx。先验签但尚未安全持久接收/业务确认时不可仅HTTP200拒绝正文。本轮未做全inbox异步迁移，仍须等领域确认成功后ACK。

具体补丁只改 payCallback 方法，并添加专属 MockMvc HTTP测试：SUCCESS→204空体；FAIL/null/畸形JSON/缺code/异常→固定500 JSON。网关String接口签名不改，不向外回显异常、请求体或头。不改权限、业务金额/CAS、全局异常处理器或退款入口。controller 自行捕获异常，避免既有全局Exception handler包装成200。

补丁：shared-callback-http-proposal.patch；git apply --check 已通过；未应用、未编译、未运行测试，不包含在 B 活跃代码补丁中。共享入口未修复前，不能宣称FAIL已经可靠触发微信重试或四域回调完整验收。

SHA256: 06611bd6e9141f05d13182e6e6afd1326501c09cc303ecaa02c6c5668a3924d8
