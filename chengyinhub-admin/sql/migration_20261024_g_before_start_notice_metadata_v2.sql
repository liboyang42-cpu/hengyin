-- Controlled replacement of the UNAPPLIED migration_20261024_c_before_start_notice_typed.sql only.
-- Depends on migration_20261024_d_committed_official_notice_wx.sql, NOT on the failed C migration.
-- Original C SHA256: 615c8c7883a97ca70e1cc14ff4ac965a895db9ccfea91f6f08e269dbf99e889a
-- Select this entry instead of old C only after confirming no successful old C receipt/typed schema.
-- NEVER run both entries, mark an ambiguous attempt applied, or retry ALTER against an already typed table.
-- The exact old 12-column/3-CHECK shape is still required; an already typed table fails before DDL.
-- Only metadata serialization is normalized: backslash + apostrophe -> apostrophe, preserving all
-- other characters/backslashes. CHAR(... USING utf8mb4) keeps LOWER operating on a text value.
-- Every original preflight, legacy-data gate, ALTER and postcondition is otherwise unchanged.
-- No production execution is authorized here. MySQL DDL commits independently: if ALTER succeeded
-- but readback/receipt failed, stop and inspect; do not rerun or drop typed columns/data to recover.

-- Additive extension of D's existing ledger only. DO NOT execute without coordinator DDL approval.
-- Preflight: D migration installed; old rows all have im_intent_id>0, valid request_hash/state/owner;
-- no event_key with reserved prefix 'before-start:'; preserve every old row/hash/unique key.
-- Operational cutover: pause job 102; retain legacy success keys; unresolved pre-ledger attempts need
-- provider/manual verification. Absence of the old two-day Redis key is NOT proof of no historical send.
-- runner: assert=scalar-true
SELECT CAST(IF(
 DATABASE() IS NOT NULL
 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND table_type='BASE TABLE' AND engine='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx')=12
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND column_name IN ('notice_type','wx_payload_json'))=0
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND (
       (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND column_type NOT LIKE '%zerofill%' AND is_nullable='NO' AND extra='auto_increment')
       OR (column_name='event_key' AND data_type='varchar' AND character_maximum_length=96 AND is_nullable='NO' AND collation_name='ascii_bin')
       OR (column_name='recipient' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND column_type NOT LIKE '%zerofill%' AND is_nullable='NO')
       OR (column_name='title' AND data_type='varchar' AND character_maximum_length=200 AND is_nullable='NO')
       OR (column_name='body' AND data_type='text' AND is_nullable='NO')
       OR (column_name='im_intent_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND column_type NOT LIKE '%zerofill%' AND is_nullable='NO')
       OR (column_name='request_hash' AND data_type='char' AND character_maximum_length=64 AND is_nullable='NO' AND collation_name='ascii_bin')
       OR (column_name='wx_state' AND data_type='varchar' AND character_maximum_length=16 AND is_nullable='NO' AND collation_name='ascii_bin' AND column_default='PENDING')
       OR (column_name='owner_token' AND data_type='char' AND character_maximum_length=36 AND is_nullable='YES' AND collation_name='ascii_bin')
       OR (column_name='created_at' AND data_type='datetime' AND datetime_precision=3 AND is_nullable='NO')
       OR (column_name='attempted_at' AND data_type='datetime' AND datetime_precision=3 AND is_nullable='YES')
       OR (column_name='accepted_at' AND data_type='datetime' AND datetime_precision=3 AND is_nullable='YES')))=12
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND constraint_type='CHECK')=3
 AND (SELECT COUNT(*) FROM information_schema.table_constraints tc
      JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
      WHERE tc.constraint_schema=DATABASE() AND tc.table_name='committed_official_notice_wx'
      AND tc.constraint_type='CHECK' AND tc.enforced='YES' AND (
       (tc.constraint_name='ck_committed_official_notice_recipient' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,CONCAT(CHAR(92 USING utf8mb4),CHAR(39 USING utf8mb4)),CHAR(39 USING utf8mb4)),'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='recipient>0andim_intent_id>0')
       OR (tc.constraint_name='ck_committed_official_notice_state' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,CONCAT(CHAR(92 USING utf8mb4),CHAR(39 USING utf8mb4)),CHAR(39 USING utf8mb4)),'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='wx_statein''pending'',''unknown'',''accepted''')
       OR (tc.constraint_name='ck_committed_official_notice_owner' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,CONCAT(CHAR(92 USING utf8mb4),CHAR(39 USING utf8mb4)),CHAR(39 USING utf8mb4)),'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='wx_state=''pending''andowner_tokenisnullandattempted_atisnullandaccepted_atisnullorwx_state=''unknown''andowner_tokenisnotnullandattempted_atisnotnullandaccepted_atisnullorwx_state=''accepted''andowner_tokenisnotnullandattempted_atisnotnullandaccepted_atisnotnull')))=3
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND constraint_name='ck_committed_official_notice_typed')=0
 AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND index_name='idx_committed_official_notice_typed_pending')=0,
 1,JSON_EXTRACT('ASSERT_FAILED_before_start_schema_preflight','$')) AS UNSIGNED) AS assertion_ok;

-- runner: assert=scalar-true
SELECT CAST(IF(
 NOT EXISTS (SELECT 1 FROM committed_official_notice_wx WHERE event_key LIKE 'before-start:%')
 AND NOT EXISTS (SELECT 1 FROM committed_official_notice_wx
      WHERE recipient IS NULL OR recipient<=0 OR im_intent_id IS NULL OR im_intent_id<=0
         OR request_hash NOT REGEXP '^[0-9a-f]{64}$'
         OR event_key NOT REGEXP '^[A-Za-z0-9:_-]{1,96}$'
         OR NOT ((wx_state='PENDING' AND owner_token IS NULL AND attempted_at IS NULL AND accepted_at IS NULL)
              OR (wx_state='UNKNOWN' AND owner_token IS NOT NULL AND owner_token<>'' AND attempted_at IS NOT NULL AND accepted_at IS NULL)
              OR (wx_state='ACCEPTED' AND owner_token IS NOT NULL AND owner_token<>'' AND attempted_at IS NOT NULL AND accepted_at IS NOT NULL))),
 1,JSON_EXTRACT('ASSERT_FAILED_before_start_legacy_preflight','$')) AS UNSIGNED) AS assertion_ok;

ALTER TABLE committed_official_notice_wx
    ADD COLUMN notice_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OFFICIAL_BROADCAST',
    ADD COLUMN wx_payload_json MEDIUMTEXT NULL,
    MODIFY COLUMN im_intent_id BIGINT NULL,
    DROP CHECK ck_committed_official_notice_recipient,
    ADD CONSTRAINT ck_committed_official_notice_typed CHECK (
        recipient > 0 AND (
            (notice_type='OFFICIAL_BROADCAST' AND im_intent_id IS NOT NULL AND im_intent_id > 0 AND wx_payload_json IS NULL)
            OR (notice_type='BEFORE_START' AND im_intent_id IS NULL AND wx_payload_json IS NOT NULL AND JSON_VALID(wx_payload_json))
        )
    ),
    ADD KEY idx_committed_official_notice_typed_pending (notice_type,wx_state,id);
-- Original PENDING/UNKNOWN/ACCEPTED checks, event identity and owner-token CAS are unchanged.
-- No backfill, deletion, job enabling, UNKNOWN reset or external action.
-- Rollback: stop producers/dispatch first. Once BEFORE_START rows exist, retain this schema and typed owner;
-- do not drop payload/identity, manufacture IM ids, or run an old dispatcher over these rows.

-- runner: assert=scalar-true
SELECT CAST(IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx')=14
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND (
       (column_name='notice_type' AND data_type='varchar' AND character_maximum_length=32
        AND collation_name='ascii_bin' AND is_nullable='NO' AND column_default='OFFICIAL_BROADCAST')
       OR (column_name='wx_payload_json' AND data_type='mediumtext' AND is_nullable='YES')
       OR (column_name='im_intent_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND column_type NOT LIKE '%zerofill%' AND is_nullable='YES')))=3
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND constraint_name='ck_committed_official_notice_recipient')=0
 AND (SELECT COUNT(*) FROM information_schema.table_constraints tc
      JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
      WHERE tc.constraint_schema=DATABASE() AND tc.table_name='committed_official_notice_wx'
      AND tc.constraint_type='CHECK' AND tc.enforced='YES' AND tc.constraint_name='ck_committed_official_notice_typed'
      AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,CONCAT(CHAR(92 USING utf8mb4),CHAR(39 USING utf8mb4)),CHAR(39 USING utf8mb4)),'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='recipient>0andnotice_type=''official_broadcast''andim_intent_idisnotnullandim_intent_id>0andwx_payload_jsonisnullornotice_type=''before_start''andim_intent_idisnullandwx_payload_jsonisnotnullandjson_validwx_payload_json')=1
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND constraint_type='CHECK' AND enforced='YES'
      AND constraint_name IN ('ck_committed_official_notice_typed','ck_committed_official_notice_state','ck_committed_official_notice_owner'))=3
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='committed_official_notice_wx'
      AND index_name='idx_committed_official_notice_typed_pending' AND non_unique=1)='notice_type,wx_state,id'
 AND NOT EXISTS (SELECT 1 FROM committed_official_notice_wx
      WHERE notice_type<>'OFFICIAL_BROADCAST' OR wx_payload_json IS NOT NULL OR im_intent_id IS NULL OR im_intent_id<=0),
 1,JSON_EXTRACT('ASSERT_FAILED_before_start_typed_readback','$')) AS UNSIGNED) AS assertion_ok;
