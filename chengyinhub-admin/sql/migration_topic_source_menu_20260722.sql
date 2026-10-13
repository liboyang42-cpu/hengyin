-- 主题运营 IA：官方主题 / 主题广场双入口
--
-- 类别：③ 数据迁移（sys_menu + sys_role_menu）。不跑不会报错，但运营入口不会出现。
-- 前置：先跑 _precheck_topic_source_menu_20260722.sql；若现有主题 C 菜单不是唯一一行，
-- 或 official-topic/topic-plaza 路由被非主题菜单占用，停止执行并人工确认。
--
-- 设计：
-- 1) 复用既有主题 C 菜单的 menu_id 作为“官方主题”，因此原有角色、按钮权限、审计链不丢失；
-- 2) 新增“主题广场”C 菜单，同组件 `business/topic/index`，但用唯一 route_name + JSON query
--    传 scope=plaza；前端据 CONTENT 槽过滤，绝不再按 topicType 分流；
-- 3) 历史待归属是两个入口内部的待办队列，不注册第三个 C 菜单；
-- 4) 仅复制“已有主题入口”的角色可见性给新广场入口，不向所有角色越权授权。
--
-- 风险：低（仅菜单/角色关联 DML）；但若不回读，成功回执不代表当前运营角色真的看得见两个入口。

-- ① 找到既有主题 C 菜单。候选必须恰好一行；半迁移或旧菜单重复时宁可不写，不能静默挑一行。
SET @legacyTopicMenuCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND (
          route_name = 'OfficialTopic'
          OR `query` = '{"scope":"official"}'
          OR COALESCE(`query`, '') IN ('', '{}')
      )
);
SET @legacyTopicMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND (
          route_name = 'OfficialTopic'
          OR `query` = '{"scope":"official"}'
          OR COALESCE(`query`, '') IN ('', '{}')
      )
);
SET @topicParent := (SELECT parent_id FROM sys_menu WHERE menu_id = @legacyTopicMenu LIMIT 1);
SET @topicOrder := (SELECT order_num FROM sys_menu WHERE menu_id = @legacyTopicMenu LIMIT 1);
SET @topicPerms := (SELECT perms FROM sys_menu WHERE menu_id = @legacyTopicMenu LIMIT 1);

-- 任一目标 path / route_name 已被其他 C 菜单占用，下面的 UPDATE/INSERT 都不会发生。
-- 不能只依赖人工预检：跳过预检时静默写出动态路由冲突，比显式不生效更危险。
SET @officialPathConflict := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND parent_id = @topicParent
      AND path = 'official-topic'
      AND menu_id <> @legacyTopicMenu
);
SET @officialRouteConflict := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND route_name = 'OfficialTopic'
      AND menu_id <> @legacyTopicMenu
);
SET @plazaPathConflict := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND parent_id <=> @topicParent
      AND path <=> 'topic-plaza'
      AND NOT (component <=> 'business/topic/index'
               AND route_name <=> 'TopicPlaza'
               AND `query` <=> '{"scope":"plaza"}')
);
SET @plazaRouteConflict := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND (
          route_name <=> 'TopicPlaza'
          OR (component <=> 'business/topic/index'
              AND `query` <=> '{"scope":"plaza"}')
      )
      AND NOT (parent_id <=> @topicParent
               AND path <=> 'topic-plaza'
               AND component <=> 'business/topic/index'
               AND route_name <=> 'TopicPlaza'
               AND `query` <=> '{"scope":"plaza"}')
);
SET @plazaExactCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND parent_id <=> @topicParent
      AND path <=> 'topic-plaza'
      AND component <=> 'business/topic/index'
      AND route_name <=> 'TopicPlaza'
      AND `query` <=> '{"scope":"plaza"}'
);
SET @menuRouteConflict := @officialPathConflict + @officialRouteConflict + @plazaPathConflict
    + @plazaRouteConflict + IF(@plazaExactCount > 1, 1, 0)
    + IF(@legacyTopicMenuCount <> 1, 1, 0);

-- ② 原 menu_id 变为官方主题。只改 IA 路由字段，保留 parent、排序、按钮子项和角色关联。
UPDATE sys_menu
SET menu_name = '官方主题',
    path = 'official-topic',
    `query` = '{"scope":"official"}',
    route_name = 'OfficialTopic'
