-- 2026-11-14 roam_poi.tags 统一为 JSON 数组串(A5.5)。
-- 此前:schema 注释写"逗号分隔",商家资料同步按逗号串写入,目录导入工具却写 JSON 数组串,/city/nodes?tag= 用 LIKE 子串。
-- 之后:一律 ["a","b"];/city/nodes?tag= 按带引号的整标签匹配;目录种子标签 roam:seed:v1:<id> 是数组里的一项。
-- 可重复执行;只改 tags 的格式(不丢标签),已是 JSON 数组(以 [ 开头)的行不动,超过 200 字符的行留给应用侧保存时规整。
-- 回滚:无需回滚(JSON 数组串对旧的 LIKE 子串匹配仍可读);如必须恢复注释,改回原 COMMENT 即可。

UPDATE roam_poi
SET tags = NULL
WHERE tags IS NOT NULL AND TRIM(tags) = '';

UPDATE roam_poi
SET tags = CONCAT('["',
        REPLACE(
            REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(tags), '"', ''), '，', ','), '、', ','), '；', ','), ', ', ','),
            ',', '","'),
        '"]')
WHERE tags IS NOT NULL
  AND tags NOT LIKE '[%'
  AND CHAR_LENGTH(tags) <= 200;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'tags'
                 AND column_type = 'varchar(255)') = 1,
    'ALTER TABLE roam_poi MODIFY COLUMN tags varchar(255) DEFAULT NULL COMMENT ''标签:JSON 数组串,如 ["roam:park","公园"];目录种子标签 roam:seed:v1:<id> 为其中一项''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
