-- 探店日第3批 A1(Q)/A2(EXTRA_HOURS) 的开售冻结条款、预审批、质量决定，
-- 以及消费它们的 FINAL / 俱乐部应付 / 批次三张表。
-- 文件名保留 funds 字样：ci/funds_it_gate.py 的 REQUIRED_TRIGGER_PATHS 认
-- chengyinhub-admin/sql/migration_*funds*.sql，改名即资金复验不触发。
--
-- ⚠️ club_edition_hours 不加任何列：四类枚举、状态枚举、非负 CHECK、CONFIRMED 三字段齐备 CHECK、
--    uk_club_edition_hour_kind 在 migration_20260815_tandianri_batch3_funds_core.sql 里已经建好，
--    A1/A2 复用它们，不重复建模、不改既有 migration。
-- 前置依赖 migration_web_admin_roles_20260719.sql：本文件末尾的 `-- runner: assert=scalar-true`
--   要回读 cy_finance / cy_operator 两个角色的授权行，而这两条 sys_role 行由那份迁移创建。
--   链路 funds_core → club → rbac_role 只建 RBAC 表、不建这两条角色行 ⇒ 少这条边就会
--   「表已建好、末尾断言再红」= 部分落库 + 门禁红。

CREATE TABLE IF NOT EXISTS club_edition_compensation_terms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    executing_club_id BIGINT NOT NULL,
    fixed_compensation DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    extra_hour_rate DECIMAL(10,2) NOT NULL,
    quality_dimension_amount DECIMAL(10,2) NOT NULL,
    quality_dimensions VARCHAR(255) NOT NULL,
    evidence_due_at DATETIME(3) NOT NULL,
    fulfillment_end_at DATETIME(3) NOT NULL COMMENT '本期履约窗结束的权威瞬间；EXTRA 上界 + FINAL「期已结束」事实门',
    extra_approver_user_id BIGINT NOT NULL COMMENT '总纲 §2.1.2 要求冻结的「审批人」；取当期成本版本上显式登记的项目负责人 topic_edition_cost.extra_approver_user_id（开售时已校验它按得动预批按钮）',
    config_hash CHAR(64) NOT NULL,
    frozen_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_edition_terms_topic (topic_id),
    CONSTRAINT ck_club_edition_terms_amounts CHECK
        (fixed_compensation >= 0 AND extra_hour_rate > 0 AND quality_dimension_amount > 0),
    -- 证据截止必须晚于履约窗结束（T+7），两个冻结事实互为体检。
    CONSTRAINT ck_club_edition_terms_window CHECK (evidence_due_at > fulfillment_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开售冻结的补偿条款；FINAL 只读它，禁止用最新配置覆盖';

-- ★项目负责人（EXTRA 事前审批人）落在<b>开售前就能签、且已经在冻结的那张表</b>上：当期成本版本。
--   上一版取 ProductRole.declaredBy —— 那个字段自己的 COMMENT 写明「声明该角色槽的后台操作账号；
--   用于冻结 actor 变更审计，<b>不参与主体归属判断</b>」，产生面只要 business:topic:content-slot:edit，
--   而且 declareRequiredOrganizerSlot 幂等重试保留的是<b>最初</b>那个声明人 —— 与「本期项目负责人」
--   没有语义关系。现在改成显式登记，开售时冻进 terms 与 configHash，开售后改成本行也改不动它。
--   ⚠️ topic_edition_cost 建在 migration_20260815_..._funds_core.sql，那份不改（既有迁移只读）；
--      这里用 information_schema 预检 + PREPARE 加列，与本文件其余 ALTER 同一形状，可重复执行。
SET @cost_extra_approver_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'topic_edition_cost'
      AND column_name = 'extra_approver_user_id'
);
SET @cost_extra_approver_sql := IF(
    @cost_extra_approver_exists = 0,
    'ALTER TABLE topic_edition_cost ADD COLUMN extra_approver_user_id BIGINT NULL COMMENT ''本期项目负责人 sys_user.user_id；EXTRA 事前审批人，开售时冻进 club_edition_compensation_terms 与 configHash''',
    'SELECT 1'
);
PREPARE cost_extra_approver_stmt FROM @cost_extra_approver_sql;
EXECUTE cost_extra_approver_stmt;
DEALLOCATE PREPARE cost_extra_approver_stmt;

