-- BE-03 幂等键:cms_registration 加 request_id(前端每次结账生成的 UUID),防网络重试/双击建重单。
-- 加可空列 + (member_id, request_id) 普通索引(非唯一:历史行 request_id=NULL,多票只标主单)。对现有数据无副作用。
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)。幂等,可重复跑。
-- ⚠️ 部署顺序:先于新 jar 落库,否则 CmsRegistration/CmsRegistrationMapper 引用 request_id 会 SQL 报错。

DROP PROCEDURE IF EXISTS _add_reg_request_id;
DELIMITER //
CREATE PROCEDURE _add_reg_request_id()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_registration' AND COLUMN_NAME = 'request_id') THEN
        ALTER TABLE cms_registration
            ADD COLUMN request_id VARCHAR(64) NULL COMMENT 'BE-03 幂等键:前端结账 UUID' AFTER parent_order_no;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_registration' AND INDEX_NAME = 'idx_reg_member_request') THEN
        ALTER TABLE cms_registration ADD INDEX idx_reg_member_request (member_id, request_id);
    END IF;
END //
DELIMITER ;
CALL _add_reg_request_id();
DROP PROCEDURE IF EXISTS _add_reg_request_id;
