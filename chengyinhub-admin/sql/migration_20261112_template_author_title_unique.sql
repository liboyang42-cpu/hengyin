-- D90 preserves owner 2026-10-23 D5(U-036): same author, undeleted market entries,
-- Java String.trim() then exact case-sensitive equality; NOT global title uniqueness.
-- Hex pattern is \A[\x00-\x20]+|[\x00-\x20]+\z (absolute Java trim boundaries, not Unicode whitespace).
-- Run while template writes are paused. Preview is read-only: no title is renamed/deleted.
-- If duplicates exist, print every affected row and BLOCKED, skip all DDL; protected
-- runner's final scalar assertion remains false, so this migration cannot be marked APPLIED.
SELECT library.id, library.member_id, library.title
FROM cms_template_library library JOIN (
 SELECT member_id, CAST(REGEXP_REPLACE(CONVERT(title USING utf8mb4) COLLATE utf8mb4_bin,
  CONVERT(0x5c415b5c7830302d5c7832305d2b7c5b5c7830302d5c7832305d2b5c7a USING utf8mb4) COLLATE utf8mb4_bin,'',1,0,'c') AS BINARY) AS normalized_title
 FROM cms_template_library WHERE del_flag=0 AND member_id IS NOT NULL AND title IS NOT NULL
 GROUP BY member_id, normalized_title
 HAVING OCTET_LENGTH(normalized_title)>0 AND COUNT(*)>1
) duplicates ON duplicates.member_id=library.member_id
 AND duplicates.normalized_title=CAST(REGEXP_REPLACE(CONVERT(library.title USING utf8mb4) COLLATE utf8mb4_bin,
  CONVERT(0x5c415b5c7830302d5c7832305d2b7c5b5c7830302d5c7832305d2b5c7a USING utf8mb4) COLLATE utf8mb4_bin,'',1,0,'c') AS BINARY)
WHERE library.del_flag=0 ORDER BY library.member_id, library.id;

SET @template_title_clean = (SELECT COUNT(*)=0 FROM (
 SELECT member_id, CAST(REGEXP_REPLACE(CONVERT(title USING utf8mb4) COLLATE utf8mb4_bin,
  CONVERT(0x5c415b5c7830302d5c7832305d2b7c5b5c7830302d5c7832305d2b5c7a USING utf8mb4) COLLATE utf8mb4_bin,'',1,0,'c') AS BINARY) AS normalized_title
 FROM cms_template_library WHERE del_flag=0 AND member_id IS NOT NULL AND title IS NOT NULL
 GROUP BY member_id, normalized_title
 HAVING OCTET_LENGTH(normalized_title)>0 AND COUNT(*)>1
) duplicates);
SET @template_title_column_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='cms_template_library' AND column_name='active_title_key');
SET @template_title_ddl = IF(@template_title_clean AND @template_title_column_exists=0,
 'ALTER TABLE cms_template_library ADD COLUMN active_title_key VARBINARY(1024) GENERATED ALWAYS AS
  (CASE WHEN del_flag=0 THEN NULLIF(CAST(REGEXP_REPLACE(CONVERT(title USING utf8mb4) COLLATE utf8mb4_bin,
   CONVERT(0x5c415b5c7830302d5c7832305d2b7c5b5c7830302d5c7832305d2b5c7a USING utf8mb4) COLLATE utf8mb4_bin,'''',1,0,''c'') AS BINARY),'''') ELSE NULL END) STORED',
 'SELECT ''Template title key skipped; inspect duplicate preview before retrying''');
PREPARE template_title_stmt FROM @template_title_ddl;
EXECUTE template_title_stmt;
DEALLOCATE PREPARE template_title_stmt;
SET @template_title_index_exists = (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='cms_template_library' AND index_name='uk_template_author_live_title');
SET @template_title_ddl = IF(@template_title_clean AND @template_title_index_exists=0,
 'ALTER TABLE cms_template_library ADD UNIQUE KEY uk_template_author_live_title(member_id,active_title_key)',
 'SELECT ''Template title unique index skipped; inspect readback''');
PREPARE template_title_stmt FROM @template_title_ddl;
EXECUTE template_title_stmt;
DEALLOCATE PREPARE template_title_stmt;
SELECT IF(@template_title_clean,'READY: inspect exact index readback',
 'BLOCKED: duplicate author/template titles; owner must decide renames, then rerun') AS template_title_migration_status;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND column_name='active_title_key'
 AND data_type='varbinary' AND character_maximum_length=1024 AND extra='STORED GENERATED'
 AND generation_expression LIKE '%regexp_replace%')
 AND (SELECT COUNT(*)=2 AND SUM(non_unique=0 AND
  ((seq_in_index=1 AND column_name='member_id') OR (seq_in_index=2 AND column_name='active_title_key')))=2
 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND index_name='uk_template_author_live_title');
