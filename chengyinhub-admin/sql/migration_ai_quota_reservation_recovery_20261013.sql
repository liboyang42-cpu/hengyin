-- C包开发测试 migration。生产应用及Quartz启用另验，本脚本不部署任务。
CREATE TABLE IF NOT EXISTS ai_quota_reservation (
 id BIGINT NOT NULL AUTO_INCREMENT,
 member_id BIGINT NOT NULL,
 biz_type VARCHAR(64) NOT NULL,
 pool VARCHAR(32) NOT NULL,
 original_day CHAR(8) NOT NULL,
 counter_key VARCHAR(191) NOT NULL,
 nonce CHAR(32) NOT NULL,
 receipt_key VARCHAR(255) NOT NULL,
 generation VARCHAR(255) NULL,
 used_after_reserve BIGINT NULL,
 state VARCHAR(40) NOT NULL,
 stage VARCHAR(40) NOT NULL,
 decision_reason VARCHAR(64) NULL,
 request_lease_token CHAR(32) NOT NULL,
 request_lease_until DATETIME(3) NOT NULL,
 owner_token CHAR(32) NULL,
 fence_version BIGINT NOT NULL DEFAULT 0,
 claim_until DATETIME(3) NULL,
 next_attempt_at DATETIME(3) NOT NULL,
 attempt_count INT NOT NULL DEFAULT 0,
 last_error_code VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 completed_at DATETIME(3) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_ai_quota_nonce(nonce), UNIQUE KEY uk_ai_quota_receipt(receipt_key),
 KEY idx_ai_quota_due(state,next_attempt_at,id), KEY idx_ai_quota_member_day(member_id,original_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 注册为暂停：生产 DDL、原身份回读及任务启用均不在本次执行范围。
INSERT INTO sys_job
 (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT 'AI原日配额恢复','DEFAULT','aiQuotaRecoveryTask.run()','0 */1 * * * ?','3','1','1','admin',NOW(),
 'C包原日原nonce补偿；默认暂停，开发测试数据库/Redis和崩溃场景回读后另行启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target='aiQuotaRecoveryTask.run()');

-- 回读合同：开发测试独立连接核表字段/nonce唯一键及暂停job；未执行不称迁移通过。
-- runner: assert=scalar-true
SELECT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='aiQuotaRecoveryTask.run()' AND status='1');
