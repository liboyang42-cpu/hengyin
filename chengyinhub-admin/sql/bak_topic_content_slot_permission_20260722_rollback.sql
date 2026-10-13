-- 回滚：主题内容归属改判独立高风险权限
-- 前置：接口代码已回滚/下线，且审批方确认不再需要该权限。不会改动任何 CONTENT 槽历史记录。

SET @topicMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND route_name = 'OfficialTopic'
);
SET @contentSlotPermissionCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE parent_id <=> @topicMenu
      AND menu_type = 'F'
      AND path <=> ''
      AND component <=> ''
      AND `query` <=> ''
      AND route_name <=> ''
      AND perms = 'business:topic:content-slot:edit'
);
SET @contentSlotPermissionMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE parent_id <=> @topicMenu
      AND menu_type = 'F'
      AND path <=> ''
      AND component <=> ''
      AND `query` <=> ''
      AND route_name <=> ''
      AND perms = 'business:topic:content-slot:edit'
);
SET @rollbackReady := IF(@contentSlotPermissionCount = 1, 1, 0);

DELETE FROM sys_role_menu
WHERE menu_id = @contentSlotPermissionMenu
  AND @rollbackReady = 1;

DELETE FROM sys_menu
WHERE menu_id = @contentSlotPermissionMenu
  AND @rollbackReady = 1;

-- 回读：预期 permission_count=1、rollback_ready=1、remaining_permission_count=0。
SELECT @contentSlotPermissionCount AS permission_count,
       @rollbackReady AS rollback_ready,
       (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:topic:content-slot:edit') AS remaining_permission_count;
