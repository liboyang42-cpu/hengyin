-- O-05 #4(2026-10-22):审核逐项驳回原因。主题/玩法模板驳回时,审核员可按条目(主题/章节/节点/玩法)给出问题,
-- 每条一行;作者在 /api/audit/timeline 的对应审核轮次里只读查看(按内容作者归属校验)。
-- 新表,无回填;幂等(CREATE TABLE IF NOT EXISTS)。本文件只生成,不直接执行:按 chengyin-db-migrate 流程只读预检 + 用户确认后落库。
-- 新 jar 只在开关 chengyin.audit.item-problems.enabled=true 时读写本表;开关默认关,表缺失时旧行为不受影响。
-- 回滚:DROP TABLE audit_problem;
CREATE TABLE IF NOT EXISTS audit_problem (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL COMMENT '所属审核任务 audit_task.id',
    record_id BIGINT NULL COMMENT '产生本条问题的审核记录 audit_record.id(驳回那一条)',
    biz_type VARCHAR(32) NOT NULL COMMENT '审核对象类型(TOPIC / TEMPLATE / TEMPLATE_LIBRARY)',
    biz_id BIGINT NOT NULL COMMENT '审核对象 id',
    owner_member_id BIGINT NULL COMMENT '内容作者(读侧归属校验用)',
    item_type VARCHAR(16) NOT NULL COMMENT '问题条目类型 TOPIC / CHAPTER / NODE / TEMPLATE',
    item_id BIGINT NULL COMMENT '问题条目 id;NULL 表示针对整个审核对象',
    reason_code VARCHAR(64) NULL COMMENT '固定清单原因编码(AuditReasonCodes),可空',
    reason_text VARCHAR(200) NOT NULL COMMENT '给作者看的逐项问题说明',
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_problem_task (task_id),
    KEY idx_audit_problem_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核逐项驳回原因(作者可读)';
