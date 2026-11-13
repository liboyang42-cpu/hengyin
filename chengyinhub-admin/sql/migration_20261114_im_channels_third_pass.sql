-- 频道第三批（native App 合同 docs/channels/contract.md §6.12/§6.14，App 仓 ui/channels-contract）。
-- 只新增表与默认引导配置；不改既有行；本地/测试库候选，不在生产执行。
-- 依赖：20261113_im_channels_second_pass（回执族已含 MEDIA/ONBOARD）、20261012_app_media_asset（媒体审核事实）。

-- 1) IM 文件/视频/语音上传台账。字节仍在私有 OSS 对象，审核事实仍是 app_media_asset.review_state（HOLD/APPROVED/REJECTED）；
--    本表只记「谁在哪个会话、用哪个原请求、传了哪份字节」以及之后挂到哪条消息。不转码：时长/封面/宽高未知时为 NULL。
CREATE TABLE IF NOT EXISTS im_media_upload (
    id BIGINT NOT NULL AUTO_INCREMENT,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FILE/VIDEO/VOICE',
    owner_member_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    upload_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id BIGINT NULL COMMENT 'app_media_asset.id；对象写入并登记审核后才有',
    file_name VARCHAR(240) NOT NULL,
    ext VARCHAR(8) CHARACTER SET ascii NOT NULL,
    mime VARCHAR(128) CHARACTER SET ascii NOT NULL,
    byte_size BIGINT NOT NULL,
    duration_ms INT NULL COMMENT '不探测不转码：NULL = 未知',
    width INT NULL,
    height INT NULL,
    cover_asset_id BIGINT NULL COMMENT '不生成封面：NULL = 无',
    state VARCHAR(12) CHARACTER SET ascii NOT NULL DEFAULT 'RESERVED',
    message_id BIGINT NULL COMMENT '已挂接的 im_message.id；挂接后不可删除',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_media_request (owner_member_id, kind, upload_request_id),
    UNIQUE KEY uk_im_media_message (message_id),
    KEY idx_im_media_asset (asset_id),
    KEY idx_im_media_conversation (conversation_id, id),
    CONSTRAINT ck_im_media_kind CHECK (kind IN ('FILE','VIDEO','VOICE')),
    CONSTRAINT ck_im_media_state CHECK (state IN ('RESERVED','UPLOADED','DELETED')),
    CONSTRAINT ck_im_media_size CHECK (byte_size > 0),
    CONSTRAINT ck_im_media_uploaded_asset CHECK (state <> 'UPLOADED' OR asset_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IM 媒体上传台账';

-- 2) 入会引导：问题/选项配置与成员答案。没有后台编辑面时由默认配置兜底（每个俱乐部一题三选项，不授角色、不加频道）。
CREATE TABLE IF NOT EXISTS im_onboarding_prompt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    single_select TINYINT NOT NULL DEFAULT 0,
    required TINYINT NOT NULL DEFAULT 1,
    enabled TINYINT NOT NULL DEFAULT 1,
    sort_no INT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_onboarding_prompt_slot (guild_kind, guild_id, sort_no),
    CONSTRAINT ck_onboarding_prompt_kind CHECK (guild_kind IN (1,3)),
    CONSTRAINT ck_onboarding_prompt_flags CHECK (single_select IN (0,1) AND required IN (0,1) AND enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入会引导问题';

CREATE TABLE IF NOT EXISTS im_onboarding_option (
    id BIGINT NOT NULL AUTO_INCREMENT,
    prompt_id BIGINT NOT NULL,
    label VARCHAR(50) NOT NULL,
    description VARCHAR(200) NOT NULL DEFAULT '',
    emoji VARCHAR(32) NOT NULL DEFAULT '',
    sort_no INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_onboarding_option_slot (prompt_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入会引导选项（本批不带授角色/加频道效果）';

CREATE TABLE IF NOT EXISTS im_onboarding_answer (
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    configuration_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answers_json JSON NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (guild_kind, guild_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入会引导成员答案（最新一次）';

-- 3) 默认引导配置：每个未删除俱乐部一题（多选、必答）三选项。重复执行不重复插入；新俱乐部由服务端读时按同一默认补种。
INSERT IGNORE INTO im_onboarding_prompt (guild_kind, guild_id, title, single_select, required, enabled, sort_no)
SELECT 1, c.id, '你最想在这里做什么？', 0, 1, 1, 1 FROM club c WHERE c.del_flag = 0;

INSERT IGNORE INTO im_onboarding_option (prompt_id, label, description, emoji, sort_no)
SELECT p.id, d.label, d.description, d.emoji, d.sort_no
  FROM im_onboarding_prompt p
  JOIN (SELECT '认识同好' AS label, '和兴趣相近的人聊聊' AS description, '👋' AS emoji, 1 AS sort_no
        UNION ALL SELECT '参加活动', '看看最近有什么活动可以一起玩', '🎯', 2
        UNION ALL SELECT '先随便看看', '先逛逛，之后再决定', '👀', 3) d
 WHERE p.guild_kind = 1 AND p.sort_no = 1 AND p.title = '你最想在这里做什么？'
   AND NOT EXISTS (SELECT 1 FROM im_onboarding_option o WHERE o.prompt_id = p.id);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'im_media_upload';
-- runner: assert=scalar-true
SELECT COUNT(*) = 0 FROM im_onboarding_prompt p WHERE NOT EXISTS (SELECT 1 FROM im_onboarding_option o WHERE o.prompt_id = p.id);
