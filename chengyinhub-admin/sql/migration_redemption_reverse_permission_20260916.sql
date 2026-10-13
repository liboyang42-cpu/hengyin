-- 错核撤销入口的后台权限位(2026-09-16)
--
-- 【类别】③ 注册数据(要在库上跑)。不是 DDL,但**不跑 = 撤销按钮只对超管存在**:
--   Chengyinhub-ui 的按钮带 v-hasPermi('business:registration:reverseRedeem'),
--   CmsRegistrationController#reverseRedemption 带同串 @PreAuthorize;
--   超管有 *:*:* 恒能看见,所以**验收必须用非超管账号**。
--
-- 【为什么需要这个入口】H 套审查 H038/H039:店员把票/权益核错了,平台/商家两端都没有撤销入口,
--   内部 PerkLedgerServiceImpl.reverseRedemption 实现与测试齐全却零调用方 —— 只能人工改库。
--   本次接线只开**平台后台**一个口(商家端不给,防自助刷单),权限点即本份注册的东西。
--
-- 【落库时机】jar + chengyinhub-ui 都上线之后(本份没有 DDL,与「DDL 先于 jar」相反)。
--   本份不落库的后果是保守的:按钮对非超管不显示、接口 403,不会漏权。
--
-- 【幂等】INSERT 按 NOT EXISTS(perms) 去重,重跑 0 行。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms = 'business:registration:reverseRedeem';
--
-- 【风险】低。只动 sys_menu 一行;授权仍由管理员在「角色管理」里手工勾选。
--
-- ======================= 只读预检 =======================
-- P1【必看】父菜单现状(仓库里从来没有 business:registration:* 的 sys_menu 种子 ——
--          这些行是历史上在后台「菜单管理」里手工建的,故本脚本按 perms 反查父菜单):
--   SELECT menu_id, menu_name, menu_type, component FROM sys_menu
--    WHERE perms = 'business:registration:list' OR component = 'business/registration/index';
--   · 查到 menu_type='C' 的行 → 正常,全执行。
--   · 0 行 → 后台里根本没有报名菜单页;先确认它确实存在再重跑,否则下面的断言会红
--     (不会静默插一个孤儿权限位)。

SET @cy_reg_menu = NULL;
SELECT menu_id INTO @cy_reg_menu FROM sys_menu
 WHERE perms = 'business:registration:list' AND menu_type = 'C' LIMIT 1;
SET @cy_reg_menu = IFNULL(@cy_reg_menu, (
    SELECT menu_id FROM sys_menu
     WHERE component = 'business/registration/index' AND menu_type = 'C' LIMIT 1));

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '撤销核销', @cy_reg_menu, 91, '', '', '', '', 1, 0, 'F', '0', '0',
       'business:registration:reverseRedeem', '#', 'admin', NOW(),
       '平台侧唯一纠错入口:撤销按章核销(回补商家配额/退回玩家权益/冲正平台应付/落审计);原因必填;商家端不给'
FROM DUAL
WHERE @cy_reg_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu
                                  WHERE perms = 'business:registration:reverseRedeem') AS cy_exist_reverse);

-- 执行后自检(可红):
--   ① 权限位必须存在且是 F 型按钮(同 perms 只此一行);
--   ② 必须挂在报名菜单下,不能是孤儿(parent_id=0);
--      父级候选与上面 INSERT 的两条反查口径一致(perms 优先、component 兜底)。
-- runner: assert=scalar-true
SELECT (
    SELECT COUNT(*) FROM sys_menu WHERE perms = 'business:registration:reverseRedeem' AND menu_type = 'F'
) = 1
AND (
    SELECT COUNT(*) FROM sys_menu m
     WHERE m.perms = 'business:registration:reverseRedeem'
       AND m.menu_type = 'F'
       AND m.parent_id IN (
           SELECT menu_id FROM sys_menu
            WHERE perms = 'business:registration:list' AND menu_type = 'C'
           UNION
           SELECT menu_id FROM sys_menu
            WHERE component = 'business/registration/index' AND menu_type = 'C')
) = 1;
