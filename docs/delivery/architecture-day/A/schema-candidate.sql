-- CI REVIEW CANDIDATE ONLY. Integrator must approve the schema before any production application.
-- No DDL has been executed by task A. MySQL 8 / InnoDB; additive, no historical backfill.
CREATE TABLE member_cash_withdraw_fund_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    withdrawal_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    out_batch_no VARCHAR(64) NOT NULL,
    out_detail_no VARCHAR(64) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    submission_intent_id BIGINT NULL,
    balance_before DECIMAL(15,2) NULL,
    balance_after DECIMAL(15,2) NULL,
    channel_batch_status VARCHAR(32) NULL,
    channel_detail_status VARCHAR(32) NULL,
    channel_detail_id VARCHAR(64) NULL,
    channel_error_code VARCHAR(64) NULL,
    create_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cash_fund_original_kind (withdrawal_id, kind),
    CONSTRAINT fk_cash_fund_original FOREIGN KEY (withdrawal_id) REFERENCES member_cash_withdraw(id),
    CONSTRAINT fk_cash_fund_intent FOREIGN KEY (submission_intent_id) REFERENCES member_cash_withdraw_fund_fact(id),
    CONSTRAINT ck_cash_fund_kind CHECK (kind IN
        ('DEBIT', 'SUBMIT_INTENT', 'ACCEPTED', 'UNKNOWN', 'NOT_ACCEPTED', 'FINAL_FAIL', 'SUCCESS', 'REFUND')),
    CONSTRAINT ck_cash_fund_rejection_intent CHECK (kind <> 'NOT_ACCEPTED' OR submission_intent_id IS NOT NULL),
    CONSTRAINT ck_cash_fund_final_fail CHECK (kind <> 'FINAL_FAIL' OR
        (channel_detail_status IS NOT NULL AND channel_detail_status = 'FAIL')),
    CONSTRAINT ck_cash_fund_amount CHECK (amount > 0),
    CONSTRAINT ck_cash_fund_debit CHECK (kind <> 'DEBIT' OR
        (balance_before IS NOT NULL AND balance_after IS NOT NULL AND balance_before - balance_after = amount))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Both bodies are single statements; no client-specific DELIMITER is needed.
CREATE TRIGGER cash_fund_fact_no_update BEFORE UPDATE ON member_cash_withdraw_fund_fact
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash withdrawal facts are append only';
CREATE TRIGGER cash_fund_fact_no_delete BEFORE DELETE ON member_cash_withdraw_fund_fact
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash withdrawal facts are append only';
