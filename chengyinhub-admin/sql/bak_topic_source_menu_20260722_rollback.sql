-- 主题运营 IA：官方主题 / 主题广场双入口 —— 手工回滚（不进入 release manifest）
--
-- 只在 migration_topic_source_menu_20260722.sql 已执行、且其 A0/A1/A2 回读确认两个目标菜单存在时使用。
-- 执行前先运行 _precheck_topic_source_menu_20260722.sql，确认旧菜单原始值；本文件默认恢复已核验的
-- 旧基线（主题管理 / topic / 空 query / 空 route_name）。若预检不同，先改下面四个 @legacy* 变量，
-- 不要把未知生产菜单静默改回错误路由。

-- 只接受本迁移写出的精确双入口；任一目标不存在、重复或路径被后续人工修改时都 fail-closed。
SET @officialTopicMenuCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component <=> 'business/topic/index'
      AND route_name <=> 'OfficialTopic'
      AND `query` <=> '{"scope":"official"}'
      AND path <=> 'official-topic'
);
SET @officialTopicMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component <=> 'business/topic/index'
      AND route_name <=> 'OfficialTopic'
      AND `query` <=> '{"scope":"official"}'
      AND path <=> 'official-topic'
);
SET @topicParent := (SELECT parent_id FROM sys_menu WHERE menu_id = @officialTopicMenu LIMIT 1);
SET @topicPlazaMenuCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND parent_id <=> @topicParent
      AND path <=> 'topic-plaza'
      AND component <=> 'business/topic/index'
      AND route_name <=> 'TopicPlaza'
      AND `query` <=> '{"scope":"plaza"}'
);
SET @topicPlazaMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND parent_id <=> @topicParent
      AND path <=> 'topic-plaza'
      AND component <=> 'business/topic/index'
      AND route_name <=> 'TopicPlaza'
      AND `query` <=> '{"scope":"plaza"}'
);
SET @rollbackReady := IF(@officialTopicMenuCount = 1 AND @topicPlazaMenuCount = 1, 1, 0);

SET @legacyMenuName := '主题管理';
SET @legacyPath := 'topic';
SET @legacyQuery := '';
SET @legacyRouteName := '';

-- 先解绑新广场 C 菜单的角色，再删除该菜单；不会删除官方菜单及其既有 F 按钮权限。
DELETE rm FROM sys_role_menu rm
INNER JOIN sys_menu plaza_menu ON plaza_menu.menu_id = rm.menu_id
WHERE plaza_menu.menu_id = @topicPlazaMenu
  AND @rollbackReady = 1
  AND plaza_menu.parent_id <=> @topicParent
  AND plaza_menu.path <=> 'topic-plaza'
  AND plaza_menu.route_name <=> 'TopicPlaza'
  AND plaza_menu.component <=> 'business/topic/index'
  AND plaza_menu.`query` <=> '{"scope":"plaza"}';

DELETE FROM sys_menu
WHERE menu_id = @topicPlazaMenu
  AND @rollbackReady = 1
  AND parent_id <=> @topicParent
  AND path <=> 'topic-plaza'
  AND route_name <=> 'TopicPlaza'
  AND component <=> 'business/topic/index'
  AND `query` <=> '{"scope":"plaza"}';

-- 仅在官方菜单仍是本迁移写入的标识时恢复旧字段，避免覆盖后续人工菜单调整。
UPDATE sys_menu
SET menu_name = @legacyMenuName,
    path = @legacyPath,
    `query` = @legacyQuery,
    route_name = @legacyRouteName
WHERE menu_id = @officialTopicMenu
  AND @rollbackReady = 1
  AND parent_id <=> @topicParent
  AND path <=> 'official-topic'
  AND route_name <=> 'OfficialTopic'
  AND `query` <=> '{"scope":"official"}'
  AND component <=> 'business/topic/index';

-- 回读：rollback_ready 必须为 1；TopicPlaza 为 0；官方原 menu_id 恢复为预检确认的旧字段。
SELECT @officialTopicMenuCount AS official_topic_menu_count,
       @topicPlazaMenuCount AS topic_plaza_menu_count,
       @rollbackReady AS rollback_ready;

SELECT menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name
FROM sys_menu
WHERE menu_id = @officialTopicMenu
   OR route_name = 'TopicPlaza';
