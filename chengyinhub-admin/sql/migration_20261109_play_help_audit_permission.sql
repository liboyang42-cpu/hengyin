-- qx-c1 #4（UG-12）：后台「提示阶梯配置摘要 + 单局帮助/跳过/净分明细」只读投影的独立查询权限。
-- 类别：③ 数据迁移（sys_menu，仅一行 F 按钮权限）。
-- 挂在「官方主题」菜单（business/topic/index，route_name=OfficialTopic）下；父菜单不存在或不唯一时不写入，
-- 回读断言随即失败（fail-closed）。权限已存在且形状一致时不重复写入（可重复执行）。
-- 故意不写 sys_role_menu：本权限授予 0 个角色；超级管理员走通配，其他角色须负责人审批后单独授予。
-- 接口只读：不返回答案、别名、提示正文；不改写任何对局事实。

SET @playHelpAuditParentCount := (
    SELECT COUNT(1) FROM sys_menu
    WHERE menu_type = 'C' AND component = 'business/topic/index' AND route_name = 'OfficialTopic');
SET @playHelpAuditParent := (
    SELECT MIN(menu_id) FROM sys_menu
    WHERE menu_type = 'C' AND component = 'business/topic/index' AND route_name = 'OfficialTopic');
SET @playHelpAuditExisting := (
    SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:playhelp:query');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '玩法帮助明细查询', @playHelpAuditParent, 95, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:playhelp:query', '#', 'migration', NOW(),
       'UG-12 只读：提示阶梯配置摘要与单局帮助/跳过/净分明细；不含答案、别名与提示正文'
FROM dual
WHERE @playHelpAuditParentCount = 1 AND @playHelpAuditExisting = 0;

-- 回读：恰好一行 F 权限挂在 OfficialTopic 下，且没有任何角色被授予。
-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:playhelp:query') = 1
  AND (SELECT COUNT(1) FROM sys_menu
        WHERE perms = 'business:playhelp:query' AND menu_type = 'F' AND parent_id <=> @playHelpAuditParent
          AND @playHelpAuditParentCount = 1) = 1
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms = 'business:playhelp:query') = 0
) AS play_help_audit_permission_ready;
