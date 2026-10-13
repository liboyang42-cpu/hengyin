-- 合规唯一性守卫(对抗审查 #6/#7):
--   agreement_doc:每 doc_type 至多 1 条 status=1 生效版(防同类两条生效→同意留痕/展示文本错配)
--   account_deregistration:每 member 至多 1 条 PENDING(防并发双击建两条注销申请)
-- MySQL 无部分索引 → 生成列 + 唯一键。发新协议版必须先把旧版 status 置 0 再把新版置 1,
-- 否则唯一键冲突(这正是约束在强制正确顺序)。幂等:列/索引存在则跳过。
-- ★预检(有违约数据先清理,否则加唯一键失败):
--   SELECT doc_type,COUNT(*) FROM agreement_doc WHERE status=1 GROUP BY doc_type HAVING COUNT(*)>1;
--   SELECT member_id,COUNT(*) FROM account_deregistration WHERE status='PENDING' GROUP BY member_id HAVING COUNT(*)>1;

DROP PROCEDURE IF EXISTS cy_compliance_guard;
DELIMITER $$
CREATE PROCEDURE cy_compliance_guard()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'agreement_doc'
                     AND column_name = 'active_doc_type') THEN
        ALTER TABLE agreement_doc
            ADD COLUMN active_doc_type VARCHAR(32)
            GENERATED ALWAYS AS (CASE WHEN status = 1 THEN doc_type ELSE NULL END) VIRTUAL
            COMMENT '生效(status=1)时=doc_type,唯一约束保证每type仅1生效版';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'agreement_doc'
                     AND index_name = 'uk_agreement_active_type') THEN
        ALTER TABLE agreement_doc ADD UNIQUE KEY uk_agreement_active_type (active_doc_type);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'account_deregistration'
                     AND column_name = 'pending_member') THEN
        ALTER TABLE account_deregistration
            ADD COLUMN pending_member BIGINT
            GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN member_id ELSE NULL END) VIRTUAL
            COMMENT 'PENDING时=member_id,唯一约束防并发双申请';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'account_deregistration'
                     AND index_name = 'uk_dereg_pending_member') THEN
        ALTER TABLE account_deregistration ADD UNIQUE KEY uk_dereg_pending_member (pending_member);
    END IF;
END$$
DELIMITER ;
CALL cy_compliance_guard();
DROP PROCEDURE IF EXISTS cy_compliance_guard;
