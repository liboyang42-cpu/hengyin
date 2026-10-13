-- R6 产品合同：活动壳票显式来源键 + 玩家可回读的通关 XP 发放事实。
--
-- 【类别】③ 要在库上跑（DDL + 受约束历史回填），生产执行前必须走 chengyin-db-migrate。
-- 【前置】migration_m7_xp_budget.sql；migration_product_type_purchase_kind_20260716.sql；存量 oms_ticket / cms_activity 表。
-- 【幂等】所有 ALTER 先查 information_schema；CREATE TABLE IF NOT EXISTS；回填只补可靠单配对的 NULL 行。
-- 【可逆】确认 XP 发放事实表无业务行且 jar 已不再读取 source_ticket_id 后，才可删表/索引/列；禁止删业务行回滚。
-- 【顺序】必须先于包含新 mapper 的 jar 落库；否则 oms_ticket 全域读取报 ERROR 1054，topic_completion_xp_award 表不存在。
--
-- 【写前 guard 在任何 DDL/回填之前】半落库或被人手改过的库里，同名对象的形状可能是错的：
--   `ALTER ... ADD COLUMN/INDEX` 都由存在性预检跳过、`CREATE TABLE IF NOT EXISTS` 直接静默接受，
--   于是「同名但列错/序错/带前缀/非唯一/不可见/CHECK 语义相反」的对象会被当成已完成。
--   下面三条 guard 与文件末端的观测【用同一套判据】，缺一即在破坏性写入之前红。
-- 【EXTERNAL_GATE】三类负控只能在真 Oracle MySQL 8 上造错 schema 再重放：
--   ①同名 source_ticket_id 列类型/nullable 不对 → guard① 红；
--   ②同名 idx_oms_ticket_activity_source 错列/错序/前缀/UNIQUE 化/不可见 → guard② 红；
--   ③同名 topic_completion_xp_award 错列/错唯一键/CHECK 语义相反 → guard③ 红。
--   正向路径（正确落库 ⇒ 本文件 8 条回读全为 1）与回填歧义行为由
--   TandianriBatch3FundsMybatisIntegrationTest 的
--   productContractsMigrationReplaysAndBackfillsOnlyUnambiguousPairs 在真库上承重。

-- 写前 fail-closed guard①：还没有这一列（首次落库）或已有列形状完全正确，两者之外一律红。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
           AND column_name = 'source_ticket_id') = 0
    OR (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
           AND column_name = 'source_ticket_id'
           AND LOWER(data_type) = 'bigint' AND is_nullable = 'YES') = 1;

-- 写前 fail-closed guard②：同名索引若已存在，必须已经是四列、按序、非唯一、BTREE、无前缀、可见。
-- 判据与末端观测逐字同源；只比名字对同名错索引恒绿。
-- 形式与下面 XP 唯一键那条不同（这里是「总 key part 数 = 4」∧「逐位命中 4 条」的双 COUNT 式，
-- 那里是 GROUP BY ... HAVING 聚合式）：本条是 R8 已逐字钉死并由 R6ProductMigrationContractTest
-- 锁住的既有断言，改写形式会同时动到那条契约，本轮不动它 —— 两种写法强度等价：
-- 多列/少列由第一段挡住，错列/错序由第二段的逐位 OR 挡住。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
           AND index_name = 'idx_oms_ticket_activity_source') = 0
    OR ((SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
            AND index_name = 'idx_oms_ticket_activity_source') = 4
   AND (SELECT COUNT(*)
          FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
           AND index_name = 'idx_oms_ticket_activity_source'
           AND non_unique = 1 AND index_type = 'BTREE'
           AND sub_part IS NULL AND is_visible = 'YES'
           AND ((seq_in_index = 1 AND column_name = 'owner_type')
             OR (seq_in_index = 2 AND column_name = 'owner_id')
             OR (seq_in_index = 3 AND column_name = 'source_ticket_id')
             OR (seq_in_index = 4 AND column_name = 'del_flag'))) = 4);

-- 写前 fail-closed guard③：CREATE TABLE IF NOT EXISTS 会把任何同名表当成已完成，
-- 所以存量表必须先证明四个关键列、唯一键形状与 CHECK 语义都对，否则在建表之前就红。
-- 判据与末端三条观测逐字同源（列 / 唯一键 / CHECK），不得只判「表存在」。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE() AND table_name = 'topic_completion_xp_award') = 0
    OR ((SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'topic_completion_xp_award'
            AND ((column_name = 'member_id'  AND LOWER(data_type) = 'bigint'   AND is_nullable = 'NO')
              OR (column_name = 'topic_id'   AND LOWER(data_type) = 'bigint'   AND is_nullable = 'NO')
              OR (column_name = 'xp_amount'  AND LOWER(data_type) = 'int'      AND is_nullable = 'NO')
              OR (column_name = 'granted_at' AND LOWER(data_type) = 'datetime' AND is_nullable = 'NO'))) = 4
   AND (SELECT COUNT(*) FROM (
            SELECT index_name
              FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'topic_completion_xp_award'
               AND index_name = 'uk_topic_completion_xp_member_topic'
             GROUP BY index_name
            HAVING COUNT(*) <=> 2
               AND MIN(non_unique) <=> 0
               AND MIN(index_type) <=> 'BTREE' AND MAX(index_type) <=> 'BTREE'
               AND COUNT(sub_part) <=> 0
               AND MIN(is_visible) <=> 'YES'
               AND SUM(seq_in_index = 1 AND column_name = 'member_id') <=> 1
               AND SUM(seq_in_index = 2 AND column_name = 'topic_id') <=> 1
        ) exact_xp_unique_key) = 1
   AND (SELECT COUNT(*) FROM information_schema.check_constraints
         WHERE constraint_schema = DATABASE()
           AND constraint_name = 'ck_topic_completion_xp_positive'
           AND REGEXP_REPLACE(LOWER(check_clause),
                   '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') <=> 'xp_amount>0') = 1);

