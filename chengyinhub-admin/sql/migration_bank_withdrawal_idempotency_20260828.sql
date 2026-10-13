-- 银行卡提现业务幂等 + 单会员单在途约束。
-- 生产只读回读（2026-08-28）：ums_member_withdrawal 总行数=0，仅 PRIMARY；本文件未执行。
-- 未经 chengyin-db-migrate 只读预检与总控确认不得执行。
-- 目标表：ums_member_withdrawal（状态 0=待审核，1=审核通过，2=驳回，3=已打款）。

-- ==================== 执行前只读预检（必须返回 0 行） ====================
-- 同一会员存在多笔未终结提现：有结果就必须逐单核对真实打款/退款事实，禁止直接删行或改状态凑唯一键。
SELECT member_id,
       COUNT(*) AS active_count,
       GROUP_CONCAT(id ORDER BY id) AS withdrawal_ids
FROM ums_member_withdrawal
WHERE del_flag = 0
  AND status IN (0, 1)
GROUP BY member_id
HAVING COUNT(*) > 1;

-- 在途行缺 member_id 会绕过 UNIQUE(NULL)；有结果同样必须先人工核对并修正归属。
SELECT id, member_id, status, del_flag, withdrawal_amount, create_time
FROM ums_member_withdrawal
WHERE del_flag = 0
  AND status IN (0, 1)
  AND member_id IS NULL;

-- 半迁移/重复执行预检：必须返回 0 行；有结果就停止，不得执行下面 ALTER。
SELECT 'column' AS object_type, column_name AS object_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND column_name IN ('request_id', 'request_fingerprint', 'pending_member_key')
UNION ALL
SELECT 'index' AS object_type, index_name AS object_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND index_name IN ('uk_withdrawal_member_request', 'uk_withdrawal_pending_member')
UNION ALL
SELECT 'constraint' AS object_type, constraint_name AS object_name
FROM information_schema.table_constraints
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND constraint_name = 'chk_withdrawal_member_id_not_null';

-- ==================== 迁移主体 ====================
-- 上述三段只读预检由总控单独确认全部为 0 行后，才执行本段；本文件不使用 ADD COLUMN IF NOT EXISTS。
ALTER TABLE ums_member_withdrawal
    ADD COLUMN request_id VARCHAR(64) NOT NULL
        COMMENT '客户端业务幂等键；同会员内唯一且不得为空' AFTER member_id,
    ADD COLUMN request_fingerprint CHAR(64) NOT NULL
        COMMENT '规范化请求指纹；同 key 异参数必须拒绝' AFTER request_id,
    ADD COLUMN pending_member_key BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN del_flag = 0 AND status IN (0, 1) THEN member_id ELSE NULL END
        ) STORED
        COMMENT '单会员单在途唯一键' AFTER request_fingerprint,
    ADD CONSTRAINT chk_withdrawal_member_id_not_null CHECK (member_id IS NOT NULL),
    ADD UNIQUE KEY uk_withdrawal_member_request (member_id, request_id),
    ADD UNIQUE KEY uk_withdrawal_pending_member (pending_member_key);

-- ==================== 执行后只读回读（列约束、生成表达式、索引列序必须匹配） ====================
SELECT column_name, column_type, is_nullable, generation_expression
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND column_name IN ('member_id', 'request_id', 'request_fingerprint', 'pending_member_key')
ORDER BY ordinal_position;

SELECT index_name,
       non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND index_name IN ('PRIMARY', 'uk_withdrawal_member_request', 'uk_withdrawal_pending_member')
GROUP BY index_name, non_unique
ORDER BY index_name;
