-- 举报开放槽后置校验；依赖 audit_report_lane_20261020，只读，不修改既有迁移或数据。
-- 保留 MySQL 8 生成表达式的逻辑括号；只归一空白、反引号与明确的字符集前缀。
-- 字面量另作逐项精确比较，防止归一把 're port' 等错误字面量当成 'report'。
SET @audit_report_lane_verify_expr := (SELECT generation_expression FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='audit_task' AND column_name='open_guard');
-- MySQL 的元数据渲染可用反斜线引号包裹字面量；只解码该分隔符，后续仍逐项核对字面量。
SET @audit_report_lane_verify_expr := REPLACE(@audit_report_lane_verify_expr,
  CONCAT(CHAR(92), CHAR(39)), CHAR(39));
SET @audit_report_lane_verify_normalized := REGEXP_REPLACE(
  REPLACE(REPLACE(REPLACE(@audit_report_lane_verify_expr, CHAR(96), ''),
    CONCAT('_utf8mb4', CHAR(39)), CHAR(39)), CONCAT('_utf8mb3', CHAR(39)), CHAR(39)),
  '[[:space:]]+', '');

-- runner: assert=scalar-true
SELECT COALESCE(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='audit_task' AND column_name='open_guard'
     AND data_type='tinyint' AND extra='STORED GENERATED' AND is_nullable='YES') = 1
  AND (SELECT COUNT(*) FROM information_schema.tables
       WHERE table_schema=DATABASE() AND table_name='audit_task' AND engine='InnoDB') = 1
  AND BINARY @audit_report_lane_verify_normalized = BINARY
    '(casewhen(statusin(0,3))then(casewhen((source_type=''report'')and(biz_typein(''TOPIC'',''TEMPLATE'',''TEMPLATE_LIBRARY'',''ACTIVITY'')))then2else1end)elseNULLend)'
  AND INSTR(REGEXP_REPLACE(@audit_report_lane_verify_expr,
        CONCAT(CHAR(96), '(status|source_type|biz_type)', CHAR(96)), '', 1, 0, 'c'), CHAR(96)) = 0
  AND BINARY REGEXP_SUBSTR(@audit_report_lane_verify_expr, '''[^'']*''', 1, 1) = BINARY '''report'''
  AND BINARY REGEXP_SUBSTR(@audit_report_lane_verify_expr, '''[^'']*''', 1, 2) = BINARY '''TOPIC'''
  AND BINARY REGEXP_SUBSTR(@audit_report_lane_verify_expr, '''[^'']*''', 1, 3) = BINARY '''TEMPLATE'''
  AND BINARY REGEXP_SUBSTR(@audit_report_lane_verify_expr, '''[^'']*''', 1, 4) = BINARY '''TEMPLATE_LIBRARY'''
  AND BINARY REGEXP_SUBSTR(@audit_report_lane_verify_expr, '''[^'']*''', 1, 5) = BINARY '''ACTIVITY'''
  AND REGEXP_SUBSTR(@audit_report_lane_verify_expr, '''[^'']*''', 1, 6) IS NULL
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name='audit_task' AND index_name='uk_audit_biz_open'
         AND non_unique=0) = 'biz_type,biz_id,open_guard'
  AND (SELECT COUNT(*) FROM audit_task WHERE NOT (open_guard <=>
       CASE WHEN status IN (0,3) THEN
         CASE WHEN source_type='report' AND biz_type IN ('TOPIC','TEMPLATE','TEMPLATE_LIBRARY','ACTIVITY')
              THEN 2 ELSE 1 END
       ELSE NULL END)) = 0,
  0) AS assertion_ok;
