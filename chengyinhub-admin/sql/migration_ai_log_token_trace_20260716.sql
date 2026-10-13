-- AI 治理 item1:ai_generation_log 补 token_in / token_out 两列 + trace_id 索引
--
-- 【背景 —— 为什么现在必须加】
--   1) **token_in/token_out 一直在被 set,但从来没落过库。**
--      AiGatewayServiceImpl.saveLog() 早就在 setTokenIn/setTokenOut,
--      但 AiGenerationLogMapper.xml 的 insert 列清单里**没有这两列**,
--      migration_ai_generation_log.sql 建表也只有 token_est ⇒ 两个值一路被丢弃,
--      零报错、零告警。表里只有 token_est=in+out 的**和**,拆不开 ——
--      「这次调用是 prompt 太长还是模型话太多」这类问题今天答不出来。
--   2) 本次同批修了 trace_id 断链(saveLog 原先 `l.setTraceId(IdUtils.simpleUUID())`
--      另起 UUID,与返给前端 / 写进 ai_merchant_match_snapshot.trace_id 的那个对不上)。
--      修完 trace_id 才第一次真正可 join ⇒ 需要按 trace_id 精确搜的索引。
--
-- ⚠️ 【2026-07-16 生产预检更正 —— 下面这段「阻断型」的前提不成立,别照它排顺序】
--   本文件原写:「阻断型:必须先于部署 jar。列不存在 → insert 报 1054 → saveLog 的
--   catch 吞掉 → 审计日志全表静默丢失」。**实测生产库:token_in / token_out 两列早就存在**,
--   且定义恰好就是本份想要的(int / DEFAULT 0 / 可空)。所以 insert 不会 1054,
--   **本份不是阻断型,也不必先于 jar**。真正待落的只有第三条 `ADD KEY idx_ai_gen_trace`
--   (纯读性能,跑不跑都不影响 jar 起来)。
--
--   ⚠️ 但那两列**不是任何迁移文件加的** —— 全仓(含所有分支)搜不到一条
--   `ALTER TABLE ai_generation_log`,建表脚本 migration_ai_generation_log.sql 里也只有
--   token_est。⇒ 是有人在某个时点**手工改了生产库、没留迁移文件**。定义碰巧是对的,
--   所以无害;但这意味着**生产 schema 与仓库记录已经不完全一致**,别把仓库当唯一真相。
--   (库里列注释是 '输入token',而本份想写 '输入token估算(systemPrompt+userPrompt)…' ——
--    注释不同正是「不是本份跑的」的证据。)
--
--   ⇒ 执行时:**跳过下面两条 ADD COLUMN**(会报 1060),只跑第三条 ADD KEY。
--     这正是【幂等】段和 P1 预检早就写好的处理方式,照做即可。
--
-- 【幂等】
--   ⚠️ **不幂等**。ADD COLUMN 重复执行报 1060,ADD KEY 重复执行报 1061。
--   生产库 MySQL 8 **没有 `ADD COLUMN IF NOT EXISTS`**(那是 MariaDB 专属,在 MySQL 8 报 1064)。
--   幂等靠下面的**只读预检**:先查、已存在就跳过对应那条。
--
-- 【可逆】—— ⚠️ 仅适用于「jar 已回退后整体拆除」。jar 还在跑时删列 = insert 立刻 1054。
--   ALTER TABLE ai_generation_log DROP INDEX idx_ai_gen_trace;
--   ALTER TABLE ai_generation_log DROP COLUMN token_in, DROP COLUMN token_out;
--
-- 【风险】—— **低**
--   · 两列均 `DEFAULT 0` 且可空,存量行自动补 0,不阻塞任何现有写入。
--   · **不写 `AFTER xxx`** —— 猜列名会报 1054,列的物理位置对本表毫无意义。
--   · 加索引会短暂占用 IO;此表是纯审计流水,写多读少,量级小,可在业务时段跑。
--   · ⚠️ 存量行的 token_in/token_out 会是 0 而不是「未知」——历史数据本就没记,
--     0 在这里的含义是「这一行早于本次迁移」,别把它当成真的零 token。
--     要区分就看 create_time 早于本次执行时间的行。
--
-- 【执行方式】
--   · 第一步只读预检 → 第二步三条 DDL 逐条执行,失败即停 → 第三步回读断言。
--   · 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:只读预检 → 用户确认 → 才执行。


