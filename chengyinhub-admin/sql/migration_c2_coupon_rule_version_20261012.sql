-- C2 / F0572 平台优惠券模板规则版本。
-- 1) sms_coupon.rule_version:后台改规则/停发/平台失效都在同一把乐观锁上推进版本,
--    过期表单(版本冲突)整体拒绝,不覆盖别人已提交的规则。
-- 2) sms_coupon_history.rule_version / rule_amount / rule_min_point:领取时在券行锁内冻结当时规则,
--    之后改面额/门槛/有效期只影响新领取;已领取的券读自己的快照(有效期本就冻结在 start_time/end_time)。
--    历史行全部为 NULL = 迁移前领取,读侧回落到券当前规则(与迁移前行为一致),不伪造历史版本。
-- 3) sms_coupon_rule_version:每次规则变更/停发/平台失效追加一行(只追加,不改写),
--    平台失效的原因独立成列,并记下受影响持券人数。
-- 可重复执行:每一步都以 information_schema 判定是否已做过。
-- ⚠️ 只在库上跑:合入前须走 chengyin-db-migrate 只读预检 + 用户确认后落库;本文件不自动执行。

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'sms_coupon' AND column_name = 'rule_version');
SET @sql := IF(@exist = 0,
  'ALTER TABLE sms_coupon ADD COLUMN rule_version INT NOT NULL DEFAULT 1 COMMENT ''规则版本:后台改规则/停发/平台失效 CAS 推进''',
  'SELECT ''sms_coupon.rule_version 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'sms_coupon_history' AND column_name = 'rule_version');
SET @sql := IF(@exist = 0,
  'ALTER TABLE sms_coupon_history ADD COLUMN rule_version INT NULL DEFAULT NULL COMMENT ''领取时冻结的券规则版本;NULL=迁移前领取''',
  'SELECT ''sms_coupon_history.rule_version 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'sms_coupon_history' AND column_name = 'rule_amount');
SET @sql := IF(@exist = 0,
  'ALTER TABLE sms_coupon_history ADD COLUMN rule_amount DECIMAL(10,2) NULL DEFAULT NULL COMMENT ''领取时冻结的面额/折扣''',
  'SELECT ''sms_coupon_history.rule_amount 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'sms_coupon_history' AND column_name = 'rule_min_point');
SET @sql := IF(@exist = 0,
  'ALTER TABLE sms_coupon_history ADD COLUMN rule_min_point DECIMAL(10,2) NULL DEFAULT NULL COMMENT ''领取时冻结的使用门槛''',
  'SELECT ''sms_coupon_history.rule_min_point 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS sms_coupon_rule_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  coupon_id BIGINT NOT NULL COMMENT 'sms_coupon.id',
  rule_version INT NOT NULL COMMENT '本行描述的规则版本',
  change_type VARCHAR(24) NOT NULL COMMENT 'BASELINE/RULE_UPDATE/STOP_ISSUE/PLATFORM_INVALIDATE',
  changed_fields VARCHAR(255) NULL DEFAULT NULL COMMENT '本次变更的白名单字段,逗号分隔',
  name VARCHAR(100) NULL DEFAULT NULL,
  amount DECIMAL(10,2) NULL DEFAULT NULL,
  min_point DECIMAL(10,2) NULL DEFAULT NULL,
  start_time DATETIME NULL DEFAULT NULL,
  end_time DATETIME NULL DEFAULT NULL,
  enable_time DATETIME NULL DEFAULT NULL,
  publish_count BIGINT NULL DEFAULT NULL,
  status INT NULL DEFAULT NULL COMMENT '该版本生效后的券状态',
  reason VARCHAR(255) NULL DEFAULT NULL COMMENT '变更/停发/平台失效原因;平台失效必填',
  affected_holder_count INT NULL DEFAULT NULL COMMENT '平台失效时受影响的未使用持券数',
  operator_id BIGINT NULL DEFAULT NULL COMMENT '后台 sys_user.user_id',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_rule_version (coupon_id, rule_version),
  KEY idx_coupon_rule_version_coupon (coupon_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板规则版本(只追加)';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'sms_coupon' AND column_name = 'rule_version'
          AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'sms_coupon_history'
         AND column_name IN ('rule_version', 'rule_amount', 'rule_min_point') AND is_nullable = 'YES') = 3
  AND (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'sms_coupon_rule_version'
         AND index_name = 'uk_coupon_rule_version') = 2 AS assertion_ok;
