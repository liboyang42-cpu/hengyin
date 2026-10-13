-- E3 商家拒绝官方邀约必须留原因；仅新增可空列，不改变既有邀约。
-- 合并前需先落库，再部署读取 handle_reason 的 jar。
ALTER TABLE coop_invite
    ADD COLUMN handle_reason VARCHAR(255) NULL COMMENT '受邀方拒绝原因' AFTER handle_time;
