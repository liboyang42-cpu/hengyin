-- 回滚：主题广场推荐治理独立权限
-- 前置：接口代码已回滚/下线，且负责人确认不再需要该权限。

SET @topicPlazaMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND route_name = 'TopicPlaza'
      AND `query` = '{"scope":"plaza"}'
);
SET @plazaRecommendPermissionCount := (
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
SET @rollbackReady := IF(@plazaRecommendPermissionCount = 1, 1, 0);

DELETE FROM sys_role_menu
WHERE menu_id = @plazaRecommendPermissionMenu
  AND @rollbackReady = 1;

DELETE FROM sys_menu
WHERE menu_id = @plazaRecommendPermissionMenu
  AND @rollbackReady = 1;

-- 回读：预期 permission_count=1、rollback_ready=1、remaining_permission_count=0。
SELECT @plazaRecommendPermissionCount AS permission_count,
       @rollbackReady AS rollback_ready,
       (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:topic:plaza:recommend') AS remaining_permission_count;
