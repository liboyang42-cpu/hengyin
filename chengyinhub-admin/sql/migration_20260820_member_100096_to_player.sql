-- 把会员 100096(智能科技体验馆)从商家版切回玩家版(2026-08-20,用户当面裁决)。
--
-- 为什么只改两个字段:
--   `ums_member.role`(+ `user_type` 兜底)决定的是**默认视角**——前端落哪个首页/哪套 tab,
--   见 RoleServiceImpl.resolveRole:role 非空优先,空则 user_type==2 → merchant。
--   而 `member_capability` 决定的是**能做什么**(RoleServiceImpl.getPermission 按能力取并集),
--   跟视角无关。所以要「变成玩家版」,改视角这一层就够,不必动能力位。
--
-- ⚠️ 因此本文件**不动**下面这些,它们留着既无害又可逆:
--   · mms_merchant#10(status=1/account_status=1/未软删)——商家资料原样保留
--   · member_capability 的 merchant / club_leader 两行——商家能力仍在,需要时还能用
--   · cms_registration_merchant 4 条中标承接(主题 19「新时尚」/ 21「副本人生」)
--     两个主题都已在 2026-03-28 结束,没有在途履约
--
-- 2026-08-20 只读预检(生产)确认无资金牵连:
--   merchant_payable 全表 0 行、coop_settlement 全表 0 行、
--   oms_order 作为卖家 0 笔 / 作为买家 0 笔、自己发布的主题活动 0 个。
--
-- ★ 本文件**推翻** migration_fix_member_100096_merchant_identity.sql(2026-08-11):
--   那次是把 100096 从 role='club' / user_type=1 / 商家被软删的错乱态**修成商家态**,
--   因为当时它登录后落玩家首页、商家页白屏(四个身份判据互踢)。
--   这次是用户主动要玩家版,不是修 bug。差别在于:当初的病是**判据互相矛盾**
--   (user_type=1 说玩家、mms_merchant 又是启用商家、role 是第三种值),
--   现在 user_type=1 + role='player' 两条一致指向玩家,而 /api/user/info 那条
--   「userType=2 && 商家合格才认商家」的硬回退根本不会被触发 ⇒ 不会重演互踢。
--
-- 反向回滚(想切回商家版):
--   UPDATE ums_member SET user_type = 2, role = 'merchant' WHERE id = 100096;
--
-- 幂等:前置断言钉死「必须还是商家态」。跑过一次后重跑会在断言处停下——这是有意的,
--       身份改写不该被无意重放。

-- 前置断言:100096 必须仍是我 2026-08-20 读到的那个商家态,且商家资料未被软删。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM ums_member
         WHERE id = 100096 AND user_type = 2 AND role = 'merchant' AND del_flag = 0) = 1
   AND (SELECT COUNT(*) FROM mms_merchant
         WHERE member_id = 100096 AND del_flag = 0) = 1;

UPDATE ums_member
   SET user_type = 1,
       role = 'player',
       update_by = 'migration_20260820_member_100096_to_player',
       update_time = NOW()
 WHERE id = 100096 AND user_type = 2 AND role = 'merchant';

-- 回读断言:必须真的落成玩家态,且商家资料仍在(没被误伤)。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM ums_member
         WHERE id = 100096 AND user_type = 1 AND role = 'player') = 1
   AND (SELECT COUNT(*) FROM mms_merchant
         WHERE member_id = 100096 AND del_flag = 0 AND account_status = 1) = 1;
