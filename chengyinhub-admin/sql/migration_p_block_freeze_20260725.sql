-- P1-6 P块:运营侧"冻结"能力(冻结某会员提现 / 冻结某商家结算放款)
--
-- 【背景】P1-6只读预检已确认:后台资金控制台(查询/退款/结算/提现)相当完整,
--   唯独没有运营可操作的"冻结"功能——全仓搜索冻结相关只命中IM拉黑(无关)和
--   "拓展人赏金48h冻结期"(自动过期,非人工操作)。本迁移给两张表各加一组冻结字段。
--
-- 【范围】只做这两个具体场景,不做通用冻结框架:
--   · member_cash_withdraw(微信零钱提现,通道A):冻结后 WithdrawTransferQueryTask 定时查询
--     和人工补单(manualRefund)均跳过该单,暂停处理,不影响其它单。
--   · merchant_payable_batch(③计酬档应付月结批次):冻结后 confirm(对账确认)/markPaid
--     (记录打款事实)均被拦,暂停该批次继续流转,不影响其它批次。
--
-- 【不做什么】冻结/解冻本身绝不移动资金——只是暂停自动化处理/人工推进的标志位,
--   金额、状态机的其它字段一律不动。
--
-- 【类别】① schema 迁移(DDL,新增可空列,存量数据不受影响)。
-- 【部署顺序】必须先于引用新列的 jar 落库(否则 insert/select 报 Unknown column)。
-- 【幂等】MySQL 8:用 information_schema 预检 + PREPARE,禁用 MariaDB 专属
--   ADD COLUMN IF NOT EXISTS(会报 1064)。可重复执行。
-- 【可逆】
--   ALTER TABLE member_cash_withdraw DROP COLUMN frozen, DROP COLUMN frozen_reason, DROP COLUMN frozen_by, DROP COLUMN frozen_at;
--   ALTER TABLE merchant_payable_batch DROP COLUMN frozen, DROP COLUMN frozen_reason, DROP COLUMN frozen_by, DROP COLUMN frozen_at;
-- 【风险】低。均为可空新增列,不改已有列、不影响存量读写。
--
-- 生产执行前必须先用 chengyin-db-migrate --describe member_cash_withdraw / merchant_payable_batch 只读预检。


-- ======================= member_cash_withdraw =======================

SET @cw_frozen_exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw'
                  AND column_name = 'frozen');
SET @cw_sql := IF(@cw_frozen_exists = 0,
  'ALTER TABLE member_cash_withdraw
     ADD COLUMN frozen TINYINT(1) NULL DEFAULT 0 COMMENT ''P块冻结:1=冻结中(暂停自动化处理,不动余额),0/NULL=未冻结'',
     ADD COLUMN frozen_reason VARCHAR(255) NULL COMMENT ''冻结原因(运营填写)'',
     ADD COLUMN frozen_by VARCHAR(64) NULL COMMENT ''冻结操作人(sys_user.user_name)'',
     ADD COLUMN frozen_at DATETIME NULL COMMENT ''冻结时间''',
  'SELECT ''member_cash_withdraw.frozen already exists, skip'' ');
PREPARE cw_stmt FROM @cw_sql;
EXECUTE cw_stmt;
DEALLOCATE PREPARE cw_stmt;


-- ======================= merchant_payable_batch =======================

SET @pb_frozen_exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'merchant_payable_batch'
                  AND column_name = 'frozen');
SET @pb_sql := IF(@pb_frozen_exists = 0,
  'ALTER TABLE merchant_payable_batch
     ADD COLUMN frozen TINYINT(1) NULL DEFAULT 0 COMMENT ''P块冻结:1=冻结中(暂停对账确认/打款,不动金额),0/NULL=未冻结'',
     ADD COLUMN frozen_reason VARCHAR(255) NULL COMMENT ''冻结原因(运营填写)'',
     ADD COLUMN frozen_by VARCHAR(64) NULL COMMENT ''冻结操作人(sys_user.user_name)'',
     ADD COLUMN frozen_at DATETIME NULL COMMENT ''冻结时间''',
  'SELECT ''merchant_payable_batch.frozen already exists, skip'' ');
PREPARE pb_stmt FROM @pb_sql;
EXECUTE pb_stmt;
DEALLOCATE PREPARE pb_stmt;


-- ======================= 回读断言(回执 ≠ 观测,必须看到 4 列) =======================

SELECT table_name, column_name, column_type, column_comment FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name IN ('member_cash_withdraw', 'merchant_payable_batch')
   AND column_name IN ('frozen', 'frozen_reason', 'frozen_by', 'frozen_at')
 ORDER BY table_name, column_name;
