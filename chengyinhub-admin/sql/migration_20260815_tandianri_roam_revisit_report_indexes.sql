-- 探店日 5-5 复访只读报表事实时间范围查询索引。
-- 纯性能 DDL；独立幂等执行，不修改既有 migration。
-- 生产执行前后必须按 chengyin-db-migrate 完成只读预检与索引列序回读。

-- 同名索引只有列序精确为 started_at 才可跳过；同名错形会再次 ADD 并以 1061 fail-closed。
SET @roam_session_started_at_shape := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'roam_session'
      AND index_name = 'idx_roam_session_started_at'
);
SET @roam_session_started_at_ddl := IF(
    @roam_session_started_at_shape IS NULL OR @roam_session_started_at_shape <> 'started_at',
    'ALTER TABLE roam_session ADD INDEX idx_roam_session_started_at (started_at)',
    'SELECT ''idx_roam_session_started_at already exists, skip''');
PREPARE roam_session_started_at_stmt FROM @roam_session_started_at_ddl;
EXECUTE roam_session_started_at_stmt;
DEALLOCATE PREPARE roam_session_started_at_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
    SELECT index_name
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'roam_session'
      AND index_name = 'idx_roam_session_started_at'
    GROUP BY index_name
    HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'started_at'
) verified_roam_session_index;

-- 同名索引只有列序精确为 visited_at 才可跳过；同名错形会再次 ADD 并以 1061 fail-closed。
SET @roam_shop_visit_visited_at_shape := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'roam_shop_visit'
      AND index_name = 'idx_roam_shop_visit_visited_at'
);
SET @roam_shop_visit_visited_at_ddl := IF(
    @roam_shop_visit_visited_at_shape IS NULL OR @roam_shop_visit_visited_at_shape <> 'visited_at',
    'ALTER TABLE roam_shop_visit ADD INDEX idx_roam_shop_visit_visited_at (visited_at)',
    'SELECT ''idx_roam_shop_visit_visited_at already exists, skip''');
PREPARE roam_shop_visit_visited_at_stmt FROM @roam_shop_visit_visited_at_ddl;
EXECUTE roam_shop_visit_visited_at_stmt;
DEALLOCATE PREPARE roam_shop_visit_visited_at_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
    SELECT index_name
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'roam_shop_visit'
      AND index_name = 'idx_roam_shop_visit_visited_at'
    GROUP BY index_name
    HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'visited_at'
) verified_roam_shop_visit_index;
