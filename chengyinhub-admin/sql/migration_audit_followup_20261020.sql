-- 审核跟进（FOLLOWUP-C-2 / FOLLOWUP-C-3，owner 2026-10-20 批准 DDL）。只新增列，不删、不改、不回填既有数据。
-- community_post_appeal.due_at     申诉处理时限 = 提交时刻 + 举报首响 SLA（标准 24 小时；紧急原因 15 分钟），存量行保持 NULL
-- audit_record.reason_codes_json   审核决定的固定清单原因编码 JSON 数组（AuditReasonCodes），存量行保持 NULL
-- 为什么不复用 audit_record.reason_code：varchar(60) 只够一个编码，全清单拼接超过 100 字符；
-- 改宽它会让 VARCHAR 长度字节从 1 变 2，触发整表 COPY 重建，新增可空 JSON 列可以 INSTANT。
-- 前置：community_post_appeal 来自 community_post_platform_20260909；audit_record 来自 p3_platform_foundation。
-- 落库顺序：#385 的六条迁移之后第 7 条；先手工跑本文件并通过末尾断言，再部署新 jar（新 jar 的 Mapper 会读写这两列）。
-- 幂等：每条 ALTER 前查 information_schema；同名但形状不符的列 fail-closed，不替换、不删除。
DROP TEMPORARY TABLE IF EXISTS audit_followup_20261020_guard;
CREATE TEMPORARY TABLE audit_followup_20261020_guard(id TINYINT PRIMARY KEY);
INSERT INTO audit_followup_20261020_guard VALUES(1);

-- 两张目标表必须存在且为 InnoDB；否则下一条 INSERT 撞主键，整个文件停在这里。
INSERT INTO audit_followup_20261020_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name IN ('community_post_appeal','audit_record') AND engine='InnoDB') <> 2;

-- 已存在的同名列必须正是本迁移要的形状；不符就停，不当成已完成。
INSERT INTO audit_followup_20261020_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='community_post_appeal' AND column_name='due_at'
    AND NOT (data_type='datetime' AND is_nullable='YES' AND column_default IS NULL));
INSERT INTO audit_followup_20261020_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='audit_record' AND column_name='reason_codes_json'
    AND NOT (data_type='json' AND is_nullable='YES' AND column_default IS NULL));

SET @appeal_due_at_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_post_appeal' AND column_name='due_at'),
  'SELECT 1',
  'ALTER TABLE community_post_appeal ADD COLUMN due_at DATETIME NULL COMMENT ''申诉处理时限(举报首响 SLA 同口径)'', ALGORITHM=INSTANT'
);
PREPARE appeal_due_at_stmt FROM @appeal_due_at_ddl;
EXECUTE appeal_due_at_stmt;
DEALLOCATE PREPARE appeal_due_at_stmt;

SET @record_reason_codes_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='audit_record' AND column_name='reason_codes_json'),
  'SELECT 1',
  'ALTER TABLE audit_record ADD COLUMN reason_codes_json JSON NULL COMMENT ''固定清单原因编码 JSON 数组(AuditReasonCodes)'', ALGORITHM=INSTANT'
);
PREPARE record_reason_codes_stmt FROM @record_reason_codes_ddl;
EXECUTE record_reason_codes_stmt;
DEALLOCATE PREPARE record_reason_codes_stmt;

DROP TEMPORARY TABLE audit_followup_20261020_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=2 AS assertion_ok
FROM information_schema.columns
WHERE table_schema=DATABASE()
  AND ((table_name='community_post_appeal' AND column_name='due_at' AND data_type='datetime')
    OR (table_name='audit_record' AND column_name='reason_codes_json' AND data_type='json'))
  AND is_nullable='YES' AND column_default IS NULL;
