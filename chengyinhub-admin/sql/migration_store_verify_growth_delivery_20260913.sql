-- 核销事务保存当时成长任务；后台恢复不依赖动态码，不补造历史核销事件。
CREATE TABLE IF NOT EXISTS store_verify_growth_delivery (
  registration_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  missions_json TEXT NOT NULL,
  create_time DATETIME NOT NULL,
  next_attempt_at DATETIME NOT NULL,
  processed_at DATETIME NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(500) NULL,
  PRIMARY KEY (registration_id),
  KEY idx_store_growth_due (processed_at, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核销成长奖励持久待办';
