-- T5 真实出金:微信零钱提现单(商家/主理人一键把可用余额提现到微信零钱)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:新建表,对存量无影响。
-- ⚠️ 部署顺序:先于新 jar 落库(MemberCashWithdrawMapper 引用本表)。
-- 说明:提现流程 = deductBalance(原子扣余额,balance>=amount)→ 建提现单(status=0处理中)
--        → 微信商家转账到零钱(幂等键 out_batch_no)→ 成功置1回写 transfer_batch_id;
--        失败则 addBalance 原路退回余额 + status=2 记 fail_reason(不丢钱)。
--        与银行卡人工审核提现(ums_member_withdrawal)是两条独立通道,本表仅微信零钱自动出金。

CREATE TABLE IF NOT EXISTS member_cash_withdraw (
    id                BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id         BIGINT(20)    NOT NULL COMMENT '提现会员(商家/主理人)',
    openid            VARCHAR(64)   NULL COMMENT '收款人微信openid(与商户号appid同主体)',
    amount            DECIMAL(15,2) NOT NULL COMMENT '提现金额(元)',
    out_batch_no      VARCHAR(64)   NULL COMMENT '商户批次单号(确定性幂等键=CW+id)',
    out_detail_no     VARCHAR(64)   NULL COMMENT '商户明细单号',
    transfer_batch_id VARCHAR(64)   NULL COMMENT '微信批次单号(成功回写,对账)',
    status            TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0处理中 1成功 2失败',
    fail_reason       VARCHAR(255)  NULL COMMENT '失败原因',
    create_time       DATETIME      NULL,
    finish_time       DATETIME      NULL COMMENT '出金完成/失败时间',
    del_flag          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_cw_member (member_id),
    KEY idx_cw_status (status),
    UNIQUE KEY uk_cw_out_batch (out_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信零钱提现单(真实出金)';
