-- TICKET_SOURCE 正式退役：从应付分量枚举与终结头里摘掉。
--
-- 【类别】③ 要在库上跑（两条 CHECK 替换 + 一列 DROP）。
-- 【★时序与本仓常规相反：必须【后】于部署，不是先于】
--   常规迁移是「加东西」，所以先落库、后部署。本文件是「减东西」：
--     · 先落库再部署 ⇒ 窗口里是【旧 jar + 新 schema】。旧 jar 的 insertFinalization 仍写
--       ticket_source_amount（列已删 ⇒ Unknown column），且仍写 TICKET_SOURCE 分量
--       （枚举已收窄 ⇒ CHECK 违反）⇒ finalizeEdition 每次抛错，俱乐部应付全线结不出。
--     · 先部署再落库 ⇒ 窗口里是【新 jar + 旧 schema】。新 jar 不写该列，列是
--       NOT NULL DEFAULT 0.00 ⇒ 落 0 即可；也不写该分量 ⇒ 旧 CHECK 是新枚举的超集，无碍。
--   ⇒ 正确顺序：合并 → 自动部署 → 观测生产 jar 的 git.commit.id → 才落本文件。
-- 【前置】20260825_club_headcount_component 把 HEADCOUNT 加进这两条 CHECK；本文件在它之上做减法。
-- 【幂等】三段 DDL 都是「存在才动」；guard① 同时接受【落库前】与【已落库】两种形状，
--          所以整文件可重放（中途失败也能原地续跑）。不回填、不删业务行。
-- 【可逆】重跑 r6 + headcount 那两支即可把枚举与列加回来；本文件不销毁任何业务数据
--          （guard② 已断言没有任何非零金额记在被删的列/分量上）。
--
-- 【为什么退役】TICKET_SOURCE 是「这张票是谁引流来的」的 G_earned×10% 渠道奖励。
--   但 source_club_id 只是分享深链上的 query 参数（chengyinhub-xcx/utils/ticket-source.js），
--   任何登录玩家都能自己填；旧实现校验「等于执行俱乐部」就放行，而**被伪造的正是那个相等条件**：
--   买家把公开可得的执行俱乐部 id 填进 body，平台自然流量就变成该俱乐部的 10% 应付，账面还完全自洽。
--   全仓逐个排查过 product_role / merchant_referral_bounty / roam_referral_enabled / 会员邀请人，
--   没有任何「这一单真的被某俱乐部引流过」的服务端事实 ⇒ 该分量早已 fail-closed 成恒 0。
--   账面上挂一个永远发 0 元的资金分量，是本仓最典型的「假保证」形态：看起来在分钱，实际恒 0，
--   每个读这段代码的人都要重新搞明白它为什么是 0。2026-08-25 产品裁决：正式退役。
--
-- 【★为什么现在退最便宜】生产 club_payable 与 topic_edition_finalization 此刻都是 0 行，
--   没有任何历史应付需要保留口径。guard② 把这条前提写成硬断言 —— 一旦将来有了真实应付行，
--   本文件会直接红，不会把有数据的库悄悄改成另一套分量口径。
--
-- 【恢复】要恢复引流归因，产品必须先定义凭据的四段语义（签发 → 持久化 → 消费 → 防重放）；
--   届时这条链路无论如何都要重写，保留一个恒 0 的空壳并不能省下那份工作。
--   ⚠️ 报名侧的信任边界（CmsRegistrationServiceImpl 恒 null + source_channel_code 审计留声、
--   cms_registration.source_club_id/source_frozen_at、freezeTicketSourceOnPayment）
--   **本文件一律不动**：那是纯防御，成本为零，拆它才危险。退役的只是结算侧的分量。

