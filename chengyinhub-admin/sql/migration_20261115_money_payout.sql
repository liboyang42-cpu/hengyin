-- R6b money payout (slug money-payout, 2026-11-15).
-- Withdrawal in TEST: a member request -> admin review queue -> payout through the existing
-- WeChat transfer integration (member_cash_withdraw). Payout receiver binding from the app: a server-issued,
-- short-lived binding code shown as a QR code, claimed in the mini-program with wx.login, confirmed in the app.
-- Everything here is refused unless the money switch area is open (payout / withdrawal); no row moves money.
-- Idempotent: CREATE TABLE IF NOT EXISTS, menu rows guarded by existence checks. Granted to no role.
--
-- ROLLBACK (only while every table below is empty or archived):
--   DELETE FROM sys_menu WHERE perms IN ('business:payoutRequest:review');
--   DELETE FROM sys_menu WHERE perms IN ('business:payoutRequest:list');
--   DROP TABLE IF EXISTS cy_payout_bind_code;
--   DROP TABLE IF EXISTS cy_member_payout_wechat;
--   -- cy_payout_request is a money record: archive it, never drop it while it has rows.

CREATE TABLE IF NOT EXISTS cy_payout_request (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  member_id        BIGINT        NOT NULL COMMENT 'requesting member (ums_member.id)',
  amount           DECIMAL(12,2) NOT NULL COMMENT 'yuan; debited only when the payout is sent',
  status           VARCHAR(16)   NOT NULL COMMENT 'PENDING | PAYING | SENT | FAILED | REJECTED | CANCELLED',
  cash_withdraw_id BIGINT        NULL     COMMENT 'member_cash_withdraw.id once the transfer is created',
  review_by        VARCHAR(64)   NULL,
  review_note      VARCHAR(255)  NULL,
  fail_reason      VARCHAR(255)  NULL,
  create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  review_time      DATETIME      NULL,
  update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_payout_request_member (member_id, id),
  KEY idx_payout_request_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R6b withdrawal requests waiting for admin review';

CREATE TABLE IF NOT EXISTS cy_payout_bind_code (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  code_hash      CHAR(64)     NOT NULL COMMENT 'sha256 hex of the code; the code itself is never stored',
  purpose        VARCHAR(24)  NOT NULL COMMENT 'WALLET | CLUB_RECEIVER',
  member_id      BIGINT       NOT NULL COMMENT 'app account the binding is for',
  club_id        BIGINT       NOT NULL DEFAULT 0,
  status         VARCHAR(16)  NOT NULL COMMENT 'ISSUED | CLAIMED | CONFIRMED | CANCELLED',
  claimed_openid VARCHAR(128) NULL     COMMENT 'mini-program openid proven by wx.login',
  expires_at     DATETIME     NOT NULL,
  claimed_at     DATETIME     NULL,
  confirmed_at   DATETIME     NULL,
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payout_bind_code_hash (code_hash),
  KEY idx_payout_bind_code_member (member_id, purpose, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R6b short-lived binding codes (QR) for payout receivers';

CREATE TABLE IF NOT EXISTS cy_member_payout_wechat (
  member_id  BIGINT       NOT NULL,
  appid      VARCHAR(64)  NOT NULL COMMENT 'mini-program appid the openid belongs to',
  openid     VARCHAR(128) NOT NULL,
  bound_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R6b WeChat payout identity bound from the app (wallet withdrawals)';

SET @payoutParentCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @payoutParent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @payoutExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:payoutRequest:list');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '提现审核', @payoutParent, 81, 'payout-request', 'business/payoutrequest/index', '', 'PayoutRequest',
       1, 0, 'C', '0', '0', 'business:payoutRequest:list', 'money', 'migration', NOW(),
       'R6b：会员提现申请审核队列(仅资金开关 withdrawal 打开时可通过并打款)'
FROM dual
WHERE @payoutParentCount = 1 AND @payoutExisting = 0;

SET @payoutMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:payoutRequest:list' AND menu_type = 'C');
SET @payoutReviewExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:payoutRequest:review');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '提现审核处理', @payoutMenu, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:payoutRequest:review', '#', 'migration', NOW(),
       'R6b：通过(经微信商家转账打款)或驳回一条提现申请(须填写说明)'
FROM dual
WHERE @payoutMenu IS NOT NULL AND @payoutReviewExisting = 0;

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name IN ('cy_payout_request', 'cy_payout_bind_code', 'cy_member_payout_wechat')) = 3
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms IN ('business:payoutRequest:list', 'business:payoutRequest:review')) = 0
) AS money_payout_ready;
