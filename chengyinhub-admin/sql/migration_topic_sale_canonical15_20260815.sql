-- 探店日 1-2：canonical-15 PRICING 草稿真源 + SELLING 不可变快照。
-- 本任务只准备 SQL，严禁在生产执行；落库前必须按 chengyin-db-migrate 做只读预检并取得授权。
-- 两表均为新表；支持失败后的重放与只落第一表时的恢复。

CREATE TABLE IF NOT EXISTS topic_sale_canonical_draft (
    topic_id BIGINT NOT NULL COMMENT 'cms_topic.id，一期一份可编辑草稿',
    schema_version VARCHAR(32) NOT NULL COMMENT '仅 CANONICAL_15_V1',
    parameter_version BIGINT NOT NULL COMMENT '每次保存单调+1，禁止值改回后复用旧审批',
    canonical_json LONGTEXT NOT NULL COMMENT 'A01-A15 typed JSON/附件引用',
    updated_by BIGINT NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (topic_id),
    CONSTRAINT ck_topic_sale_draft_version CHECK (parameter_version > 0),
    CONSTRAINT ck_topic_sale_draft_schema CHECK (schema_version = 'CANONICAL_15_V1')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店日开售 canonical15 草稿真源';

CREATE TABLE IF NOT EXISTS topic_sale_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    parameter_version BIGINT NOT NULL,
    source_review_task_id BIGINT NOT NULL COMMENT '明确 APPROVED 的 TOPIC_SALE_REVIEW',
    canonical_json LONGTEXT NOT NULL,
    frozen_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_sale_snapshot_topic (topic_id),
    UNIQUE KEY uk_topic_sale_snapshot_review (source_review_task_id),
    CONSTRAINT ck_topic_sale_snapshot_version CHECK (parameter_version > 0),
    CONSTRAINT ck_topic_sale_snapshot_schema CHECK (schema_version = 'CANONICAL_15_V1')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='finalizeForSale 同事务冻结的不可变 canonical15';

-- runner: assert=scalar-true
SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM cms_topic t
    LEFT JOIN topic_sale_snapshot s ON s.topic_id = t.id
    WHERE t.del_flag <> 2
      AND t.product_type = 2
      AND t.lifecycle = 3
      AND (t.selling_started_at IS NULL OR t.selling_started_at >= '2026-08-15 00:00:00')
      AND s.topic_id IS NULL
) THEN 1 ELSE 0 END AS all_existing_selling_shop_days_have_snapshot;

-- 落库后换新只读连接回读：两表 ENGINE=InnoDB、两个 CHECK=ENFORCED、两个 UNIQUE 均存在。
-- 不得用 runner 成功回执或本次请求 DTO 回显代替 information_schema / 业务表读回。
