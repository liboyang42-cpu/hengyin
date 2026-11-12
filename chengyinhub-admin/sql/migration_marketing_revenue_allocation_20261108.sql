-- 创作者/推荐人收入分配：版本化规则 + 原单分配快照 + 分配/冲销分录 + 待核事实队列（M5）。
-- Source-only ordered migration. 不执行生产 DDL；须走 chengyin-db-migrate 只读预检并由 root 决定落库时点。
--
-- 设计收口（root 修复要求，2026-11-08 第二轮）：
--  1) 适用范围编码进 rule_key（topic:<id>:s<N> / activity:<id>:s<N> / global:s<N>），
--     解析是确定性回退链，不建 scope 表。
--  2) 主应计唯一键 (order_type, order_id)，**不含 rule_version**：改规则/退役/换来源都不能二次应计，
--     旧重试读到原 snapshot 即返回，不解析当前 ACTIVE 规则。
--  3) 不建 FK 到任何既有资金表；不改 settlement_rule / settlement_detail / merchant_payable / cms_registration。
--  4) 无钱包/余额/打款状态列。本域只产出应收应付事实。
--  5) 不预置任何费率（50/75/20 竞品样本不入库）。
--  6) 【新增】金额一律同时存分（*_fen）作为精确口径：退款分笔冲销不能因逐笔取整丢分。
--  7) 【新增】反向分录带 event_ref（refund:<refundId>）+ 该事件的金额/币种/原单，用于"同 ID 异 payload"拒绝
--     与"全退后重放返回同回执而不是撞累计上限"。
--  8) 【新增】contract_ref + payee_member_id：作者必须是**受审交易合同声明的收款权利人**，
--     不能把活动/主题 owner 直接当授权收款人；两者为空的规则不得 ACTIVE。
--  9) 【新增】marketing_revenue_posting_task：**可靠投递队列**（不是账本，不含金额/余额/钱包列）。
--     调用方在原事务入队（与支付/退款事实同提交），原事实 commit 后由 consumer 在独立事务消费；
--     失败更新 status/attempts/reason_code 并可重试。这样既不读未提交事实、也不静默丢弃。
--
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_revenue_rule','marketing_revenue_allocation',
      'marketing_revenue_allocation_line','marketing_revenue_posting_task'))=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name IN ('uk_revenue_rule_key_version','uk_revenue_rule_single_active',
      'uk_revenue_allocation_order','uk_revenue_line_entry','uk_revenue_line_event',
      'uk_revenue_posting_ref'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_revenue_preflight','$')) AS UNSIGNED) AS assertion_ok;

-- 版本化分配规则。draft 可改；publish 后冻结，改规则须新建版本。
-- author_base_rate + platform_base_rate = 1；referral_rate 由 referral_funding_side 指明从哪一方份额里出。
-- contract_ref + payee_member_id 是"已启用模式"的必要条件：没有受审合同来源与合同收款人，
-- 规则只能停在 DRAFT，不能给任何真实订单授钱。
CREATE TABLE IF NOT EXISTS marketing_revenue_rule (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_key VARCHAR(96) NOT NULL COMMENT '稳定逻辑键，含适用域与场景：topic:<id>:s<N> / activity:<id>:s<N> / global:s<N>',
    version INT NOT NULL COMMENT '版本号，同 rule_key 从 1 递增；发布后不可改',
    scenario TINYINT NOT NULL COMMENT '1=平台代售作者内容 2=作者自带码售自己内容 3=推荐他人内容',
    currency CHAR(3) NOT NULL COMMENT 'ISO-4217；必须与已验证支付事实的币种一致',
    author_base_rate DECIMAL(9,8) NOT NULL COMMENT '内容净额中作者基础份额',
    platform_base_rate DECIMAL(9,8) NOT NULL COMMENT '内容净额中平台基础份额',
    referral_rate DECIMAL(9,8) NOT NULL DEFAULT 0 COMMENT '推荐佣金率，出资侧见 referral_funding_side',
    referral_funding_side TINYINT NOT NULL DEFAULT 0 COMMENT '0=无推荐 1=从平台份额出 2=从作者份额出',
    contract_ref VARCHAR(64) NULL COMMENT '受审交易合同/协议标识；ACTIVE 必填，DRAFT 可空',
    payee_member_id BIGINT NULL COMMENT '合同声明的作者收款权利人；ACTIVE 必填。不得默认取内容 owner',
    hold_days INT NOT NULL DEFAULT 14 COMMENT '应计到可结算的等待天数',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=DRAFT 1=ACTIVE 2=RETIRED',
    effective_from DATETIME NULL,
    effective_to DATETIME NULL,
    note VARCHAR(255) NULL,
    create_by BIGINT NULL COMMENT '后台操作者 sys_user id',
    create_time DATETIME NOT NULL,
    publish_time DATETIME NULL,
    retire_time DATETIME NULL,
    active_key VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN rule_key ELSE NULL END) STORED
        COMMENT '仅 ACTIVE 有值；唯一键保证同一 rule_key 同时只有一个生效版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_revenue_rule_key_version (rule_key, version),
    UNIQUE KEY uk_revenue_rule_single_active (active_key),
    KEY idx_revenue_rule_status (status, scenario, currency),
    CONSTRAINT ck_revenue_rule_scenario CHECK (scenario IN (1,2,3)),
    CONSTRAINT ck_revenue_rule_status CHECK (status IN (0,1,2)),
    CONSTRAINT ck_revenue_rule_currency CHECK (currency >= 'AAA' AND currency <= 'ZZZ'),
    CONSTRAINT ck_revenue_rule_author_rate CHECK (author_base_rate >= 0 AND author_base_rate <= 1),
    CONSTRAINT ck_revenue_rule_platform_rate CHECK (platform_base_rate >= 0 AND platform_base_rate <= 1),
    CONSTRAINT ck_revenue_rule_referral_rate CHECK (referral_rate >= 0 AND referral_rate <= 1),
    CONSTRAINT ck_revenue_rule_funding_side CHECK (referral_funding_side IN (0,1,2)),
    CONSTRAINT ck_revenue_rule_hold_days CHECK (hold_days >= 0 AND hold_days <= 365),
    CONSTRAINT ck_revenue_rule_funding_presence CHECK (
        (referral_funding_side = 0 AND referral_rate = 0)
        OR (referral_funding_side IN (1,2) AND referral_rate > 0)),
    CONSTRAINT ck_revenue_rule_active_needs_contract CHECK (
        status <> 1 OR (contract_ref IS NOT NULL AND payee_member_id IS NOT NULL AND payee_member_id > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创作者/推荐分配规则（版本化，发布后冻结，不预置费率，ACTIVE 需受审合同与收款人）';

-- 原单分配快照：一原单一行，insert-only 主账。
-- paid_fen 与 payment_fact_hash 把"折后实付"钉死在**已验证支付事实**上，
-- 不再用 payment_status + total/original 回退猜金额。
CREATE TABLE IF NOT EXISTS marketing_revenue_allocation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_type TINYINT NOT NULL COMMENT '1=cms_registration；与既有账本 source_type 对应',
    order_id BIGINT NOT NULL COMMENT 'cms_registration.id，即既有 settlement_detail.source_id',
    allocation_key VARCHAR(96) NOT NULL COMMENT '业务键快照，如 registration:555；不含规则版本',
    rule_id BIGINT UNSIGNED NOT NULL,
    rule_key VARCHAR(96) NOT NULL,
    rule_version INT NOT NULL COMMENT '应计时冻结的规则版本，仅供追溯',
    scenario TINYINT NOT NULL,
    attribution_id BIGINT NULL COMMENT 'M1 marketing_attribution.id；无来源事实时 NULL',
    sharer_member_id BIGINT NULL COMMENT '来源事实冗余（服务端读取，不信客户端传参）',
    campaign_key VARCHAR(40) NULL,
    author_member_id BIGINT NOT NULL COMMENT '合同声明的作者收款人（rule.payee_member_id），不是内容 owner',
    contract_ref VARCHAR(64) NOT NULL COMMENT '应计时冻结的受审合同标识',
    owner_type TINYINT NOT NULL COMMENT '订单 owner_type：1=主题 2=活动（仅内容关联，非收款人）',
    owner_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    paid_fen BIGINT UNSIGNED NOT NULL COMMENT '已验证支付事实的实付金额（分），精确口径',
    payment_fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '授权本次应计的 wx_verified_payment_fact 摘要',
    original_amount_fen BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '原价快照（分），不参与扣减',
    discount_fen BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已发生折扣快照（分），禁止二次扣减',
    point_deduct_fen BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '积分抵扣快照（分），现行账本不计入 gross',
    tax_fen BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '税费（分），须有核算来源证据',
    payment_fee_fen BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '支付手续费（分），须有核算来源证据',
    channel_commission_fen BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '渠道佣金（分），须有合同证据',
    fee_basis_ref VARCHAR(96) NOT NULL COMMENT '三项扣费的证据引用；无证据不得应计（不得默认 0）',
    net_fen BIGINT UNSIGNED NOT NULL COMMENT '实付 − 税费 − 支付手续费 − 渠道佣金（分）',
    referral_funding_side TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=ACCRUED 2=SETTLEABLE 3=PAID 4=PARTIALLY_REVERSED 5=REVERSED',
    settleable_at DATETIME NULL COMMENT '应计 + hold_days',
    reversed_fen BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计已冲销（分），精确口径',
    snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '原单+来源+规则冻结+净额分项+实付的规范摘要，用于同单异 payload 冲突检测',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_revenue_allocation_order (order_type, order_id),
    UNIQUE KEY uk_revenue_allocation_key (allocation_key),
    KEY idx_revenue_allocation_author (author_member_id, create_time),
    KEY idx_revenue_allocation_status (status, settleable_at),
    CONSTRAINT fk_revenue_allocation_rule FOREIGN KEY (rule_id)
        REFERENCES marketing_revenue_rule (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_revenue_allocation_status CHECK (status IN (1,2,3,4,5)),
    CONSTRAINT ck_revenue_allocation_scenario CHECK (scenario IN (1,2,3)),
    CONSTRAINT ck_revenue_allocation_currency CHECK (currency >= 'AAA' AND currency <= 'ZZZ'),
    CONSTRAINT ck_revenue_allocation_net CHECK (net_fen <= paid_fen),
    CONSTRAINT ck_revenue_allocation_deductions CHECK (
        tax_fen + payment_fee_fen + channel_commission_fen <= paid_fen),
    CONSTRAINT ck_revenue_allocation_reversed CHECK (reversed_fen <= net_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原单收入分配快照：金额以分为精确口径，应计/可结算/已打款/冲销分开，不含钱包';

-- 分配与冲销分录。entry_type=1 应计；entry_type=2 退款/chargeback 反向。
-- 反向分录带 event_ref，让"同一退款事件重放"能先命中幂等事实、再谈累计封顶。
CREATE TABLE IF NOT EXISTS marketing_revenue_allocation_line (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    allocation_id BIGINT UNSIGNED NOT NULL,
    entry_type TINYINT NOT NULL COMMENT '1=ACCRUE 2=REVERSE',
    line_role VARCHAR(16) NOT NULL COMMENT 'author / referrer / platform',
    recipient_member_id BIGINT NULL COMMENT '平台行为 NULL',
    funding_side TINYINT NOT NULL DEFAULT 0 COMMENT '推荐佣金出资侧 0=不适用 1=平台 2=作者',
    basis_fen BIGINT UNSIGNED NOT NULL COMMENT '计算基数（净额，分）',
    rate DECIMAL(9,8) NOT NULL DEFAULT 0,
    amount_fen BIGINT NOT NULL COMMENT '正向为正、冲销为负，整数分，无取整损失',
    currency CHAR(3) NOT NULL,
    entry_key VARCHAR(160) NOT NULL COMMENT 'accrue:<role> / reverse:<eventRef>:<role>',
    event_ref VARCHAR(96) NULL COMMENT '退款/chargeback 事件标识 refund:<refundId>；应计行为 NULL',
    event_amount_fen BIGINT UNSIGNED NULL COMMENT '该事件的退款金额（分），用于同 ID 异金额拒绝',
    event_currency CHAR(3) NULL COMMENT '该事件的币种，用于同 ID 异币种拒绝',
    event_order_type TINYINT NULL,
    event_order_id BIGINT NULL COMMENT '该事件指向的原单，用于同 ID 异原单拒绝',
    reversal_of_line_id BIGINT UNSIGNED NULL COMMENT '冲销指向的原分录',
    source_fact VARCHAR(96) NULL COMMENT '触发事实，如 refund:123 / payment-fact:<hash>',
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_revenue_line_entry (allocation_id, entry_key),
    UNIQUE KEY uk_revenue_line_event (allocation_id, event_ref, line_role),
    KEY idx_revenue_line_recipient (recipient_member_id, create_time),
    KEY idx_revenue_line_reversal (reversal_of_line_id),
    CONSTRAINT fk_revenue_line_allocation FOREIGN KEY (allocation_id)
        REFERENCES marketing_revenue_allocation (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_revenue_line_reversal FOREIGN KEY (reversal_of_line_id)
        REFERENCES marketing_revenue_allocation_line (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_revenue_line_entry_type CHECK (entry_type IN (1,2)),
    CONSTRAINT ck_revenue_line_role CHECK (line_role IN ('author','referrer','platform')),
    CONSTRAINT ck_revenue_line_funding_side CHECK (funding_side IN (0,1,2)),
    CONSTRAINT ck_revenue_line_currency CHECK (currency >= 'AAA' AND currency <= 'ZZZ'),
    CONSTRAINT ck_revenue_line_sign CHECK (
        (entry_type = 1 AND amount_fen >= 0) OR (entry_type = 2 AND amount_fen <= 0)),
    CONSTRAINT ck_revenue_line_event_presence CHECK (
        (entry_type = 1 AND event_ref IS NULL AND event_amount_fen IS NULL
            AND reversal_of_line_id IS NULL)
        OR (entry_type = 2 AND event_ref IS NOT NULL AND event_amount_fen IS NOT NULL
            AND event_currency IS NOT NULL AND event_order_type IS NOT NULL
            AND event_order_id IS NOT NULL AND reversal_of_line_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分配与退款冲销分录；金额整数分；退款按原分录反向并带事件身份';

-- 收入分配投递任务：**可靠队列**，不是账本，也不含余额/钱包列。
--
-- 为什么不是"直接新事务读同单"：支付回调/退款审批调用本域时，它们的原支付/退款事实
-- 还没提交。另起事务去读同一张单会读到不可见数据，还可能和调用方持有的行锁互相等待。
-- 因此接线方式是：
--   1) 调用方在**原事务**里 enqueue（任务行与事实同事务提交，回滚则一起回滚）；
--   2) 原事实 commit 之后，consumer 在独立事务里 claim 并执行应计/冲销；
--   3) 失败更新本行 status/attempts/reason_code + next_attempt_at，可重试、可审计、可补录。
CREATE TABLE IF NOT EXISTS marketing_revenue_posting_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_kind TINYINT NOT NULL COMMENT '1=ACCRUE(按报名单) 2=REVERSE(按退款单)',
    ref_id BIGINT NOT NULL COMMENT 'ACCRUE→registrationId；REVERSE→refundId',
    order_id BIGINT NOT NULL COMMENT '归属报名单，便于按单查全部任务',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=DONE 2=FAILED(待重试) 3=CLAIMED(租约中)',
    attempts INT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(64) NULL COMMENT '领取者标识；进程崩溃后由租约过期回收',
    lease_expires_at DATETIME NULL COMMENT '租约到期时间；过期即视为领取失败可被回收',
    reason_code VARCHAR(48) NULL COMMENT '最近一次失败原因码，如 RULE_NOT_FOUND / REFUND_FACT_MISSING',
    detail VARCHAR(255) NULL COMMENT '脱敏说明，供对账定位',
    next_attempt_at DATETIME NOT NULL COMMENT '下次可尝试时间(退避)',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_revenue_posting_ref (task_kind, ref_id),
    KEY idx_revenue_posting_due (status, next_attempt_at),
    KEY idx_revenue_posting_lease (status, lease_expires_at),
    KEY idx_revenue_posting_order (order_id),
    CONSTRAINT ck_revenue_posting_kind CHECK (task_kind IN (1,2)),
    CONSTRAINT ck_revenue_posting_status CHECK (status IN (0,1,2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收入分配投递可靠队列：原事务入队、提交后消费、失败可重试可审计';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_revenue_rule','marketing_revenue_allocation',
      'marketing_revenue_allocation_line','marketing_revenue_posting_task')
    AND UPPER(engine)='INNODB')=4
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND index_name IN ('uk_revenue_rule_key_version','uk_revenue_rule_single_active',
      'uk_revenue_allocation_order','uk_revenue_line_entry','uk_revenue_line_event',
      'uk_revenue_posting_ref')
    AND non_unique=0)>=6
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_revenue_allocation'
    AND column_name IN ('paid_fen','payment_fact_hash','tax_fen','payment_fee_fen',
      'channel_commission_fen','fee_basis_ref','net_fen','reversed_fen','contract_ref'))=9
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_revenue_allocation_line'
    AND column_name IN ('amount_fen','event_ref','event_amount_fen','event_currency',
      'event_order_type','event_order_id','reversal_of_line_id'))=7
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_revenue_rule'
    AND column_name IN ('contract_ref','payee_member_id'))=2
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_revenue_allocation','marketing_revenue_allocation_line',
      'marketing_revenue_posting_task')
    AND column_name IN ('wallet_id','balance_after','withdraw_id','available_balance'))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_revenue_posting_task'
    AND column_name IN ('lease_owner','lease_expires_at'))=2
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name='ck_revenue_rule_active_needs_contract')=1
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name='ck_revenue_line_event_presence')=1,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_revenue_readback','$')) AS UNSIGNED) AS assertion_ok;
