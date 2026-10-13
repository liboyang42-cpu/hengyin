-- 4-3 ORGANIZER actor 声明审计：product_role 继续是唯一写真源，canonical 只冻结只读副本。
-- 生产库禁止直接执行；必须走 chengyin-db-migrate 的只读预检、确认、执行、schema/数据回读。

-- P0 只读预检：product_role 必须已存在；declared_by 若已存在，先核对类型再决定是否跳过。
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema = DATABASE() AND table_name = 'product_role';
-- SELECT column_name, column_type, is_nullable
--   FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'product_role' AND column_name = 'declared_by';
-- SELECT lifecycle, COUNT(*) FROM cms_topic GROUP BY lifecycle ORDER BY lifecycle;
-- SELECT COUNT(*) AS existing_sale_snapshot_count FROM topic_sale_snapshot;
-- 若 existing_sale_snapshot_count > 0：停止；本变更给 CANONICAL_15_V1 补了必需 organizerActor，
-- 不得用当前 live product_role 猜历史 actor，须逐期用原审核/合同证据人工裁定后另写迁移。

-- 【幂等】2026-08-18：原本是裸 ALTER ADD COLUMN，重跑报 1060 —— 落库中断后无法恢复。
-- 改成本仓既有范式:information_schema 预检 + PREPARE。
-- ⚠️ MySQL 8 不支持 ADD COLUMN IF NOT EXISTS(报 1064),只能这么写。
-- 没跑过的库上跑一遍的效果与改前完全一致(类型/可空/注释/位置逐字未动)。
SET @product_role_declared_by_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'product_role'
      AND column_name = 'declared_by'
);
SET @product_role_declared_by_sql := IF(
    @product_role_declared_by_exists = 0,
    'ALTER TABLE product_role ADD COLUMN declared_by BIGINT NULL COMMENT ''声明该角色槽的后台操作账号；ORGANIZER 开售前必须非空并冻结进 canonical'' AFTER status',
    'SELECT 1'
);
PREPARE product_role_declared_by_stmt FROM @product_role_declared_by_sql;
EXECUTE product_role_declared_by_stmt;
DEALLOCATE PREPARE product_role_declared_by_stmt;

-- P1 执行后 schema 回读：期望 1 行，BIGINT，可空（存量 CONTENT/历史 ORGANIZER 不静默回填）。
-- SELECT column_name, column_type, is_nullable, column_comment
--   FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'product_role' AND column_name = 'declared_by';

-- P2 数据回读：只列问题，不猜历史声明人；FREE_EXPLORE 开售闸会 fail-closed。
-- SELECT r.id, r.topic_id, r.entity_type, r.entity_id, r.declared_by, t.lifecycle
--   FROM product_role r JOIN cms_topic t ON t.id = r.topic_id
--  WHERE r.slot = 'ORGANIZER' AND r.status = 1
--  ORDER BY r.id DESC LIMIT 100;
