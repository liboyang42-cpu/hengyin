-- 第二组五个圈层自由探索主题：模板谱系、供给角色候选、商家供给快照、玩家自助记录。
-- 设计真源：Questify_第二组五个圈层自由探索主题_每主题8个即插即用商家模板_20260821.md
-- 本文件只准备，不在开发阶段连接或写生产库。

-- ======================== 只读预检 ========================
-- SELECT column_name FROM information_schema.columns
--  WHERE table_schema=DATABASE() AND table_name='cms_topic'
--    AND column_name IN ('circle_theme_code','source_template_topic_id');
-- SELECT column_name FROM information_schema.columns
--  WHERE table_schema=DATABASE() AND table_name='cms_topic_chapter'
--    AND column_name IN ('circle_candidate_code','circle_role','circle_policy','circle_enabled');
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema=DATABASE() AND table_name IN
--  ('merchant_circle_supply_profile','circle_explore_session','circle_explore_session_member',
--   'circle_explore_record','circle_explore_answer');

ALTER TABLE cms_topic
    ADD COLUMN circle_theme_code VARCHAR(24) NULL COMMENT '圈层主题码；NULL=普通主题' AFTER product_type,
    ADD COLUMN source_template_topic_id BIGINT NULL COMMENT '模板复制谱系；NULL=非模板副本' AFTER circle_theme_code,
    ADD KEY idx_topic_circle_public (circle_theme_code, user_status, status, del_flag),
    ADD KEY idx_topic_source_template (source_template_topic_id),
    ADD CONSTRAINT ck_topic_circle_theme_code CHECK (
        circle_theme_code IS NULL OR circle_theme_code IN
        ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')
    );

ALTER TABLE cms_topic_chapter
    ADD COLUMN circle_candidate_code VARCHAR(8) NULL COMMENT '供给角色候选码，如A1/O1/K1/H1/N1' AFTER terms_mode,
    ADD COLUMN circle_role VARCHAR(16) NULL COMMENT '候选作用：EXPERIENCE/REST/EXPRESSION' AFTER circle_candidate_code,
    ADD COLUMN circle_policy VARCHAR(24) NULL COMMENT '供给专项策略' AFTER circle_role,
    ADD COLUMN circle_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '圈层实例是否启用该候选；普通章节恒为1' AFTER circle_policy,
    ADD KEY idx_chapter_circle_selection (topic_id, circle_enabled, circle_role),
    ADD CONSTRAINT ck_chapter_circle_role CHECK (
        circle_role IS NULL OR circle_role IN ('EXPERIENCE','REST','EXPRESSION')
    ),
    ADD CONSTRAINT ck_chapter_circle_policy CHECK (
        circle_policy IS NULL OR circle_policy IN
        ('COMMON','WORKDAY_EVENING','SOLO','PARENT_CHILD','PHOTO_RULES','PHOTO_SERVICE','NIGHT')
    ),
    ADD CONSTRAINT ck_chapter_circle_enabled CHECK (circle_enabled IN (0,1));

