-- R10-03 广场发布幂等:客户端稳定发布意图键(丢回包重试不得再 insert)
--
-- 背景: /api/creativesquare/action 发布只有「请求在途防连点」,没有跨重试/冷恢复的稳定意图键。
--       服务端已写入但客户端丢响应后,用户重试会再 insert 一条 → 重复帖子、重复审核/奖励。
-- 方案: 客户端在首写前生成并持久化 request_id(按账号+草稿隔离),重试复用;
--       服务端 (member_id, request_id) 唯一 + 查重回读,同键不同载荷(sha256)判冲突。
--
-- ⚠️ 本文件只落盘,本次修复不在生产执行;由总控按迁移流程(只读预检 + 确认)后运行。
-- ⚠️ 历史行 request_id 为 NULL,唯一键对 NULL 不冲突;不删除/回填任何历史帖子与资金。

ALTER TABLE ums_creative_square
    ADD COLUMN request_id VARCHAR(64) NULL COMMENT '客户端发布意图幂等键(账号内唯一)' AFTER del_flag,
    ADD COLUMN request_signature VARCHAR(64) NULL COMMENT '发布意图载荷 sha256;同键不同载荷=冲突' AFTER request_id,
    ADD UNIQUE KEY uk_creative_square_member_request (member_id, request_id);
