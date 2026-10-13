-- 探店日承接费换轨：加 HEADCOUNT（按已赚票数计酬）分量，并把保底与工时解耦。
--
-- 【类别】③ 要在库上跑（DDL + 两条 CHECK 替换），必须先于包含新 mapper/service 的 jar。
-- 【前置】club_compensation 建 club_edition_compensation_terms / club_payable / topic_edition_finalization；
--          r6_funds_final_closure 把 TICKET_SOURCE 加进两条 CHECK（本文件在它之上再加一个枚举）。
-- 【幂等】所有 ALTER 先查 information_schema；CHECK 缺则 ADD、在则原子 DROP+ADD 替换；不回填业务行。
-- 【可逆】确认 club_payable 无 HEADCOUNT 行、finalization.headcount_amount 全为 0 之后，
--          可删四个新列并把两条 CHECK 恢复到 r6 版本；禁止删业务行回滚。
--
-- 【为什么加这一分量】2026-08-25 产品裁决：俱乐部自报工时整条退役（线下无法核实，
--   自报即计酬是资损通道）。承接方原本的变动收入 EXTRA 随之归零，只剩 F + Q。
--   换轨的原则是「不按投入付，按产出付」：
--     · 工时是【投入】，且是自陈数字 —— 平台没有任何能证伪它的服务端事实；
--     · 已赚票数是【产出】，且是平台侧事实 —— topic_edition_funds_attribution.ticket_count
--       在 FINAL 同事务固化，口径是 gEarned = gCash + uIn − uOut − rRefund 那条链，已扣退款。
--   同行都是这么做的（Airbnb Experiences / Klook / GetYourGuide 一律按成交订单抽成，
--   剧本杀兼职 DM 按场结算），没有一家按线下工时给线下带队方付钱。
--
-- 【单价与封顶为什么进 topic_edition_cost 而不是写死常量】每期保底与单价都不一样，
--   由平台在后台逐期登记、开售当场冻进 terms；FINAL 只读冻结值，绝不回读最新配置
--   （与 fixed_compensation / quality_dimension_amount 同一套纪律）。
--
-- 【EXTERNAL_GATE】负控只能在真 Oracle MySQL 8 上造错 schema 再重放：
--   ①同名 headcount_rate 列类型或 nullable 不对 → guard① 红；
--   ②两条 CHECK 的枚举/分量少一个或多一个 → 末端两条精确回读红。

-- ─────────────────────────────────────────────────────────────────────────────
-- 写前 fail-closed guard①：这四个新列要么都还不存在（首次落库），要么形状完全正确。
-- 半落库或被人手改过的库里，同名列的类型/可空性可能是错的，而存在性预检会把它当成已完成。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND ((table_name = 'topic_edition_cost' AND column_name IN ('headcount_rate', 'headcount_cap'))
             OR (table_name = 'club_edition_compensation_terms' AND column_name IN ('headcount_rate', 'headcount_cap'))
             OR (table_name = 'topic_edition_finalization' AND column_name = 'headcount_amount')
             OR (table_name = 'topic_edition_funds_attribution' AND column_name = 'net_ticket_count'))) = 0
    -- 括号把「形状全对」整体包住:没有它,OR/AND 的优先级读起来要靠猜。
    OR ((SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE()
            AND LOWER(data_type) = 'decimal' AND numeric_precision = 14 AND numeric_scale = 2
            AND ((table_name = 'topic_edition_cost' AND column_name = 'headcount_rate' AND is_nullable = 'NO')
              OR (table_name = 'topic_edition_cost' AND column_name = 'headcount_cap'  AND is_nullable = 'YES')
              OR (table_name = 'club_edition_compensation_terms' AND column_name = 'headcount_rate' AND is_nullable = 'NO')
              OR (table_name = 'club_edition_compensation_terms' AND column_name = 'headcount_cap'  AND is_nullable = 'YES')
              OR (table_name = 'topic_edition_finalization' AND column_name = 'headcount_amount' AND is_nullable = 'NO'))) = 5
    -- net_ticket_count 是 BIGINT 不是 DECIMAL，形状单列一条查（上面那条按 decimal 收窄）。
   AND (SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
            AND column_name = 'net_ticket_count'
            AND LOWER(data_type) = 'bigint' AND is_nullable = 'YES') = 1);

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 期级成本：后台逐期登记「每人头单价」与「人头封顶」。
--    cap 可空 = 不封顶；rate 默认 0 = 这一期不按人头计酬（只发保底），是合法配置。
SET @hc_cost_rate_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_cost'
       AND column_name = 'headcount_rate'
);
SET @hc_cost_rate_sql := IF(
    @hc_cost_rate_exists = 0,
    'ALTER TABLE topic_edition_cost ADD COLUMN headcount_rate DECIMAL(14,2) NOT NULL DEFAULT 0.00',
    'SELECT 1'
);
PREPARE hc_cost_rate_stmt FROM @hc_cost_rate_sql;
EXECUTE hc_cost_rate_stmt;
DEALLOCATE PREPARE hc_cost_rate_stmt;

