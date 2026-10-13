-- D-02 通道A「提现出金管理 + 人工补单」后台菜单与权限。
-- menu_id 自增(与既有 menu 迁移一致);幂等:按 perms 唯一性 NOT EXISTS 守卫,可重复执行。
-- 挂在「用户提现申请」同父级之下。

SET @parent := (SELECT parent_id FROM (SELECT parent_id FROM sys_menu WHERE perms = 'business:memberwithdrawal:list' LIMIT 1) p);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '提现出金管理', IFNULL(@parent, 1), 20, 'cashwithdraw', 'business/cashwithdraw/index', 1, 0, 'C', '0', '0', 'business:cashwithdraw:list', 'money', 'admin', sysdate(), 'D-02 通道A提现出金管理+人工补单队列'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:cashwithdraw:list') t);

SET @m := (SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:cashwithdraw:list' LIMIT 1) t);

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time)
SELECT '提现详情', @m, 1, 'F', '0', '0', 'business:cashwithdraw:query', 'admin', sysdate()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:cashwithdraw:query') t);

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time)
SELECT '提现人工处理', @m, 2, 'F', '0', '0', 'business:cashwithdraw:manage', 'admin', sysdate()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:cashwithdraw:manage') t);
