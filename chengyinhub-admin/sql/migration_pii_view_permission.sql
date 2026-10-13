-- 后台 PII「看全量」权限位（2026-07-16）
-- 背景：报名/订单的列表、详情、导出此前把姓名/手机/邮箱/地址明文直接序列化给前端。
--       PII TypeHandler 只解决了「落库加密」，数据在 ORM 边界就已解密成明文，
--       出后端那一刻就是明文，只在 Vue 模板里掐字符挡不住 DevTools 和导出的 xlsx。
--       本次在 Controller 上加脱敏闸门：无下列权限者拿到的一律是脱敏值。
--
-- ★失败方向是安全的：本脚本没跑 = 没人被授予 = 非超管一律只看脱敏值。
--   与常见的「注册 SQL 没跑 = 功能静默消失、零告警」相反，这里没跑只会更保守，不会漏明文。
--
-- ★超管持 *:*:*，天然匹配这两个权限，始终看全量，无需也无法通过本脚本收回。
--   因此「列表不再出现明文」只能用非超管角色验收。
--
-- ★父菜单来源：仓库里从来没有 business:registration:* / business:order:* 的 sys_menu 种子，
--   这些行是历史上在后台「菜单管理」里手工建的。故本脚本按 perms 反查父菜单，查不到就不插，
--   避免在菜单树根部留下一个孤儿按钮。查不到时请先确认后台确有对应菜单页，再重跑本脚本。

-- 1) 报名：business:registration:pii
SET @cy_reg_menu = NULL;
SELECT menu_id INTO @cy_reg_menu FROM sys_menu WHERE perms = 'business:registration:list' AND menu_type = 'C' LIMIT 1;
SET @cy_reg_menu = IFNULL(@cy_reg_menu, (SELECT menu_id FROM sys_menu WHERE component = 'business/registration/index' AND menu_type = 'C' LIMIT 1));

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '报名查看全量PII', @cy_reg_menu, 90, '', '', '', '', 1, 0, 'F', '0', '0', 'business:registration:pii', '#', 'admin', NOW(), '授予后报名列表/详情/导出可见姓名手机邮箱地址全量，未授予只见脱敏值'
FROM DUAL
WHERE @cy_reg_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:registration:pii') AS cy_exist_reg);

-- 2) 订单：business:order:pii
SET @cy_order_menu = NULL;
SELECT menu_id INTO @cy_order_menu FROM sys_menu WHERE perms = 'business:order:list' AND menu_type = 'C' LIMIT 1;
SET @cy_order_menu = IFNULL(@cy_order_menu, (SELECT menu_id FROM sys_menu WHERE component = 'business/order/index' AND menu_type = 'C' LIMIT 1));

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '订单查看全量PII', @cy_order_menu, 90, '', '', '', '', 1, 0, 'F', '0', '0', 'business:order:pii', '#', 'admin', NOW(), '授予后订单列表/详情/导出可见收件人姓名电话地址与下单人手机全量，未授予只见脱敏值'
FROM DUAL
WHERE @cy_order_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:order:pii') AS cy_exist_order);

-- 3) 回读断言（回执 ≠ 观测）：必须看到两行，否则父菜单没找到、权限位没建成，
--    此时非超管一律只看脱敏值（安全但无法授予），请先在后台确认菜单页再重跑。
SELECT menu_id, menu_name, parent_id, perms FROM sys_menu WHERE perms IN ('business:registration:pii', 'business:order:pii');
