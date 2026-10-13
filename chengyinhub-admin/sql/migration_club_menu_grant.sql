-- 「俱乐部管理」菜单授权给指定后台角色
-- 说明：
--   1) 先执行过 migration_club.sql（它已把 俱乐部管理 菜单 + business:club:* 按钮写入 sys_menu）。
--   2) 超级管理员(role_key='admin', 通常 role_id=1)自动拥有全部菜单，无需执行本脚本。
--   3) 只有给“非超管的运营角色”授权时才需要本脚本。把 @role 改成目标角色ID(sys_role.role_id)。

SET @role := 2;   -- TODO: 改成你的目标角色ID（在后台「系统管理→角色」里看 role_id）

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @role, menu_id FROM sys_menu
WHERE perms IN (
    'business:club:list',
    'business:club:query',
    'business:club:add',
    'business:club:edit',
    'business:club:remove',
    'business:club:audit'
)
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- 授权后让对应账号重新登录后台即可看到「俱乐部管理」。
