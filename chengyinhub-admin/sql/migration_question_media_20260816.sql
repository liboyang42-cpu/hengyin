-- ============================================================================
-- 题干配图/配音 + 选项配图配音(2026-08-16)
--
-- 背景:模板的答题模块此前只能配纯文字 —— 题干一行字,选项 A~D 四行字。
-- 本迁移让题干能挂一张图和一段音频(看图答题 / 听音答题),选项各自也能挂图和音频。
--
-- 为什么不把 question_a~d 换成 JSON 选项数组:
--   question_a~d 散在 5 张表(cms_member_template / cms_template_library /
--   cms_registration_merchant / cms_registration_scan / 视图 view_registration_merchant)
--   共 26 个读写方。换存储是破坏性重构 + 全量回填,而本次要的只是「选项能配图和音」。
--   所以走纯新增:文本仍在 question_a~d,媒体挂在旁边一列,按字母 A/B/C/D 对齐 ——
--   字母本来就是这套模型的键(correct_answer 存的就是字母),不是新造的关联。
--
-- 为什么题干走两个普通列、选项走一个 JSON 列:
--   题干只有一份,varchar 长度本身就是校验;选项是 A~D 四份 × (图 + 音),
--   摊成列就是 8 个新列,且以后加第五个选项还要再加列。选项这一份用 JSON。
--
-- ⚠️ 两张表都要改。发布链路 TemplatePublishServiceImpl 是**逐字段 copy**:
--   只加 cms_member_template 不加 cms_template_library,编辑器填好的题干图会在
--   「发布到模板库」那一刻被静默丢掉,没有任何报错。
--
-- ⚠️ MySQL 8 不支持 ADD COLUMN IF NOT EXISTS(报 1064),所以本文件是裸 ADD COLUMN,
--   重复执行会报 1060 Duplicate column。执行前先跑下面的只读预检。
--
-- ⚠️ 本文件只准备,不执行。落库走 chengyin-db-migrate 的只读预检 + 人工确认路径。
-- ============================================================================


-- ======================= 第一步:只读预检 =======================
--
-- 1) 确认这三列都还不存在(有任何一行返回 = 本文件已执行过,别再跑):
--   SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('cms_member_template', 'cms_template_library')
--      AND COLUMN_NAME IN ('question_img','question_audio','question_option_media_json');
--
-- 2) 确认锚点列存在(下面用 AFTER 定位;两张表的列顺序不同,锚点也不同):
--   SHOW COLUMNS FROM cms_member_template  LIKE 'correct_answer';
--   SHOW COLUMNS FROM cms_template_library LIKE 'correct_answer';
--
-- 3) 看一眼受影响的存量规模(纯新增列,不回填、不改语义,此项只为知情):
--   SELECT validation_method, COUNT(*) FROM cms_member_template
--    WHERE del_flag = 0 GROUP BY validation_method;
--   -- validation_method = 3 的就是选择题模板,是这次新增能力的目标人群。


-- ======================= 第二步:DDL =======================

-- 1) 我的模板(编辑器直接写这张表,是题面的真源)
ALTER TABLE cms_member_template
    ADD COLUMN question_img              VARCHAR(500)  NULL COMMENT '题干配图URL(看图答题)'                    AFTER correct_answer,
    ADD COLUMN question_audio            VARCHAR(500)  NULL COMMENT '题干配音URL(听音答题)'                    AFTER question_img,
    ADD COLUMN question_option_media_json VARCHAR(2000) NULL COMMENT '选项媒体,按字母对齐 question_a~d:{"A":{"img":"..","audio":".."}}' AFTER question_audio;

-- 2) 公共模板库:镜像同步,否则发布即丢
ALTER TABLE cms_template_library
    ADD COLUMN question_img              VARCHAR(500)  NULL COMMENT '题干配图URL'                              AFTER correct_answer,
    ADD COLUMN question_audio            VARCHAR(500)  NULL COMMENT '题干配音URL'                              AFTER question_img,
    ADD COLUMN question_option_media_json VARCHAR(2000) NULL COMMENT '选项媒体,按字母对齐 question_a~d'          AFTER question_audio;


-- ======================= 第三步:落库后回读 =======================
--
-- 执行完必须回读一次 —— ALTER 返回 OK 只证明语句被接受:
--   SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('cms_member_template', 'cms_template_library')
--      AND COLUMN_NAME IN ('question_img','question_audio','question_option_media_json')
--    ORDER BY TABLE_NAME, ORDINAL_POSITION;
--   -- 期望 6 行(两张表 × 三列)。少于 6 行 = 有一张表没改到,发布链路会静默丢字段。
