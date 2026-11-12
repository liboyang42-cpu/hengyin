-- D90: cms_template_library.status is the only market-state source of truth.
-- 0 pending, 1 live, 2 rejected/withdrawn, 3 approved-offline.
-- Personal cms_member_template lifecycle and immutable audit records are NOT changed.
-- Clean cutover: pause template writes and retire the old jar before dropping publish_status.
SELECT id, status FROM cms_template_library WHERE status IS NOT NULL AND status NOT IN (0,1,2,3) ORDER BY id;
SET @template_states_valid = (SELECT COUNT(*)=0 FROM cms_template_library WHERE status IS NOT NULL AND status NOT IN (0,1,2,3));
SET @template_publish_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='cms_template_library' AND column_name='publish_status');
SET @template_state_sql = IF(@template_states_valid AND @template_publish_exists=1,
 'UPDATE cms_template_library SET status=CASE WHEN status=1 AND IFNULL(publish_status,0)<>1 THEN 3 ELSE IFNULL(status,0) END', 'SELECT 1');
PREPARE template_state_stmt FROM @template_state_sql;
EXECUTE template_state_stmt;
DEALLOCATE PREPARE template_state_stmt;

-- Mutable appeal-task snapshots must retain their original live/offline distinction.
-- Do not rewrite personal preTakedown.template or immutable audit-record snapshots.
UPDATE audit_task task JOIN (
 SELECT source.id, JSON_ARRAYAGG(JSON_REMOVE(JSON_SET(library.entry, '$.status',
  CASE WHEN JSON_CONTAINS_PATH(library.entry,'one','$.publishStatus')
    AND JSON_UNQUOTE(JSON_EXTRACT(library.entry,'$.status'))='1'
    AND IFNULL(JSON_UNQUOTE(JSON_EXTRACT(library.entry,'$.publishStatus')),'0')<>'1'
   THEN 3 ELSE CAST(JSON_UNQUOTE(JSON_EXTRACT(library.entry,'$.status')) AS UNSIGNED) END), '$.publishStatus')) AS libraries
 FROM audit_task source
 JOIN JSON_TABLE(IF(JSON_VALID(source.snapshot_json),source.snapshot_json,JSON_OBJECT()),
  '$.preTakedown.libraries[*]' COLUMNS(entry JSON PATH '$')) library
 WHERE JSON_VALID(source.snapshot_json)
  AND JSON_TYPE(JSON_EXTRACT(source.snapshot_json,'$.preTakedown.libraries'))='ARRAY'
 GROUP BY source.id
) canonical ON canonical.id=task.id
SET task.snapshot_json=JSON_SET(task.snapshot_json,'$.preTakedown.libraries',canonical.libraries)
WHERE @template_states_valid;

SET @template_state_sql = IF(@template_states_valid AND @template_publish_exists=1,
 'ALTER TABLE cms_template_library DROP COLUMN publish_status', 'SELECT 1');
PREPARE template_state_stmt FROM @template_state_sql;
EXECUTE template_state_stmt;
DEALLOCATE PREPARE template_state_stmt;
SET @template_state_sql = IF(@template_states_valid,
 'ALTER TABLE cms_template_library MODIFY COLUMN status BIGINT DEFAULT 0 COMMENT ''D90 market state 0 pending 1 live 2 rejected 3 approved-offline''',
 'SELECT ''BLOCKED: unknown template status; preview above requires owner decision''');
PREPARE template_state_stmt FROM @template_state_sql;
EXECUTE template_state_stmt;
DEALLOCATE PREPARE template_state_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*)=0 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND column_name='publish_status')
 AND (SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND column_name='status'
 AND column_comment='D90 market state 0 pending 1 live 2 rejected 3 approved-offline')
 AND (SELECT COUNT(*)=0 FROM cms_template_library WHERE status IS NULL OR status NOT IN (0,1,2,3));
