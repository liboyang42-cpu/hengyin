-- Store only the audited structured draft result; never persist the original idea.
CREATE TABLE ai_theme_draft_request (
    user_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_json MEDIUMTEXT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (user_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
