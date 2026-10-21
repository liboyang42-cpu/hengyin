-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. No seed rows, no OPEN policy, no player data, no cash or points columns.
-- Pledge quantity is this batch's allocation ledger. It is not pms_sku sellable stock.

CREATE TABLE w17_reward_policy (
  policy_code VARCHAR(64) NOT NULL,
  rule_version VARCHAR(128) NOT NULL,
  decision VARCHAR(16) NOT NULL,
  actor_member_id BIGINT NULL,
  PRIMARY KEY (policy_code, rule_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_pledge (
  pledge_id VARCHAR(64) NOT NULL PRIMARY KEY,
  store_id BIGINT NOT NULL,
  merchant_member_id BIGINT NOT NULL,
  coupon_id BIGINT NOT NULL,
  supply_ref VARCHAR(128) NOT NULL,
  quantity INT NOT NULL,
  service_start BIGINT NOT NULL,
  service_end BIGINT NOT NULL,
  redeem_start BIGINT NOT NULL,
  redeem_end BIGINT NOT NULL,
  terms VARCHAR(512) NOT NULL,
  minor_restricted TINYINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_pledge_version (
  version_id VARCHAR(64) NOT NULL PRIMARY KEY,
  pledge_id VARCHAR(64) NOT NULL,
  version_no INT NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  quantity INT NOT NULL,
  service_start BIGINT NOT NULL,
  service_end BIGINT NOT NULL,
  redeem_start BIGINT NOT NULL,
  redeem_end BIGINT NOT NULL,
  terms VARCHAR(512) NOT NULL,
  minor_restricted TINYINT NOT NULL,
  rule_version VARCHAR(128) NULL,
  state VARCHAR(24) NOT NULL,
  merchant_member_id BIGINT NULL,
  merchant_hash VARCHAR(64) NULL,
  merchant_confirmed_at BIGINT NULL,
  reviewer_member_id BIGINT NULL,
  reviewer_hash VARCHAR(64) NULL,
  frozen_at BIGINT NULL,
  UNIQUE KEY uk_w17_pledge_version (pledge_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_stock (
  version_id VARCHAR(64) NOT NULL PRIMARY KEY,
  quantity INT NOT NULL,
  available INT NOT NULL,
  reserved INT NOT NULL,
  awarded_unredeemed INT NOT NULL,
  redeemed INT NOT NULL,
  expired INT NOT NULL,
  frozen_units INT NOT NULL,
  withdrawn INT NOT NULL,
  version_no BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_intent (
  owner_id BIGINT NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  version_id VARCHAR(64) NOT NULL,
  branch VARCHAR(16) NOT NULL,
  subject_id VARCHAR(160) NOT NULL,
  state VARCHAR(24) NOT NULL,
  award_id VARCHAR(64) NULL,
  error_code VARCHAR(64) NULL,
  PRIMARY KEY (owner_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_award (
  award_id VARCHAR(64) NOT NULL PRIMARY KEY,
  version_id VARCHAR(64) NOT NULL,
  rule_version VARCHAR(128) NOT NULL,
  beneficiary_id BIGINT NOT NULL,
  reward_scope VARCHAR(160) NOT NULL,
  fact_ref VARCHAR(256) NOT NULL,
  history_id BIGINT NULL,
  state VARCHAR(24) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_w17_award_business (rule_version, beneficiary_id, reward_scope, fact_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_receipt (
  award_id VARCHAR(64) NOT NULL PRIMARY KEY,
  history_id BIGINT NOT NULL,
  terms_hash VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_outbox (
  award_id VARCHAR(64) NOT NULL PRIMARY KEY,
  state VARCHAR(16) NOT NULL,
  attempt INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w17_reward_case (
  case_id VARCHAR(64) NOT NULL PRIMARY KEY,
  award_id VARCHAR(64) NULL,
  version_id VARCHAR(64) NOT NULL,
  owner_id BIGINT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
