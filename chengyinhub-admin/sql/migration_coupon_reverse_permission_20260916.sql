-- 券核销撤销入口的后台权限位(2026-09-16)
--
-- 【类别】③ 注册数据(要在库上跑)。不是 DDL,但**不跑 = 撤销按钮只对超管存在**:
--   chengyinhub-ui 优惠券流水页(views/business/coupon/ledger.vue)的「撤销核销」按钮带
--   v-hasPermi('business:couponhistory:reverseRedeem'),
--   SmsCouponHistoryController#reverse 带同串 @PreAuthorize;
--   超管有 *:*:* 恒能看见,所以**验收必须用非超管账号**。
--
-- 【为什么需要这个入口】H039「拿错券 / 店员扫错券」:券核销从未有冲销实现
--   (全仓 grep SmsCouponHistoryServiceImpl 的 reverse/撤销/冲销 零命中),只能人工改库。
--   本次实现只开**平台后台**一个口(商家端不给,防自助刷单),权限点即本份注册的东西。
--   撤销会回滚券态(use_status 1→0,券恢复可用)、核销计数 -1、撤回仍待结算的分成明细,
--   并落不可变审计(谁撤的/为什么/哪条,写失败整体回滚)。
--
-- 【落库时机】jar + chengyinhub-ui 都上线之后(本份没有 DDL,与「DDL 先于 jar」相反)。
--   本份不落库的后果是保守的:按钮对非超管不显示、接口 403,不会漏权。
--
-- 【幂等】INSERT 按 NOT EXISTS(perms) 去重,重跑 0 行。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms = 'business:couponhistory:reverseRedeem';
--
-- 【风险】低。只动 sys_menu 一行;不自动授权(授权仍由管理员在「角色管理」里手工勾选)。
--
-- ======================= 只读预检 =======================
-- P1【必看】父菜单现状(仓库里 business:couponhistory:list 由
--   migration_p2_4_admin_ia_menu.sql 9.3 节注册为 F 型按钮,parent_id=2050 优惠券管理;
--   2050 又被 migration_menu_2050_coupon_to_supply_20260726.sql 改挂到供给保障目录):
--   SELECT menu_id, menu_name, menu_type, parent_id FROM sys_menu
--    WHERE perms = 'business:couponhistory:list';
--   · 查到 1 行 → 正常,反查它的 parent_id 作为本权限位的父级。
--   · 0 行 → 后台里没有优惠券流水权限;退回按 component='business/coupon/index' 反查
--     优惠券管理页;两者都查不到就不插且断言变红(不会静默插一个孤儿权限位)。

SET @cy_coupon_parent = NULL;
SELECT parent_id INTO @cy_coupon_parent FROM sys_menu
 WHERE perms = 'business:couponhistory:list' AND menu_type = 'F' LIMIT 1;
SET @cy_coupon_parent = IFNULL(@cy_coupon_parent, (
    SELECT menu_id FROM sys_menu
     WHERE perms = 'business:coupon:list' AND menu_type = 'C' LIMIT 1));

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '撤销券核销', @cy_coupon_parent, 91, '', '', '', '', 1, 0, 'F', '0', '0',
       'business:couponhistory:reverseRedeem', '#', 'admin', NOW(),
       '平台侧唯一纠错入口:撤销券核销(券态回滚/计数回退/待结算分成撤回/落不可变审计);原因必填;商家端不给'
FROM DUAL
WHERE @cy_coupon_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu
                                  WHERE perms = 'business:couponhistory:reverseRedeem') AS cy_exist_coupon_reverse);

-- 执行后自检(可红):
--   ① 权限位必须存在且是 F 型按钮(同 perms 只此一行);
--   ② 必须挂在优惠券父级下,不能是孤儿(parent_id=0);
--      父级候选与上面 INSERT 的两条反查口径一致(流水权限优先、优惠券管理页兜底)。
-- runner: assert=scalar-true
SELECT (
    SELECT COUNT(*) FROM sys_menu WHERE perms = 'business:couponhistory:reverseRedeem' AND menu_type = 'F'
) = 1
AND (
    SELECT COUNT(*) FROM sys_menu m
     WHERE m.perms = 'business:couponhistory:reverseRedeem'
       AND m.menu_type = 'F'
       AND m.parent_id IN (
           SELECT parent_id FROM sys_menu
            WHERE perms = 'business:couponhistory:list' AND menu_type = 'F'
           UNION
           SELECT menu_id FROM sys_menu
            WHERE perms = 'business:coupon:list' AND menu_type = 'C'
       )
) = 1
AND (
    SELECT COUNT(*) FROM sys_menu
     WHERE perms = 'business:couponhistory:reverseRedeem' AND parent_id = 0
) = 0;
