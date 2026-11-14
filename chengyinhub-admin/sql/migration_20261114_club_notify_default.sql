-- 俱乐部共享通知默认（App Core/NativeChannelNotification* clubDefaultRead/clubDefaultSave，GET/POST /api/club/settings/notify-level）。
-- 与 im_notify_pref(scope_type=2) 的“本人对俱乐部的偏好”不同：这一行是俱乐部级、所有成员共享的默认等级。
-- 未配置 = 无行（读回 version 0、level 1 仅@）。只新增表；不回填、不改既有行。
-- 依赖：20261113_im_channels_second_pass（NOTIFY 回执族）。
CREATE TABLE IF NOT EXISTS im_club_notify_default (
    club_id BIGINT NOT NULL,
    level TINYINT NOT NULL COMMENT '0 全部 1 仅@',
    version BIGINT NOT NULL COMMENT '每次保存 +1 的 CAS 版本，首次保存为 1',
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (club_id),
    CONSTRAINT ck_club_notify_default_level CHECK (level IN (0, 1)),
    CONSTRAINT ck_club_notify_default_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部共享通知默认等级';
