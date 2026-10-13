-- 微信小程序非空身份唯一保障；只新增索引，不修复或删除既有会员。
-- MySQL 8.0.13+ expression index；重复数据必须先由独立批准的数据方案处置。
-- 目标列只读合同：InnoDB / varchar(100) / utf8mb4_general_ci / NULL allowed。
DROP TEMPORARY TABLE IF EXISTS wechat_identity_unique_guard;
CREATE TEMPORARY TABLE wechat_identity_unique_guard(id TINYINT PRIMARY KEY);
INSERT INTO wechat_identity_unique_guard VALUES(1);

INSERT INTO wechat_identity_unique_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='ums_member' AND engine='InnoDB') <> 1
  OR (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='ums_member' AND column_name='wx_open_id'
      AND column_type='varchar(100)' AND is_nullable='YES'
      AND character_set_name='utf8mb4' AND collation_name='utf8mb4_general_ci') <> 1;

-- 使用与索引完全相同的表达式及列排序规则检测全部会员，含已删除/冻结会员。
INSERT INTO wechat_identity_unique_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM ums_member
  WHERE NULLIF(wx_open_id,'') IS NOT NULL
  GROUP BY NULLIF(wx_open_id,'') HAVING COUNT(*)>1);

-- 同名但错误的索引禁止当成已完成；不删除或替换它。
SET @wechat_identity_index_rows := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='ums_member'
    AND index_name='uk_ums_member_wx_open_id_nonblank');
SET @wechat_identity_index_exact := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='ums_member'
    AND index_name='uk_ums_member_wx_open_id_nonblank'
    AND non_unique=0 AND seq_in_index=1 AND column_name IS NULL AND sub_part IS NULL
    AND collation='A' AND index_type='BTREE' AND is_visible='YES'
    AND CAST(expression AS BINARY) IN (
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',_utf8mb4',CHAR(92),CHAR(39),CHAR(92),CHAR(39),')') AS BINARY),
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',_utf8mb4',CHAR(39),CHAR(39),')') AS BINARY),
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',',CHAR(39),CHAR(39),')') AS BINARY)));
INSERT INTO wechat_identity_unique_guard
SELECT 1 WHERE @wechat_identity_index_rows<>0
  AND (@wechat_identity_index_rows<>1 OR @wechat_identity_index_exact<>1);

SET @wechat_identity_ddl := IF(@wechat_identity_index_rows=0,
  'CREATE UNIQUE INDEX uk_ums_member_wx_open_id_nonblank ON ums_member ((NULLIF(wx_open_id,'''')))',
  'SELECT 1');
PREPARE wechat_identity_stmt FROM @wechat_identity_ddl;
EXECUTE wechat_identity_stmt;
DEALLOCATE PREPARE wechat_identity_stmt;

-- 最终同名索引精确 fingerprint。guard 让普通 mysql 客户端也拒绝不匹配结果。
SET @wechat_identity_verified := (SELECT COUNT(*)=1 AND SUM(
    non_unique=0 AND seq_in_index=1 AND column_name IS NULL AND sub_part IS NULL
    AND collation='A' AND index_type='BTREE' AND is_visible='YES'
    AND CAST(expression AS BINARY) IN (
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',_utf8mb4',CHAR(92),CHAR(39),CHAR(92),CHAR(39),')') AS BINARY),
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',_utf8mb4',CHAR(39),CHAR(39),')') AS BINARY),
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',',CHAR(39),CHAR(39),')') AS BINARY)))=1
  FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ums_member'
    AND index_name='uk_ums_member_wx_open_id_nonblank');
INSERT INTO wechat_identity_unique_guard SELECT 1 WHERE COALESCE(@wechat_identity_verified,0)<>1;
DROP TEMPORARY TABLE wechat_identity_unique_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=1 AND SUM(SHA2(CONCAT_WS('~', non_unique, seq_in_index,
    COALESCE(column_name,'<null>'),
    CASE WHEN CAST(expression AS BINARY) IN (
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',_utf8mb4',CHAR(92),CHAR(39),CHAR(92),CHAR(39),')') AS BINARY),
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',_utf8mb4',CHAR(39),CHAR(39),')') AS BINARY),
      CAST(CONCAT('nullif(',CHAR(96),'wx_open_id',CHAR(96),',',CHAR(39),CHAR(39),')') AS BINARY))
      THEN 'nullif(wx_open_id,'''')' ELSE '<invalid-expression>' END,
    COALESCE(CAST(sub_part AS CHAR),'<null>'), collation, index_type, is_visible),256)
    = '83171908800442e5cf4a9183139672e0db71c42e628e964724548f902ee55f73')=1 AS assertion_ok
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='ums_member'
  AND index_name='uk_ums_member_wx_open_id_nonblank';
