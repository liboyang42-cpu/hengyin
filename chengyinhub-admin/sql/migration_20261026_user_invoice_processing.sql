-- W8-11: configuration is empty by design. No role grants, subjects or production data.
-- Apply after migration_20261022_f7_user_invoice.sql. Never infer a tax issuer from an owner name.
CREATE TABLE IF NOT EXISTS user_invoice_issuer (
 id bigint NOT NULL PRIMARY KEY, issuer_name varchar(100) NOT NULL,
 reconciliation_user_id bigint NOT NULL, enabled tinyint NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_invoice_issuer_owner (
 owner_type int NOT NULL, owner_id bigint NOT NULL, issuer_id bigint NOT NULL,
 PRIMARY KEY(owner_type, owner_id), KEY ix_issuer(issuer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_invoice_scope (
 issuer_id bigint NOT NULL, user_id bigint NOT NULL,
 can_handle tinyint NOT NULL DEFAULT 0, can_reconcile tinyint NOT NULL DEFAULT 0,
 enabled tinyint NOT NULL DEFAULT 0, PRIMARY KEY(issuer_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_invoice_processing (
 application_id bigint NOT NULL PRIMARY KEY, issuer_id bigint NOT NULL,
 version bigint NOT NULL DEFAULT 0, handler_id bigint NULL,
 operation_id varchar(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
 reconciliation_status varchar(32) NOT NULL DEFAULT 'NONE',
 evidence_ref varchar(128) NULL,
 UNIQUE KEY uk_operation(issuer_id,operation_id),
 KEY ix_issuer_application(issuer_id,application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Payload HMAC and metadata only: never store tax numbers or free-form invoice documents in audit.
CREATE TABLE IF NOT EXISTS user_invoice_processing_audit (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, application_id bigint NOT NULL,
 actor_id bigint NOT NULL, request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action varchar(32) NOT NULL, result_status varchar(16) NOT NULL,
 result_version bigint NOT NULL, evidence_ref varchar(128) NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_command(application_id,actor_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One verified external invoice reference per issuer and one per application.
CREATE TABLE IF NOT EXISTS user_invoice_issued_reference (
 issuer_id bigint NOT NULL,
 invoice_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 application_id bigint NOT NULL,
 PRIMARY KEY(issuer_id,invoice_no), UNIQUE KEY uk_application(application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- runner: assert=scalar-true
SELECT
 (SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user_invoice_processing')
  = 'application_id,issuer_id,version,handler_id,operation_id,reconciliation_status,evidence_ref'
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='user_invoice_processing_audit' AND index_name='uk_command' AND non_unique=0)
  = 'application_id,actor_id,request_id'
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='user_invoice_issued_reference' AND index_name='PRIMARY' AND non_unique=0)
  = 'issuer_id,invoice_no'
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='user_invoice_processing_audit' AND column_name IN ('request_id','fingerprint') AND collation_name='ascii_bin')=2;