CREATE TABLE IF NOT EXISTS club_extra_hours_approval (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    category VARCHAR(16) NOT NULL,
    approved_hours DECIMAL(10,2) NOT NULL,
    approved_by BIGINT NOT NULL,
    approved_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    work_started_at DATETIME(3) NOT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    correction_of BIGINT NULL,
    note VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_extra_idem (topic_id, club_id, idempotency_key),
    KEY idx_club_extra_lookup (topic_id, club_id, category),
    CONSTRAINT ck_club_extra_category CHECK (category IN ('PREP', 'CONTENT', 'ONSITE', 'REVIEW')),
    CONSTRAINT ck_club_extra_hours CHECK (approved_hours >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='额外工时事前预审批；append-only，更正写新行指向原行';

-- ★一条审批只能被更正一次。两条兄弟 correction 各自都「没抬高原件」，
-- 合起来却把批准额度按条累加 ⇒ 超额付款（40 × 5 变成 40 × 10）。
-- correction_of 为 NULL 的普通审批行不受影响：MySQL 唯一索引里 NULL 可以重复。
-- 用 information_schema 预检 + PREPARE 而不是直接 ALTER：本文件在 IT 里会被连跑两遍，
-- 也可能落到已按旧版本建过表的库上，两种情况都必须是同一条幂等路径。
SET @extra_correction_uk_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'club_extra_hours_approval'
      AND index_name = 'uk_club_extra_correction_of'
);
SET @extra_correction_uk_sql := IF(
    @extra_correction_uk_exists = 0,
    'ALTER TABLE club_extra_hours_approval ADD UNIQUE KEY uk_club_extra_correction_of (correction_of)',
    'SELECT 1'
);
PREPARE extra_correction_uk_stmt FROM @extra_correction_uk_sql;
EXECUTE extra_correction_uk_stmt;
DEALLOCATE PREPARE extra_correction_uk_stmt;

CREATE TABLE IF NOT EXISTS club_quality_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    dimension VARCHAR(32) NOT NULL,
    decision_status VARCHAR(16) NOT NULL,
    amount DECIMAL(10,2) NULL,
    evidence_hash CHAR(64) NULL,
    excluded_by_fault TINYINT NOT NULL DEFAULT 0,
    -- ★归责方必须落库。本仓 LogAspect 已加固到不写请求体（setOperParam/setJsonResult 全仓 0 命中），
    --   「随 @Log 落 sys_oper_log」是假的；「哪一方的过错让这一维走了排除路径」正是排除动作
    --   唯一需要留痕的事实，丢了就无处可查。
    fault_party VARCHAR(16) NULL COMMENT '排除计酬的归责方：MERCHANT / PLATFORM；只在 excluded_by_fault=1 的行上有值',
    -- ★理由必须落列，与 fault_party / club_payable.reason 同一个理由：@Log 不写请求体。
    --   一次确认是不可逆动作（判不过 = 该维永久锁死 0 元），过错排除更重（能把一个已确认的 0
    --   抬成 200，一期最多 +600，方向是多付）—— 审计 2 §「权限、审计和失败语义」对审批类动作
    --   的最低集里「理由」是明写的。不落这一列，「是哪一笔事故、依据什么定的责」就只存在于
    --   操作者的记忆里。
    reason VARCHAR(500) NULL COMMENT '本次判定/排除的理由；一次确认与过错排除都必填，PENDING 种子行为空',
    due_at DATETIME(3) NOT NULL,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME(3) NULL,
    decision_version BIGINT NOT NULL DEFAULT 1,
    is_current TINYINT NOT NULL DEFAULT 1,
    correction_of BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_quality_version (topic_id, club_id, dimension, decision_version),
    CONSTRAINT ck_club_quality_dimension CHECK
        (dimension IN ('DELIVERY_SAFETY', 'PLAYER_EXPERIENCE', 'CONTENT_REPORT')),
    CONSTRAINT ck_club_quality_status CHECK (decision_status IN ('PENDING', 'CONFIRMED')),
    -- ★每维只能 0 或 200：中间值在库这一层就写不进来，不只靠 Java。
    CONSTRAINT ck_club_quality_amount CHECK (amount IS NULL OR amount = 0 OR amount = 200),
    -- 归责方只有这两种，且只能出现在被排除的行上；「排除了但没写是谁的过错」在库这一层就写不进来。
    -- ⚠️ `fault_party IS NOT NULL AND` 这半句是承重的，别当冗余删掉：CHECK 只在求值为 FALSE 时才拒，
    --    求值为 UNKNOWN 一律放行。少了它，excluded_by_fault=1 且 fault_party IS NULL 时
    --    左支是 (TRUE AND NULL)=NULL、右支是 FALSE，NULL OR FALSE = NULL ⇒ 这条约束把它【放行】，
    --    恰好放行的就是它声称要挡的那一种。2026-08-17 在真 MySQL 8.0.45 上实测写进去过。
    CONSTRAINT ck_club_quality_fault_party CHECK
        ((excluded_by_fault = 1 AND fault_party IS NOT NULL AND fault_party IN ('MERCHANT', 'PLATFORM'))
         OR (excluded_by_fault = 0 AND fault_party IS NULL)),
    CONSTRAINT ck_club_quality_confirmed CHECK
        ((decision_status = 'CONFIRMED' AND amount IS NOT NULL
          AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
         OR decision_status <> 'CONFIRMED'),
    -- ★已决的行必须带理由。Java 侧那道 isBlank 校验是给人话的，这一条是「写不进来」的那道。
    CONSTRAINT ck_club_quality_reason CHECK
        (decision_status <> 'CONFIRMED' OR (reason IS NOT NULL AND reason <> ''))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量奖励三维逐项决定；每维 0/200，一次确认，版本化更正';

-- 已按早先版本建过这张表的开发/测试库补列与补 CHECK（生产上这张表尚不存在，走上面的 CREATE）。
-- 与本文件其余 ALTER 同一形状：information_schema 预检 + PREPARE，可重复执行。
-- ⚠️ 不这么做的话，CREATE TABLE IF NOT EXISTS 会在这类库上静默跳过整段新列 ——
--    而回读断言只查 sys_menu/sys_role_menu，查不出来。
SET @quality_reason_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'club_quality_decision'
      AND column_name = 'reason'
);
SET @quality_reason_sql := IF(
    @quality_reason_exists = 0,
    'ALTER TABLE club_quality_decision ADD COLUMN reason VARCHAR(500) NULL COMMENT ''本次判定/排除的理由；一次确认与过错排除都必填'', ADD CONSTRAINT ck_club_quality_reason CHECK (decision_status <> ''CONFIRMED'' OR (reason IS NOT NULL AND reason <> ''''))',
    'SELECT 1'
);
PREPARE quality_reason_stmt FROM @quality_reason_sql;
EXECUTE quality_reason_stmt;
DEALLOCATE PREPARE quality_reason_stmt;

-- ★写前 fail-closed guard:加/换约束【之前】先盘点存量违规行。
--   为什么必须在写之前问:旧定义对「排除了但没写归责方」求值为 UNKNOWN ⇒ 放行,所以这类行
--   在此刻【真的可能存在】——问题在这一刻还没有答案,断言才有判别力。
--   放到 ADD CONSTRAINT 之后问就是【恒真】:真有违规行时 ADD CONSTRAINT 会先 ERROR 3819 中止,
--   根本走不到那条 SELECT ⇒ 它每次都读到 0、每次都打印「已验证」,却什么都没验证。
--   恒真断言在本仓比没有断言更坏。2026-08-18 R14 改写,原形状见 git 历史。
--   附带收益:存量违规不再以一句通用的 3819 收场,而是当场点名是这道 guard 拦的。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0 FROM club_quality_decision
 WHERE (excluded_by_fault = 1 AND fault_party IS NULL)
    OR (excluded_by_fault = 0 AND fault_party IS NOT NULL);

-- 已按旧定义建过 ck_club_quality_fault_party 的库必须原地换成 NULL 安全版本。
-- 旧定义对「排除了但没写归责方」求值为 UNKNOWN ⇒ 放行；只补新库不修存量库，
-- 等于让已经存在的开发/测试库继续留着一条不设防的防线。归一后比对，替换是幂等的。
SET @quality_fault_party_is_null_safe := (
    SELECT COUNT(*) FROM information_schema.check_constraints
    WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_quality_fault_party'
      AND UPPER(check_clause) LIKE '%FAULT_PARTY%IS NOT NULL%'
);
SET @quality_fault_party_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'club_quality_decision'
      AND constraint_name = 'ck_club_quality_fault_party' AND constraint_type = 'CHECK'
);
SET @quality_fault_party_sql := IF(
    @quality_fault_party_exists = 1 AND @quality_fault_party_is_null_safe = 0,
    'ALTER TABLE club_quality_decision DROP CHECK ck_club_quality_fault_party, ADD CONSTRAINT ck_club_quality_fault_party CHECK ((excluded_by_fault = 1 AND fault_party IS NOT NULL AND fault_party IN (''MERCHANT'', ''PLATFORM'')) OR (excluded_by_fault = 0 AND fault_party IS NULL))',
    'SELECT 1'
);
PREPARE quality_fault_party_stmt FROM @quality_fault_party_sql;
EXECUTE quality_fault_party_stmt;
DEALLOCATE PREPARE quality_fault_party_stmt;

-- 写后回读:NULL 安全版本真的落地了吗 —— 判据是归一后【逐字符相等】,不是关键词模糊匹配。
--   ⚠️ 这里不能再问「有没有违规行」:在这个位置那是恒真的(见上面写前 guard 的说明)。
--   目标串由 2026-08-18 在真 MySQL 8.0.45 上 dump 出的打印形态归一得到,不是推的。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.check_constraints
 WHERE constraint_schema = DATABASE()
   AND constraint_name = 'ck_club_quality_fault_party'
   AND REGEXP_REPLACE(LOWER(check_clause),
           '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
       <=> 'excluded_by_fault=1andfault_partyisnotnullandfault_partyin''merchant'',''platform''orexcluded_by_fault=0andfault_partyisnull';

-- 每个 (topic, club, dimension) 最多一个 current 版本：生成列对 current 行取常量、历史行取 NULL。
SET @quality_current_guard_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'club_quality_decision'
      AND column_name = 'current_guard'
);
SET @quality_current_guard_sql := IF(
    @quality_current_guard_exists = 0,
    'ALTER TABLE club_quality_decision ADD COLUMN current_guard TINYINT GENERATED ALWAYS AS (CASE WHEN is_current = 1 THEN 1 ELSE NULL END) STORED, ADD UNIQUE KEY uk_club_quality_single_current (topic_id, club_id, dimension, current_guard)',
    'SELECT 1'
);
PREPARE quality_current_guard_stmt FROM @quality_current_guard_sql;
EXECUTE quality_current_guard_stmt;
DEALLOCATE PREPARE quality_current_guard_stmt;

CREATE TABLE IF NOT EXISTS topic_edition_finalization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    final_status VARCHAR(16) NOT NULL,
    fixed_compensation DECIMAL(14,2) NOT NULL,
    quality_amount DECIMAL(14,2) NOT NULL,
    extra_amount DECIMAL(14,2) NOT NULL,
    payee_profile_id BIGINT NOT NULL,
    payee_profile_version BIGINT NOT NULL,
    payee_facts_hash CHAR(64) NOT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    finalized_by BIGINT NOT NULL,
    finalized_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_edition_finalization (topic_id),
    UNIQUE KEY uk_topic_edition_finalization_idem (idempotency_key),
    CONSTRAINT ck_topic_edition_final_status CHECK (final_status IN ('FINAL')),
    CONSTRAINT ck_topic_edition_final_amounts CHECK
        (fixed_compensation >= 0 AND quality_amount >= 0 AND extra_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店日期级 FINAL 结算头；一期一行，重放读回同一行';

CREATE TABLE IF NOT EXISTS club_payable (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    component VARCHAR(16) NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    batch_id BIGINT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    reversal_of BIGINT NULL,
    -- ★理由落库，理由同 club_quality_decision.fault_party：@Log 不写请求体。
    --   审计 2「成本录入/更正 … 必要审计：类别、金额、说明」——一条无因由的负向资金行不满足它。
    reason VARCHAR(500) NULL COMMENT '红冲 / 重计提的理由；FINAL 首次计提为空',
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 幂等键按 (topic, club, component, key)：FINAL 重放算同一笔；红冲写新键的反向行；
    -- 红冲后的重计提写 FINAL:{topicId}#R{已有行数}，重放同一次重计提照样撞这把键。
    UNIQUE KEY uk_club_payable_idem (topic_id, club_id, component, idempotency_key),
    -- 只有反向行/重计提行需要理由；两者都不是 FINAL 首次计提。
    CONSTRAINT ck_club_payable_reason CHECK (entry_type = 'ACCRUAL' OR reason IS NOT NULL),
    KEY idx_club_payable_batch (batch_id),
    CONSTRAINT ck_club_payable_component CHECK (component IN ('FIXED', 'QUALITY', 'EXTRA')),
    CONSTRAINT ck_club_payable_entry CHECK (entry_type IN ('ACCRUAL', 'REVERSAL')),
    -- 只追加：正向必须为正、红冲必须为负，历史金额永远不被 UPDATE 改写。
    CONSTRAINT ck_club_payable_amount CHECK
        ((entry_type = 'ACCRUAL' AND amount >= 0) OR (entry_type = 'REVERSAL' AND amount <= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部应付台账；按 component 分笔、只追加';

-- ★一期一批次一张发票（裁决表 §7.1 E4 / 审计 2 P0-9「唯一 (club_id, topic_id)」）。
--   不照抄 merchant_payable 的自然月：合同按期签、F 按期冻、Q 按期考核、票源按期归因，
--   所有输入都是按期的。缺 topic_id 这一列，两期两份合同的钱会并进同一张付款单/同一张发票，
--   而批次自己无法自报它属于哪一期。
-- ★状态轴与发票轴是两条独立的轴（照抄 merchant_payable_batch 的范式）：
--   batch_status 管钱走没走，invoice_status 管票开没开。混成一条就表达不了「已打款但未开票」。
CREATE TABLE IF NOT EXISTS club_payable_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(64) NOT NULL,
    club_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    total_amount DECIMAL(14,2) NOT NULL,
    batch_status VARCHAR(16) NOT NULL,
    invoice_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    invoice_no VARCHAR(64) NULL,
    red_invoice_no VARCHAR(64) NULL,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME(3) NULL,
    paid_by BIGINT NULL,
    paid_at DATETIME(3) NULL,
    payment_reference VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_payable_batch_no (batch_no),
    UNIQUE KEY uk_club_payable_batch_edition (club_id, topic_id),
    CONSTRAINT ck_club_payable_batch_status CHECK (batch_status IN ('PENDING', 'CONFIRMED', 'PAID')),
    CONSTRAINT ck_club_payable_batch_invoice CHECK (invoice_status IN ('NONE', 'ISSUED', 'RED')),
    -- 发票号与发票状态必须自洽：ISSUED 必须有票号，RED 必须两个号都有。
    CONSTRAINT ck_club_payable_batch_invoice_no CHECK
        ((invoice_status = 'NONE' AND invoice_no IS NULL AND red_invoice_no IS NULL)
         OR (invoice_status = 'ISSUED' AND invoice_no IS NOT NULL AND red_invoice_no IS NULL)
         OR (invoice_status = 'RED' AND invoice_no IS NOT NULL AND red_invoice_no IS NOT NULL)),
    -- ★确认人与付款登记人分离：同一个人两头都签，在库这一层就写不进去。
    CONSTRAINT ck_club_payable_batch_separation CHECK
        (paid_by IS NULL OR confirmed_by IS NULL OR paid_by <> confirmed_by),
    CONSTRAINT ck_club_payable_batch_paid CHECK
        ((batch_status = 'PAID' AND paid_by IS NOT NULL AND paid_at IS NOT NULL
          AND payment_reference IS NOT NULL) OR batch_status <> 'PAID')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部应付付款批次；一期一批次一张发票，确认与付款登记分离，状态/发票双轴';

-- 权限种子：确认工时 / 批额外工时 / 定质量奖励 / FINAL / 批次确认 / 付款登记，六个动作六种权限。
SET @cy_comp_parent := (
    SELECT menu_id FROM sys_menu
    WHERE menu_type = 'C' AND (perms = 'business:topic:list' OR component = 'business/topic/index')
    ORDER BY CASE WHEN perms = 'business:topic:list' THEN 0 ELSE 1 END, menu_id
    LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT seed.menu_name, @cy_comp_parent, seed.order_num, '', '', 1, 0,
       'F', '0', '0', seed.perms, '#', 'admin', NOW(), seed.remark
FROM (
    SELECT '探店日实际工时确认' AS menu_name, 81 AS order_num,
           'business:clubhours:confirm' AS perms,
           'A2：确认俱乐部实际工时；宽泛的 business:topic:edit 不得替代' AS remark
    UNION ALL SELECT '探店日额外工时审批', 82, 'business:clubextra:approve',
           'A2：事前预审批与更正；追认不得抬高原预算'
    UNION ALL SELECT '探店日质量奖励确认', 83, 'business:clubquality:confirm',
           'A1：三维逐项一次确认，每维 0 或 200'
    UNION ALL SELECT '探店日质量奖励过错排除', 87, 'business:clubquality:exclude',
           'A1：已证明由商家/平台造成的过错排除该维计酬；能推翻已确认的 200，故与确认权分开'
    UNION ALL SELECT '探店日期级FINAL结算', 84, 'business:editionfinal:execute',
           'F3：FINAL 只消费已确认事实，重放返回同一结果'
    UNION ALL SELECT '俱乐部应付批次确认', 85, 'business:clubpayable:confirm',
           'F3：批次确认人不得兼任付款登记人'
    UNION ALL SELECT '俱乐部应付付款登记', 86, 'business:clubpayable:paid',
           'F3：付款登记人不得兼任批次确认人'
) AS seed
WHERE @cy_comp_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms = seed.perms);

-- ★把「确认人 ≠ 付款登记人」接到仓库<b>权威</b>的四角色上（migration_web_admin_roles_20260719.sql
--   建的 cy_operator / cy_auditor / cy_support / cy_finance）。
--   只有 Java + SQL CAS + DB CHECK 三层「同一个人两头都签写不进去」是不够的：新 seed 的这两个权限
--   在四角色矩阵里一条授权都没有 ⇒ 今天只有超管拿得到 ⇒ paid_by <> confirmed_by 恒不可满足 ⇒
--   第一笔真实对公打款登记不进去。分派成：批次确认 → 财务结算，付款登记 → 日常运营。
--   ⚠️ 这一步只解决「权限落在两个角色上」；「至少存在两个非超管 sys_user 分别持这两个角色」
--      是部署侧的事，本迁移不造账号（不虚构 ID）。
SET @cy_role_finance := (SELECT role_id FROM sys_role WHERE role_key = 'cy_finance' AND del_flag = '0' LIMIT 1);
SET @cy_role_operator := (SELECT role_id FROM sys_role WHERE role_key = 'cy_operator' AND del_flag = '0' LIMIT 1);
SET @cy_perm_batch_confirm := (SELECT menu_id FROM sys_menu WHERE perms = 'business:clubpayable:confirm' LIMIT 1);
SET @cy_perm_batch_paid := (SELECT menu_id FROM sys_menu WHERE perms = 'business:clubpayable:paid' LIMIT 1);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_role_finance, @cy_perm_batch_confirm FROM dual
WHERE @cy_role_finance IS NOT NULL AND @cy_perm_batch_confirm IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_role_finance AND menu_id = @cy_perm_batch_confirm);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_role_operator, @cy_perm_batch_paid FROM dual
WHERE @cy_role_operator IS NOT NULL AND @cy_perm_batch_paid IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_role_operator AND menu_id = @cy_perm_batch_paid);

-- 两头不得落在同一个角色上，否则分离在权限层就已经形同虚设。窄范围删除，只针对这两个 menu_id。
DELETE FROM sys_role_menu WHERE role_id = @cy_role_operator AND menu_id = @cy_perm_batch_confirm;
DELETE FROM sys_role_menu WHERE role_id = @cy_role_finance AND menu_id = @cy_perm_batch_paid;

-- ★EXTRA 事前审批权限 → cy_operator（日常运营）。
--   本轮 freezeTermsForSale 把它变成了<b>开售硬闸</b>：ExtraApproverReachability 用它判
--   「冻进 extra_approver_user_id 的项目负责人按不按得动预批按钮」。只种菜单不授权 ⇒
--   今天只有超管（*:*:*）匹配得上 ⇒ 唯一能通过开售闸的「项目负责人」就是 userId=1 ⇒
--   锁定裁决「项目负责人事前审批」在可落地配置里退化成「超管自己批自己」。
--   落 cy_operator 而不是 cy_finance：项目负责人属运营侧，与财务的审核/付款职责分离不冲突。
--   ⚠️ 只授这一条 F 权限，不碰 business:topic:edit 之类的宽权限。
SET @cy_perm_extra_approve := (SELECT menu_id FROM sys_menu WHERE perms = 'business:clubextra:approve' LIMIT 1);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_role_operator, @cy_perm_extra_approve FROM dual
WHERE @cy_role_operator IS NOT NULL AND @cy_perm_extra_approve IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_role_operator AND menu_id = @cy_perm_extra_approve);

-- ★承载四个 A 全部按钮的那张页：/topic/detail。
--   路由准入是 chengyinhub-ui/src/router/index.js 里 `path: '/topic'` 的
--   `permissions: ['business:topic:query']`，由 store/modules/permission.js 的
--   filterDynamicRoutes → auth.hasPermiOr 过滤；不满足就不 addRoutes，敲 URL 也会落到 /404。
--   cy_finance / cy_operator 拿不到它 ⇒ 两人登进后台<b>打不开那张页</b> ⇒ 本轮在 API 层修好的
--   「确认人 ≠ 付款登记人」可达性，在控制台上依旧点不动，只能 curl。
--
--   ⚠️ 父菜单要不要一并授？——查过链路了，**这条路由不需要**：
--     · 权限串来自 SysMenuMapper.selectMenuPermsByUserId，它的 where 只有
--       `m.status='0' and r.status='0' and ur.user_id=?`，**没有任何父菜单条件**；
--     · /topic 在 router/index.js 里是 `hidden: true` 的<b>静态动态路由</b>，不走 getRouters()
--       返回的侧栏树 —— memory `chengyin-ruoyi-menu-parent-auth-trap` 那条「父目录没授权则整棵
--       子树静默消失」讲的是<b>侧栏树</b>，对这条 hidden 路由不适用。
--   所以这里只窄授 business:topic:query 一条，不顺手把主题管理整棵目录塞进这两个角色的侧栏
--   （那会连带给出主题列表页，超出「打开资金操作面」这个需求）。
SET @cy_perm_topic_query := (SELECT menu_id FROM sys_menu WHERE perms = 'business:topic:query' LIMIT 1);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_role_finance, @cy_perm_topic_query FROM dual
WHERE @cy_role_finance IS NOT NULL AND @cy_perm_topic_query IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_role_finance AND menu_id = @cy_perm_topic_query);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @cy_role_operator, @cy_perm_topic_query FROM dual
WHERE @cy_role_operator IS NOT NULL AND @cy_perm_topic_query IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu
                  WHERE role_id = @cy_role_operator AND menu_id = @cy_perm_topic_query);

-- 回读合同：执行后必须从对面读回为 1。0 表示父菜单没找到或权限行形状不对，视为迁移未生效。
-- runner: assert=scalar-true
SELECT COUNT(*) = 7
FROM sys_menu
WHERE perms IN ('business:clubhours:confirm', 'business:clubextra:approve', 'business:clubquality:confirm',
                'business:clubquality:exclude',
                'business:editionfinal:execute', 'business:clubpayable:confirm', 'business:clubpayable:paid')
  AND menu_type = 'F' AND status = '0' AND parent_id = @cy_comp_parent;

-- 回读合同：批次确认与付款登记必须各落一个角色、且两头不同角色。
-- 0 表示四角色基线不在这个库上，或授权/互斥没落地 —— 那就等于分离闸只剩超管点不动的那一半。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_role_finance AND menu_id = @cy_perm_batch_confirm) = 1
   AND (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_role_operator AND menu_id = @cy_perm_batch_paid) = 1
   AND (SELECT COUNT(*) FROM sys_role_menu
        WHERE menu_id IN (@cy_perm_batch_confirm, @cy_perm_batch_paid)
          AND role_id IN (SELECT role_id FROM sys_role_menu a
                          WHERE a.menu_id = @cy_perm_batch_confirm)
          AND menu_id = @cy_perm_batch_paid) = 0;

-- 回读合同：两条承重权限必须真的授到角色上，不是只种了菜单。
--   ①EXTRA 事前审批 → cy_operator：读不回 1，就说明开售闸唯一能放行的「项目负责人」仍只有超管；
--   ②主题详情页 → cy_finance 与 cy_operator 各一行：读不回 1，两人登进后台就打不开
--     承载四个 A 全部按钮的 /topic/detail，API 层的可达性修复在控制台上等于没做。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_role_operator AND menu_id = @cy_perm_extra_approve) = 1
   AND (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_role_finance AND menu_id = @cy_perm_topic_query) = 1
   AND (SELECT COUNT(*) FROM sys_role_menu
        WHERE role_id = @cy_role_operator AND menu_id = @cy_perm_topic_query) = 1;

-- ★上线前置动作 + 部署诊断（**不放进本迁移执行**，理由见下）
--
--   本文件只种菜单与授权，不造 sys_user。而既有裁决是「后台只用超管单账号、四角色不建号」，
--   于是 `club_payable_batch` 的 `paid_by <> confirmed_by`（DB CHECK + SQL CAS + Java 三层）
--   会在**第一笔真实对公打款登记**上把人拦死，运营看到的只是一个点不动的按钮。
--   审计 2 逐字要求「若环境尚无法提供第二账号，必须以配置化降级状态显式显示，不能偷偷放弃复核」。
--
--   ⚠️ 为什么这段诊断不写成本文件里的一条 SELECT：本仓 ci/migration_gates.py 的 readback 规则
--      （声明了回读的迁移里，任何未绑 `-- runner: assert=` 的裸 SELECT 一律红）是对的 ——
--      执行器会**丢弃**未绑断言的查询结果，那种 SELECT 看着像观测、其实什么都没产生。
--      而这条又不能 assert：断言会把整份迁移卡在「先建账号」上，那是上线清单的人工步骤。
--   ⇒ 真正的观测点在 API：`GET /business/editionfinal/{topicId}` 的 `separation`
--      （degraded / reason / confirmHolders / paidHolders / deploymentAction，
--       实现见 TopicEditionFinalizationServiceImpl.separationReadiness）。它每次打开操作面都在读。
--
--   上线清单：为 cy_finance 与 cy_operator 各建一个**非超管** sys_user，然后手工跑下面这段核对
--   （separable_pairs ≥ 1 才算双人复核真能用；超管 user_id=1 被排除 —— 他持 *:*:* 两头都点得到，
--    算进来这三个数恒真，正是「回执 ≠ 观测」里最没用的那种绿）：
--
--   SELECT m.perms, COUNT(DISTINCT ur.user_id) AS non_super_holders
--     FROM sys_menu m
--     JOIN sys_role_menu rm ON rm.menu_id = m.menu_id
--     JOIN sys_user_role ur ON ur.role_id = rm.role_id
--     JOIN sys_role r ON r.role_id = ur.role_id
--     JOIN sys_user u ON u.user_id = ur.user_id
--    WHERE m.perms IN ('business:clubpayable:confirm', 'business:clubpayable:paid')
--      AND m.status = '0' AND r.status = '0' AND r.del_flag = '0'
--      AND u.status = '0' AND u.del_flag = '0' AND ur.user_id <> 1
--    GROUP BY m.perms;
--   -- 两行都 ≥ 1 还不够：两条权限落在同一个人身上时 CAS 照样命中 0 行。
--   -- 逐个核对 user_id 是否真能凑出不同的两个人，或直接读上面那个 API 的 separation.degraded。
