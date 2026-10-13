-- P0 合规:订单收货信息 PII 加密(《隐私政策》§6.3)。
-- 加宽 receiver_name/receiver_phone/receiver_detail_address 容纳 AES-GCM 密文 + 加手机号盲索引 receiver_phone_hash。
-- receiver_province(省)/receiver_phone_code(区号)属粗粒度,不加密不索引。
-- ★执行顺序:schema 迁移,须先于 jar 部署。历史明文回填走 piiBackfillTask.run()(报名+订单同任务)。
-- ⚠️ 密钥同报名:chengyin.pii.key / chengyin.pii.hmac-key。幂等:MODIFY 可重复;列/索引用存储过程守卫。

-- 列宽:密文 base64 = 3 + ceil((明文字节+28)/3)*4。原列(实测生产库)receiver_name VARCHAR(100)、receiver_phone VARCHAR(32)、
--       receiver_detail_address VARCHAR(200);按 utf8mb4 最坏 4 字节/字算,密文最长依次 575/211/1107。加宽留余量消除 1406 写失败:
--       receiver_name→768,receiver_phone→256,receiver_detail_address→1280。
ALTER TABLE oms_order MODIFY COLUMN receiver_name           VARCHAR(768)  NULL COMMENT '收货人姓名(AES-GCM密文)';
ALTER TABLE oms_order MODIFY COLUMN receiver_phone          VARCHAR(256)  NULL COMMENT '收货人手机(AES-GCM密文)';
ALTER TABLE oms_order MODIFY COLUMN receiver_detail_address VARCHAR(1280) NULL COMMENT '收货详细地址(AES-GCM密文)';

-- 幂等加盲索引列 + 索引:prepared-statement 守卫(兼容 run_migration.py 按 ; 切分;不用 DELIMITER 存储过程,同报名迁移)。
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE table_schema = DATABASE() AND table_name = 'oms_order' AND column_name = 'receiver_phone_hash');
SET @sql := IF(@has_col = 0,
    'ALTER TABLE oms_order ADD COLUMN receiver_phone_hash CHAR(64) NULL COMMENT ''收货手机HMAC盲索引'' AFTER receiver_phone',
    'SELECT ''receiver_phone_hash 已存在,跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE table_schema = DATABASE() AND table_name = 'oms_order' AND index_name = 'idx_order_receiver_phone_hash');
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE oms_order ADD INDEX idx_order_receiver_phone_hash (receiver_phone_hash)',
    'SELECT ''idx_order_receiver_phone_hash 已存在,跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
