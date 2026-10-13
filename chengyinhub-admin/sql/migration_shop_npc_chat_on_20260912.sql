-- ================= migration_shop_npc_chat_on_20260912.sql =================
SELECT 'migration_shop_npc_chat_on_20260912.sql' AS running;
-- 门店分身对话独立开关:本轮提审要求审核员点得到就必须能问一句。
-- ⚠️ 不复用 feature.flag.roamNpcChat / NpcFeatureFlags.chatOn() —— 那条写死 false(合规 No-Go)。
-- 依赖:sys_config(RuoYi 基础表)。自然键 = config_key。
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config WHERE config_key = 'feature.flag.shopNpcChat';
-- 没有行或值不是 true → 执行本文件;已是 true → 幂等,UPDATE 仍写成 true。

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-门店分身对话', 'feature.flag.shopNpcChat', 'true', 'N', 'admin', NOW(),
       '自由探索店铺分身 /api/ai/npc/shop-chat;与漫游自由聊无关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.shopNpcChat');

UPDATE sys_config
SET config_value = 'true',
    config_name = '功能开关-门店分身对话',
    remark = '自由探索店铺分身 /api/ai/npc/shop-chat;与漫游自由聊无关'
WHERE config_key = 'feature.flag.shopNpcChat'
  AND (config_value IS NULL OR LOWER(config_value) <> 'true');

-- 回读断言:期望一行,值为 true。漫游 chat 键不得被本文件改动。
SELECT config_key, config_value
FROM sys_config
WHERE config_key IN ('feature.flag.shopNpcChat', 'feature.flag.roamNpcChat');
