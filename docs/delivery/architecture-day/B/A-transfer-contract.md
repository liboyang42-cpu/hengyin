# B → A 转账合同核对

以下为当前未提交修订，历史 09029140 提交不改写。Map 方法签名不变。

## 提交分类

| 场景 | 返回 | A 的解释 |
| --- | --- | --- |
| 本次 SDK 调用前服务/本地参数明确拒绝 | `ok=false, ambiguous=false` | 仅证明本次未调用；仍须检查原 DEBIT、原 SUBMIT_INTENT、是否曾同号发起。不能据此证明先前未执行 |
| SDK 后任何 ServiceException、5xx、SYSTEM_ERROR、限流、超时、验签/解析异常、空/错号响应 | `ok=false, ambiguous=true` | UNKNOWN，保留原号原参数，不回补 |
| 原批次号匹配且 batchId 非空的受理应答 | `ok=true`，batchId/batchStatus | 仅受理，不表示到账 |
| 缺 ambiguous、null 回应等非上述完整证明 | B 不再生成此确定拒绝形状 | A 仍按 UNKNOWN |

## CLOSED 的依据与禁止推断

使用的实际 API 是旧批量转账 `/v3/transfer/batches`，不是另一产品的单笔转账 bill API。

官方 [通过商家批次单号查询批次单](https://pay.wechatpay.cn/doc/v3/merchant/4012458877) 定义 `transfer_batch.batch_status=CLOSED` 为已关闭，需查关闭原因；`close_reason` 包括超时、场景不可用、付款受限，且可新增。它未明确保证 CLOSED 下所有明细从未到账。

因此 **B 不提供 CLOSED 可安全回补的结论，A 不应接受单纯 CLOSED 作为失败证明，即使此应用通常单笔一批。** 当前 B 对 CLOSED 输出 `result=UNKNOWN, batchStatus=CLOSED`，不输出 `result=FAIL`。

[开发指引](https://pay.wechatpay.cn/doc/v3/merchant/4012065168) 要求批次 FINISHED 才查明细。[通过商家明细单号查询明细单](https://pay.wechatpay.cn/doc/v3/merchant/4012458902) 明确 `detail_status=FAIL` 是该明细转账失败；SUCCESS 是转账成功。

## 查询字段映射

| 官方字段 | B Map 字段 | 条件 |
| --- | --- | --- |
| transfer_batch.batch_status | batchStatus | CLOSED→UNKNOWN；WAIT_PAY/ACCEPTED/PROCESSING→PROCESSING；FINISHED 才查询明细 |
| transfer_batch.mchid / appid / out_batch_no / batch_id | mchid / appid / outBatchNo / batchId | 商户匹配当前服务，原批次号相符；批次限定 total_num=1 且正整数金额 |
| detail_status | detailStatus / result | FAIL→FAIL、SUCCESS→SUCCESS；未知状态→UNKNOWN |
| out_detail_no / detail_id | outDetailNo / detailId | 原明细号匹配、微信明细号非空 |
| transfer_amount | transferAmount | 单位为分；与单笔批次总金额一致，A 仍须与原冻结金额核对 |
| fail_reason | failReasonCode | 供核实；不替代 detail_status |

明细终态还要求 mchid、appid、out_batch_no、batch_id 与批次一致。缺失、串单、金额不一致、未知状态和查询异常均 UNKNOWN。B 返回补充字段不改变 Map 方法签名；A 应核对原意图身份，不应认为 A 的冻结 appid/金额校验已经由 B 自动完成。

测试已新增 CLOSED/PENDING 不查询明细、FINISHED 的显式 SUCCESS/FAIL、错批次/明细身份、金额不符等负控；Java 尚未运行，不声称真微信/真库验收通过。
