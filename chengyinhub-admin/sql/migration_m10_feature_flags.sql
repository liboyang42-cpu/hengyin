-- ============================================================================
-- M10 灰度功能开关 · 初始化 sys_config 6 行(默认全关)
-- ----------------------------------------------------------------------------
-- 后端 /api/config/features 读取这些 key(默认 false)。新功能自营期一律默认关闭,
-- 按需在后台把对应 config_value 改 true 灰度放开。config_type=N(非系统内置,可删)。
-- 复用 RuoYi sys_config 的 Redis 缓存:后台改配置会清缓存即时生效。
--
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检;若 config_key 已存在请勿重复插入。
-- ============================================================================

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES
  ('功能开关-自玩票',   'feature.flag.selfPlay',    'false', 'N', 'admin', NOW(), 'M10 自玩票全链,默认关'),
  ('功能开关-点亮城市', 'feature.flag.citymap',     'false', 'N', 'admin', NOW(), 'M10 点亮城市+徽章图鉴,默认关'),
  ('功能开关-途中彩蛋', 'feature.flag.eggs',        'false', 'N', 'admin', NOW(), 'M10 NPC彩蛋池,默认关'),
  ('功能开关-随机掉落', 'feature.flag.drops',       'false', 'N', 'admin', NOW(), 'M10 随机奖励掉落,默认关'),
  ('功能开关-轨迹记录', 'feature.flag.trackRecord', 'false', 'N', 'admin', NOW(), 'M10 以走代编轨迹记录,默认关'),
  ('功能开关-XP预算',   'feature.flag.xpBudget',    'false', 'N', 'admin', NOW(), 'M10 XP预算与勋章分配,默认关');
