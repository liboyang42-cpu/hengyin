-- Restore the existing club-payee maker/checker split without rewriting historical SQL.
-- Fresh schema replay excludes the old pure-DML page initializer by its exact fingerprint.
-- Existing accounts or roles with a page keep their other grants; only the approved payee pairs change.
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_payee_guard;
DROP TEMPORARY TABLE IF EXISTS tmp_cy_payee_roles;
DROP TEMPORARY TABLE IF EXISTS tmp_cy_payee_menus;
DROP TEMPORARY TABLE IF EXISTS tmp_cy_payee_fresh;
DROP TEMPORARY TABLE IF EXISTS tmp_cy_payee_preserved;
CREATE TEMPORARY TABLE tmp_cy_payee_guard(id INT PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE tmp_cy_payee_roles(role_key VARCHAR(100) PRIMARY KEY, role_id BIGINT NOT NULL UNIQUE) ENGINE=InnoDB;
CREATE TEMPORARY TABLE tmp_cy_payee_menus(perms VARCHAR(100) PRIMARY KEY, menu_id BIGINT NOT NULL UNIQUE) ENGINE=InnoDB;
CREATE TEMPORARY TABLE tmp_cy_payee_fresh(role_id BIGINT PRIMARY KEY, role_key VARCHAR(100) NOT NULL) ENGINE=InnoDB;
CREATE TEMPORARY TABLE tmp_cy_payee_preserved(role_id BIGINT, menu_id BIGINT, PRIMARY KEY(role_id,menu_id)) ENGINE=InnoDB;
INSERT INTO tmp_cy_payee_guard(id) VALUES(1);
INSERT INTO tmp_cy_payee_roles(role_key,role_id)
SELECT role_key,role_id FROM sys_role
WHERE del_flag='0' AND role_key IN ('cy_operator','cy_auditor','cy_support','cy_finance') FOR UPDATE;
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM tmp_cy_payee_roles) <> 4;
INSERT INTO tmp_cy_payee_menus(perms,menu_id)
SELECT perms,menu_id FROM sys_menu
WHERE perms IN ('business:clubpayee:verify','business:clubpayee:payout','business:clubpayable:confirm','business:clubhours:correct') FOR UPDATE;
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM tmp_cy_payee_menus) <> 4;
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE
 (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:clubpayee:verify','business:clubpayee:payout','business:clubpayable:confirm','business:clubhours:correct')
  AND menu_type='F' AND status='0' AND visible='0') <> 4;
-- Keep the four source-declared exceptional grants if they already exist.
INSERT INTO tmp_cy_payee_preserved(role_id,menu_id)
SELECT rm.role_id,rm.menu_id FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE r.del_flag='0' AND ((r.role_key='cy_operator' AND m.perms='business:clubpayee:payout')
 OR (r.role_key='cy_finance' AND m.perms IN ('business:clubpayee:verify','business:clubpayable:confirm','business:clubhours:correct')));
-- Capture before filling ancestors; an account-bearing installation is never bulk-initialized.
INSERT INTO tmp_cy_payee_fresh(role_id,role_key)
SELECT r.role_id,r.role_key FROM tmp_cy_payee_roles r
WHERE NOT EXISTS (SELECT 1 FROM sys_user)
 AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=r.role_id AND m.menu_type='C');
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_payee_fresh t JOIN sys_menu m
WHERE t.role_key = 'cy_operator'
  AND m.perms <> 'business:clubpayee:verify'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND m.perms NOT LIKE '%refund%:review%' AND m.perms NOT LIKE '%withdraw%:review%'
  AND m.perms NOT LIKE '%withdraw%:audit%' AND m.perms NOT LIKE '%withdraw%:viewpii%'
  AND m.perms NOT LIKE '%cashwithdraw%:manage%' AND m.perms NOT LIKE '%:pii'
  AND m.perms NOT LIKE '%settlement%:confirm%' AND m.perms NOT LIKE '%payout%'
  -- 0719 之后新增的资金/导出类动作同样不授运营:确认、结算生成、冻结、冷静期配置、余额/提现写入、导出。
  AND m.perms NOT LIKE '%:confirm%' AND m.perms NOT LIKE '%:settle%' AND m.perms NOT LIKE '%:generate%'
  AND m.perms NOT LIKE '%:export%' AND m.perms NOT LIKE '%:freeze%' AND m.perms NOT LIKE '%cooldown%'
  AND m.perms NOT LIKE '%settlementrule:%' AND m.perms NOT LIKE '%clawback%'
  AND NOT (m.perms LIKE '%memberbalance%' AND m.perms NOT LIKE '%:list' AND m.perms NOT LIKE '%:query')
  AND NOT (m.perms LIKE '%memberwithdrawal%' AND m.perms NOT LIKE '%:list' AND m.perms NOT LIKE '%:query')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_payee_fresh t JOIN sys_menu m
WHERE t.role_key = 'cy_auditor'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%audit%' OR m.perms LIKE '%review%' OR m.perms LIKE '%:query' OR m.perms LIKE '%:list')
  AND m.perms NOT LIKE '%settlement%' AND m.perms NOT LIKE '%withdraw%' AND m.perms NOT LIKE '%refund%' AND m.perms NOT LIKE '%payout%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_payee_fresh t JOIN sys_menu m
