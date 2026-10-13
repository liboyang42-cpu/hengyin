-- 只读预检：主题广场推荐治理独立权限
-- 前置：migration_topic_source_menu_20260722.sql 的 A0/A1/A2 已通过。
-- 预期：TopicPlaza C 菜单恰好 1 行；新 F 权限为 0 或恰好 1 行且挂在该菜单下。

SELECT menu_id, menu_name, parent_id, menu_type, path, component, `query`, route_name, perms, status
FROM sys_menu
WHERE (menu_type = 'C' AND component = 'business/topic/index'
       AND route_name = 'TopicPlaza' AND `query` = '{"scope":"plaza"}')
   OR perms = 'business:topic:plaza:recommend'
ORDER BY menu_id;

-- 若已存在权限，先核验有哪些非超管角色已显式授予；本迁移不会复制官方 topic:edit 角色。
SELECT role_id, menu_id
FROM sys_role_menu
WHERE menu_id IN (
    SELECT menu_id FROM sys_menu WHERE perms = 'business:topic:plaza:recommend'
)
ORDER BY role_id, menu_id;