SET @hc_cost_cap_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_cost'
       AND column_name = 'headcount_cap'
);
SET @hc_cost_cap_sql := IF(
    @hc_cost_cap_exists = 0,
    'ALTER TABLE topic_edition_cost ADD COLUMN headcount_cap DECIMAL(14,2) NULL',
    'SELECT 1'
);
PREPARE hc_cost_cap_stmt FROM @hc_cost_cap_sql;
EXECUTE hc_cost_cap_stmt;
DEALLOCATE PREPARE hc_cost_cap_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 开售冻结条款：把上面两个数冻进来。FINAL 只读这里，不回读 topic_edition_cost。
SET @hc_terms_rate_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'club_edition_compensation_terms'
       AND column_name = 'headcount_rate'
);
SET @hc_terms_rate_sql := IF(
    @hc_terms_rate_exists = 0,
    'ALTER TABLE club_edition_compensation_terms ADD COLUMN headcount_rate DECIMAL(14,2) NOT NULL DEFAULT 0.00',
    'SELECT 1'
);
PREPARE hc_terms_rate_stmt FROM @hc_terms_rate_sql;
EXECUTE hc_terms_rate_stmt;
DEALLOCATE PREPARE hc_terms_rate_stmt;

SET @hc_terms_cap_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'club_edition_compensation_terms'
       AND column_name = 'headcount_cap'
);
SET @hc_terms_cap_sql := IF(
    @hc_terms_cap_exists = 0,
    'ALTER TABLE club_edition_compensation_terms ADD COLUMN headcount_cap DECIMAL(14,2) NULL',
    'SELECT 1'
);
PREPARE hc_terms_cap_stmt FROM @hc_terms_cap_sql;
EXECUTE hc_terms_cap_stmt;
DEALLOCATE PREPARE hc_terms_cap_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 终结头：第五个分量。默认 0，历史头不用回填 —— 它们本来就没有人头计酬。
SET @hc_final_amount_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
       AND column_name = 'headcount_amount'
);
SET @hc_final_amount_sql := IF(
    @hc_final_amount_exists = 0,
    'ALTER TABLE topic_edition_finalization ADD COLUMN headcount_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00 AFTER ticket_source_amount',
    'SELECT 1'
);
PREPARE hc_final_amount_stmt FROM @hc_final_amount_sql;
EXECUTE hc_final_amount_stmt;
DEALLOCATE PREPARE hc_final_amount_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 两条 CHECK 各加一个成员。缺约束时 ADD，存在时原子 DROP+ADD 替换；重放同一结果。
SET @hc_final_check_exists := (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_edition_final_amounts'
);
SET @hc_final_check_sql := IF(
    @hc_final_check_exists = 1,
    'ALTER TABLE topic_edition_finalization DROP CHECK ck_topic_edition_final_amounts, ADD CONSTRAINT ck_topic_edition_final_amounts CHECK (fixed_compensation >= 0 AND quality_amount >= 0 AND extra_amount >= 0 AND ticket_source_amount >= 0 AND headcount_amount >= 0)',
    'ALTER TABLE topic_edition_finalization ADD CONSTRAINT ck_topic_edition_final_amounts CHECK (fixed_compensation >= 0 AND quality_amount >= 0 AND extra_amount >= 0 AND ticket_source_amount >= 0 AND headcount_amount >= 0)'
);
PREPARE hc_final_check_stmt FROM @hc_final_check_sql;
EXECUTE hc_final_check_stmt;
DEALLOCATE PREPARE hc_final_check_stmt;

