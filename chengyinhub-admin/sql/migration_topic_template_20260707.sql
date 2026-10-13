-- T7 主题模板"另存":给 cms_topic 加 is_template 标记
-- 语义:把一个已发布主题整套「另存为主题模板」,别人 /use 复制即用(不新建 topic-template 表)。
-- 安全:is_template 为隔离字段,只被 selectTopicTemplateList / updateTopicIsTemplate / selectTopicIsTemplate 引用,
--       不进 insertCmsTopic / updateCmsTopic 通用语句,故本迁移未落库前存量建/改主题不受影响。
ALTER TABLE cms_topic
  ADD COLUMN is_template tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否主题模板(1=已另存为模板,可复制即用)';

CREATE INDEX idx_cms_topic_is_template ON cms_topic (is_template);
