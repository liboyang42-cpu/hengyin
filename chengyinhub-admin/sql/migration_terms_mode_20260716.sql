-- P0-50 供给槽三档条款(D21):章节定档 + offer 带条款
-- 来源:《城瘾商业化_四种产品模式_定稿_2026-07-15》§4.2 D21;《城瘾代码侧_现状与改造施工图》P0-50
--
-- 【D21 是什么】
--   ③ 的商家参与方式从「权益一种」扩为三档:
--     TRAFFIC  引流档 —— 零承诺零结算,接待打卡即可
--     REVSHARE 计酬档 —— 零现金承诺,**按核销**计固定人头费 ¥X/人(MVP 形态)
--     PERK     权益档 —— 承诺权益(retail_value ≥ 章节门槛),最强获客
--   BD 现在手里是一个阶梯而不是一道门槛:引流档开口、计酬档成交、权益档留给主力店。
--
-- 【为什么档位挂在**章节**上,而不是只挂 offer】(2026-07-16 拍板)
--   商业文档全篇讲的是「权益档**章节**」「计酬/引流档**章节**」,三处口径都按章节走:
--     · D5 承诺分档 —— 详情页**按章节**展示一句承诺(权益档「可兑换权益」/ 其余「≥1 家可完成」)
--     · 感知价值公式 —— 只加总**权益档章节**
--     · quota/容量/安全垫 —— 仅**权益档章节**
--   若档位只挂 offer,一个章节里混着 PERK 和 TRAFFIC 商家时,上面三句**全都答不出来**:
--   承诺该显示哪句?感知价值算不算它?容量按 PERK 的 Σ 算——可玩家明明可以去 TRAFFIC 商家?
--   ⇒ **章节定档(口径),offer 带条款(该商家谈定的价),入驻时校验两者一致。**
--
--   offer 仍带 terms_mode,不是冗余:它是**入驻校验的对象**(offer 的档必须等于章节的档),
--   也是核销时算钱的依据。章节改档后,存量 offer 的档不会跟着变 —— 那正是要的:
--   已谈定的条款不被追溯改写(与 P0-06/P0-28 的快照同一条纪律)。
--
-- 【perk_id 必须放开为可空】
--   现在是 NOT NULL(migration_perk_ledger_20260716.sql:124)⇒ **TRAFFIC/REVSHARE 连行都插不进去**
--   (它们没有权益可引用)。放开后由 CHECK 保证「PERK 必须有 perk_id、其余必须没有」——
--   一行「TRAFFIC 但挂着 perk_id」的数据说不清它到底承诺没承诺权益。
--
-- 【为什么另起一份而不是改 perk_ledger 原文】
--   那份已经合进 master(PR#65)。**合并过的迁移永不回头改** —— 你可能随时去跑它,
--   改了就等于你跑的版本和当初审的版本不是同一个。另写一份,两种情况下顺序都对:
--   perk_ledger 先(建表),本份后(加列)。
--
-- 【幂等】
--   ⚠️ **不幂等**。ADD COLUMN 重复执行报 1060,MODIFY 重复执行无害但会重建校验。执行前跑 P1 预检。
--
-- 【可逆】—— ⚠️ 仅适用于「jar 已回退后整体拆除」
--   ALTER TABLE merchant_chapter_offer DROP CHECK ck_offer_terms;
--   ALTER TABLE merchant_chapter_offer DROP COLUMN terms_mode, DROP COLUMN per_head_fee;
--   ALTER TABLE merchant_chapter_offer MODIFY COLUMN perk_id BIGINT NOT NULL COMMENT '...';
--   ALTER TABLE cms_topic_chapter DROP CHECK ck_chapter_terms;
--   ALTER TABLE cms_topic_chapter DROP COLUMN terms_mode;
--   ⚠️ perk_id 收回 NOT NULL 前必须先删掉所有 perk_id IS NULL 的行,否则 MODIFY 报 1138。
--
-- 【风险】
--   **低 —— 零行为变化**。两个 terms_mode 都 DEFAULT 'PERK' ⇒ 存量行(若有)全部落在权益档,
--   与今天的唯一玩法完全一致。per_head_fee 可空,只有 REVSHARE 用得上。
--   ⚠️ **DEFAULT 'PERK' 是刻意从严**:PERK 是三档里唯一走 quota 链、唯一有容量约束的。
--   默认成 TRAFFIC 会让存量行凭空失去容量约束 = 静默超卖。
--
-- 【实测说明 —— 别把它当成在生产版本上验过】
--   本文件的 6 条 DDL + 9 条 CHECK 行为(PERK 必须有 perk_id / TRAFFIC 不得挂 perk_id /
--   REVSHARE 必须有 per_head_fee 且非负 / TRAFFIC 不得带 per_head_fee / 非法档位被拒 /
--   quota_total=0 不触发 ck_offer_quota)已在**本地 MySQL 9.7.1** 上逐条跑通。
--   ⚠️ **生产是 MySQL 8.x,不是 9.7**。上述行为自 8.0.16 起语义稳定,故判断可迁移 ——
--   但这是**推断,不是在生产版本上的观测**。P2 的版本闸 + P4 的 CHECK 探针**必须真跑**,
--   它们才是在你那台库上的证据。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
-- ⚠️ 部署顺序:migration_perk_ledger_20260716.sql **必须先跑**(本份依赖它建的表),再跑本份,再部署 jar。


-- ======================= 【执行方式】 =======================
-- · **六条**按顺序逐条执行,失败即停。
-- · 前置:migration_perk_ledger_20260716.sql 已落库(否则第 1 条就报 1146 表不存在)。


-- ======================= 第一步:只读预检 =======================
--
-- P0【前置】依赖的表在不在:
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_chapter_offer';
--   · 0 行 → **停**,先跑 migration_perk_ledger_20260716.sql。
--
-- P1【必看】本份的列是否已存在:
--   SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND ((TABLE_NAME='merchant_chapter_offer' AND COLUMN_NAME IN ('terms_mode','per_head_fee','perk_id'))
--        OR (TABLE_NAME='cms_topic_chapter' AND COLUMN_NAME='terms_mode'));
--   · 只看到 perk_id 且 IS_NULLABLE='NO' → 正常,六条全执行。
--   · 看到 terms_mode → 已跑过,逐条划掉已存在的。
--
-- P2【版本闸 —— 决定 CHECK 是真约束还是注释】
--   SELECT VERSION();
--   ⚠️ **必须 ≥ 8.0.16**。低于此版本 MySQL **只解析不执行** CHECK ⇒ 本份的 ck_offer_terms /
--   ck_chapter_terms 全是注释 ⇒「PERK 必须有 perk_id」「REVSHARE 必须有 per_head_fee」
--   这两条一致性**没有任何东西在保证**,只剩应用层。跑完用 P4 当场验。
--
-- P3【存量盘点】现在有多少 offer 行:
--   SELECT COUNT(*) FROM merchant_chapter_offer;
--   · 0 行 → perk_id 的 MODIFY 无风险。
--   · >0 行 → 它们会全部被 DEFAULT 成 PERK。确认这是对的(今天只有权益档一种玩法,应该是对的)。
--
-- P4【落库后自检 —— 同时是 CHECK 的版本探针,必须真跑】
--   -- 期望被 ck_offer_terms 拒(3819)。**没被拒 = 你的 MySQL < 8.0.16,CHECK 全是摆设**:
--   INSERT INTO merchant_chapter_offer (topic_id, chapter_id, merchant_id, terms_mode, perk_id)
--        VALUES (999999, 999999, 999999, 'TRAFFIC', 123);
--   -- 期望被拒(REVSHARE 必须有 per_head_fee):
--   INSERT INTO merchant_chapter_offer (topic_id, chapter_id, merchant_id, terms_mode, perk_id)
--        VALUES (999999, 999999, 999999, 'REVSHARE', NULL);
--   -- 跑完删掉试探行:
--   DELETE FROM merchant_chapter_offer WHERE topic_id = 999999;


-- ======================= 第二步:DDL =======================

-- 章节定档:决定对外承诺 / 感知价值算不算它 / 容量口径。
ALTER TABLE cms_topic_chapter
    ADD COLUMN terms_mode VARCHAR(16) NOT NULL DEFAULT 'PERK' COMMENT 'D21供给条款档:PERK权益档(承诺可兑换权益,走quota容量链)/REVSHARE计酬档(按核销付人头费,不设容量)/TRAFFIC引流档(零结算,不设容量)。默认PERK=从严:它是唯一有容量约束的档', ALGORITHM=INSTANT;

ALTER TABLE cms_topic_chapter
    ADD CONSTRAINT ck_chapter_terms CHECK (terms_mode IN ('TRAFFIC', 'REVSHARE', 'PERK'));

-- offer 带条款:该商家谈定的档与价。档必须与章节一致(应用层校验),价是条款快照。
ALTER TABLE merchant_chapter_offer
    ADD COLUMN terms_mode VARCHAR(16) NOT NULL DEFAULT 'PERK' COMMENT 'D21条款档,入驻时须与所属章节的terms_mode一致。章节改档不追溯改写存量offer——已谈定的条款不被追溯(同P0-06/P0-28快照纪律)', ALGORITHM=INSTANT;

ALTER TABLE merchant_chapter_offer
    ADD COLUMN per_head_fee DECIMAL(10,2) NULL COMMENT 'REVSHARE档:每核销人次的固定人头费(条款快照,复用coop shareMode=2语义)。仅REVSHARE非空。⚠️平台采购支出,非分账,零收付通依赖', ALGORITHM=INSTANT;

-- perk_id 放开可空 + 三档一致性。
-- ⚠️ 这条**不能带 ALGORITHM=INSTANT**:改可空性要重建表,不在 INSTANT 能做的范围内。
--    带上会报 `ERROR 1845 (0A000): ALGORITHM=INSTANT is not supported for this operation.
--    Try ALGORITHM=COPY/INPLACE.` —— 已实测(见文件头【实测说明】)。
--    ⚠️ 后果是它**不是 instant**:会锁表重建。此表当前预期为空/极小(P3 数一下),
--    走默认算法可接受;若 P3 数到大量行,改用 pt-online-schema-change,别在业务时段跑。
ALTER TABLE merchant_chapter_offer
    MODIFY COLUMN perk_id BIGINT NULL COMMENT '引用coop_perk.id(申报层)。⚠️仅PERK档非空——TRAFFIC/REVSHARE没有权益可引用,原NOT NULL会让它们连行都插不进去';

ALTER TABLE merchant_chapter_offer
    ADD CONSTRAINT ck_offer_terms CHECK (
        terms_mode IN ('TRAFFIC', 'REVSHARE', 'PERK')
        AND (terms_mode <> 'PERK' OR perk_id IS NOT NULL)
        AND (terms_mode = 'PERK' OR perk_id IS NULL)
        AND (terms_mode <> 'REVSHARE' OR (per_head_fee IS NOT NULL AND per_head_fee >= 0))
        AND (terms_mode = 'REVSHARE' OR per_head_fee IS NULL));
