-- 探店日第3批 E2：应付台账增加 TICKET_SOURCE 票源奖励 component。
--
-- 【类别】③ 要在库上跑（DDL）。代码只要先发，FINAL 插入第四笔应付就会被旧
-- ck_club_payable_component 以 ERROR 3819 拒绝，所以本迁移必须先于 jar。
--
-- 【前置】migration_20260816_tandianri_batch3_funds_club_compensation.sql 建立 club_payable
-- 及 ck_club_payable_component；依赖图在 ci/migration-dependencies.json 明示登记。
--
-- 【只改一件事】不新增金额列、不改幂等键、不改只追加/红冲约束；只扩展 component
-- 枚举。新金额口径由 Java 只读 topic_edition_funds(FINAL).g_earned 承重。
--
-- 【可逆】先确认不存在 TICKET_SOURCE 行，再用同一条 ALTER 把 CHECK 改回
-- ('FIXED','QUALITY','EXTRA')。已有第四类行时回滚会被 CHECK 正常拒绝，不能偷删台账。

ALTER TABLE club_payable
    DROP CHECK ck_club_payable_component,
    ADD CONSTRAINT ck_club_payable_component
        CHECK (component IN ('FIXED', 'QUALITY', 'EXTRA', 'TICKET_SOURCE'));

-- 回读的是 MySQL 自己持久化的 CHECK 定义，不是刚提交的 SQL 字符串。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
   AND MAX(UPPER(check_clause) LIKE '%TICKET_SOURCE%') = 1
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE()
  AND constraint_name = 'ck_club_payable_component';
