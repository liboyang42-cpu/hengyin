-- 探店日第3批：**已经在售/在跑/在结/已关**的 ③ FREE_EXPLORE 主题的历史处置。
-- 文件名保留 funds 字样：ci/funds_it_gate.py 的 REQUIRED_TRIGGER_PATHS 认
-- chengyinhub-admin/sql/migration_*funds*.sql，改名即资金复验不触发。
--
-- ★问题：四道开售闸（A4 收款人快照 / A1·A2 条款冻结）只挂在 PRICING→SELLING 这一次转移上。
--   任何在本批部署**之前**就进入 SELLING 的 ③ 主题，club_edition_compensation_terms 与
--   topic_sale_payee_snapshot 都是空 ⇒ Q / EXTRA / FINAL 六个写入口全部抛 EDITION_TERMS_MISSING
--   或 FINAL_PAYEE_SNAPSHOT_MISSING ⇒ 这些期永久不可用，且现场排障会把它误判成「配置没落」。
--
-- ★本迁移**不补快照**。开售当时的收款人版本、当时的成本行、当时的履约窗，今天都无法证明；
--   审计 2「数据迁移与回读」明令「历史 SELLING 主题没有审核记录时的读策略要明确标为
--   **历史豁免**，不能在后台读取时将其误判为已审核」。所以这里落的是一条**显式豁免事实**：
--   「这一期不走四个 A，因为它开售时这套机制还不存在」。
--
-- ★fail-closed 的形状：豁免只对**已经开过售**的主题成立，且只在它**确实没有**冻结条款时成立。
--   新主题、以及任何已有 terms 的主题，一行都不会被写进来 —— 也就永远绕不过开售闸。

CREATE TABLE IF NOT EXISTS club_edition_legacy_exemption (
    topic_id BIGINT NOT NULL,
    lifecycle INT NOT NULL COMMENT '登记豁免时的生命周期：3 SELLING / 4 RUNNING / 5 SETTLING / 6 CLOSED',
    selling_started_at DATETIME NULL COMMENT '开售时刻；它早于本批部署正是豁免成立的事实依据',
    reason VARCHAR(255) NOT NULL,
    decided_by VARCHAR(64) NOT NULL,
    decided_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='探店日四个A的历史豁免登记；只登记「开售早于本机制、且确无冻结条款」的期，不补任何伪快照';

-- 回填：ProductType.FREE_EXPLORE = 2（ProductType.java）；
-- TopicLifecycleState SELLING=3 / RUNNING=4 / SETTLING=5 / CLOSED=6（TopicLifecycleState.java）。
-- 三个 where 条件缺一不可：
--   ① product_type = 2       —— 只有 ③ 才有这四个 A；
--   ② lifecycle >= 3 且 selling_started_at 非空 —— 「已经开过售」是可证明的事实；
--   ③ 没有 terms 行           —— 有条款的期照常走正常链路，绝不被豁免掉。
INSERT INTO club_edition_legacy_exemption (topic_id, lifecycle, selling_started_at, reason, decided_by)
SELECT t.id, t.lifecycle, t.selling_started_at,
       '开售早于探店日四个A的开售闸上线，开售当时的收款人版本与成本版本无法证明，按历史豁免处理',
       'migration_20260816_tandianri_batch3'
FROM cms_topic t
WHERE t.product_type = 2
  AND t.del_flag <> 2
  AND t.lifecycle >= 3
  AND t.selling_started_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM club_edition_compensation_terms c WHERE c.topic_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM club_edition_legacy_exemption e WHERE e.topic_id = t.id);

-- 回读合同：执行后必须从对面读回为 1。
-- 断言的是「不存在第三种状态」：每一个已开售的 ③ 主题，要么有冻结条款、要么有显式豁免。
-- 返回 0 = 还有既不在闸内、也没被显式豁免的期 —— 那种期就是「六个入口报同一个错、
-- 现场排障误判成配置没落」的那一类，本迁移的目的正是把它清零。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM cms_topic t
WHERE t.product_type = 2
  AND t.del_flag <> 2
  AND t.lifecycle >= 3
  AND t.selling_started_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM club_edition_compensation_terms c WHERE c.topic_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM club_edition_legacy_exemption e WHERE e.topic_id = t.id);
