-- 举报单独立开放槽（AUDIT-INT-5，owner 2026-10-20 批准 DDL）。
-- 改 audit_task.open_guard（STORED 生成列）的表达式：主题/玩法/模板广场/活动（TOPIC/TEMPLATE/TEMPLATE_LIBRARY/ACTIVITY）
-- 的未结举报单（source_type='report'）取 2，其余未结单取 1，终态 NULL。其余业务类型举报仍与审核单共用一个槽（行为不变）。
-- 类型清单须与 AuditTask.REPORT_LANE_BIZ_TYPES 一致。
-- uk_audit_biz_open(biz_type, biz_id, open_guard) 不动 ⇒ 同一内容可同时有一张未结举报单 + 一张未结内容审核单，
-- 两类各自仍只允许一张。此前举报单与内容审核单共用一个槽：一张举报就挡住作者编辑/重提，举报也被并进内容审核单。
-- 前置：audit_task 在 sql/base_schema_20260818.sql（open_guard 由 migration_audit_open_task_guard_20260721.sql 引入）。
-- 落库顺序：先手工跑本文件并通过末尾断言，再部署新 jar（新 jar 才会给举报单单独建单）。
-- 执行代价：修改 STORED 生成列表达式需 ALGORITHM=COPY（整表重建，期间阻塞写入）；audit_task 体量小，择低峰执行。
-- 存量行：重建时按新表达式重算；改前每个 (biz_type, biz_id) 至多一张未结单，重算后不可能撞键。
-- 幂等：表达式已是新形状则跳过；既不是旧形状也不是新形状（被人改过）则 fail-closed，不覆盖。
DROP TEMPORARY TABLE IF EXISTS audit_report_lane_20261020_guard;
CREATE TEMPORARY TABLE audit_report_lane_20261020_guard(id TINYINT PRIMARY KEY);
INSERT INTO audit_report_lane_20261020_guard VALUES(1);

-- audit_task 必须存在且为 InnoDB，open_guard 必须是 STORED 生成列。
INSERT INTO audit_report_lane_20261020_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='audit_task' AND column_name='open_guard'
    AND extra='STORED GENERATED' AND data_type='tinyint') <> 1
  OR (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='audit_task' AND engine='InnoDB') <> 1;

-- uk_audit_biz_open 必须正是 (biz_type, biz_id, open_guard) 的唯一键。
INSERT INTO audit_report_lane_20261020_guard
SELECT 1 WHERE (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='audit_task' AND index_name='uk_audit_biz_open' AND non_unique=0)
  <=> 'biz_type,biz_id,open_guard' IS NOT TRUE;

SET @audit_report_lane_expr := (SELECT generation_expression FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='audit_task' AND column_name='open_guard');
SET @audit_report_lane_new := LOCATE('report', @audit_report_lane_expr) > 0;
SET @audit_report_lane_old := REPLACE(REPLACE(@audit_report_lane_expr, CHAR(96), ''), ' ', '')
  = '(casewhen(statusin(0,3))then1elseNULLend)';

-- 既不是旧形状也不是本迁移的新形状：停。
INSERT INTO audit_report_lane_20261020_guard
SELECT 1 WHERE NOT (@audit_report_lane_new OR @audit_report_lane_old);

SET @audit_report_lane_ddl := IF(@audit_report_lane_new,
  'SELECT 1',
  'ALTER TABLE audit_task MODIFY COLUMN open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (0, 3) THEN (CASE WHEN source_type = ''report'' AND biz_type IN (''TOPIC'', ''TEMPLATE'', ''TEMPLATE_LIBRARY'', ''ACTIVITY'') THEN 2 ELSE 1 END) ELSE NULL END) STORED COMMENT ''未结任务唯一闸：未结举报单2、其余未结单1、终态NULL'', ALGORITHM=COPY'
);
PREPARE audit_report_lane_stmt FROM @audit_report_lane_ddl;
EXECUTE audit_report_lane_stmt;
DEALLOCATE PREPARE audit_report_lane_stmt;

DROP TEMPORARY TABLE audit_report_lane_20261020_guard;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='audit_task' AND column_name='open_guard'
          AND extra='STORED GENERATED' AND LOCATE('report', generation_expression) > 0) = 1
   AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='audit_task' AND index_name='uk_audit_biz_open'
          AND non_unique=0) = 'biz_type,biz_id,open_guard'
   AND (SELECT COUNT(*) FROM audit_task WHERE status IN (0, 3) AND source_type = 'report'
        AND biz_type IN ('TOPIC', 'TEMPLATE', 'TEMPLATE_LIBRARY', 'ACTIVITY') AND open_guard <> 2) = 0
   AND (SELECT COUNT(*) FROM audit_task WHERE status IN (0, 3)
        AND NOT (source_type <=> 'report' AND biz_type IN ('TOPIC', 'TEMPLATE', 'TEMPLATE_LIBRARY', 'ACTIVITY'))
        AND open_guard <> 1) = 0
  AS assertion_ok;
