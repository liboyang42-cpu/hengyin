-- P0-06 退款政策快照:cms_registration + 3 列
-- 来源:《城瘾代码侧_现状与改造施工图_TODO_2026-07-15》Phase 2 / P0-06
--
-- 【范围 —— 只做 ③ 的档位】
--   施工图 §4 必清栏原话:「P0-05/06/07/08/09/10(退款地基;**P0-06 只需 ③ 的档位**)」。
--   ③ 的档位 = 锚 oms_ticket.end_time,截止前且未核销 100% / 首核销转人工 / 截止后 0%
--   —— **两档,没有 30%**。30% 那档是 ① 专属(GUIDED_48H_100_30_0),而 ① 不在首轮(D12)。
--   ①/② 的档位码先留位不落码,等它们开闸再做。
--
-- 【背景 —— 和费率快照是同一个病】
--   RefundPolicy 现在**每次现取**票的 end_time / 活动的 start_date 来算截止点。但这些字段
--   卖了票之后还能改(oms_ticket 有后台 CRUD、updateOmsTicket 随时可改 end_time)⇒
--   **改一次票的有效期,追溯改写所有已售单的退款截止**。玩家买的时候是「7-20 前可退」,
--   运营把票的 end_time 改到 7-18,这张已售的票就凭空少了两天退款窗口,而且零告警。
--
--   这与刚做的 P0-28 批1(费率快照)是同一个病根:**定价/成交时固化的是结果,判定时却现取输入**。
--   业界口径:「三月的账单在四月改价之后也必须永远重现三月的算法」。退款政策同理 ——
--   **玩家买的时候答应他什么,就必须一直是什么**。
--
-- 【四列的分工】
--   refund_policy_code    政策标识(如 EXPLORE_END_100_0)。将来加档位/改规则靠加新 code,不改旧单。
--   refund_policy_version 政策版本号。同一个 code 的参数调整(比如 48h 改 72h)靠它区分。
--   refund_deadline       可退截止点快照(③ = 票的 end_time)。**这是「别再算了」的那一列**。
--   ⚠️ 不预留 anchor 列:③ 里 anchor 与 deadline 恒同值,存两遍没有信息量。① 的 30% 档将来确实需要
--      「离锚点多久」,但 ① 未排期,且**那时不存在需要回填的存量 ① 单** —— 真要用时一条 ALTER 即可,
--      现在建的话就是一列零读方的空列,还会让人以为 ① 已经实现了。
--
-- 【幂等】
--   3 个 ADD COLUMN **不幂等**(重复执行报 1060)。执行前跑 P1 预检。
--
-- 【可逆】—— ⚠️ 仅适用于「jar 已部署后整体回退」,不适用于本迁移中途失败
--   ALTER TABLE cms_registration DROP COLUMN refund_policy_code, DROP COLUMN refund_policy_version,
--       DROP COLUMN refund_deadline
--   回退顺序 = 先回 jar 再 DROP。
--
-- 【风险】
--   **低 —— 零行为变化**。三列全可空:NULL = 存量单 / 快照没打上 ⇒ 读侧回落现取(与今天完全一致)。
--   不回填:存量单的锚点无从追溯(票的 end_time 可能已经被改过,回填等于把「现在的值」
--   伪造成「当初的值」)。**判不清的留 NULL,禁止静默回填** —— 与 product_type 那份同一条铁律。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库。


-- ======================= 【执行方式】 =======================
-- · 三条按顺序逐条执行,失败即停。
-- · **中途失败无害,不要回滚**:新增列全部可空,jar 尚未部署 ⇒ 无代码读写。
--   正确处置 = 修好失败那条继续,别去跑【可逆】的 DROP。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】三个新列是否已存在:
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_registration'
--      AND COLUMN_NAME IN ('refund_policy_code','refund_policy_version','refund_deadline');
--   · 0 行 → 三条全执行。 · 有命中 → 逐条划掉已存在的(撞已存在列 = 1060)。
--
-- P2【锁表评估】
--   SELECT COUNT(*) FROM cms_registration;
--   SHOW CREATE TABLE cms_registration;   -- 看 ROW_FORMAT,决定 INSTANT 可不可用
--   三条都带 ALGORITHM=INSTANT ⇒ 不是 INSTANT 就硬报 1845 当场停,而不是静默全表重建。
--
-- P3【落库后自检】新单是否真的打上了快照(**这条最重要**):
--   SELECT id, registration_no, purchase_kind, refund_policy_code, refund_deadline, create_time
--     FROM cms_registration
--    WHERE create_time > '<部署时间>' ORDER BY id DESC LIMIT 10;
--   期望:**部署之后新建的单,这三列非空**。若仍全 NULL ⇒ 快照没打上,读侧一直在回落现取
--   ⇒ 本迁移白做、且没有任何测试会红(接线断言守的是源码,守不住「运行时真的写进去了」)。
--   ⚠️ 存量单(部署前的)这三列为 NULL 是**正常且预期**的,别去回填。


-- ======================= 第二步:DDL =======================

ALTER TABLE cms_registration
    ADD COLUMN refund_policy_code VARCHAR(32) NULL COMMENT '退款政策标识(如EXPLORE_END_100_0)。下单时快照,NULL=存量单,读侧回落现取', ALGORITHM=INSTANT;

ALTER TABLE cms_registration
    ADD COLUMN refund_policy_version INT NULL COMMENT '退款政策版本号。同一code的参数调整靠它区分。NULL同上', ALGORITHM=INSTANT;

ALTER TABLE cms_registration
    ADD COLUMN refund_deadline DATETIME NULL COMMENT '可退截止点快照(③=票end_time)。「别再算了」的那一列:锚点字段卖票后还能改,现取=追溯改写已售单的退款窗口', ALGORITHM=INSTANT;
