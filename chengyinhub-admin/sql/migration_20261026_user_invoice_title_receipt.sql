-- W8-11 本人抬头保存命令回执。前置 migration_20261022_f7_user_invoice.sql。
-- 不复制名称/税号；payload_fingerprint 为域分离的带密钥 HMAC，不对外返回。
-- title_id=NULL 只允许存在于未提交的保存事务内；失败必须整事务回滚。
-- 不回填旧请求，不删除旧表，不启用任何开关。仅在批准环境执行。
CREATE TABLE IF NOT EXISTS user_invoice_title_receipt (
  member_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title_id BIGINT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (member_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本人抬头保存原请求回执，无明文PII';

-- runner: assert=scalar-true
SELECT
  (SELECT GROUP_CONCAT(CONCAT_WS(':',column_name,column_type,is_nullable) ORDER BY ordinal_position SEPARATOR '|')
   FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_invoice_title_receipt')
  = 'member_id:bigint:NO|request_id:varchar(64):NO|payload_fingerprint:char(64):NO|title_id:bigint:YES|create_time:datetime:NO'
  AND
  (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
   FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user_invoice_title_receipt' AND index_name='PRIMARY' AND non_unique=0)
  = 'member_id,request_id'
  AND
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_invoice_title_receipt'
   AND column_name IN ('request_id','payload_fingerprint') AND collation_name='ascii_bin') = 2
  AS assertion_ok;
