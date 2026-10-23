# B 新表预检与迁移顺序（未执行）

以下只读 SQL 供协调者在获准的隔离副本执行；不要默认连接生产。先停止旧支付 writer，记录副本时间及代码 SHA。任何 UNKNOWN 无原渠道/原参数证据都阻塞启用，不能清记录或按 NOT_FOUND 自动回填。

核对真实结构、金额类型及支付身份字段：

```sql
SELECT table_name, column_name, column_type, is_nullable, collation_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('club_membership_invoice','oms_order','coop_invite','cms_registration','wx_pay_order_intent')
  AND column_name IN ('id','status','payment_status','deposit_status','out_trade_no','order_sn',
    'registration_no','deposit_out_trade_no','amount_fen','pay_amount','deposit_amount',
    'wechat_payment_amount','mchid','business_out_trade_no','biz_type','biz_id','attempt_no',
    'appid','trade_type','currency','total_fen','request_hash','closed_confirmed_at','active_biz_id')
ORDER BY table_name, ordinal_position;
```

若新表已存在，执行 `SHOW CREATE TABLE wx_pay_order_intent` 并与候选逐字段、索引、字符集、约束比对；不以 `IF NOT EXISTS` 吞掉漂移。缺表时不执行下面依赖新表的查询。

核对四域所有仍可能发起支付的身份（输出仅汇总，不导出 openid/请求参数）：

```sql
WITH active_business AS (
  SELECT 'CLM' AS biz_type,id,out_trade_no AS business_no,amount_fen AS amount_fen
  FROM club_membership_invoice WHERE status='PAYING'
  UNION ALL
  SELECT 'COMMERCE',id,order_sn,pay_amount*100 FROM oms_order
  WHERE status=0 AND biz_type IN ('premium_template','promotion_slot','custom_event')
  UNION ALL
  SELECT 'DEP',id,deposit_out_trade_no,deposit_amount*100 FROM coop_invite WHERE deposit_status=1
  UNION ALL
  SELECT 'REGISTRATION',id,registration_no,wechat_payment_amount*100
  FROM cms_registration WHERE payment_status=1
)
SELECT biz_type,COUNT(*) AS rows_total,
  SUM(business_no IS NULL OR business_no='') AS missing_identity,
  SUM(amount_fen IS NULL OR amount_fen<=0 OR amount_fen<>TRUNCATE(amount_fen,0)) AS bad_amount,
  SUM(CHAR_LENGTH(business_no)>32) AS longer_than_provider_limit
FROM active_business GROUP BY biz_type;
```

同一业务号重复、同一 biz_type/id 的活跃意图不唯一、业务金额与冻结分不一致必须停止。按上述 active_business CTE 与新表 `(biz_type,biz_id)` 关联，单独统计没有快照的活跃记录；逐笔用原 merchant/原外部号查询官方原单，记录证据来源和状态。已存在原单的通知/查单允许核验真实不可变事实；不允许用当前配置猜原渠道。历史活跃 UNKNOWN 或原微信查询仍 NOT_FOUND 时，不自动预下单、不换号、不释放，须人工补证或等待可靠终态。历史 CLOSED 但无快照的再次发起也先补证，不伪造原请求指纹。

获准执行新表 DDL 后，须在独立 MySQL 8 验证：ascii_bin 单号区分大小写；generated active_biz_id 的 NULL/唯一行为；同业务并发两个渠道仅一个成功；错误金额/参数事务回滚零覆盖；另一连接在 SDK 开始时已看见提交；查证 CLOSED 后旧行/旧请求不变、只新增新别名；UNKNOWN/NOT_FOUND 不释放。对应 `WechatPaymentIntentMySqlIT` 要求 GitHub-hosted 与显式 `B_PAYMENT_INTENT_DDL_APPROVED=1`，缺前提硬失败，不跳过。

部署前顺序是预检/补证 → 批准并验证 DDL → 协调者确认实际库表及原活跃记录处理 → 启用新 writer。回退只能先停止新支付提交并保留读取映射能力；不删表、不删行、不复用外部号，不以退款或清空资金数据处理失败。首次产生 WXP 别名后不能直接换回不认识映射的旧网关。