CREATE TABLE merchant_circle_supply_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    offer_id BIGINT NOT NULL COMMENT 'merchant_chapter_offer.id；一份生效供给一份声明快照',
    version_no INT NOT NULL DEFAULT 1 COMMENT '同一offer的声明版本，从1递增',
    is_current TINYINT NOT NULL DEFAULT 1 COMMENT '1=当前有效版本；历史版本只供审计和记录卡回放',
    current_offer_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_current=1 THEN offer_id ELSE NULL END) STORED,
    supply_name VARCHAR(120) NOT NULL COMMENT '商家正在正常销售或明确确认的0元供给名称',
    supply_kind VARCHAR(16) NOT NULL COMMENT 'PRODUCT/SERVICE/EVENT/ZERO_BROWSE',
    regular_price DECIMAL(10,2) NOT NULL COMMENT '商家正常价格；0元确认填0',
    action_type VARCHAR(16) NOT NULL COMMENT 'NAVIGATE/BOOK/BUY',
    action_value VARCHAR(500) NOT NULL COMMENT '导航、预约或购买入口',
    available_hours VARCHAR(500) NOT NULL COMMENT '商家确认的开放或可预约时段',
    min_people INT NOT NULL,
    max_people INT NOT NULL,
    checked_at DATETIME NOT NULL COMMENT '商家最后确认时间',
    merchant_rules TEXT NOT NULL COMMENT '现场、预约、改期和正常经营规则',

    workday_evening_covered TINYINT NULL COMMENT '下班主题：真实覆盖工作日18:00-21:30',
    single_person_allowed TINYINT NULL COMMENT '单人主题：明确允许1人购买或预约',

    declared_age_range VARCHAR(120) NULL COMMENT 'DECLARED_AGE_RANGE',
    guardian_mode VARCHAR(120) NULL COMMENT 'GUARDIAN_MODE；只能记录商家声明',
    guardian_present_throughout TINYINT NULL COMMENT '监护人是否明确全程在场；亲子供给必须为1',
    merchant_childcare_required TINYINT NULL COMMENT '是否需要商家代看或交接儿童；亲子供给必须为0',
    adult_child_capacity VARCHAR(120) NULL COMMENT 'ADULT_CHILD_CAPACITY',
    price_unit VARCHAR(32) NULL COMMENT 'PRICE_UNIT',
    restrictions TEXT NULL COMMENT 'RESTRICTIONS',
    tools_animals_materials TEXT NULL COMMENT 'TOOLS_ANIMALS_MATERIALS',
    incident_process TEXT NULL COMMENT 'INCIDENT_PROCESS',
    cancellation_rule TEXT NULL COMMENT 'CANCELLATION_RULE',

    personal_photo_allowed TINYINT NULL COMMENT '是否允许个人非商业拍摄',
    photo_allowed_area VARCHAR(300) NULL COMMENT '允许拍摄区域',
    tripod_policy VARCHAR(120) NULL COMMENT '三脚架规则',
    lighting_policy VARCHAR(120) NULL COMMENT '补光灯规则',
    photo_duration_limit VARCHAR(120) NULL COMMENT '拍摄时长限制',

    night_hours VARCHAR(300) NULL COMMENT 'NIGHT_HOURS',
    last_entry VARCHAR(120) NULL COMMENT 'LAST_ENTRY',
    alcohol_involved TINYINT NULL COMMENT '夜间供给是否涉及酒精；NIGHT必填0或1',
    legal_drinking_age INT NULL COMMENT '涉及酒精时商家所在地法定饮酒年龄',
    legal_drinking_age_rule VARCHAR(300) NULL COMMENT '涉及酒精时的当地法定饮酒年龄规则',
    alcohol_warning VARCHAR(300) NULL COMMENT '涉及酒精时的健康与禁止酒驾提示',
    event_end_at DATETIME NULL COMMENT 'EVENT_END_AT；固定驻场可空，单场必填',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_circle_supply_offer_version (offer_id, version_no),
    UNIQUE KEY uk_circle_supply_one_current (current_offer_id),
    KEY idx_circle_supply_current (offer_id, is_current),
    KEY idx_circle_supply_freshness (checked_at, event_end_at),
    CONSTRAINT ck_circle_supply_kind CHECK (
        supply_kind IN ('PRODUCT','SERVICE','EVENT','ZERO_BROWSE')
    ),
    CONSTRAINT ck_circle_supply_action CHECK (action_type IN ('NAVIGATE','BOOK','BUY')),
    CONSTRAINT ck_circle_supply_current CHECK (is_current IN (0,1)),
    CONSTRAINT ck_circle_supply_price CHECK (regular_price >= 0),
    CONSTRAINT ck_circle_supply_people CHECK (min_people > 0 AND max_people >= min_people),
    CONSTRAINT ck_circle_supply_workday CHECK (workday_evening_covered IS NULL OR workday_evening_covered IN (0,1)),
    CONSTRAINT ck_circle_supply_single CHECK (single_person_allowed IS NULL OR single_person_allowed IN (0,1)),
    CONSTRAINT ck_circle_supply_guardian_present CHECK (guardian_present_throughout IS NULL OR guardian_present_throughout IN (0,1)),
    CONSTRAINT ck_circle_supply_merchant_childcare CHECK (merchant_childcare_required IS NULL OR merchant_childcare_required IN (0,1)),
    CONSTRAINT ck_circle_supply_photo CHECK (personal_photo_allowed IS NULL OR personal_photo_allowed IN (0,1)),
    CONSTRAINT ck_circle_supply_alcohol CHECK (alcohol_involved IS NULL OR alcohol_involved IN (0,1)),
    CONSTRAINT ck_circle_supply_drinking_age CHECK (legal_drinking_age IS NULL OR legal_drinking_age BETWEEN 18 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈层主题商家现成供给与专项声明快照';

CREATE TABLE circle_explore_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL COMMENT '圈层城市实例主题id',
    owner_member_id BIGINT NOT NULL,
    invite_code VARCHAR(12) NOT NULL COMMENT '只用于熟人同行，不做陌生人匹配',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_circle_session_invite (invite_code),
    KEY idx_circle_session_owner (owner_member_id, status),
    KEY idx_circle_session_topic (topic_id, status),
    CONSTRAINT ck_circle_session_status CHECK (status IN ('ACTIVE','COMPLETED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈层自由探索玩家会话';

CREATE TABLE circle_explore_session_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_circle_session_member (session_id, member_id),
    KEY idx_circle_member_sessions (member_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈层自由探索熟人同行成员';

CREATE TABLE circle_explore_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    offer_id BIGINT NOT NULL COMMENT '玩家自助记录的商家供给；不写核销事实',
    supply_profile_id BIGINT NOT NULL COMMENT '记录时的供给声明版本；后续重新确认不得追溯改写',
    note VARCHAR(500) NULL,
    recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_circle_record_member_offer (session_id, member_id, offer_id),
    KEY idx_circle_record_session (session_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家自助记录；不代表预约、到店、核销或成交';

CREATE TABLE circle_explore_answer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    answer_stage VARCHAR(24) NOT NULL COMMENT 'PRE_STATE/POST_STATE/DECISION/ADULT_FAVORITE/CHILD_FAVORITE/KEYWORD',
    answer_value VARCHAR(300) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_circle_answer_member_stage (session_id, member_id, answer_stage),
    KEY idx_circle_answer_session (session_id, answer_stage),
    CONSTRAINT ck_circle_answer_stage CHECK (
        answer_stage IN ('PRE_STATE','POST_STATE','DECISION','ADULT_FAVORITE','CHILD_FAVORITE','KEYWORD')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈层主题轻互动答案';

-- ======================== 落库后回读 ========================
-- SELECT table_name, engine FROM information_schema.tables
--  WHERE table_schema=DATABASE() AND table_name IN
--  ('merchant_circle_supply_profile','circle_explore_session','circle_explore_session_member',
--   'circle_explore_record','circle_explore_answer');
-- 期望五行且全部 InnoDB。
-- SELECT column_name, is_nullable, column_default FROM information_schema.columns
--  WHERE table_schema=DATABASE() AND table_name IN ('cms_topic','cms_topic_chapter')
--    AND (column_name LIKE 'circle_%' OR column_name='source_template_topic_id');

-- ======================== 负控（scratch/事务中执行） ========================
-- SELECT COUNT(*) > 0 AS negative_control_precondition FROM cms_topic_chapter;
-- 期望为1；若为空表，先在scratch库准备一行合法fixture，禁止把0行更新当成负控通过。
-- UPDATE cms_topic_chapter SET circle_enabled=2
--  WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM cms_topic_chapter) x);
-- 期望命中真实行并由 MySQL 8.0 CHECK 返回3819；若放行或影响0行立即NO-GO。

-- ======================== 回滚说明 ========================
-- 玩家事实表与供给快照可能已有业务数据，生产回滚不得直接 DROP；须先停写、导出和核对引用。
-- 空库开发回滚顺序：circle_explore_answer → circle_explore_record → circle_explore_session_member
-- → circle_explore_session → merchant_circle_supply_profile → 移除章节列 → 移除主题列。
