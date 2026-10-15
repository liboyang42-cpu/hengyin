-- Durable close intent is committed with presence_visible=0; consent_record is written later.
CREATE TABLE IF NOT EXISTS roam_presence_revoke_intent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL,
  member_id BIGINT NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  previous_consent_id BIGINT NOT NULL DEFAULT 0,
  occurred_at DATETIME(6) NOT NULL,
  state TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending,1 leased,2 done,3 manual',
  attempt_count INT NOT NULL DEFAULT 0,
  owner_token VARCHAR(64) NULL,
  lease_until DATETIME(6) NULL,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_presence_revoke_request (request_id),
  KEY idx_presence_revoke_due (state,next_attempt_at,lease_until),
  KEY idx_presence_revoke_member (member_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Committed nearby sharing close facts and audit retry';
-- runner: assert=scalar-true
SELECT COUNT(*)=11 FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='roam_presence_revoke_intent'
 AND ((column_name IN ('id','member_id','previous_consent_id') AND data_type='bigint' AND is_nullable='NO')
  OR (column_name='request_id' AND data_type='varchar' AND character_maximum_length=64 AND is_nullable='NO')
  OR (column_name='event_type' AND data_type='varchar' AND character_maximum_length=16 AND is_nullable='NO')
  OR (column_name IN ('occurred_at','next_attempt_at') AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO')
  OR (column_name IN ('lease_until') AND data_type='datetime' AND datetime_precision=6)
  OR (column_name='state' AND data_type='tinyint' AND is_nullable='NO' AND column_default='0')
  OR (column_name='attempt_count' AND data_type='int' AND is_nullable='NO' AND column_default='0')
  OR (column_name='owner_token' AND data_type='varchar' AND character_maximum_length=64));
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name='roam_presence_revoke_intent' AND index_name='uk_presence_revoke_request'
 AND column_name='request_id' AND non_unique=0;
-- Preserve existing event order; repeated forward application must not replace live preferences.
INSERT IGNORE INTO roam_presence_revoke_intent
 (id,request_id,member_id,event_type,previous_consent_id,occurred_at,state,next_attempt_at)
SELECT id,request_id,member_id,event_type,id,occurred_at,2,create_time FROM consent_record
WHERE doc_type='privacy_policy' AND scene='roam_presence' AND scope_type IS NULL AND scope_id IS NULL
 AND event_type IN ('AGREE','REVOKE') ORDER BY id;
-- runner: assert=scalar-true
SELECT COUNT(*)=0 FROM consent_record c LEFT JOIN roam_presence_revoke_intent e
 ON e.request_id=c.request_id AND e.member_id=c.member_id AND e.event_type=c.event_type
WHERE c.doc_type='privacy_policy' AND c.scene='roam_presence' AND c.scope_type IS NULL AND c.scope_id IS NULL
 AND c.event_type IN ('AGREE','REVOKE') AND e.id IS NULL;
