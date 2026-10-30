-- ============================================================================
-- X12:主题画面风格 cms_topic.look_json
--
-- 一个主题选一种画面风格（星尘 / 纸本 / 末世 / 夜城）+ 该风格的参数 + 转场 + 故事流字体，
-- 由 ThemeLook.java 严格校验后存成紧凑 JSON；NULL 或空串 = 默认风格，老主题外观不变。
-- 取值域与小程序 pages/play/utils/theme-look.js 同一份口径。
--
-- ⚠️ 必须先于后端 jar 落库：/api/play/nodes 与 /api/topic/edit-detail 会单独 select look_json，
--    列不存在时这两个接口会报错。
-- ★ 存量：不迁、不写，全部为 NULL。
-- ============================================================================

-- 第 1 步：先查后加（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）。返回 0 行才执行下面的 ALTER。
SELECT column_name, data_type, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'cms_topic'
  AND column_name = 'look_json';

ALTER TABLE cms_topic
  ADD COLUMN look_json VARCHAR(1024) NULL COMMENT '主题画面风格:风格/参数/转场/故事字体(ThemeLook);空=默认风格' AFTER published_theme_runtime_config_json;

-- 第 2 步：回读断言
-- SELECT COUNT(*) AS should_be_1 FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'look_json';
-- SELECT COUNT(*) AS should_be_0 FROM cms_topic WHERE look_json IS NOT NULL;
