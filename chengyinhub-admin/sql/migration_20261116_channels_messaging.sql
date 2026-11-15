-- W5 C2 channels-messaging（roadmap slug channels-messaging）：消息表情回应 + 回复/话题引用索引。
--
-- 回滚说明（rollback）：
--   DROP TABLE IF EXISTS im_message_reaction;
--   DROP TABLE IF EXISTS im_message_ref;
--   两张表都是本迁移新建的，删掉不影响 im_message 及任何既有表；代码回滚后回复/话题只剩 extra_json 里的 id，
--   表情回应消失。
--
-- 只新增表，不改 im_message（大表，不加列）：回复与话题关系另存 im_message_ref，消息正文仍在 im_message，
-- 引用摘要读取时按当前消息状态现算（撤回/未过审/注销的内容不会被复制进别人的消息）。
-- 提及沿用 migration_20261112_im_channels_platform.sql 的 im_message_mention（发送时同事务写入）。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 依赖：migration_20261112_im_channels_platform.sql（im_message_mention）、migration_im.sql（im_message）。

-- 1) 表情回应：每人每条消息每个表情一行；表情只收服务端白名单里的固定集合（不是自由文本，无需内容审核）。
CREATE TABLE IF NOT EXISTS im_message_reaction (
    message_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    emoji VARCHAR(16) NOT NULL COMMENT '白名单 key，如 thumbs_up',
    conversation_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (message_id, member_id, emoji),
    KEY idx_message_reaction_member (member_id),
    KEY idx_message_reaction_conv (conversation_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息表情回应';

-- 2) 回复与话题：reply_to_id = 被引用的消息；thread_root_id = 话题首条消息（话题内回复按它取列表）。
CREATE TABLE IF NOT EXISTS im_message_ref (
    message_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    reply_to_id BIGINT NOT NULL DEFAULT 0,
    thread_root_id BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (message_id),
    KEY idx_message_ref_thread (conversation_id, thread_root_id, message_id),
    KEY idx_message_ref_reply (reply_to_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息回复/话题引用';
