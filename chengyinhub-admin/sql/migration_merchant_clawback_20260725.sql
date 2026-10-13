-- P1-6 M块:商家违规倒扣(2026-07-25 拍板)
--
-- 【背景】CoopComplaintMapper 建单目前只挡 CmsTopicServiceImpl.payoutDueCoopSettlements
--   的打款前放款(未结算的钱不放),打款后完全没有倒扣路径 —— 钱已经离开平台就再没人管。
--   本迁移建 merchant_clawback_application 表承接「已打款结算款」的事后人工追回。
--
-- 【业务规则(已拍板,不是本迁移可讨论范围)】
--   1) 默认路径 OFFSET_BATCH:商家有未来未打款批次(merchant_payable_batch 的
--      PENDING/CONFIRMED,取 period_ym 最小的一个)时,从其 amount_total 里直接扣减,
--      不碰余额。扣减后金额可能变负(留痕说明扣了什么),不做同步防负校验。
--   2) 兜底路径 BALANCE:商家没有任何未来可冲抵批次时(已停止合作/全部已 PAID),
--      直接扣 ums_member.balance(允许扣成负数,复用 MerchantReferralBountyService
--      clawbackBalance 的 CAS 范式)。
--   3) 必须人工介入:倒扣金额由运营人工核实投诉后手动填写,不是系统自动计算;走
--      「发起 → 复核确认」两步,复核人须≠发起人(maker-checker,与
--      MerchantPayableServiceImpl.markBatchPaid 同型)。
--   4) 独立权限点,不与日常投诉/结算处理权限捆绑(见 migration_merchant_clawback_menu_20260725.sql)。
--
-- 【类别】① schema 迁移(DDL,新建表,不影响任何存量表)。
-- 【部署顺序】必须先于引用这张表的 jar 落库(否则 insert/select 报 1146)。
-- 【幂等】CREATE TABLE IF NOT EXISTS,可重复执行。
-- 【可逆】DROP TABLE merchant_clawback_application;
-- 【风险】低。新建表,不改任何已有表结构。
--
-- 生产执行前只读预检:
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_clawback_application';   -- 期望 0
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_payable_batch';           -- 期望 1(依赖表已存在)

CREATE TABLE IF NOT EXISTS merchant_clawback_application (
  id                 BIGINT        PRIMARY KEY AUTO_INCREMENT,
  merchant_member_id BIGINT        NOT NULL COMMENT '被追回的商家 ums_member.id',
  reason             VARCHAR(1000) NOT NULL COMMENT '违规/投诉依据说明(运营人工填写)',
  amount             DECIMAL(10,2) NOT NULL COMMENT '倒扣金额(正数,运营人工核实后填写,不由系统计算)',
  settle_path        VARCHAR(20)   NOT NULL COMMENT 'OFFSET_BATCH=冲抵未来批次(默认) / BALANCE=直接扣余额(兜底,仅无未来批次时)。发起时系统自动判定',
  target_batch_id    BIGINT        NULL COMMENT 'settle_path=OFFSET_BATCH 时的目标 merchant_payable_batch.id;BALANCE 路径为 NULL',
  status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW=待复核 / EXECUTED=已执行 / REJECTED=已驳回',
  initiated_by       VARCHAR(64)   NOT NULL COMMENT '发起人 sys_user.user_name',
  confirmed_by       VARCHAR(64)   NULL COMMENT '复核人 sys_user.user_name(执行/驳回时回填)。必须 ≠ initiated_by',
  confirmed_at       DATETIME      NULL COMMENT '复核(执行或驳回)时刻',
  executed_at        DATETIME      NULL COMMENT '实际完成扣款/冲抵的时刻',
  reject_reason      VARCHAR(500)  NULL COMMENT '驳回原因(复核人填写)',
  create_time        DATETIME      NOT NULL COMMENT '发起时刻',
  KEY idx_merchant (merchant_member_id, create_time),
  KEY idx_status (status),
  KEY idx_target_batch (target_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M块:商家违规倒扣申请(已打款结算款的事后人工追回,发起-复核两步)';

-- 回读断言(回执 ≠ 观测,必须看到 1 行)
SELECT COUNT(*) AS 表已建 FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_clawback_application';
SELECT COUNT(*) AS 现有申请数 FROM merchant_clawback_application;
