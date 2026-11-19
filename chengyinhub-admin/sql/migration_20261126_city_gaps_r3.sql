-- City Monopoly gaps r3 (qa/CITY-GAPS-R3.md): deed serials, district names/colour keys backfill, on-site task submissions.
-- 积分 only; no financial ledger tables are touched. Additive & re-runnable: guarded ADD COLUMN / ADD KEY,
-- CREATE TABLE IF NOT EXISTS, INSERT IGNORE backfills.
-- Rollback (in this order; serials and submissions are discarded):
--   ALTER TABLE roam_territory DROP INDEX uk_roam_territory_deed, DROP COLUMN deed_serial_live, DROP COLUMN deed_serial;
--   DROP TABLE IF EXISTS city_deed_counter, city_onsite_task_submission;
--   (city_district rows inserted by section 4 may stay; they are plain metadata. To remove only the backfilled ones:
--    DELETE FROM city_district WHERE name = district_id;)

-- 1. Deed serial on the territory. deed_serial_live is the serial while the deed is ACTIVE, so a takeover (old row
--    REVOKED, new row ACTIVE with the same serial) never collides, while two live deeds of one district can never share
--    a number.
SET @cg3_terr_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='roam_territory' AND column_name='deed_serial');
SET @cg3_terr_sql := IF(@cg3_terr_cols=0,
  'ALTER TABLE roam_territory ADD COLUMN deed_serial int DEFAULT NULL COMMENT ''地契 No. N, issued per board district at first claim; kept on takeover'', ADD COLUMN deed_serial_live int GENERATED ALWAYS AS (IF(status = ''ACTIVE'', deed_serial, NULL)) VIRTUAL, ADD UNIQUE KEY uk_roam_territory_deed (board_id, district_id, deed_serial_live)',
  'SELECT 1');
PREPARE cg3_terr_stmt FROM @cg3_terr_sql;
EXECUTE cg3_terr_stmt;
DEALLOCATE PREPARE cg3_terr_stmt;

-- 2. Per-district serial counter (row lock serializes concurrent first claims; a rolled-back claim returns its number).
CREATE TABLE IF NOT EXISTS `city_deed_counter` (
  `board_id` varchar(96) NOT NULL,
  `district_id` varchar(64) NOT NULL,
  `last_serial` int NOT NULL DEFAULT 0,
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`board_id`, `district_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly deed serial counter per board district';

-- 3. Backfill: live MONOPOLY deeds without a serial get numbers after the district's counter, in claim order;
--    then every counter catches up with the highest serial issued. Re-running only numbers rows still NULL.
UPDATE roam_territory t
JOIN (
  SELECT x.territory_id,
         COALESCE(k.last_serial, 0) + ROW_NUMBER() OVER (PARTITION BY x.board_id, x.district_id ORDER BY x.claimed_at, x.territory_id) AS serial
  FROM roam_territory x
  LEFT JOIN city_deed_counter k ON k.board_id = x.board_id AND k.district_id = x.district_id
  WHERE x.rules_kind = 'MONOPOLY' AND x.status = 'ACTIVE' AND x.deed_serial IS NULL
    AND x.board_id IS NOT NULL AND x.district_id IS NOT NULL AND x.district_id <> ''
) n ON n.territory_id = t.territory_id
SET t.deed_serial = n.serial
WHERE t.rules_kind = 'MONOPOLY' AND t.status = 'ACTIVE' AND t.deed_serial IS NULL;

INSERT INTO city_deed_counter(board_id, district_id, last_serial)
SELECT board_id, district_id, MAX(deed_serial) FROM roam_territory
WHERE deed_serial IS NOT NULL AND board_id IS NOT NULL AND district_id IS NOT NULL
GROUP BY board_id, district_id
ON DUPLICATE KEY UPDATE last_serial = GREATEST(last_serial, VALUES(last_serial));

-- 4. District metadata: every district referenced by a board point gets a city_district row if it has none.
--    Name defaults to the district id (operations rename it with UPDATE city_district SET name = ... ; the reads serve
--    whatever is stored), colour key district.c1..c6 by sorted district id per board (the same stable order the iOS
--    client uses for its slots), ownable_count = live points in the district. Existing rows are never overwritten.
INSERT IGNORE INTO city_district(board_id, district_id, name, color_token, ownable_count)
SELECT d.board_id, d.district_id, LEFT(d.district_id, 64),
       CONCAT('district.c', ((ROW_NUMBER() OVER (PARTITION BY d.board_id ORDER BY d.district_id) - 1) % 6) + 1),
       d.points
FROM (
  SELECT board_id, district_id, COUNT(*) AS points FROM city_game_point
  WHERE del_flag = 0 AND district_id IS NOT NULL AND district_id <> ''
  GROUP BY board_id, district_id
) d;

-- 5. On-site challenge task submissions (one per member and issued task; consumed once by the challenge command).
CREATE TABLE IF NOT EXISTS `city_onsite_task_submission` (
  `submission_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `task_ref` varchar(160) NOT NULL,
  `territory_id` char(36) NOT NULL,
  `quote_id` char(36) NOT NULL,
  `task_type` varchar(24) NOT NULL DEFAULT 'PHOTO_PRESENCE',
  `photo_url` varchar(500) NOT NULL,
  `lat` decimal(16,12) DEFAULT NULL,
  `lng` decimal(16,12) DEFAULT NULL,
  `h_acc` double DEFAULT NULL,
  `distance_m` double DEFAULT NULL,
  `presence_ok` tinyint NOT NULL DEFAULT 0,
  `vision_state` varchar(16) NOT NULL COMMENT 'PASS/FAIL/DEGRADED/SKIPPED',
  `confidence` int DEFAULT NULL,
  `vision_reason` varchar(120) DEFAULT NULL,
  `result` varchar(8) NOT NULL COMMENT 'NONE/FAIL/PASS/PERFECT',
  `reasons` varchar(255) NOT NULL DEFAULT '',
  `consumed_at` datetime(3) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`submission_id`),
  UNIQUE KEY `uk_city_onsite_task_member_ref` (`member_id`, `task_ref`),
  KEY `idx_city_onsite_task_territory` (`territory_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly on-site challenge task submissions (server-graded)';
