-- D-02 原子防重:每个 member 同时只允许一条在途提现(status in 0,3)。
-- MySQL 无部分索引 → 用虚拟生成列(status in(0,3)时=member_id,否则 NULL)+ 唯一键实现。
-- 并发重复发起时第二条 insert 触发唯一键冲突,由 service 捕获 DuplicateKeyException 友好拦截。
-- 幂等:列/索引存在则跳过。
-- ★执行前预检:若已有同 member 的多条在途(0/3)重复单,加唯一键会失败,须先人工清理:
--   SELECT member_id, COUNT(*) c FROM member_cash_withdraw WHERE status IN (0,3) AND del_flag=0
--   GROUP BY member_id HAVING c > 1;

DROP PROCEDURE IF EXISTS cy_fund_add_dedup;
DELIMITER $$
CREATE PROCEDURE cy_fund_add_dedup()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw'
                     AND column_name = 'pending_member_key') THEN
        ALTER TABLE member_cash_withdraw
            ADD COLUMN pending_member_key BIGINT
            GENERATED ALWAYS AS (CASE WHEN status IN (0, 3) THEN member_id ELSE NULL END) VIRTUAL
            COMMENT '在途(0/3)时=member_id,用于唯一约束原子防重';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw'
                     AND index_name = 'uk_pending_member') THEN
        ALTER TABLE member_cash_withdraw ADD UNIQUE KEY uk_pending_member (pending_member_key);
    END IF;
END$$
DELIMITER ;
CALL cy_fund_add_dedup();
DROP PROCEDURE IF EXISTS cy_fund_add_dedup;
