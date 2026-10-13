-- 主题运营 IA：官方主题 / 主题广场双入口 —— 只读预检
--
-- 这是生产库只读脚本，不写 sys_menu / sys_role_menu。
-- 先确认当前主题 C 菜单、路径冲突与已授权角色，再执行
-- migration_topic_source_menu_20260722.sql。

-- P1：应恰好一行现有主题 C 菜单。若多行，先停止并人工确认哪一行是当前主入口。
SELECT menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name,
       visible, status, perms
FROM sys_menu
WHERE menu_type = 'C' AND component = 'business/topic/index'
ORDER BY menu_id;

-- P2：目标路径或路由名被别的菜单占用会导致动态路由冲突；任一非主题行都不得继续执行迁移。
SELECT menu_id, menu_name, parent_id, path, component, `query`, route_name
FROM sys_menu
WHERE path IN ('official-topic', 'topic-plaza')
   OR route_name IN ('OfficialTopic', 'TopicPlaza')
ORDER BY menu_id;

-- P3：记录当前已有主题入口授权的角色数。迁移后主题广场必须复制这批 role_id。
SELECT rm.role_id, COUNT(*) AS topic_menu_rows
FROM sys_role_menu rm
JOIN sys_menu m ON m.menu_id = rm.menu_id
WHERE m.menu_type = 'C' AND m.component = 'business/topic/index'
GROUP BY rm.role_id
ORDER BY rm.role_id;
