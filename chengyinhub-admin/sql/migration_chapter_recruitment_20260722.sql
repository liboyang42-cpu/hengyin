-- V1 章节招商与商家申请（2026-07-22）
-- 执行前必须走 chengyin-db-migrate：只读预检 -> 风险确认 -> 执行 -> DESCRIBE/索引回读。
-- 本迁移只建立结构，不回填存量章节：recruit_enabled 默认 0，避免 max_merchant=4 的存量章被误开放。
-- 前置：coop_perk / coop_perk_template 已按既有权益迁移落库。本份会让 coop_perk 同时承载
-- 「邀约权益」和「章节开放招商的独立快照」；执行前须在只读预检中确认该表行数与维护窗口。

-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS。两个列分别守卫，避免一次中断后留下半结构时重跑失败。
SET @chapter_category_id_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'category_id'
);
SET @chapter_category_id_sql := IF(
    @chapter_category_id_exists = 0,
    'ALTER TABLE cms_topic_chapter ADD COLUMN category_id INT NULL COMMENT ''适合商家品类ID，匹配mms_merchant.category_id'' AFTER category',
    'SELECT ''cms_topic_chapter.category_id already exists'''
);
PREPARE chapter_category_id_stmt FROM @chapter_category_id_sql;
EXECUTE chapter_category_id_stmt;
DEALLOCATE PREPARE chapter_category_id_stmt;

SET @chapter_recruit_enabled_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'recruit_enabled'
);
SET @chapter_recruit_enabled_sql := IF(
    @chapter_recruit_enabled_exists = 0,
    'ALTER TABLE cms_topic_chapter ADD COLUMN recruit_enabled TINYINT NOT NULL DEFAULT 0 COMMENT ''是否开放商家承接：0否 1是'' AFTER category_id',
    'SELECT ''cms_topic_chapter.recruit_enabled already exists'''
);
PREPARE chapter_recruit_enabled_stmt FROM @chapter_recruit_enabled_sql;
EXECUTE chapter_recruit_enabled_stmt;
DEALLOCATE PREPARE chapter_recruit_enabled_stmt;

-- 开放招商的实际供给不能直接引用 coop_perk_template：模板可被修改/删除，且它与 coop_perk
-- 是两张表、主键空间也不同。故先允许 coop_perk 生成不挂邀约的独立快照，并直存商家归属。
-- invite_id 从 NOT NULL 改为 NULL 需要表重建；执行前必须按 chengyin-db-migrate 的预检结果安排窗口。
SET @coop_perk_invite_nullable := (
    SELECT is_nullable FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'coop_perk' AND column_name = 'invite_id'
     LIMIT 1
);
SET @coop_perk_invite_sql := IF(
    @coop_perk_invite_nullable = 'NO',
    'ALTER TABLE coop_perk MODIFY COLUMN invite_id BIGINT(20) NULL COMMENT ''所属邀约；开放招商独立供给为空''',
    'SELECT ''coop_perk.invite_id already nullable'''
);
PREPARE coop_perk_invite_stmt FROM @coop_perk_invite_sql;
EXECUTE coop_perk_invite_stmt;
DEALLOCATE PREPARE coop_perk_invite_stmt;

SET @coop_perk_merchant_id_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'coop_perk' AND column_name = 'merchant_id'
);
SET @coop_perk_merchant_id_sql := IF(
    @coop_perk_merchant_id_exists = 0,
    'ALTER TABLE coop_perk ADD COLUMN merchant_id BIGINT(20) NULL COMMENT ''开放招商独立供给的商家memberId'' AFTER invite_id',
    'SELECT ''coop_perk.merchant_id already exists'''
);
PREPARE coop_perk_merchant_id_stmt FROM @coop_perk_merchant_id_sql;
EXECUTE coop_perk_merchant_id_stmt;
DEALLOCATE PREPARE coop_perk_merchant_id_stmt;

-- 申请与生效供给分表：申请未审核前不能进入 merchant_chapter_offer，避免影响核销、容量和结算。
CREATE TABLE IF NOT EXISTS merchant_chapter_application (
    id BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id BIGINT(20) NOT NULL COMMENT '由chapter反查写入，查询索引用，不信任前端',
    chapter_id BIGINT(20) NOT NULL COMMENT '申请承接的章节ID',
    merchant_id BIGINT(20) NOT NULL COMMENT '申请商家memberId，不是mms_merchant.id',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待审核 1已通过 2已拒绝',
    message VARCHAR(500) DEFAULT NULL COMMENT '商家申请说明',
    audit_remark VARCHAR(500) DEFAULT NULL COMMENT '发布者审核备注',
    audit_member_id BIGINT(20) DEFAULT NULL COMMENT '实际审核的主题发布者memberId',
    handle_time DATETIME DEFAULT NULL COMMENT '审核时间',
    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记 0存在 2删除',
    create_by VARCHAR(64) DEFAULT '' COMMENT '创建者',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by VARCHAR(64) DEFAULT '' COMMENT '更新者',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chapter_application_merchant (chapter_id, merchant_id),
    KEY idx_chapter_application_topic_status (topic_id, status),
    KEY idx_chapter_application_merchant_status (merchant_id, status),
    KEY idx_chapter_application_chapter_status (chapter_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家章节承接申请';

-- 回滚（仅未写入业务数据时使用）：
-- DROP TABLE IF EXISTS merchant_chapter_application;
-- ALTER TABLE cms_topic_chapter DROP COLUMN recruit_enabled, DROP COLUMN category_id;
-- ⚠️ 若已有开放招商的独立权益快照，不能直接收回 coop_perk.merchant_id 或把 invite_id 改回 NOT NULL；
--    应先停止入口、迁移/归档相关 offer 后再做专门回滚方案。
