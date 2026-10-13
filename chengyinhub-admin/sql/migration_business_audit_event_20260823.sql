-- 商家/俱乐部/平台共用的高风险业务动作不可变审计。
-- 该表只追加，不承担领域终态；before/after 仅允许白名单字段，禁止 token、手机号、openid 与请求正文。
-- 仅生成迁移文件；上线前必须单独走 chengyin-db-migrate 预检、执行与业务读回。

CREATE TABLE IF NOT EXISTS business_audit_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  domain_type VARCHAR(16) NOT NULL COMMENT 'MERCHANT/CLUB/PLATFORM',
  domain_id BIGINT NOT NULL COMMENT '商家行/俱乐部行等领域主体主键',
  owner_member_id BIGINT NOT NULL,
  actor_member_id BIGINT NOT NULL,
  actor_role_code VARCHAR(32) NOT NULL,
  action VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT DEFAULT NULL,
  relation_id BIGINT DEFAULT NULL,
  before_json JSON DEFAULT NULL COMMENT '白名单字段；禁止 token/手机号/openid/请求正文',
  after_json JSON DEFAULT NULL,
  reason VARCHAR(500) DEFAULT NULL,
  request_id VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_audit_request (domain_type, domain_id, action, request_id),
  KEY idx_business_audit_domain (domain_type, domain_id, create_time),
  KEY idx_business_audit_actor (actor_member_id, create_time),
  KEY idx_business_audit_target (target_type, target_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序高风险业务动作不可变审计';

-- 结构读回：同名残缺表、错误引擎或错误幂等键必须让执行器失败。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name = 'business_audit_event' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'business_audit_event'
         AND column_name IN ('domain_type','domain_id','owner_member_id','actor_member_id','actor_role_code',
           'action','target_type','request_id','before_json','after_json','create_time')) = 11
  AND EXISTS (
    SELECT 1 FROM (
      SELECT index_name, MIN(non_unique) AS non_unique,
             GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
      FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'business_audit_event'
      GROUP BY index_name
    ) exact_unique
    WHERE index_name = 'uk_business_audit_request' AND non_unique = 0
      AND cols = 'domain_type,domain_id,action,request_id')
  AS assertion_ok;

-- 候选同名表不得只凭列名假绿：签名固定全部 15 列的类型/null/default/extra/
-- charset（非 utf8mb4 还固定 collation）与生成表达式。
SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  AND SUM(table_name = 'business_audit_event' AND column_count = 15
    AND column_hash = '195a299082a792b8e2398c721635b371556132eddbd77ed44dc631b0545e03ef') = 1
  AS assertion_ok
FROM (
  SELECT table_name, COUNT(*) AS column_count,
         SHA2(GROUP_CONCAT(CONCAT_WS('~', column_name, LOWER(column_type), is_nullable,
           COALESCE(LOWER(CAST(column_default AS CHAR)), '<null>'), LOWER(extra),
           COALESCE(character_set_name, '<null>'),
           CASE WHEN character_set_name IS NULL OR character_set_name = 'utf8mb4'
             THEN COALESCE(character_set_name, '<null>') ELSE COALESCE(collation_name, '<null>') END,
           COALESCE(REGEXP_REPLACE(LOWER(generation_expression),
             '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), '<null>'))
           ORDER BY ordinal_position SEPARATOR '|'), 256) AS column_hash
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'business_audit_event'
  GROUP BY table_name
) exact_columns;
