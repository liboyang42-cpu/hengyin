-- 探店日 R7：把首三期「唯一执行俱乐部」的 DISTRIBUTION 单例语义下沉数据库。
-- 【类别】③ 要在库上跑（DDL），必须先于依赖该唯一性的 jar。
-- 【前置】migration_20260817_tandianri_r7_distribution_backfill.sql；先补可证明的存量槽并让冲突变红，
--          再建唯一键，禁止靠建索引时随机撞到哪一行来代替审计。
-- 【幂等】列与索引分别从 information_schema 预检；缺哪一项只补哪一项，可安全重放。
--          ⚠️ 半落库重放必须 fail-closed：同名但形状/表达式不对的列或索引在【任何 DDL 之前】就红，
--          绝不静默 drop/recreate —— 在无人看见时换掉一条并发防线，比不建更危险。
-- 【可逆】仅在 jar 已回退且确认没有依赖该并发防线的新写流量后，先删唯一键、再删生成列；
--          不删除、不改写任何 product_role 业务行。
-- 【EXTERNAL_GATE】下列负控只能在真 Oracle MySQL 8 上造错 schema 再重放，不用 H2/dry-run 冒充；
--          本文件不假装已经挡住了：①错表达式同名生成列 → guard① 必须在 ALTER 之前红；
--          ②(distribution_singleton_key, other_column) 复合 UNIQUE → guard② 必须红；
--          ③同名非唯一 / 错列 / 前缀 / 不可见索引 → guard② 必须红。
--          正向路径（正确落库 ⇒ 本文件 7 条回读全部为 1）由 TandianriBatch3FundsMybatisIntegrationTest
--          的 distributionSingletonMigrationReplaysAndDatabaseRejectsASecondActiveRow 在真库上承重。

-- 写前业务冲突预检：已有两个及以上生效 DISTRIBUTION 时必须停止，绝不删行或挑一个。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM (
    SELECT topic_id
    FROM product_role
    WHERE slot = 'DISTRIBUTION' AND status = 1
    GROUP BY topic_id
    HAVING COUNT(*) >= 2
) distribution_conflicts;

-- 写前 fail-closed guard①：同名生成列若类型/nullable/extra/表达式有一处不对，此处即红。
-- 表达式比对不用 LIKE：'%STATUS%%SLOT%%DISTRIBUTION%%TOPIC_ID%' 对 status = 2、
-- CONCAT 顺序颠倒、少一个分支的错表达式一律照样命中 —— 那是恒绿，不是断言。
-- MySQL 打印生成表达式时会补反引号、反斜杠转义、字符集引导符、括号与空白（版本相关噪音），
-- 先把这些噪音连同大小写一起归一，再与目标语义【归一后逐字符相等】。
-- 两处归一是【保语义】的，不是放松：ELSE NULL 与省略 ELSE 等价；字面量大小写在 _ci 排序规则下比较等价。
-- 排错：SELECT generation_expression FROM information_schema.columns
--       WHERE table_schema = DATABASE() AND table_name = 'product_role'
--         AND column_name = 'distribution_singleton_key';
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'product_role'
  AND column_name = 'distribution_singleton_key'
  AND NOT (
        LOWER(data_type) <=> 'varchar'
    AND character_maximum_length <=> 32
    AND is_nullable <=> 'YES'
    AND UPPER(extra) <=> 'VIRTUAL GENERATED'
    AND REGEXP_REPLACE(
            REGEXP_REPLACE(LOWER(generation_expression), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', ''),
            'elsenull', '')
        <=> 'casewhenstatus=1andslot=''distribution''thenconcattopic_id,''-distribution''end'
  );

-- 写前 fail-closed guard②：同名索引必须已经是「可见、非前缀、唯一、精确只有 distribution_singleton_key 一列」。
-- 判据对【全部 key part 聚合】，不按 seq_in_index = 1 过滤：
-- (distribution_singleton_key, other_column) 复合 UNIQUE 与重复 ordinal 在过滤式判据下都只剩一行，恒绿。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'product_role'
      AND index_name = 'uk_product_role_distribution_singleton'
    GROUP BY index_name
    HAVING NOT (
          COUNT(*) <=> 1
      AND MIN(non_unique) <=> 0
      AND MIN(seq_in_index) <=> 1
      AND MIN(column_name) <=> 'distribution_singleton_key'
      AND COUNT(sub_part) <=> 0
      AND MIN(is_visible) <=> 'YES'
    )
) wrong_distribution_singleton_index;

