-- Test migration only; preserves original drop log and A coupon source receipts.
CREATE TABLE IF NOT EXISTS roam_reward_delivery (
    drop_log_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    coupon_id BIGINT NULL,
    extra_xp INT NULL,
    reward_kind VARCHAR(8) NOT NULL,
    state VARCHAR(24) NOT NULL,
    history_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (drop_log_id),
    KEY idx_roam_delivery_state (state, drop_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS roam_reward_reconcile_cursor (
    id TINYINT NOT NULL PRIMARY KEY,
    after_id BIGINT NOT NULL,
    through_id BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_job(job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
    create_by, create_time, remark)
SELECT '原漫游掉落补交付', 'DEFAULT', 'roamRewardDeliveryTask.run()', '0 0/10 * * * ?', '3', '1', '1',
    'system', NOW(), '暂停；负责人:C包；启用条件:A原来源发券、掉落原日志与C当次XP金额快照已集成并完成测试账回读；生产启用另验。'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'roamRewardDeliveryTask.run()');

-- readback: Registered task remains paused and concurrency disabled.
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AND MAX(status = '1' AND concurrent = '1') = 1
FROM sys_job WHERE invoke_target = 'roamRewardDeliveryTask.run()';
-- readback: Delivery and cursor schema are present on the selected scratch database.
-- runner: assert=scalar-true
SELECT COUNT(*) = 2 FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('roam_reward_delivery', 'roam_reward_reconcile_cursor');
