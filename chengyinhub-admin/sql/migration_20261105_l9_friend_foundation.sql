-- L9 friendship storage foundation, MySQL 8.0.16+.
-- Source-only preparation: apply only through the reviewed migration process.
-- Does not install API handlers, change authentication, or enable a DM policy.
-- Existing tables are never rewritten by this file. IF NOT EXISTS is rerunnable,
-- not a schema-upgrade mechanism; inspect existing table shapes before deployment.

CREATE TABLE IF NOT EXISTS member_friend_group (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(12) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    is_default TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_name (member_id, name),
    KEY idx_group_pos (member_id, position),
    CONSTRAINT chk_group_owner CHECK (member_id > 0),
    CONSTRAINT chk_group_default CHECK (is_default IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One unordered pair stores the current request and friendship state.
-- status: 0 pending, 1 friend, 2 rejected, 3 withdrawn/expired/removed/blocked.
-- requester_id retains historical provenance even when status becomes 1.
-- The service adapter must translate non-pending requester_id to zero for
-- FriendPairRules.Pair; it must not persist Pair.requesterId() blindly.
CREATE TABLE IF NOT EXISTS member_friend (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    low_member_id BIGINT UNSIGNED NOT NULL,
    high_member_id BIGINT UNSIGNED NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    requester_id BIGINT UNSIGNED NOT NULL,
    request_msg VARCHAR(60) NULL,
    source_type TINYINT NOT NULL DEFAULT 0,
    source_id BIGINT UNSIGNED NULL,
    expire_time DATETIME NOT NULL,
    acted_time DATETIME NULL,
    friend_since DATETIME NULL,
    reject_reason TINYINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_pair (low_member_id, high_member_id),
    KEY idx_low_status (low_member_id, status, update_time),
    KEY idx_high_status (high_member_id, status, update_time),
    KEY idx_requester_expire (requester_id, status, expire_time),
    CONSTRAINT chk_friend_pair_order CHECK (low_member_id > 0 AND low_member_id < high_member_id),
    CONSTRAINT chk_friend_status CHECK (status BETWEEN 0 AND 3),
    CONSTRAINT chk_friend_source CHECK (source_type BETWEEN 0 AND 5),
    CONSTRAINT chk_friend_requester CHECK (requester_id IN (low_member_id, high_member_id)),
    CONSTRAINT chk_friend_since CHECK (status <> 1 OR friend_since IS NOT NULL),
    CONSTRAINT chk_friend_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Owner-scoped preferences. Group ownership and active friendship admission
-- remain service responsibilities; no FK is added to existing member/IM tables.
CREATE TABLE IF NOT EXISTS member_friend_meta (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    friend_member_id BIGINT UNSIGNED NOT NULL,
    remark VARCHAR(30) NULL,
    group_id BIGINT UNSIGNED NULL,
    starred TINYINT NOT NULL DEFAULT 0,
    progress_scope TINYINT NOT NULL DEFAULT 1,
    muted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_meta_pair (member_id, friend_member_id),
    KEY idx_meta_group (member_id, group_id, starred),
    CONSTRAINT chk_meta_scope CHECK (progress_scope IN (0, 1)),
    CONSTRAINT chk_meta_flags CHECK (starred IN (0, 1) AND muted IN (0, 1)),
    CONSTRAINT chk_meta_self CHECK (member_id > 0 AND friend_member_id > 0 AND member_id <> friend_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
