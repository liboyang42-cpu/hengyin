-- 通关奖励只绑定本次发券的原回执；旧行保留 NULL，不按时间猜测或回填。
-- 发布顺序：本迁移回读通过后再部署依赖 history_id 的后端；生产执行另行审批。
SET @completion_receipt_exists := (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'cms_topic_completion_coupon_reward_log'
 AND column_name = 'history_id');
SET @completion_receipt_ddl := IF(@completion_receipt_exists = 0,
 'ALTER TABLE cms_topic_completion_coupon_reward_log ADD COLUMN history_id BIGINT NULL COMMENT ''原通关奖励领取回执 sms_coupon_history.id'' AFTER coupon_id',
 'SELECT ''cms_topic_completion_coupon_reward_log.history_id already exists''');
PREPARE completion_receipt_stmt FROM @completion_receipt_ddl;
EXECUTE completion_receipt_stmt;
DEALLOCATE PREPARE completion_receipt_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'cms_topic_completion_coupon_reward_log'
 AND column_name = 'history_id' AND column_type = 'bigint'
 AND is_nullable = 'YES' AND column_default IS NULL AND extra = '';
