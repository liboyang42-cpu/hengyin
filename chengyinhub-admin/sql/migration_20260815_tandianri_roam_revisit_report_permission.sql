-- 探店日 5-5：漫游复访报表独立只读权限。
-- 只准备，不在本任务执行；未执行时非超管无法访问，失败方向为 fail-closed。
-- 首版没有后台 UI，权限作为运营分析页下的功能位注册，不创建页面或 sys_job。

SET @cy_analytics_menu := (
    SELECT menu_id
    FROM sys_menu
    WHERE menu_type = 'C'
      AND (perms = 'business:analytics:list' OR component = 'business/analytics/index')
    ORDER BY CASE WHEN perms = 'business:analytics:list' THEN 0 ELSE 1 END, menu_id
    LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '漫游复访报表', @cy_analytics_menu, 20, '', '', 1, 0,
       'F', '0', '0', 'business:roamRevisitReport:list', '#', 'admin', NOW(),
       '探店日5-5只读报表权限；仅允许查询SHOP_REVISIT/ROAM_REVISIT 7/30/90日观察窗'
FROM dual
WHERE @cy_analytics_menu IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'business:roamRevisitReport:list'
  );

-- 执行后的对端回读必须为 1；0 表示父级未找到或权限行形状错误。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM sys_menu
WHERE perms = 'business:roamRevisitReport:list'
  AND parent_id = @cy_analytics_menu
  AND menu_type = 'F'
  AND status = '0';
