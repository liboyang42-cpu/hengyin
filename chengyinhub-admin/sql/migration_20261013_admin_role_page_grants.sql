-- F-W4-01: an environment rebuilt from the repository has the canonical C/M menu rows
-- (migration_20261012_missing_business_menu) but the four back-office roles have no page grants,
-- because their baseline matrix (migration_web_admin_roles_20260719) predates the 2026-08-18 snapshot.
-- Operators, auditors, finance and support therefore see only the home page and every route 404s.
--
-- Scope (conservative, insert-only):
--   * Only roles cy_operator / cy_auditor / cy_support / cy_finance that currently hold NO page (menu_type='C')
--     grant at all. A role with any page grant has been configured by an operator and is left untouched,
--     so production roles (which already have pages) are a no-op.
--   * Re-applies exactly the 20260719 matrix (business perms only, no system menus, same financial/PII
--     exclusions), then fills the M/C ancestors of what was granted. Never deletes or narrows a grant.
--   * role_id 1 (admin) and role 2 (common) are not touched.
SET @now = NOW();

DROP TEMPORARY TABLE IF EXISTS tmp_cy_fresh_admin_roles;
CREATE TEMPORARY TABLE tmp_cy_fresh_admin_roles(role_id BIGINT PRIMARY KEY, role_key VARCHAR(100) NOT NULL) ENGINE=InnoDB;
INSERT INTO tmp_cy_fresh_admin_roles(role_id, role_key)
SELECT r.role_id, r.role_key FROM sys_role r
WHERE r.role_key IN ('cy_operator','cy_auditor','cy_support','cy_finance') AND r.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
                  WHERE rm.role_id = r.role_id AND m.menu_type = 'C');

-- 运营：全量业务菜单，排除资金/隐私高风险动作（同 20260719）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_fresh_admin_roles t JOIN sys_menu m
WHERE t.role_key = 'cy_operator'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND m.perms NOT LIKE '%refund%:review%' AND m.perms NOT LIKE '%withdraw%:review%'
  AND m.perms NOT LIKE '%withdraw%:audit%' AND m.perms NOT LIKE '%withdraw%:viewpii%'
  AND m.perms NOT LIKE '%cashwithdraw%:manage%' AND m.perms NOT LIKE '%:pii'
  AND m.perms NOT LIKE '%settlement%:confirm%' AND m.perms NOT LIKE '%payout%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);

-- 审核：只授业务域审核相关动作及必要查询，不授系统、edit/remove 或资金动作。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_fresh_admin_roles t JOIN sys_menu m
WHERE t.role_key = 'cy_auditor'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%audit%' OR m.perms LIKE '%review%' OR m.perms LIKE '%:query' OR m.perms LIKE '%:list')
  AND m.perms NOT LIKE '%settlement%' AND m.perms NOT LIKE '%withdraw%' AND m.perms NOT LIKE '%refund%' AND m.perms NOT LIKE '%payout%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);

-- 客服：业务查询/列表/详情与售后发起；不授系统菜单或业务事实写入。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_fresh_admin_roles t JOIN sys_menu m
WHERE t.role_key = 'cy_support'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%:query' OR m.perms LIKE '%:list' OR m.perms LIKE '%:detail' OR m.perms LIKE '%refund%:create')
  AND m.perms NOT LIKE '%:remove' AND m.perms NOT LIKE '%:delete' AND m.perms NOT LIKE '%:edit'
  AND m.perms NOT LIKE '%:review' AND m.perms NOT LIKE '%:audit' AND m.perms NOT LIKE '%:confirm'
  AND m.perms NOT LIKE '%:payout' AND m.perms NOT LIKE '%:manage' AND m.perms NOT LIKE '%:viewpii' AND m.perms NOT LIKE '%:pii'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);

-- 财务：业务资金域及必要对象查询；不授系统菜单或内容/供给编辑。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_fresh_admin_roles t JOIN sys_menu m
WHERE t.role_key = 'cy_finance'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%refund%' OR m.perms LIKE '%withdraw%' OR m.perms LIKE '%settlement%' OR m.perms LIKE '%payout%'
       OR m.perms LIKE '%:query' OR m.perms LIKE '%:list')
  AND m.perms NOT LIKE '%:add' AND m.perms NOT LIKE '%:edit' AND m.perms NOT LIKE '%:remove'
  AND m.perms NOT LIKE '%:delete' AND m.perms NOT LIKE '%:create'
  AND m.perms NOT LIKE 'business:merchant:%' AND m.perms NOT LIKE 'business:club:%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);

-- 反向补齐祖先(按钮→页面→目录→域)，否则动态路由树缺父节点。九域导航最多四层，执行四次幂等扩展。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_fresh_admin_roles t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_fresh_admin_roles t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_fresh_admin_roles t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_fresh_admin_roles t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);

-- 自检：本次补授的角色都必须至少拿到一个页面；没有被补授的角色(已有页面授权)不在此断言内。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0 FROM tmp_cy_fresh_admin_roles t
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
                  WHERE rm.role_id = t.role_id AND m.menu_type = 'C');

-- 自检：补授不越过 20260719 的边界——四个角色都不得持有系统/监控/工具菜单或财务敏感动作之外的越权项。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0 FROM sys_role_menu rm JOIN tmp_cy_fresh_admin_roles t ON t.role_id = rm.role_id
JOIN sys_menu m ON m.menu_id = rm.menu_id
WHERE m.menu_type IN ('C','F') AND (m.perms LIKE 'system:%' OR m.perms LIKE 'monitor:%' OR m.perms LIKE 'tool:%');

DROP TEMPORARY TABLE tmp_cy_fresh_admin_roles;
