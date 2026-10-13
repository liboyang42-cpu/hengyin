-- 8-07（阻断#20-A1）：后台首页 403 的授权缺口。
-- HomeController#selectHomeDataList 挂 @PreAuthorize("hasPermi('business:analytics:list')")，
-- 而生产唯一在用的非超管账号 system（user_id=100）是 role_id=2，未持有该权限 ⇒ 首页待审计卡片全不显示。
-- 本迁移只补 role 2 的 sys_role_menu 授权；接口权限口径、首页计数口径不在本迁移范围（见 P8-D7 20-A）。
-- 可重复执行：menu_id 从 sys_menu 现查，已有行 INSERT IGNORE 不报错；菜单行不存在时整句插 0 行，回读为 0 并红。

SET @analytics_menu := (
    SELECT menu_id
    FROM sys_menu
    WHERE perms = 'business:analytics:list'
    ORDER BY menu_id
    LIMIT 1
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, @analytics_menu
FROM dual
WHERE @analytics_menu IS NOT NULL;

-- 对端回读合同：执行后必须从 sys_role_menu 读回 role 2 的这条授权。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM sys_role_menu
WHERE role_id = 2 AND menu_id = @analytics_menu;