-- 【EXTERNAL_GATE】负控只能在真 Oracle MySQL 8 上造错 schema 再重放：
--   ①两条 CHECK 里少一个成员或多一个成员 → 末端两条精确回读红（逐字符 <=> 比，不用 LIKE）；
--   ②ticket_source_amount 没删掉 → 回读③ 红；
--   ③报名侧三列被误删（改过界）→ 回读④ 红；
--   ④guard② 的负控：先往 club_payable 插一行再重放本文件，必须红 —— 证明「趁台账空」
--     这个前提真的被守着，而不是一句写在注释里的许愿。
--   ⚠️ 本文件虽已挂进 ClubPayableMybatisIntegrationTest 的迁移链，但那条路【证明不了 guard】：
--   它用 Spring 的 ScriptUtils 执行，而 `-- runner: assert=scalar-true` 只有 ci/run_migration.py 认；
--   在 IT 里那几条断言退化成普通 SELECT，返回 false 也不会失败。
--   ⇒ IT 只证明了【DDL 幂等】(执行两遍不报错)，不证明 guard 会拦。别把它当 guard 的证据。
--   guard 的实证是本地用 run_migration 的真断言语义在真 MySQL 上跑的负控：
--     · 造一行 component='TICKET_SOURCE' 的应付 → guard② 红；
--     · 造一行 ticket_source_amount<>0 的终结头 → guard② 红；
--     · 把该行改成 0（部署窗口内新 jar 写出来的样子）→ 放行，列删掉，而那行终结头【没被销毁】；
--     · 同一文件连跑两遍 → 两遍都 OK（guard① 同时接受前态与后态）。
--   枚举收窄另有真库负控：theRetiredTicketSourceComponentIsRejectedByTheCheckConstraint。

-- ─────────────────────────────────────────────────────────────────────────────
-- 写前 fail-closed guard①：component 枚举必须处于【落库前】或【已落库】之一，不许是第三种形状。
-- 前态 = headcount 那支已落库（含 TICKET_SOURCE + HEADCOUNT）；后态 = 本文件已跑过（无 TICKET_SOURCE）。
-- ★同时接受后态是为了让本文件可重放：只认前态的话，跑完一次就再也跑不了，
--   中途失败（比如第 3 步 DDL 锁超时）就没法原地续跑，只能人工拼接残局。
-- 第三种形状（枚举被人手改成别的样子）说明库不在任何已知状态上 ⇒ 停下来，不要继续做减法。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.check_constraints
         WHERE constraint_schema = DATABASE()
           AND constraint_name = 'ck_club_payable_component'
           AND REGEXP_REPLACE(LOWER(check_clause), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
               IN ('componentin''fixed'',''quality'',''extra'',''ticket_source'',''headcount''',
                   'componentin''fixed'',''quality'',''extra'',''headcount''')) = 1;

-- 写前 fail-closed guard②：★没有任何一分钱真的记在 TICKET_SOURCE 名下。
--
-- 判据【不是】「两张表为空」—— 那条写法会把自己锁死：本文件必须后于部署（见文件头），
-- 而部署到落库之间新 jar 完全可以合法写入终结头与应付行（旧 schema 允许），
-- 一旦写了，「表为空」就永远不成立，只能靠删真实资金行才能落库 —— 那正是它想防的事。
--
-- 真正让「删列 / 收窄枚举」安全的不变量只有两条：
--   ① 没有 component = 'TICKET_SOURCE' 的应付行（否则收窄枚举 = 让已有资金行违宪）；
--   ② 没有 ticket_source_amount <> 0 的终结头（否则删列 = 销毁一笔真发过的钱）。
-- 新 jar 写出来的行：分量里没有 TICKET_SOURCE，该列取默认 0 ⇒ 两条都满足，窗口内落库安全。
-- 而真有票源奖励发出去过的话，两条任一为假 ⇒ 红，停下来让人看。
--
-- ⚠️ 这一条必须走动态 SQL 探列，不能直接写 WHERE ticket_source_amount：
--   重放时（后态）该列已被第 3 步删掉，直接引用会撞 Unknown column ——
--   那会把「可重放」重新打破一次，正是 guard① 刚修好的东西。
--   SQL 是先解析后执行的，写在 IF 的未选中分支里也照样解析失败，所以只能 PREPARE。
SET @ts_has_amount_col := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
       AND column_name = 'ticket_source_amount'
);
SET @ts_nonzero_sql := IF(
    @ts_has_amount_col = 1,
    'SET @ts_nonzero_amounts := (SELECT COUNT(*) FROM topic_edition_finalization WHERE ticket_source_amount <> 0)',
    'SET @ts_nonzero_amounts := 0'
);
PREPARE ts_nonzero_stmt FROM @ts_nonzero_sql;
EXECUTE ts_nonzero_stmt;
DEALLOCATE PREPARE ts_nonzero_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM club_payable WHERE component = 'TICKET_SOURCE') = 0
   AND @ts_nonzero_amounts = 0;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 应付分量枚举：摘掉 TICKET_SOURCE，留 FIXED/QUALITY/EXTRA/HEADCOUNT。
