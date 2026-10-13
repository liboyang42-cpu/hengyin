-- 章节申请记录补位关联，不交换原商家归属、不修改申请状态。
-- 前置：chapter_recruitment_20260722。上线执行另行只读预检、确认目标库和批准。
SET @application_replacement_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_application'
      AND column_name = 'replacement_of_offer_id'
);
SET @application_replacement_sql := IF(@application_replacement_exists = 0,
    'ALTER TABLE merchant_chapter_application ADD COLUMN replacement_of_offer_id BIGINT NULL COMMENT ''申请补位的原供给ID''',
    'SELECT ''merchant_chapter_application.replacement_of_offer_id already exists''');
PREPARE application_replacement_stmt FROM @application_replacement_sql;
EXECUTE application_replacement_stmt;
DEALLOCATE PREPARE application_replacement_stmt;