SET @hc_component_check_exists := (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_payable_component'
);
SET @hc_component_check_sql := IF(
    @hc_component_check_exists = 1,
    'ALTER TABLE club_payable DROP CHECK ck_club_payable_component, ADD CONSTRAINT ck_club_payable_component CHECK (component IN (''FIXED'', ''QUALITY'', ''EXTRA'', ''TICKET_SOURCE'', ''HEADCOUNT''))',
    'ALTER TABLE club_payable ADD CONSTRAINT ck_club_payable_component CHECK (component IN (''FIXED'', ''QUALITY'', ''EXTRA'', ''TICKET_SOURCE'', ''HEADCOUNT''))'
);
PREPARE hc_component_check_stmt FROM @hc_component_check_sql;
EXECUTE hc_component_check_stmt;
DEALLOCATE PREPARE hc_component_check_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. FINAL 归因：【已扣退款】的净票数。承接费 HEADCOUNT 的唯一计酬基数。
--
--    ★为什么不能直接用既有的 ticket_count：它是 count(*) over payment_status = 2 的【毛计数】，
--      全额退款的报名照样算 1 张。它必须保持毛口径 —— 六量里的 n_cash 与它逐行对账
--      （validatePersistedFinalAttributions 断言 Σticket_count == n_cash），改它会连带打断对账。
--      所以净口径单开一列，与 earned_amount 共用同一个退款 join、在同一事务里算出。
--      拿毛计数计酬的后果是「整场退光了还照付承接费」，且零告警。
--
--    ★为什么可空、且【不】回填历史行：净票数是一个事实，不是一个默认值。
--      本列落库之前冻的 FINAL 快照里没有这个事实，NULL 是对它唯一诚实的表达。
--      回填成 0 会让那些期次静默少付；回填成 ticket_count 会让它们静默多付。
--      消费方（headcountAmount）读到 NULL 一律判 PENDING，两种错都不犯。
--      历史期次本来也没有人头计酬条款，正常路径根本不会去读它。
SET @hc_net_tickets_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
       AND column_name = 'net_ticket_count'
);
SET @hc_net_tickets_sql := IF(
    @hc_net_tickets_exists = 0,
    'ALTER TABLE topic_edition_funds_attribution ADD COLUMN net_ticket_count BIGINT NULL AFTER ticket_count',
    'SELECT 1'
);
PREPARE hc_net_tickets_stmt FROM @hc_net_tickets_sql;
EXECUTE hc_net_tickets_stmt;
DEALLOCATE PREPARE hc_net_tickets_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 回读①：五个 DECIMAL 新列的形状逐个钉死（类型 / 精度 / 可空性），少一个或形状不对都红。
-- （第六列 net_ticket_count 是 BIGINT，在回读⑤单独钉。）
-- runner: assert=scalar-true
SELECT COUNT(*) = 5
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND LOWER(data_type) = 'decimal' AND numeric_precision = 14 AND numeric_scale = 2
  AND ((table_name = 'topic_edition_cost' AND column_name = 'headcount_rate' AND is_nullable = 'NO')
    OR (table_name = 'topic_edition_cost' AND column_name = 'headcount_cap'  AND is_nullable = 'YES')
    OR (table_name = 'club_edition_compensation_terms' AND column_name = 'headcount_rate' AND is_nullable = 'NO')
    OR (table_name = 'club_edition_compensation_terms' AND column_name = 'headcount_cap'  AND is_nullable = 'YES')
    OR (table_name = 'topic_edition_finalization' AND column_name = 'headcount_amount' AND is_nullable = 'NO'));

-- 回读②：component 枚举【逐字符】比较，不用 LIKE。
-- 理由与 r6 同源：'%HEADCOUNT%' 对 component <> 'HEADCOUNT'、对 IN (…,'ANYTHING') 一样命中，
-- 而 LIKE 里的 `_` 还是未转义的单字符通配符 —— 那是恒绿不是断言。
-- 归一链不依赖 sql_mode：空白用 POSIX 字符类 [[:space:]]，不写 \s
-- （NO_BACKSLASH_ESCAPES 下 \s 会退化成字面 s，把枚举里的 s 一起吃掉 ⇒ 对正确的库恒红）。
-- 排错：SELECT check_clause FROM information_schema.check_constraints
--       WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_payable_component';
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_payable_component'
  AND REGEXP_REPLACE(LOWER(check_clause), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
      <=> 'componentin''fixed'',''quality'',''extra'',''ticket_source'',''headcount''';

-- 回读③：五分量非负 CHECK 的语义回读；漏掉任一分量、或把某个 >= 写成 <= 都必须红。
-- 排错：SELECT check_clause FROM information_schema.check_constraints
--       WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_edition_final_amounts';
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_edition_final_amounts'
  AND REGEXP_REPLACE(LOWER(check_clause), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
      <=> 'fixed_compensation>=0andquality_amount>=0andextra_amount>=0andticket_source_amount>=0andheadcount_amount>=0';

-- 【为什么这里没有「历史行取值」的断言】
-- 曾经写过两条:「既有终结头 headcount_amount 必须全是 0」「既有归因行 net_ticket_count 必须全是 NULL」。
-- 两条都是【点位断言】:第一次落库为真,等真实业务跑起来（结出第一笔承接费、冻出第一份带净票数的
-- FINAL）之后再重放本文件,它们会红 —— 而那时库是完全正确的。会假红的断言比没有断言更坏:
-- 它训练人忽略红灯,下次真出事也没人看。
--
-- 历史行的取值由 DDL 本身决定,不需要事后断言,也不该由本文件的重放来担保:
--   · headcount_amount NOT NULL DEFAULT 0.00 ⇒ 既有行取默认值 0.00（想要的结果）;
--   · net_ticket_count NULL 无默认         ⇒ 既有行取 NULL（想要的结果,消费方据此判 PENDING）。
-- 而「本文件不含任何 DML」是一个可以直接 grep 验证、且永远为真的更强保证:
--   grep -Ei '^[[:space:]]*(update|insert|delete|replace)' 本文件 ⇒ 应无输出。

-- 回读⑤：净票数列的形状 —— 必须可空。若被建成 NOT NULL DEFAULT 0，
-- 历史快照会被静默填成「净票数 0」，消费方的 PENDING 闸再也不会触发 ⇒ 那些期次静默少付。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'topic_edition_funds_attribution'
  AND column_name = 'net_ticket_count'
  AND LOWER(data_type) = 'bigint' AND is_nullable = 'YES';
