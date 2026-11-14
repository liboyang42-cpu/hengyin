-- 频道第四批（native App 合同 docs/channels/contract.md §8.1 黑名单，App 仓 ui/channels-contract）。
-- 只放宽回执族约束（新增 BLOCK = 黑名单取消/结算原回执）；不建表、不改既有行；本地/测试库候选，不在生产执行。
-- 黑名单真源仍是既有 im_block；本批不改它。
-- 依赖：20261114_im_channels_third_pass（回执族已含 MEDIA/ONBOARD）。

SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_request_receipt'
       AND constraint_name = 'ck_channel_receipt_family' AND constraint_type = 'CHECK'
);
SET @ck_ready = (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_channel_receipt_family'
       AND check_clause LIKE '%''BLOCK''%'
);
SET @ddl = IF(
    @ck_exists > 0 AND @ck_ready = 0,
    'ALTER TABLE im_channel_request_receipt DROP CHECK ck_channel_receipt_family',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_request_receipt'
       AND constraint_name = 'ck_channel_receipt_family' AND constraint_type = 'CHECK'
);
SET @ddl = IF(
    @ck_exists = 0,
    'ALTER TABLE im_channel_request_receipt ADD CONSTRAINT ck_channel_receipt_family CHECK (family IN (''META'',''GOV'',''MSG'',''FRIEND'',''FMETA'',''FREMOVE'',''FINVITE'',''FTEAM'',''MOD'',''APPEAL'',''REPORT'',''NOTIFY'',''CHAIN'',''CHECKIN'',''ADMIT'',''STATE'',''PRIVACY'',''ROLE'',''MEDIA'',''ONBOARD'',''BLOCK''))',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
