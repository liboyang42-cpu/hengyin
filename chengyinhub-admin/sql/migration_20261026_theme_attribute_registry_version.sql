-- DEC02 A: lazy per-topic revision; no existing attribute/snapshot rewrite.
CREATE TABLE IF NOT EXISTS theme_attribute_registry_version (
    topic_id BIGINT NOT NULL,
    registry_version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
