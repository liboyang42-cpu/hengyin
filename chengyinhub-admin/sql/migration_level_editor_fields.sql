-- ============================================================
-- 关卡编辑器(创建游戏)重构:节点级体验层字段 + 草稿态
-- 设计报告:docs/superpowers/specs/2026-06-23-创建游戏-关卡编辑器-重构设计.md §8
-- 执行前:确认本脚本未执行过(列重复会报错)。MySQL8 ADD COLUMN 走 INSTANT。
-- 说明:draft_status 是"草稿/已发布"态,与既有审核态 status(0待审/1通过/2失败)并存,不复用。
-- 预检结论(2026-06-23):
--   cms_template_library 无 medal_name 列 -> 库侧体验字段锚定到 coupon_id 之后。
--   player_node_progress.pic_url 已存在 -> review_status 锚定到 pic_url 之后。
-- ============================================================

-- 1) 我的模板:体验层字段 + 草稿态
ALTER TABLE cms_member_template
    ADD COLUMN story_text         VARCHAR(500) NULL COMMENT '剧情文案(到达点位/开始挑战前展示)'       AFTER medal_name,
    ADD COLUMN story_img          VARCHAR(255) NULL COMMENT '剧情配图URL'                              AFTER story_text,
    ADD COLUMN hint1              VARCHAR(255) NULL COMMENT '一级提示'                                 AFTER story_img,
    ADD COLUMN hint2              VARCHAR(255) NULL COMMENT '二级提示'                                 AFTER hint1,
    ADD COLUMN answer_reveal      VARCHAR(500) NULL COMMENT '兜底答案/解析'                            AFTER hint2,
    ADD COLUMN photo_require_desc VARCHAR(255) NULL COMMENT '拍照打卡要求描述(validation_method=2 用)' AFTER answer_reveal,
    ADD COLUMN audio_url          VARCHAR(255) NULL COMMENT '语音讲解音频URL'                          AFTER photo_require_desc,
    ADD COLUMN audio_duration     INT          NULL COMMENT '语音时长(秒,展示用)'                     AFTER audio_url,
    ADD COLUMN draft_status       TINYINT      NOT NULL DEFAULT 1 COMMENT '草稿态:0草稿 1已发布' AFTER publish_status;

-- 2) 公共模板库:镜像同步体验层字段(草稿不进库,故不加 draft_status)
--    该表无 medal_name 列,锚定到 coupon_id 之后。
ALTER TABLE cms_template_library
    ADD COLUMN story_text         VARCHAR(500) NULL COMMENT '剧情文案'       AFTER coupon_id,
    ADD COLUMN story_img          VARCHAR(255) NULL COMMENT '剧情配图URL'     AFTER story_text,
    ADD COLUMN hint1              VARCHAR(255) NULL COMMENT '一级提示'        AFTER story_img,
    ADD COLUMN hint2              VARCHAR(255) NULL COMMENT '二级提示'        AFTER hint1,
    ADD COLUMN answer_reveal      VARCHAR(500) NULL COMMENT '兜底答案/解析'   AFTER hint2,
    ADD COLUMN photo_require_desc VARCHAR(255) NULL COMMENT '拍照打卡要求描述' AFTER answer_reveal,
    ADD COLUMN audio_url          VARCHAR(255) NULL COMMENT '语音讲解音频URL'  AFTER photo_require_desc,
    ADD COLUMN audio_duration     INT          NULL COMMENT '语音时长(秒)'    AFTER audio_url;

-- 3) 玩家进度:拍照打卡审核位(字段先留,审核 UI 后置)
--    pic_url 已存在(见 migration_play_progress_points.sql),此处只加 review_status。
ALTER TABLE player_node_progress
    ADD COLUMN review_status TINYINT NOT NULL DEFAULT 1 COMMENT '拍照审核态:0待审 1通过 2驳回(非拍照节点默认1)' AFTER pic_url;
