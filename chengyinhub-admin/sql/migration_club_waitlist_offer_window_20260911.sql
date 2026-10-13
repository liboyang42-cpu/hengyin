-- 候补认领窗口:默认 30 分钟 → 一天,上限 120 分钟 → 一天(2026-09-11 用户裁决)。
--
-- 为什么要改:候补被叫到号之后必须在这个窗口内完成付款,否则名额顺延给下一个人。
-- 30 分钟意味着「手机没在身边就丢名额」;俱乐部活动大多提前几天甚至几周开卖,
-- 没有理由按分钟计。
--
-- ⚠️ 代价要说清楚:窗口拉长 = 一个被保留的名额最多占住一天,队列里后面的人要等这么久,
--    而且对方可以一直不理它。真正的护栏不是这个上限,而是 ClubWaitlistServiceImpl.offerDeadline
--    里那句「取 min(now+窗口, 票的报名截止/开场时间)」—— 窗口再长也活不过这张票本身。
--    急场(当天开卖)仍可由主理人把窗口手动调小,下限保持 5 分钟不动。
--
-- 存量影响:offer_minutes 是每个系列自己存的值,已有系列继续用它们已经存的数;
-- 这次只改「新建时的默认」和「允许的上限」,不回填任何一行。

ALTER TABLE club_event_series DROP CHECK chk_club_event_series_offer;
ALTER TABLE club_event_series ADD CONSTRAINT chk_club_event_series_offer CHECK (offer_minutes BETWEEN 5 AND 1440);
ALTER TABLE club_event_series ALTER COLUMN offer_minutes SET DEFAULT 1440;

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
   WHERE table_schema = DATABASE() AND table_name = 'club_event_series') = 18
  AND
  (SELECT COUNT(*) FROM information_schema.table_constraints
   WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
     AND table_name IN ('club_event_series','club_event_occurrence','club_event_waitlist',
       'club_event_attendance_correction','club_event_notification_campaign',
       'club_event_notification_delivery','club_event_notification_outbox')) = 15
  AS ok;
