-- ③ 计酬档(REVSHARE)· 平台应付台账 + 月结批次
-- 来源:《③REVSHARE平台应付台账_施工图_20260717》§5;《城瘾商业化_四种产品模式_定稿_2026-07-15》§4.2 D21
--       《交接_P0-13~17权益账本接线_2026-07-16》§6bis 决策 B / B-口径 / B-边界
--
-- 【这两张表是什么】
--   ③-自营 的票款**全额归平台**,平台按核销给商家付固定人头费 ¥X/人。
--   ⇒ 这笔钱是平台的**采购/推广费支出**,走「应付台账 → 对公打款 → 商家开票」,
--     **不是分账、不碰二清、不等收付通**(D21 原话)。
--
--   merchant_payable       = 不可变的**事件流水**(计提 ACCRUAL / 冲正 REVERSAL)
--   merchant_payable_batch = 月结**批次**,状态机与发票挂这里
--
-- 【为什么必须新表,不能复用 settlement_detail】(施工图 §5.1,两条缺一不可)
--   1. settlement_detail 的模型是 `Σ明细 == gross`,而 per_head_fee **与票价无关且可能 > gross**
--      ⇒ 硬塞会让 merchantShare 被兜底夹到 0 = **账算错**。
--   2. redemption 表**没有金额快照**。它有 offer_id,看起来应付额可以 join
--      merchant_chapter_offer.per_head_fee 现算 —— **不行**:商家把人头费从 ¥5 改成 ¥50,
--      历史上所有已核销的应付被**追溯改写**。这与 P0-28「拍板4:分账与定价同冻结,
--      不被 live 费率追溯改写」是同一个病,本项目已经栽过一次。
--      ⇒ **新表的核心价值就是「核销那一刻的费率快照」,不是存 join 结果。**
--
-- 【为什么状态机在**批次**上,不在台账行上】(2026-07-17 拍板后定稿,刻意偏离施工图 §5.2 草案)
--   §5.2 草案把 status 放在台账行,但同一份文档的 §5.4/T4 又要求「append-only:只有 insert,
--   没有 update」——**同一行既要不可变、又要 PENDING→CONFIRMED→PAID 地变,做不到**。
--   用户拍板「月结批次」正好解开:
--     · 台账行 = 不可变事件(它记的是「这笔核销发生了,当时费率是 X」,这是历史,永不改)
--     · 批次   = 可变生命周期(待结 → 对账确认 → 已打款),**天然就是批次粒度的动作**
--   若把 status 放行上,一次批次流转要 UPDATE N 行、还可能各行漂移到不同状态 = 对不上账。
--
--   ⚠️ 更硬的理由:**已打款批次的事后冲正**。若 status 在行上,一笔 PAID 的计提被冲正时
--   无法表达(钱已经打出去了,不能把行改回 PENDING)。批次模型下它自然落进**下个月的批次**
--   变成负向净额 —— 这正是 §4.1「绝对值 + 方向列」范式存在的理由,也是真实会计的做法。
--
-- 【为什么不存 batch_id,而是推导批次归属】
--   存 batch_id ⇒ 生成批次时要 UPDATE 台账行 ⇒ **又破 append-only**。
--   批次归属 = (merchant_member_id, create_time 所在自然月),**可推导**:
--     月结 = 自然月、次月结上月 ⇒ 结算时该月已过完 ⇒ 不可能再有新行落进那个月 ⇒ 推导是稳的。
--   ⚠️ 这条的前提是「批次只在月份结束后生成」。**若将来改成月中就能结,这个推导立刻不成立**
--      (月中生成后又来了新行,它们无处可去)—— 那时必须回头加 batch_id 并接受 UPDATE。
--   批次行仍存 amount_total:它是**实际打出去的那个数**,要冻结留痕,不能每次查都重算。
--
-- 【应付余额怎么算 —— 别写成 SUM(amount)】
--   amount 是**绝对值不带正负**,方向由 entry_type 单独承载(§4.1 竞品实证的可移植范式)。
--   ⇒ 净额 = SUM(CASE entry_type WHEN 'ACCRUAL' THEN amount ELSE -amount END)
--   写成 SUM(amount) 会把冲正算成又欠一笔 = 翻倍多付。
--
-- 【幂等】
--   ⚠️ **表级幂等**(CREATE TABLE IF NOT EXISTS),重复执行无害。
--   ⚠️ 行级幂等靠 uk_payable_idem:accrual:{redemptionId} / reversal:{redemptionId}
--      ⇒ 同一笔核销重复入账被键拦住。
--   ⚠️ 与 redemption 表的幂等设计**刻意不同**:那张表的撤销是 UPDATE status 改 ACTIVE→REVERSED,
--      靠生成列让键塌成 NULL 来释放;本表的冲正是**追加新行**,两个键天然不撞,不需要生成列。
--      别照抄那边的 active_idem_key 范式过来 —— 那是为「撤销后可重新核销」设计的,本表没这个语义。
--
-- 【可逆】—— ⚠️ 仅适用于「jar 已回退后整体拆除」
--   DROP TABLE IF EXISTS merchant_payable_batch;
--   DROP TABLE IF EXISTS merchant_payable;
--   ⚠️ 台账是财务记录。**生产上有行之后别 DROP** —— 先导出留档。
--
-- 【风险】
--   **低 —— 纯新增两张表,零存量改动、零行为变化**。
--   今天 ③ 一张票都没卖过(product_type=3 主题 0 个 / merchant_chapter_offer 0 行,
--   施工图 §2 于 2026-07-17 亲查)⇒ 落库后这两张表会保持 0 行,直到 ③ 真开卖。
--   ⚠️ 但**③ 开卖那一刻起**,没有这两张表就是:核销成功、redemption 有流水、
--      **没有任何地方记着平台欠这家店多少钱**,且零告警。这就是它必须先于 ③ 上量落库的理由。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
-- ⚠️ 部署顺序:本份**先落库**,再合 jar。表不在则新代码查询报 1146。
-- ⚠️ 前置:migration_perk_ledger_20260716.sql(建 redemption 表)+ migration_terms_mode_20260716.sql
--    (给 merchant_chapter_offer 加 terms_mode/per_head_fee)**都必须已落库**。两者均已在生产。


