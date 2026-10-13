-- 主题通关优惠券：主题级配置 + 每玩家每主题一次的幂等发放日志。
-- 生产执行前必须走 chengyin-db-migrate: 只读预检 → 用户确认 → 执行 → describe 回读。

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'cms_topic'
                  AND column_name = 'complete_reward_coupon_id');
SET @sql := IF(@exists = 0,
  'ALTER TABLE cms_topic ADD COLUMN complete_reward_coupon_id BIGINT NULL COMMENT ''主题通关奖励券 sms_coupon.id'' AFTER finish_medal_img',
  'SELECT ''cms_topic.complete_reward_coupon_id already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS cms_topic_completion_coupon_reward_log (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  topic_id    BIGINT NOT NULL,
  member_id   BIGINT NOT NULL,
  coupon_id   BIGINT NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_topic_member (topic_id, member_id),
  KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题通关奖励券发放幂等日志';
