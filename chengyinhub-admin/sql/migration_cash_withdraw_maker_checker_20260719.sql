-- E-2 零钱提现人工回补双人复核。
-- 只改 schema，不触碰业务数据；部署顺序：先执行本迁移，再发布引用 manual_verifier 的 jar。
-- status 新增语义：4=人工已登记，待第二人复核。第二人须与 manual_operator 不同。

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw' AND column_name = 'manual_verifier');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE member_cash_withdraw ADD COLUMN manual_verifier VARCHAR(64) NULL COMMENT ''人工回补第二复核人(须与处理人不同)'' AFTER manual_operator',
  'SELECT ''manual_verifier already exists, skip''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- status=4 仍是资金在途，必须继续占用每会员唯一在途键。
ALTER TABLE member_cash_withdraw
  MODIFY COLUMN pending_member_key BIGINT
  GENERATED ALWAYS AS (CASE WHEN status IN (0, 3, 4) THEN member_id ELSE NULL END) VIRTUAL
  COMMENT '在途(0/3/4)时=member_id,用于唯一约束原子防重';
