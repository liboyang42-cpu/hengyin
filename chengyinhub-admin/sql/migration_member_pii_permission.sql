-- 会员「看全量 PII」权限位（2026-07-16 · 续 PR #68）
-- 背景：后台会员列表/详情/导出此前把手机号、姓名明文直接序列化给前端,且 bcrypt 密码哈希
--       也随响应体直出。本轮在 UmsMemberController 加脱敏闸门:无下列权限者拿到的手机/姓名一律脱敏,
--       密码哈希则对谁都不返回。此文件只建「看全量」权限位,与 registration/order 同款。
--
-- ★失败方向是安全的:本脚本没跑 = 没人被授予 = 非超管一律只看脱敏值。
--   与「注册 SQL 没跑 = 功能静默消失」相反,这里没跑只会更保守,不会漏明文;但没跑就没人能被授予看全量。
--
-- ★超管持 *:*:* 天然匹配,始终看全量,故「列表不再出现明文」只能用非超管角色验收。
--
-- ★父菜单来源:仓库里没有 business:member:* 的 sys_menu 种子(历史上后台手工建的),
--   故按 perms 反查父菜单,查不到就不插,避免在菜单树根部留孤儿按钮。
--   查不到时请先确认后台确有「会员管理」菜单页,再重跑本脚本(幂等,可重复执行)。

SET @cy_member_menu = NULL;
SELECT menu_id INTO @cy_member_menu FROM sys_menu WHERE perms = 'business:member:list' AND menu_type = 'C' LIMIT 1;
SET @cy_member_menu = IFNULL(@cy_member_menu, (SELECT menu_id FROM sys_menu WHERE component = 'business/member/index' AND menu_type = 'C' LIMIT 1));

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '会员查看全量PII', @cy_member_menu, 90, '', '', '', '', 1, 0, 'F', '0', '0', 'business:member:pii', '#', 'admin', NOW(), '授予后会员列表/详情/导出可见手机与姓名全量,未授予只见脱敏值;密码哈希对谁都不返回'
FROM DUAL
WHERE @cy_member_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:member:pii') AS cy_exist_member);

-- 回读断言(回执 ≠ 观测):必须看到 1 行,否则父菜单没找到、权限位没建成,
-- 此时非超管一律只看脱敏值(安全但无法授予),请先在后台确认「会员管理」菜单页再重跑。
SELECT menu_id, menu_name, parent_id, perms FROM sys_menu WHERE perms = 'business:member:pii';