WHERE t.role_key = 'cy_support'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%:query' OR m.perms LIKE '%:list' OR m.perms LIKE '%:detail' OR m.perms LIKE '%refund%:create')
  AND m.perms NOT LIKE '%:remove' AND m.perms NOT LIKE '%:delete' AND m.perms NOT LIKE '%:edit'
  AND m.perms NOT LIKE '%:review' AND m.perms NOT LIKE '%:audit' AND m.perms NOT LIKE '%:confirm'
  AND m.perms NOT LIKE '%:payout' AND m.perms NOT LIKE '%:manage' AND m.perms NOT LIKE '%:viewpii' AND m.perms NOT LIKE '%:pii'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT t.role_id, m.menu_id FROM tmp_cy_payee_fresh t JOIN sys_menu m
WHERE t.role_key = 'cy_finance'
  AND m.perms <> 'business:clubpayee:payout'
  AND m.status = '0' AND m.visible = '0' AND m.perms LIKE 'business:%'
  AND (m.perms LIKE '%refund%' OR m.perms LIKE '%withdraw%' OR m.perms LIKE '%settlement%' OR m.perms LIKE '%payout%'
       OR m.perms LIKE '%:query' OR m.perms LIKE '%:list')
  AND m.perms NOT LIKE '%:add' AND m.perms NOT LIKE '%:edit' AND m.perms NOT LIKE '%:remove'
  AND m.perms NOT LIKE '%:delete' AND m.perms NOT LIKE '%:create'
  AND m.perms NOT LIKE 'business:merchant:%' AND m.perms NOT LIKE 'business:club:%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = t.role_id AND x.menu_id = m.menu_id);
-- The earlier compensation/hour-correction migrations expressly grant these two finance actions.
-- Reaffirm them only for newly initialized roles, never for an existing configured role.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT t.role_id,m.menu_id FROM tmp_cy_payee_fresh t JOIN sys_menu m
WHERE t.role_key='cy_finance' AND m.perms IN ('business:clubpayable:confirm','business:clubhours:correct')
 AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id=t.role_id AND x.menu_id=m.menu_id);
-- Restore exactly the two approved positive payee pairs for both fresh and existing installations.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM tmp_cy_payee_roles r JOIN tmp_cy_payee_menus m
WHERE ((r.role_key='cy_operator' AND m.perms='business:clubpayee:payout')
 OR (r.role_key='cy_finance' AND m.perms='business:clubpayee:verify'))
 AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
DELETE FROM sys_role_menu WHERE role_id = (SELECT role_id FROM tmp_cy_payee_roles WHERE role_key = 'cy_operator') AND menu_id = (SELECT menu_id FROM tmp_cy_payee_menus WHERE perms = 'business:clubpayee:verify');
DELETE FROM sys_role_menu WHERE role_id = (SELECT role_id FROM tmp_cy_payee_roles WHERE role_key = 'cy_finance') AND menu_id = (SELECT menu_id FROM tmp_cy_payee_menus WHERE perms = 'business:clubpayee:payout');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_payee_fresh t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_payee_fresh t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_payee_fresh t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm JOIN tmp_cy_payee_fresh t ON t.role_id = rm.role_id
JOIN sys_menu c ON c.menu_id = rm.menu_id JOIN sys_menu p ON p.menu_id = c.parent_id
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = p.menu_id);
-- Every assertion below executes inside the marked transaction: no schema-only DATA_ASSERT downgrade.
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE
 (SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
  WHERE r.del_flag='0' AND ((r.role_key='cy_operator' AND m.perms='business:clubpayee:payout')
    OR (r.role_key='cy_finance' AND m.perms='business:clubpayee:verify'))) <> 2;
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE EXISTS
 (SELECT 1 FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
  WHERE r.del_flag='0' AND ((r.role_key='cy_operator' AND m.perms='business:clubpayee:verify')
    OR (r.role_key='cy_finance' AND m.perms='business:clubpayee:payout')));
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE EXISTS
 (SELECT 1 FROM tmp_cy_payee_preserved e WHERE NOT EXISTS
  (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=e.role_id AND rm.menu_id=e.menu_id));
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE EXISTS
 (SELECT 1 FROM tmp_cy_payee_fresh t WHERE NOT EXISTS
  (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=t.role_id AND m.menu_type='C'));
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE EXISTS
 (SELECT 1 FROM sys_role_menu rm JOIN tmp_cy_payee_fresh t ON t.role_id=rm.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
  WHERE m.menu_type IN ('C','F') AND (m.perms LIKE 'system:%' OR m.perms LIKE 'monitor:%' OR m.perms LIKE 'tool:%'));
INSERT INTO tmp_cy_payee_guard(id) SELECT 1 WHERE EXISTS
 (SELECT 1 FROM sys_role_menu rm JOIN tmp_cy_payee_fresh t ON t.role_id=rm.role_id AND t.role_key='cy_operator'
  JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.perms <> 'business:clubpayee:payout' AND
  (m.perms LIKE '%:confirm%' OR m.perms LIKE '%:settle%' OR m.perms LIKE '%:generate%' OR m.perms LIKE '%:export%'
   OR m.perms LIKE '%:freeze%' OR m.perms LIKE '%cooldown%' OR m.perms LIKE '%payout%' OR m.perms LIKE '%clawback%'
   OR m.perms LIKE '%:pii' OR m.perms LIKE '%viewpii%'
   OR (m.perms LIKE '%memberbalance%' AND m.perms NOT LIKE '%:list' AND m.perms NOT LIKE '%:query')
   OR (m.perms LIKE '%memberwithdrawal%' AND m.perms NOT LIKE '%:list' AND m.perms NOT LIKE '%:query')));
DROP TEMPORARY TABLE tmp_cy_payee_preserved;
DROP TEMPORARY TABLE tmp_cy_payee_fresh;
DROP TEMPORARY TABLE tmp_cy_payee_menus;
DROP TEMPORARY TABLE tmp_cy_payee_roles;
DROP TEMPORARY TABLE tmp_cy_payee_guard;
