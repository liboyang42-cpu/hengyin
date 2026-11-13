-- 俱乐部系列创建「原始结果」（App ClubSeriesOriginalResult，/api/club/event-ops/series/original-result）：
-- series/create 首次执行成功时，在同一事务里把完整初始结果（原始线格式字段、系列行、全部场次、生效日期）
-- 冻结成一行，配 receipt_hash。之后系列改期/关闭都不改这一行；读回只按 (club, 发起人, requestId) 取，
-- 原线格式不一致即冲突。本表上线前创建的系列没有快照，读回如实 UNKNOWN。只存业务字段，不存令牌或口令。
CREATE TABLE IF NOT EXISTS club_event_series_original_result (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 series_id BIGINT NOT NULL,
 club_id BIGINT NOT NULL,
 topic_id BIGINT NOT NULL,
 actor_member_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
 wire_json MEDIUMTEXT NOT NULL,
 result_json MEDIUMTEXT NOT NULL,
 receipt_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_time DATETIME(3) NOT NULL,
 UNIQUE KEY uk_series_original_result_series(series_id),
 UNIQUE KEY uk_series_original_result_request(club_id,actor_member_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name='club_event_series_original_result' AND index_name='uk_series_original_result_request' AND seq_in_index=3 AND column_name='request_id';