-- ======================= 【执行方式】 =======================
-- · **两条 CREATE TABLE** 按顺序执行(batch 表无外键依赖 payable,顺序其实无所谓,但按此序读着顺)。
-- · 两条都是 IF NOT EXISTS ⇒ 重复执行无害,失败即停。


-- ======================= 第一步:只读预检 =======================
--
-- P0【前置】依赖的表在不在:
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('redemption', 'merchant_chapter_offer');
--   · 少于 2 行 → **停**,先跑 migration_perk_ledger_20260716.sql / migration_terms_mode_20260716.sql。
--
-- P1【必看】本份的表是否已存在:
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('merchant_payable', 'merchant_payable_batch');
--   · 0 行 → 正常,两条全执行。
--   · 有行 → 已跑过。IF NOT EXISTS 会跳过,但**要核对既有结构与本份一致**(见 P4),
--     否则代码按新结构查、库是旧结构 = 1054 未知列。
--
-- P2【版本闸 —— 决定 CHECK 是真约束还是注释】
--   SELECT VERSION();
--   ⚠️ **必须 ≥ 8.0.16**。低于此版本 MySQL **只解析不执行** CHECK ⇒ 本份的 ck_payable_entry_type /
--   ck_payable_amount / ck_batch_status / ck_batch_invoice_status **全是注释** ⇒
--   「entry_type 只能是 ACCRUAL/REVERSAL」「amount 不得为负」这些一致性**没有任何东西在保证**,
--   只剩应用层。跑完用 P4 当场验。
--
-- P3【存量盘点】③ 今天卖出去过没有:
--   SELECT COUNT(*) AS revshare_offers FROM merchant_chapter_offer WHERE terms_mode = 'REVSHARE';
--   SELECT COUNT(*) AS revshare_chapters FROM cms_topic_chapter WHERE terms_mode = 'REVSHARE';
--   · 都是 0 → 与施工图 §2 的观测一致,落库后两表保持 0 行,零风险。
--   · >0 → **停下来告诉用户**:说明 ③ 已经在卖了,而这段时间的核销**没有任何应付被记下来**
--     ⇒ 这是**已发生的丢账**,要先把历史补齐(逐笔按 redemption + offer 快照回填),
--     不能当成「新表零风险」直接上。
--
-- P4【落库后自检 —— 同时是 CHECK 的版本探针,必须真跑】
--   -- 期望被 ck_payable_entry_type 拒(3819)。**没被拒 = 你的 MySQL < 8.0.16,CHECK 全是摆设**:
--   INSERT INTO merchant_payable (redemption_id, topic_id, chapter_id, merchant_member_id,
--          per_head_fee_snapshot, amount, entry_type, idempotency_key)
--        VALUES (999999, 999999, 999999, 999999, 5.00, 5.00, 'BOGUS', 'probe:bad-type');
--   -- 期望被 ck_payable_amount 拒(amount 必须 ≥ 0,负数说明有人把方向写进了金额):
--   INSERT INTO merchant_payable (redemption_id, topic_id, chapter_id, merchant_member_id,
--          per_head_fee_snapshot, amount, entry_type, idempotency_key)
--        VALUES (999999, 999999, 999999, 999999, 5.00, -5.00, 'ACCRUAL', 'probe:neg-amount');
--   -- 期望被 uk_payable_idem 拒(1062)—— 幂等键真的在拦:
--   INSERT INTO merchant_payable (redemption_id, topic_id, chapter_id, merchant_member_id,
--          per_head_fee_snapshot, amount, entry_type, idempotency_key)
--        VALUES (999999, 999999, 999999, 999999, 5.00, 5.00, 'ACCRUAL', 'probe:idem');
--   INSERT INTO merchant_payable (redemption_id, topic_id, chapter_id, merchant_member_id,
--          per_head_fee_snapshot, amount, entry_type, idempotency_key)
--        VALUES (999999, 999999, 999999, 999999, 5.00, 5.00, 'ACCRUAL', 'probe:idem');
--   -- 期望被 ck_batch_status 拒:
--   INSERT INTO merchant_payable_batch (merchant_member_id, period_ym, amount_total, status)
--        VALUES (999999, '2026-07', 0.00, 'BOGUS');
--   -- 期望被 uk_batch_merchant_period 拒(1062)—— 同商家同月只能有一个批次:
--   INSERT INTO merchant_payable_batch (merchant_member_id, period_ym, amount_total)
--        VALUES (999999, '2026-07', 0.00);
--   INSERT INTO merchant_payable_batch (merchant_member_id, period_ym, amount_total)
--        VALUES (999999, '2026-07', 0.00);
--   -- 跑完删掉试探行:
--   DELETE FROM merchant_payable WHERE merchant_member_id = 999999;
--   DELETE FROM merchant_payable_batch WHERE merchant_member_id = 999999;


