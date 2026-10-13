-- 主题广场推荐治理独立权限
--
-- 依赖：必须先完成 migration_topic_source_menu_20260722.sql 的 A0/A1/A2 回读，
-- 因为本权限必须唯一挂在 TopicPlaza 菜单下。若主题广场菜单不存在或不唯一，本迁移 fail-closed 不写权限。
-- 类别：③ 数据迁移（sys_menu）。不跑不会报错，但非超管无法被显式授予广场推荐治理权限。
-- 前置：先执行 _precheck_topic_plaza_recommend_permission_20260723.sql。
--
-- 边界：推荐只影响广场发现位，不是普通内容编辑；不得复制 business:topic:edit 的角色映射，
-- 否则获得官方内容编辑权的角色会被静默扩大为可治理会员/俱乐部主题的角色。

SET @topicPlazaMenuCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND route_name = 'TopicPlaza'
      AND `query` = '{"scope":"plaza"}'
);
SET @topicPlazaMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND route_name = 'TopicPlaza'
      AND `query` = '{"scope":"plaza"}'
);

SET @plazaRecommendPermissionExactCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE parent_id <=> @topicPlazaMenu
      AND menu_type = 'F'
      AND path <=> ''
      AND component <=> ''
      AND `query` <=> ''
      AND route_name <=> ''
      AND perms = 'business:topic:plaza:recommend'
);
SET @plazaRecommendPermissionConflictCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE perms = 'business:topic:plaza:recommend'
      AND NOT (
          parent_id <=> @topicPlazaMenu
          AND menu_type = 'F'
          AND path <=> ''
          AND component <=> ''
          AND `query` <=> ''
          AND route_name <=> ''
      )
);
SET @plazaRecommendMigrationBlockCount := IF(@topicPlazaMenuCount <> 1, 1, 0)
    + @plazaRecommendPermissionConflictCount
    + IF(@plazaRecommendPermissionExactCount > 1, 1, 0);

-- 幂等插入：仅在主题广场菜单唯一且同权限未被其他菜单占用时写入。
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '主题广场推荐治理', @topicPlazaMenu, 90, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:topic:plaza:recommend', '#', 'migration', NOW(),
       '仅调整俱乐部/会员主题的发现推荐；不允许改写剧情、路线、图片说明或票价'
FROM dual
WHERE @plazaRecommendMigrationBlockCount = 0
  AND @plazaRecommendPermissionExactCount = 0;

-- 写后回读必须重新查询，不能复用插入前的 0 计数。
SET @plazaRecommendPermissionExactCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE parent_id <=> @topicPlazaMenu
      AND menu_type = 'F'
      AND path <=> ''
      AND component <=> ''
      AND `query` <=> ''
      AND route_name <=> ''
      AND perms = 'business:topic:plaza:recommend'
);
SET @plazaRecommendPermissionMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE parent_id <=> @topicPlazaMenu
      AND menu_type = 'F'
      AND path <=> ''
      AND component <=> ''
      AND `query` <=> ''
      AND route_name <=> ''
      AND perms = 'business:topic:plaza:recommend'
);

-- 故意不写 sys_role_menu：超级管理员有通配权限；非超管必须经负责人审批后单独授予本 F 权限。
-- 授权后要求目标账号重新登录，并以该账号分别验证 CLUB/MEMBER 允许、PLATFORM/无槽拒绝两条路径。

-- ============================================================================
-- 回读断言（必跑；写入成功不等于权限归属符合最小授权）
-- ============================================================================
-- A0 预期 topic_plaza_menu_count=1、migration_block_count=0、permission_exact_count=1。
SELECT @topicPlazaMenuCount AS topic_plaza_menu_count,
       @plazaRecommendMigrationBlockCount AS migration_block_count,
       @plazaRecommendPermissionExactCount AS permission_exact_count;

-- A1 预期仅一行 F 权限，且 parent_id 指向 TopicPlaza；perms 必须精确匹配接口注解。
SELECT menu_id, menu_name, parent_id, menu_type, perms, status, remark
FROM sys_menu
WHERE menu_id = @plazaRecommendPermissionMenu;

-- A2 审核显式授权对象。本迁移不新增 sys_role_menu；非超管授权只能在审批后出现。
SELECT rm.role_id, role.role_name, role.role_key
FROM sys_role_menu rm
JOIN sys_role role ON role.role_id = rm.role_id
WHERE rm.menu_id = @plazaRecommendPermissionMenu
ORDER BY rm.role_id;

-- 回滚见 bak_topic_plaza_recommend_permission_20260723_rollback.sql。
