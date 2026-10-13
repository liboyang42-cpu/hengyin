-- 主题内容归属改判独立高风险权限
--
-- 顺序：本文件名中的 02 是有意的；必须在 migration_topic_source_menu_20260722.sql
-- 的 A0/A1/A2 回读成功后执行。依赖的 OfficialTopic 菜单不存在时，本迁移 fail-closed 不写权限。
--
-- 类别：③ 数据迁移（sys_menu）。不跑不会报错，但非超管无法被显式授予内容归属改判权限。
-- 前置：先执行 _precheck_topic_content_slot_permission_20260722.sql。
--
-- 为什么不能复用 business:topic:edit：CONTENT 槽决定票款归属；普通编辑可改内容，不应自动获得
-- 改钱归属的能力。超级管理员拥有通配权限；非超管必须由授权负责人在迁移后单独授予本 F 权限。
--
-- 风险：低（仅菜单 DML），但权限放宽会直接影响票款归属，因此不复制既有 topic:edit 角色映射。

SET @topicMenuCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND route_name = 'OfficialTopic'
);
SET @topicMenu := (
    SELECT MIN(menu_id)
    FROM sys_menu
    WHERE menu_type = 'C'
      AND component = 'business/topic/index'
      AND route_name = 'OfficialTopic'
);

SET @contentSlotPermissionExactCount := (
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
SET @contentSlotPermissionConflictCount := (
    SELECT COUNT(1)
    FROM sys_menu
    WHERE perms = 'business:topic:content-slot:edit'
      AND NOT (
          parent_id <=> @topicMenu
          AND menu_type = 'F'
          AND path <=> ''
          AND component <=> ''
          AND `query` <=> ''
          AND route_name <=> ''
      )
);
SET @migrationBlockCount := IF(@topicMenuCount <> 1, 1, 0)
    + @contentSlotPermissionConflictCount
    + IF(@contentSlotPermissionExactCount > 1, 1, 0);

-- 仅在官方主题 C 菜单唯一、且同名权限未被别处占用时插入。重复执行不会产生重复 F 菜单。
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '主题内容归属改判', @topicMenu, 90, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:topic:content-slot:edit', '#', 'migration', NOW(),
       '高风险：CONTENT 槽决定票款归属；仅授权负责人批准的角色可执行'
FROM dual
WHERE @migrationBlockCount = 0
  AND @contentSlotPermissionExactCount = 0;

-- 初次执行前的计数必为 0，不能拿它充当写后回读。
SET @contentSlotPermissionExactCount := (
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

-- 故意不写 sys_role_menu：绝不把普通 topic:edit 角色自动扩大为可改票款归属。
-- 非超管授权须经负责人审批后，在菜单管理中为目标角色单独关联 @contentSlotPermissionMenu；
-- 授权后让对应账号重新登录，并以该账号验证拒绝/允许两条路径。

-- ============================================================================
-- 回读断言（必跑；写入成功不等于权限归属符合最小授权）
-- ============================================================================
-- A0 预期 topic_menu_count=1、migration_block_count=0、permission_exact_count=1。
SELECT @topicMenuCount AS topic_menu_count,
       @migrationBlockCount AS migration_block_count,
       @contentSlotPermissionExactCount AS permission_exact_count;

-- A1 预期仅一行 F 权限，且 parent_id 指向 OfficialTopic；perms 必须精确匹配接口注解。
SELECT menu_id, menu_name, parent_id, menu_type, perms, status, remark
FROM sys_menu
WHERE menu_id = @contentSlotPermissionMenu;

-- A2 审核显式授权对象。预期不会因本迁移新增任何 sys_role_menu 行；非超管授权只应由审批后出现。
SELECT rm.role_id, role.role_name, role.role_key
FROM sys_role_menu rm
JOIN sys_role role ON role.role_id = rm.role_id
WHERE rm.menu_id = @contentSlotPermissionMenu
ORDER BY rm.role_id;

-- 回滚见 bak_topic_content_slot_permission_20260722_rollback.sql。回滚前先下线接口并确认无待处理归属改判。
