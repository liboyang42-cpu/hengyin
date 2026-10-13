-- 商家 Owner/邀请接受按 member_id 判定身份时的锁定读索引。
-- 仅准备 migration，本分支不执行；生产落库前须完成只读 preflight、授权与执行后 schema readback。
-- MySQL 8 secondary-index 创建使用 INPLACE + LOCK=NONE，避免无索引的 COUNT ... FOR UPDATE 扫描并锁住全表范围。

SET @mms_merchant_member_index_exact := (
    SELECT COUNT(*) = 1
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'mms_merchant'
          AND index_name = 'idx_mms_merchant_member'
        GROUP BY index_name
        HAVING COUNT(*) = 1
           AND SUM(seq_in_index = 1
               AND column_name = 'member_id'
               AND non_unique = 1
               AND index_type = 'BTREE'
               AND sub_part IS NULL
               AND is_visible = 'YES') = 1
    ) exact_index
);
SET @mms_merchant_member_index_name_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mms_merchant'
      AND index_name = 'idx_mms_merchant_member'
);
SET @mms_merchant_member_index_sql := IF(
    @mms_merchant_member_index_exact = 1,
    'SELECT ''idx_mms_merchant_member already exists with exact shape, skip''',
    IF(
        @mms_merchant_member_index_name_exists = 0,
        'ALTER TABLE mms_merchant ADD INDEX idx_mms_merchant_member (member_id), ALGORITHM=INPLACE, LOCK=NONE',
        'SELECT ''idx_mms_merchant_member exists with unexpected shape; final readback must fail'''
    )
);
PREPARE mms_merchant_member_index_stmt FROM @mms_merchant_member_index_sql;
EXECUTE mms_merchant_member_index_stmt;
DEALLOCATE PREPARE mms_merchant_member_index_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mms_merchant'
      AND index_name = 'idx_mms_merchant_member'
    GROUP BY index_name
    HAVING COUNT(*) = 1
       AND SUM(seq_in_index = 1
           AND column_name = 'member_id'
           AND non_unique = 1
           AND index_type = 'BTREE'
           AND sub_part IS NULL
           AND is_visible = 'YES') = 1
) verified_mms_merchant_member_index;

-- 回滚（确认没有查询依赖后）：
-- ALTER TABLE mms_merchant DROP INDEX idx_mms_merchant_member, ALGORITHM=INPLACE, LOCK=NONE;
