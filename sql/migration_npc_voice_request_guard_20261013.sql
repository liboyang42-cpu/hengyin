-- Merchant voice requests survive resets/restarts; do not retry unknown supplier outcomes.
ALTER TABLE npc_profile
  ADD COLUMN voice_generation BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN voice_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE npc_voice_request (
  merchant_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  profile_id BIGINT NOT NULL,
  generation BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  sample VARCHAR(255) NOT NULL,
  voice_id VARCHAR(255) NULL,
  voice_status TINYINT NOT NULL DEFAULT 4 COMMENT '1 pending, 2 ready, 4 unknown',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (merchant_id, request_id),
  UNIQUE KEY uk_profile_generation (profile_id, generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
