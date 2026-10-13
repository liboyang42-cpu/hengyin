-- 探店日 5-4：逐期漫游导流开关。默认关闭，由运营逐期显式开启。
--
-- 【幂等】2026-08-18：原本是裸 ALTER ADD COLUMN，重跑报 1060 —— 落库中断(断网/Ctrl-C/超时)后
--   无法恢复。改成本仓既有范式:information_schema 预检 + PREPARE。
--   ⚠️ MySQL 8 不支持 ADD COLUMN IF NOT EXISTS(报 1064),只能这么写。
--   没跑过的库上跑一遍的效果与改前完全一致(列定义、位置、注释逐字未动)。
SET @roam_referral_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic'
      AND column_name = 'roam_referral_enabled'
);
SET @roam_referral_sql := IF(
    @roam_referral_exists = 0,
    'ALTER TABLE cms_topic ADD COLUMN roam_referral_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''本期漫游首次发现卡导流:0关闭 1开启'' AFTER product_type',
    'SELECT 1'
);
PREPARE roam_referral_stmt FROM @roam_referral_sql;
EXECUTE roam_referral_stmt;
DEALLOCATE PREPARE roam_referral_stmt;

-- 回读合同（本文件仅准备，不在代码任务中执行）：存量与新建主题都必须默认关闭。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM cms_topic
WHERE roam_referral_enabled NOT IN (0, 1) OR roam_referral_enabled IS NULL;
