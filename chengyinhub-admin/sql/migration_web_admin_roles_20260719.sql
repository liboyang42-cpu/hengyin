-- Web 后台四角色最小权限基线（幂等）。
-- 角色创建与菜单授权均按 role_key/perms 查找，不依赖生产自增 ID。
SET @now = NOW();

INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly,
                      status, del_flag, create_by, create_time, remark)
SELECT '日常运营', 'cy_operator', 10, '1', 1, 1, '0', '0', 'migration', @now, '城瘾后台日常运营：内容与供给，不含资金终审'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'cy_operator' AND del_flag = '0');

INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly,
                      status, del_flag, create_by, create_time, remark)
SELECT '审核风控', 'cy_auditor', 20, '1', 1, 1, '0', '0', 'migration', @now, '城瘾后台审核风控：统一审核台，不含资金与业务事实写入'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'cy_auditor' AND del_flag = '0');

INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly,
                      status, del_flag, create_by, create_time, remark)
SELECT '客服售后', 'cy_support', 30, '2', 1, 1, '0', '0', 'migration', @now, '城瘾后台客服：用户调证与售后发起，只读业务对象'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'cy_support' AND del_flag = '0');

INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly,
                      status, del_flag, create_by, create_time, remark)
SELECT '财务结算', 'cy_finance', 40, '1', 1, 1, '0', '0', 'migration', @now, '城瘾后台财务：资金查询、复核与结算，不含内容供给编辑'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'cy_finance' AND del_flag = '0');

SET @operator = (SELECT role_id FROM sys_role WHERE role_key = 'cy_operator' AND del_flag = '0' LIMIT 1);
SET @auditor = (SELECT role_id FROM sys_role WHERE role_key = 'cy_auditor' AND del_flag = '0' LIMIT 1);
SET @support = (SELECT role_id FROM sys_role WHERE role_key = 'cy_support' AND del_flag = '0' LIMIT 1);
SET @finance = (SELECT role_id FROM sys_role WHERE role_key = 'cy_finance' AND del_flag = '0' LIMIT 1);

-- 四角色基线是权威矩阵：先清空旧授权，再按明确的 business 权限重建。
-- 不能保留无 perms 的旧页面节点，否则曾经误授的系统配置页面会继续出现在动态路由中。
DELETE FROM sys_role_menu WHERE role_id IN (@operator, @auditor, @support, @finance);

-- 运营：全量业务菜单，排除资金/隐私高风险动作。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @operator, m.menu_id FROM sys_menu m
WHERE m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND m.perms NOT LIKE '%refund%:review%' AND m.perms NOT LIKE '%withdraw%:review%'
  AND m.perms NOT LIKE '%withdraw%:audit%' AND m.perms NOT LIKE '%withdraw%:viewpii%'
  AND m.perms NOT LIKE '%cashwithdraw%:manage%' AND m.perms NOT LIKE '%:pii'
  AND m.perms NOT LIKE '%settlement%:confirm%' AND m.perms NOT LIKE '%payout%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = @operator AND x.menu_id = m.menu_id);

-- 审核：只授业务域审核相关动作及必要查询，不授系统、edit/remove 或资金动作。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @auditor, m.menu_id FROM sys_menu m
WHERE m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%audit%' OR m.perms LIKE '%review%' OR m.perms LIKE '%:query' OR m.perms LIKE '%:list')
  AND m.perms NOT LIKE '%settlement%' AND m.perms NOT LIKE '%withdraw%' AND m.perms NOT LIKE '%refund%' AND m.perms NOT LIKE '%payout%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = @auditor AND x.menu_id = m.menu_id);

-- 客服：业务查询/列表/详情与售后发起；不授系统菜单或业务事实写入。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @support, m.menu_id FROM sys_menu m
WHERE m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%:query' OR m.perms LIKE '%:list' OR m.perms LIKE '%:detail' OR m.perms LIKE '%refund%:create')
  AND m.perms NOT LIKE '%:remove' AND m.perms NOT LIKE '%:delete' AND m.perms NOT LIKE '%:edit'
  AND m.perms NOT LIKE '%:review' AND m.perms NOT LIKE '%:audit' AND m.perms NOT LIKE '%:confirm'
  AND m.perms NOT LIKE '%:payout' AND m.perms NOT LIKE '%:manage' AND m.perms NOT LIKE '%:viewpii' AND m.perms NOT LIKE '%:pii'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = @support AND x.menu_id = m.menu_id);

-- 财务：业务资金域及必要对象查询；不授系统菜单或内容/供给编辑。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @finance, m.menu_id FROM sys_menu m
WHERE m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%refund%' OR m.perms LIKE '%withdraw%' OR m.perms LIKE '%settlement%' OR m.perms LIKE '%payout%'
       OR m.perms LIKE '%:query' OR m.perms LIKE '%:list')
  AND m.perms NOT LIKE '%:add' AND m.perms NOT LIKE '%:edit' AND m.perms NOT LIKE '%:remove'
  AND m.perms NOT LIKE '%:delete' AND m.perms NOT LIKE '%:create'
  AND m.perms NOT LIKE 'business:merchant:%' AND m.perms NOT LIKE 'business:club:%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = @finance AND x.menu_id = m.menu_id);

-- 按权限命中的页面/按钮反向补齐菜单祖先，否则角色虽有按钮权限，动态路由树却没有父节点。
-- 当前九域导航最多三层；连续三次幂等扩展可同时兼容更深存量树。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE rm.role_id IN (@operator, @auditor, @support, @finance)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE rm.role_id IN (@operator, @auditor, @support, @finance)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE rm.role_id IN (@operator, @auditor, @support, @finance)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);

SELECT role_id, role_name, role_key, status, del_flag
FROM sys_role WHERE role_key IN ('cy_operator','cy_auditor','cy_support','cy_finance') ORDER BY role_sort;
