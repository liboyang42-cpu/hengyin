-- P0 合规:报名信息 PII 加密(《隐私政策》§6.3)。
-- 加宽 real_name/phone/email/address 以容纳 AES-GCM 密文(v1:base64...) + 加手机号盲索引列 phone_hash。
-- ★执行顺序:schema 迁移,须先于 jar 部署(jar 起来后新写入即密文,需列宽足够)。
-- ★历史明文加密:jar 部署后运行 piiBackfillTask.run() 分批回填(见 migration_pii_backfill_job.sql)。
-- ⚠️ 密钥:jar 部署前【必须】在 application.yml 配 chengyin.pii.key(Base64 32字节)+ chengyin.pii.hmac-key;
--    ★未配则报名/收货 PII 写入被 fail-closed 拒绝(TypeHandler 改动),不会明文落库——务必先配再放量。
-- 幂等:MODIFY 可重复;phone_hash 列/索引用存储过程守卫,可重复执行。
-- 列宽:AES-GCM 密文 base64 长度 = 3 + ceil((明文字节+28)/3)*4。原列(实测生产库)real_name/email/address 均 VARCHAR(100)、
--       phone VARCHAR(20);按 utf8mb4 最坏 4 字节/字算,100 字→密文最长 575、20 字→147。统一加宽留余量,消除长值 1406 写失败:
--       real_name/email/address→768(容 575),phone→256(容 147)。
ALTER TABLE cms_registration MODIFY COLUMN real_name VARCHAR(768) NULL COMMENT '真实姓名(AES-GCM密文)';
ALTER TABLE cms_registration MODIFY COLUMN phone     VARCHAR(256) NULL COMMENT '手机号(AES-GCM密文)';
ALTER TABLE cms_registration MODIFY COLUMN email     VARCHAR(768) NULL COMMENT '邮箱(AES-GCM密文)';
ALTER TABLE cms_registration MODIFY COLUMN address   VARCHAR(768) NULL COMMENT '地址(AES-GCM密文)';

-- 幂等加盲索引列 + 索引:用 prepared-statement 守卫(兼容 run_migration.py 按 ; 切分逐条执行;不用 DELIMITER 存储过程,
-- 否则脚本按 ; 裸切会把过程体切碎报语法错——与 migration_withdrawal_pii_encryption 同款写法)。
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND column_name = 'phone_hash');
SET @sql := IF(@has_col = 0,
    'ALTER TABLE cms_registration ADD COLUMN phone_hash CHAR(64) NULL COMMENT ''手机号HMAC盲索引(加密后精确检索)'' AFTER phone',
    'SELECT ''phone_hash 已存在,跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND index_name = 'idx_reg_phone_hash');
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE cms_registration ADD INDEX idx_reg_phone_hash (phone_hash)',
    'SELECT ''idx_reg_phone_hash 已存在,跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
