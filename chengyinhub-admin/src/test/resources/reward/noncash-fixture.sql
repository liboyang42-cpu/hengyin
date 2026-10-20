-- Isolated H2 fixture ONLY, NOT a production migration or authorization to activate.
CREATE TABLE pms_product(id BIGINT PRIMARY KEY, merchant_id BIGINT NOT NULL, publish_status INT NOT NULL, del_flag INT NOT NULL);
CREATE TABLE pms_sku(id BIGINT PRIMARY KEY,product_id BIGINT NOT NULL,stock BIGINT NOT NULL);
CREATE TABLE w17_noncash_pledge(pledge_id VARCHAR(512) PRIMARY KEY,fingerprint VARCHAR(64) NOT NULL,payload CLOB NOT NULL,
 total BIGINT NOT NULL,available BIGINT NOT NULL,reserved BIGINT NOT NULL,awarded BIGINT NOT NULL,consumed BIGINT NOT NULL,retired BIGINT NOT NULL,
 CHECK(total=available+reserved+awarded+consumed+retired),CHECK(available>=0 AND reserved>=0 AND awarded>=0 AND consumed>=0 AND retired>=0));
CREATE TABLE w17_noncash_reservation(reservation_id VARCHAR(64) PRIMARY KEY,pledge_id VARCHAR(512) NOT NULL,member_id BIGINT NOT NULL,state VARCHAR(32) NOT NULL);
CREATE TABLE w17_noncash_award(award_id VARCHAR(64) PRIMARY KEY,pledge_id VARCHAR(512) NOT NULL,member_id BIGINT NOT NULL,source_event_id VARCHAR(512) NOT NULL,
 reservation_id VARCHAR(64),state VARCHAR(32) NOT NULL,awarded_at BIGINT NOT NULL);
CREATE TABLE w17_noncash_audit(audit_id VARCHAR(64) PRIMARY KEY,pledge_id VARCHAR(512) NOT NULL,award_id VARCHAR(64),kind VARCHAR(64) NOT NULL,source_id VARCHAR(512) NOT NULL,occurred_at BIGINT NOT NULL);

CREATE TABLE w17_noncash_consumption(source_receipt_id VARCHAR(512) PRIMARY KEY,award_id VARCHAR(64) NOT NULL UNIQUE,staff_actor_id BIGINT NOT NULL);
