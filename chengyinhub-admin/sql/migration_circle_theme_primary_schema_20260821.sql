-- 五个首批圈层主题扩展：放宽主题码与轻互动阶段白名单；基础字段与玩家事实表由 batch2 迁移创建。
-- 依赖：migration_circle_theme_batch2_20260821.sql

ALTER TABLE cms_topic
    ADD COLUMN circle_reviewed_at DATETIME NULL COMMENT '城市代理最后复核本实例供给时间' AFTER source_template_topic_id,
    DROP CHECK ck_topic_circle_theme_code,
    ADD CONSTRAINT ck_topic_circle_theme_code CHECK (
        circle_theme_code IS NULL OR circle_theme_code IN (
            'AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT',
            'FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI'
        )
    );

ALTER TABLE circle_explore_answer
    DROP CHECK ck_circle_answer_stage,
    ADD COLUMN shared_answer_stage VARCHAR(24) GENERATED ALWAYS AS (
        CASE WHEN answer_stage IN ('QUESTION_CARD','NEXT_PICK','CITY_KEYWORDS') THEN answer_stage ELSE NULL END
    ) STORED AFTER answer_stage,
    ADD UNIQUE KEY uk_circle_answer_session_shared (session_id, shared_answer_stage),
    ADD CONSTRAINT ck_circle_answer_stage CHECK (
        answer_stage IN (
            'PRE_STATE','POST_STATE','DECISION','ADULT_FAVORITE','CHILD_FAVORITE','KEYWORD',
            'PRE_CHOICE','POST_CHOICE','SELF_MOMENT','QUESTION_CARD','PRE_WISH','NEXT_PICK',
            'CITY_KEYWORDS'
        )
    );

ALTER TABLE merchant_circle_supply_profile
    ADD COLUMN duration_minutes INT NULL COMMENT '现成供给体验时长（分钟）' AFTER max_people,
    ADD COLUMN split_booking_allowed TINYINT NULL COMMENT '1=同行可分开预约或轮换，0=需同时接待' AFTER duration_minutes,
    ADD COLUMN optional_benefit VARCHAR(500) NULL COMMENT '可选现有权益；无权益可空' AFTER split_booking_allowed,
    ADD COLUMN benefit_rules VARCHAR(500) NULL COMMENT '权益资金来源、有效期与核销规则' AFTER optional_benefit,
    ADD CONSTRAINT ck_circle_supply_duration CHECK (duration_minutes IS NULL OR duration_minutes > 0),
    ADD CONSTRAINT ck_circle_supply_split_booking CHECK (
        split_booking_allowed IS NULL OR split_booking_allowed IN (0,1)
    );

-- 正向检查：应返回 circle_reviewed_at，且 ck_topic_circle_theme_code 含 FITNESS 与 SHANGHAI。
-- SELECT column_name FROM information_schema.columns
--  WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='circle_reviewed_at';
-- SELECT constraint_name, check_clause
--   FROM information_schema.check_constraints
--  WHERE constraint_schema = DATABASE()
--    AND constraint_name IN ('ck_topic_circle_theme_code','ck_circle_answer_stage');

-- 负控：在 scratch 库事务内写入不存在的主题码，应由 CHECK 返回 ERROR 3819。
-- UPDATE cms_topic SET circle_theme_code='UNSUPPORTED'
--  WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM cms_topic) x);
-- UPDATE circle_explore_answer SET answer_stage='UNSUPPORTED'
--  WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM circle_explore_answer) x);
-- 两条均须命中真实行并由 MySQL 8.0 CHECK 返回 ERROR 3819；影响0行不算负控通过。
