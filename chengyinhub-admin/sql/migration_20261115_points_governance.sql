-- ================= migration_20261115_points_governance.sql =================
SELECT 'migration_20261115_points_governance.sql' AS running;
-- R7 points-governance (MON-09, MKT-12, MON-10): points get per-member daily caps per award source and a monthly
-- budget per programme; territory yield gets its own non-cashable city coin.
--
-- ROLLBACK (manual, only after the backend build that reads these tables has been rolled back):
--   DROP TABLE IF EXISTS points_award_cap;
--   DROP TABLE IF EXISTS points_programme_budget;
--   DROP TABLE IF EXISTS points_budget_usage;
--   DROP TABLE IF EXISTS city_coin_account;   -- city coin balances are lost; export first if needed
--   DROP TABLE IF EXISTS city_coin_ledger;
--   DELETE FROM sys_config WHERE config_key LIKE 'points.governance.%' OR config_key LIKE 'city.coin.%';
--   DELETE FROM sys_menu WHERE perms IN ('business:pointsgov:list', 'business:pointsgov:edit');
-- The backend fails open when the governance tables are missing (points are awarded as before, an error is
-- logged), so applying this after the deploy is safe; applying it before is also safe (additive only).
-- City coin credits fail closed when its tables are missing (no coin is credited, the caller's flow continues).
--
-- Idempotent: CREATE TABLE IF NOT EXISTS; seed and config rows inserted only when missing (never overwrite ops values).
-- ⚠️ Run the chengyin-db-migrate read-only preflight and get owner confirmation before production.

-- Per-member daily cap per award source (event_type of ums_member_points_detail). NULL cap = no daily cap.
CREATE TABLE IF NOT EXISTS points_award_cap (
  event_type        BIGINT       NOT NULL COMMENT 'award source = ums_member_points_detail.event_type',
  source_name       VARCHAR(64)  NOT NULL,
  programme         VARCHAR(32)  NOT NULL COMMENT 'points_programme_budget.programme this source draws from',
  daily_cap_points  INT          DEFAULT NULL COMMENT 'max points one member can earn from this source per day; NULL = no cap',
  status            CHAR(1)      NOT NULL DEFAULT '0' COMMENT '0 = governed, 1 = not governed',
  update_by         VARCHAR(64)  DEFAULT NULL,
  update_time       DATETIME     DEFAULT NULL,
  PRIMARY KEY (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R7 points governance: per-member daily cap per award source';

-- Monthly budget per programme. NULL budget = no budget set yet (dashboard flags it).
CREATE TABLE IF NOT EXISTS points_programme_budget (
  programme              VARCHAR(32)  NOT NULL,
  programme_name         VARCHAR(64)  NOT NULL,
  monthly_budget_points  BIGINT       DEFAULT NULL COMMENT 'max points issued per calendar month; NULL = not set',
  status                 CHAR(1)      NOT NULL DEFAULT '0' COMMENT '0 = enforced, 1 = not enforced',
  update_by              VARCHAR(64)  DEFAULT NULL,
  update_time            DATETIME     DEFAULT NULL,
  PRIMARY KEY (programme)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R7 points governance: monthly budget per programme';

-- Points issued per programme per month; advanced with a conditional UPDATE inside the award transaction.
CREATE TABLE IF NOT EXISTS points_budget_usage (
  programme      VARCHAR(32)  NOT NULL,
  period         CHAR(7)      NOT NULL COMMENT 'yyyy-MM',
  issued_points  BIGINT       NOT NULL DEFAULT 0,
  update_time    DATETIME     DEFAULT NULL,
  PRIMARY KEY (programme, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R7 points governance: points issued per programme per month';

-- City coin: non-cashable territory currency. Credits only; no statement converts or spends it.
CREATE TABLE IF NOT EXISTS city_coin_account (
  member_id    BIGINT    NOT NULL,
  balance      BIGINT    NOT NULL DEFAULT 0,
  create_time  DATETIME  NOT NULL,
  update_time  DATETIME  DEFAULT NULL,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R7 city coin balance (non-cashable, never converted)';

CREATE TABLE IF NOT EXISTS city_coin_ledger (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  member_id      BIGINT        NOT NULL,
  amount         INT           NOT NULL COMMENT 'always > 0: city coin is only ever credited',
  balance_after  BIGINT        NOT NULL,
  reason         VARCHAR(32)   NOT NULL COMMENT 'HOLDER_YIELD',
  ref_key        VARCHAR(160)  NOT NULL COMMENT 'unique reference per credit (idempotency)',
  place_id       VARCHAR(64)   DEFAULT NULL,
  create_time    DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_coin_ref (ref_key),
  KEY idx_city_coin_member_time (member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R7 city coin append-only ledger';

-- Seed programmes. Budgets start unset: the owner sets them in the admin console (积分治理).
INSERT INTO points_programme_budget (programme, programme_name, monthly_budget_points, status, update_by, update_time)
SELECT 'PLAY', '游玩奖励', NULL, '0', 'migration', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM points_programme_budget WHERE programme = 'PLAY');
INSERT INTO points_programme_budget (programme, programme_name, monthly_budget_points, status, update_by, update_time)
SELECT 'VISIT', '报名与到店奖励', NULL, '0', 'migration', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM points_programme_budget WHERE programme = 'VISIT');

INSERT INTO points_programme_budget (programme, programme_name, monthly_budget_points, status, update_by, update_time)
SELECT 'COMMUNITY', '评价奖励', NULL, '0', 'migration', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM points_programme_budget WHERE programme = 'COMMUNITY');

-- Seed daily caps for the governed sources: PointsAwardServiceImpl.award (10 报名成功, 11 到店核销, 12 节点打卡,
-- 13 通关) and CommentPublicationService (4 活动评价). 500 points = ¥10 at the 0.02 rate.
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 10, '报名成功', 'VISIT', 500, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 10);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 11, '到店核销', 'VISIT', 500, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 11);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 12, '节点打卡', 'PLAY', 500, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 12);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 13, '通关', 'PLAY', 500, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 13);
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 4, '活动评价', 'COMMUNITY', 500, '0', 'migration', NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 4);

-- Config. points.governance.mode: ENFORCE (default) applies caps and budgets; SHADOW only logs; OFF skips.
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '积分治理-模式', 'points.governance.mode', 'ENFORCE', 'N', 'admin', NOW(),
       'ENFORCE 执行每日上限与月度预算;SHADOW 只记录不拦截;OFF 不检查。缺省 ENFORCE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'points.governance.mode');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市币-每次占领产出', 'city.coin.holder_yield', '10', 'N', 'admin', NOW(),
       '持有地点每次结算给持有人的城市币。城市币不可提现、不可兑换积分/现金/券/订单'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.coin.holder_yield');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市币-每人每日上限', 'city.coin.daily_cap', '100', 'N', 'admin', NOW(),
       '一个会员每天最多获得的城市币'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.coin.daily_cap');

-- Admin menu under 运营规则 (path 'rules'), next to 积分配置. Not granted to any role here.
SET @pgRulesCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'rules');
SET @pgRules := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'rules');
SET @pgExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:pointsgov:list');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '积分治理', @pgRules, 21, 'pointsgov', 'business/pointsgov/index', '', 'PointsGovernance',
       1, 0, 'C', '0', '0', 'business:pointsgov:list', 'chart', 'migration', NOW(),
       'R7:积分每日上限、月度预算、发放/兑换/未兑负债看板'
FROM dual WHERE @pgRulesCount = 1 AND @pgExisting = 0;
SET @pgMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:pointsgov:list' AND menu_type = 'C');
SET @pgEditExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:pointsgov:edit');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '积分治理修改', @pgMenu, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:pointsgov:edit', '#', 'migration', NOW(), 'R7:改每日上限与月度预算'
FROM dual WHERE @pgMenu IS NOT NULL AND @pgEditExisting = 0;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(1) FROM points_programme_budget WHERE programme IN ('PLAY', 'VISIT', 'COMMUNITY')) = 3
   AND (SELECT COUNT(1) FROM points_award_cap WHERE event_type IN (4, 10, 11, 12, 13)) = 5
   AND (SELECT COUNT(1) FROM sys_config WHERE config_key IN ('points.governance.mode', 'city.coin.holder_yield', 'city.coin.daily_cap')) = 3
   AS points_governance_ready;
