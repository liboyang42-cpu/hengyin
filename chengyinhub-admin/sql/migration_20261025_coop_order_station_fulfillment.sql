-- Explicitly confirmed one-station SUPPLY original-order attribution; expand only.
-- New invitations choose a station, acceptance freezes it in original terms_snapshot.
-- No historical invite/order/scan/audit row is inferred, updated, backfilled or deleted.
-- Use the canonical ledger and stop on every failed assertion; no production execution authorized.
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name='coop_order_station_fulfillment')=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='coop_invite' AND column_name='supply_station_id')=0
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='coop_invite' AND index_name='idx_coop_invite_supply_station')=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
   AND constraint_name IN ('ck_coop_invite_supply_station','fk_coop_invite_supply_station',
       'ck_corsf_positive_refs','ck_corsf_nonnegative_basis','ck_corsf_fingerprints',
       'fk_corsf_order','fk_corsf_topic','fk_corsf_member','fk_corsf_registration',
       'fk_corsf_station','fk_corsf_audit'))=0
  AND (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
   AND trigger_name IN ('tr_corsf_immutable_update','tr_corsf_immutable_delete'))=0
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN ('coop_invite','coop_order','cms_topic','ums_member','cms_registration',
                      'cms_registration_merchant','business_audit_event') AND engine='InnoDB')=7
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND column_name='id' AND column_type='bigint'
   AND table_name IN ('coop_order','cms_registration','cms_registration_merchant','business_audit_event'))=4
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND column_name='id' AND column_type='bigint unsigned'
   AND table_name IN ('cms_topic','ums_member'))=2;

ALTER TABLE coop_invite
  ADD COLUMN supply_station_id BIGINT NULL COMMENT 'Explicit original SUPPLY invitation station; legacy NULL is unproven',
  ADD KEY idx_coop_invite_supply_station (topic_id,to_type,to_id,supply_station_id),
  ADD CONSTRAINT ck_coop_invite_supply_station CHECK (supply_station_id IS NULL OR supply_station_id>0),
  ADD CONSTRAINT fk_coop_invite_supply_station FOREIGN KEY (supply_station_id) REFERENCES cms_registration_merchant(id);

CREATE TABLE coop_order_station_fulfillment (
    id                      BIGINT NOT NULL AUTO_INCREMENT,
    order_id                BIGINT NOT NULL,
    topic_id                BIGINT UNSIGNED NOT NULL,
    beneficiary_member_id   BIGINT UNSIGNED NOT NULL,
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

-- These assertions verify structure only, not producer correctness or economic acceptance.
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='coop_invite' AND column_name='supply_station_id'
   AND column_type='bigint' AND is_nullable='YES')=1
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name='coop_order_station_fulfillment' AND engine='InnoDB')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='coop_order_station_fulfillment')=12
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
   AND table_name='coop_order_station_fulfillment' AND constraint_type='FOREIGN KEY')=6
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='coop_order_station_fulfillment' AND index_name='uk_corsf_original_proof'
   AND non_unique=0 AND column_name='verification_audit_id' AND seq_in_index=1)=1
  AND (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
   AND event_object_table='coop_order_station_fulfillment' AND action_timing='BEFORE'
   AND ((trigger_name='tr_corsf_immutable_update' AND event_manipulation='UPDATE')
     OR (trigger_name='tr_corsf_immutable_delete' AND event_manipulation='DELETE')))=2;
