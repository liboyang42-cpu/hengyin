-- 把 status 列注释补上 3=已打款(migration_withdrawal_payout_reference_20260809.sql 引入了该终态,
-- 但没同步列注释,库里的注释从此与代码不一致,下一个读 schema 的人会被误导)。
--
-- ⚠️ MODIFY COLUMN 会用这里写的定义**整条替换**原定义 —— 漏写哪个属性就是静默删除哪个属性。
--    本条的定义逐字取自生产 SHOW CREATE TABLE,只改 COMMENT 尾部:
--        `status` int DEFAULT '0' COMMENT '状态：0待审核 1审核通过 2审核失败'
--    特别注意 DEFAULT '0' —— run_migration.py --describe 不打印默认值,照它写就会把默认值删掉。
--    status 上有 idx_status 索引;类型不变,索引不受影响。
-- ALGORITHM=INSTANT:只改注释,MySQL 8 支持秒级完成;若某天不再支持会**响亮报错**而不是
--    偷偷重建表,这正是要的行为。

-- 预检:确认当前注释里确实还没有「3已打款」(返回 1 行 = 需要执行;返回 0 行 = 已经补过了)
SELECT column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND column_name = 'status'
  AND column_comment NOT LIKE '%3已打款%';

ALTER TABLE ums_member_withdrawal
    MODIFY COLUMN status int DEFAULT '0' COMMENT '状态：0待审核 1审核通过 2审核失败 3已打款',
    ALGORITHM=INSTANT;
