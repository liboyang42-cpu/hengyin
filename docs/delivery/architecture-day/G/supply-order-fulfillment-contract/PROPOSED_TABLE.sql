-- DESIGN CANDIDATE ONLY. Not a canonical migration; DO NOT EXECUTE without root approval,
-- exact preflight, shared source/caller implementation and controlled real-MySQL tests.
-- No legacy contract/fact backfill, UPDATE, DELETE or production DDL is authorized here.
CREATE TABLE coop_order_station_fulfillment (
    id                      BIGINT NOT NULL AUTO_INCREMENT,
    order_id                BIGINT NOT NULL,
    topic_id                BIGINT NOT NULL,
    beneficiary_member_id   BIGINT NOT NULL,
    registration_id         BIGINT NOT NULL,
    station_id              BIGINT NOT NULL,
    verification_audit_id   BIGINT NOT NULL,
    sales_basis_amount      DECIMAL(15,2) NOT NULL,
    order_terms_sha256      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_fingerprint      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fulfilled_at            DATETIME(3) NOT NULL,
    recorded_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_corsf_original_proof (verification_audit_id),
    KEY idx_corsf_order_registration (order_id, registration_id),
    KEY idx_corsf_topic_member (topic_id, beneficiary_member_id, registration_id),
    CONSTRAINT ck_corsf_positive_refs CHECK
        (order_id > 0 AND topic_id > 0 AND beneficiary_member_id > 0
         AND registration_id > 0 AND station_id > 0 AND verification_audit_id > 0),
    CONSTRAINT ck_corsf_nonnegative_basis CHECK (sales_basis_amount >= 0),
    CONSTRAINT ck_corsf_fingerprints CHECK
        (REGEXP_LIKE(order_terms_sha256, '^[0-9a-f]{64}$', 'c')
         AND REGEXP_LIKE(source_fingerprint, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT fk_corsf_order FOREIGN KEY (order_id) REFERENCES coop_order(id),
    CONSTRAINT fk_corsf_topic FOREIGN KEY (topic_id) REFERENCES cms_topic(id),
    CONSTRAINT fk_corsf_member FOREIGN KEY (beneficiary_member_id) REFERENCES ums_member(id),
    CONSTRAINT fk_corsf_registration FOREIGN KEY (registration_id) REFERENCES cms_registration(id),
    CONSTRAINT fk_corsf_station FOREIGN KEY (station_id) REFERENCES cms_registration_merchant(id),
    CONSTRAINT fk_corsf_audit FOREIGN KEY (verification_audit_id) REFERENCES business_audit_event(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- These FKs only prove existence. The *mandatory*, single-writer guarded INSERT SELECT
-- must prove same topic/order/member/station/source audit/registration/terms hash and
-- exact original sales basis under the existing topic -> registration transaction locks.
-- This proposal has NO runnable INSERT or producer, and does not assert FK scope isolation.
CREATE TRIGGER tr_corsf_immutable_update BEFORE UPDATE ON coop_order_station_fulfillment
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_ORIGINAL_SUPPLY_FULFILLMENT';
CREATE TRIGGER tr_corsf_immutable_delete BEFORE DELETE ON coop_order_station_fulfillment
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_ORIGINAL_SUPPLY_FULFILLMENT';
