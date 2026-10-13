-- D-02 提现资金状态机:member_cash_withdraw 增列。
-- 目的:修复「微信已受理即标记成功」资损——转账后置处理中,定时查询确认到账才置成功;
--       失败补偿留痕(退款失败进待人工);支持定时查询与对账。
-- 日期:2026-07-11  影响表:member_cash_withdraw
-- 幂等:用存储过程按 information_schema 判断后再加列,可重复执行。
-- status 语义:0=处理中(已提交微信待确认) 1=成功(查询确认到账) 2=失败已退款 3=失败待人工

DROP PROCEDURE IF EXISTS cy_fund_add_col;
DELIMITER $$
CREATE PROCEDURE cy_fund_add_col(IN col VARCHAR(64), IN ddl VARCHAR(512))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'member_cash_withdraw'
                     AND column_name = col) THEN
        SET @s = CONCAT('ALTER TABLE member_cash_withdraw ADD COLUMN ', ddl);
        PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END$$
DELIMITER ;

CALL cy_fund_add_col('wechat_batch_status', "wechat_batch_status VARCHAR(32) NULL COMMENT '微信批次状态 ACCEPTED/PROCESSING/FINISHED/CLOSED'");
CALL cy_fund_add_col('submit_time',        "submit_time DATETIME NULL COMMENT '提交微信时间'");
CALL cy_fund_add_col('last_query_time',    "last_query_time DATETIME NULL COMMENT '最后查询微信时间'");
CALL cy_fund_add_col('balance_deduct_id',  "balance_deduct_id BIGINT NULL COMMENT '余额扣减流水ID'");
CALL cy_fund_add_col('balance_refund_id',  "balance_refund_id BIGINT NULL COMMENT '余额回补流水ID'");
CALL cy_fund_add_col('manual_operator',    "manual_operator VARCHAR(64) NULL COMMENT '人工处理人'");
CALL cy_fund_add_col('manual_note',        "manual_note VARCHAR(500) NULL COMMENT '人工处理说明'");
CALL cy_fund_add_col('trace_id',           "trace_id VARCHAR(64) NULL COMMENT '审计 trace id'");

DROP PROCEDURE IF EXISTS cy_fund_add_col;

-- 索引:定时查询扫「处理中」单(幂等加索引)
DROP PROCEDURE IF EXISTS cy_fund_add_idx;
DELIMITER $$
CREATE PROCEDURE cy_fund_add_idx()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'member_cash_withdraw'
                     AND index_name = 'idx_mcw_status_query') THEN
        ALTER TABLE member_cash_withdraw ADD INDEX idx_mcw_status_query (status, last_query_time);
    END IF;
END$$
DELIMITER ;
CALL cy_fund_add_idx();
DROP PROCEDURE IF EXISTS cy_fund_add_idx;
