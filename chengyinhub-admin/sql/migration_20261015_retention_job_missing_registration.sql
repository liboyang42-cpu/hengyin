-- F-L5C-17: the historical job seed is included in the schema snapshot manifest,
-- but its sys_job data is absent from the snapshot. Register a missing job paused.
-- Keep the historical migration and every existing definition/status/schedule unchanged.
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_retention_job_guard;
DROP TEMPORARY TABLE IF EXISTS tmp_cy_retention_job_rows;
CREATE TEMPORARY TABLE tmp_cy_retention_job_guard(id INT PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE tmp_cy_retention_job_rows(
 job_id BIGINT PRIMARY KEY,
 invoke_target VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
) ENGINE=InnoDB;
INSERT INTO tmp_cy_retention_job_guard(id) VALUES(1);
-- Range locks must protect an empty registry against concurrent inserts too.
INSERT INTO tmp_cy_retention_job_guard(id)
SELECT 1 WHERE @@transaction_isolation NOT IN ('REPEATABLE-READ','SERIALIZABLE');
-- Capture current definitions under row/range locks, not an older MVCC snapshot.
INSERT INTO tmp_cy_retention_job_rows(job_id,invoke_target)
SELECT job_id,invoke_target FROM sys_job ORDER BY job_id FOR UPDATE;

-- CanonicalJobTarget maps the bean and exact class to the same no-arg run method.
-- Java trim accepts only ASCII U+0000..U+0020, not Unicode spaces such as NBSP.
-- JobInvokeUtil trims the receiver/method and accepts omitted or empty parentheses.
-- Unknown argument/suffix forms must be reviewed, never treated as a missing job.
INSERT INTO tmp_cy_retention_job_guard(id)
SELECT 1 WHERE EXISTS (SELECT 1 FROM tmp_cy_retention_job_rows
 WHERE REGEXP_LIKE(invoke_target,
 '^[[:space:]]*(dataRetentionCleanupTask|com[.]chengyinhub[.]business[.]task[.]DataRetentionCleanupTask)[[:space:]]*[.][[:space:]]*run([[:space:]]*[(].*|[[:space:]]*)$', 'cn')
 AND NOT REGEXP_LIKE(invoke_target,
 CONVERT(CONCAT('^', '[', CHAR(0), '-', CHAR(32), ']', '*(dataRetentionCleanupTask|com[.]chengyinhub[.]business[.]task[.]DataRetentionCleanupTask)', '[', CHAR(0), '-', CHAR(32), ']', '*[.]', '[', CHAR(0), '-', CHAR(32), ']', '*run', '[', CHAR(0), '-', CHAR(32), ']', '*([(]', '[', CHAR(0), '-', CHAR(32), ']', '*[)])?', '[', CHAR(0), '-', CHAR(32), ']', '*$') USING utf8mb4) COLLATE utf8mb4_bin, 'c'));
-- Reject canonical duplicates across groups and statuses before any persistent insert.
INSERT INTO tmp_cy_retention_job_guard(id)
SELECT 1 WHERE (SELECT COUNT(*) FROM tmp_cy_retention_job_rows
 WHERE REGEXP_LIKE(invoke_target,
 CONVERT(CONCAT('^', '[', CHAR(0), '-', CHAR(32), ']', '*(dataRetentionCleanupTask|com[.]chengyinhub[.]business[.]task[.]DataRetentionCleanupTask)', '[', CHAR(0), '-', CHAR(32), ']', '*[.]', '[', CHAR(0), '-', CHAR(32), ']', '*run', '[', CHAR(0), '-', CHAR(32), ']', '*([(]', '[', CHAR(0), '-', CHAR(32), ']', '*[)])?', '[', CHAR(0), '-', CHAR(32), ']', '*$') USING utf8mb4) COLLATE utf8mb4_bin, 'c'))>1;

INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '数据留存清理','COMPLIANCE','dataRetentionCleanupTask.run()','0 30 3 * * ?',
 '3','1','1','system',NOW(),'仅补缺失登记且默认暂停；核对隐私指引、留存配置与候选后再由责任人启用'
WHERE NOT EXISTS (SELECT 1 FROM tmp_cy_retention_job_rows
 WHERE REGEXP_LIKE(invoke_target,
 CONVERT(CONCAT('^', '[', CHAR(0), '-', CHAR(32), ']', '*(dataRetentionCleanupTask|com[.]chengyinhub[.]business[.]task[.]DataRetentionCleanupTask)', '[', CHAR(0), '-', CHAR(32), ']', '*[.]', '[', CHAR(0), '-', CHAR(32), ']', '*run', '[', CHAR(0), '-', CHAR(32), ']', '*([(]', '[', CHAR(0), '-', CHAR(32), ']', '*[)])?', '[', CHAR(0), '-', CHAR(32), ']', '*$') USING utf8mb4) COLLATE utf8mb4_bin, 'c'));
-- Validate the current result with a locking INSERT/SELECT before commit.
DROP TEMPORARY TABLE IF EXISTS tmp_cy_retention_job_result;
CREATE TEMPORARY TABLE tmp_cy_retention_job_result(job_id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_retention_job_result(job_id)
SELECT job_id FROM sys_job WHERE REGEXP_LIKE(invoke_target,
 CONVERT(CONCAT('^', '[', CHAR(0), '-', CHAR(32), ']', '*(dataRetentionCleanupTask|com[.]chengyinhub[.]business[.]task[.]DataRetentionCleanupTask)', '[', CHAR(0), '-', CHAR(32), ']', '*[.]', '[', CHAR(0), '-', CHAR(32), ']', '*run', '[', CHAR(0), '-', CHAR(32), ']', '*([(]', '[', CHAR(0), '-', CHAR(32), ']', '*[)])?', '[', CHAR(0), '-', CHAR(32), ']', '*$') USING utf8mb4) COLLATE utf8mb4_bin, 'c') FOR UPDATE;
INSERT INTO tmp_cy_retention_job_guard(id)
SELECT 1 WHERE (SELECT COUNT(*) FROM tmp_cy_retention_job_result)<>1;
DROP TEMPORARY TABLE tmp_cy_retention_job_result;
DROP TEMPORARY TABLE tmp_cy_retention_job_rows;
DROP TEMPORARY TABLE tmp_cy_retention_job_guard;
