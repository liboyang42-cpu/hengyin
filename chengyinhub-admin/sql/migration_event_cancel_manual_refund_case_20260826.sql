-- 活动取消遇到“同一父订单部分核销”时，冻结可枚举的人工退款评估事实。
-- 本表不写 refund_application、不执行支付，也没有 REFUNDED/RESOLVED 终态。
-- 上线前仍须单独走 chengyin-db-migrate 预检、执行和目标库读回。

CREATE TABLE IF NOT EXISTS activity_cancel_manual_refund_case (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT DEFAULT NULL,
  activity_id BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  actor_member_id BIGINT NOT NULL,
  parent_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payer_registration_id BIGINT NOT NULL,
  payer_member_id BIGINT NOT NULL,
  paid_cash_amount_fen BIGINT UNSIGNED NOT NULL,
  verified_registration_ids_json JSON NOT NULL,
  unverified_registration_ids_json JSON NOT NULL,
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING_ASSESSMENT',
  assessed_refund_amount_fen BIGINT UNSIGNED DEFAULT NULL,
  assessment_note VARCHAR(500) DEFAULT NULL,
  assessed_by_user_id BIGINT DEFAULT NULL,
  assessment_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  assessed_at DATETIME(3) DEFAULT NULL,
  actual_refund_amount_fen BIGINT UNSIGNED DEFAULT NULL,
  manual_channel VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  evidence_object_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  evidence_sha256 BINARY(32) DEFAULT NULL,
  handle_note VARCHAR(500) DEFAULT NULL,
  handled_by_user_id BIGINT DEFAULT NULL,
  handle_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  handled_at DATETIME(3) DEFAULT NULL,
  verification_note VARCHAR(500) DEFAULT NULL,
  verified_by_user_id BIGINT DEFAULT NULL,
  verify_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  verified_at DATETIME(3) DEFAULT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_activity_cancel_manual_order (activity_id, parent_order_no),
  UNIQUE KEY uk_activity_cancel_manual_assessment_request (assessment_request_id),
  UNIQUE KEY uk_activity_cancel_manual_handle_request (handle_request_id),
  UNIQUE KEY uk_activity_cancel_manual_verify_request (verify_request_id),
  KEY idx_activity_cancel_manual_status (status, club_id, id),
  KEY idx_activity_cancel_manual_club (club_id, status, id),
  CONSTRAINT chk_activity_cancel_manual_status CHECK
    (status IN ('PENDING_ASSESSMENT','MANUAL_REFUND_REQUIRED','MANUAL_REFUND_RECORDED',
      'MANUAL_REFUND_EVIDENCE_VERIFIED','NO_REFUND')),
  CONSTRAINT chk_activity_cancel_manual_identity CHECK
    (activity_id > 0 AND owner_member_id > 0 AND actor_member_id > 0
      AND payer_registration_id > 0 AND payer_member_id > 0
      AND (club_id IS NULL OR club_id > 0)),
  CONSTRAINT chk_activity_cancel_manual_verified_json CHECK
    (JSON_TYPE(verified_registration_ids_json) = 'ARRAY'
      AND JSON_LENGTH(verified_registration_ids_json) > 0),
  CONSTRAINT chk_activity_cancel_manual_unverified_json CHECK
    (JSON_TYPE(unverified_registration_ids_json) = 'ARRAY'
      AND JSON_LENGTH(unverified_registration_ids_json) > 0),
  CONSTRAINT chk_activity_cancel_manual_version CHECK (
    (status = 'PENDING_ASSESSMENT' AND MOD(version, 2) = 0)
    OR (status IN ('MANUAL_REFUND_REQUIRED','NO_REFUND') AND MOD(version, 2) = 1)
    OR (status = 'MANUAL_REFUND_RECORDED' AND version >= 2 AND MOD(version, 2) = 0)
    OR (status = 'MANUAL_REFUND_EVIDENCE_VERIFIED' AND version >= 3 AND MOD(version, 2) = 1)),
  CONSTRAINT chk_activity_cancel_manual_assessment CHECK
    (IF(status = 'PENDING_ASSESSMENT',
      IF(assessed_refund_amount_fen IS NULL AND assessment_note IS NULL
        AND assessed_by_user_id IS NULL AND assessment_request_id IS NULL AND assessed_at IS NULL, 1, 0),
      IF(assessed_refund_amount_fen IS NOT NULL AND assessed_refund_amount_fen <= paid_cash_amount_fen
        AND ((status = 'NO_REFUND' AND assessed_refund_amount_fen = 0)
          OR (status <> 'NO_REFUND' AND assessed_refund_amount_fen > 0))
        AND assessment_note IS NOT NULL AND CHAR_LENGTH(TRIM(assessment_note)) BETWEEN 1 AND 500
        AND assessed_by_user_id IS NOT NULL AND assessed_by_user_id > 0
        AND assessment_request_id IS NOT NULL
        AND assessed_at IS NOT NULL, 1, 0)) = 1),
  CONSTRAINT chk_activity_cancel_manual_handle CHECK
    (IF(status IN ('MANUAL_REFUND_RECORDED','MANUAL_REFUND_EVIDENCE_VERIFIED'),
      IF(actual_refund_amount_fen IS NOT NULL
        AND actual_refund_amount_fen = assessed_refund_amount_fen
        AND manual_channel IN ('original_retry','offline_transfer','wx_abnormal_change','wx_abnormal_bankcard')
        AND evidence_object_key IS NOT NULL
        AND evidence_sha256 IS NOT NULL
        AND handle_note IS NOT NULL AND CHAR_LENGTH(TRIM(handle_note)) BETWEEN 1 AND 500
        AND handled_by_user_id IS NOT NULL AND handled_by_user_id > 0
        AND handle_request_id IS NOT NULL
        AND handled_at IS NOT NULL, 1, 0),
      IF(actual_refund_amount_fen IS NULL AND manual_channel IS NULL
        AND evidence_object_key IS NULL AND evidence_sha256 IS NULL
        AND handle_note IS NULL AND handled_by_user_id IS NULL
        AND handle_request_id IS NULL AND handled_at IS NULL, 1, 0)) = 1),
  CONSTRAINT chk_activity_cancel_manual_verify CHECK
    (IF(status = 'MANUAL_REFUND_EVIDENCE_VERIFIED',
      IF(verification_note IS NOT NULL AND CHAR_LENGTH(TRIM(verification_note)) BETWEEN 1 AND 500
        AND verified_by_user_id IS NOT NULL AND verified_by_user_id > 0
        AND verified_by_user_id <> handled_by_user_id
        AND verify_request_id IS NOT NULL
        AND verified_at IS NOT NULL, 1, 0),
      IF(verification_note IS NULL AND verified_by_user_id IS NULL
        AND verify_request_id IS NULL AND verified_at IS NULL, 1, 0)) = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='活动取消部分核销订单人工退款评估事实；不代表已退款';

-- 候选同名表必须逐列完全匹配；签名覆盖 32 列的 type/null/default/extra/
-- charset/collation/generated expression，包含 evidence_object_key。
SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'activity_cancel_manual_refund_case'
          AND engine = 'InnoDB' AND table_collation = 'utf8mb4_general_ci') = 1
  AND COUNT(*) = 32
  AND SUM(CASE
    WHEN column_name = 'id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL AND LOWER(extra) = 'auto_increment' THEN 1
    WHEN column_name = 'club_id' AND column_type = 'bigint' AND is_nullable = 'YES'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'activity_id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'owner_member_id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'actor_member_id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'parent_order_no' AND column_type = 'varchar(64)' AND is_nullable = 'NO'
      AND column_default IS NULL AND collation_name = 'ascii_bin' THEN 1
    WHEN column_name = 'payer_registration_id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'payer_member_id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'paid_cash_amount_fen' AND column_type = 'bigint unsigned'
      AND is_nullable = 'NO' AND column_default IS NULL THEN 1
    WHEN column_name = 'verified_registration_ids_json' AND column_type = 'json'
      AND is_nullable = 'NO' AND column_default IS NULL THEN 1
    WHEN column_name = 'unverified_registration_ids_json' AND column_type = 'json'
      AND is_nullable = 'NO' AND column_default IS NULL THEN 1
    WHEN column_name = 'status' AND column_type = 'varchar(32)' AND is_nullable = 'NO'
      AND column_default <=> 'PENDING_ASSESSMENT' AND collation_name = 'ascii_bin' THEN 1
    WHEN column_name = 'assessed_refund_amount_fen' AND column_type = 'bigint unsigned'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'assessment_note' AND column_type = 'varchar(500)'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'assessed_by_user_id' AND column_type = 'bigint'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'assessment_request_id' AND column_type = 'varchar(64)'
      AND is_nullable = 'YES' AND column_default IS NULL AND collation_name = 'ascii_bin' THEN 1
    WHEN column_name = 'assessed_at' AND column_type = 'datetime(3)'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'actual_refund_amount_fen' AND column_type = 'bigint unsigned'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'manual_channel' AND column_type = 'varchar(32)'
      AND is_nullable = 'YES' AND column_default IS NULL AND collation_name = 'ascii_bin' THEN 1
    WHEN column_name = 'evidence_object_key' AND column_type = 'varchar(128)'
      AND is_nullable = 'YES' AND column_default IS NULL AND collation_name = 'ascii_bin' THEN 1
    WHEN column_name = 'evidence_sha256' AND column_type = 'binary(32)'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'handle_note' AND column_type = 'varchar(500)'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'handled_by_user_id' AND column_type = 'bigint'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'handle_request_id' AND column_type = 'varchar(64)'
      AND is_nullable = 'YES' AND column_default IS NULL AND collation_name = 'ascii_bin' THEN 1
    WHEN column_name = 'handled_at' AND column_type = 'datetime(3)'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'verification_note' AND column_type = 'varchar(500)'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'verified_by_user_id' AND column_type = 'bigint'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'verify_request_id' AND column_type = 'varchar(64)'
      AND is_nullable = 'YES' AND column_default IS NULL AND collation_name = 'ascii_bin' THEN 1
    WHEN column_name = 'verified_at' AND column_type = 'datetime(3)'
      AND is_nullable = 'YES' AND column_default IS NULL THEN 1
    WHEN column_name = 'version' AND column_type = 'bigint unsigned'
      AND is_nullable = 'NO' AND column_default <=> '0' THEN 1
    WHEN column_name = 'create_time' AND column_type = 'datetime(3)'
      AND is_nullable = 'NO' AND LOWER(column_default) <=> 'current_timestamp(3)' THEN 1
    WHEN column_name = 'update_time' AND column_type = 'datetime(3)'
      AND is_nullable = 'NO' AND LOWER(column_default) <=> 'current_timestamp(3)'
      AND LOWER(extra) LIKE '%on update current_timestamp(3)%' THEN 1
    ELSE 0 END) = 32
  AND SHA2(GROUP_CONCAT(CONCAT_WS('~', column_name, LOWER(column_type), is_nullable,
        COALESCE(LOWER(CAST(column_default AS CHAR)), '<null>'), LOWER(extra),
        COALESCE(character_set_name, '<null>'), COALESCE(collation_name, '<null>'),
        COALESCE(REGEXP_REPLACE(LOWER(generation_expression),
          '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), '<null>'))
        ORDER BY ordinal_position SEPARATOR '|'), 256) =
      '19890136c1e9a986edb16e6b534858931ebdb9f172c28f1f6d9cc3cfa411edf9'
  AS assertion_ok
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'activity_cancel_manual_refund_case';

-- 唯一键和队列索引必须完整、可见、无 prefix。
-- runner: assert=scalar-true
SELECT COUNT(*) = 7
  AND SUM(index_type = 'BTREE' AND is_visible = 'YES'
    AND prefix_parts = 0 AND ascending_parts = parts) = 7
  AND SUM(CASE
    WHEN index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_order' AND non_unique = 0
      AND cols = 'activity_id,parent_order_no' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_assessment_request' AND non_unique = 0
      AND cols = 'assessment_request_id' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_handle_request' AND non_unique = 0
      AND cols = 'handle_request_id' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_verify_request' AND non_unique = 0
      AND cols = 'verify_request_id' THEN 1
    WHEN index_name = 'idx_activity_cancel_manual_status' AND non_unique = 1
      AND cols = 'status,club_id,id' THEN 1
    WHEN index_name = 'idx_activity_cancel_manual_club' AND non_unique = 1
      AND cols = 'club_id,status,id' THEN 1
    ELSE 0 END) = 7 AS assertion_ok
FROM (
  SELECT index_name, MIN(non_unique) non_unique, MIN(index_type) index_type,
         MIN(is_visible) is_visible, COUNT(*) parts,
         SUM(sub_part IS NOT NULL) prefix_parts, SUM(collation = 'A') ascending_parts,
         GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') cols
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'activity_cancel_manual_refund_case'
  GROUP BY index_name
) exact_indexes;

-- CHECK 必须由 MySQL 实际 ENFORCED，且正文不能被同名恒真约束替换。
-- canonical 仅去空白/标识符与 charset introducer，刻意保留语义括号。
-- runner: assert=scalar-true
SELECT COUNT(*) = 8
  AND SUM(CASE
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_status'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> 'e68f601bf7ea150c6bcb47f484c0e2f0429d7e1cf79d99aa82a52be4071efcb9' THEN 1
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_identity'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> 'b2aba8bfadd5b2045a8fad5c818500ab5808daa1d520cd7f5db06ffac4113071' THEN 1
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_verified_json'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> '7d0286bdcb785cbc0fb5b046d715d867c53dbf593249ce10cf536685b9009a9a' THEN 1
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_unverified_json'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> 'fb0423935cee25770172ef27aa39efa1d62b3e1311db1cd5712a51d894c5789a' THEN 1
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_version'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> '74bcb0567bcf36e89e1c67b3ef04bcd1b698dbf4cf9156c6148000ca2bc8ac5e' THEN 1
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_assessment'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> 'd214fce37e2d38d3f5c19ca4e1d9ebad1f96eb623bbbc3b84c9359e4f50f5b84' THEN 1
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_handle'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> '73b2bec505cae385415d6b12f4e9a9fc402ae0289a722ecca558ba63bedf054e' THEN 1
    WHEN cc.constraint_name = 'chk_activity_cancel_manual_verify'
      AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
        '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
        <=> '5a704b999ff3ec921bc39f2bb24cc1181cbe07a8d459651bb3038c117d73512d' THEN 1
    ELSE 0 END) = 8 AS assertion_ok
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema = cc.constraint_schema
 AND tc.constraint_name = cc.constraint_name
WHERE cc.constraint_schema = DATABASE()
  AND tc.table_name = 'activity_cancel_manual_refund_case'
  AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES';