SET @source_ticket_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'source_ticket_id'
);
SET @ddl := IF(@source_ticket_column_exists = 0,
  'ALTER TABLE oms_ticket ADD COLUMN source_ticket_id BIGINT NULL COMMENT ''活动壳票对应的主题源票 id'' AFTER owner_id',
  'SELECT ''oms_ticket.source_ticket_id already exists''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @source_ticket_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
     AND index_name = 'idx_oms_ticket_activity_source'
);
SET @ddl := IF(@source_ticket_index_exists = 0,
  'ALTER TABLE oms_ticket ADD INDEX idx_oms_ticket_activity_source(owner_type, owner_id, source_ticket_id, del_flag)',
  'SELECT ''idx_oms_ticket_activity_source already exists''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量只回填“该活动票按旧四字段恰好命中一张主题票”的可靠配对；歧义行保持 NULL，读侧 fail-closed 并告警。
--
-- 歧义有【两个方向】，两个都必须挡住：
--   正向：一张活动票命中 ≥2 张主题票 —— 由 HAVING COUNT(*) = 1 挡住；
--   反向：≥2 张活动票争同一张主题票 —— HAVING COUNT(*) = 1 对它【恒绿】（每张活动票各自只命中一张），
--         结果是两张活动票拿到同一个 source_ticket_id，配对键当场失去唯一性。
-- 反向由下面的 NOT EXISTS 挡住：四字段相等是对称关系，所以「有别的活动票争同一张主题票」
--   ⇔「同一主题下存在另一张四字段完全相同的活动票」。
--   sibling 【不限定 source_ticket_id IS NULL】是刻意的：已被上一轮回填占走的主题票同样不能再被认领，
--   这样重放才收敛（否则半落库后第二轮会把已被占用的键再发一次）。
UPDATE oms_ticket activity_ticket
JOIN (
  SELECT candidate.activity_ticket_id, MIN(candidate.topic_ticket_id) AS source_ticket_id
  FROM (
    SELECT at.id AS activity_ticket_id, tt.id AS topic_ticket_id
      FROM oms_ticket at
      JOIN cms_activity activity
        ON activity.id = at.owner_id AND activity.del_flag = 0
      JOIN oms_ticket tt
        ON tt.owner_type = 1 AND tt.owner_id = activity.topic_id AND tt.del_flag = 0
       AND tt.name = at.name
       AND tt.price <=> at.price
       AND tt.start_time <=> at.start_time
       AND tt.end_time <=> at.end_time
     WHERE at.owner_type = 2 AND at.del_flag = 0 AND at.source_ticket_id IS NULL
       AND NOT EXISTS (
             SELECT 1
               FROM oms_ticket sibling
               JOIN cms_activity sibling_activity
                 ON sibling_activity.id = sibling.owner_id AND sibling_activity.del_flag = 0
              WHERE sibling.owner_type = 2 AND sibling.del_flag = 0
                AND sibling.id <> at.id
                AND sibling_activity.topic_id = activity.topic_id
                AND sibling.name = at.name
                AND sibling.price <=> at.price
                AND sibling.start_time <=> at.start_time
                AND sibling.end_time <=> at.end_time)
  ) candidate
  GROUP BY candidate.activity_ticket_id
  HAVING COUNT(*) = 1
) reliable ON reliable.activity_ticket_id = activity_ticket.id
SET activity_ticket.source_ticket_id = reliable.source_ticket_id
WHERE activity_ticket.source_ticket_id IS NULL;

CREATE TABLE IF NOT EXISTS topic_completion_xp_award (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  member_id   BIGINT NOT NULL,
  topic_id    BIGINT NOT NULL,
  xp_amount   INT NOT NULL,
  granted_at  DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_topic_completion_xp_member_topic (member_id, topic_id),
  KEY idx_topic_completion_xp_topic (topic_id),
  CONSTRAINT ck_topic_completion_xp_positive CHECK (xp_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家每期通关 XP 一次性发放事实';

-- 列回读连类型与 nullable 一起钉：只问「有没有叫这个名字的列」对错类型/被改成 NOT NULL 恒绿。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' AND column_name = 'source_ticket_id'
   AND LOWER(data_type) = 'bigint' AND is_nullable = 'YES';

-- 索引回读必须问「这个索引长什么样」，不能只问「有没有叫这个名字的东西」。
-- 名字相同、列错/序错/带前缀/被 UNIQUE 化/被 INVISIBLE 的索引都会让读侧走错执行计划，
-- 而只比名字的回读对这五种全恒绿。information_schema.statistics 每列一行：
-- 第一段钉总列数（挡多列/少列），第二段逐位钉 seq_in_index → column_name 并锁死索引属性。
-- ⚠️ 原断言写的是 COUNT(*) = 1 —— 四列索引在 statistics 里是 4 行，它对正确索引恒红、
-- 只对单列索引才绿；名字对了也证明不了形状。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*)
          FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
           AND index_name = 'idx_oms_ticket_activity_source') = 4
   AND (SELECT COUNT(*)
          FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'oms_ticket'
           AND index_name = 'idx_oms_ticket_activity_source'
           AND non_unique = 1 AND index_type = 'BTREE'
           AND sub_part IS NULL AND is_visible = 'YES'
           AND ((seq_in_index = 1 AND column_name = 'owner_type')
             OR (seq_in_index = 2 AND column_name = 'owner_id')
             OR (seq_in_index = 3 AND column_name = 'source_ticket_id')
             OR (seq_in_index = 4 AND column_name = 'del_flag'))) = 4;

-- XP 表关键列回读：四列的类型与 NOT NULL 一起钉。
-- 只判「表存在」对「同名表少一列 / xp_amount 被建成可空」这类形状恒绿，而这张表是
-- 「每人每期通关 XP 只发一次」的库侧真源，形状不对等于防线不存在。
-- runner: assert=scalar-true
SELECT COUNT(*) = 4
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'topic_completion_xp_award'
   AND ((column_name = 'member_id'  AND LOWER(data_type) = 'bigint'   AND is_nullable = 'NO')
     OR (column_name = 'topic_id'   AND LOWER(data_type) = 'bigint'   AND is_nullable = 'NO')
     OR (column_name = 'xp_amount'  AND LOWER(data_type) = 'int'      AND is_nullable = 'NO')
     OR (column_name = 'granted_at' AND LOWER(data_type) = 'datetime' AND is_nullable = 'NO'));

-- 唯一键回读：对【全部 key part 聚合】判，不按 seq_in_index 过滤 ——
-- 过滤式判据对复合键（member_id, topic_id, xp_amount）与重复 ordinal 都只剩一行、恒绿。
-- 逐位钉 seq_in_index → column_name：列序颠倒成 (topic_id, member_id) 时两条 SUM 同时为 0 ⇒ 红；
-- 建在错列 (member_id, xp_amount) 上 ⇒ 第二条 SUM 为 0 ⇒ 红；
-- 非唯一 / 前缀 / INVISIBLE 分别由 MIN(non_unique)、COUNT(sub_part)、MIN(is_visible) 挡住。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
    SELECT index_name
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'topic_completion_xp_award'
       AND index_name = 'uk_topic_completion_xp_member_topic'
     GROUP BY index_name
    HAVING COUNT(*) <=> 2
       AND MIN(non_unique) <=> 0
       AND MIN(index_type) <=> 'BTREE' AND MAX(index_type) <=> 'BTREE'
       AND COUNT(sub_part) <=> 0
       AND MIN(is_visible) <=> 'YES'
       AND SUM(seq_in_index = 1 AND column_name = 'member_id') <=> 1
       AND SUM(seq_in_index = 2 AND column_name = 'topic_id') <=> 1
) exact_xp_unique_key;