WHERE menu_id = @legacyTopicMenu
  AND @menuRouteConflict = 0;

SET @officialTopicMenu := (
    SELECT menu_id
    FROM sys_menu
WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND route_name = 'OfficialTopic'
      AND `query` = '{"scope":"official"}'
      AND parent_id <=> @topicParent
      AND path <=> 'official-topic'
    LIMIT 1
);

-- ③ 新增主题广场入口。同一 component 必须用不同的 path / route_name，避免 TagsView 与动态路由碰撞。
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '主题广场', @topicParent, @topicOrder + 1, 'topic-plaza', 'business/topic/index', '{"scope":"plaza"}', 'TopicPlaza',
       1, 0, 'C', '0', '0', @topicPerms, 'guide', 'migration', NOW(),
       '俱乐部/会员主题监管与发现；与官方主题共用列表和主题360，来源由 CONTENT 槽过滤'
FROM dual
WHERE @officialTopicMenu IS NOT NULL
  AND @menuRouteConflict = 0
  AND @plazaExactCount = 0;

SET @topicPlazaMenu := (
    SELECT menu_id
    FROM sys_menu
    WHERE menu_type = 'C'
      AND parent_id <=> @topicParent
      AND path <=> 'topic-plaza'
      AND component <=> 'business/topic/index'
      AND route_name <=> 'TopicPlaza'
      AND `query` <=> '{"scope":"plaza"}'
    LIMIT 1
);

-- ④ 只复制原主题入口已有角色，避免新入口对未授权角色可见；重复执行不会产生重复关联。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, @topicPlazaMenu
FROM sys_role_menu rm
WHERE rm.menu_id = @officialTopicMenu
  AND @topicPlazaMenu IS NOT NULL
  AND @menuRouteConflict = 0
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_menu existing_mapping
      WHERE existing_mapping.role_id = rm.role_id
        AND existing_mapping.menu_id = @topicPlazaMenu
  );

-- ============================================================================
-- 回读断言（必跑；写入成功不等于两个入口已对正确角色可见）
-- ============================================================================

-- A0：legacy_topic_menu_count 必须为 1，migration_block_count 必须为 0；否则本脚本已 fail-closed 且没有写入。
SELECT @legacyTopicMenuCount AS legacy_topic_menu_count,
       @menuRouteConflict AS migration_block_count;

-- A1：期望 official / plaza 各一行，component 相同、route_name/path/query 各自唯一。
SELECT menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, perms
FROM sys_menu
WHERE menu_id IN (@officialTopicMenu, @topicPlazaMenu)
ORDER BY menu_id;

-- A2：期望 duplicate_count 全为 1；0 表示迁移没有生效，>1 表示动态路由可能冲突。
SELECT route_name, COUNT(*) AS duplicate_count
FROM sys_menu
WHERE route_name IN ('OfficialTopic', 'TopicPlaza')
GROUP BY route_name;

-- A2b：主题广场必须继承既有主题入口的权限标识；permission_mismatch 期望为 0。
SELECT COUNT(*) AS permission_mismatch
FROM sys_menu official_menu
JOIN sys_menu plaza_menu ON plaza_menu.menu_id = @topicPlazaMenu
WHERE official_menu.menu_id = @officialTopicMenu
  AND NOT (official_menu.perms <=> plaza_menu.perms);

-- A3：迁移前拥有旧主题入口的 role_id 必须全部拥有主题广场；missing_plaza_roles 期望为 0。
SELECT COUNT(*) AS missing_plaza_roles
FROM sys_role_menu official_mapping
WHERE official_mapping.menu_id = @officialTopicMenu
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_menu plaza_mapping
      WHERE plaza_mapping.role_id = official_mapping.role_id
        AND plaza_mapping.menu_id = @topicPlazaMenu
  );

-- A4：上线后用非超管运营账号重新登录后台：必须同时看到“官方主题”“主题广场”。
--     官方入口只显示 PLATFORM，广场只显示 CLUB/MEMBER；历史待归属横幅数量必须与 HISTORICAL 查询 total 一致。
