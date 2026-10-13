-- 普通路线使用已有公共 HOST_NODE；不虚构商家主体。商家点位仍保留实际 merchant_member_id。
-- 仅新增迁移；既有商家站点、索引及历史运行记录保持。
ALTER TABLE game_station_run MODIFY COLUMN merchant_member_id BIGINT NULL;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'game_station_run'
   AND column_name = 'merchant_member_id' AND is_nullable = 'YES' AND data_type = 'bigint';
