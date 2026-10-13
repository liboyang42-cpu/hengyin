-- coop_invite 扩列(拍板第十节·保证金):商家↔俱乐部(type1)收 ¥50 履约保证金,微信支付缴纳,完成退/违约扣。
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)。
-- 影响:对存量表加可空列,全部 NULL 默认,对现有数据/查询无副作用。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 CoopInvite 实体/Mapper 引用新列会 SQL 报错。
-- ⚠️ 仅 type1 俱乐部邀约用:商家↔商家(type2)弱绑定不收(靠信用分+互评)。

ALTER TABLE coop_invite
    ADD COLUMN deposit_amount       DECIMAL(10,2) NULL COMMENT '保证金额(仅type1俱乐部,固定¥50)'                    AFTER fixed_fee,
    ADD COLUMN deposit_status       TINYINT(1)    NULL DEFAULT 0 COMMENT '保证金状态 0无需/1待缴/2已缴冻结/3已扣/4已退' AFTER deposit_amount,
    ADD COLUMN deposit_out_trade_no VARCHAR(64)   NULL COMMENT '保证金微信支付商户订单号(前缀DEP)'                    AFTER deposit_status,
    ADD COLUMN deposit_refund_no    VARCHAR(64)   NULL COMMENT '保证金退款单号(完成原路退)'                          AFTER deposit_out_trade_no;
