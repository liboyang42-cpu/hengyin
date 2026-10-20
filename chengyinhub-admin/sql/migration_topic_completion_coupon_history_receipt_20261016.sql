-- REVIEW ONLY: 未执行迁移。旧行保持NULL，不猜测或回填原领取身份。
-- 发布顺序：先经批准执行此加列迁移，再部署依赖history_id的源码。
SET @exists := (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'cms_topic_completion_coupon_reward_log'
 AND column_name = 'history_id');
SET @sql := IF(@exists = 0,
 'ALTER TABLE cms_topic_completion_coupon_reward_log ADD COLUMN history_id BIGINT NULL COMMENT ''原通关奖励领取回执 sms_coupon_history.id'' AFTER coupon_id',
 'SELECT ''cms_topic_completion_coupon_reward_log.history_id already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- 原uk_topic_member保持，无旧数据UPDATE、无外键/新全局唯一性政策。
