-- be-gap-merchant R3：W05 草稿来源生产者（MerchantAuthoringRecordService）的候选表。
-- AI 生成结果在模型返回后、短事务内写一行候选（服务端事实 + 人工确认约束 + AI traceId + 模板 JSON）；
-- 商家逐字段确认后，同一事务写普通 MERCHANT 草稿和 merchant_authoring_source（20261119_merchant_gap 已建）。
-- 仅新增表，无外键，可重复执行。不涉及资金、库存、结算或支付。

CREATE TABLE IF NOT EXISTS merchant_authoring_candidate (
  id BIGINT NOT NULL AUTO_INCREMENT,
  principal_member_id BIGINT NOT NULL,
  merchant_row_id BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  facts_json LONGTEXT NOT NULL,
  facts_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  constraints_json LONGTEXT NOT NULL,
  constraints_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  ai_trace_id VARCHAR(200) NOT NULL,
  template_json LONGTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_authoring_candidate_owner (owner_member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 AI 草稿候选（不可变证据，仅 INSERT）';
