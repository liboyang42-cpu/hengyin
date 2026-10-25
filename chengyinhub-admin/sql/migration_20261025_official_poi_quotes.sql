-- 2026-10-25 官方地点公开引述:注明出处的报道 / 游记摘录(JSON 数组 [{text,source,author,date,url}]),与玩家评价分开展示。
-- 同时加照片来源与许可署名 photo_credit(CC BY 等许可要求注明作者与许可)。
-- 可重复执行;只加可空列,不改既有数据。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi' AND column_name='quotes_json')=0, 'ALTER TABLE roam_poi ADD COLUMN quotes_json TEXT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi' AND column_name='photo_credit')=0, 'ALTER TABLE roam_poi ADD COLUMN photo_credit TEXT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
