-- Requires migration_club_role_event_ops_20260823.sql and migration_club_waitlist_offer_window_20260911.sql.
-- Explicit opt-in for new series; existing series retain ordinary signup semantics.
ALTER TABLE club_event_series
  ADD COLUMN host_reservation_required TINYINT(1) NULL DEFAULT 0 AFTER waitlist_enabled;

ALTER TABLE club_event_waitlist
  DROP CHECK chk_club_event_waitlist_state,
  DROP CHECK chk_club_event_waitlist_offer,
  MODIFY COLUMN active_member_key VARCHAR(160) CHARACTER SET ascii GENERATED ALWAYS AS
    (CASE WHEN state IN ('REQUESTED','WAITING','OFFERED','CLAIMED')
      THEN CONCAT(activity_id, ':', ticket_id, ':', member_id) ELSE NULL END) STORED,
  ADD CONSTRAINT chk_club_event_waitlist_state CHECK
    (state IN ('REQUESTED','WAITING','OFFERED','CLAIMED','CONVERTED','REJECTED','CANCELLED','EXPIRED')),
  ADD CONSTRAINT chk_club_event_waitlist_offer CHECK
    ((state IN ('OFFERED','CLAIMED','CONVERTED') AND offered_at IS NOT NULL
      AND offer_expires_at IS NOT NULL AND offer_token_hash IS NOT NULL)
      OR (state = 'REQUESTED' AND offer_expires_at IS NOT NULL)
      OR state IN ('WAITING','REJECTED','CANCELLED','EXPIRED'));

-- runner: assert=scalar-true
SELECT
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'club_event_series' AND column_name = 'host_reservation_required'
    AND column_type = 'tinyint(1)' AND is_nullable = 'YES' AND column_default = '0')
  AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'club_event_waitlist' AND column_name = 'active_member_key'
    AND generation_expression LIKE '%REQUESTED%') AS club_host_reservation_shape_ok;

-- Preserve the prior seven-table health contract; this additive series field makes 19 columns.
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'club_event_series'
     AND column_name = 'offer_minutes' AND column_default = '1440') = 1
  AND
  -- 负向断言:约束必须真的放宽到 1440。只改默认不改 CHECK,后台填 1440 会在写库那一刻炸,
  -- 而且是 SQLIntegrityConstraintViolation 这种没人看得懂的报错。
  (SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_club_event_series_offer'
     AND check_clause LIKE '%1440%') = 1
  AND
  -- ⚠️ 本条同时**接管**了 migration_club_role_event_ops_20260823 的结构断言:
  --    那条把 club_event_series 的整列指纹算进 SHA2,改默认值必然让它判假,于是它被登记成
  --    「由本迁移替代」(ci/migration-assertion-supersessions.json)。替代不能只换一句弱断言 ——
  --    否则这七张表以后加列 / 删 CHECK 都不再有门禁看得见。把它守的三条结构事实原样接过来。
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('club_event_series','club_event_occurrence','club_event_waitlist',
       'club_event_attendance_correction','club_event_notification_campaign',
       'club_event_notification_delivery','club_event_notification_outbox')) = 7
  AND
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'club_event_series') = 19
  AND
  (SELECT COUNT(*) FROM information_schema.table_constraints
   WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
     AND table_name IN ('club_event_series','club_event_occurrence','club_event_waitlist',
       'club_event_attendance_correction','club_event_notification_campaign',
       'club_event_notification_delivery','club_event_notification_outbox')) = 15
  AS ok;
