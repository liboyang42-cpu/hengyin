-- M7(questo 施工 BE-13 / MP-04 / WEB-05 / C04):公开内容目录的落地元信息与公开落地文案翻译。
-- 纯新增两张表，不改既有表、不加既有列、不回填任何数据。没有真实供给就不产生目录行(目录是查询期聚合，不是表)。
--
-- 为什么需要这两张表，且为什么不是第二内容真源：
--   1. 团体/学校筛选必须来自真实配置字段，不能按关键词猜分类；cms_topic 没有任何
--      group/school 可售配置，CmsTopicTemplateMeta 的人群字段只服务 is_template=1 的模板本体。
--      故新增 content_landing_metadata 承载「本实例对团体/学校可承接 + 人数/年龄/无障碍说明」这类
--      作者或运营显式填写的落地配置。
--   2. 公开落地文案的多语言只能有作者显式译文；现网无任何内容翻译承载表。
--      content_landing_translation 只存 title/subtitle/summary 三段公开摘要译文，**不**复制
--      正文 blocks、答案、提示、角色私密线索或发布快照 —— 那些仍只有 cms_topic / cms_topic_chapter /
--      cms_member_template / topic_sale_snapshot 一个真源。缺译一律由读侧记为回退，不写「已翻译」。
--   3. 两表都以 topic_id 关联既有主题；主题下架/私有/未发布时由读侧既有公开闸排除，
--      不靠本表 status 兜底，也不靠前端隐藏。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 列/唯一键断言；已存在同名表不替换、不删除。
-- 落库顺序：可先于新 jar 执行；旧 jar 不读这两张表(所有既有 SELECT 都是显式列名)。
-- 执行：只由 owner 在获准测试环境执行；本仓自动化不执行它。

CREATE TABLE IF NOT EXISTS content_landing_metadata (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  topic_id BIGINT NOT NULL COMMENT 'cms_topic.id；1:1',
  source_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN' COMMENT '原文语言；cms_topic.name/subtitle/description 的语言',
  landing_summary VARCHAR(500) NULL COMMENT '作者填写的无剧透公开摘要；NULL = 读侧回退 cms_topic.description 截断',
  group_eligible TINYINT NOT NULL DEFAULT 0 COMMENT '1=团队/团建可承接；0=未声明(不等于不可承接，展示为未确认)',
  school_eligible TINYINT NOT NULL DEFAULT 0 COMMENT '1=学校/班级可承接；0=未声明',
  group_min_people INT NULL COMMENT '团体最小人数；NULL=未声明',
  group_max_people INT NULL COMMENT '团体最大人数；NULL=未声明',
  age_min INT NULL COMMENT '建议最小年龄；NULL=未声明',
  age_max INT NULL COMMENT '建议最大年龄；NULL=未声明',
  accessibility_note VARCHAR(255) NULL COMMENT '无障碍/天气/场地限制说明；NULL=未提供',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=生效 0=停用(读侧忽略本行元信息，不影响主题能否公开)',
  row_version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本；作者写入需回传 expectedVersion',
  update_by BIGINT NULL COMMENT '最后修改人 memberId',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_landing_metadata_topic (topic_id),
  KEY idx_content_landing_metadata_group (status, group_eligible),
  KEY idx_content_landing_metadata_school (status, school_eligible),
  CONSTRAINT chk_content_landing_metadata_status CHECK (status IN (0, 1)),
  CONSTRAINT chk_content_landing_metadata_group CHECK (group_eligible IN (0, 1)),
  CONSTRAINT chk_content_landing_metadata_school CHECK (school_eligible IN (0, 1)),
  CONSTRAINT chk_content_landing_metadata_people CHECK (
    group_min_people IS NULL OR group_max_people IS NULL OR group_min_people <= group_max_people),
  CONSTRAINT chk_content_landing_metadata_age CHECK (
    age_min IS NULL OR age_max IS NULL OR age_min <= age_max)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M7 公开目录落地元信息(非内容真源)';

CREATE TABLE IF NOT EXISTS content_landing_translation (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  topic_id BIGINT NOT NULL COMMENT 'cms_topic.id',
  locale VARCHAR(16) NOT NULL COMMENT 'BCP-47 语言标签，如 en / zh-CN / ja',
  source_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN' COMMENT '本条译文对应的原文语言，用于判断原文已改需重译',
  title VARCHAR(200) NULL COMMENT '公开标题译文；NULL=缺译',
  subtitle VARCHAR(255) NULL COMMENT '公开副标题译文；NULL=缺译',
  summary VARCHAR(500) NULL COMMENT '公开摘要译文；NULL=缺译',
  translation_state VARCHAR(16) NOT NULL COMMENT 'APPROVED=可用；PENDING=待校对，读侧不当作已完成翻译',
  row_version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  update_by BIGINT NULL COMMENT '最后修改人 memberId',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_landing_translation_topic_locale (topic_id, locale),
  KEY idx_content_landing_translation_locale (locale, translation_state),
  CONSTRAINT chk_content_landing_translation_state CHECK (translation_state IN ('APPROVED', 'PENDING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M7 公开落地文案译文(只含公开摘要，不含正文/答案)';

-- 回读断言：两张表的关键列各就位，且唯一键存在。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='content_landing_metadata'
          AND column_name IN ('topic_id','source_locale','landing_summary','group_eligible','school_eligible',
                              'group_min_people','group_max_people','age_min','age_max','accessibility_note',
                              'status','row_version')) = 12 AS assertion_ok;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='content_landing_translation'
          AND column_name IN ('topic_id','locale','source_locale','title','subtitle','summary',
                              'translation_state','row_version')) = 8 AS assertion_ok;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='content_landing_metadata'
          AND index_name='uk_content_landing_metadata_topic' AND non_unique=0) = 1 AS assertion_ok;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='content_landing_translation'
          AND index_name='uk_content_landing_translation_topic_locale' AND non_unique=0) = 1 AS assertion_ok;