-- ======================= 第二步:DDL =======================

-- ③ 计酬档平台应付台账:**不可变事件流水**。冲正走 REVERSAL 新行,绝不改原行。
CREATE TABLE IF NOT EXISTS merchant_payable (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    redemption_id         BIGINT        NOT NULL COMMENT '回指核销流水 redemption.id;REVERSAL 行指向被冲的那笔核销',
    topic_id              BIGINT        NOT NULL COMMENT '主题id(③-自营)',
    chapter_id            BIGINT        NOT NULL COMMENT '章节id。③ 必须非空——一票跨N章,**章才是应付的粒度**',
    merchant_member_id    BIGINT        NOT NULL COMMENT '收款商家 memberId(供应商)。⚠️是 memberId 不是 mms_merchant.id,与 redemption.merchant_id 同口径',
    per_head_fee_snapshot DECIMAL(10,2) NOT NULL COMMENT '★核销那一刻冻结的 ¥X/人。**绝不 join merchant_chapter_offer 现算**——商家改价会追溯改写历史应付(P0-28 同型病)',
    amount                DECIMAL(10,2) NOT NULL COMMENT '★绝对值,不带正负。方向由 entry_type 承载。MVP 形态下 == per_head_fee_snapshot(固定人头费×1人次)',
    entry_type            VARCHAR(16)   NOT NULL COMMENT 'ACCRUAL 计提(核销即计提,已拍板) / REVERSAL 冲正(撤销核销)',
    idempotency_key       VARCHAR(128)  NOT NULL COMMENT 'accrual:{redemptionId} / reversal:{redemptionId}。同一笔核销重复入账被 uk 拦住',
    create_time           DATETIME      NOT NULL COMMENT '★兼作批次归属依据:批次 = (merchant_member_id, 本列所在自然月)。改这列的语义前先读文件头「为什么不存 batch_id」',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payable_idem (idempotency_key),
    KEY idx_payable_merchant_time (merchant_member_id, create_time),
    KEY idx_payable_redemption (redemption_id),
    CONSTRAINT ck_payable_entry_type CHECK (entry_type IN ('ACCRUAL', 'REVERSAL')),
    CONSTRAINT ck_payable_amount CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='③计酬档平台应付台账(append-only事件流水;冲正走REVERSAL新行;净额=SUM(CASE entry_type WHEN ACCRUAL THEN amount ELSE -amount END))';


-- 月结批次:**状态机与发票挂这里**,台账行保持不可变。
-- 批次成员由 (merchant_member_id, period_ym) 推导,不落 batch_id(见文件头)。
CREATE TABLE IF NOT EXISTS merchant_payable_batch (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_member_id BIGINT        NOT NULL COMMENT '收款商家 memberId',
    period_ym          CHAR(7)       NOT NULL COMMENT '结算期,自然月 YYYY-MM(如 2026-07)。次月结上月',
    amount_total       DECIMAL(12,2) NOT NULL COMMENT '★生成时冻结的净额=SUM(CASE entry_type WHEN ACCRUAL THEN amount ELSE -amount END)。是实际打出去的那个数,留痕用,别每次查都重算。⚠️可能为负(上月已付、本月大量冲正)——那是欠平台钱,别夹到0',
    status             VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待结 / CONFIRMED对账确认 / PAID已打款(2026-07-17拍板三档)。⚠️与 invoice_status 是**两条独立轴**,别混——混了就表达不了「已打款但未开票」',
    invoice_status     VARCHAR(16)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE未开票 / ISSUED已开蓝票 / RED已红冲。★冲正路径的分支键:已开票的冲正必须走红字发票流程',
    invoice_no         VARCHAR(64)   NULL COMMENT '蓝票号',
    red_invoice_no     VARCHAR(64)   NULL COMMENT '红票号(已开票批次被冲正时必须回填)',
    confirmed_at       DATETIME      NULL COMMENT '对账确认时间',
    confirmed_by       VARCHAR(64)   NULL COMMENT '对账确认人(运营)',
    paid_at            DATETIME      NULL COMMENT '打款时间',
    paid_by            VARCHAR(64)   NULL COMMENT '打款操作人(运营)',
    pay_voucher_no     VARCHAR(64)   NULL COMMENT '对公打款凭证号/流水号,留痕对账用',
    create_time        DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_merchant_period (merchant_member_id, period_ym),
    KEY idx_batch_status (status, period_ym),
    CONSTRAINT ck_batch_status CHECK (status IN ('PENDING', 'CONFIRMED', 'PAID')),
    CONSTRAINT ck_batch_invoice_status CHECK (invoice_status IN ('NONE', 'ISSUED', 'RED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='③计酬档应付月结批次(状态机+发票轴;成员由(商家,period_ym)推导不落batch_id)';
