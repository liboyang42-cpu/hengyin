-- ============================================================
-- 关卡编辑器(创建游戏)重构 第二批:剧情多节点 + 拍照审核开关
-- 设计报告:docs/superpowers/specs/2026-06-23-创建游戏-关卡编辑器-重构设计.md §8
-- 前置:依赖 migration_level_editor_fields.sql(已加 story_text/story_img/photo_require_desc 等)。
-- 执行前:确认本脚本未执行过(列重复会报错)。MySQL8 ADD COLUMN 走 INSTANT。
-- 说明:
--   story_json  存剧情多节点 JSON 列表 [{"text":"","tag":"","img":""}],与单节点 story_text 并存,前端二选一。
--   photo_review 拍照打卡是否需人工审核,默认 0(不审核),与 photo_require_desc 配套。
-- 字段缺省安全:旧记录 story_json=NULL、photo_review=0,读写向后兼容。
-- ============================================================

-- 1) 我的模板:剧情多节点 + 拍照审核开关
--    story_json 锚定到 story_img 之后;photo_review 锚定到 photo_require_desc 之后。
ALTER TABLE cms_member_template
    ADD COLUMN story_json   TEXT    NULL              COMMENT '剧情多节点JSON列表 [{"text":"","tag":"","img":""}]' AFTER story_img,
    ADD COLUMN photo_review TINYINT NOT NULL DEFAULT 0 COMMENT '拍照打卡是否需人工审核:0否 1是'                AFTER photo_require_desc;

-- 2) 公共模板库:镜像同步同两列
ALTER TABLE cms_template_library
    ADD COLUMN story_json   TEXT    NULL              COMMENT '剧情多节点JSON列表 [{"text":"","tag":"","img":""}]' AFTER story_img,
    ADD COLUMN photo_review TINYINT NOT NULL DEFAULT 0 COMMENT '拍照打卡是否需人工审核:0否 1是'                AFTER photo_require_desc;

-- ============================================================
-- 回滚(如需):
-- ALTER TABLE cms_member_template  DROP COLUMN story_json, DROP COLUMN photo_review;
-- ALTER TABLE cms_template_library DROP COLUMN story_json, DROP COLUMN photo_review;
-- ============================================================
