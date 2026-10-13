-- B1 主题草稿态:给 cms_topic 加 is_draft(存了未提审=草稿,仅作者可见)
-- 安全:is_draft 为隔离字段,只被 updateTopicDraftState / selectDraftTopicIds 引用,
--       不进 insertCmsTopic / updateCmsTopic 通用语句,故本迁移未落库前存量建/改主题不受影响。
--       且 saveDraft 是新前端参数,旧客户端不传 → 未落库前无人触发草稿路径。
ALTER TABLE cms_topic
  ADD COLUMN is_draft tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否草稿(存了未提审,1=仅作者可见)';

CREATE INDEX idx_cms_topic_is_draft ON cms_topic (is_draft);
