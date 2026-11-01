-- 漫游简化轨迹 roam_session.track_json(2026-10-31 业主批准 P-7 待定问题 ③:漫游记录卡片就是这次的轨迹地图,轨迹存服务端)
-- 执行方式:走 chengyin-db-migrate,只读预检后确认。部署新 jar 前先落库(新 jar 的护照历史读 track_json)。
-- 内容:结算时客户端上送的轨迹,服务端抽稀到 ≤300 点、坐标保留 5 位小数,JSON [[lat,lng],...]。
-- 隐私:与 last_lat/last_lng 同属详细位置,30 天保留期到期由 clearEndedSessionLocation 一并清空(不延长保留期)。
-- 可重复执行:加列先查 information_schema。不回填 —— 存量会话没有轨迹,如实留空。

SET @roam_session_track_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='roam_session' AND column_name='track_json');
SET @roam_session_track_sql := IF(@roam_session_track_exists=0,
    'ALTER TABLE roam_session ADD COLUMN track_json MEDIUMTEXT NULL COMMENT ''简化轨迹 [[lat,lng],...] ≤300 点;30 天随详细位置清空''',
    'SELECT 1');
PREPARE roam_session_track_stmt FROM @roam_session_track_sql;
EXECUTE roam_session_track_stmt;
DEALLOCATE PREPARE roam_session_track_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='roam_session' AND column_name='track_json')=1 AS roam_session_track_ready;

-- 回退:保留列(旧 jar 不读它)。禁止 DROP。
