-- ============================================================================
-- Rollback for migration_20261125_admin_r3_perms.sql
-- ============================================================================

DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
WHERE m.menu_type = 'F' AND (m.perms LIKE 'business:rewarddrop:%' OR m.perms LIKE 'business:taskpool:%');
DELETE FROM sys_menu WHERE menu_type = 'F' AND (perms LIKE 'business:rewarddrop:%' OR perms LIKE 'business:taskpool:%');
