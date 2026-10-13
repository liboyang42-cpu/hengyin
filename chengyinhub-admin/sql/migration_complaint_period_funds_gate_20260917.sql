-- 客诉期提现闸(2026-09-16 晚用户拍板 1-5,release-0917)
--
-- 【类别】③ DDL,两张新表,只加不改。**jar 部署之前落库**:新 jar 的活动结算与退款冲销
--   会读写这两张表,表不在 = 结算任务整轮报错(fail-closed,不会多打钱,但钱全部结不出去)。
--
-- 1) cms_activity_settlement_hold —— 涉诉订单结算暂扣
--    活动结束满 7 天结算时,还有未结客诉(coop_complaint.status<3)的报名单从本次结算剔出、
--    在这里登记一行 status=0;其余照常入余额。客诉关闭后结算任务下一轮补结(status=1),
--    报名已退款则作废(status=2,从没打过款,不冲销)。
-- 2) ums_member_settlement_debt —— 收款方欠款
--    客诉期后仍发生退款冲销 / 客诉扣划,余额扣到 0 还不够的差额记为欠款(原先只打告警转人工)。
--    之后该收款方的每笔结算入账先抵欠款;到期(due_time)未清 = 不能发布新活动 / 开新场次。
--
-- 【幂等】CREATE TABLE IF NOT EXISTS,重跑无副作用。
-- 【可逆】DROP TABLE cms_activity_settlement_hold; DROP TABLE ums_member_settlement_debt;
--   (回滚 jar 之后才可 DROP;有 status=0 的暂扣行时先人工核对再删,否则涉诉部分永远结不出)
-- 【只读预检】SELECT table_name FROM information_schema.tables
--   WHERE table_schema = DATABASE() AND table_name IN ('cms_activity_settlement_hold','ums_member_settlement_debt');

CREATE TABLE IF NOT EXISTS cms_activity_settlement_hold (
  id BIGINT NOT NULL AUTO_INCREMENT,
  activity_id BIGINT NOT NULL COMMENT '活动 id(cms_activity.id)',
  registration_id BIGINT NOT NULL COMMENT '涉诉报名单 id(cms_registration.id)',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0暂扣中 1已补结入余额 2报名已退款作废',
  create_time DATETIME NOT NULL,
  update_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hold_registration (registration_id),
  KEY idx_hold_status (status, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动结算涉诉订单暂扣';

CREATE TABLE IF NOT EXISTS ums_member_settlement_debt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL COMMENT '欠款收款方 ums_member.id',
  source_type VARCHAR(32) NOT NULL COMMENT 'refund=退款冲销 complaint=客诉扣划',
  source_id BIGINT NOT NULL COMMENT 'refund_application.id / coop_complaint.id',
  amount DECIMAL(12,2) NOT NULL COMMENT '欠款额(余额扣不动的差额)',
  repaid_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '已从后续结算抵扣',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0未清 1已清',
  due_time DATETIME NOT NULL COMMENT '限期补足截止;过期未清限制发布新活动',
  create_time DATETIME NOT NULL,
  update_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_debt_source (source_type, source_id, member_id),
  KEY idx_debt_member_status (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款方结算欠款';