SET @ts_component_check_exists := (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_payable_component'
);
SET @ts_component_check_sql := IF(
    @ts_component_check_exists = 1,
    'ALTER TABLE club_payable DROP CHECK ck_club_payable_component, ADD CONSTRAINT ck_club_payable_component CHECK (component IN (''FIXED'', ''QUALITY'', ''EXTRA'', ''HEADCOUNT''))',
    'ALTER TABLE club_payable ADD CONSTRAINT ck_club_payable_component CHECK (component IN (''FIXED'', ''QUALITY'', ''EXTRA'', ''HEADCOUNT''))'
);
PREPARE ts_component_check_stmt FROM @ts_component_check_sql;
EXECUTE ts_component_check_stmt;
DEALLOCATE PREPARE ts_component_check_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 终结头的非负 CHECK：先摘掉引用 ticket_source_amount 的那一项，才能删列。
--    顺序不能反 —— CHECK 还引用着的列删不掉。
SET @ts_final_check_exists := (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_edition_final_amounts'
);
SET @ts_final_check_sql := IF(
    @ts_final_check_exists = 1,
    'ALTER TABLE topic_edition_finalization DROP CHECK ck_topic_edition_final_amounts, ADD CONSTRAINT ck_topic_edition_final_amounts CHECK (fixed_compensation >= 0 AND quality_amount >= 0 AND extra_amount >= 0 AND headcount_amount >= 0)',
    'ALTER TABLE topic_edition_finalization ADD CONSTRAINT ck_topic_edition_final_amounts CHECK (fixed_compensation >= 0 AND quality_amount >= 0 AND extra_amount >= 0 AND headcount_amount >= 0)'
);
PREPARE ts_final_check_stmt FROM @ts_final_check_sql;
EXECUTE ts_final_check_stmt;
DEALLOCATE PREPARE ts_final_check_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 删掉终结头上的 ticket_source_amount 列。guard② 已断言没有一分非零金额记在这一列上。
SET @ts_final_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
       AND column_name = 'ticket_source_amount'
);
SET @ts_final_col_sql := IF(
    @ts_final_col_exists = 1,
    'ALTER TABLE topic_edition_finalization DROP COLUMN ticket_source_amount',
    'SELECT 1'
);
PREPARE ts_final_col_stmt FROM @ts_final_col_sql;
EXECUTE ts_final_col_stmt;
DEALLOCATE PREPARE ts_final_col_stmt;

-- ─────────────────────────────────────────────────────────────────────────────
-- 回读①：component 枚举【逐字符】比较，不用 LIKE（理由同 headcount 那支：
-- '%TICKET_SOURCE%' 这类模式对 NOT IN、对多出来的成员一样命中，是恒绿不是断言）。
-- 归一链不依赖 sql_mode：空白用 POSIX 字符类，不写 \s。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_club_payable_component'
  AND REGEXP_REPLACE(LOWER(check_clause), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
      <=> 'componentin''fixed'',''quality'',''extra'',''headcount''';

-- 回读②：四分量非负 CHECK 的语义回读；漏掉任一分量、或残留 ticket_source_amount 都必须红。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_topic_edition_final_amounts'
  AND REGEXP_REPLACE(LOWER(check_clause), '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
      <=> 'fixed_compensation>=0andquality_amount>=0andextra_amount>=0andheadcount_amount>=0';

-- 回读③：列真的没了。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'topic_edition_finalization'
  AND column_name = 'ticket_source_amount';

-- 【为什么这里没有「报名侧三列还在」的断言】
-- 曾写过一条回读④，断言 cms_registration 的 source_club_id / source_frozen_at /
-- source_channel_code 三列一列不少，用来证明「只退结算侧、没改过界」。它有两个毛病：
--   ① 超出本文件的因果范围：本文件根本不碰那张表，该断言只可能因为【别的东西】删了列而红 ——
--      那不是这支迁移该负责的事，红了也指不到原因；
--   ② 实测会假红：在只建了资金那几张表的 scratch 库里跑（本地重放验证就是这么跑的），
--      cms_registration 压根不存在 ⇒ 恒 0 ⇒ 恒红。会假红的断言比没有断言更坏。
-- 「本文件不触碰报名侧」是一个可以直接 grep 验证、且永远为真的更强保证：
--   grep -iE 'cms_registration|source_club_id|source_frozen_at|source_channel_code' 本文件
--   ⇒ 只应命中本段注释与文件头的说明，不应命中任何一条可执行语句。
-- 行为侧由 TicketSourceAttributionTrustBoundaryTest（自报值永不落库）继续承重。
