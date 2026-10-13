-- 账号注销状态与申请记录。仅提交迁移草案；生产执行前必须预检并取得确认。
ALTER TABLE ums_member
    ADD COLUMN deregister_status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/PENDING/COMPLETED' AFTER del_flag,
    ADD COLUMN deregister_apply_time DATETIME NULL AFTER deregister_status,
    ADD COLUMN deregister_execute_after DATETIME NULL AFTER deregister_apply_time;

CREATE TABLE account_deregistration (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/CANCELLED/COMPLETED/FAILED',
    apply_time DATETIME NOT NULL,
    execute_after DATETIME NOT NULL,
    cancel_time DATETIME NULL,
    complete_time DATETIME NULL,
    failure_reason VARCHAR(500) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_deregister_member_status (member_id, status, apply_time),
    KEY idx_deregister_execute (status, execute_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号注销申请与冷静期执行记录';