-- ======================= 第一步:只读预检(只读,可放心跑) =======================
--
-- P0【前置】表在不在:
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generation_log';
--   · 0 行 → **停**,先跑 migration_ai_generation_log.sql(建表)。
--
-- P1【必看】本份的两列是否已存在:
--   SELECT COLUMN_NAME, COLUMN_DEFAULT, IS_NULLABLE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generation_log'
--      AND COLUMN_NAME IN ('token_in', 'token_out');
--   · 0 行 → 正常,两条 ADD COLUMN 都执行。
--   · 已看到 → 已跑过,划掉对应那条(再跑报 1060)。
--
-- P2【必看】trace_id 索引是否已存在:
--   SHOW INDEX FROM ai_generation_log WHERE Key_name = 'idx_ai_gen_trace';
--   · 0 行 → 正常,执行第三条。
--   · 有行 → 已跑过,跳过(再跑报 1061)。
--
-- P3【存量盘点】这张表现在多大(决定加索引要多久):
--   SELECT COUNT(*) FROM ai_generation_log;
--   · 预期很小(AI 网关刚上线不久)。若意外数到百万级,加索引改到低峰跑。


-- ======================= 第二步:DDL(三条,逐条执行,失败即停) =======================

-- 输入侧 token:今天被 saveLog set 了但一路丢弃。
ALTER TABLE ai_generation_log
    ADD COLUMN token_in INT DEFAULT 0 COMMENT '输入token估算(systemPrompt+userPrompt)。⚠️本次迁移前的存量行恒为0=历史没记,非真实零token';

-- 输出侧 token:同上。与 token_in 拆开才能回答「prompt 太长 vs 模型话太多」。
ALTER TABLE ai_generation_log
    ADD COLUMN token_out INT DEFAULT 0 COMMENT '输出token估算(模型返回文本)。token_est=token_in+token_out,保留不动以免动摇存量口径';

-- trace_id 精确搜:修完断链后,log.trace_id 才第一次能与
-- ai_merchant_match_snapshot.trace_id / 前端拿到的 traceId 对上,需要索引支撑按 traceId 查链路。
ALTER TABLE ai_generation_log
    ADD KEY idx_ai_gen_trace (trace_id);


-- ======================= 第三步:回读断言(必须真跑 —— 回执 ≠ 观测) =======================
--
-- A1 两列真的建出来了:
--   SELECT COLUMN_NAME, DATA_TYPE, COLUMN_DEFAULT, IS_NULLABLE
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generation_log'
--      AND COLUMN_NAME IN ('token_in', 'token_out')
--    ORDER BY COLUMN_NAME;
--   ✅ 期望**恰好 2 行**:
--        token_in  | int | 0 | YES
--        token_out | int | 0 | YES
--   ❌ 少于 2 行 → DDL 没全跑成,**别部署 jar**(会静默丢审计日志)。
--
-- A2 索引真的建出来了:
--   SHOW INDEX FROM ai_generation_log WHERE Key_name = 'idx_ai_gen_trace';
--   ✅ 期望**恰好 1 行**,Column_name = 'trace_id',Non_unique = 1。
--   ❌ 0 行 → 第三条没跑成。不阻断部署(只影响查询快慢),但要补跑。
--
-- A3 存量行被正确补默认值(表非空时才有意义):
--   SELECT COUNT(*) AS total,
--          SUM(token_in  IS NULL) AS null_in,
--          SUM(token_out IS NULL) AS null_out
--     FROM ai_generation_log;
--   ✅ 期望 null_in = 0 且 null_out = 0(存量行全部补成 0)。
--
-- A4【部署 jar 之后再跑】链路真的接通了 —— 这条才是「观测」,前三条只是「回执」:
--   SELECT trace_id, token_in, token_out, token_est, provider, create_time
--     FROM ai_generation_log
--    ORDER BY id DESC LIMIT 5;
--   ✅ 期望:新产生的行 token_in/token_out **均 > 0**(正常路径),
--      且 token_in + token_out = token_est。
--   ❌ 新行仍然全是 0 → Mapper 没生效 / jar 没部署到位。
--   ✅ 并且:拿前端返回的 traceId 去查,**必须查得到对应那一行**:
--        SELECT COUNT(*) FROM ai_generation_log WHERE trace_id = '<前端拿到的traceId>';
--      期望 = 1。**这条查不到 = 断链没修好**,前三条断言全绿也不算数。
