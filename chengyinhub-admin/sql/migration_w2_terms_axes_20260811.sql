-- W2 合同三：合作单接受时冻结角色/范围/选集/证据/条款快照。
-- 只准备 SQL，不在本任务执行；MySQL 8，禁止 IF NOT EXISTS。
-- 执行前只读抽查（列尚不存在时用 information_schema 检查）：
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coop_order'
--   AND COLUMN_NAME IN ('role_snapshot','scope_type_snapshot','scope_id_snapshot',
--     'eligible_purchase_kinds_snapshot','fulfillment_evidence_rule_snapshot',
--     'terms_snapshot','deposit_amount_snapshot');

ALTER TABLE coop_order
    ADD COLUMN role_snapshot VARCHAR(32) NULL COMMENT '接受时服务端冻结的合同角色 SUPPLY/ORGANIZER' AFTER terms_version,
    ADD COLUMN scope_type_snapshot VARCHAR(32) NULL COMMENT '接受时冻结的履约范围类型 TOPIC/NODE' AFTER role_snapshot,
    ADD COLUMN scope_id_snapshot BIGINT(20) NULL COMMENT '接受时冻结的履约范围 ID' AFTER scope_type_snapshot,
    ADD COLUMN eligible_purchase_kinds_snapshot VARCHAR(128) NULL COMMENT '接受时服务端冻结的允许 purchase_kind 集合' AFTER scope_id_snapshot,
    ADD COLUMN fulfillment_evidence_rule_snapshot VARCHAR(64) NULL COMMENT '接受时服务端冻结的履约证据规则' AFTER eligible_purchase_kinds_snapshot,
    ADD COLUMN terms_snapshot TEXT NULL COMMENT '接受时服务端冻结的两轴/金额/权益条款 JSON' AFTER fulfillment_evidence_rule_snapshot,
    ADD COLUMN deposit_amount_snapshot DECIMAL(10,2) NULL COMMENT '独立于两轴的接受时保证金快照' AFTER terms_snapshot;

-- 存量无快照 order 不在运行时推断，只进入人工修复清单；必须返回 0 行才可宣称已补齐。
SELECT id, invite_id, topic_id, role_snapshot, scope_type_snapshot, scope_id_snapshot,
       eligible_purchase_kinds_snapshot, fulfillment_evidence_rule_snapshot, terms_snapshot
FROM coop_order
WHERE del_flag = 0
  AND (role_snapshot IS NULL OR scope_type_snapshot IS NULL OR scope_id_snapshot IS NULL
    OR eligible_purchase_kinds_snapshot IS NULL OR fulfillment_evidence_rule_snapshot IS NULL
    OR terms_snapshot IS NULL);
