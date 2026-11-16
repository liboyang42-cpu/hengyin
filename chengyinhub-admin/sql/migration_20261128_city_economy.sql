-- ================= migration_20261128_city_economy.sql =================
SELECT 'migration_20261128_city_economy.sql' AS running;
-- W7 x-city-economy (2026-11-16): one honest City Monopoly economy, 积分 only (owner decision 1, CITY-7 option A).
--   CITY-7  City Monopoly income becomes governed award sources of points governance: event types 71-76 in
--           points_award_cap under the new CITY programme (budget unset = tracked). A refused credit keeps its
--           city_points_flow row PENDING with hold_reason = 'GOVERNANCE' and the 03:03 settle retries it.
--   CITY-8  The opening credit is sink-bound: city_member_season.stipend_left, spent on claims, upgrades, shields and
--           scouting, zeroed at season settle; it never touches ums_member.point.
--   CITY-15 Debt follows the member: city_member_debt, backfilled once from open city_member_season.debt.
--           New switch city.monopoly.abuse.clawback_lookback_d = 60 (clawbacks reach back across seasons).
--   CITY-19 Each board without districts gets one default district 'd1' (全城) holding its places, so district
--           rules run until operators author real districts in the console (城市棋盘 → 等级与街区).
--
-- Rollback instructions (only after the backend build that reads these has been rolled back):
--   DELETE FROM points_award_cap WHERE event_type IN (71,72,73,74,75,76) AND update_by = 'migration';
--   DELETE FROM points_programme_budget WHERE programme = 'CITY' AND update_by = 'migration';
--   DELETE FROM sys_config WHERE config_key = 'city.monopoly.abuse.clawback_lookback_d';
--   UPDATE city_points_flow SET hold_reason = NULL;  -- held credits stay PENDING; settle them by hand first
--   ALTER TABLE city_points_flow DROP INDEX idx_city_points_flow_hold, DROP COLUMN hold_reason;
--   ALTER TABLE city_member_season DROP COLUMN stipend_left;
--   DROP TABLE IF EXISTS city_member_debt;  -- member debt is lost; export first if needed
--   (The default districts are ordinary authored data; delete them in the console if unwanted.)
--
-- Additive and idempotent: guarded ADD COLUMN, CREATE TABLE IF NOT EXISTS, INSERT ... WHERE NOT EXISTS (an operator's
-- value is never overwritten). Points tables (ums_member, ums_member_points_detail) are not touched.
-- ⚠️ Run the chengyin-db-migrate read-only preflight and get owner confirmation before production.

-- ---------------------------------------------------------------- CITY-7 governance hold
SET @ce_flow_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'city_points_flow' AND column_name = 'hold_reason');
SET @ce_flow_sql := IF(@ce_flow_cols = 0,
  'ALTER TABLE city_points_flow ADD COLUMN hold_reason varchar(16) DEFAULT NULL COMMENT ''GOVERNANCE = points governance refused the credit; retried at settle'', ADD KEY idx_city_points_flow_hold (hold_reason, state, id)',
  'SELECT 1');
PREPARE ce_flow_stmt FROM @ce_flow_sql;
EXECUTE ce_flow_stmt;
DEALLOCATE PREPARE ce_flow_stmt;

INSERT INTO points_programme_budget (programme, programme_name, monthly_budget_points, status, update_by, update_time)
SELECT 'CITY', '城市大富翁', NULL, '0', 'migration', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM points_programme_budget WHERE programme = 'CITY');

-- Daily caps per member follow the game's own ceilings: rent ≤ owner daily soft 200 + over 100 + charm 100;
-- visitor reward 2 × 10 yielding check-ins; chance 15 × 2. A refund returns the player's own fee (not governed).
-- The opening credit (73) is no longer paid in 积分 (CITY-8); its row stays for old history.
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 71, '城市大富翁收租', 'CITY', 400, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 71);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 72, '城市大富翁访客奖励', 'CITY', 20, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 72);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 73, '城市大富翁开城补贴（已停发积分）', 'CITY', 0, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 73);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 74, '城市大富翁守擂补偿', 'CITY', 300, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 74);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 75, '城市大富翁挑战退款', 'CITY', NULL, '1', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 75);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 76, '城市大富翁机会格', 'CITY', 30, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 76);

-- ---------------------------------------------------------------- CITY-8 sink-bound opening credit
SET @ce_season_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'city_member_season' AND column_name = 'stipend_left');
SET @ce_season_sql := IF(@ce_season_cols = 0,
  'ALTER TABLE city_member_season ADD COLUMN stipend_left bigint NOT NULL DEFAULT 0 COMMENT ''opening credit left this season; spent on claims, upgrades, shields, scouting; not 积分''',
  'SELECT 1');
PREPARE ce_season_stmt FROM @ce_season_sql;
EXECUTE ce_season_stmt;
DEALLOCATE PREPARE ce_season_stmt;

-- ---------------------------------------------------------------- CITY-15 member-level debt
CREATE TABLE IF NOT EXISTS `city_member_debt` (
  `member_id` bigint NOT NULL,
  `debt` bigint NOT NULL DEFAULT 0 COMMENT '积分 owed (unpaid upkeep, clawbacks); repaid from later income in any season',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly member debt across seasons (CITY-15)';

-- Backfill once: a member that already has a row is left alone, so a re-run never double counts.
INSERT INTO city_member_debt (member_id, debt)
SELECT s.member_id, SUM(s.debt) FROM city_member_season s
WHERE s.debt > 0 AND NOT EXISTS (SELECT 1 FROM city_member_debt d WHERE d.member_id = s.member_id)
GROUP BY s.member_id;

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.clawback_lookback_d', 'city.monopoly.abuse.clawback_lookback_d', '60', 'Y', 'admin', NOW(), 'CITY-15 clawback lookback across seasons (days)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.clawback_lookback_d');

-- ---------------------------------------------------------------- CITY-19 default district per board
INSERT INTO city_district (board_id, district_id, name, color_token)
SELECT DISTINCT p.board_id, 'd1', '全城', 'district.c1' FROM city_game_point p
WHERE p.del_flag = 0 AND NOT EXISTS (SELECT 1 FROM city_district d WHERE d.board_id = p.board_id);

UPDATE city_game_point p SET p.district_id = 'd1'
WHERE p.district_id IS NULL AND p.del_flag = 0
  AND EXISTS (SELECT 1 FROM city_district d WHERE d.board_id = p.board_id AND d.district_id = 'd1');

UPDATE roam_territory t JOIN city_game_point p ON p.board_id = t.board_id AND p.point_id = t.point_id
SET t.district_id = p.district_id
WHERE t.district_id IS NULL AND p.district_id IS NOT NULL AND t.status = 'ACTIVE' AND t.rules_kind = 'MONOPOLY';

UPDATE city_district d SET d.ownable_count = (
  SELECT COUNT(*) FROM city_game_point p JOIN city_game_property pr ON pr.board_id = p.board_id AND pr.point_id = p.point_id
  WHERE p.board_id = d.board_id AND p.district_id = d.district_id AND p.del_flag = 0 AND pr.role = 'OPERABLE')
WHERE d.district_id = 'd1' AND d.ownable_count = 0;
