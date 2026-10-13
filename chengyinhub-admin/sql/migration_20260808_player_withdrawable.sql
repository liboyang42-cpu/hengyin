-- 玩家开放提现(用户 2026-08-08 拍板)。
--
-- 现状:player 的 withdrawable=0,而 player 的 can_create_theme=1、max_themes=2 —— 也就是
-- 普通玩家**能发主题、也能真拿到钱进余额**(payoutDueCoopSettlements 给 merchantId 加余额时
-- 不分 payeeType,initiator 保底/毛利照样入账),但 ApiWithdrawalController 会以
-- 「当前身份不支持提现」硬格挡。结果是玩家看得到一笔提不出来的钱,界面上还不解释为什么。
--
-- 这条把 player 也放开,三种角色统一可提现。⚠️ 这是资金出口的权限扩面,不是 UI 改动:
-- 放开后任何注册用户只要余额 > 0 即可发起提现申请(仍走 UmsMemberWithdrawalService 的
-- 金额校验 + 后台审核,不是直接出金)。
--
-- 预检(执行前先跑,确认当前值确实是 0,别改到已经是 1 的行上):
--   SELECT id, role, can_create_theme, withdrawable FROM user_permission ORDER BY id;
--   -- 2026-08-08 实测生产:player=0 / club=1 / merchant=1

UPDATE user_permission SET withdrawable = 1 WHERE role = 'player' AND withdrawable = 0;

-- 复核(执行后跑,三行都应为 1):
--   SELECT role, withdrawable FROM user_permission ORDER BY id;