-- CHECK 回读不用 LIKE：'%XP_AMOUNT%' 对 xp_amount >= 0、xp_amount < 0 这类【语义相反】的约束
-- 一律照样命中，那是恒绿不是断言。做法与 r7 生成列同源：先把 MySQL 打印出来的反引号 / 反斜杠转义 /
-- 括号 / 空白 / 字符集引导符连同大小写一起归一，再与规范表达式【逐字符相等】。
-- 归一是保语义的：这些只是打印噪音，字面量大小写在 _ci 排序规则下比较等价。
-- 排错：SELECT check_clause FROM information_schema.check_constraints
--       WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_completion_xp_positive';
-- 归一链【不依赖 sql_mode】：空白用 POSIX 字符类 [[:space:]] 表达，不写 \s。
--    2026-08-17 在真 MySQL 8.0.45 上实测过原因：旧写法 '[`\\\\()\\s]' 里的 \s 依赖默认反斜杠转义，
--    会话一旦是 NO_BACKSLASH_ESCAPES，\s 退化成「字面字符 s」⇒ 空白不再被剥、连 ticket_source 的 s 都被吃掉
--    ⇒ 五条精确比较对【正确的库】全部恒红（实测 old_match=False / new_match=True）。
--    现写法在两种 sql_mode 下逐条实测均为真，且 [[:space:]] 已确认被 MySQL 8 的 ICU 引擎支持。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  FROM information_schema.check_constraints
 WHERE constraint_schema = DATABASE()
   AND constraint_name = 'ck_topic_completion_xp_positive'
   AND REGEXP_REPLACE(LOWER(check_clause),
           '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') <=> 'xp_amount>0';
