-- 俱乐部名称全站唯一保障(用户 2026-10-20 决定并批准本 DDL);只新增索引,不修改或删除任何俱乐部。
-- 与 ClubMapper.countClubsHoldingName 同口径:del_flag=0 且 status in (0,1) 的俱乐部按 TRIM(name) 占用名称;
-- 被驳回(2)与已解散/删除(del_flag=2)的表达式为 NULL,不占。重复占用必须先由独立批准的数据方案处置。
-- MySQL 8.0.13+ expression index。目标列只读合同:InnoDB / name varchar(64) NOT NULL utf8mb4_0900_ai_ci /
-- status tinyint(1) NOT NULL / del_flag int NOT NULL。
DROP TEMPORARY TABLE IF EXISTS club_name_unique_guard;
CREATE TEMPORARY TABLE club_name_unique_guard(id TINYINT PRIMARY KEY);
INSERT INTO club_name_unique_guard VALUES(1);

INSERT INTO club_name_unique_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='club' AND engine='InnoDB') <> 1
  OR (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='club' AND column_name='name'
      AND column_type='varchar(64)' AND is_nullable='NO'
      AND character_set_name='utf8mb4' AND collation_name='utf8mb4_0900_ai_ci') <> 1
  OR (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='club' AND column_name='status'
      AND column_type='tinyint(1)' AND is_nullable='NO') <> 1
  OR (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='club' AND column_name='del_flag'
      AND column_type='int' AND is_nullable='NO') <> 1;

-- 使用与索引完全相同的表达式及列排序规则检测全部俱乐部。
INSERT INTO club_name_unique_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM club
  WHERE (CASE WHEN del_flag = 0 AND status IN (0, 1) THEN TRIM(name) END) IS NOT NULL
  GROUP BY (CASE WHEN del_flag = 0 AND status IN (0, 1) THEN TRIM(name) END) HAVING COUNT(*)>1);

-- 同名但错误的索引禁止当成已完成;不删除或替换它。
SET @club_name_index_rows := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='club'
    AND index_name='uk_club_holding_name');
SET @club_name_index_exact := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='club'
    AND index_name='uk_club_holding_name'
    AND non_unique=0 AND seq_in_index=1 AND column_name IS NULL AND sub_part IS NULL
    AND collation='A' AND index_type='BTREE' AND is_visible='YES'
    AND CAST(expression AS BINARY) = CAST(REPLACE(
      '(case when ((~del_flag~ = 0) and (~status~ in (0,1))) then trim(~name~) end)',
      '~',CHAR(96)) AS BINARY));
INSERT INTO club_name_unique_guard
SELECT 1 WHERE @club_name_index_rows<>0
  AND (@club_name_index_rows<>1 OR @club_name_index_exact<>1);

SET @club_name_ddl := IF(@club_name_index_rows=0,
  'CREATE UNIQUE INDEX uk_club_holding_name ON club ((CASE WHEN del_flag = 0 AND status IN (0, 1) THEN TRIM(name) END))',
  'SELECT 1');
PREPARE club_name_stmt FROM @club_name_ddl;
EXECUTE club_name_stmt;
DEALLOCATE PREPARE club_name_stmt;

-- 最终同名索引精确 fingerprint。guard 让普通 mysql 客户端也拒绝不匹配结果。
SET @club_name_verified := (SELECT COUNT(*)=1 AND SUM(
    non_unique=0 AND seq_in_index=1 AND column_name IS NULL AND sub_part IS NULL
    AND collation='A' AND index_type='BTREE' AND is_visible='YES'
    AND CAST(expression AS BINARY) = CAST(REPLACE(
      '(case when ((~del_flag~ = 0) and (~status~ in (0,1))) then trim(~name~) end)',
      '~',CHAR(96)) AS BINARY))=1
  FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='club'
    AND index_name='uk_club_holding_name');
INSERT INTO club_name_unique_guard SELECT 1 WHERE COALESCE(@club_name_verified,0)<>1;
DROP TEMPORARY TABLE club_name_unique_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=1 AND SUM(SHA2(CONCAT_WS('~', non_unique, seq_in_index,
    COALESCE(column_name,'<null>'),
    CASE WHEN CAST(expression AS BINARY) = CAST(REPLACE(
      '(case when ((~del_flag~ = 0) and (~status~ in (0,1))) then trim(~name~) end)',
      '~',CHAR(96)) AS BINARY)
      THEN 'holding-trim-name' ELSE '<invalid-expression>' END,
    COALESCE(CAST(sub_part AS CHAR),'<null>'), collation, index_type, is_visible),256)
    = '12845e111ac6b4331085810caff9a0bde4efbeccfc2a5a75b88a95b1e5b524dd')=1 AS assertion_ok
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='club'
  AND index_name='uk_club_holding_name';
