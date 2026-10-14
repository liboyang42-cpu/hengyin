-- run41-prod-ddl.sql 是 2026-09-19 生产快照；此后仓库迁移给本 IT 涉及表新增的列按原定义叠加（仅列，不含无关表）。
-- cms_topic: migration_theme_runtime_20261004.sql
ALTER TABLE cms_topic ADD COLUMN theme_runtime_config_json MEDIUMTEXT NULL;
ALTER TABLE cms_topic ADD COLUMN published_theme_runtime_config_json MEDIUMTEXT NULL;
-- merchant_chapter_offer: migration_20261003_merchant_offer_withdrawal_columns.sql
ALTER TABLE merchant_chapter_offer ADD COLUMN replacement_of_offer_id BIGINT NULL DEFAULT NULL;
ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_reason VARCHAR(500) NULL DEFAULT NULL;
ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_required TINYINT(1) NULL DEFAULT NULL;
-- merchant_chapter_offer: migration_club_direct_merchant_receivable_20261004.sql
ALTER TABLE merchant_chapter_offer ADD COLUMN payer_club_id BIGINT NULL;
