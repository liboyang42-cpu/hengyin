-- 活动来源主题关联迁移（决策二A：商家报名主题后自动生成活动）
-- 执行方式：在 MySQL 命令行连接到生产库后 source 本文件，或在数据库管理工具里运行
-- 注意：执行前请备份 cms_activity 表

-- 来源主题ID：由商家报名主题自动生成活动时回填；商家独立创建的活动该列为 NULL
ALTER TABLE cms_activity
    ADD COLUMN topic_id BIGINT NULL COMMENT '来源主题ID 商家报名主题生成时回填 独立创建活动为空'
    AFTER template_id;

CREATE INDEX idx_cms_activity_topic ON cms_activity(topic_id);
