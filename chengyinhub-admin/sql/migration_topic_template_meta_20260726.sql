-- ============================================================================
-- 主题模板详情页元数据落库(07-23 指南 P0 字段字典 · 本轮收窄范围)
--
-- 背景:07-23《Questify_商家俱乐部主题模板详情页_展示内容与同类产品研究》第十三节
-- 给出 24 行 P0 字段字典。逐条比对现码后确认:其中 14 组字段在 cms_topic 及相关表里
-- **一列都没有** —— 不是「VO 没投影」,是根本没有存储。VO 加了字段也永远是 null。
-- 本迁移建两张 1:1 附表承载这批元数据。
--
-- 为什么建附表而不是给 cms_topic / cms_topic_chapter 加列:
--   ① cms_topic 是全仓最热的表,selectCmsTopicVo 被 9 处 select 复用(含事务内
--      SELECT ... FOR UPDATE),加 14 列会让每一个读路径都多背这些列;
--   ② 这批字段只服务「模板货架详情页」一个消费面,与主题的运行时语义无关;
--   ③ 模板元数据是可选的 —— 不是每个 is_template=1 的主题都已填全,
--      附表「没有行」天然表达「未填」,比在主表塞一堆 NULL 列干净。
--
-- ⚠️ 本轮**不做**(依赖「城市实例」领域概念,全仓零实现,已单独立卡):
--   instances[] / 案例数据、revenueChain 主要收益链、数据指标、
--   lockedFields[] / editableFields[]、以及 P1(指南第568行)/ P2(第578行)全部。
--
-- ⚠️ 本文件只准备,不执行。落库走 chengyin-db-migrate 的只读预检 + 人工确认路径。
-- ============================================================================


-- ======================= 第一步:只读预检 =======================
-- 执行前先跑这几条,确认现状与预期一致,再往下走。
--
-- 1) 两张表是否已存在(重复执行本文件应当是安全的,但先看一眼):
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('cms_topic_template_meta', 'cms_topic_chapter_template_meta');
--
-- 2) 货架上现在有几个模板(本迁移不回填,建完是空表,货架仍空):
--   SELECT COUNT(*) FROM cms_topic WHERE del_flag = 0 AND is_template = 1 AND status = 1;
--
-- 3) 确认 cms_topic / cms_topic_chapter 的主键类型是 BIGINT,与下面的外键列对齐:
--   SHOW CREATE TABLE cms_topic;
--   SHOW CREATE TABLE cms_topic_chapter;


-- ======================= 第二步:DDL =======================

