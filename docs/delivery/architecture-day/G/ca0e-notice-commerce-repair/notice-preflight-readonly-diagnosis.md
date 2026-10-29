# 原通知前检只读诊断语句（历史文档）

本附件记录已完成aab91b9诊断的SELECT元数据语句，不是迁移入口，不由runner执行；实际fixture保留同一诊断及原异常。

```sql
-- READ ONLY. Coordinator's one approved isolated D-only scratch diagnosis.
-- Run AFTER original D migration and BEFORE original C migration in the SAME selected scratch schema.
-- This is not a replacement migration, not a bypass, and must never be registered as assertion success.
-- All nine predicates below are copied unchanged from original C statement #1.
SELECT VERSION() AS mysql_version, DATABASE() AS selected_schema, @@SESSION.sql_mode AS sql_mode;

SELECT
 (DATABASE() IS NOT NULL) AS database_selected,
 ((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND table_type='BASE TABLE' AND engine='InnoDB')=1) AS canonical_innodb_table,
 ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx')=12) AS canonical_12_columns,
 ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND column_name IN ('notice_type','wx_payload_json'))=0) AS typed_columns_not_yet_present,
 ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
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
       OR (column_name='accepted_at' AND data_type='datetime' AND datetime_precision=3 AND is_nullable='YES')))=12) AS all_12_column_shapes,
 ((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND constraint_type='CHECK')=3) AS exactly_3_checks,
 ((SELECT COUNT(*) FROM information_schema.table_constraints tc
      JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
      WHERE tc.constraint_schema=DATABASE() AND tc.table_name='committed_official_notice_wx'
      AND tc.constraint_type='CHECK' AND tc.enforced='YES' AND (
       (tc.constraint_name='ck_committed_official_notice_recipient' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='recipient>0andim_intent_id>0')
       OR (tc.constraint_name='ck_committed_official_notice_state' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='wx_statein''pending'',''unknown'',''accepted''')
       OR (tc.constraint_name='ck_committed_official_notice_owner' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='wx_state=''pending''andowner_tokenisnullandattempted_atisnullandaccepted_atisnullorwx_state=''unknown''andowner_tokenisnotnullandattempted_atisnotnullandaccepted_atisnullorwx_state=''accepted''andowner_tokenisnotnullandattempted_atisnotnullandaccepted_atisnotnull')))=3) AS all_3_enforced_exact_check_texts,
 ((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND constraint_name='ck_committed_official_notice_typed')=0) AS typed_constraint_name_not_reserved,
 ((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='committed_official_notice_wx' AND index_name='idx_committed_official_notice_typed_pending')=0) AS typed_index_name_not_reserved;

SHOW CREATE TABLE committed_official_notice_wx;

SELECT column_name,data_type,column_type,is_nullable,character_maximum_length,
       collation_name,column_default,extra,datetime_precision
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='committed_official_notice_wx'
ORDER BY ordinal_position;

SELECT tc.constraint_name,tc.constraint_type,tc.enforced,cc.check_clause,
       LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,
         '_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]','')) AS original_guard_normalized
FROM information_schema.table_constraints tc
LEFT JOIN information_schema.check_constraints cc
  ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
WHERE tc.constraint_schema=DATABASE() AND tc.table_name='committed_official_notice_wx'
ORDER BY tc.constraint_name;

SELECT index_name,non_unique,seq_in_index,column_name
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='committed_official_notice_wx'
ORDER BY index_name,seq_in_index;
```
