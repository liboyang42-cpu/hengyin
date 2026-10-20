-- AUDIT-G4 通用申诉表（P3-3）：作者对被驳回/下架的活动、主题、玩法、模板广场条目申诉一次。
-- 只新建表，不改既有表、不回填数据；community_post_appeal 保持原样。
-- 【前置】audit_task（migration_p3_platform_foundation.sql）。【落库顺序】先手工执行本迁移，再部署新 jar。
-- 幂等：表不存在则建；已存在则逐列/逐索引核对 fingerprint，不一致时 guard 主键冲突让客户端报错，不改不删。
DROP TEMPORARY TABLE IF EXISTS audit_appeal_guard;
CREATE TEMPORARY TABLE audit_appeal_guard(id TINYINT PRIMARY KEY);
INSERT INTO audit_appeal_guard VALUES(1);

-- 前置表必须存在（申诉单 task_id 指向 audit_task.id）。
INSERT INTO audit_appeal_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='audit_task' AND engine='InnoDB') <> 1;

CREATE TABLE IF NOT EXISTS audit_appeal (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  biz_type VARCHAR(40) NOT NULL COMMENT '被申诉内容类型，与 audit_task.biz_type 同口径',
  biz_id BIGINT NOT NULL COMMENT '被申诉内容ID',
  task_id BIGINT NOT NULL COMMENT '申诉生成的 audit_task.id（source_type=appeal）',
  member_id BIGINT NOT NULL COMMENT '申诉人（内容作者）会员ID',
  reason TEXT NOT NULL COMMENT '申诉理由',
  evidence_json JSON NULL COMMENT '补充材料',
  state VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/REVIEWING/ACCEPTED/REJECTED',
  due_at DATETIME NOT NULL COMMENT '处理时限',
  handled_by BIGINT NULL COMMENT '处理人 sys_user.user_id',
  handled_at DATETIME NULL COMMENT '处理时间',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_audit_appeal_biz_member (biz_type, biz_id, member_id),
  KEY idx_audit_appeal_state_due (state, due_at),
  KEY idx_audit_appeal_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用内容申诉';

-- 同名但形状不对的旧表禁止当成已完成：列 fingerprint。
SET @audit_appeal_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable,
    COALESCE(column_default, '<null>')) ORDER BY ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_appeal');
INSERT INTO audit_appeal_guard
SELECT 1 WHERE COALESCE(@audit_appeal_columns, '') NOT IN (
  CONCAT('id:bigint:NO:<null>|biz_type:varchar(40):NO:<null>|biz_id:bigint:NO:<null>|task_id:bigint:NO:<null>',
    '|member_id:bigint:NO:<null>|reason:text:NO:<null>|evidence_json:json:YES:<null>',
    '|state:varchar(16):NO:SUBMITTED|due_at:datetime:NO:<null>|handled_by:bigint:YES:<null>',
    '|handled_at:datetime:YES:<null>|created_at:datetime:NO:<null>'));

-- 索引 fingerprint：唯一键(biz_type,biz_id,member_id)、队列键(state,due_at)、任务键(task_id)。
SET @audit_appeal_indexes := (SELECT GROUP_CONCAT(CONCAT_WS(':', index_name, non_unique, seq_in_index, column_name)
    ORDER BY index_name, seq_in_index SEPARATOR '|')
  FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='audit_appeal');
INSERT INTO audit_appeal_guard
SELECT 1 WHERE COALESCE(@audit_appeal_indexes, '') <> CONCAT(
  'idx_audit_appeal_state_due:1:1:state|idx_audit_appeal_state_due:1:2:due_at',
  '|idx_audit_appeal_task:1:1:task_id|PRIMARY:0:1:id',
  '|uk_audit_appeal_biz_member:0:1:biz_type|uk_audit_appeal_biz_member:0:2:biz_id',
  '|uk_audit_appeal_biz_member:0:3:member_id');
DROP TEMPORARY TABLE audit_appeal_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=3 AS assertion_ok FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='audit_appeal'
  AND index_name='uk_audit_appeal_biz_member' AND non_unique=0;
