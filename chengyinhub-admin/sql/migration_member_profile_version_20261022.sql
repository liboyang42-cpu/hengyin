-- Lane W10b #25(2026-10-22):个人资料版本化提交记录 + 头像审核回执。
-- 每次 /api/user/update 成功写一行(version_no 按会员递增),只记「改了哪些字段」与本次头像引用/送检媒体哈希,
-- 不复制昵称、简介等文本内容。头像审核状态不在这里落库:回执时按 avatar_media_hash 读
-- wx_content_callback_inbox(内容安全回调的权威表)实时推导,避免两处状态分叉。
-- 新表,无回填;幂等(CREATE TABLE IF NOT EXISTS)。必须先于新 jar 落库,按 chengyin-db-migrate 流程只读预检 + 用户确认后执行。
-- 新 jar 在表缺失时只记 WARN、不影响资料保存(回执返回 profileVersion=null)。
-- 回滚:DROP TABLE ums_member_profile_version;
CREATE TABLE IF NOT EXISTS ums_member_profile_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '提交者',
    version_no INT NOT NULL COMMENT '该会员的资料版本号,从 1 递增',
    changed_fields VARCHAR(255) NOT NULL DEFAULT '' COMMENT '本次提交的字段名(逗号分隔),不存内容',
    avatar_ref VARCHAR(255) NULL COMMENT '本次写入的头像引用;未改头像为 NULL',
    avatar_media_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次送检头像 URL 的 SHA-256(小写十六进制)',
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_profile_version (member_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人资料版本化提交记录(审核回执用)';
