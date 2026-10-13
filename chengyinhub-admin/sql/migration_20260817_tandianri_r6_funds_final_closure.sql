-- 探店日 R6 资金闭环：FINAL 逐票金额归因、四分量终结头，以及 0817 component CHECK 自愈。
--
-- 【类别】③ 要在库上跑（DDL + 历史头回填），必须先于包含新 mapper 的 jar。
-- 【前置】funds_core 建 topic_edition_funds；club_compensation 建 finalization/payable；
--          ticket_source_payable 把 TICKET_SOURCE 加入 payable 枚举。
-- 【幂等】所有 ALTER 先查 information_schema；CREATE IF NOT EXISTS；回填只补 0 值历史头。
-- 【可逆】确认 attribution 表无行、finalization.ticket_source_amount 全为 0 且 payable 无
--          TICKET_SOURCE 后，可删 attribution 表/新列，并把两个 CHECK 恢复到旧定义；禁止删业务行回滚。
--
-- 【写前 guard 在任何建表 / 加列 / 回填之前】半落库或被人手改过的库里同名对象的形状可能是错的：
--   `CREATE TABLE IF NOT EXISTS` 与存在性预检都会把同名错对象当成已完成。
--   guard① 钉 attribution 表（列 / 生成列 / 唯一键 / 外键 / 两条 CHECK），guard② 钉终结头新列，
--   两条与文件末端的观测【用同一套判据】，任一不符即在破坏性写入之前红。
-- 【EXTERNAL_GATE】负控只能在真 Oracle MySQL 8 上造错 schema 再重放：
--   ①同名 attribution 表缺唯一键 / 生成列表达式不对 / CHECK 语义相反 → guard① 红；
--   ②同名 ticket_source_amount 列类型或 nullable 不对 → guard② 红。
--   正向路径（正确落库 ⇒ 本文件 11 条回读全为 1）由 TandianriBatch3FundsMybatisIntegrationTest
--   的 finalClosureMigrationReplaysAndEveryDeclaredReadbackHolds 在真库上承重。

