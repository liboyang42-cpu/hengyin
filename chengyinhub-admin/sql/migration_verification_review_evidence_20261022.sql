-- Doc2 F4(2026-10-22):核销复核(verification_review)的商家补充材料。只新建表,不改 verification_review、不回填数据。
-- 读写方:MerchantDeskServiceImpl(chengyinhub-system)。仅在 sys_config['merchant.desk.f4.enabled']=true 时读写。
-- 商家只能给 verifier_member_id = 自己 Owner memberId 且 status=0(待复核)的复核单补材料;补材料不改复核结论,
-- 结论仍由后台人工复核写 verification_review.status。媒体只存已归属本人的 media-asset 引用(不公开)。
-- 每张复核单最多 10 份补充材料(服务端计数校验)。
-- 幂等:表不存在则建;已存在则核对列 fingerprint,不一致时 guard 主键冲突让执行器报错,不改不删。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚(确认开关为 false 后):DROP TABLE verification_review_evidence;
DROP TEMPORARY TABLE IF EXISTS verification_review_evidence_guard;
CREATE TEMPORARY TABLE verification_review_evidence_guard(id TINYINT PRIMARY KEY);
INSERT INTO verification_review_evidence_guard VALUES(1);

CREATE TABLE IF NOT EXISTS verification_review_evidence (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  review_id BIGINT NOT NULL COMMENT 'verification_review.id',
  owner_member_id BIGINT NOT NULL COMMENT '商家 Owner memberId(= verification_review.verifier_member_id)',
  actor_member_id BIGINT NOT NULL COMMENT '提交人 memberId(Owner 或员工)',
  note VARCHAR(500) NULL COMMENT '文字说明',
  media_refs VARCHAR(1000) NULL COMMENT 'media-asset 引用,分号分隔,最多 6 个',
  request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
  created_at DATETIME(3) NOT NULL COMMENT '提交时间(服务端)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_verification_review_evidence_request (owner_member_id, request_id),
  KEY idx_verification_review_evidence_review (review_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doc2 F4 核销复核商家补充材料';

SET @verification_review_evidence_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable)
    ORDER BY ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence');
INSERT INTO verification_review_evidence_guard
SELECT 1 FROM DUAL WHERE COALESCE(@verification_review_evidence_columns, '') <> CONCAT(
  'id:bigint:NO|review_id:bigint:NO|owner_member_id:bigint:NO|actor_member_id:bigint:NO',
  '|note:varchar(500):YES|media_refs:varchar(1000):YES|request_id:varchar(64):NO|created_at:datetime(3):NO');
DROP TEMPORARY TABLE verification_review_evidence_guard;

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 AS assertion_ok FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence'
  AND index_name = 'uk_verification_review_evidence_request' AND non_unique = 0;
