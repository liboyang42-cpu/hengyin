# F07 取消报名反馈

工作树：`repair-cancel-feedback-0913`；分支：`codex/repair-cancel-feedback-0913`。已成功 fetch 后从 `github/master` 的 `7860bfa0f79ad77d02a4e3051465353ec86cafad` 建立。

## 修复边界

`cancelRefund` 与 `cancelByOwner` 原来无条件宣称现金原路退回和积分已返还。现在退款执行服务成功返回后，由专用 `RegistrationCancellationReadService` 通过现有 SELECT 接口读取报名、父订单和退款单，返回实际结果；Controller 只调用读取并包装既有 AjaxResult，在 data 增加只读状态。

- 资格：从报名 `registration_status=3` 判断；缺少回读事实时为 UNCONFIRMED。
- 现金：没有现金支付时明确“无现金退款”；有现金时读取 `refund_application.status/payout_status/refund_amount`。受理、派发、渠道处理、渠道确认到账、失败待人工、人工处理待复核、完成人工复核分别表达，不以报名取消或退款审核通过推断到账。
- 积分：累计订单实际 `point_used`，只把 `points_returned=1` 的积分计为已返还。该标记与用户积分增加由原退款事务同时提交。本单无积分时不宣称返积分。
- 多票：按 `parent_order_no` 读取整单，现金首单与现有 refundWholeOrder 一致，以 `payable_amount>0` 定位；绝不使用从单的假现金金额，也不把每张票分别算成一笔现金退款。纯积分父单无现金首单。
- 取消执行抛错时保持原拒绝，不查询或宣称新退款。取消已提交但后续回读失败时明确提示刷新订单，结果为 UNCONFIRMED，避免把提交成功误报为执行失败。

不修改 TeamGroupServiceImpl、退款执行、退款账本、权限、库存、积分写链或接口签名。小程序由总控负责。不联系生产或渠道，不新增依赖，不提交推送。

## 响应字段

`registrationId`；`scope`（REGISTRATION/PARENT_ORDER）；`cancellationStatus`（CANCELLED/UNCONFIRMED）；`cashRefundStatus`（NOT_NEEDED/DISPATCH_PENDING/DISPATCHING/PROCESSING/SUCCESS/PENDING_MANUAL/MANUAL_HANDLED/MANUAL_VERIFIED/UNCONFIRMED）；`cashRefundAmount`（权威退款金额，无需退款为0，无法确认则null）；`pointsUsed`、`pointsReturned`；`pointsRefundStatus`（NOT_NEEDED/RETURNED/PARTIAL/UNCONFIRMED）。完全回读失败时不猜 scope，数量返回null。

这些是回读时刻的状态，异步渠道可在响应之后继续变化；不能用此次代码测试证明实际渠道到账。

## 验证

依次执行 RED→最小修复→GREEN：

1. 零元无积分：旧固定文案与预期实际事实不符。
2. 现金：仅处理零元后仍将已受理现金当作无退款；覆盖7个 payout 状态。
3. 积分：旧投影忽略已提交的返还标记，纯积分退款被说成未使用积分。
4. 父订单：旧投影读从单 sourceId=11，负控要求现金首单12；另测纯积分父单不得消费从单现金占位值。
5. 取消提交后读取失败：旧处理返回500，负控要求保留已提交的动作回执，明确事实未确认。
6. 退款单读取失败：报名取消和积分返还已读回的事实应保留，只有现金状态未确认。

重复取消保持原拒绝，并验证不调用结果读取；主理人和玩家两个入口分别覆盖。既有订单退款列表“按会员读取最新退款状态”的回归保留。

```bash
mvn -q -pl chengyinhub-admin -am test \
  -Dtest=ApiRegistrationRefundProjectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

RED 日志保留于 `/tmp/f07-zero-red.log`、`/tmp/f07-cash-red.log`、`/tmp/f07-points-red.log`、`/tmp/f07-parent-red.log`、`/tmp/f07-read-red.log`、`/tmp/f07-refund-read-red.log`。最终日志：`/tmp/f07-final-green.log`。

最终 Maven exit 0：9 项通过，0 失败、0 错误、0 跳过；`git diff --check` 通过。日志中的读取异常是两条回归主动注入的预期告警，不是测试失败。

独立 Spec 审查无问题。Standards 提出的薄 Controller 问题已处理：将父单展开、现金首单识别、积分汇总与失败投影移入专用只读 Service，已通过 Spec 的行为及文案保持原样。测试仍通过两个 Controller 入口执行真实只读 Service，仅外部数据源和原退款执行使用测试替身，原9项断言没有放宽。结构调整验证日志：`/tmp/f07-service-green.log`。

边界：未重新实跑资金执行、事务回调或支付渠道；生产部署与小程序入口仍需总控验收。结构调整后的独立复审由总控统筹。
