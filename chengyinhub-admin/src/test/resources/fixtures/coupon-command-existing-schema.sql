-- Synthetic, isolated test substrate for existing coupon and owner tables only.
-- This is not a historical schema migration or a production schema substitute.
-- The new receipt table must be created from its unmodified repository migration.
CREATE TABLE ums_member (id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE sms_coupon (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255), coupon_type INT, platform INT,
  amount DECIMAL(12,2), min_point DECIMAL(12,2),
  start_time DATETIME(3), end_time DATETIME(3), use_type INT,
  publish_count BIGINT, use_count BIGINT, receive_count BIGINT,
  enable_time DATETIME(3), code VARCHAR(255),
  scope_type INT, member_id BIGINT, merchant_id BIGINT,
  status INT, del_flag INT, description VARCHAR(512),
  create_by VARCHAR(64), create_time DATETIME(3),
  update_by VARCHAR(64), update_time DATETIME(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sms_coupon_history (
  id BIGINT NOT NULL PRIMARY KEY, coupon_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL, use_status INT NOT NULL, coupon_code VARCHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
