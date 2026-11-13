-- App 原始请求台账：同一 actor 的同一 request_id 只执行一次原写入，结果（或业务拒绝）不可变地保存，
-- 供 App 在超时/断网后按原 request_id 读回原结果，而不是用新 ID 重放。业务写与 COMMITTED 行同一事务提交；
-- 业务拒绝在回滚后另起事务记为 REJECTED。只存摘要与结果投影，不存口令、令牌或原始请求体。
CREATE TABLE IF NOT EXISTS app_original_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 protocol VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_member_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 scope_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii NOT NULL,
 result_json MEDIUMTEXT NULL,
 error_code INT NULL,
 error_message VARCHAR(255) NULL,
 create_time DATETIME(3) NOT NULL,
 update_time DATETIME(3) NOT NULL,
 UNIQUE KEY uk_app_original_request(protocol,actor_member_id,request_id),
 KEY idx_app_original_request_actor(actor_member_id,protocol,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name='app_original_request' AND index_name='uk_app_original_request' AND seq_in_index=3 AND column_name='request_id';
