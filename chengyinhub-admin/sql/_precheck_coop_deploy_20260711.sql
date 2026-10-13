-- ============================================================================
-- 城瘾 coop 体系部署预检(只读,服务器侧执行)· 2026-07-11
-- 目的:确认已上传 jar(b5af74ca)所需的 schema + sys_job 是否就绪,避免 Unknown column / 任务不跑。
-- 用法:登录生产库后整份执行,看 status 列。OK=已就绪;MISSING=待补(按下方"如何补"处理)。
-- 铁律:schema 缺 → 必须先补再让 jar 承接流量;sys_job 缺 → jar 部署后跑 migration_coop_deadline_sysjob.sql。
-- ============================================================================

-- 一、coop 核心表(缺任一 → 该 jar 的 coop 接口会崩;补法:跑对应 migration_coop_*.sql)
SELECT check_item, IF(cnt>0,'OK','MISSING') AS status FROM (
  SELECT 'TABLE coop_invite'        AS check_item, COUNT(*) cnt FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coop_invite'
  UNION ALL SELECT 'TABLE coop_order',        COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coop_order'
  UNION ALL SELECT 'TABLE coop_settlement',   COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coop_settlement'
  UNION ALL SELECT 'TABLE coop_review',       COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coop_review'
  UNION ALL SELECT 'TABLE coop_perk',         COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coop_perk'
  UNION ALL SELECT 'TABLE coop_perk_template',COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coop_perk_template'
  UNION ALL SELECT 'TABLE coop_credit_log',   COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coop_credit_log'
) t;

-- 二、关键列(★verified_reg_ids 是 CREATE-only 陷阱重灾区:表早存在时 IF NOT EXISTS 不补列,须手动 ALTER)
-- 补法(缺则执行):
--   ALTER TABLE coop_settlement ADD COLUMN verified_reg_ids TEXT NULL COMMENT 'T9结算审计快照:被本次结算计入的已核销报名id';
SELECT check_item, IF(cnt>0,'OK','MISSING') AS status FROM (
  SELECT 'COL coop_settlement.verified_reg_ids' AS check_item, COUNT(*) cnt FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement' AND column_name='verified_reg_ids'
  UNION ALL SELECT 'COL coop_invite.deposit_status',        COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_invite' AND column_name='deposit_status'
  UNION ALL SELECT 'COL coop_invite.deposit_out_trade_no',  COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_invite' AND column_name='deposit_out_trade_no'
  UNION ALL SELECT 'COL coop_order.terms_version',          COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_order' AND column_name='terms_version'
) t;

-- 三、sys_job 定时任务(★这些依赖 jar 内 Task 类 → 必须"jar 部署后"跑 migration_coop_deadline_sysjob.sql)
--     部署 jar 前跑本预检时它们应为 MISSING(正常);跑完 sysjob 迁移后应为 OK 且 status=0(正常运行)。
SELECT invoke_target AS check_item,
       CASE WHEN COUNT(*)=0 THEN 'MISSING(部署jar后跑sysjob迁移)'
            WHEN MAX(status)='0' THEN 'OK(正常运行)'
            ELSE 'OK但已暂停(status=1,需在后台启用)' END AS status
FROM sys_job WHERE invoke_target IN
  ('coopInviteExpireTask.run()','topicRecruitDeadlineTask.run()','coopSettlementPayoutTask.run()')
GROUP BY invoke_target
UNION ALL
SELECT '(应有3条上述任务,少的即缺)', CAST(COUNT(DISTINCT invoke_target) AS CHAR)
FROM sys_job WHERE invoke_target IN
  ('coopInviteExpireTask.run()','topicRecruitDeadlineTask.run()','coopSettlementPayoutTask.run()');

-- 四、T5 微信零钱提现(★仅当"已上传 jar 包含 T5 代码"时才需要;b5af74ca 不含 T5 → 本项 MISSING 是正常的)
--     若你的 jar 是 eb868ec1 或更新(含 ApiWalletController/CashWithdraw),缺则先跑:migration_member_cash_withdraw.sql
SELECT 'TABLE member_cash_withdraw (仅含T5的jar需要)' AS check_item,
       IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='member_cash_withdraw')>0,'OK','MISSING(含T5的jar才需补)') AS status;
