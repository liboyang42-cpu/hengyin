-- R12 creator-earnings（GAM-10 / MON-06）：创作者收益台账，填充 creator_income_detail，菜单「创作者收益」。
-- 回退：本迁移只新增表、只给 creator_income_detail 加可空列、只插一条菜单。旧 jar 不读这些对象。
--   回退时保留表与列（台账是只追加的事实，删表会让下次重算重复发积分）；只需要
--   DELETE FROM sys_menu WHERE component='business/creator/earnings' 即可隐藏后台入口。
-- 执行方式：走 chengyin-db-migrate；可重复执行（IF NOT EXISTS / information_schema 守卫 / NOT EXISTS）。
-- 资金口径：本台账只记积分与「按售价应得的分成意向」。money_state 永远不是已付款：
--   钱的开关（chengyin.release.money-payments）关闭时记 HELD_MONEY_OFF；开启后记 PENDING_PAYOUT，仍不打款。

CREATE TABLE IF NOT EXISTS creator_earning_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务事件确定性幂等键',
  event_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ADOPT/INSTALL/PLAY/SALE/SALE_REVERSAL',
  creator_member_id BIGINT NOT NULL COMMENT '创作者 ums_member.id',
  actor_member_id BIGINT NULL COMMENT '使用者/玩家/买家 ums_member.id',
  item_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TEMPLATE/WORKSHOP',
  item_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '模板库 id 或工坊版本 version_id',
  item_title VARCHAR(255) NULL COMMENT '记账时的标题快照',
  source_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源事实主键（领用回执/打卡记录/订单号）',
  points INT NOT NULL DEFAULT 0 COMMENT '本事件给创作者的积分（已入账）',
  capped TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=有效游玩但已到每日上限，不发积分',
  gross_minor BIGINT NOT NULL DEFAULT 0 COMMENT '售价（最小货币单位），仅 SALE/SALE_REVERSAL',
  creator_share_bps INT NOT NULL DEFAULT 0 COMMENT '记账时的创作者分成万分比',
  creator_minor BIGINT NOT NULL DEFAULT 0 COMMENT '创作者应得意向（最小货币单位，冲正为负）',
  currency VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
  money_state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE' COMMENT 'NONE/HELD_MONEY_OFF/PENDING_PAYOUT',
  income_order_key CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'App 收益事实键 MOD+29hex，仅 SALE/SALE_REVERSAL',
  occur_time DATETIME(3) NOT NULL COMMENT '来源事实发生时间',
  occur_date DATE NOT NULL COMMENT '每日上限按此日（Asia/Shanghai）',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cel_event (event_key),
  KEY idx_cel_creator_time (creator_member_id, id),
  KEY idx_cel_creator_day (creator_member_id, occur_date, event_type),
  KEY idx_cel_actor_day (creator_member_id, actor_member_id, item_kind, item_ref, occur_date),
  KEY idx_cel_order_key (income_order_key, id),
  CONSTRAINT ck_cel_type CHECK (event_type IN ('ADOPT', 'INSTALL', 'PLAY', 'SALE', 'SALE_REVERSAL')),
  CONSTRAINT ck_cel_kind CHECK (item_kind IN ('TEMPLATE', 'WORKSHOP')),
  CONSTRAINT ck_cel_points CHECK (points >= 0),
  CONSTRAINT ck_cel_money CHECK (money_state IN ('NONE', 'HELD_MONEY_OFF', 'PENDING_PAYOUT')),
  CONSTRAINT ck_cel_bps CHECK (creator_share_bps BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='创作者收益台账（只追加，每个业务事件一行）';

-- creator_income_detail 增加 points 列：积分不是金额，不写进 amount。
SET @cid_points_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='creator_income_detail' AND column_name='points');
SET @cid_points_sql := IF(@cid_points_exists=0,
    'ALTER TABLE creator_income_detail ADD COLUMN points INT NULL COMMENT ''本行积分（来自 creator_earning_ledger）'' AFTER amount',
    'SELECT 1');
PREPARE cid_points_stmt FROM @cid_points_sql;
EXECUTE cid_points_stmt;
DEALLOCATE PREPARE cid_points_stmt;

-- 后台菜单：挂在「创作者准入」同一父目录下，复用 business:creator:list 权限（只读报表 + 手动重算）。
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '创作者收益', p.parent_id, 35, 'creator-earnings', 'business/creator/earnings', 1, 0, 'C', '0', '0', 'business:creator:list', 'money', 'migration', NOW(), 'R12 创作者收益台账'
FROM (SELECT parent_id FROM sys_menu WHERE component='business/risk/creator-admission/index' ORDER BY menu_id LIMIT 1) p
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/creator/earnings');

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='creator_earning_ledger'
        AND index_name='uk_cel_event' AND non_unique=0)=1
   AND (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='creator_income_detail' AND column_name='points')=1
   AS creator_earnings_ready;
