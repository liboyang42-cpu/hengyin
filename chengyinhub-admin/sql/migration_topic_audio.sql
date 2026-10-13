-- 主题音频: cms_topic 增加 audio_url / audio_duration
-- 与模板层 cms_template_library / cms_member_template 对齐
ALTER TABLE cms_topic
    ADD COLUMN audio_url VARCHAR(500) NULL COMMENT '主题音频URL(OSS)',
    ADD COLUMN audio_duration INT NULL COMMENT '主题音频时长(秒)';
