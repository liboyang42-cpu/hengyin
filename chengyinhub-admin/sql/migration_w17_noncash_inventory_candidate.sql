-- W17 isolated-evaluation candidate only. No runtime authority, endpoint or activation is granted.
-- Dedicated supply references the canonical SKU by immutable snapshot; this migration does NOT
-- create or change pms_product/pms_sku. Source consent/ownership verification remains mandatory.
-- NO PAD binary Unicode collation preserves exact Java string identity (including trailing spaces).
CREATE TABLE IF NOT EXISTS w17_noncash_pledge (
    pledge_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    total BIGINT NOT NULL,
    available BIGINT NOT NULL,
    reserved BIGINT NOT NULL,
    awarded BIGINT NOT NULL,
    consumed BIGINT NOT NULL,
    retired BIGINT NOT NULL,
    PRIMARY KEY (pledge_id),
    CONSTRAINT ck_w17_supply_positive CHECK (total > 0),
    CONSTRAINT ck_w17_supply_nonnegative CHECK (available >= 0 AND reserved >= 0 AND awarded >= 0 AND consumed >= 0 AND retired >= 0),
    CONSTRAINT ck_w17_supply_conserved CHECK (total = available + reserved + awarded + consumed + retired)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS w17_noncash_reservation (
    reservation_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pledge_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    member_id BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uk_w17_reservation_owner (reservation_id, pledge_id, member_id),
    CONSTRAINT fk_w17_reservation_pledge FOREIGN KEY (pledge_id) REFERENCES w17_noncash_pledge(pledge_id),
    CONSTRAINT ck_w17_reservation_member CHECK (member_id > 0),
    CONSTRAINT ck_w17_reservation_state CHECK (state IN ('RESERVED','AWARDED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS w17_noncash_award (
    award_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pledge_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    member_id BIGINT NOT NULL,
    source_event_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reservation_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    awarded_at BIGINT NOT NULL,
    PRIMARY KEY (award_id),
    UNIQUE KEY uk_w17_award_reservation (reservation_id),
    KEY ix_w17_award_member (member_id, awarded_at),
    CONSTRAINT fk_w17_award_pledge FOREIGN KEY (pledge_id) REFERENCES w17_noncash_pledge(pledge_id),
    CONSTRAINT fk_w17_award_reservation FOREIGN KEY (reservation_id, pledge_id, member_id) REFERENCES w17_noncash_reservation(reservation_id, pledge_id, member_id),
    CONSTRAINT ck_w17_award_member CHECK (member_id > 0),
    CONSTRAINT ck_w17_award_state CHECK (state IN ('AWARDED','REDEEMED','EXPIRED','REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS w17_noncash_consumption (
    source_receipt_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    award_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    staff_actor_id BIGINT NOT NULL,
    PRIMARY KEY (source_receipt_id),
    UNIQUE KEY uk_w17_consumption_award (award_id),
    CONSTRAINT fk_w17_consumption_award FOREIGN KEY (award_id) REFERENCES w17_noncash_award(award_id),
    CONSTRAINT ck_w17_consumption_staff CHECK (staff_actor_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS w17_noncash_audit (
    audit_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pledge_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    award_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at BIGINT NOT NULL,
    PRIMARY KEY (audit_id),
    KEY ix_w17_audit_pledge (pledge_id),
    CONSTRAINT fk_w17_audit_pledge FOREIGN KEY (pledge_id) REFERENCES w17_noncash_pledge(pledge_id),
    CONSTRAINT fk_w17_audit_award FOREIGN KEY (award_id) REFERENCES w17_noncash_award(award_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

SELECT IF(COUNT(*) = 5, 1, 0) FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name IN ('w17_noncash_pledge','w17_noncash_reservation','w17_noncash_award','w17_noncash_consumption','w17_noncash_audit') AND engine = 'InnoDB';
SELECT IF(COUNT(*) = 6, 1, 0) FROM information_schema.referential_constraints
 WHERE constraint_schema = DATABASE() AND constraint_name LIKE 'fk_w17_%';
SELECT IF(COUNT(*) = 1, 1, 0) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'w17_noncash_consumption' AND column_name = 'source_receipt_id' AND collation_name = 'utf8mb4_0900_bin';
