-- FIX-P2-26(阶段2 台账 2-26 / 阻断#3):oms_ticket 票种五字段加列。
-- 病:小程序发布页票种编辑器一直在提交
--     saleStartTime / saleEndTime / refundSupported / syncWithTheme / meetingPointAddress
--     (pages/publish/fabu/index.js saveTicket 与 pages/publish/simple buildPayload 两处入口),
--     而 oms_ticket 与 ActivityTicketRequest 都没有这些字段,BeanUtils.copyProperties 静默丢弃 ——
--     发布者填了等于没填,编辑回读恒为空(「售票开始/结束」在 UI 上还标着必填)。
--
-- 本次只做「加列 + 落库 + 回读」,不做生效逻辑:
--   · 售票开始/结束不拦购票(仍以票本身的 start_time/end_time 为准);
--   · refund_supported 不参与任何退款判定;
--   · sync_with_theme 不做主题日期同步。
--   三项等产品拍板后再另写迁移/代码,不要在本次口径上猜。
--
-- 可空性与类型参照同表现有列:sale_* 对齐 start_time/end_time(DATETIME NULL);
-- meeting_point_address 对齐 meeting_point(VARCHAR(255) NULL);
-- refund_supported / sync_with_theme 用 TINYINT(1) NULL —— 存量行不默认回填,
-- 避免给历史票伪造「支持随时退款」的承诺。
--
-- 部署顺序:本迁移必须先于新 jar 落库(OmsTicketMapper 的 select/insert/update 会引用这 5 列)。
-- 生产库禁止直接执行;必须走 chengyin-db-migrate 的只读预检、确认、执行、schema 回读。
--
-- 【幂等】2026-09-18:按本仓既有范式 information_schema 预检 + PREPARE(同
-- migration_20260815_shop_day_organizer_actor.sql):MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,
-- 裸 ALTER 重跑报 1060,落库中断后无法恢复。

-- P0 只读预检:执行前应看到 5 行 exists_cnt = 0;执行后回读应为 1 且类型/可空与下方一致。
-- SELECT COUNT(*) AS sale_start_time_exists FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'sale_start_time';
-- SELECT COUNT(*) AS sale_end_time_exists FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'sale_end_time';
-- SELECT COUNT(*) AS refund_supported_exists FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'refund_supported';
-- SELECT COUNT(*) AS sync_with_theme_exists FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'sync_with_theme';
-- SELECT COUNT(*) AS meeting_point_address_exists FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'meeting_point_address';

SET @oms_ticket_sale_start_time_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
      AND column_name = 'sale_start_time'
);
SET @oms_ticket_sale_start_time_sql := IF(
    @oms_ticket_sale_start_time_exists = 0,
    'ALTER TABLE oms_ticket ADD COLUMN sale_start_time DATETIME NULL COMMENT ''售票开始时间(发布页填写;仅落库回显,购票闸未接)'' AFTER end_time',
    'SELECT 1'
);
PREPARE oms_ticket_sale_start_time_stmt FROM @oms_ticket_sale_start_time_sql;
EXECUTE oms_ticket_sale_start_time_stmt;
DEALLOCATE PREPARE oms_ticket_sale_start_time_stmt;

SET @oms_ticket_sale_end_time_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
      AND column_name = 'sale_end_time'
);
SET @oms_ticket_sale_end_time_sql := IF(
    @oms_ticket_sale_end_time_exists = 0,
    'ALTER TABLE oms_ticket ADD COLUMN sale_end_time DATETIME NULL COMMENT ''售票结束时间(发布页填写;仅落库回显,购票闸未接)'' AFTER sale_start_time',
    'SELECT 1'
);
PREPARE oms_ticket_sale_end_time_stmt FROM @oms_ticket_sale_end_time_sql;
EXECUTE oms_ticket_sale_end_time_stmt;
DEALLOCATE PREPARE oms_ticket_sale_end_time_stmt;

SET @oms_ticket_meeting_point_address_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
      AND column_name = 'meeting_point_address'
);
SET @oms_ticket_meeting_point_address_sql := IF(
    @oms_ticket_meeting_point_address_exists = 0,
    'ALTER TABLE oms_ticket ADD COLUMN meeting_point_address VARCHAR(255) NULL COMMENT ''集合点地址(地图选点文本)'' AFTER meeting_point',
    'SELECT 1'
);
PREPARE oms_ticket_meeting_point_address_stmt FROM @oms_ticket_meeting_point_address_sql;
EXECUTE oms_ticket_meeting_point_address_stmt;
DEALLOCATE PREPARE oms_ticket_meeting_point_address_stmt;

SET @oms_ticket_refund_supported_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
      AND column_name = 'refund_supported'
);
SET @oms_ticket_refund_supported_sql := IF(
    @oms_ticket_refund_supported_exists = 0,
    'ALTER TABLE oms_ticket ADD COLUMN refund_supported TINYINT(1) NULL COMMENT ''支持随时退款 0否1是(发布页填写;仅落库回显,退款规则未接)'' AFTER refund_rule',
    'SELECT 1'
);
PREPARE oms_ticket_refund_supported_stmt FROM @oms_ticket_refund_supported_sql;
EXECUTE oms_ticket_refund_supported_stmt;
DEALLOCATE PREPARE oms_ticket_refund_supported_stmt;

SET @oms_ticket_sync_with_theme_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
      AND column_name = 'sync_with_theme'
);
SET @oms_ticket_sync_with_theme_sql := IF(
    @oms_ticket_sync_with_theme_exists = 0,
    'ALTER TABLE oms_ticket ADD COLUMN sync_with_theme TINYINT(1) NULL COMMENT ''售票时间与主题日期同步 0否1是(发布页填写;仅落库回显,同步逻辑未接)'' AFTER refund_supported',
    'SELECT 1'
);
PREPARE oms_ticket_sync_with_theme_stmt FROM @oms_ticket_sync_with_theme_sql;
EXECUTE oms_ticket_sync_with_theme_stmt;
DEALLOCATE PREPARE oms_ticket_sync_with_theme_stmt;

-- P1 执行后 schema 回读:期望 5 行,类型 DATETIME/DATETIME/VARCHAR(255)/TINYINT(1)/TINYINT(1),全部可空。
-- SELECT column_name, column_type, is_nullable, column_comment
--   FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
--    AND column_name IN ('sale_start_time','sale_end_time','refund_supported','sync_with_theme','meeting_point_address')
--  ORDER BY ordinal_position;
