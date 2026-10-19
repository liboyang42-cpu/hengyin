-- NEW proposed owner operation registry, not a payment/license/settlement ledger.
-- Preparation alone grants nothing. No migration is executed by this patch.
-- Retain CANCELLED/REJECTED tombstones: deleting one could revive a delayed request.
CREATE TABLE w18_paid_professional_operation (
 operation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_member_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_data VARBINARY(4096) NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 rejection_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(operation_key),
 UNIQUE KEY uq_w18_professional_actor_request(owner_member_id,request_id),
 CONSTRAINT ck_w18_professional_operation_state CHECK (state IN ('PREPARED','CREATED','REJECTED','CANCELLED')),
 CONSTRAINT ck_w18_professional_operation_reason CHECK ((state='REJECTED' AND rejection_code IS NOT NULL) OR (state<>'REJECTED' AND rejection_code IS NULL)),
 CONSTRAINT ck_w18_professional_operation_owner CHECK (owner_member_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
