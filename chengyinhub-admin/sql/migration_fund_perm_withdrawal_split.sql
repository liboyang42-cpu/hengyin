-- D-04 提现权限拆分:business:memberwithdrawal:review 拆为
--   viewpii = 查看完整收款资料明文(payout 接口),audit = 审核通过/驳回。
-- 目的:「看明文收款资料」与「审核操作」分权,最小权限;明文读取仍走 @Log 审计。
-- 幂等:按 perms 唯一性 NOT EXISTS 守卫(derived-table 包裹避免 MySQL 1093),可重复执行。
-- 兼容:自动把两个新权限授予当前已持有 review 的角色,避免上线后财务瞬断(super-admin 本就绕过鉴权)。
-- 注意:menu_id 走自增(不显式指定),与既有 menu 迁移一致。

SET @rev_parent := (SELECT parent_id FROM (SELECT parent_id FROM sys_menu WHERE perms = 'business:memberwithdrawal:review' LIMIT 1) p);

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '查看提现收款资料明文', IFNULL(@rev_parent, 1), 5, 'F', '0', '0', 'business:memberwithdrawal:viewpii', 'admin', sysdate(), 'D-04 拆分:仅查看完整收款资料明文'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:memberwithdrawal:viewpii') t);

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '提现审核操作', IFNULL(@rev_parent, 1), 6, 'F', '0', '0', 'business:memberwithdrawal:audit', 'admin', sysdate(), 'D-04 拆分:仅审核通过/驳回'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:memberwithdrawal:audit') t);

-- 把两个新权限授予当前持有 review 的角色(去重幂等)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, nm.menu_id
FROM sys_role_menu rm
JOIN sys_menu m  ON rm.menu_id = m.menu_id AND m.perms = 'business:memberwithdrawal:review'
JOIN sys_menu nm ON nm.perms IN ('business:memberwithdrawal:viewpii', 'business:memberwithdrawal:audit')
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT role_id, menu_id FROM sys_role_menu) x
    WHERE x.role_id = rm.role_id AND x.menu_id = nm.menu_id
);
