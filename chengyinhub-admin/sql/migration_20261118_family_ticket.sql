-- 2026-11-18 F12 亲子票(1 大 1 小,FE-G21)。
-- 票种带人数构成与儿童年龄段;商家供给带「是否接受亲子票」与儿童份说明;订单存下单时的人数构成与儿童年龄(不存姓名)。
-- 首版只允许 1+0(默认)与 1+1,由应用层 FamilyTicketRules 校验。
-- 只新增带默认值/可空的列,可重复执行;表不存在时跳过(局部建表的测试库只叠它需要的表);回滚:DROP 这七列即可,旧代码不读它们。

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'adult_count') = 0,
    'ALTER TABLE oms_ticket ADD COLUMN adult_count TINYINT NOT NULL DEFAULT 1 COMMENT ''每张票含成人数''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'child_count') = 0,
    'ALTER TABLE oms_ticket ADD COLUMN child_count TINYINT NOT NULL DEFAULT 0 COMMENT ''每张票含儿童数''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'child_age_min') = 0,
    'ALTER TABLE oms_ticket ADD COLUMN child_age_min TINYINT NULL COMMENT ''亲子票儿童最小年龄(含)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'child_age_max') = 0,
    'ALTER TABLE oms_ticket ADD COLUMN child_age_max TINYINT NULL COMMENT ''亲子票儿童最大年龄(含)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer' AND column_name = 'family_accepted') = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN family_accepted TINYINT NOT NULL DEFAULT 0 COMMENT ''是否接受亲子票(1大1小同享)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer' AND column_name = 'family_perk_text') = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN family_perk_text VARCHAR(200) NULL COMMENT ''儿童份说明，如「儿童份小杯」''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'cms_registration') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND column_name = 'party_snapshot_json') = 0,
    'ALTER TABLE cms_registration ADD COLUMN party_snapshot_json VARCHAR(500) NULL COMMENT ''下单时人数构成与儿童年龄''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
