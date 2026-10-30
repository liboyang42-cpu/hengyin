-- ============================================================================
-- 商家身份解锁「分支 / 条件玩法」与「博弈机制(计时/排行/多人)」(2026-10-30,实走 B2)
--
-- 现状(2026-10-30 生产只读回读):user_permission 中 role='merchant'、membership_level IS NULL
-- 的默认档 can_branch=0、can_game_mechanic=0。平台为商家准备的 A8/B27 商家互动玩法(42 款,
-- create_by LIKE 'seed_merchant_game%')几乎都用到分支或计时/排行,CapabilityGuard 因此拒绝商家
-- 采用,生产至今只有 1 次商家采用。俱乐部默认档已是 1/1。
--
-- 只读预检(执行前先跑，期望恰 1 行且两列都为 0):
--   SELECT role, membership_level, can_branch, can_game_mechanic
--     FROM user_permission WHERE role = 'merchant' AND membership_level IS NULL;
--
-- 幂等：WHERE 钉住 role='merchant' + membership_level IS NULL;重跑不改行。纯 DML,不改表结构。
-- 回滚：同样 WHERE 把两列改回 0。
-- 按 chengyin-db-migrate 流程只读预检 + 用户确认后由人工执行。
-- ============================================================================

UPDATE user_permission
   SET can_branch = 1,
       can_game_mechanic = 1
 WHERE role = 'merchant'
   AND membership_level IS NULL;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok
  FROM user_permission
 WHERE role = 'merchant' AND membership_level IS NULL AND can_branch = 1 AND can_game_mechanic = 1;
