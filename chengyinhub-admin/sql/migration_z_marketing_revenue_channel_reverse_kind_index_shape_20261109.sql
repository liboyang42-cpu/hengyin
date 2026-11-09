-- Exact additive replacement of migration_z_marketing_revenue_channel_reverse_kind_20261108.sql (sha256 99dab1cc3390cfc2f9139fbd901631570abb616df67776409ab31adec0a5930f).
-- Preserve every DDL/data statement and other assertion; verify both ordered full unique-index columns.
-- Fresh unapplied substitution requires a separately reviewed hash-bound runner contract; no APPLIED receipt.
-- M8-F2 追加：让收入投递队列接受 CHANNEL_REVERSE 类型（task_kind=3）。
--
-- 背景：M5 的 migration_marketing_revenue_allocation_20261108.sql 建表时
--   ck_revenue_posting_kind CHECK (task_kind IN (1,2))
-- 只允许应计(1)与微信退款冲销(2)。渠道(OTA)退款的事实主键与 refund_application.id
-- 是两套命名空间，必须用独立的 kind=3，否则唯一键 (task_kind, ref_id) 会让
-- 两套 id 互相顶掉。因此这里只做**最小增量**：放宽同一个 CHECK，不动其它列。
--
-- 依赖：必须在该建表迁移之后执行（文件名 z 前缀保证排序在后）。
-- Source-only ordered migration. 不执行生产 DDL；须走 chengyin-db-migrate 只读预检并由 root 决定落库时点。
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name='marketing_revenue_posting_task')=1
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND table_name='marketing_revenue_posting_task'
    AND constraint_name='ck_revenue_posting_kind' AND constraint_type='CHECK')=1
  AND (SELECT COUNT(*) = 2
          AND SUM(non_unique = 0 AND seq_in_index = 1 AND column_name = 'task_kind' AND sub_part IS NULL) = 1
          AND SUM(non_unique = 0 AND seq_in_index = 2 AND column_name = 'ref_id' AND sub_part IS NULL) = 1
        FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='marketing_revenue_posting_task'
          AND index_name='uk_revenue_posting_ref') = 1,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_revenue_channel_kind_preflight','$')) AS UNSIGNED) AS assertion_ok;

ALTER TABLE marketing_revenue_posting_task
    DROP CHECK ck_revenue_posting_kind;

ALTER TABLE marketing_revenue_posting_task
    ADD CONSTRAINT ck_revenue_posting_kind CHECK (task_kind IN (1,2,3));

-- 列注释同步为三型语义（只改注释，不改类型/默认值）。
ALTER TABLE marketing_revenue_posting_task
    MODIFY COLUMN task_kind TINYINT NOT NULL
        COMMENT '1=ACCRUE(按报名单) 2=REVERSE(微信退款单) 3=CHANNEL_REVERSE(渠道退款事实)';

ALTER TABLE marketing_revenue_posting_task
    MODIFY COLUMN ref_id BIGINT NOT NULL
        COMMENT 'ACCRUE→registrationId；REVERSE→refund_application.id；CHANNEL_REVERSE→marketing_channel_refund_verification.id';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND table_name='marketing_revenue_posting_task'
    AND constraint_name='ck_revenue_posting_kind' AND constraint_type='CHECK')=1
  AND (SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name='ck_revenue_posting_kind'
    AND check_clause LIKE '%3%')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_revenue_posting_task' AND column_name='task_kind'
    AND column_comment LIKE '%CHANNEL_REVERSE%')=1,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_revenue_channel_kind_readback','$')) AS UNSIGNED) AS assertion_ok;