SET @r7_distribution_guard_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'product_role'
      AND column_name = 'distribution_singleton_key'
);
SET @r7_distribution_guard_column_sql := IF(
    @r7_distribution_guard_column_exists = 0,
    'ALTER TABLE product_role ADD COLUMN distribution_singleton_key VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 1 AND slot = ''DISTRIBUTION'' THEN CONCAT(topic_id, ''-DISTRIBUTION'') END) VIRTUAL COMMENT ''生效 DISTRIBUTION 单例 guard（首三期唯一执行俱乐部）''',
    'SELECT 1'
);
PREPARE r7_distribution_guard_column_stmt FROM @r7_distribution_guard_column_sql;
EXECUTE r7_distribution_guard_column_stmt;
DEALLOCATE PREPARE r7_distribution_guard_column_stmt;

SET @r7_distribution_guard_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'product_role'
      AND index_name = 'uk_product_role_distribution_singleton'
);
SET @r7_distribution_guard_index_sql := IF(
    @r7_distribution_guard_index_exists = 0,
    'ALTER TABLE product_role ADD UNIQUE KEY uk_product_role_distribution_singleton (distribution_singleton_key)',
    'SELECT 1'
);
PREPARE r7_distribution_guard_index_stmt FROM @r7_distribution_guard_index_sql;
EXECUTE r7_distribution_guard_index_stmt;
DEALLOCATE PREPARE r7_distribution_guard_index_stmt;

-- 回读生成列：问 MySQL 持久化的列类型与表达式，不回读本文件变量；判据与 guard① 同一条，不得放松。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'product_role'
  AND column_name = 'distribution_singleton_key'
  AND LOWER(data_type) <=> 'varchar'
  AND character_maximum_length <=> 32
  AND is_nullable <=> 'YES'
  AND UPPER(extra) <=> 'VIRTUAL GENERATED'
  AND REGEXP_REPLACE(
          REGEXP_REPLACE(LOWER(generation_expression), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', ''),
          'elsenull', '')
      <=> 'casewhenstatus=1andslot=''distribution''thenconcattopic_id,''-distribution''end';

-- 回读生成列的【真实取值】：文本对了不等于算出来的东西对。逐行比对 MySQL 自己算的值与目标语义，
-- NULL 安全比较，任何一行不一致即红。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM product_role
WHERE NOT (distribution_singleton_key <=> CASE WHEN status = 1 AND slot = 'DISTRIBUTION' THEN CONCAT(topic_id, '-DISTRIBUTION') END);

-- 回读唯一键：同名普通索引、错列、复合、前缀、不可见或重复 ordinal 都不能冒充完成；判据与 guard② 同一条。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'product_role'
      AND index_name = 'uk_product_role_distribution_singleton'
    GROUP BY index_name
    HAVING COUNT(*) <=> 1
       AND MIN(non_unique) <=> 0
       AND MIN(seq_in_index) <=> 1
       AND MIN(column_name) <=> 'distribution_singleton_key'
       AND COUNT(sub_part) <=> 0
       AND MIN(is_visible) <=> 'YES'
) exact_distribution_singleton_index;

-- 末端业务 readback：迁移后仍有多行即红；不得只凭 DDL receipt 宣称约束生效。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM (
    SELECT topic_id
    FROM product_role
    WHERE slot = 'DISTRIBUTION' AND status = 1
    GROUP BY topic_id
    HAVING COUNT(*) >= 2
) distribution_conflicts;
