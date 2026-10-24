-- DEFER-ROAM(2026-10-24,B:P2-034):roam_session.distance_m 列注释注明「客户端自报、不可信」。
-- 只改列注释:类型 int、可空、默认 '0' 逐字不变,数据不动(MySQL 8 改注释为仅元数据,ALGORITHM=INPLACE 不重建表)。
-- 背景:里程由 /api/roam/finish 的客户端参数写入;服务端只按 min(200 km, 时长×35 m/s + 格子数×300 m) 封顶
--   (ApiRoamController.finish),仅用于展示(历史、社区帖路线引用),探索值只按服务端格子/到点记录结算。
-- 幂等:列定义与预期一致时才改(重复执行结果相同);定义已被别处改过则 guard 主键冲突报错,不改不删。
-- 回滚:ALTER TABLE roam_session MODIFY COLUMN distance_m int DEFAULT '0' COMMENT '本次里程(米)';
DROP TEMPORARY TABLE IF EXISTS roam_session_distance_comment_guard;
CREATE TEMPORARY TABLE roam_session_distance_comment_guard(id TINYINT PRIMARY KEY);
INSERT INTO roam_session_distance_comment_guard VALUES(1);
SET @roam_session_distance_def := (
    SELECT CONCAT_WS(':', column_type, is_nullable, COALESCE(column_default, 'NULL'))
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'roam_session' AND column_name = 'distance_m'
);
INSERT INTO roam_session_distance_comment_guard
SELECT 1 FROM DUAL WHERE COALESCE(@roam_session_distance_def, '') <> 'int:YES:0';
DROP TEMPORARY TABLE roam_session_distance_comment_guard;

ALTER TABLE roam_session
  MODIFY COLUMN distance_m int DEFAULT '0' COMMENT '本次里程(米);客户端自报值,不可信:服务端按时长与格子数封顶后落库,仅用于展示,不参与探索值结算',
  ALGORITHM=INPLACE, LOCK=NONE;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'roam_session' AND column_name = 'distance_m'
  AND column_type = 'int' AND is_nullable = 'YES' AND column_default = '0'
  AND column_comment LIKE '%客户端自报值,不可信%';
