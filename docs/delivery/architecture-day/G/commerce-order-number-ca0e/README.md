# ca0e MySQL订单号失败最小修复

Parent `ca0e0d2598727f6e567c802b7a664f9634d945c6`。针对run40122516880/job120957029394原6例中的1failure/3error；未运行任何复测/Java/DB/CI。

来源：OmsOrderCommerceGuardMySqlIT.createCheckout确实调用真实MerchantCommerceService.createOrder；UUID参数仅为request ID，不是fixture伪造order_sn。真实生成器为TPLTM_(6) / PROMOT_(7) / CUSTOM_(7) + 32位UUID hex，即38/39字符；canonical sql/base_schema_20260818.sql oms_order.order_sn为VARCHAR(32)。是生产生成器不满足原schema，不能通过扩夹具字段修绿。

生产仅把完整UUID数值由hex编码改为最多25位小写base36，保留全部128位UUID值，不截断、不改变三个业务前缀；最长7+25=32，仅字母数字。原request幂等先查已存订单、原业务号/支付intent、金额/权限/锁/收款/回执/CAS均不改。schema/DDL、原订单和付款事实均不动。

IT原6个场景和所有业务断言保留，只在真实createCheckout返回后补<=32断言。原MerchantCommerceServiceTest场景/断言保留，追加一个覆盖三业务前缀/合法字符/长度/完整UUID值位宽的测试源码：allCommercePrefixesKeepTheFullUuidValueWithinCanonicalOrderNumberLength。未跑；不宣称原MySQL失败已绿。
