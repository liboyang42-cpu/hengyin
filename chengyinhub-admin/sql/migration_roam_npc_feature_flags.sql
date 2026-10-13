-- ================= migration_roam_npc_feature_flags.sql =================
SELECT 'migration_roam_npc_feature_flags.sql' AS running;
-- 漫游 AI NPC 双开关(补充方案 §13):默认全关;Event/Chat 独立;
-- 服务端另有非法组合归一(Event=false 时 Chat 强制 false,见 ApiConfigController.normalizeNpcFlags)。
-- 依赖:sys_config(RuoYi 基础表)。自然键 = config_key。
--
-- 落库前只读预检(2026-07-16 已跑,结果 0 行 → 走新建分支,幂等分支不触发):
--   SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'feature.flag.roamNpc%';
--
-- ⚠️ roamNpcChat 不只是「前端不显示入口」:ApiAiNpcController.chat 会读它,非 true 即拒(Task 5.3)。
--    ⚠️ 别写成「开了会绕过频控」—— PR #75 已把 NpcChatService 收编进 AiGatewayService
--    (频控 + 内容安全 + 审计日志都在网关里),那个洞堵上了。开它的真实风险是**合规**:
--    Chat 轨被 Phase 0 合规签字阻断,签字前不该开放。

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-漫游NPC事件气泡', 'feature.flag.roamNpcEvent', 'false', 'N', 'admin', NOW(), '预审事件话术,零模型调用,默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.roamNpcEvent');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-漫游NPC自由追问', 'feature.flag.roamNpcChat', 'false', 'N', 'admin', NOW(), 'Chat轨 No-Go:Phase0 合规签字未过,签字前禁开。端点已有闸门(非true即拒)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.roamNpcChat');

-- ⚠️ 上面是 NOT EXISTS 幂等插入:行已存在时**不会更新 remark**。
--    若生产已落过旧 remark(含已被 PR #75 证伪的「绕过频控」说法),需单独 UPDATE 纠正 —— 本次已做。

-- 回读断言:期望两行,值均为 false
SELECT config_key, config_value FROM sys_config WHERE config_key IN ('feature.flag.roamNpcEvent','feature.flag.roamNpcChat');
