-- 商家结算单(报告§5.5 · B4-1):合作商家按核销分润 + T+7 打款
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:新建表,对存量无影响。
-- ⚠️ 部署顺序:先于新 jar 落库(CoopSettlement Mapper 引用 coop_settlement)。
-- 说明:仅"有 coop_order 的主题"走本结算(存量主题无 coop_order,仍走旧 distributeMerchantCommissions,零影响)。
--       分成型 amount = share_rate% × 核销销售额;固定型 amount = ¥fixed_fee × 核销人头数;引流型 0。
--       结算时挂 status=0(待打款,payable_time=活动结束+7天),到期由 CoopSettlementPayoutTask 打款置1;
--       退款/取消:未打款单作废置2。

CREATE TABLE IF NOT EXISTS coop_settlement (
    id             BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id       BIGINT(20)    NOT NULL COMMENT '主题',
    coop_order_id  BIGINT(20)    NOT NULL COMMENT '来源合作单',
    merchant_id    BIGINT(20)    NOT NULL COMMENT '收款商家memberId(=coop_order.to_id)',
    share_mode     TINYINT(1)    NULL COMMENT '0引流 1分成 2固定(条款快照)',
    share_rate     DECIMAL(5,2)  NULL COMMENT '分成比例',
    fixed_fee      DECIMAL(10,2) NULL COMMENT '固定型每核销人头费',
    verified_heads INT           NOT NULL DEFAULT 0 COMMENT '核销人头数',
    verified_sales DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '核销销售额(分成型用)',
    amount         DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '商家应收',
    verified_reg_ids TEXT        NULL COMMENT 'T9结算审计快照:被本次结算计入的已核销报名id(CSV,供对账/纠纷回溯)',
    status         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0待打款 1已打款 2作废',
    payable_time   DATETIME      NULL COMMENT '可打款时间(=活动结束+7天)',
    settle_time    DATETIME      NULL COMMENT '结算(挂单)时间',
    payout_time    DATETIME      NULL COMMENT '实际打款时间',
    create_time    DATETIME      NULL,
    del_flag       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_settle_topic (topic_id),
    KEY idx_settle_merchant (merchant_id),
    KEY idx_settle_payout (status, payable_time),
    UNIQUE KEY uk_settle_order (coop_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家结算单(核销分润·T+7打款)';
