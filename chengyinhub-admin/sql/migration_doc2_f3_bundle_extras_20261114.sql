-- DOC2 F3 free-explore bundle extras for the app (gaps A pass 2): reviews, renewal intent and voucher appeals.
-- Additive only. None of these rows moves money: a review and a renewal intent are statements, an appeal is a
-- service case whose approval does NOT consume the unit (apply_state stays NOT_APPLIED until the settlement owner
-- decides). Refund requests and transfers are refused in v1 and have no table here.
CREATE TABLE IF NOT EXISTS explore_bundle_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  registration_id BIGINT NOT NULL,
  holder_member_id BIGINT NOT NULL,
  topic_id BIGINT NOT NULL,
  rating TINYINT NOT NULL,
  content VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/HIDDEN; only APPROVED is listed',
  idempotency_key VARCHAR(96) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_review_order_holder (registration_id, holder_member_id),
  UNIQUE KEY uk_bundle_review_idem (holder_member_id, idempotency_key),
  KEY idx_bundle_review_topic (topic_id, status, id),
  CONSTRAINT ck_bundle_review_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票评价';

CREATE TABLE IF NOT EXISTS explore_bundle_renewal_intent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  registration_id BIGINT NOT NULL,
  holder_member_id BIGINT NOT NULL,
  topic_id BIGINT NOT NULL,
  intent VARCHAR(16) NOT NULL COMMENT 'WILL_RENEW/MAYBE/NO; no price lock, no order',
  note VARCHAR(200) NULL,
  supersedes_id BIGINT NULL,
  idempotency_key VARCHAR(96) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_renewal_idem (holder_member_id, idempotency_key),
  KEY idx_bundle_renewal_order (registration_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票续费意向(只插不改,最新一条生效)';

CREATE TABLE IF NOT EXISTS explore_bundle_appeal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  registration_id BIGINT NOT NULL,
  unit_id BIGINT NOT NULL,
  holder_member_id BIGINT NOT NULL,
  merchant_id BIGINT NULL,
  reason_code VARCHAR(32) NOT NULL,
  note VARCHAR(500) NULL,
  evidence_json JSON NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/APPROVED/REJECTED/WITHDRAWN',
  active_unit_key BIGINT GENERATED ALWAYS AS (IF(status = 'OPEN', unit_id, NULL)) STORED,
  idempotency_key VARCHAR(96) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  decided_by BIGINT NULL,
  decided_at DATETIME NULL,
  decision_reason VARCHAR(500) NULL,
  apply_state VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLIED' COMMENT 'approval never consumes the unit in v1',
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bundle_appeal_idem (holder_member_id, idempotency_key),
  UNIQUE KEY uk_bundle_appeal_active_unit (active_unit_key),
  KEY idx_bundle_appeal_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F3 套票申诉(只记录,不消耗单位)';
