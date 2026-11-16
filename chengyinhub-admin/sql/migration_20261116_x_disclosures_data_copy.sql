-- ================= migration_20261116_x_disclosures_data_copy.sql =================
-- W7 x-disclosures (ONB-10, PIPL Art. 45): a member can ask for a copy of their personal information
-- (POST /api/user/data-copy/request); the request is a ticket the admin console lists and closes
-- (GET /business/dataCopy/list, PUT /business/dataCopy/{id}/status).
--
-- ROLLBACK: the old jar never reads the table or the menu rows. To undo, after the new jar is gone:
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE perms IN ('business:dataCopy:list','business:dataCopy:edit'));
--   DELETE FROM sys_menu WHERE perms = 'business:dataCopy:edit';
--   DELETE FROM sys_menu WHERE component = 'business/datacopy/index';
--   DROP TABLE IF EXISTS member_data_copy_request;
--
-- Personal data: member_id and the handling note. Account erasure deletes the member's requests
-- (MemberPrivacyErasureService). The response deadline is not stored: it is the created date plus
-- compliance.data-copy.response-working-days (default 15, Mon-Fri) and is computed on read.
--
-- Admin menu: one C row under 审核与风控 (audit-center) plus an F row for the status change. The C row is created
-- hidden (visible='1') because the Vue page business/datacopy/index does not exist yet; show it when the page ships.
-- No sys_role_menu grants: the owner assigns roles later; the super admin has access at once.
-- Idempotent: CREATE TABLE IF NOT EXISTS and NOT EXISTS guards on every menu insert.

CREATE TABLE IF NOT EXISTS member_data_copy_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DELIVERED/REJECTED',
    note VARCHAR(500) NULL COMMENT '处理备注；拒绝时写明原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_by BIGINT NULL COMMENT '最后处理的后台用户 sys_user.user_id',
    handled_at DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_member_data_copy_member_status (member_id, status),
    KEY idx_member_data_copy_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个人信息副本申请(PIPL 第45条)';

SET @cy_data_copy_parent := (SELECT menu_id FROM sys_menu WHERE menu_type = 'M' AND path = 'audit-center' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '个人信息副本申请', @cy_data_copy_parent, 57, 'data-copy', 'business/datacopy/index', 1, 0, 'C', '1', '0', 'business:dataCopy:list', 'documentation', 'migration', NOW(), 'ONB-10 PIPL 第45条;页面未上线前隐藏'
FROM DUAL WHERE @cy_data_copy_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/datacopy/index' OR perms = 'business:dataCopy:list');

SET @cy_data_copy_menu := (SELECT menu_id FROM sys_menu WHERE component = 'business/datacopy/index' AND menu_type = 'C' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '副本申请处理', @cy_data_copy_menu, 1, '#', '', 1, 0, 'F', '0', '0', 'business:dataCopy:edit', '#', 'migration', NOW(), ''
FROM DUAL WHERE @cy_data_copy_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:dataCopy:edit');

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 'member_data_copy_request') = 1
   AND (SELECT COUNT(*) FROM sys_menu WHERE perms = 'business:dataCopy:list') = 1
   AND (SELECT COUNT(*) FROM sys_menu WHERE perms = 'business:dataCopy:edit') = 1 AS member_data_copy_ready;
