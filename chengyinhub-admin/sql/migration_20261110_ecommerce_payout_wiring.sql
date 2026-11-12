-- l-payout2 2026-11-10：电商收付通接入现有支付链路（下单 / 回调 / 退款 / 结算）所需的新增结构。
-- 类别：③ 数据迁移。Additive only：一张新表 + 两条暂停状态的定时任务 + 一个后台菜单（零角色授权）。
-- 依赖：migration_20261110_ecommerce_payout.sql（ecom_* 表）。
-- 所有写入路径都在 payout.ecommerce.* 开关之后，默认关闭；开关关闭时没有任何代码读写本表。
-- 定时任务以 status='1'（暂停）写入：开通电商收付通并打开开关后，由负责人在后台「定时任务」里手动启用。

CREATE TABLE IF NOT EXISTS ecom_refund (
  out_refund_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退款单号（与现有 refund_application.refund_no 相同，退款幂等键）',
  out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务支付单号（ecom_order_seller.out_trade_no）',
  sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  refund_fen bigint NOT NULL,
  total_fen bigint NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'RETURNING/RETURN_FAILED/SUBMITTING/SUBMITTED/SUCCESS/CLOSED/ABNORMAL',
  refund_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '微信退款单号',
  fail_reason varchar(64) CHARACTER SET ascii NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (out_refund_no),
  KEY idx_ecom_refund_trade (out_trade_no),
  KEY idx_ecom_refund_state (state),
  CONSTRAINT ck_ecom_refund_amount CHECK (refund_fen > 0 AND total_fen > 0 AND refund_fen <= total_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='以二级商户为卖家的支付单的退款（先回退分账再退款）';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema=DATABASE() AND table_name='ecom_refund' AND table_type='BASE TABLE')=1
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='ecom_refund'
          AND column_name IN ('out_refund_no','out_trade_no','sub_mchid','transaction_id','refund_fen','total_fen',
                              'state','refund_id','fail_reason','created_at','updated_at'))=11;

-- 定时任务（暂停状态写入，可重复执行）。sys_job 是 RuoYi 基表，不是受管迁移依赖。
INSERT INTO sys_job(job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent,
                    status, create_by, create_time, remark)
SELECT '电商收付通分账提交与重试', 'DEFAULT', 'ecommercePayoutTask.shareAndRetry()', '0 0/10 * * * ?',
       '3', '1', '1', 'migration', NOW(),
       '开关 payout.ecommerce.profit-sharing-enabled 打开后才动钱：补齐已结算主题的分账计划、提交到期计划、查询处理中分账与回退'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'ecommercePayoutTask.shareAndRetry()');

INSERT INTO sys_job(job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent,
                    status, create_by, create_time, remark)
SELECT '电商收付通进件状态同步', 'DEFAULT', 'ecommercePayoutTask.syncOnboarding()', '0 15/30 * * * ?',
       '3', '1', '1', 'migration', NOW(),
       '开关 payout.ecommerce.enabled 打开后才查询微信：刷新审核中 / 待签约 / 待验证的二级商户进件状态'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'ecommercePayoutTask.syncOnboarding()');

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 AND SUM(status = '1' AND concurrent = '1' AND misfire_policy = '3') = 2
FROM sys_job WHERE invoke_target IN ('ecommercePayoutTask.shareAndRetry()', 'ecommercePayoutTask.syncOnboarding()');

-- 后台菜单：资金售后 → 电商收付通（进件审核 / 分账记录 / 回退与退款 / 失败重试同一页四个标签）。
-- 父菜单不唯一时不写入，回读断言随即失败（fail-closed）。故意不写 sys_role_menu：授予 0 个角色。
SET @ecomPayoutParentCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @ecomPayoutParent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @ecomPayoutExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:ecommercepayout:query');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '电商收付通', @ecomPayoutParent, 70, 'ecommerce-payout', 'business/finance/ecommerce-payout/index', '', 'EcommercePayout',
       1, 0, 'C', '0', '0', 'business:ecommercepayout:query', 'money', 'migration', NOW(),
       'l-payout2：二级商户进件审核、分账记录、回退与退款、失败重试'
FROM dual
WHERE @ecomPayoutParentCount = 1 AND @ecomPayoutExisting = 0;

SET @ecomPayoutMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:ecommercepayout:query' AND menu_type = 'C');
SET @ecomPayoutEditExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:ecommercepayout:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '电商收付通操作', @ecomPayoutMenu, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:ecommercepayout:edit', '#', 'migration', NOW(),
       'l-payout2：登记 / 提交进件、刷新状态、重新计划分账、重试分账回退'
FROM dual
WHERE @ecomPayoutMenu IS NOT NULL AND @ecomPayoutEditExisting = 0;

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:ecommercepayout:query' AND menu_type = 'C'
      AND component = 'business/finance/ecommerce-payout/index' AND @ecomPayoutParentCount = 1) = 1
  AND (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:ecommercepayout:edit' AND menu_type = 'F') = 1
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms IN ('business:ecommercepayout:query', 'business:ecommercepayout:edit')) = 0
) AS ecommerce_payout_menu_ready;
