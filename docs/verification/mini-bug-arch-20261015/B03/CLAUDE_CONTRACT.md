# B03 给 Claude 的兼容合同

本次没有新增或改变 HTTP 路径、方法、DTO、权限、分页、缓存协议、requestId 或 version 字段。沿用各现有控制器；不需要 UI 新增请求。以下是内部服务行为边界，不是新的公开 API。

| 服务入口 | 既有身份与输入 | 候选行为 |
|---|---|---|
| `IPointsAwardService.award(memberId,eventType,eventId,reason)` | memberId>0、eventType 非空；规则沿用当前配置 | 已发事件返回0；有效奖励返回积分数；会员缺失或余额NULL抛 ServiceException；流水使用当前锁读余额 |
| `awardPublisherSettlement` | 沿用结算业务键及既有专用规则 | 保留结算身份、防重、额度和事务；当前锁读作为流水余额事实 |
| `confirmBatch` / `markBatchPaid` / `markBatchInvoiceIssued` | batchId及既有操作人/付款凭证/票号；归属及后台权限沿用原控制器 | 服务预检发现冻结/非正金额仍抛既有 IllegalStateException；预检后发生竞态，SQL零行返回false；付款继续要求原双人复核 |

不能把 false、异常或网络超时显示为成功。结果未知时读取现有资产流水/批次事实，不能通过更换事件身份盲目补发。非空奖励事件由现有三元唯一键防重；本次没有为 NULL eventId 新增保证。账户行锁仅服务事务内有效，调用方继续走 Spring 服务代理。

明确测试 fixture（非真实账户）：会员1积分100；另一业务扣30后奖励5，期望余额75且奖励流水70→75；两个不同事件各奖5期望余额110、流水100→105→110；同一非空事件只提交一笔。商家批次1初始金额100，追扣120后金额-20，确认/登记付款/开蓝票均不推进；冻结后票据仍为NONE。正常正数未冻结批次可以按既有流程确认、另一操作人登记付款及开票。

这些 fixture 已写入专属 Java 测试但尚未运行。Cloud静态5/5通过不代替真实数据库验证。积分明细、资金中心、新领券或支付产品仍由 Claude 单独设计，本单不新增 UI 或资金政策。
