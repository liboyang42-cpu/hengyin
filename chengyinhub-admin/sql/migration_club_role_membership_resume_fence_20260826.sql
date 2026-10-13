-- 会费恢复撤权栅栏：成员退出/移除/封禁/俱乐部解散后，未支付账单不得再返回或附着预支付参数。
-- 依赖：migration_club_role_membership_20260823.sql。
-- 本文件只增加一个可空时间戳；历史 PAYING 账单保持未封锁，随后由真实 access-end 事务写入。

-- 目标表必须是既有会费账单，且本迁移尚未执行；第三种结构在 DDL 前 fail closed。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice'
     AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice'
         AND column_name IN ('id', 'club_id', 'member_id', 'status', 'pay_params_json', 'version')) = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice'
         AND column_name = 'resume_blocked_at') = 0
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice'
         AND column_name = 'status' AND column_type = 'varchar(16)'
         AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*)
       FROM information_schema.table_constraints tc
       JOIN information_schema.check_constraints cc
         ON cc.constraint_schema = tc.constraint_schema
        AND cc.constraint_name = tc.constraint_name
       WHERE tc.constraint_schema = DATABASE()
         AND tc.table_name = 'club_membership_invoice'
         AND tc.constraint_name = 'chk_club_membership_invoice_status'
         AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES'
         AND LOWER(cc.check_clause) LIKE '%status%'
         AND LOWER(cc.check_clause) LIKE '%created%'
         AND LOWER(cc.check_clause) LIKE '%paying%'
         AND LOWER(cc.check_clause) LIKE '%paid%'
         AND LOWER(cc.check_clause) LIKE '%closed%') = 1
  AS assertion_ok;

ALTER TABLE club_membership_invoice
  ADD COLUMN resume_blocked_at DATETIME DEFAULT NULL
    COMMENT '成员关系结束后禁止恢复/附着预支付参数的线性化栅栏'
    AFTER pay_params_json;

-- 从新连接回读完整列合同，不能只依赖 ALTER exit 0。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice'
     AND column_name = 'resume_blocked_at'
     AND column_type = 'datetime'
     AND is_nullable = 'YES'
     AND column_default IS NULL
     AND extra = '') = 1
  AND (SELECT COUNT(*) FROM club_membership_invoice
       WHERE resume_blocked_at IS NOT NULL AND NOT (status <=> 'PAYING')) = 0
  AS assertion_ok;
