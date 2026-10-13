-- E4 商业化(先做能力不真收费):商家付费权益/订阅表
-- 关联设计:Desktop/Questify_商家店铺装修_最终设计与TODO_20260708.md §7 E4
-- 定价/分成不写死:后台可配(本表只记已授予的权益;首批人工授予,收费开关默认关)

CREATE TABLE IF NOT EXISTS merchant_subscription (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL COMMENT '商家 memberId',
  subscription_type VARCHAR(50) NOT NULL COMMENT '权益类型 premium_template/promotion_slot/brand_home/custom_event',
  start_date DATETIME NULL,
  end_date DATETIME NULL COMMENT '到期时间(NULL=永久)',
  max_usage INT NULL COMMENT '可用次数(NULL=不限)',
  used_count INT NULL DEFAULT 0,
  is_active TINYINT NULL DEFAULT 1 COMMENT '1生效 0停用',
  remark VARCHAR(255) NULL,
  create_time DATETIME NULL,
  update_time DATETIME NULL,
  KEY idx_merchant (merchant_id),
  KEY idx_type (subscription_type)
) COMMENT='E4商家付费权益/订阅';

-- 回滚: DROP TABLE merchant_subscription;
