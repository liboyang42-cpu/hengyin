-- 只读预检：主题内容归属改判高风险权限
-- 顺序：先完成 migration_topic_source_menu_20260722.sql 的 A0/A1/A2 回读，
-- 再执行本预检与 migration_topic_source_menu_20260722_02_content_slot_permission.sql。
-- 预期：OfficialTopic C 菜单恰好 1 行；新 F 权限为 0 或恰好 1 行且挂在该菜单下。

SELECT menu_id, menu_name, parent_id, menu_type, path, component, `query`, route_name, perms, status
FROM sys_menu
WHERE (menu_type = 'C' AND component = 'business/topic/index' AND route_name = 'OfficialTopic')
   OR perms = 'business:topic:content-slot:edit'
ORDER BY menu_id;

-- 若已存在权限，先看有哪些非超管角色被显式授予；本迁移不会复制普通 topic:edit 角色。
SELECT role_id, menu_id
FROM sys_role_menu
WHERE menu_id IN (
    SELECT menu_id FROM sys_menu WHERE perms = 'business:topic:content-slot:edit'
)
ORDER BY role_id, menu_id;
