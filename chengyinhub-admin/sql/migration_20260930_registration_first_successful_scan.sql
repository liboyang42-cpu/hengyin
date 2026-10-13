-- ORDER-01：首次合法扫码成功即订单展示完成；核销与履约结算事实保持独立。
-- 存量不按 GPS/到店时间/通关或埋点回填；首次时间只由合法扫码事务写入且不可覆盖。
-- 部署执行前须完成只读预检并取得用户确认；本文件不连接生产数据库。
SET @registration_first_scan_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'cms_registration'
       AND column_name = 'first_successful_scan_at'
);
SET @registration_first_scan_sql := IF(
    @registration_first_scan_exists = 0,
    'ALTER TABLE cms_registration ADD COLUMN first_successful_scan_at DATETIME NULL COMMENT ''本订单首次合法扫码成功时间，与核销独立'' AFTER verification_time',
    'SELECT ''cms_registration.first_successful_scan_at already exists'''
);
PREPARE registration_first_scan_stmt FROM @registration_first_scan_sql;
EXECUTE registration_first_scan_stmt;
DEALLOCATE PREPARE registration_first_scan_stmt;
