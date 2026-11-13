-- App gaps A pass 2: restore the npc_route_egg table (owner decision 2026-11-13, relayed by the coordinator).
--
-- History: migration_x11_decommission_route_eggs_20260907.sql dropped this table (2026-09-07 decision to take route
-- eggs offline). The app's egg collection (POST /api/play/egg/collect, served on feature/app-gaps-a) reads reviewed
-- rows from it, and TEST schemas built from the ledger have no table, so the route answers 500 there. This
-- migration only recreates the empty table with the baseline DDL; it restores no rows, re-enables no producer
-- (no AI generation, no author write path) and must be ordered after x11 (ci/migration-dependencies.json).
-- Until operations insert reviewed rows (status = 1), every collect answers 404.
CREATE TABLE IF NOT EXISTS npc_route_egg (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  topic_id   BIGINT        NULL,
  seq        INT           NULL COMMENT '沿路顺序',
  lat        DECIMAL(10,6) NULL,
  lng        DECIMAL(10,6) NULL,
  radius_m   INT           DEFAULT 120 COMMENT '触发半径(米)',
  text       VARCHAR(300)  NULL COMMENT '彩蛋文案(≤80字,预审入库)',
  status     TINYINT       NULL COMMENT '0待审 1已审',
  created_at DATETIME      NULL,
  PRIMARY KEY (id),
  KEY idx_topic (topic_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M5 途中彩蛋池(每主题≤15条)';