-- 写前 fail-closed guard①：还没有这张表（首次落库）或它的形状完全正确，两者之外一律红。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution') = 0
    OR ((SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
            AND ((column_name = 'funds_snapshot_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
              OR (column_name = 'source_club_id'    AND LOWER(data_type) = 'bigint' AND is_nullable = 'YES')
              OR (column_name = 'ticket_count'      AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
              OR (column_name = 'earned_amount'     AND LOWER(data_type) = 'decimal'
                  AND numeric_precision = 14 AND numeric_scale = 2 AND is_nullable = 'NO'))) = 4
   AND (SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
            AND column_name = 'source_key'
            AND LOWER(data_type) = 'bigint'
            AND UPPER(extra) = 'STORED GENERATED'
            AND REGEXP_REPLACE(LOWER(generation_expression),
                    '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') <=> 'coalescesource_club_id,0') = 1
   AND (SELECT COUNT(*) FROM (
            SELECT index_name
              FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
               AND index_name = 'uk_edition_funds_attribution_source'
             GROUP BY index_name
            HAVING COUNT(*) <=> 2
               AND MIN(non_unique) <=> 0
               AND MIN(index_type) <=> 'BTREE' AND MAX(index_type) <=> 'BTREE'
               AND COUNT(sub_part) <=> 0
               AND MIN(is_visible) <=> 'YES'
               AND SUM(seq_in_index = 1 AND column_name = 'funds_snapshot_id') <=> 1
               AND SUM(seq_in_index = 2 AND column_name = 'source_key') <=> 1
        ) exact_attribution_unique_key) = 1
   AND (SELECT COUNT(*) FROM information_schema.key_column_usage
          WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
            AND constraint_name = 'fk_edition_funds_attribution_snapshot'
            AND column_name = 'funds_snapshot_id'
            AND referenced_table_name = 'topic_edition_funds'
            AND referenced_column_name = 'id') = 1
   AND (SELECT COUNT(*) FROM information_schema.check_constraints
          WHERE constraint_schema = DATABASE()
            AND constraint_name = 'ck_edition_funds_attribution_source'
            AND REGEXP_REPLACE(LOWER(check_clause),
                    '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
                <=> 'source_club_idisnullorsource_club_id>0') = 1
   AND (SELECT COUNT(*) FROM information_schema.check_constraints
          WHERE constraint_schema = DATABASE()
            AND constraint_name = 'ck_edition_funds_attribution_amounts'
            AND REGEXP_REPLACE(LOWER(check_clause),
                    '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
                <=> 'ticket_count>0andearned_amount>=0') = 1);

-- 写前 fail-closed guard②：终结头新列若已存在，类型/精度/nullable 必须已经对；
-- 它下面紧跟着一条真写数据的回填 UPDATE，形状不对时绝不能先把钱写进去。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
           AND column_name = 'ticket_source_amount') = 0
    OR (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
           AND column_name = 'ticket_source_amount'
           AND LOWER(data_type) = 'decimal' AND numeric_precision = 14 AND numeric_scale = 2
           AND is_nullable = 'NO') = 1;

CREATE TABLE IF NOT EXISTS topic_edition_funds_attribution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    funds_snapshot_id BIGINT NOT NULL,
    source_club_id BIGINT NULL COMMENT '支付时冻结的票源俱乐部；NULL=平台自然流量',
    ticket_count BIGINT NOT NULL,
    earned_amount DECIMAL(14,2) NOT NULL,
    source_key BIGINT GENERATED ALWAYS AS (COALESCE(source_club_id, 0)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_edition_funds_attribution_source (funds_snapshot_id, source_key),
    KEY idx_edition_funds_attribution_club (source_club_id, funds_snapshot_id),
    CONSTRAINT fk_edition_funds_attribution_snapshot
        FOREIGN KEY (funds_snapshot_id) REFERENCES topic_edition_funds(id),
    CONSTRAINT ck_edition_funds_attribution_source
        CHECK (source_club_id IS NULL OR source_club_id > 0),
    CONSTRAINT ck_edition_funds_attribution_amounts
        CHECK (ticket_count > 0 AND earned_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='FINAL 六量同事务固化的逐票金额归因；E2 不回读可变报名表';

SET @r6_ticket_source_column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
       AND column_name = 'ticket_source_amount'
);
SET @r6_ticket_source_column_sql := IF(
    @r6_ticket_source_column_exists = 0,
    'ALTER TABLE topic_edition_finalization ADD COLUMN ticket_source_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00 AFTER extra_amount',
    'SELECT 1'
);
PREPARE r6_ticket_source_column_stmt FROM @r6_ticket_source_column_sql;
EXECUTE r6_ticket_source_column_stmt;
DEALLOCATE PREPARE r6_ticket_source_column_stmt;

-- 只用 FINAL 首次计提那一行回填历史终结头；红冲/重计提属于后续台账事实，不改写原始头。
UPDATE topic_edition_finalization f
LEFT JOIN club_payable p
  ON p.topic_id = f.topic_id
 AND p.club_id = f.club_id
 AND p.component = 'TICKET_SOURCE'
 AND p.entry_type = 'ACCRUAL'
 AND p.idempotency_key = CONCAT('FINAL:', f.topic_id)
SET f.ticket_source_amount = COALESCE(p.amount, 0.00)
WHERE f.ticket_source_amount = 0.00;

-- 历史/半落库环境可能缺 CHECK，也可能仍是三分量旧定义；两种都安全收敛到四分量。
SET @r6_final_amount_check_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'topic_edition_finalization'
       AND constraint_name = 'ck_topic_edition_final_amounts' AND constraint_type = 'CHECK'
);
SET @r6_final_amount_check_sql := IF(
    @r6_final_amount_check_exists = 1,
    'ALTER TABLE topic_edition_finalization DROP CHECK ck_topic_edition_final_amounts, ADD CONSTRAINT ck_topic_edition_final_amounts CHECK (fixed_compensation >= 0 AND quality_amount >= 0 AND extra_amount >= 0 AND ticket_source_amount >= 0)',
    'ALTER TABLE topic_edition_finalization ADD CONSTRAINT ck_topic_edition_final_amounts CHECK (fixed_compensation >= 0 AND quality_amount >= 0 AND extra_amount >= 0 AND ticket_source_amount >= 0)'
);
PREPARE r6_final_amount_check_stmt FROM @r6_final_amount_check_sql;
EXECUTE r6_final_amount_check_stmt;
DEALLOCATE PREPARE r6_final_amount_check_stmt;

-- 修复 0817 直接 DROP CHECK 的半落库风险：缺约束时 ADD，存在时原子替换；重放仍是同一结果。
SET @r6_component_check_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'club_payable'
       AND constraint_name = 'ck_club_payable_component' AND constraint_type = 'CHECK'
);
SET @r6_component_check_sql := IF(
    @r6_component_check_exists = 1,
    'ALTER TABLE club_payable DROP CHECK ck_club_payable_component, ADD CONSTRAINT ck_club_payable_component CHECK (component IN (''FIXED'', ''QUALITY'', ''EXTRA'', ''TICKET_SOURCE''))',
    'ALTER TABLE club_payable ADD CONSTRAINT ck_club_payable_component CHECK (component IN (''FIXED'', ''QUALITY'', ''EXTRA'', ''TICKET_SOURCE''))'
);
PREPARE r6_component_check_stmt FROM @r6_component_check_sql;
EXECUTE r6_component_check_stmt;
DEALLOCATE PREPARE r6_component_check_stmt;

-- 回读 MySQL 自己持久化的表/列/约束，不拿刚提交的 SQL 字符串自证。
-- 只判「表存在」对「同名表缺唯一键 / 生成列表达式不对 / 外键指向别处」全恒绿，
-- 而这张表的唯一键正是「同一快照同一俱乐部只能有一条归因行」的库侧防线 ——
-- 它缺失即等于票源奖励可以被重复计提。以下四条把列 / 生成列 / 唯一键 / 外键分开钉。
-- runner: assert=scalar-true
SELECT COUNT(*) = 4
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
   AND ((column_name = 'funds_snapshot_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
     OR (column_name = 'source_club_id'    AND LOWER(data_type) = 'bigint' AND is_nullable = 'YES')
     OR (column_name = 'ticket_count'      AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
     OR (column_name = 'earned_amount'     AND LOWER(data_type) = 'decimal'
         AND numeric_precision = 14 AND numeric_scale = 2 AND is_nullable = 'NO'));

-- 生成列回读：表达式换成 COALESCE(source_club_id, -1) 或改成 VIRTUAL 都必须红 ——
-- source_key 是唯一键里代表「平台」的那一位，它算错，去重键就落在错误的等价类上。
-- 排错：SELECT generation_expression FROM information_schema.columns
--       WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
--         AND column_name = 'source_key';
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
   AND column_name = 'source_key'
   AND LOWER(data_type) = 'bigint'
   AND UPPER(extra) = 'STORED GENERATED'
   AND REGEXP_REPLACE(LOWER(generation_expression),
           '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') <=> 'coalescesource_club_id,0';

-- 唯一索引回读：对【全部 key part 聚合】判，不按 seq_in_index 过滤 ——
-- 过滤式判据对 (funds_snapshot_id, source_key, id) 这类复合键恒绿。
-- 列序颠倒、非唯一、前缀、INVISIBLE 都必须红。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
    SELECT index_name
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
       AND index_name = 'uk_edition_funds_attribution_source'
     GROUP BY index_name
    HAVING COUNT(*) <=> 2
       AND MIN(non_unique) <=> 0
       AND MIN(index_type) <=> 'BTREE' AND MAX(index_type) <=> 'BTREE'
       AND COUNT(sub_part) <=> 0
       AND MIN(is_visible) <=> 'YES'
       AND SUM(seq_in_index = 1 AND column_name = 'funds_snapshot_id') <=> 1
       AND SUM(seq_in_index = 2 AND column_name = 'source_key') <=> 1
) exact_attribution_unique_key;

-- 外键回读：必须证明它指向 topic_edition_funds(id)，而不是只证明「有个叫这名字的约束」。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM information_schema.key_column_usage
 WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
   AND constraint_name = 'fk_edition_funds_attribution_snapshot'
   AND column_name = 'funds_snapshot_id'
   AND referenced_table_name = 'topic_edition_funds'
   AND referenced_column_name = 'id';

-- attribution 两条关键 CHECK 的语义回读：归一后逐字符相等，能区分 > 0 与 >= 0、IS NULL 与 IS NOT NULL。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM information_schema.check_constraints
 WHERE constraint_schema = DATABASE()
   AND constraint_name = 'ck_edition_funds_attribution_source'
   AND REGEXP_REPLACE(LOWER(check_clause),
           '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
       <=> 'source_club_idisnullorsource_club_id>0';

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM information_schema.check_constraints
 WHERE constraint_schema = DATABASE()
   AND constraint_name = 'ck_edition_funds_attribution_amounts'
   AND REGEXP_REPLACE(LOWER(check_clause),
           '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
       <=> 'ticket_count>0andearned_amount>=0';

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
  AND column_name = 'ticket_source_amount' AND is_nullable = 'NO'
  AND LOWER(data_type) = 'decimal' AND numeric_precision = 14 AND numeric_scale = 2;

-- CHECK 回读不用 LIKE：'%TICKET_SOURCE%' 对 component IN ('TICKET_SOURCE')、
-- component <> 'TICKET_SOURCE'、IN ('FIXED','TICKET_SOURCE','ANYTHING') 一律照样命中，
-- 而且 LIKE 里的 `_` 是未转义的单字符通配符，连 TICKETxSOURCE 都能冒充 —— 那是恒绿不是断言。
-- 做法与 r7 生成列同源：先把 MySQL 打印出来的反引号 / 反斜杠转义 / 括号 / 空白 / 字符集引导符
-- 连同大小写一起归一，再与规范表达式【逐字符相等】，四个枚举缺一即红、多一个也红。
-- 归一是保语义的：这些只是打印噪音，字面量大小写在 _ci 排序规则下比较等价。
-- 排错：SELECT check_clause FROM information_schema.check_constraints
--       WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_payable_component';
-- 归一链【不依赖 sql_mode】：空白用 POSIX 字符类 [[:space:]] 表达，不写 \s。
--    2026-08-17 在真 MySQL 8.0.45 上实测过原因：旧写法 '[`\\\\()\\s]' 里的 \s 依赖默认反斜杠转义，
--    会话一旦是 NO_BACKSLASH_ESCAPES，\s 退化成「字面字符 s」⇒ 空白不再被剥、连 ticket_source 的 s 都被吃掉
--    ⇒ 五条精确比较对【正确的库】全部恒红（实测 old_match=False / new_match=True）。
--    现写法在两种 sql_mode 下逐条实测均为真，且 [[:space:]] 已确认被 MySQL 8 的 ICU 引擎支持。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_payable_component'
  AND REGEXP_REPLACE(LOWER(check_clause), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
      <=> 'componentin''fixed'',''quality'',''extra'',''ticket_source''';

-- 四分量非负 CHECK 的语义回读：漏掉任一分量、或把某个 >= 写成 <= 都必须红。
-- 排错：SELECT check_clause FROM information_schema.check_constraints
--       WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_edition_final_amounts';
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_edition_final_amounts'
  AND REGEXP_REPLACE(LOWER(check_clause), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
      <=> 'fixed_compensation>=0andquality_amount>=0andextra_amount>=0andticket_source_amount>=0';