-- ---------------------------------------------------------------------------
-- 主题级模板元数据。与 cms_topic 1:1(uk_meta_topic 保证)。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cms_topic_template_meta (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id               BIGINT       NOT NULL COMMENT '主题id(cms_topic.id);仅对 is_template=1 的主题有意义',

    -- 模板名称、版本、状态 ——「状态仅允许实验、试点、已验证、停用」
    -- ⚠️ 未满足入库门槛的只能标 EXPERIMENTAL,不得与 VERIFIED 混排(指南第十三节)。
    template_status        VARCHAR(16)  NOT NULL DEFAULT 'EXPERIMENTAL'
                           COMMENT '模板状态:EXPERIMENTAL实验/PILOT试点/VERIFIED已验证/RETIRED停用',
    version                VARCHAR(32)  NULL COMMENT '模板版本号,如 v1.0。实例默认锁定采用时的版本',

    -- ⚠️ 「模式」不在本表 —— 真源是 cms_topic.product_type(1经典定向/2自由探索)。
    -- 在这里再存一份会立刻和主表分叉,而「不允许空值或第三种发布模式」这条约束
    -- 只有守住单一真源才成立。VO 从 product_type 映射,不读本表。

    applicable_roles       VARCHAR(64)  NULL
                           COMMENT '适用身份,逗号分隔:MERCHANT商家/CLUB俱乐部/AGENT代理人。必填口径=至少一类',

    -- 核心人群与触发:「必须同时写人群、时刻和需求」,所以拆三列而不是一段自由文本 ——
    -- 合成一列的话「只写了人群」这种半成品在库里看不出来。
    audience_who           VARCHAR(255) NULL COMMENT '核心人群:是谁',
    audience_moment        VARCHAR(255) NULL COMMENT '核心触发:什么时刻',
    audience_need          VARCHAR(255) NULL COMMENT '核心需求:要解决什么',

    player_promise         VARCHAR(255) NULL
                           COMMENT '玩家承诺,40-80字。必须说明过程和结果,不能只写口号',

    -- 核心玩家循环:六段有序结构,必须包含反馈、结果和复玩/邀请。
    -- 存换行分隔的有序文本而不是 JSON:六段是固定长度的顺序列表,没有嵌套,
    -- 用 JSON 只会让 seed 和人工校对都变难。
    core_loop              TEXT         NULL COMMENT '核心玩家循环六段,换行分隔,顺序即阶段顺序',

    main_skeleton_template_id BIGINT    NULL
                           COMMENT '主玩法骨架(cms_member_template.id)。⚠️只存id,详情走既有白名单玩法VO,不得由此另开出口',

    evidence_level         VARCHAR(16)  NULL
                           COMMENT '证据等级四级:NONE无实测/ESTIMATED估算/PILOTED已试点/VERIFIED已验证。无实测时必须显式标记',

    -- 风险与资质:按类别必填,合作方可见,必要项对玩家公开
    risk_tags              VARCHAR(255) NULL COMMENT '风险标签,逗号分隔',
    risk_qualifications    TEXT         NULL COMMENT '所需资质材料说明',
    risk_cancellation      TEXT         NULL COMMENT '取消规则',
    risk_liability         TEXT         NULL COMMENT '责任划分',
    risk_safety_notice     TEXT         NULL COMMENT '安全提示(对玩家公开)',

    -- 玩家预览三段:开始前/进行中/完成后,三态齐全才算必填达标
    preview_before         TEXT         NULL COMMENT '玩家预览:开始前',
    preview_during         TEXT         NULL COMMENT '玩家预览:进行中',
    preview_after          TEXT         NULL COMMENT '玩家预览:完成后',

    author                 VARCHAR(64)  NULL COMMENT '模板作者主体(可追溯)',

    del_flag               TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 2删除',
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                           COMMENT '最后更新时间;「模板作者与更新」的时间轴读这一列',

    PRIMARY KEY (id),
    -- 1:1。没有这个键的话同一个主题能落多行元数据,详情页读哪行就成了运气。
    UNIQUE KEY uk_meta_topic (topic_id),
    CONSTRAINT ck_meta_template_status CHECK (
        template_status IN ('EXPERIMENTAL', 'PILOT', 'VERIFIED', 'RETIRED')),
    CONSTRAINT ck_meta_evidence_level CHECK (
        evidence_level IS NULL
        OR evidence_level IN ('NONE', 'ESTIMATED', 'PILOTED', 'VERIFIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题模板详情页元数据(07-23 P0 字段字典·主题级)';


-- ---------------------------------------------------------------------------
-- 章节级模板元数据。与 cms_topic_chapter 1:1(uk_chapter_meta_chapter 保证)。
--
-- 为什么不直接给 cms_topic_chapter 加 13 列:那张表在招商/供给/结算链路上被反复读写,
-- 而这批字段纯展示。分表让「展示元数据缺失」不可能影响招商闸的判断。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cms_topic_chapter_template_meta (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    chapter_id             BIGINT       NOT NULL COMMENT '章节id(cms_topic_chapter.id)',
    topic_id               BIGINT       NOT NULL COMMENT '主题id,冗余一列供按主题批量拉取,避免 N+1',

    role_need              TEXT         NULL COMMENT '本章节需要的商家角色与职责描述',

    -- 排他规则:「必须写区域、期限和原因」—— 同样拆列,合成一段就看不出漏了哪项
    exclusivity_mode       VARCHAR(8)   NULL COMMENT '排他:HARD硬排他/SOFT软排他/NONE不限',
    exclusivity_scope      VARCHAR(255) NULL COMMENT '排他区域',
    exclusivity_period     VARCHAR(255) NULL COMMENT '排他期限',
    exclusivity_reason     TEXT         NULL COMMENT '排他原因',

    -- 运行投入:「必须能判断最低开场条件」
    effort_prep_hours      DECIMAL(6,2) NULL COMMENT '准备工时(小时)',
    effort_staff_minutes   INT          NULL COMMENT '每场店员投入(分钟)',
    effort_venue           VARCHAR(255) NULL COMMENT '场地要求',
    effort_materials       TEXT         NULL COMMENT '物料要求',
    effort_max_concurrent  INT          NULL COMMENT '最大同时接待人数',
    effort_available_slots VARCHAR(255) NULL COMMENT '可用时段',
    effort_needs_booking   TINYINT      NULL COMMENT '是否需预约:1是 0否',
    effort_cancel_cost     TEXT         NULL COMMENT '取消代价',
    effort_qualifications  TEXT         NULL COMMENT '资质要求',

    del_flag               TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 2删除',
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_chapter_meta_chapter (chapter_id),
    KEY idx_chapter_meta_topic (topic_id),
    CONSTRAINT ck_chapter_meta_exclusivity CHECK (
        exclusivity_mode IS NULL OR exclusivity_mode IN ('HARD', 'SOFT', 'NONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题模板详情页元数据(07-23 P0 字段字典·章节级)';


-- ======================= 第三步:落库后回读验证 =======================
-- ⚠️「回执 ≠ 观测」:执行没报错不等于表建成了想要的样子。下面几条是从库里读回来的事实。
--
--   SHOW CREATE TABLE cms_topic_template_meta;
--   SHOW CREATE TABLE cms_topic_chapter_template_meta;
--
-- 负控(确认 CHECK 真的会拦,而不是 MySQL 静默忽略):
--   下面这条**应当报错**。如果它插入成功,说明 CHECK 没生效(MySQL 5.7 会解析但忽略 CHECK),
--   那么 template_status 就是没有防线的自由文本,详情页会渲染出未知状态。
--   INSERT INTO cms_topic_template_meta (topic_id, template_status) VALUES (-1, 'BOGUS');
--   -- 期望:ERROR 3819 (HY000): Check constraint 'ck_meta_template_status' is violated.
--   -- 若误插成功,记得清理:DELETE FROM cms_topic_template_meta WHERE topic_id = -1;


-- ======================= 回滚 =======================
-- 两张表都是新建的附表,没有任何存量数据依赖它们,可直接drop。
-- ⚠️ 但若已经录入过模板元数据,drop 会连内容一起丢 —— 先导出再drop。
--   DROP TABLE IF EXISTS cms_topic_chapter_template_meta;
--   DROP TABLE IF EXISTS cms_topic_template_meta;
