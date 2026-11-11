| 流程 | 步骤 | 从 | 到 | 金额（分） | 通道 | 判定 |
|---|---|---|---|---|---|---|
| ON(目标) 多商户合单：补差后整单退款 | pay | PAYER | 1900000101 | 5900 | WECHAT | PAYMENT |
| ON(目标) 多商户合单：补差后整单退款 | pay | PAYER | 1900000888 | 4100 | WECHAT | PAYMENT |
| ON(目标) 多商户合单：补差后整单退款 | share | 1900000101 | 1900000001 | 400 | WECHAT | COMMISSION |
| ON(目标) 多商户合单：补差后整单退款 | return | 1900000001 | 1900000101 | 400 | WECHAT | REVERSAL |
| ON(目标) 多商户合单：补差后整单退款 | refund | 1900000101 | PAYER | 5900 | WECHAT | REFUND |
| ON(目标) 多商户合单：补差后整单退款 | refund | 1900000888 | PAYER | 4100 | WECHAT | REFUND |
| OFF 新通道（全部开关关闭） | — | — | — | 0 | — | 无移动 |
| ON(目标) 单商户 + 第三方份额（转付 RELAY） | pay | PAYER | 1900000101 | 10000 | WECHAT | PAYMENT |
| ON(目标) 单商户 + 第三方份额（转付 RELAY） | share | 1900000101 | 1900000001 | 2500 | WECHAT | COMMISSION |
| ON(目标) 单商户 + 第三方份额（转付 RELAY） | payable:MERCHANT_VERIFY | 1900000001 | member:66 | 1000 | OFF_CHANNEL | REDISTRIBUTION |
| ON(仅 l-payout2，合单关) 单商户 + 第三方份额 | pay | PAYER | 1900000101 | 10000 | WECHAT | PAYMENT |
| ON(仅 l-payout2，合单关) 单商户 + 第三方份额 | share | 1900000101 | 1900000001 | 1500 | WECHAT | COMMISSION |
| ON(仅 l-payout2，合单关) 单商户 + 第三方份额 | share | 1900000101 | 1900000202 | 1000 | WECHAT | SUB_TO_SUB |
| ON 单商户电商收付通（主题门票，卖家唯一收款方） | pay | PAYER | 1900000101 | 10000 | WECHAT | PAYMENT |
| ON 单商户电商收付通（主题门票，卖家唯一收款方） | share | 1900000101 | 1900000001 | 1500 | WECHAT | COMMISSION |
| ON 单商户电商收付通（主题门票，卖家唯一收款方） | return | 1900000001 | 1900000101 | 600 | WECHAT | REVERSAL |
| ON 单商户电商收付通（主题门票，卖家唯一收款方） | refund | 1900000101 | PAYER | 4000 | WECHAT | REFUND |
| ON(目标) 多商户合单：预留够付 | pay | PAYER | 1900000101 | 5900 | WECHAT | PAYMENT |
| ON(目标) 多商户合单：预留够付 | pay | PAYER | 1900000888 | 4100 | WECHAT | PAYMENT |
| ON(目标) 多商户合单：预留够付 | payable:MERCHANT_VERIFY | 1900000888 | member:66 | 1000 | OFF_CHANNEL | REDISTRIBUTION |
| ON(目标) 多商户合单：预留够付 | payable:SURPLUS | 1900000888 | member:55 | 1600 | OFF_CHANNEL | REDISTRIBUTION |
