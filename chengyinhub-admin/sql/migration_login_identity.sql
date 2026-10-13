-- 原生微信登录 + Sign in with Apple 身份字段
-- 执行方式：连生产库 source 本文件（或用 db-migrate skill 跑）
-- 背景：ums_member 原只有 wx_open_id（小程序 openid）。原生微信 App 登录靠
--       微信开放平台 unionid 与小程序账号归一；Apple 登录靠 identityToken 的 sub。
--       本迁移补齐 union_id / apple_id 两列并各加普通索引（按身份查重）。

ALTER TABLE ums_member
    ADD COLUMN union_id varchar(64)  NULL COMMENT '微信开放平台unionid' AFTER wx_open_id,
    ADD COLUMN apple_id varchar(128) NULL COMMENT 'Apple sub'          AFTER union_id;

ALTER TABLE ums_member
    ADD INDEX idx_ums_member_union_id (union_id),
    ADD INDEX idx_ums_member_apple_id (apple_id);
