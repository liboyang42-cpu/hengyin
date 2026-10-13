-- ================= migration_merchant_npc_chat_flag_20260905.sql =================
SELECT 'migration_merchant_npc_chat_flag_20260905.sql' AS running;
-- 门店 NPC 对话开关。默认 **false**。
--
-- 【为什么必须是一个新开关,不能复用 roamNpcChat】
--   roamNpcChat 是**漫游自由追问**那一轨,被 Phase 0 合规签字阻断,
--   而且 NpcFeatureFlags.chatOn() 在无形陪伴 V1 里**硬编码 return false** ——
--   连配置都读不到,运营在后台改它零效果。
--   门店对话是**另一件事**:范围只有商家自己的店铺信息、事实包由商家自审、
--   受众是主动点开店铺形象的玩家。它要走自己的合规评估、自己的 Gate。
--   ⚠️ 复用旧键 = 把两个合规范围不同的功能绑在一个开关上,
--      将来任一方要开都会把另一方一起放出去。
--
-- 【类别】③ 迁移/注册数据(要在库上跑)。**不跑的后果**:
--   ApiConfigController 读不到该键 → flagOn(null) = false → 端点恒 403。
--   也就是「不跑 = 功能默认关着」,**这是安全的失败方向**,不是零告警型静默故障。
--   ⇒ 本份可以在 jar 之后跑;但要开功能就必须先跑。
--
-- 【幂等】NOT EXISTS 幂等。⚠️ 行已存在时**不会更新 remark**(照抄
--   migration_roam_npc_feature_flags.sql 踩过的那条:旧 remark 会留着骗人)。
--
-- 【可逆】DELETE FROM sys_config WHERE config_key = 'feature.flag.merchantNpcChat';
--   删掉后端点回到 403,与从没跑过等价。
--
-- 【风险】—— **低**。只往 sys_config 插一行,值为 false;插完功能仍是关的。
--   真正的风险动作是**把它改成 true**,那一步由人在后台做,且必须在合规签字之后。

-- 只读预检(期望 0 行 = 全新)
SELECT config_key, config_value, remark FROM sys_config
 WHERE config_key = 'feature.flag.merchantNpcChat';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-门店NPC对话', 'feature.flag.merchantNpcChat', 'false', 'N', 'admin', NOW(),
       '门店分身对话(scope=merchant)。与漫游 roamNpcChat 是两轨,合规范围不同,不可互相代替。开之前需门店对话专项合规签字'
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.merchantNpcChat'
);

-- 回读断言:期望一行,值为 false
SELECT config_key, config_value FROM sys_config
 WHERE config_key = 'feature.flag.merchantNpcChat';
