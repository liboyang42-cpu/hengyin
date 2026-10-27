-- ================= migration_20261027_orient_scoring_menu_grants.sql =================
-- OrientScoringAdminController / 主题页「计分申诉与争议」控制台使用 business:orientScoring:list 与
-- business:orientScoring:review，但 sys_menu 从未登记这两个权限 ⇒ 只有超管能用。
-- 本迁移只做两件事，均幂等、只插不删：
--   1) 在官方主题 C 菜单(component=business/topic/index, path=official-topic)下补两个 F 按钮；
--      父菜单按现有 component/path 查，不写死 menu_id。
--   2) owner 2026-10-27 拍板：两个权限都只授予 role_key='cy_operator'(运营管理员)，不授其他角色。
-- 前置任一不满足(官方主题 C 菜单不是恰好一行 / 同名权限已重复或不是 F)即整体中止，不做半截写入。
--
-- 落库前只读预检：
--   SELECT menu_id, path, route_name FROM sys_menu
--   WHERE menu_type='C' AND component='business/topic/index' AND path='official-topic';
--   SELECT menu_id, menu_type, perms FROM sys_menu
--   WHERE perms IN ('business:orientScoring:list','business:orientScoring:review');
--   SELECT role_id, role_key FROM sys_role WHERE role_key='cy_operator' AND del_flag='0';
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_orient_scoring_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_orient_scoring_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_orient_scoring_menu_guard(id) VALUES(1);
INSERT INTO tmp_cy_orient_scoring_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu
  WHERE menu_type='C' AND component='business/topic/index' AND path='official-topic')<>1;
INSERT INTO tmp_cy_orient_scoring_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT perms FROM sys_menu
  WHERE perms IN ('business:orientScoring:list','business:orientScoring:review') GROUP BY perms HAVING COUNT(*)>1);
INSERT INTO tmp_cy_orient_scoring_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT menu_id FROM sys_menu
  WHERE perms IN ('business:orientScoring:list','business:orientScoring:review') AND menu_type<>'F');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '计分申诉与争议查看',(SELECT menu_id FROM sys_menu WHERE menu_type='C' AND component='business/topic/index' AND path='official-topic'),
       99,'#','',1,0,'F','0','0','business:orientScoring:list','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:orientScoring:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '计分申诉与争议复核',(SELECT menu_id FROM sys_menu WHERE menu_type='C' AND component='business/topic/index' AND path='official-topic'),
       99,'#','',1,0,'F','0','0','business:orientScoring:review','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:orientScoring:review');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id FROM sys_role r JOIN sys_menu m
WHERE r.role_key='cy_operator' AND r.del_flag='0'
  AND m.menu_type='F' AND m.perms IN ('business:orientScoring:list','business:orientScoring:review')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);

-- 回读自检(事务模式只允许 DML,断言写成守卫插入:任一不成立即主键冲突,整批回滚)。
-- ① 两个 F 按钮各恰好一行，挂在官方主题 C 菜单下。
INSERT INTO tmp_cy_orient_scoring_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu f
  JOIN sys_menu p ON p.menu_id = f.parent_id AND p.menu_type='C' AND p.component='business/topic/index' AND p.path='official-topic'
  WHERE f.menu_type='F' AND f.perms IN ('business:orientScoring:list','business:orientScoring:review'))<>2;
-- ② 每个有效 cy_operator 角色都持有两个权限。
INSERT INTO tmp_cy_orient_scoring_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT 1 FROM sys_role r JOIN sys_menu m
  ON m.menu_type='F' AND m.perms IN ('business:orientScoring:list','business:orientScoring:review')
  WHERE r.role_key='cy_operator' AND r.del_flag='0'
    AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id));
-- ③ 除有效 cy_operator 外没有任何角色持有这两个权限(超管走代码放行，不入 sys_role_menu)。
INSERT INTO tmp_cy_orient_scoring_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT 1 FROM sys_role_menu rm
  JOIN sys_menu m ON m.menu_id = rm.menu_id AND m.perms IN ('business:orientScoring:list','business:orientScoring:review')
  JOIN sys_role r ON r.role_id = rm.role_id
  WHERE r.role_key <> 'cy_operator' OR r.del_flag <> '0');
DROP TEMPORARY TABLE tmp_cy_orient_scoring_menu_guard;
